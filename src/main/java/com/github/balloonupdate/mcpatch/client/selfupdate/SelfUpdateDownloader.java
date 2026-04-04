package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 客户端更新下载器
 * 支持跨平台（Windows/Linux/macOS/Android）
 * 自动选择最佳下载位置
 * 支持镜像切换（超时自动切换下一个）
 * 支持多线程并行下载
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
     * 带镜像切换的下载（支持多线程）
     */
    private static void downloadWithMirrorFallback(String originalUrl, Path targetFile, String expectedChecksum) throws Exception {
        Exception lastException = null;
        String currentUrl = GitHubMirror.convertDownloadUrl(originalUrl);
        int maxTries = 5;
        
        for (int i = 0; i < maxTries; i++) {
            try {
                Log.info("开始下载: " + currentUrl);
                
                // 使用多线程下载
                final String downloadUrl = currentUrl;
                MultiThreadDownloader.download(downloadUrl, targetFile, 6, new MultiThreadDownloader.ProgressCallback() {
                    @Override
                    public void onProgress(long downloaded, long total, int percent) {
                        if (total > 0) {
                            Log.debug("下载进度: " + percent + "% (" + formatSize(downloaded) + "/" + formatSize(total) + ")");
                        }
                    }
                    
                    @Override
                    public void onComplete() {
                        Log.info("下载完成");
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        Log.error("下载错误: " + e.getMessage());
                    }
                });
                
                // 校验 checksum（如果提供）
                if (expectedChecksum != null && !expectedChecksum.isEmpty()) {
                    String actualChecksum = calculateChecksum(targetFile);
                    Log.debug("文件校验值(SHA-256): " + actualChecksum);
                    
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
                cleanupPartialFiles(targetFile);
                
                // 切换到下一个镜像
                if (GitHubMirror.isGitHubUrl(currentUrl)) {
                    currentUrl = GitHubMirror.getNextDownloadMirrorUrl(currentUrl, originalUrl);
                } else {
                    // 已经是原始链接了，不再尝试
                    break;
                }
            }
        }
        
        throw lastException != null ? lastException : new Exception("所有下载尝试都失败");
    }
    
    /**
     * 清理部分下载的文件
     */
    private static void cleanupPartialFiles(Path targetFile) {
        try {
            if (Files.exists(targetFile)) {
                Files.delete(targetFile);
            }
            // 删除分片文件
            Path parent = targetFile.getParent();
            String fileName = targetFile.getFileName().toString();
            int index = 0;
            while (true) {
                Path partFile = parent.resolve(fileName + ".part" + index);
                if (!Files.exists(partFile)) break;
                Files.delete(partFile);
                index++;
            }
        } catch (Exception e) {
            // 忽略清理错误
        }
    }
    
    /**
     * 计算文件校验值
     */
    private static String calculateChecksum(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = input.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
        }
        return bytesToHex(digest.digest());
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

        Files.write(markerFile, content.toString().getBytes());

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