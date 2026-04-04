package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.utils.Env;
import com.github.balloonupdate.mcpatch.client.logging.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 客户端自身更新检查器
 * 支持跨平台（Windows/Linux/macOS/Android）
 */
public class SelfUpdateChecker {
    /**
     * 标记文件后缀
     */
    public static final String MARKER_SUFFIX = ".update-pending";

    /**
     * 新版本临时文件后缀
     */
    public static final String NEW_VERSION_SUFFIX = ".new";

    /**
     * 检查是否有待安装的更新
     */
    public static boolean hasPendingUpdate() {
        try {
            Path markerFile = getUpdateMarkerFile();
            boolean exists = Files.exists(markerFile);
            
            if (exists) {
                Log.debug("发现待安装的更新，标记文件: " + markerFile);
            }
            
            return exists;
        } catch (Exception e) {
            Log.debug("检查更新标记失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取更新标记文件路径
     * 自动选择最佳位置（跨平台兼容）
     */
    public static Path getUpdateMarkerFile() {
        return PlatformPathResolver.getMarkerFilePath();
    }

    /**
     * 获取新版本临时文件路径
     * 自动选择最佳位置（跨平台兼容）
     */
    public static Path getNewVersionFile() {
        return PlatformPathResolver.getNewVersionPath();
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

        if (latestVersion == null || latestVersion.isEmpty()) {
            return false;
        }

        // 清理版本号（去掉 v 前缀）
        if (currentVersion.startsWith("v")) {
            currentVersion = currentVersion.substring(1);
        }
        if (latestVersion.startsWith("v")) {
            latestVersion = latestVersion.substring(1);
        }

        // 检查是否有预发布后缀（如 -beta, -alpha, -rc）
        String currentSuffix = "";
        String latestSuffix = "";
        
        if (currentVersion.contains("-")) {
            String[] parts = currentVersion.split("-", 2);
            currentVersion = parts[0];
            currentSuffix = parts.length > 1 ? parts[1] : "";
        }
        if (latestVersion.contains("-")) {
            String[] parts = latestVersion.split("-", 2);
            latestVersion = parts[0];
            latestSuffix = parts.length > 1 ? parts[1] : "";
        }

        String[] current = currentVersion.split("\\.");
        String[] latest = latestVersion.split("\\.");

        int maxLength = Math.max(current.length, latest.length);

        for (int i = 0; i < maxLength; i++) {
            int c = i < current.length ? parseVersionPart(current[i]) : 0;
            int l = i < latest.length ? parseVersionPart(latest[i]) : 0;

            if (l > c) {
                return true;
            } else if (l < c) {
                return false;
            }
        }

        // 版本号相同时，比较后缀
        // 无后缀（正式版）> 有后缀（预发布版）
        if (currentSuffix.isEmpty() && !latestSuffix.isEmpty()) {
            // 当前是正式版，最新是预发布版，不需要更新
            return false;
        } else if (!currentSuffix.isEmpty() && latestSuffix.isEmpty()) {
            // 当前是预发布版，最新是正式版，需要更新
            return true;
        } else if (!currentSuffix.isEmpty() && !latestSuffix.isEmpty()) {
            // 都是预发布版，比较后缀（简单比较）
            return latestSuffix.compareTo(currentSuffix) > 0;
        }

        return false;
    }

    /**
     * 解析版本号的某一部分
     * 处理如 "0-beta" 这样的版本号
     */
    private static int parseVersionPart(String part) {
        try {
            // 提取数字部分
            StringBuilder numStr = new StringBuilder();
            for (char c : part.toCharArray()) {
                if (Character.isDigit(c)) {
                    numStr.append(c);
                } else {
                    break;
                }
            }
            if (numStr.length() > 0) {
                return Integer.parseInt(numStr.toString());
            }
            return 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 打印当前平台信息（调试用）
     */
    public static void printPlatformInfo() {
        Log.debug("===== 平台信息 =====");
        Log.debug(PlatformPathResolver.getPlatformInfo());
    }
}