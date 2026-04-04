package com.github.balloonupdate.mcpatch.client.selfupdate;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 跨平台环境检查
 */
public class PlatformCheckTest {

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  跨平台环境兼容性检查");
        System.out.println("========================================\n");

        // 系统信息
        System.out.println("【系统信息】");
        System.out.println("----------------------------------------");
        System.out.println("操作系统: " + System.getProperty("os.name"));
        System.out.println("系统版本: " + System.getProperty("os.version"));
        System.out.println("系统架构: " + System.getProperty("os.arch"));
        System.out.println("Java 版本: " + System.getProperty("java.version"));
        System.out.println("Java 供应商: " + System.getProperty("java.vendor"));

        // 文件系统信息
        System.out.println("\n【文件系统信息】");
        System.out.println("----------------------------------------");
        System.out.println("文件分隔符: '" + System.getProperty("file.separator") + "'");
        System.out.println("路径分隔符: '" + System.getProperty("path.separator") + "'");
        System.out.println("行分隔符: " + (System.getProperty("line.separator").equals("\n") ? "\\n" : "\\r\\n"));
        System.out.println("临时目录: " + System.getProperty("java.io.tmpdir"));
        System.out.println("用户目录: " + System.getProperty("user.home"));
        System.out.println("当前目录: " + System.getProperty("user.dir"));

        // JAR 路径检测
        System.out.println("\n【JAR 路径检测】");
        System.out.println("----------------------------------------");
        
        Path jarPath = getJarPath();
        System.out.println("JAR 路径: " + jarPath);

        if (jarPath != null) {
            System.out.println("父目录: " + jarPath.getParent());
            System.out.println("文件名: " + jarPath.getFileName());
            
            // 模拟新版本文件路径
            Path newVersion = jarPath.resolveSibling(jarPath.getFileName() + ".new");
            Path markerFile = jarPath.resolveSibling(jarPath.getFileName() + ".update-pending");
            Path backupFile = jarPath.resolveSibling(jarPath.getFileName() + ".backup");
            
            System.out.println("\n【更新文件路径（模拟）】");
            System.out.println("----------------------------------------");
            System.out.println("新版本: " + newVersion);
            System.out.println("标记文件: " + markerFile);
            System.out.println("备份文件: " + backupFile);
            
            // 路径有效性检查
            System.out.println("\n【路径有效性检查】");
            System.out.println("----------------------------------------");
            System.out.println("父目录是否存在: " + java.nio.file.Files.exists(jarPath.getParent()));
            System.out.println("父目录是否可写: 检查中...");
            
            try {
                Path testFile = jarPath.resolveSibling(".write-test-" + System.currentTimeMillis());
                java.nio.file.Files.createFile(testFile);
                java.nio.file.Files.delete(testFile);
                System.out.println("父目录可写: ✅ 是");
            } catch (Exception e) {
                System.out.println("父目录可写: ❌ 否 (" + e.getMessage() + ")");
            }
        } else {
            System.out.println("⚠ 开发环境运行，无法获取 JAR 路径");
            System.out.println("在实际 JAR 环境中运行时可正常获取路径");
        }

        // 平台兼容性总结
        System.out.println("\n========================================");
        System.out.println("  平台兼容性总结");
        System.out.println("========================================");
        System.out.println();
        
        String osName = System.getProperty("os.name").toLowerCase();
        
        if (osName.contains("win")) {
            System.out.println("Windows 平台: ✅ 完全支持");
            System.out.println("  - 路径处理已有特殊适配");
            System.out.println("  - 文件系统无限制");
        } else if (osName.contains("mac")) {
            System.out.println("macOS 平台: ✅ 完全支持");
            System.out.println("  - 标准文件系统");
            System.out.println("  - 需要有写入权限");
        } else if (osName.contains("nux") || osName.contains("nix")) {
            System.out.println("Linux 平台: ✅ 完全支持");
            System.out.println("  - 标准文件系统");
            System.out.println("  - 需要有写入权限");
        } else if (osName.contains("android")) {
            System.out.println("Android 平台: ⚠️ 需要验证");
            System.out.println("  - 依赖启动器实现");
            System.out.println("  - 可能需要特殊权限");
        } else {
            System.out.println("未知平台: ❓ 需要验证");
        }
        
        System.out.println();
        System.out.println("注意事项:");
        System.out.println("  - 确保 JAR 所在目录有写入权限");
        System.out.println("  - Android 设备需要测试具体启动器");
        System.out.println("  - 部分启动器可能有沙箱限制");
    }

    private static Path getJarPath() {
        try {
            String url = java.net.URLDecoder.decode(
                PlatformCheckTest.class.getProtectionDomain().getCodeSource().getLocation().getFile(), 
                "UTF-8"
            ).replace("\\", "/");

            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");

            if (windows && url.startsWith("/")) {
                url = url.substring(1);
            }

            if (url.endsWith(".class") && url.contains("!")) {
                String path = url.substring(0, url.lastIndexOf("!"));
                if (path.contains("file:/")) {
                    path = path.substring(path.indexOf("file:/") + "file:/".length());
                }
                return Paths.get(path);
            } else if (url.endsWith(".jar")) {
                return Paths.get(url);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}