package com.github.balloonupdate.mcpatch.client.selfupdate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试更新窗口
 */
public class WindowTest {

    public static void main(String[] args) throws Exception {
        System.out.println("测试更新窗口...");
        
        // 显示窗口
        SelfUpdateWindow.showWindow("Mcpatch 客户端更新测试");
        Thread.sleep(500);
        
        // 模拟检查过程
        SelfUpdateWindow.updateStatus("正在检查客户端更新...");
        Thread.sleep(1000);
        
        SelfUpdateWindow.updateStatus("正在从 GitHub 获取版本信息...");
        Thread.sleep(1000);
        
        // 模拟下载过程
        SelfUpdateWindow.updateStatus("发现新版本 0.0.12，正在下载...");
        Thread.sleep(500);
        
        // 模拟下载进度
        long total = 7 * 1024 * 1024; // 7 MB
        for (int i = 0; i <= 100; i += 5) {
            long downloaded = total * i / 100;
            SelfUpdateWindow.setDownloadProgress(downloaded, total);
            Thread.sleep(100);
        }
        
        SelfUpdateWindow.updateStatus("下载完成，将在下次启动时安装");
        Thread.sleep(1500);
        
        // 关闭窗口
        SelfUpdateWindow.closeWindow();
        
        System.out.println("测试完成！");
    }
}