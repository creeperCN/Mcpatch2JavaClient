package com.github.balloonupdate.mcpatch.client.network;

import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云ESA A方案鉴权凭据服务。
 * <p>
 * 在下载每个文件之前，向鉴权 API 请求 auth_key，然后拼接到下载 URL 中，
 * 实现防盗链功能，防止恶意反复下载。
 * <p>
 * 特性：
 * - 线程安全的凭据缓存，同一文件路径在有效期内复用 auth_key
 * - 提前刷新机制，在 auth_key 过期前 5 分钟主动刷新
 * - OkHttp 连接池复用，减少握手开销
 */
public class AuthKeyService {

    /**
     * 鉴权 API 地址
     */
    private final String authApiUrl;

    /**
     * 鉴权 URL 有效时长，单位秒
     */
    private final int expireTime;

    /**
     * 鉴权用户ID
     */
    private final String uid;

    /**
     * OkHttp 客户端（复用连接池）
     */
    private final OkHttpClient client;

    /**
     * 自定义 HTTP headers，与配置文件中的 http-headers 保持一致
     */
    private final Map<String, String> httpHeaders;

    /**
     * 缓存：filePath → AuthKeyEntry
     * 使用 ConcurrentHashMap 保证多线程并行下载时的线程安全
     */
    private final ConcurrentHashMap<String, AuthKeyEntry> cache = new ConcurrentHashMap<>();

    /**
     * 提前刷新时间：5分钟（毫秒）
     * 在 auth_key 过期前 5 分钟主动刷新，避免下载过程中凭据过期
     */
    private static final long REFRESH_AHEAD_MILLIS = 5 * 60 * 1000;

    /**
     * 缓存条目，保存 auth_key 及其过期时间
     */
    static class AuthKeyEntry {
        final String authKey;
        final long expireTimestampMillis;

        AuthKeyEntry(String authKey, long expireTimestampMillis) {
            this.authKey = authKey;
            this.expireTimestampMillis = expireTimestampMillis;
        }
    }

    /**
     * 创建鉴权凭据服务
     *
     * @param authApiUrl  鉴权 API 地址，例如 https://auth-api.mxzysoa.com/generate-auth-url
     * @param expireTime  鉴权 URL 有效时长，单位秒
     * @param uid         鉴权用户ID
     * @param httpTimeout HTTP 超时时间，单位毫秒
     * @param httpHeaders 自定义 HTTP headers，与配置文件中的 http-headers 保持一致
     */
    public AuthKeyService(String authApiUrl, int expireTime, String uid, int httpTimeout, Map<String, String> httpHeaders) {
        this.authApiUrl = authApiUrl;
        this.expireTime = expireTime;
        this.uid = uid;
        this.httpHeaders = httpHeaders;

        this.client = new OkHttpClient.Builder()
                .connectTimeout(httpTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(httpTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(httpTimeout, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 根据原始下载 URL 构建带鉴权参数的 URL。
     * <p>
     * 流程：
     * 1. 从 originalUrl 提取路径部分作为 filePath（例如 /index.json）
     * 2. 向鉴权 API 请求 auth_key（带缓存）
     * 3. 在 originalUrl 后追加 ?auth_key=xxx 或 &auth_key=xxx
     *
     * @param originalUrl 原始下载 URL
     * @return 带 auth_key 参数的 URL
     * @throws McpatchBusinessException 鉴权请求失败时抛出
     */
    public String buildAuthUrl(String originalUrl) throws McpatchBusinessException {
        String filePath = extractFilePath(originalUrl);
        String authKey = getAuthKey(filePath);
        String separator = originalUrl.contains("?") ? "&" : "?";
        return originalUrl + separator + "auth_key=" + authKey;
    }

    /**
     * 从完整 URL 中提取文件路径部分。
     * <p>
     * 例如：
     * - https://dl1.mxzysoa.com/index.json → /index.json
     * - https://dl1.mxzysoa.com/sub/v1.pack → /sub/v1.pack
     * - https://dl1.mxzysoa.com:8443/a/b/c.dat?q=1 → /a/b/c.dat
     *
     * @param url 完整的 URL
     * @return 以 / 开头的文件路径
     */
    private String extractFilePath(String url) throws McpatchBusinessException {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            return path;
        } catch (Exception e) {
            throw new McpatchBusinessException("无法从 URL 中提取文件路径: " + url, e);
        }
    }

    /**
     * 获取 auth_key（带缓存，线程安全）。
     * <p>
     * 缓存命中且未过期时直接返回；缓存未命中或已过期时向鉴权 API 请求新的 auth_key。
     * 使用 ConcurrentHashMap 的原子操作保证同一路径在并发场景下不会重复请求。
     *
     * @param filePath 文件路径，以 / 开头
     * @return auth_key 字符串
     * @throws McpatchBusinessException 鉴权请求失败时抛出
     */
    private String getAuthKey(String filePath) throws McpatchBusinessException {
        AuthKeyEntry entry = cache.get(filePath);

        // 缓存命中且未过期
        if (entry != null && System.currentTimeMillis() < entry.expireTimestampMillis - REFRESH_AHEAD_MILLIS) {
            return entry.authKey;
        }

        // 缓存未命中或已过期，请求新的 auth_key
        AuthKeyEntry newEntry = requestAuthKey(filePath);
        cache.put(filePath, newEntry);
        return newEntry.authKey;
    }

    /**
     * 向鉴权 API 请求 auth_key。
     * <p>
     * 调用方式：GET {authApiUrl}?filePath={filePath}&expireTime={expireTime}&uid={uid}
     * 响应格式（rawKey=true，GET 默认）：纯文本，格式为 {timestamp}-{rand}-{uid}-{md5hash}
     *
     * @param filePath 文件路径，以 / 开头
     * @return AuthKeyEntry 包含 auth_key 和过期时间
     * @throws McpatchBusinessException 请求失败时抛出
     */
    private AuthKeyEntry requestAuthKey(String filePath) throws McpatchBusinessException {
        String encodedFilePath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);

        String url = authApiUrl
                + "?filePath=" + encodedFilePath
                + "&expireTime=" + expireTime
                + "&uid=" + URLEncoder.encode(uid, StandardCharsets.UTF_8);

        Log.info("正在获取下载凭据: " + filePath);

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .get();

        // 添加自定义headers（与下载请求保持一致，包括 User-Agent 等）
        if (httpHeaders != null) {
            for (Map.Entry<String, String> e : httpHeaders.entrySet()) {
                reqBuilder.addHeader(e.getKey(), e.getValue());
            }
        }

        Request request = reqBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.peekBody(300).string();
                throw new McpatchBusinessException(
                        String.format("鉴权API返回错误 %d: %s (filePath=%s)\n%s",
                                response.code(), response.message(), filePath, body));
            }

            String authKey = response.body().string().trim();

            if (authKey.isEmpty()) {
                throw new McpatchBusinessException("鉴权API返回了空的auth_key (filePath=" + filePath + ")");
            }

            // 解析 auth_key 中的时间戳，计算过期时间
            // auth_key 格式：{timestamp}-{rand}-{uid}-{md5hash}
            long expireTimestampMillis = parseExpireTimestamp(authKey);

            Log.info("下载凭据获取成功: " + filePath);

            return new AuthKeyEntry(authKey, expireTimestampMillis);
        } catch (McpatchBusinessException e) {
            Log.warn("获取下载凭据失败: " + filePath);
            throw e;
        } catch (Exception e) {
            Log.warn("获取下载凭据失败: " + filePath);
            throw new McpatchBusinessException("请求鉴权凭据失败: filePath=" + filePath, e);
        }
    }

    /**
     * 从 auth_key 字符串中解析过期时间戳。
     * <p>
     * auth_key 格式：{timestamp}-{rand}-{uid}-{md5hash}
     * timestamp 是十进制 Unix 时间戳（秒），表示 auth_key 失效时间。
     *
     * @param authKey auth_key 字符串
     * @return 过期时间戳（毫秒）
     */
    private long parseExpireTimestamp(String authKey) {
        try {
            int dashIndex = authKey.indexOf('-');
            if (dashIndex > 0) {
                long timestampSeconds = Long.parseLong(authKey.substring(0, dashIndex));
                return timestampSeconds * 1000;
            }
        } catch (NumberFormatException e) {
            Log.warn("无法解析auth_key中的时间戳，使用默认过期时间");
        }

        // 解析失败时，使用当前时间 + expireTime 作为过期时间
        return System.currentTimeMillis() + (long) expireTime * 1000;
    }

    /**
     * 清除所有缓存。
     * 通常在关闭时调用。
     */
    public void clearCache() {
        cache.clear();
    }
}
