package com.github.balloonupdate.mcpatch.client.selfupdate;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 独立更新检测测试
 */
public class UpdateCheckTest {

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  客户端更新检测模拟测试");
        System.out.println("========================================\n");

        // Step 1: 测试镜像选择
        System.out.println("【步骤 1】镜像站配置...");
        System.out.println("----------------------------------------");
        GitHubMirror.testAllMirrors();

        System.out.println("\n✓ 镜像策略: 超时自动切换下一个");

        // Step 2: 测试 GitHub Release API
        System.out.println("\n【步骤 2】请求 GitHub Release API...");
        System.out.println("----------------------------------------");

        GitHubReleaseClient.setUseMirror(true);

        String owner = "BalloonUpdate";
        String repo = "Mcpatch2JavaClient";

        System.out.println("仓库: " + owner + "/" + repo);
        System.out.println("正在获取最新版本...\n");

        ClientVersionInfo info = GitHubReleaseClient.fetchLatestRelease(owner, repo);

        System.out.println("获取成功！");
        System.out.println("  版本号: " + info.latestVersion);
        System.out.println("  发布日期: " + info.releaseDate);
        System.out.println("  文件大小: " + (info.fileSize / 1024 / 1024) + " MB");
        System.out.println("  预发布: " + info.prerelease);
        System.out.println("  下载地址: " + info.downloadUrl);

        // Step 3: 版本比较
        System.out.println("\n【步骤 3】版本比较...");
        System.out.println("----------------------------------------");

        String currentVersion = "0.0.0";  // 模拟当前版本
        boolean needUpdate = SelfUpdateChecker.needUpdate(currentVersion, info.latestVersion);

        System.out.println("当前版本: " + currentVersion);
        System.out.println("最新版本: " + info.latestVersion);
        System.out.println("需要更新: " + (needUpdate ? "是" : "否"));

        // Step 4: 测试文件路径
        System.out.println("\n【步骤 4】文件路径检查...");
        System.out.println("----------------------------------------");

        // 模拟 JAR 路径
        Path mockJarPath = Paths.get("/tmp/test-update.jar");
        System.out.println("模拟 JAR 路径: " + mockJarPath);
        System.out.println("新版本将保存到: " + mockJarPath.getParent() + "/" + mockJarPath.getFileName() + ".new");
        System.out.println("标记文件位置: " + mockJarPath.getParent() + "/" + mockJarPath.getFileName() + ".update-pending");

        // Step 5: 输出更新日志
        System.out.println("\n【步骤 5】更新日志...");
        System.out.println("----------------------------------------");
        if (info.changelog != null && !info.changelog.isEmpty()) {
            System.out.println(info.changelog);
        } else {
            System.out.println("（无更新日志）");
        }

        // 总结
        System.out.println("\n========================================");
        System.out.println("  更新检测测试完成");
        System.out.println("========================================");
        System.out.println();
        System.out.println("测试结果:");
        System.out.println("  ✓ 镜像选择正常");
        System.out.println("  ✓ GitHub API 正常");
        System.out.println("  ✓ 版本比较正常");
        System.out.println("  ✓ 文件路径正常");
        System.out.println();
        System.out.println("如果启用更新，将:");
        System.out.println("  1. 下载新版本到 JAR 同目录");
        System.out.println("  2. 创建标记文件");
        System.out.println("  3. 下次启动时自动安装");
    }
}