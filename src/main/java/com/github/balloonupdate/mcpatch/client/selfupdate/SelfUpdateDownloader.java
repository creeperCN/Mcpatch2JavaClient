package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 客户端更新下载器
 * 支持跨平台（Windows/Linux/macOS/Android）
 * 自动选择最佳下载位置
 * 支持镜像切换（超时自动切换下一个）
 */
public class SelfUpdateDownloader {
    /**
     * 下载新版本（带镜像切换）
     * @param downloadUrl 下载地址
     * @param expectedChecksum 预期的校验值（可选）
     * @return 下载后的文件路径
     */
    public static Path downloadNewVersion(String downloadUrl, String expectedChecksum) throws Exception {
        Log.info("正在下载客户端更新...");

        // 使用跨平台路径解析
        Path newVersionFile = SelfUpdateChecker.getNewVersionFile();
        Path updateDir = newVersionFile.getParent();

        Log.debug("平台: " + PlatformPathResolver.detectPlatform());
        Log.debug("下载位置: " + newVersionFile);

        // 确保目录存在
        if (!Files.exists(updateDir)) {
            Files.createDirectories(updateDir);
            Log.debug("创建目录: " + updateDir);
        }

        // 删除旧的临时文件
        if (Files.exists(newVersionFile)) {
            Files.delete(newVersionFile);
            Log.debug("删除旧文件: " + newVersionFile);
        }

        // 使用带镜像切换的下载
        downloadWithMirrorFallback(downloadUrl, newVersionFile, expectedChecksum);

        // 创建更新标记文件
        createUpdateMarker(newVersionFile);

        Log.info("客户端更新下载完成，将在下次启动时安装");
        Log.info("文件位置: " + newVersionFile);
        
        return newVersionFile;
    }

    /**
     * 带镜像切换的下载
     */
    private static void downloadWithMirrorFallback(String originalUrl, Path targetFile, String expectedChecksum) throws Exception {
        Exception lastException = null;
        String currentUrl = GitHubMirror.convertDownloadUrl(originalUrl);
        int maxTries = 5;

        for (int i = 0; i < maxTries; i++) {
            HttpURLConnection conn = null;
            try {
                Log.info("开始下载: " + currentUrl);

                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(GitHubMirror.getConnectTimeout());
                conn.setReadTimeout(GitHubMirror.getReadTimeout());
                conn.setRequestProperty("User-Agent", "Mcpatch2JavaClient-Updater");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new RuntimeException("HTTP " + responseCode);
                }

                long contentLength = conn.getContentLengthLong();
                Log.debug("文件大小: " + formatSize(contentLength));

                // 下载并计算校验值
                MessageDigest digest = MessageDigest.getInstance("SHA-256");

                try (InputStream input = conn.getInputStream();
                     OutputStream output = Files.newOutputStream(targetFile)) {

                    byte[] buffer = new byte[64 * 1024];
                    long downloaded = 0;
                    int len;
                    long lastLogTime = System.currentTimeMillis();

                    while ((len = input.read(buffer)) != -1) {
                        output.write(buffer, 0, len);
                        digest.update(buffer, 0, len);
                        downloaded += len;

                        // 每 2 秒输出一次进度
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime > 2000) {
                            int percent = contentLength > 0 ? (int)(downloaded * 100 / contentLength) : 0;
                            Log.debug("下载进度: " + percent + "% (" + formatSize(downloaded) + ")");
                            lastLogTime = now;
                        }
                    }
                }

                Log.info("下载完成");

                // 校验 checksum（如果提供）
                String actualChecksum = bytesToHex(digest.digest());
                Log.debug("文件校验值(SHA-256): " + actualChecksum);

                if (expectedChecksum != null && !expectedChecksum.isEmpty()) {
                    if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                        Files.delete(targetFile);
                        throw new RuntimeException("校验值不匹配! 预期: " + expectedChecksum + ", 实际: " + actualChecksum);
                    }
                    Log.debug("校验值验证通过");
                }

                // 成功，缓存镜像
                GitHubMirror.markDownloadMirrorSuccess();
                return;

            } catch (Exception e) {
                Log.debug("下载失败: " + e.getMessage());
                lastException = e;

                // 删除可能的部分下载文件
                Files.deleteIfExists(targetFile);

                // 切换到下一个镜像
                if (GitHubMirror.isGitHubUrl(currentUrl)) {
                    currentUrl = GitHubMirror.getNextDownloadMirrorUrl(currentUrl, originalUrl);
                } else {
                    // 已经是原始链接了，不再尝试
                    break;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        throw lastException != null ? lastException : new Exception("所有下载尝试都失败");
    }

    /**
     * 创建更新标记文件
     */
    public static void createUpdateMarker(Path newJarPath) throws Exception {
        Path markerFile = SelfUpdateChecker.getUpdateMarkerFile();

        // 写入新版本文件信息
        StringBuilder content = new StringBuilder();
        content.append("path=").append(newJarPath.toAbsolutePath()).append("\n");
        content.append("time=").append(System.currentTimeMillis()).append("\n");

        Files.writeString(markerFile, content.toString());

        Log.debug("已创建更新标记文件: " + markerFile);
    }

    /**
     * 格式化文件大小
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
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