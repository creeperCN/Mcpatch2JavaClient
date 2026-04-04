package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.config.AppConfig;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.utils.Env;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端自身更新管理器
 * 统一管理更新检查、下载、安装流程
 */
public class SelfUpdateManager {
    /**
     * 配置项: 是否启用自动更新
     */
    public static final String CONFIG_ENABLED = "enabled";

    /**
     * 配置项: 版本检查 URL
     */
    public static final String CONFIG_VERSION_URL = "version-url";

    /**
     * 配置项: 更新渠道
     */
    public static final String CONFIG_CHANNEL = "channel";

    /**
     * 配置项: 是否自动安装
     */
    public static final String CONFIG_AUTO_INSTALL = "auto-install";

    /**
     * 配置项: 是否启用备份
     */
    public static final String CONFIG_BACKUP_ENABLED = "backup-enabled";

    /**
     * 配置项: 失败时是否回滚
     */
    public static final String CONFIG_ROLLBACK_ON_FAILURE = "rollback-on-failure";

    /**
     * 更新配置
     */
    private Map<String, Object> config;

    /**
     * 单例实例
     */
    private static SelfUpdateManager instance;

    public SelfUpdateManager(Map<String, Object> config) {
        this.config = config != null ? config : new HashMap<>();
    }

    /**
     * 初始化更新管理器
     */
    public static void init(AppConfig appConfig) {
        // 从主配置中获取客户端更新配置
        // 注意: 需要在 AppConfig 中添加 client-update 配置项
        instance = new SelfUpdateManager(new HashMap<>());
    }

    /**
     * 获取单例实例
     */
    public static SelfUpdateManager getInstance() {
        return instance;
    }

    /**
     * 执行完整的自更新检查和安装流程
     * @return 是否需要重启
     */
    public static boolean performSelfUpdate() {
        try {
            // 1. 先安装待处理的更新（如果有）
            if (SelfUpdateInstaller.installPendingUpdate()) {
                Log.info("已安装客户端更新，建议重启程序");
                return true;
            }

            // 2. 检查是否有新版本
            String versionUrl = getVersionCheckUrl();
            if (versionUrl == null || versionUrl.isEmpty()) {
                Log.debug("未配置客户端更新 URL，跳过检查");
                return false;
            }

            ClientVersionInfo versionInfo = SelfUpdateChecker.fetchLatestVersion(versionUrl);
            String currentVersion = Env.getVersion();

            Log.debug("当前版本: " + currentVersion + ", 最新版本: " + versionInfo.latestVersion);

            // 3. 判断是否需要更新
            if (!SelfUpdateChecker.needUpdate(currentVersion, versionInfo.latestVersion)) {
                Log.debug("客户端已是最新版本");
                return false;
            }

            // 4. 下载新版本
            Log.info("发现新版本: " + versionInfo.latestVersion);
            if (!versionInfo.changelog.isEmpty()) {
                Log.info("更新日志: " + versionInfo.changelog);
            }

            SelfUpdateDownloader.downloadNewVersion(versionInfo.downloadUrl, versionInfo.checksum);

            return false; // 需要下次启动时安装

        } catch (Exception e) {
            Log.error("检查客户端更新失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取版本检查 URL
     */
    private static String getVersionCheckUrl() {
        // 从配置或默认值获取
        // 可以在 mcpatch.yml 中配置: client-update.version-url
        return System.getProperty("mcpatch.selfupdate.url", "");
    }

    /**
     * 是否启用自更新
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("mcpatch.selfupdate.enabled", "true"));
    }
}