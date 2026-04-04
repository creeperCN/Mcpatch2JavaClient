package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.utils.Env;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 客户端更新安装器
 * 支持跨平台（Windows/Linux/macOS/Android）
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
            Log.debug("读取标记文件: " + markerFile);

            // 解析标记文件
            String markerContent = new String(Files.readAllBytes(markerFile));
            Path newJarPath = parseMarkerFile(markerContent);

            if (newJarPath == null) {
                Log.warn("标记文件格式错误，跳过更新");
                Files.deleteIfExists(markerFile);
                return false;
            }

            // 检查新版本文件是否存在
            if (!Files.exists(newJarPath)) {
                Log.warn("新版本文件不存在: " + newJarPath);
                Files.deleteIfExists(markerFile);
                return false;
            }

            // 获取当前 JAR 路径
            Path currentJar = Env.getJarPath();
            if (currentJar == null) {
                Log.warn("无法获取当前 JAR 路径，跳过更新");
                Log.warn("请手动替换文件: " + newJarPath);
                return false;
            }

            Log.info("正在安装客户端更新...");
            Log.debug("当前版本: " + currentJar);
            Log.debug("新版本: " + newJarPath);

            // 检查是否是同一个文件（不同路径指向同一文件）
            if (Files.isSameFile(newJarPath, currentJar)) {
                Log.info("新版本与当前版本相同，跳过更新");
                Files.deleteIfExists(markerFile);
                return false;
            }

            // 备份当前版本
            backupCurrentVersion(currentJar);

            // 替换 JAR 文件
            Log.info("替换文件中...");
            Files.move(newJarPath, currentJar, StandardCopyOption.REPLACE_EXISTING);

            // 删除标记文件
            Files.deleteIfExists(markerFile);

            Log.info("===========================================");
            Log.info("客户端更新安装完成!");
            Log.info("新版本已安装: " + currentJar);
            Log.info("===========================================");

            return true;

        } catch (Exception e) {
            Log.error("安装客户端更新失败: " + e.getMessage());

            // 尝试回滚
            try {
                if (rollbackUpdate()) {
                    Log.info("已回滚到备份版本");
                }
            } catch (Exception ex) {
                Log.error("回滚失败: " + ex.getMessage());
            }

            return false;
        }
    }

    /**
     * 解析标记文件内容，获取新版本文件路径
     * 包含路径安全校验，防止路径遍历攻击
     */
    private static Path parseMarkerFile(String content) {
        try {
            BufferedReader reader = new BufferedReader(new StringReader(content));
            String line;
            Path jarPath = null;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("path=")) {
                    Path parsed = Paths.get(line.substring(5).trim()).normalize().toAbsolutePath();
                    if (isSafeUpdatePath(parsed)) {
                        jarPath = parsed;
                    } else {
                        Log.warn("标记文件中的路径不安全，已拒绝: " + parsed);
                        return null;
                    }
                }
            }

            // 如果没有 path= 格式，尝试直接作为路径解析（旧格式兼容）
            if (jarPath == null && !content.trim().isEmpty()) {
                String firstLine = content.split("\n")[0].trim();
                if (!firstLine.isEmpty()) {
                    Path parsed = Paths.get(firstLine).normalize().toAbsolutePath();
                    if (isSafeUpdatePath(parsed)) {
                        jarPath = parsed;
                    } else {
                        Log.warn("标记文件中的路径不安全，已拒绝: " + parsed);
                        return null;
                    }
                }
            }

            return jarPath;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验路径是否在允许的更新目录内，防止路径遍历攻击
     */
    private static boolean isSafeUpdatePath(Path path) {
        // 获取当前 JAR 所在目录作为白名单根目录
        Path currentJar = Env.getJarPath();
        if (currentJar == null) {
            // 开发环境下无法获取 JAR 路径，允许任意路径
            return true;
        }

        Path allowedDir = currentJar.getParent();
        if (allowedDir == null) {
            return false;
        }
        allowedDir = allowedDir.normalize().toAbsolutePath();

        Path normalizedPath = path.normalize().toAbsolutePath();

        // 路径必须在允许的目录内
        if (!normalizedPath.startsWith(allowedDir)) {
            return false;
        }

        // 文件名必须以 .jar.new 结尾（更新文件特征）
        String fileName = normalizedPath.getFileName().toString();
        return fileName.endsWith(".jar.new");
    }

    /**
     * 备份当前版本
     */
    private static void backupCurrentVersion(Path currentJar) throws Exception {
        Path backup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);

        // 删除旧备份
        Files.deleteIfExists(backup);

        // 创建新备份
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

    /**
     * 清理所有更新相关文件（用于取消更新）
     */
    public static void cleanupUpdateFiles() {
        try {
            // 删除新版本文件
            Path newJar = SelfUpdateChecker.getNewVersionFile();
            if (Files.exists(newJar)) {
                Files.delete(newJar);
                Log.debug("已删除新版本文件");
            }

            // 删除标记文件
            Path markerFile = SelfUpdateChecker.getUpdateMarkerFile();
            if (Files.exists(markerFile)) {
                Files.delete(markerFile);
                Log.debug("已删除标记文件");
            }
        } catch (Exception e) {
            Log.warn("清理更新文件失败: " + e.getMessage());
        }
    }
}