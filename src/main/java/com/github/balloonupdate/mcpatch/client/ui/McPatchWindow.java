package com.github.balloonupdate.mcpatch.client.ui;

import com.github.balloonupdate.mcpatch.client.utils.BytesUtils;
import com.github.kasuminova.GUI.SetupSwing;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 更新主窗口
 *
 * 布局设计：
 * ┌─────────────── McPatch 更新 ───────────────┐
 * │                                              │
 * │  正在下载更新文件 - 已完成 3/15              │  statusLabel (粗体)
 * │  v1.0.2 → v2.5.0                            │  versionLabel (灰色)
 * │  ─────────────────────────────               │
 * │  当前: CSMCMod-3.70.jar                     │  fileLabel
 * │  [████████████░░░░░░░░] 68.0%               │  fileProgressBar
 * │  162.14 MB / 239.14 MB                      │  fileProgressLabel (灰色)
 * │  ─────────────────────────────               │
 * │  总进度                                      │  totalLabel (粗体)
 * │  [████████░░░░░░░░░░░░] 45.0%               │  totalProgressBar
 * │  327.5 MB / 712.8 MB  12.5 MB/s  剩余 31秒  │  totalProgressLabel (灰色)
 * │                                              │
 * │  ✓检查更新 ✓元数据 ●下载 ○应用 ○完成        │  phaseLabel
 * └──────────────────────────────────────────────┘
 */
public class McPatchWindow {
    int width;
    int height;

    JFrame window;

    // UI 组件
    JLabel statusLabel;            // 主状态文本
    JLabel versionLabel;           // 版本信息
    JLabel fileLabel;              // 当前文件名
    JProgressBar fileProgressBar;  // 当前文件进度条
    JLabel fileProgressLabel;      // 当前文件进度文本
    JProgressBar totalProgressBar; // 总进度条
    JLabel totalProgressLabel;     // 总进度文本 + 速度 + ETA
    JLabel phaseLabel;             // 阶段指示器

    // 向后兼容别名
    JLabel label;
    JLabel labelSecondary;
    JProgressBar progressBar;

    public OnWindowClosing onWindowClosing;

    private volatile int currentPhase = -1;

    private static final String[] PHASE_NAMES = {"检查更新", "下载元数据", "下载文件", "应用更新", "完成"};

    public McPatchWindow(int width, int height) {
        this.width = width;
        this.height = height;

        window = new JFrame();
        window.setTitle("McPatch 更新");

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(new EmptyBorder(15, 20, 15, 20));

        // === 状态 + 版本区域 ===
        statusLabel = new JLabel("正在检测更新");
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        mainPanel.add(statusLabel);
        mainPanel.add(Box.createVerticalStrut(2));

        versionLabel = new JLabel(" ");
        versionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        versionLabel.setFont(versionLabel.getFont().deriveFont(12f));
        versionLabel.setForeground(new Color(120, 120, 120));
        mainPanel.add(versionLabel);
        mainPanel.add(Box.createVerticalStrut(8));

        // 分隔线
        JSeparator sep1 = new JSeparator();
        sep1.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(sep1);
        mainPanel.add(Box.createVerticalStrut(8));

        // === 当前文件进度区域 ===
        fileLabel = new JLabel(" ");
        fileLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileLabel.setFont(fileLabel.getFont().deriveFont(12f));
        mainPanel.add(fileLabel);
        mainPanel.add(Box.createVerticalStrut(3));

        fileProgressBar = new JProgressBar(0, 1000);
        fileProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileProgressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        fileProgressBar.setStringPainted(true);
        fileProgressBar.setString("0.0%");
        mainPanel.add(fileProgressBar);
        mainPanel.add(Box.createVerticalStrut(2));

        fileProgressLabel = new JLabel(" ");
        fileProgressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileProgressLabel.setFont(fileProgressLabel.getFont().deriveFont(11f));
        fileProgressLabel.setForeground(new Color(120, 120, 120));
        mainPanel.add(fileProgressLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        // 分隔线
        JSeparator sep2 = new JSeparator();
        sep2.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(sep2);
        mainPanel.add(Box.createVerticalStrut(8));

        // === 总进度区域 ===
        JLabel totalTitle = new JLabel("总进度");
        totalTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalTitle.setFont(totalTitle.getFont().deriveFont(Font.BOLD, 13f));
        mainPanel.add(totalTitle);
        mainPanel.add(Box.createVerticalStrut(3));

        totalProgressBar = new JProgressBar(0, 1000);
        totalProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalProgressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        totalProgressBar.setStringPainted(true);
        totalProgressBar.setString("0.0%");
        mainPanel.add(totalProgressBar);
        mainPanel.add(Box.createVerticalStrut(2));

        totalProgressLabel = new JLabel(" ");
        totalProgressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalProgressLabel.setFont(totalProgressLabel.getFont().deriveFont(12f));
        totalProgressLabel.setForeground(new Color(120, 120, 120));
        mainPanel.add(totalProgressLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        // === 阶段指示器 ===
        phaseLabel = new JLabel(" ");
        phaseLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        phaseLabel.setFont(phaseLabel.getFont().deriveFont(11f));
        mainPanel.add(phaseLabel);

        // 向后兼容别名
        label = statusLabel;
        labelSecondary = fileLabel;
        progressBar = totalProgressBar;

        window.setContentPane(mainPanel);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.setResizable(false);
        window.setLocationRelativeTo(null);

        McPatchWindow that = this;

        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (onWindowClosing != null)
                    onWindowClosing.run(that);
                else
                    destroy();
            }
        });
    }

    public McPatchWindow() {
        this(480, 320);
    }

    // ===== 新增方法 =====

    /**
     * 设置版本信息（如 "v1.0.2 → v2.5.0"）
     */
    public void setVersionInfo(String from, String to) {
        Runnable task = () -> {
            if (from == null || from.isEmpty()) {
                versionLabel.setText("更新到 " + to);
            } else {
                versionLabel.setText(from + " \u2192 " + to);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 设置当前文件的下载进度
     *
     * @param filename  文件名
     * @param downloaded 已下载字节
     * @param totalSize  文件总大小
     */
    public void setFileProgress(String filename, long downloaded, long totalSize) {
        Runnable task = () -> {
            fileLabel.setText(filename != null ? filename : " ");
            if (totalSize > 0) {
                int permille = (int) Math.min(1000, downloaded * 1000 / totalSize);
                fileProgressBar.setValue(permille);
                fileProgressBar.setString(String.format("%.1f%%", permille / 10.0));
                fileProgressLabel.setText(BytesUtils.convertBytes(downloaded) + " / " + BytesUtils.convertBytes(totalSize));
            } else {
                fileProgressBar.setValue(0);
                fileProgressBar.setString("0.0%");
                fileProgressLabel.setText(" ");
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 设置总下载进度（带速度和预估剩余时间）
     *
     * @param downloaded 已下载总字节
     * @param totalBytes 总字节数
     * @param speed      速度字符串（如 "12.5 MB"）
     * @param eta        预估剩余时间（如 "31秒"），可为 null
     */
    public void setTotalProgress(long downloaded, long totalBytes, String speed, String eta) {
        Runnable task = () -> {
            if (totalBytes > 0) {
                int permille = (int) Math.min(1000, downloaded * 1000 / totalBytes);
                totalProgressBar.setValue(permille);
                totalProgressBar.setString(String.format("%.1f%%", permille / 10.0));

                StringBuilder sb = new StringBuilder();
                sb.append(BytesUtils.convertBytes(downloaded)).append(" / ").append(BytesUtils.convertBytes(totalBytes));
                if (speed != null && !speed.isEmpty()) {
                    sb.append("    ").append(speed).append("/s");
                }
                if (eta != null && !eta.isEmpty()) {
                    sb.append("    ").append(eta);
                }
                totalProgressLabel.setText(sb.toString());
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 设置当前阶段
     * 0=检查更新, 1=下载元数据, 2=下载文件, 3=应用更新, 4=完成
     */
    public void setPhase(int phase) {
        if (phase == currentPhase) return;
        currentPhase = phase;
        Runnable task = () -> {
            StringBuilder sb = new StringBuilder("<html>");
            for (int i = 0; i < PHASE_NAMES.length; i++) {
                if (i > 0) sb.append("&nbsp;&nbsp;");
                if (i < phase) {
                    // 已完成阶段 - 绿色勾
                    sb.append("<span style='color:#4CAF50'>&#10003;</span> ").append(PHASE_NAMES[i]);
                } else if (i == phase) {
                    // 当前阶段 - 蓝色圆点 + 加粗
                    sb.append("<span style='color:#1976D2'>&#9679;</span> <b>").append(PHASE_NAMES[i]).append("</b>");
                } else {
                    // 未到达阶段 - 灰色空心圆
                    sb.append("<span style='color:#BBBBBB'>&#9675;</span> ").append(PHASE_NAMES[i]);
                }
            }
            sb.append("</html>");
            phaseLabel.setText(sb.toString());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 清空文件进度区域（用于非下载阶段）
     */
    public void clearFileProgress() {
        Runnable task = () -> {
            fileLabel.setText(" ");
            fileProgressBar.setValue(0);
            fileProgressBar.setString("0.0%");
            fileProgressLabel.setText(" ");
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    // ===== 向后兼容方法 =====

    public void setTitleText(String value) {
        Runnable task = () -> window.setTitle(value);
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public void show() {
        Runnable task = () -> {
            window.pack();
            Dimension prefSize = window.getPreferredSize();
            int w = Math.max(prefSize.width + 30, 480);
            int h = Math.max(prefSize.height + 15, 310);
            window.setSize(w, h);
            window.setLocationRelativeTo(null);
            window.setVisible(true);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public void hide() {
        Runnable task = () -> window.setVisible(false);
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public void destroy() {
        Runnable task = () -> window.dispose();
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 进度条下方的文字（总进度详情）
     */
    public void setProgressBarText(String value) {
        Runnable task = () -> {
            totalProgressLabel.setText(value);
            totalProgressLabel.setToolTipText(value);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 总进度条的值 (0-1000)
     */
    public void setProgressBarValue(int value) {
        Runnable task = () -> {
            totalProgressBar.setValue(value);
            totalProgressBar.setString(String.format("%.1f%%", value / 10.0));
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 主状态标签文字
     */
    public void setLabelText(String value) {
        Runnable task = () -> {
            statusLabel.setText(value);
            statusLabel.setToolTipText(value);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 副标签文字（当前文件名）
     */
    public void setLabelSecondaryText(String value) {
        Runnable task = () -> {
            fileLabel.setToolTipText(value);
            fileLabel.setText(value);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    @FunctionalInterface
    public interface OnWindowClosing {
        void run(McPatchWindow window);
    }

    // 开发时调试用
    public static void main(String[] args) {
        SetupSwing.init();
        McPatchWindow w = new McPatchWindow();
        w.show();
        w.setVersionInfo("v1.0.2", "v2.5.0");
        w.setPhase(2);
        w.setLabelText("正在下载更新文件 - 已完成 3/15");
        w.setFileProgress("CSMCMod-3.70.jar", 162000000L, 239000000L);
        w.setTotalProgress(327500000L, 712800000L, "12.5 MB", "预计剩余: 31秒");
    }
}
