package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * 客户端更新下载器
 */
public class SelfUpdateDownloader {
    /**
     * 临时更新文件名
     */
    public static final String UPDATE_TEMP_FILE = "mcpatch-update-new.jar";

    /**
     * 下载新版本到临时位置
     * @param downloadUrl 下载地址
     * @param expectedChecksum 预期的校验值
     * @return 下载后的文件路径
     */
    public static Path downloadNewVersion(String downloadUrl, String expectedChecksum) throws Exception {
        Log.info("正在下载客户端更新...");

        Path tempFile = getUpdateTempFile();

        // 确保父目录存在
        Files.createDirectories(tempFile.getParent());

        // 删除旧的临时文件
        if (Files.exists(tempFile)) {
            Files.delete(tempFile);
        }

        // 下载文件
        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(300000); // 5分钟超时

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("下载失败: HTTP " + conn.getResponseCode());
        }

        long contentLength = conn.getContentLengthLong();
        Log.debug("文件大小: " + contentLength + " bytes");

        // 下载并计算校验值
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream input = conn.getInputStream();
             OutputStream output = Files.newOutputStream(tempFile)) {

            byte[] buffer = new byte[64 * 1024];
            long downloaded = 0;
            int len;

            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
                digest.update(buffer, 0, len);
                downloaded += len;

                // 每 1MB 输出一次进度
                if (downloaded % (1024 * 1024) == 0) {
                    Log.debug("已下载: " + (downloaded / 1024 / 1024) + " MB");
                }
            }
        }

        // 校验 checksum
        String actualChecksum = bytesToHex(digest.digest());
        Log.debug("文件校验值: " + actualChecksum);

        if (expectedChecksum != null && !expectedChecksum.isEmpty()) {
            if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                Files.delete(tempFile);
                throw new RuntimeException("校验值不匹配! 预期: " + expectedChecksum + ", 实际: " + actualChecksum);
            }
            Log.debug("校验值验证通过");
        }

        // 创建更新标记文件
        createUpdateMarker(tempFile);

        Log.info("客户端更新下载完成，将在下次启动时安装");
        return tempFile;
    }

    /**
     * 获取临时更新文件路径
     */
    public static Path getUpdateTempFile() {
        String tempDir = System.getProperty("java.io.tmpdir");
        return Paths.get(tempDir, UPDATE_TEMP_FILE);
    }

    /**
     * 创建更新标记文件
     */
    public static void createUpdateMarker(Path newJarPath) throws Exception {
        Path markerFile = SelfUpdateChecker.getUpdateMarkerFile();

        // 写入新版本文件路径
        Files.writeString(markerFile, newJarPath.toAbsolutePath().toString());

        Log.debug("已创建更新标记文件: " + markerFile);
    }

    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}