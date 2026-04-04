package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.config.AppConfig;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.utils.Env;

import javax.swing.*;
import java.awt.*;

/**
 * 客户端更新进度窗口
 * 在更新时显示进度，避免用户以为程序卡死
 */
public class SelfUpdateWindow {
    
    private static JFrame frame;
    private static JLabel statusLabel;
    private static JProgressBar progressBar;
    private static JTextArea logArea;
    private static StringBuilder logBuffer = new StringBuilder();
    
    /**
     * 显示更新窗口
     */
    public static void showWindow(String title) {
        if (frame != null) {
            return; // 已经显示了
        }
        
        // 在事件线程中创建窗口
        SwingUtilities.invokeLater(() -> {
            createWindow(title);
        });
        
        // 等待窗口创建
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
    }
    
    private static void createWindow(String title) {
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(450, 300);
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // 状态标签
        statusLabel = new JLabel("正在检查客户端更新...");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        panel.add(statusLabel, BorderLayout.NORTH);
        
        // 进度条
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(400, 25));
        panel.add(progressBar, BorderLayout.CENTER);
        
        // 日志区域
        logArea = new JTextArea();
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setEditable(false);
        logArea.setBackground(new Color(245, 245, 245));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(400, 150));
        panel.add(scrollPane, BorderLayout.SOUTH);
        
        frame.add(panel);
        frame.setVisible(true);
    }
    
    /**
     * 更新状态文本
     */
    public static void updateStatus(String status) {
        String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String logLine = "[" + timestamp + "] " + status;
        
        logBuffer.append(logLine).append("\n");
        Log.info(status);
        
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText(status);
            }
            if (logArea != null) {
                logArea.setText(logBuffer.toString());
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }
    
    /**
     * 设置进度
     * @param percent 进度百分比 (0-100)
     */
    public static void setProgress(int percent) {
        SwingUtilities.invokeLater(() -> {
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setValue(percent);
                progressBar.setStringPainted(true);
                progressBar.setString(percent + "%");
            }
        });
    }
    
    /**
     * 设置下载进度
     * @param downloaded 已下载字节
     * @param total 总字节数
     */
    public static void setDownloadProgress(long downloaded, long total) {
        int percent = total > 0 ? (int) (downloaded * 100 / total) : 0;
        String downloadedStr = formatSize(downloaded);
        String totalStr = formatSize(total);
        
        SwingUtilities.invokeLater(() -> {
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setValue(percent);
                progressBar.setStringPainted(true);
                progressBar.setString(downloadedStr + " / " + totalStr);
            }
        });
    }
    
    /**
     * 关闭窗口
     */
    public static void closeWindow() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
                statusLabel = null;
                progressBar = null;
                logArea = null;
                logBuffer = new StringBuilder();
            }
        });
    }
    
    /**
     * 格式化文件大小
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
    
    /**
     * 检查窗口是否显示
     */
    public static boolean isShowing() {
        return frame != null && frame.isShowing();
    }
}