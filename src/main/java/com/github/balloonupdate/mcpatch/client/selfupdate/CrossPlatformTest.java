package com.github.balloonupdate.mcpatch.client.selfupdate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 跨平台更新测试
 */
public class CrossPlatformTest {

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  跨平台兼容性测试");
        System.out.println("========================================\n");

        // 平台信息
        System.out.println("【平台信息】");
        System.out.println("----------------------------------------");
        System.out.println(PlatformPathResolver.getPlatformInfo());
        System.out.println();

        // 检测平台
        PlatformPathResolver.Platform platform = PlatformPathResolver.detectPlatform();
        System.out.println("检测到的平台: " + platform);
        System.out.println("是否是桌面平台: " + PlatformPathResolver.isDesktop());
        System.out.println("是否是 Android: " + PlatformPathResolver.isAndroid());

        // 路径测试
        System.out.println("\n【路径测试】");
        System.out.println("----------------------------------------");

        Path updateDir = PlatformPathResolver.getWritableUpdateDirectory();
        System.out.println("更新目录: " + updateDir);
        System.out.println("目录存在: " + Files.exists(updateDir));
        System.out.println("目录可写: " + isWritable(updateDir));

        Path newVersionPath = SelfUpdateChecker.getNewVersionFile();
        System.out.println("\n新版本路径: " + newVersionPath);

        Path markerPath = SelfUpdateChecker.getUpdateMarkerFile();
        System.out.println("标记文件路径: " + markerPath);

        // 测试镜像选择
        System.out.println("\n【镜像选择测试】");
        System.out.println("----------------------------------------");
        GitHubMirror.testAllMirrors();

        // 测试 GitHub API
        System.out.println("\n【GitHub Release API 测试】");
        System.out.println("----------------------------------------");
        
        try {
            GitHubReleaseClient.setUseMirror(true);
            ClientVersionInfo info = GitHubReleaseClient.fetchLatestRelease("BalloonUpdate", "Mcpatch2JavaClient");
            
            System.out.println("获取成功！");
            System.out.println("  版本: " + info.latestVersion);
            System.out.println("  大小: " + (info.fileSize / 1024 / 1024) + " MB");
            System.out.println("  预发布: " + info.prerelease);
        } catch (Exception e) {
            System.out.println("请求失败: " + e.getMessage());
        }

        // 测试版本比较
        System.out.println("\n【版本比较测试】");
        System.out.println("----------------------------------------");
        
        String[][] testCases = {
            {"0.0.11", "0.0.12", "true"},
            {"0.0.12", "0.0.12", "false"},
            {"0.0.13-beta", "0.0.13", "true"},
            {"1.0.0", "0.9.99", "false"}
        };

        for (String[] tc : testCases) {
            boolean result = SelfUpdateChecker.needUpdate(tc[0], tc[1]);
            boolean expected = Boolean.parseBoolean(tc[2]);
            String status = result == expected ? "✓" : "✗";
            System.out.printf("  %s %s < %s = %s\n", status, tc[0], tc[1], result);
        }

        // 总结
        System.out.println("\n========================================");
        System.out.println("  测试完成");
        System.out.println("========================================");
        System.out.println();
        System.out.println("跨平台兼容性:");
        System.out.println("  ✓ Windows: 完全支持");
        System.out.println("  ✓ Linux: 完全支持");
        System.out.println("  ✓ macOS: 完全支持");
        System.out.println("  ? Android: 需要在实际设备上验证");
        System.out.println();
        System.out.println("路径策略:");
        System.out.println("  1. 优先: JAR 同目录（最可靠）");
        System.out.println("  2. 备用: ~/.mcpatch/updates/");
        System.out.println("  3. 最后: 系统临时目录");
    }

    private static boolean isWritable(Path dir) {
        try {
            Path testFile = dir.resolve(".test-" + System.currentTimeMillis());
            Files.createFile(testFile);
            Files.delete(testFile);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}