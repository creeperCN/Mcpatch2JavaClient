package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 客户端自身更新检查器
 */
public class SelfUpdateChecker {
    /**
     * 标记文件路径（表示有待安装的更新）
     */
    public static final String UPDATE_MARKER_FILE = ".mcpatch-selfupdate-marker";

    /**
     * 检查是否有待安装的更新
     */
    public static boolean hasPendingUpdate() {
        try {
            Path markerFile = getUpdateMarkerFile();
            return Files.exists(markerFile);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取更新标记文件路径
     */
    public static Path getUpdateMarkerFile() {
        String tempDir = System.getProperty("java.io.tmpdir");
        return Paths.get(tempDir, UPDATE_MARKER_FILE);
    }

    /**
     * 从远程服务器获取最新版本信息
     * @param versionUrl 版本信息 URL
     * @return 版本信息对象
     */
    public static ClientVersionInfo fetchLatestVersion(String versionUrl) throws Exception {
        Log.debug("正在检查客户端更新: " + versionUrl);

        URL url = new URL(versionUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("获取版本信息失败: HTTP " + conn.getResponseCode());
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }

        return ClientVersionInfo.fromJson(response.toString());
    }

    /**
     * 比较版本号，判断是否需要更新
     * @param currentVersion 当前版本
     * @param latestVersion 最新版本
     * @return 是否需要更新
     */
    public static boolean needUpdate(String currentVersion, String latestVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) {
            return true;
        }

        String[] current = currentVersion.split("\\.");
        String[] latest = latestVersion.split("\\.");

        int maxLength = Math.max(current.length, latest.length);

        for (int i = 0; i < maxLength; i++) {
            int c = i < current.length ? Integer.parseInt(current[i]) : 0;
            int l = i < latest.length ? Integer.parseInt(latest[i]) : 0;

            if (l > c) {
                return true;
            } else if (l < c) {
                return false;
            }
        }

        return false;
    }
}