package com.github.balloonupdate.mcpatch.client;

import com.github.balloonupdate.mcpatch.client.data.Range;
import com.github.balloonupdate.mcpatch.client.data.TempUpdateFile;
import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.network.ServerSession;
import com.github.balloonupdate.mcpatch.client.network.Servers;
import com.github.balloonupdate.mcpatch.client.ui.McPatchWindow;
import com.github.balloonupdate.mcpatch.client.utils.BytesUtils;
import com.github.balloonupdate.mcpatch.client.utils.HashUtility;
import com.github.balloonupdate.mcpatch.client.utils.PathUtility;
import com.github.balloonupdate.mcpatch.client.utils.SpeedStat;
import com.github.balloonupdate.mcpatch.client.config.AppConfig;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多文件并行下载协调器。
 * 管理线程池、任务分发、进度统计和失败重试。
 */
public class ParallelDownloader {
    private final Servers servers;
    private final AppConfig config;
    private final McPatchWindow window;
    private final int threadCount;

    /**
     * 已下载的总字节数（线程安全）
     */
    private final AtomicLong totalDownloaded;

    /**
     * 需要下载的总字节数
     */
    private final long totalBytes;

    /**
     * 速度统计器（已加synchronized保证线程安全）
     */
    private final SpeedStat speed;

    /**
     * UI刷新限频计时器（线程安全）
     */
    private final AtomicLong uiTimer;

    /**
     * 总文件数
     */
    private final int totalFileCount;

    /**
     * 已成功完成的文件数（线程安全，仅下载成功时递增）
     */
    private final AtomicInteger completedFileCount = new AtomicInteger(0);

    /**
     * 焦点文件管理器（与 ChunkedDownloader 共享同一个实例）
     */
    private final FocusManager focusManager;

    public ParallelDownloader(Servers servers, AppConfig config, McPatchWindow window,
                              AtomicLong totalDownloaded, long totalBytes,
                              SpeedStat speed, AtomicLong uiTimer, int threadCount, int totalFileCount) {
        this.servers = servers;
        this.config = config;
        this.window = window;
        this.threadCount = threadCount;
        this.totalDownloaded = totalDownloaded;
        this.totalBytes = totalBytes;
        this.speed = speed;
        this.uiTimer = uiTimer;
        this.totalFileCount = totalFileCount;
        this.focusManager = new FocusManager();
    }

    // ===== 焦点文件管理 =====
    // 所有焦点相关逻辑已移至 FocusManager，此处仅做委托

    private void acquireFocus(String filename) {
        focusManager.acquireFocus(filename);
    }

    private boolean isFocusFile(String filename) {
        return focusManager.isFocusFile(filename);
    }

    private String getDisplayName(String filename) {
        return focusManager.getDisplayName(filename);
    }

    // ===== 下载逻辑 =====

    /**
     * 执行并行下载，含重试逻辑。
     * 失败的文件会被收集起来，最多重试3轮。
     */
    public void download(List<TempUpdateFile> updateFiles) throws McpatchBusinessException {
        List<TempUpdateFile> remaining = new ArrayList<>(updateFiles);
        int maxRetries = 3;

        // 创建线程池（在所有重试轮次中复用，避免每轮重建）
        int poolSize = Math.min(threadCount, updateFiles.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "mcpatch-downloader");
            t.setDaemon(true);
            return t;
        });

        try {
            for (int round = 0; round < maxRetries && !remaining.isEmpty(); round++) {
                List<DownloadResult> failures = doParallelDownload(remaining, round, executor);

                if (failures.isEmpty()) {
                    return; // 全部成功
                }

                // 收集失败项准备重试
                remaining.clear();
                for (DownloadResult failure : failures) {
                    Log.warn(String.format("下载失败，将重试: %s, 原因: %s",
                        failure.file.path, failure.exception.getMessage()));
                    remaining.add(failure.file);
                }

                Log.info(String.format("第 %d 轮下载完成，%d 个文件失败，准备重试", round + 1, failures.size()));
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    Log.warn("并行下载线程池未能在10秒内正常终止，已强制关闭");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (!remaining.isEmpty()) {
            throw new McpatchBusinessException(
                String.format("经过 %d 轮重试后仍有 %d 个文件下载失败", maxRetries, remaining.size()));
        }
    }

    /**
     * 执行一轮并行下载
     */
    private List<DownloadResult> doParallelDownload(List<TempUpdateFile> files, int round, ExecutorService executor)
            throws McpatchBusinessException {

        // 创建分片下载器（共享焦点管理器，确保焦点状态一致）
        ChunkedDownloader chunkedDownloader = new ChunkedDownloader(
                servers, config, window, totalDownloaded, totalBytes, speed, uiTimer, executor,
                focusManager);

        // 失败结果收集（线程安全）
        ConcurrentLinkedQueue<DownloadResult> failures = new ConcurrentLinkedQueue<>();

        // 提交下载任务
        List<Future<?>> futures = new ArrayList<>();
        for (TempUpdateFile f : files) {
            // 在外层计算 filename，确保 finally 块中可以访问以释放焦点
            final String filename = PathUtility.getFilename(f.path);
            futures.add(executor.submit(() -> {
                focusManager.incrementActiveDownloadCount();
                boolean success = false;
                try {
                    // 判断是否需要分片下载
                    if (chunkedDownloader.shouldUseChunkedDownload(f)) {
                        chunkedDownloader.download(f);
                    } else {
                        downloadSingleFile(f);
                    }
                    success = true;
                } catch (Exception e) {
                    failures.add(new DownloadResult(f, e));
                } finally {
                    focusManager.decrementActiveDownloadCount();
                    // 释放焦点，让其他正在下载的文件有机会接管单文件进度条
                    focusManager.releaseFocus(filename);
                    // 仅成功完成时递增计数（失败文件将在重试轮次中重新计数）
                    if (success) {
                        int completed = completedFileCount.incrementAndGet();
                        updateStatusUI(completed);
                    }
                }
            }));
        }

        // 等待所有任务完成
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                throw new McpatchBusinessException("用户打断了更新", e);
            } catch (ExecutionException e) {
                // 单个任务的异常已在上方收集，这里不再处理
            }
        }

        // 所有下载完成后，强制刷新总进度（避免限频导致UI未显示到100%）
        forceUpdateTotalProgress();

        return new ArrayList<>(failures);
    }

    /**
     * 更新状态栏UI（文件计数 + ETA）
     */
    private void updateStatusUI(int completedFiles) {
        if (window == null) return;

        SwingUtilities.invokeLater(() -> {
            window.setLabelText(String.format("正在下载更新文件 - 已完成 %d/%d", completedFiles, totalFileCount));
        });
    }

    /**
     * 在当前线程中下载单个文件（含hash校验）。
     * 每次调用会创建独立的 ServerSession，确保线程安全。
     */
    private void downloadSingleFile(TempUpdateFile f) throws Exception {
        String filename = PathUtility.getFilename(f.path);

        try (ServerSession session = servers.createSession()) {
            Log.debug("  开始下载 " + f.path + " (线程: " + Thread.currentThread().getName() + ")");
            Log.debug("    Download " + f.tempPath);

            Path tempDirectory = f.tempPath.getParent();
            Files.createDirectories(tempDirectory);

            // 空文件不需要下载，直接创建空文件并设置修改时间
            if (f.length == 0) {
                if (Files.exists(f.tempPath)) {
                    Files.delete(f.tempPath);
                }
                Files.createFile(f.tempPath);
                Files.setLastModifiedTime(f.tempPath, FileTime.from(f.modified, TimeUnit.SECONDS));
                return;
            }

            // 尝试获取焦点，并显示初始进度
            acquireFocus(filename);
            if (window != null && isFocusFile(filename)) {
                final String displayName = getDisplayName(filename);
                SwingUtilities.invokeLater(() -> {
                    window.setFileProgress(displayName, 0, f.length);
                });
            }

            AtomicLong bytesCounter = new AtomicLong();

            Range range = new Range(f.offset, f.offset + f.length);
            String desc = f.path + " in " + f.label;

            session.downloadFile(f.containerName, range, desc, f.tempPath,
                (packageLength, bytesReceived, lengthExpected) -> {
                    // 计数
                    bytesCounter.addAndGet(packageLength);
                    totalDownloaded.addAndGet(packageLength);
                    speed.feed(packageLength);

                    // 更新UI（限频 + EDT线程安全）
                    if (window != null) {
                        long now2 = System.currentTimeMillis();

                        if (now2 - uiTimer.get() > 300) {
                            uiTimer.set(now2);

                            // [修复] 在进度回调中重试获取焦点
                            // 如果焦点文件已完成并释放了焦点，此文件可以接管单文件进度条
                            acquireFocus(filename);

                            final String speedStr = speed.sampleSpeed2();
                            final long fileDownloaded = bytesCounter.get();
                            final boolean isFocus = isFocusFile(filename);
                            final String displayName = getDisplayName(filename);

                            SwingUtilities.invokeLater(() -> {
                                // 仅焦点文件更新单文件进度，避免多线程闪烁
                                if (isFocus) {
                                    window.setFileProgress(displayName, fileDownloaded, f.length);
                                }

                                // 总进度始终更新（重新读取 totalDownloaded 以避免 EDT 队列中旧值覆盖新值）
                                long currentDl = totalDownloaded.get();
                                String eta = calculateETA(currentDl, totalBytes);
                                window.setTotalProgress(currentDl, totalBytes, speedStr, eta);
                            });
                        }
                    }
                },
                (fallback) -> {
                    // 进度回退：从totalDownloaded中扣减本次下载已报告的字节
                    long toSubtract = bytesCounter.get();
                    totalDownloaded.addAndGet(-toSubtract);
                    // 重置bytesCounter，避免重试时累积旧值导致重复扣减
                    bytesCounter.set(0);

                    if (window != null) {
                        final String speedStr = speed.sampleSpeed2();

                        // [修复] OnFail回调中也尝试获取焦点，确保回退进度能正确显示
                        acquireFocus(filename);
                        final boolean isFocus = isFocusFile(filename);

                        SwingUtilities.invokeLater(() -> {
                            if (isFocus) {
                                String displayName = getDisplayName(filename);
                                window.setFileProgress(displayName, 0, f.length);
                            }
                            // 重新读取 totalDownloaded 以避免 EDT 队列中旧值覆盖新值
                            long currentDl = totalDownloaded.get();
                            String eta = calculateETA(currentDl, totalBytes);
                            window.setTotalProgress(currentDl, totalBytes, speedStr, eta);
                        });
                    }
                }
            );

            // 修复文件 mtime
            Files.setLastModifiedTime(f.tempPath, FileTime.from(f.modified, TimeUnit.SECONDS));

            // 下载完成，强制刷新总进度（避免限频导致最后几字节未显示）
            forceUpdateTotalProgress();

            // 显示校验状态（仅焦点文件）
            // 注意：校验阶段使用原始filename做焦点判断，而非verifyLabel，
            // 因为focusFilename存储的是原始文件名（不含"(校验中)"后缀）
            // [修复] 校验前重新获取焦点并刷新锁定
            acquireFocus(filename);
            if (window != null && isFocusFile(filename)) {
                final String displayName = getDisplayName(filename) + " (校验中)";
                SwingUtilities.invokeLater(() -> {
                    window.setFileProgress(displayName, 0, f.length);
                });
            }

            // 校验文件完整性（SHA-256 优先，回退到 CRC 校验）
            if (f.sha256 != null && !f.sha256.isEmpty()) {
                // 优先使用 SHA-256 校验
                String actualSHA256 = HashUtility.calculateSHA256WithProgress(f.tempPath, f.length,
                        (bytesRead, total) -> updateVerifyProgress(filename, bytesRead, total));

                if (!actualSHA256.equals(f.sha256)) {
                    throw new McpatchBusinessException(
                        String.format("临时文件 SHA-256 校验失败，预期 %s，实际 %s，文件路径 %s",
                            f.sha256, actualSHA256, f.tempPath.toFile().getAbsolutePath()));
                }
            } else {
                // 服务端未提供 SHA-256 时，回退到 CRC 校验
                String hash = HashUtility.calculateHashWithProgress(f.tempPath, f.length,
                        (bytesRead, total) -> updateVerifyProgress(filename, bytesRead, total));

                if (!hash.equals(f.hash)) {
                    throw new McpatchBusinessException(
                        String.format("临时文件 CRC 校验失败，预期 %s，实际 %s，文件路径 %s",
                            f.hash, hash, f.tempPath.toFile().getAbsolutePath()));
                }
            }
        }
        // 注意：焦点释放由 doParallelDownload() 的 finally 块统一处理，
        // 而非在此处释放，确保校验阶段焦点不会被提前释放
    }

    /**
     * 更新校验进度（带限频，同时更新单文件进度和总进度）
     * @param filename 原始文件名（不含后缀），用于焦点判断
     */
    private void updateVerifyProgress(String filename, long bytesRead, long totalFileBytes) {
        if (window == null) return;

        long now = System.currentTimeMillis();
        if (now - uiTimer.get() <= 300) return;
        uiTimer.set(now);

        // [修复] 校验进度回调中也刷新焦点锁定，防止校验期间焦点被抢占
        acquireFocus(filename);

        final long br = bytesRead;
        final long tb = totalFileBytes;
        final String speedStr = speed.sampleSpeed2();
        final boolean isFocus = isFocusFile(filename);
        // 校验阶段的显示名称附加"(校验中)"后缀
        final String displayName = getDisplayName(filename) + " (校验中)";

        SwingUtilities.invokeLater(() -> {
            // 仅焦点文件更新单文件进度
            if (isFocus) {
                window.setFileProgress(displayName, br, tb);
            }
            // 校验期间也要更新总进度，避免UI冻结
            // 重新读取 totalDownloaded 以避免 EDT 队列中旧值覆盖新值
            long currentDl = totalDownloaded.get();
            String eta = calculateETA(currentDl, totalBytes);
            window.setTotalProgress(currentDl, totalBytes, speedStr, eta);
        });
    }

    /**
     * 强制刷新总进度到当前实际值（避免限频导致UI滞后）
     */
    private void forceUpdateTotalProgress() {
        if (window == null) return;

        final String speedStr = speed.sampleSpeed2();

        SwingUtilities.invokeLater(() -> {
            // 重新读取 totalDownloaded 以避免 EDT 队列中旧值覆盖新值
            long currentDl = totalDownloaded.get();
            String eta = calculateETA(currentDl, totalBytes);
            window.setTotalProgress(currentDl, totalBytes, speedStr, eta);
        });
    }

    /**
     * 计算预估剩余时间
     */
    private String calculateETA(long downloaded, long total) {
        if (total <= 0 || downloaded <= 0) return "";
        long speedBytes = speed.sampleSpeed();
        if (speedBytes <= 0) return "";
        long remaining = total - downloaded;
        long etaSeconds = remaining / speedBytes;
        return BytesUtils.formatETA(etaSeconds);
    }

    /**
     * 下载结果记录
     */
    static class DownloadResult {
        final TempUpdateFile file;
        final Exception exception;

        DownloadResult(TempUpdateFile file, Exception exception) {
            this.file = file;
            this.exception = exception;
        }
    }

    /**
     * 根据总下载大小自动计算线程数。
     * 小文件用少量线程避免开销，大文件用更多线程充分利用带宽。
     *
     * @param totalBytes 需要下载的总字节数
     * @param maxThreadsOverride 用户配置的最大线程数，0表示自动
     * @return 计算出的线程数
     */
    public static int calculateThreadCount(long totalBytes, int maxThreadsOverride) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int threads;

        if (totalBytes < 1 * 1024 * 1024) {           // < 1MB
            threads = 1;
        } else if (totalBytes < 10 * 1024 * 1024) {    // 1-10MB
            threads = 2;
        } else if (totalBytes < 50 * 1024 * 1024) {    // 10-50MB
            threads = 3;
        } else if (totalBytes < 100 * 1024 * 1024) {   // 50-100MB
            threads = 4;
        } else if (totalBytes < 500 * 1024 * 1024) {   // 100-500MB
            threads = 6;
        } else {                                         // > 500MB
            threads = 8;
        }

        // 不超过CPU核心数，且至少1个线程
        threads = Math.max(1, Math.min(threads, cpuCores));

        // 如果用户配置了最大线程数，取较小值
        if (maxThreadsOverride > 0) {
            threads = Math.min(threads, maxThreadsOverride);
        }

        return threads;
    }
}
