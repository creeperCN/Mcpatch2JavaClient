package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.utils.Env;

/**
 * 客户端自身更新管理器
 * 统一管理更新检查、下载、安装流程
 * 
 * 使用 GitHub Release API 获取更新
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
            
            // 2. 安装待处理的更新（如果有）
            if (SelfUpdateInstaller.installPendingUpdate()) {
                if (showWindow) {
                    SelfUpdateWindow.updateStatus("已安装客户端更新");
                }
                Log.info("已安装客户端更新，建议重启程序");
                
                // 关闭窗口
                if (showWindow) {
                    Thread.sleep(500);
                    SelfUpdateWindow.closeWindow();
                }
                return true;
            }

            // 3. 检查是否有新版本
            String githubRepo = getGitHubRepo();
            if (githubRepo == null || githubRepo.isEmpty()) {
                Log.debug("未配置 GitHub 仓库，跳过自更新检查");
                if (showWindow) {
                    SelfUpdateWindow.closeWindow();
                }
                return false;
            }

            // 4. 配置镜像加速
            configureMirror();

            // 5. 从 GitHub Release 获取版本信息
            if (showWindow) {
                SelfUpdateWindow.updateStatus("正在从 GitHub 获取版本信息...");
            }
            
            ClientVersionInfo versionInfo = fetchFromGitHub(githubRepo);

            String currentVersion = Env.getVersion();
            Log.debug("当前版本: " + currentVersion + ", 最新版本: " + versionInfo.latestVersion);

            // 6. 判断是否需要更新
            if (!SelfUpdateChecker.needUpdate(currentVersion, versionInfo.latestVersion)) {
                Log.debug("客户端已是最新版本");
                if (showWindow) {
                    SelfUpdateWindow.updateStatus("客户端已是最新版本 (" + currentVersion + ")");
                    Thread.sleep(500);
                    SelfUpdateWindow.closeWindow();
                }
                return false;
            }

            // 7. 检查预发布版本
            if (versionInfo.prerelease && !isPreReleaseEnabled()) {
                Log.debug("最新版本为预发布版本，跳过更新");
                if (showWindow) {
                    SelfUpdateWindow.updateStatus("最新版本为预发布版本，跳过更新");
                    Thread.sleep(500);
                    SelfUpdateWindow.closeWindow();
                }
                return false;
            }

            // 8. 下载新版本
            if (showWindow) {
                SelfUpdateWindow.updateStatus("发现新版本 " + versionInfo.latestVersion + "，正在下载...");
            }
            
            Log.info("发现新版本: " + versionInfo.latestVersion);
            if (versionInfo.changelog != null && !versionInfo.changelog.isEmpty()) {
                Log.info("更新日志: " + versionInfo.changelog.replace("\n", "\n         "));
            }

            // 使用 SelfUpdateDownloader 下载（带镜像切换）
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
     * 从 GitHub Release 获取版本信息
     */
    private static ClientVersionInfo fetchFromGitHub(String githubRepo) throws Exception {
        String[] parts = githubRepo.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("GitHub 仓库格式错误，应为 owner/repo: " + githubRepo);
        }

        String owner = parts[0];
        String repo = parts[1];

        Log.debug("从 GitHub Release 获取版本: " + owner + "/" + repo);

        return GitHubReleaseClient.fetchLatestRelease(owner, repo);
    }

    /**
     * 配置镜像加速
     */
    private static void configureMirror() {
        String mirrorConfig = getMirrorConfig();

        if ("false".equalsIgnoreCase(mirrorConfig)) {
            GitHubReleaseClient.setUseMirror(false);
            Log.debug("镜像加速已禁用");
        } else {
            GitHubReleaseClient.setUseMirror(true);
            Log.debug("镜像加速模式: 启用（超时自动切换）");
        }
    }

    private static String getGitHubRepo() {
        return System.getProperty("mcpatch.selfupdate.github-repo", "");
    }

    private static String getMirrorConfig() {
        return System.getProperty("mcpatch.selfupdate.mirror", "auto");
    }

    private static String getChannel() {
        return System.getProperty("mcpatch.selfupdate.channel", "stable");
    }

    private static boolean isPreReleaseEnabled() {
        return "beta".equals(getChannel()) || "alpha".equals(getChannel());
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("mcpatch.selfupdate.enabled", "true"));
    }
}