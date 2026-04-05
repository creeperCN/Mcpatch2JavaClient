package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.utils.Env;

/**
 * 客户端自身更新管理器
 * 统一管理更新检查、下载、安装流程
 * 
 * 从服主 McPatch2 管理端获取更新
 * 支持跨平台（Windows/Linux/macOS/Android）
 */
public class SelfUpdateManager {
    /**
     * 执行完整的自更新检查和安装流程
     * @param showWindow 是否显示进度窗口
     * @return 是否需要重启
     */
    public static boolean performSelfUpdate(boolean showWindow) {
        try {
            // 1. 显示窗口（如果需要）
            if (showWindow) {
                SelfUpdateWindow.showWindow("Mcpatch 客户端更新");
                SelfUpdateWindow.updateStatus("正在检查客户端更新...");
            }
            
            // 注意：installPendingUpdate() 已在 main/premain 入口最开始调用
            // 这里只负责检查更新和下载，不负责安装

            // 2. 检查是否配置了更新服务器
            String serverUrl = getUpdateServerUrl();
            if (serverUrl == null || serverUrl.isEmpty()) {
                Log.debug("未配置客户端更新服务器，跳过自更新检查");
                if (showWindow) {
                    SelfUpdateWindow.closeWindow();
                }
                return false;
            }

            // 3. 从服主服务器获取版本信息
            if (showWindow) {
                SelfUpdateWindow.updateStatus("正在从服主服务器获取版本信息...");
            }
            
            ClientVersionInfo versionInfo = ServerVersionClient.fetchVersionInfo(serverUrl);

            String currentVersion = Env.getVersion();
            Log.debug("当前版本: " + currentVersion + ", 服主指定版本: " + versionInfo.latestVersion);

            // 4. 判断是否需要更新
            if (!SelfUpdateChecker.needUpdate(currentVersion, versionInfo.latestVersion)) {
                Log.debug("客户端版本符合服主要求");
                if (showWindow) {
                    SelfUpdateWindow.updateStatus("客户端版本符合服主要求 (" + currentVersion + ")");
                    Thread.sleep(500);
                    SelfUpdateWindow.closeWindow();
                }
                return false;
            }

            // 5. 检查强制更新
            if (versionInfo.forceUpdate) {
                Log.info("服主强制要求更新客户端");
            }

            // 6. 下载新版本
            if (showWindow) {
                SelfUpdateWindow.updateStatus("发现新版本 " + versionInfo.latestVersion + "，正在下载...");
            }
            
            Log.info("发现新版本: " + versionInfo.latestVersion);
            if (versionInfo.changelog != null && !versionInfo.changelog.isEmpty()) {
                Log.info("更新日志: " + versionInfo.changelog.replace("\n", "\n         "));
            }

            // 使用 SelfUpdateDownloader 下载
            SelfUpdateDownloader.downloadNewVersion(versionInfo.downloadUrl, versionInfo.checksum);

            if (showWindow) {
                SelfUpdateWindow.updateStatus("下载完成，将在下次启动时安装");
                Thread.sleep(1000);
                SelfUpdateWindow.closeWindow();
            }

            return false; // 需要下次启动时安装

        } catch (Exception e) {
            Log.error("检查客户端更新失败: " + e.getMessage());
            
            if (showWindow) {
                SelfUpdateWindow.updateStatus("更新检查失败: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                SelfUpdateWindow.closeWindow();
            }
            
            return false;
        }
    }

    /**
     * 执行更新检查（不显示窗口）
     */
    public static boolean performSelfUpdate() {
        return performSelfUpdate(false);
    }

    /**
     * 获取更新服务器地址
     */
    private static String getUpdateServerUrl() {
        return System.getProperty("mcpatch.selfupdate.server-url", "");
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("mcpatch.selfupdate.enabled", "true"));
    }
}