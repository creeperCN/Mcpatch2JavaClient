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
     * 已完成的文件数（线程安全）
     */
    private final AtomicInteger completedFileCount = new AtomicInteger(0);

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
    }

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

        // 创建分片下载器（复用线程池）
        ChunkedDownloader chunkedDownloader = new ChunkedDownloader(
                servers, config, window, totalDownloaded, totalBytes, speed, uiTimer, executor);

        // 失败结果收集（线程安全）
        ConcurrentLinkedQueue<DownloadResult> failures = new ConcurrentLinkedQueue<>();

        // 提交下载任务
        List<Future<?>> futures = new ArrayList<>();
        for (TempUpdateFile f : files) {
            futures.add(executor.submit(() -> {
                try {
                    // 判断是否需要分片下载
                    if (chunkedDownloader.shouldUseChunkedDownload(f)) {
                        chunkedDownloader.download(f);
                    } else {
                        downloadSingleFile(f);
                    }
                } catch (Exception e) {
                    failures.add(new DownloadResult(f, e));
                } finally {
                    // 文件完成（无论成功失败都算处理完毕）
                    int completed = completedFileCount.incrementAndGet();
                    updateStatusUI(completed);
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
        try (ServerSession session = servers.createSession()) {
            String filename = PathUtility.getFilename(f.path);

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

            // 展示即将开始下载 - 更新单文件进度
            if (window != null) {
                SwingUtilities.invokeLater(() -> {
                    window.setFileProgress(filename, 0, f.length);
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

                            final long dl = totalDownloaded.get();
                            final String speedStr = speed.sampleSpeed2();
                            final long fileDownloaded = bytesCounter.get();

                            SwingUtilities.invokeLater(() -> {
                                // 更新单文件进度
                                window.setFileProgress(filename, fileDownloaded, f.length);

                                // 更新总进度 + 速度 + ETA
                                String eta = calculateETA(dl, totalBytes);
                                window.setTotalProgress(dl, totalBytes, speedStr, eta);
                            });
                        }
                    }
                },
                (fallback) -> {
                    // 进度回退
                    totalDownloaded.addAndGet(-bytesCounter.get());

                    if (window != null) {
                        final long dl = totalDownloaded.get();
                        final String speedStr = speed.sampleSpeed2();

                        SwingUtilities.invokeLater(() -> {
                            window.setFileProgress(filename, 0, f.length);
                            String eta = calculateETA(dl, totalBytes);
                            window.setTotalProgress(dl, totalBytes, speedStr, eta);
                        });
                    }
                }
            );

            // 修复文件 mtime
            Files.setLastModifiedTime(f.tempPath, FileTime.from(f.modified, TimeUnit.SECONDS));

            // 显示校验状态
            if (window != null) {
                SwingUtilities.invokeLater(() -> {
                    window.setFileProgress(filename + " (校验中)", f.length, f.length);
                });
            }

            // 校验文件完整性（SHA-256 优先，回退到 CRC 校验）
            if (f.sha256 != null && !f.sha256.isEmpty()) {
                // 优先使用 SHA-256 校验
                String actualSHA256 = HashUtility.calculateSHA256(f.tempPath);

                if (!actualSHA256.equals(f.sha256)) {
                    throw new McpatchBusinessException(
                        String.format("临时文件 SHA-256 校验失败，预期 %s，实际 %s，文件路径 %s",
                            f.sha256, actualSHA256, f.tempPath.toFile().getAbsolutePath()));
                }
            } else {
                // 服务端未提供 SHA-256 时，回退到 CRC 校验
                String hash = HashUtility.calculateHash(f.tempPath);

                if (!hash.equals(f.hash)) {
                    throw new McpatchBusinessException(
                        String.format("临时文件 CRC 校验失败，预期 %s，实际 %s，文件路径 %s",
                            f.hash, hash, f.tempPath.toFile().getAbsolutePath()));
                }
            }
        }
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
