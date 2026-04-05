package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.utils.Env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 跨平台路径解析器
 * 处理不同平台的文件路径问题，包括 Android
 */
public class PlatformPathResolver {

    /**
     * 平台类型枚举
     */
    public enum Platform {
        WINDOWS,
        MACOS,
        LINUX,
        ANDROID,
        UNKNOWN
    }

    /**
     * 缓存检测到的平台
     */
    private static Platform detectedPlatform = null;

    /**
     * 缓存备用目录
     */
    private static Path fallbackDirectory = null;
    
    /**
     * 缓存更新目录（避免每次重新计算）
     */
    private static Path cachedUpdateDirectory = null;

    /**
     * 检测当前平台
     */
    public static Platform detectPlatform() {
        if (detectedPlatform != null) {
            return detectedPlatform;
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        String javaVendor = System.getProperty("java.vendor", "").toLowerCase();
        String javaHome = System.getProperty("java.home", "").toLowerCase();

        // 检测 Android
        // Android 的特点：
        // 1. java.vendor 包含 "Android"
        // 2. java.home 包含 "dalvik" 或 "art"
        // 3. 存在 android.os.Build 类
        if (javaVendor.contains("android") || 
            javaHome.contains("dalvik") || 
            javaHome.contains("art") ||
            isAndroidRuntime()) {
            detectedPlatform = Platform.ANDROID;
            return detectedPlatform;
        }

        // 检测桌面平台
        if (osName.contains("win")) {
            detectedPlatform = Platform.WINDOWS;
        } else if (osName.contains("mac")) {
            detectedPlatform = Platform.MACOS;
        } else if (osName.contains("nux") || osName.contains("nix")) {
            detectedPlatform = Platform.LINUX;
        } else {
            detectedPlatform = Platform.UNKNOWN;
        }

        return detectedPlatform;
    }

    /**
     * 检测是否是 Android 运行时
     */
    private static boolean isAndroidRuntime() {
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 是否是 Android 平台
     */
    public static boolean isAndroid() {
        return detectPlatform() == Platform.ANDROID;
    }

    /**
     * 是否是桌面平台（Windows/macOS/Linux）
     */
    public static boolean isDesktop() {
        Platform platform = detectPlatform();
        return platform == Platform.WINDOWS || 
               platform == Platform.MACOS || 
               platform == Platform.LINUX;
    }

    /**
     * 获取可写入的更新目录
     * 优先级：
     * 1. JAR 同目录（如果可写）
     * 2. 用户主目录下的 .mcpatch 目录
     * 3. 临时目录
     */
    public static Path getWritableUpdateDirectory() {
        // 使用缓存，避免每次重新计算导致路径不一致
        if (cachedUpdateDirectory != null) {
            return cachedUpdateDirectory;
        }
        
        // 尝试 1: JAR 同目录
        Path jarDir = getJarDirectory();
        if (jarDir != null && isDirectoryWritable(jarDir)) {
            cachedUpdateDirectory = jarDir;
            return cachedUpdateDirectory;
        }

        // 尝试 2: 用户主目录下的 .mcpatch 目录
        Path homeDir = getHomeMcpatchDir();
        if (homeDir != null) {
            try {
                Files.createDirectories(homeDir);
                if (isDirectoryWritable(homeDir)) {
                    cachedUpdateDirectory = homeDir;
                    return cachedUpdateDirectory;
                }
            } catch (Exception e) {
                // 忽略，尝试下一个
            }
        }

        // 尝试 3: 临时目录
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        if (isDirectoryWritable(tempDir)) {
            cachedUpdateDirectory = tempDir;
            return cachedUpdateDirectory;
        }

        // 最后：返回当前目录
        cachedUpdateDirectory = Paths.get(System.getProperty("user.dir", "."));
        return cachedUpdateDirectory;
    }

    /**
     * 获取 JAR 所在目录
     */
    private static Path getJarDirectory() {
        Path jarPath = Env.getJarPath();
        if (jarPath != null) {
            return jarPath.getParent();
        }
        return null;
    }

    /**
     * 获取用户主目录下的 .mcpatch 目录
     */
    private static Path getHomeMcpatchDir() {
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isEmpty()) {
            return Paths.get(userHome, ".mcpatch", "updates");
        }
        return null;
    }

    /**
     * 检查目录是否可写
     */
    private static boolean isDirectoryWritable(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return false;
        }

        try {
            Path testFile = dir.resolve(".write-test-" + System.currentTimeMillis());
            Files.createFile(testFile);
            Files.delete(testFile);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取新版本文件路径
     * 根据平台自动选择最佳位置
     */
    public static Path getNewVersionPath() {
        Path jarPath = Env.getJarPath();
        Path updateDir = getWritableUpdateDirectory();

        if (jarPath != null && jarPath.getParent().equals(updateDir)) {
            // JAR 目录可写，放在 JAR 同目录
            return jarPath.resolveSibling(jarPath.getFileName() + ".new");
        } else {
            // 使用备用目录
            return updateDir.resolve("mcpatch-update-new.jar");
        }
    }

    /**
     * 获取标记文件路径
     */
    public static Path getMarkerFilePath() {
        Path jarPath = Env.getJarPath();
        Path updateDir = getWritableUpdateDirectory();

        if (jarPath != null && jarPath.getParent().equals(updateDir)) {
            // JAR 目录可写，放在 JAR 同目录
            return jarPath.resolveSibling(jarPath.getFileName() + ".update-pending");
        } else {
            // 使用备用目录
            return updateDir.resolve(".mcpatch-selfupdate-marker");
        }
    }

    /**
     * 获取备份文件路径
     */
    public static Path getBackupPath() {
        Path jarPath = Env.getJarPath();
        if (jarPath != null) {
            return jarPath.resolveSibling(jarPath.getFileName() + ".backup");
        }
        return null;
    }

    /**
     * 获取平台信息字符串
     */
    public static String getPlatformInfo() {
        Platform platform = detectPlatform();
        StringBuilder info = new StringBuilder();
        info.append("平台: ").append(platform.name()).append("\n");
        info.append("操作系统: ").append(System.getProperty("os.name")).append("\n");
        info.append("架构: ").append(System.getProperty("os.arch")).append("\n");
        info.append("Java: ").append(System.getProperty("java.version")).append("\n");
        info.append("更新目录: ").append(getWritableUpdateDirectory());
        return info.toString();
    }
}