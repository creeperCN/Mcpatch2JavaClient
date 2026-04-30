package com.github.balloonupdate.mcpatch.client.network;

import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 云控配置拉取服务
 * 从 Mcpatch 云控后端拉取远程 YAML 配置，替代本地 mcpatch.yml
 *
 * 支持:
 * - API Key 鉴权 (Bearer Token)
 * - HMAC-SHA256 请求签名
 * - RSA-SHA256 响应验签
 * - AES-128-CBC 响应解密
 * - 云控返回的 X-Config-Version 版本号
 */
public class CloudConfigService {

    /**
     * 云控服务器地址 (例: https://cloud.example.com)
     */
    private final String serverUrl;

    /**
     * API Key (可选，用于鉴权)
     */
    private final String apiKey;

    /**
     * HMAC 密钥 (可选，用于请求签名)
     */
    private final String hmacSecret;

    /**
     * AES 密钥 (可选，十六进制字符串，用于解密响应)
     */
    private final String aesKeyHex;

    /**
     * RSA 公钥 (可选，PEM 格式，用于验签)
     */
    private final String rsaPublicKey;

    /**
     * HTTPS 连接超时（毫秒）
     */
    private final int timeout;

    /**
     * 上次成功拉取的配置版本号
     */
    private String lastConfigVersion = null;

    private final OkHttpClient httpClient;

    public CloudConfigService(String serverUrl, String apiKey, String hmacSecret,
                              String aesKeyHex, String rsaPublicKey, int timeout) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.hmacSecret = hmacSecret;
        this.aesKeyHex = aesKeyHex;
        this.rsaPublicKey = rsaPublicKey;
        this.timeout = timeout;

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build();
    }

    /**
     * 从云控后端拉取配置
     * @return 云控返回的 YAML 配置文本
     * @throws McpatchBusinessException 拉取失败时
     */
    public String fetchConfig() throws McpatchBusinessException {
        String url = normalizeUrl(serverUrl) + "/api/client?encrypt=true";

        Request.Builder reqBuilder = new Request.Builder().url(url);

        // 添加 API Key 鉴权头
        if (apiKey != null && !apiKey.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        // 添加 HMAC 签名头
        if (hmacSecret != null && !hmacSecret.isEmpty()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String signature = generateHMAC(timestamp, "/api/client");
            reqBuilder.addHeader("X-Timestamp", String.valueOf(timestamp));
            reqBuilder.addHeader("X-Signature", signature);
        }

        String responseBody;
        String configVersion = null;
        String signatureFromServer = null;

        try (Response rsp = httpClient.newCall(reqBuilder.build()).execute()) {
            int code = rsp.code();

            if (code != 200) {
                String body = rsp.peekBody(500).string();
                throw new McpatchBusinessException(
                    String.format("云控服务器返回 HTTP %d: %s", code, body));
            }

            // 读取响应头中的版本号和签名
            configVersion = rsp.header("X-Config-Version");
            signatureFromServer = rsp.header("X-Config-Signature");
            String aesIv = rsp.header("X-AES-IV");
            String encryptionAlgo = rsp.header("X-Encryption-Algorithm");

            // 读取响应体
            responseBody = rsp.body().string();

            // AES 解密（如果响应是加密的）
            if ("AES-128-CBC".equals(encryptionAlgo) && aesKeyHex != null && !aesKeyHex.isEmpty() && aesIv != null) {
                Log.info("云控响应已加密，正在解密...");
                responseBody = aesDecrypt(responseBody, aesKeyHex, aesIv);
            }

        } catch (IOException e) {
            throw new McpatchBusinessException("无法连接云控服务器: " + serverUrl, e);
        }

        // RSA 验签（如果提供了公钥且服务端返回了签名）
        if (rsaPublicKey != null && !rsaPublicKey.isEmpty() && signatureFromServer != null) {
            if (!verifyRSASignature(responseBody, signatureFromServer)) {
                Log.warn("云控配置 RSA 签名验证失败！配置可能被篡改。");
            } else {
                Log.info("云控配置 RSA 签名验证通过");
            }
        }

        if (configVersion != null) {
            lastConfigVersion = configVersion;
            Log.info("云控配置版本: v" + configVersion);
        }

        return responseBody;
    }

    /**
     * 获取上次成功拉取的配置版本号
     */
    public String getLastConfigVersion() {
        return lastConfigVersion;
    }

    // ============ HMAC-SHA256 签名 ============

    private String generateHMAC(long timestamp, String requestPath) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec =
                new javax.crypto.spec.SecretKeySpec(hmacSecret.getBytes("UTF-8"), "HmacSHA256");
            mac.init(keySpec);

            // 签名内容: timestamp + apiKey + requestPath
            String signContent = timestamp + "|" + (apiKey != null ? apiKey : "") + "|" + requestPath;
            byte[] hash = mac.doFinal(signContent.getBytes("UTF-8"));

            // 转 hex
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.warn("HMAC 签名生成失败: " + e.getMessage());
            return "";
        }
    }

    // ============ RSA-SHA256 验签 ============

    private boolean verifyRSASignature(String data, String signatureBase64) {
        try {
            // 去除 PEM 头尾和换行
            String pem = rsaPublicKey
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

            byte[] keyBytes = java.util.Base64.getDecoder().decode(pem);
            java.security.spec.X509EncodedKeySpec keySpec =
                new java.security.spec.X509EncodedKeySpec(keyBytes);
            java.security.KeyFactory factory = java.security.KeyFactory.getInstance("RSA");
            java.security.PublicKey publicKey = factory.generatePublic(keySpec);

            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(data.getBytes("UTF-8"));

            byte[] signatureBytes = java.util.Base64.getDecoder().decode(signatureBase64);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            Log.warn("RSA 验签过程出错: " + e.getMessage());
            return false;
        }
    }

    // ============ AES-128-CBC 解密 ============

    private String aesDecrypt(String encryptedBase64, String keyHex, String ivBase64) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");

            byte[] keyBytes = hexToBytes(keyHex);
            javax.crypto.spec.SecretKeySpec keySpec =
                new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");

            byte[] ivBytes = java.util.Base64.getDecoder().decode(ivBase64);
            javax.crypto.spec.IvParameterSpec ivSpec =
                new javax.crypto.spec.IvParameterSpec(ivBytes);

            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] encryptedBytes = java.util.Base64.getDecoder().decode(encryptedBase64);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            return new String(decryptedBytes, "UTF-8");
        } catch (Exception e) {
            Log.warn("AES 解密失败: " + e.getMessage());
            return encryptedBase64;
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String normalizeUrl(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
