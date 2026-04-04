package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.utils.Env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 客户端更新安装器
 * 负责在程序启动时替换旧版本的 JAR 文件
 */
public class SelfUpdateInstaller {
    /**
     * 备份文件后缀
     */
    public static final String BACKUP_SUFFIX = ".backup";

    /**
     * 检查并安装待处理的更新
     * @return 是否安装了更新
     */
    public static boolean installPendingUpdate() {
        try {
            // 检查是否有待安装的更新
            if (!SelfUpdateChecker.hasPendingUpdate()) {
                return false;
            }

            Path markerFile = SelfUpdateChecker.getUpdateMarkerFile();

            // 读取新版本路径
            String newJarPathStr = Files.readString(markerFile).trim();
            if (newJarPathStr.isEmpty()) {
                Log.warn("更新标记文件为空，跳过更新");
                Files.delete(markerFile);
                return false;
            }

            Path newJar = Paths.get(newJarPathStr);

            // 检查新版本文件是否存在
            if (!Files.exists(newJar)) {
                Log.warn("新版本文件不存在: " + newJar);
                Files.delete(markerFile);
                return false;
            }

            // 获取当前 JAR 路径
            Path currentJar = Env.getJarPath();
            if (currentJar == null) {
                Log.warn("无法获取当前 JAR 路径，跳过更新");
                Files.delete(markerFile);
                return false;
            }

            Log.info("正在安装客户端更新...");
            Log.debug("当前版本: " + currentJar);
            Log.debug("新版本: " + newJar);

            // 备份当前版本
            backupCurrentVersion(currentJar);

            // 替换 JAR 文件
            Files.move(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);

            // 删除标记文件
            Files.delete(markerFile);

            Log.info("客户端更新安装完成!");

            return true;

        } catch (Exception e) {
            Log.error("安装客户端更新失败: " + e.getMessage());

            // 尝试回滚
            try {
                rollbackUpdate();
            } catch (Exception ex) {
                Log.error("回滚失败: " + ex.getMessage());
            }

            return false;
        }
    }

    /**
     * 备份当前版本
     */
    private static void backupCurrentVersion(Path currentJar) throws Exception {
        Path backup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);

        if (Files.exists(backup)) {
            Files.delete(backup);
        }

        Files.copy(currentJar, backup);

        Log.debug("已备份当前版本到: " + backup);
    }

    /**
     * 回滚到备份版本
     */
    public static boolean rollbackUpdate() throws Exception {
        Path currentJar = Env.getJarPath();
        if (currentJar == null) {
            return false;
        }

        Path backup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);

        if (!Files.exists(backup)) {
            Log.warn("备份文件不存在，无法回滚");
            return false;
        }

        Log.info("正在回滚到备份版本...");

        Files.move(backup, currentJar, StandardCopyOption.REPLACE_EXISTING);

        Log.info("回滚完成");
        return true;
    }

    /**
     * 检查是否存在备份文件
     */
    public static boolean hasBackup() {
        Path currentJar = Env.getJarPath();
        if (currentJar == null) {
            return false;
        }

        Path backup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);
        return Files.exists(backup);
    }

    /**
     * 清理旧的备份文件
     */
    public static void cleanupBackup() {
        try {
            Path currentJar = Env.getJarPath();
            if (currentJar == null) {
                return;
            }

            Path backup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);

            if (Files.exists(backup)) {
                Files.delete(backup);
                Log.debug("已清理备份文件");
            }
        } catch (Exception e) {
            Log.warn("清理备份文件失败: " + e.getMessage());
        }
    }
}