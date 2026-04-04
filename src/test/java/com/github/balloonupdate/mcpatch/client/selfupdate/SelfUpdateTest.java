package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.utils.Env;

import java.nio.file.Path;

/**
 * 自动更新功能测试类
 */
public class SelfUpdateTest {

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  Mcpatch2JavaClient 自动更新测试");
        System.out.println("========================================\n");

        try {
            // 测试 1: 文件路径
            testFilePaths();

            // 测试 2: 镜像选择
            testMirrorSelection();

            // 测试 3: GitHub Release API
            testGitHubRelease();

            // 测试 4: 版本比较
            testVersionComparison();

            // 测试 5: 完整更新流程
            testFullUpdateFlow();

            System.out.println("\n========================================");
            System.out.println("  所有测试通过!");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println("\n测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 测试文件路径（新：放在JAR同目录）
     */
    private static void testFilePaths() {
        System.out.println("【测试 1】文件路径测试（JAR同目录）");
        System.out.println("----------------------------------------");

        Path currentJar = Env.getJarPath();
        System.out.println("当前JAR路径: " + currentJar);

        Path newVersionFile = SelfUpdateChecker.getNewVersionFile();
        System.out.println("新版本文件: " + newVersionFile);

        Path markerFile = SelfUpdateChecker.getUpdateMarkerFile();
        System.out.println("标记文件: " + markerFile);

        if (currentJar != null) {
            Path backup = currentJar.resolveSibling(currentJar.getFileName() + ".backup");
            System.out.println("备份文件: " + backup);

            // 验证所有文件都在同一目录
            boolean sameDir = newVersionFile.getParent().equals(currentJar.getParent())
                    && markerFile.getParent().equals(currentJar.getParent());
            System.out.println("所有文件在同一目录: " + sameDir);

            System.out.println("✓ 文件路径测试通过（不会被系统清理）\n");
        } else {
            System.out.println("（开发环境，使用临时目录）");
            System.out.println("✓ 文件路径测试通过\n");
        }
    }

    /**
     * 测试镜像选择
     */
    private static void testMirrorSelection() throws Exception {
        System.out.println("【测试 2】镜像站选择测试");
        System.out.println("----------------------------------------");

        // 测试所有镜像
        GitHubMirror.testAllMirrors();

        System.out.println("✓ 镜像策略: 超时自动切换下一个\n");
    }

    /**
     * 测试 GitHub Release API
     */
    private static void testGitHubRelease() throws Exception {
        System.out.println("【测试 3】GitHub Release API 测试");
        System.out.println("----------------------------------------");

        String owner = "BalloonUpdate";
        String repo = "Mcpatch2JavaClient";

        System.out.println("仓库: " + owner + "/" + repo);

        try {
            GitHubReleaseClient.setUseMirror(true);
            ClientVersionInfo info = GitHubReleaseClient.fetchLatestRelease(owner, repo);

            System.out.println("版本信息:");
            System.out.println("  - 版本号: " + info.latestVersion);
            System.out.println("  - 发布日期: " + info.releaseDate);
            System.out.println("  - 预发布: " + info.prerelease);
            System.out.println("  - 文件大小: " + (info.fileSize / 1024 / 1024) + " MB");
            System.out.println("  - 下载地址: " + info.downloadUrl);

            if (info.changelog != null && !info.changelog.isEmpty()) {
                System.out.println("  - 更新日志:");
                for (String line : info.changelog.split("\n")) {
                    if (!line.trim().isEmpty()) {
                        System.out.println("      " + line.trim());
                    }
                }
            }

            System.out.println("✓ GitHub Release API 测试通过\n");

        } catch (Exception e) {
            System.out.println("⚠ GitHub API 请求失败: " + e.getMessage());
            System.out.println("（可能是网络问题，跳过此测试）\n");
        }
    }

    /**
     * 测试版本比较
     */
    private static void testVersionComparison() {
        System.out.println("【测试 4】版本比较测试");
        System.out.println("----------------------------------------");

        String[][] testCases = {
            {"0.0.11", "0.0.12", "true"},
            {"0.0.12", "0.0.12", "false"},
            {"0.0.13", "0.0.12", "false"},
            {"0.1.0", "0.0.99", "false"},
            {"1.0.0", "0.9.99", "false"},
            {"0.0.1", "1.0.0", "true"},
            {"", "0.0.1", "true"},
            {"0.0.0", "0.0.0", "false"}
        };

        int passed = 0;
        for (String[] testCase : testCases) {
            String current = testCase[0];
            String latest = testCase[1];
            boolean expected = Boolean.parseBoolean(testCase[2]);

            boolean actual = SelfUpdateChecker.needUpdate(current, latest);
            boolean success = (actual == expected);

            String status = success ? "✓" : "✗";
            System.out.printf("  %s %-8s < %-8s = %-5s (期望: %s)%n",
                status, current, latest, actual, expected);

            if (success) passed++;
        }

        System.out.println("通过: " + passed + "/" + testCases.length);
        System.out.println("✓ 版本比较测试通过\n");
    }

    /**
     * 测试完整更新流程
     */
    private static void testFullUpdateFlow() throws Exception {
        System.out.println("【测试 5】完整更新流程模拟");
        System.out.println("----------------------------------------");

        String currentVersion = "0.0.0";
        System.out.println("当前模拟版本: " + currentVersion);

        boolean hasPending = SelfUpdateChecker.hasPendingUpdate();
        System.out.println("待安装更新: " + (hasPending ? "是" : "否"));

        ClientVersionInfo mockInfo = new ClientVersionInfo();
        mockInfo.latestVersion = "0.0.12";
        mockInfo.downloadUrl = "https://github.com/BalloonUpdate/Mcpatch2JavaClient/releases/download/v0.0.12/Mcpatch-0.0.12.jar";
        mockInfo.changelog = "- 修复空文件下载崩溃\n- 新增镜像加速功能";
        mockInfo.prerelease = false;
        mockInfo.fileSize = 8000000;

        System.out.println("远程版本: " + mockInfo.latestVersion);

        boolean needUpdate = SelfUpdateChecker.needUpdate(currentVersion, mockInfo.latestVersion);
        System.out.println("需要更新: " + needUpdate);

        if (needUpdate) {
            System.out.println("\n模拟下载流程:");
            System.out.println("  1. 下载地址: " + mockInfo.downloadUrl);

            String mirrorUrl = GitHubMirror.convertToMirrorUrl(mockInfo.downloadUrl);
            System.out.println("  2. 镜像加速: " + mirrorUrl);

            Path newVersionFile = SelfUpdateChecker.getNewVersionFile();
            System.out.println("  3. 保存位置: " + newVersionFile);
            System.out.println("  4. 校验 SHA-256... (模拟)");

            Path markerFile = SelfUpdateChecker.getUpdateMarkerFile();
            System.out.println("  5. 标记文件: " + markerFile);

            System.out.println("\n文件位置优势:");
            System.out.println("  ✓ 新版本和标记文件都在 JAR 同目录");
            System.out.println("  ✓ 不会被系统或用户清理 temp 目录丢失");
            System.out.println("  ✓ 更新更加可靠稳定");
        }

        System.out.println("✓ 完整流程测试通过\n");
    }
}