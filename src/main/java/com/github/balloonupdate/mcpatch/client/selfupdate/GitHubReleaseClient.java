package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub Release API 客户端
 * 用于从 GitHub Release 获取版本信息
 * 
 * API 和下载使用不同的镜像站
 */
public class GitHubReleaseClient {
    /**
     * GitHub API 基础 URL
     */
    private static final String GITHUB_API_BASE = "https://api.github.com/repos/";

    /**
     * 是否使用镜像加速
     */
    private static boolean useMirror = true;

    /**
     * 从 GitHub Release 获取最新版本信息
     */
    public static ClientVersionInfo fetchLatestRelease(String owner, String repo) throws Exception {
        String apiUrl = GITHUB_API_BASE + owner + "/" + repo + "/releases/latest";
        Log.debug("正在从 GitHub Release 获取版本信息: " + apiUrl);

        String response;
        if (useMirror) {
            response = httpGetWithFallback(apiUrl, true);
        } else {
            response = httpGetDirect(apiUrl);
        }

        JSONObject json = new JSONObject(response);
        return parseGitHubRelease(json);
    }

    /**
     * 从 GitHub Release 获取指定版本信息
     */
    public static ClientVersionInfo fetchReleaseByTag(String owner, String repo, String tag) throws Exception {
        String apiUrl = GITHUB_API_BASE + owner + "/" + repo + "/releases/tags/" + tag;
        Log.debug("正在从 GitHub Release 获取版本信息: " + apiUrl);

        String response;
        if (useMirror) {
            response = httpGetWithFallback(apiUrl, true);
        } else {
            response = httpGetDirect(apiUrl);
        }

        JSONObject json = new JSONObject(response);
        return parseGitHubRelease(json);
    }

    /**
     * 获取所有 Release 列表
     */
    public static List<ClientVersionInfo> fetchReleases(String owner, String repo, int limit) throws Exception {
        String apiUrl = GITHUB_API_BASE + owner + "/" + repo + "/releases?per_page=" + limit;
        Log.debug("正在从 GitHub Release 获取版本列表: " + apiUrl);

        String response;
        if (useMirror) {
            response = httpGetWithFallback(apiUrl, true);
        } else {
            response = httpGetDirect(apiUrl);
        }

        JSONArray jsonArray = new JSONArray(response);

        List<ClientVersionInfo> versions = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject release = jsonArray.getJSONObject(i);
            versions.add(parseGitHubRelease(release));
        }

        return versions;
    }

    /**
     * 解析 GitHub Release JSON 为 ClientVersionInfo
     */
    private static ClientVersionInfo parseGitHubRelease(JSONObject json) {
        ClientVersionInfo info = new ClientVersionInfo();

        // 版本号 (去除 v 前缀)
        String tagName = json.getString("tag_name");
        info.latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

        // 更新日志
        info.body = json.optString("body", "");
        info.changelog = info.body;

        // 发布日期
        info.releaseDate = json.optString("published_at", "");

        // 是否预发布
        info.prerelease = json.optBoolean("prerelease", false);

        // 查找 JAR 文件资源
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");

                // 查找 .jar 文件
                if (name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc")) {
                    // 保持原始URL，由下载器决定是否使用镜像
                    info.downloadUrl = asset.getString("browser_download_url");
                    info.fileSize = asset.optLong("size", 0);
                    Log.debug("找到 JAR 文件: " + name + " (" + info.downloadUrl + ")");
                    break;
                }
            }
        }

        return info;
    }

    /**
     * 带自动切换的 HTTP GET 请求
     * @param apiUrl 原始 API URL
     * @param isApi 是否是 API 请求（true=API镜像，false=下载镜像）
     */
    private static String httpGetWithFallback(String apiUrl, boolean isApi) throws Exception {
        Exception lastException = null;
        String currentUrl = isApi ? GitHubMirror.convertApiUrl(apiUrl) : GitHubMirror.convertDownloadUrl(apiUrl);
        String[] mirrors = isApi ? new String[]{"API镜像1", "API镜像2"} : new String[]{"下载镜像1", "下载镜像2"};
        int maxTries = 5;

        for (int i = 0; i < maxTries; i++) {
            HttpURLConnection conn = null;
            try {
                Log.debug("尝试: " + currentUrl);

                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(GitHubMirror.getConnectTimeout());
                conn.setReadTimeout(GitHubMirror.getReadTimeout());
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "Mcpatch2JavaClient-Updater");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // 成功，缓存当前镜像
                    if (isApi) {
                        GitHubMirror.markApiMirrorSuccess();
                    } else {
                        GitHubMirror.markDownloadMirrorSuccess();
                    }

                    StringBuilder response = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line).append("\n");
                        }
                    }
                    return response.toString();
                } else {
                    throw new Exception("HTTP " + responseCode);
                }

            } catch (Exception e) {
                Log.debug("请求失败: " + e.getMessage());
                lastException = e;

                // 切换到下一个镜像
                if (GitHubMirror.isGitHubUrl(currentUrl)) {
                    currentUrl = GitHubMirror.getNextMirrorUrl(currentUrl, apiUrl);
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

        throw lastException != null ? lastException : new Exception("所有请求都失败");
    }

    /**
     * 直接请求（不使用镜像）
     */
    private static String httpGetDirect(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "Mcpatch2JavaClient-Updater");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("HTTP 请求失败: " + responseCode);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        }

        return response.toString();
    }

    /**
     * 设置是否使用镜像加速
     */
    public static void setUseMirror(boolean enabled) {
        useMirror = enabled;
        if (!enabled) {
            GitHubMirror.reset();
        }
    }

    /**
     * 获取是否使用镜像加速
     */
    public static boolean isUseMirror() {
        return useMirror;
    }
}