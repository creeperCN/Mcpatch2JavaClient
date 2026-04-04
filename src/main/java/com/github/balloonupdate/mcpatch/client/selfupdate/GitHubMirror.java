package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub 镜像加速器
 * 用于在国内环境下加速访问 GitHub
 * 
 * API 和下载使用不同的镜像站列表
 */
public class GitHubMirror {
    /**
     * GitHub API 镜像站列表（用于 API 请求）
     */
    private static final String[] API_MIRROR_URLS = {
        "https://gh.bugdey.us.kg/",
        "https://github.dpik.top/"
    };

    /**
     * GitHub Release 下载镜像站列表（用于文件下载）
     */
    private static final String[] DOWNLOAD_MIRROR_URLS = {
        "https://gh-proxy.org/",
        "https://hk.gh-proxy.org/",
        "https://cdn.gh-proxy.org/",
        "https://edgeone.gh-proxy.org/"
    };

    /**
     * 连接超时时间（毫秒）
     */
    private static final int CONNECT_TIMEOUT = 10000; // 10秒

    /**
     * 读取超时时间（毫秒）
     */
    private static final int READ_TIMEOUT = 30000; // 30秒

    /**
     * 当前 API 镜像索引
     */
    private static int currentApiMirrorIndex = 0;

    /**
     * 当前下载镜像索引
     */
    private static int currentDownloadMirrorIndex = 0;

    /**
     * 缓存成功的 API 镜像
     */
    private static String cachedApiMirror = null;

    /**
     * 缓存成功的下载镜像
     */
    private static String cachedDownloadMirror = null;

    /**
     * 转换 GitHub API URL 为镜像 URL
     */
    public static String convertApiUrl(String originalUrl) {
        if (!isGitHubUrl(originalUrl)) {
            return originalUrl;
        }

        // 如果有缓存的镜像，直接使用
        if (cachedApiMirror != null) {
            return cachedApiMirror + originalUrl;
        }

        // 尝试第一个镜像
        String mirror = API_MIRROR_URLS[currentApiMirrorIndex];
        Log.debug("使用 API 镜像站: " + mirror);
        return mirror + originalUrl;
    }

    /**
     * 转换 GitHub 下载 URL 为镜像 URL
     */
    public static String convertDownloadUrl(String originalUrl) {
        if (!isGitHubUrl(originalUrl)) {
            return originalUrl;
        }

        // 如果有缓存的镜像，直接使用
        if (cachedDownloadMirror != null) {
            return cachedDownloadMirror + originalUrl;
        }

        // 尝试第一个镜像
        String mirror = DOWNLOAD_MIRROR_URLS[currentDownloadMirrorIndex];
        Log.debug("使用下载镜像站: " + mirror);
        return mirror + originalUrl;
    }

    /**
     * 兼容旧方法：转换 URL（自动判断类型）
     */
    public static String convertToMirrorUrl(String originalUrl) {
        if (originalUrl.contains("api.github.com")) {
            return convertApiUrl(originalUrl);
        } else {
            return convertDownloadUrl(originalUrl);
        }
    }

    /**
     * 当 API 请求失败时，切换到下一个镜像
     */
    public static String getNextApiMirrorUrl(String failedUrl, String originalUrl) {
        currentApiMirrorIndex++;
        
        if (currentApiMirrorIndex >= API_MIRROR_URLS.length) {
            Log.warn("所有 API 镜像站都已尝试，使用原始链接");
            currentApiMirrorIndex = 0;
            return originalUrl;
        }

        String nextMirror = API_MIRROR_URLS[currentApiMirrorIndex];
        Log.info("切换到下一个 API 镜像站: " + nextMirror);
        return nextMirror + originalUrl;
    }

    /**
     * 当下载失败时，切换到下一个镜像
     */
    public static String getNextDownloadMirrorUrl(String failedUrl, String originalUrl) {
        currentDownloadMirrorIndex++;
        
        if (currentDownloadMirrorIndex >= DOWNLOAD_MIRROR_URLS.length) {
            Log.warn("所有下载镜像站都已尝试，使用原始链接");
            currentDownloadMirrorIndex = 0;
            return originalUrl;
        }

        String nextMirror = DOWNLOAD_MIRROR_URLS[currentDownloadMirrorIndex];
        Log.info("切换到下一个下载镜像站: " + nextMirror);
        return nextMirror + originalUrl;
    }

    /**
     * 兼容旧方法
     */
    public static String getNextMirrorUrl(String failedUrl, String originalUrl) {
        if (failedUrl.contains("api.github.com") || isApiMirrorUrl(failedUrl)) {
            return getNextApiMirrorUrl(failedUrl, originalUrl);
        } else {
            return getNextDownloadMirrorUrl(failedUrl, originalUrl);
        }
    }

    /**
     * 检查是否是 API 镜像 URL
     */
    private static boolean isApiMirrorUrl(String url) {
        for (String mirror : API_MIRROR_URLS) {
            if (url.startsWith(mirror)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标记当前 API 镜像成功
     */
    public static void markApiMirrorSuccess() {
        if (cachedApiMirror == null && currentApiMirrorIndex < API_MIRROR_URLS.length) {
            cachedApiMirror = API_MIRROR_URLS[currentApiMirrorIndex];
            Log.debug("缓存 API 镜像站: " + cachedApiMirror);
        }
    }

    /**
     * 标记当前下载镜像成功
     */
    public static void markDownloadMirrorSuccess() {
        if (cachedDownloadMirror == null && currentDownloadMirrorIndex < DOWNLOAD_MIRROR_URLS.length) {
            cachedDownloadMirror = DOWNLOAD_MIRROR_URLS[currentDownloadMirrorIndex];
            Log.debug("缓存下载镜像站: " + cachedDownloadMirror);
        }
    }

    /**
     * 兼容旧方法
     */
    public static void markCurrentMirrorSuccess() {
        // 根据当前状态判断是哪个镜像
        if (cachedApiMirror == null) {
            markApiMirrorSuccess();
        }
        if (cachedDownloadMirror == null) {
            markDownloadMirrorSuccess();
        }
    }

    /**
     * 重置所有镜像状态
     */
    public static void reset() {
        currentApiMirrorIndex = 0;
        currentDownloadMirrorIndex = 0;
        cachedApiMirror = null;
        cachedDownloadMirror = null;
    }

    /**
     * 检查是否是 GitHub URL
     */
    public static boolean isGitHubUrl(String url) {
        if (url == null) return false;
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("github.com") || lowerUrl.contains("githubusercontent.com");
    }

    /**
     * 获取连接超时时间
     */
    public static int getConnectTimeout() {
        return CONNECT_TIMEOUT;
    }

    /**
     * 获取读取超时时间
     */
    public static int getReadTimeout() {
        return READ_TIMEOUT;
    }

    /**
     * 测试并打印所有镜像状态
     */
    public static void testAllMirrors() {
        Log.info("===== GitHub 镜像站列表 =====");
        
        Log.info("API 镜像站:");
        for (int i = 0; i < API_MIRROR_URLS.length; i++) {
            String status = (i == currentApiMirrorIndex) ? " [当前]" : "";
            Log.info("  " + (i + 1) + ". " + API_MIRROR_URLS[i] + status);
        }
        
        Log.info("下载镜像站:");
        for (int i = 0; i < DOWNLOAD_MIRROR_URLS.length; i++) {
            String status = (i == currentDownloadMirrorIndex) ? " [当前]" : "";
            Log.info("  " + (i + 1) + ". " + DOWNLOAD_MIRROR_URLS[i] + status);
        }
        
        Log.info("策略: 超时 " + (CONNECT_TIMEOUT / 1000) + " 秒自动切换下一个");
        Log.info("==============================");
    }
}