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

    /**
     * 当前正在下载的文件数（线程安全），用于UI显示策略
     */
    private final AtomicInteger activeDownloadCount = new AtomicInteger(0);

    /**
     * 当前焦点文件名（线程安全），多线程下载时只显示焦点文件，避免闪烁
     */
    private volatile String focusFilename = null;

    /**
     * 焦点文件锁定时间，避免频繁切换
     */
    private final AtomicLong focusLockTime = new AtomicLong(0);

    /**
     * 焦点文件最小显示时长（毫秒）
     */
    private static final long FOCUS_LOCK_DURATION = 2000;

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

    // ===== 焦点文件管理 =====

    /**
     * 尝试获取焦点。如果当前没有焦点文件或锁定时间已过，则成为焦点文件。
     * 焦点机制：多线程同时下载时，单文件进度条只跟踪一个"焦点文件"，
     * 避免文件名疯狂闪烁。焦点锁定2秒，期间不会被其他文件抢占。
     *
     * @param filename 要获取焦点的文件名
     */
    private void acquireFocus(String filename) {
        long now = System.currentTimeMillis();
        // 没有焦点文件，或者焦点锁定时间已过，可以抢占
        if (focusFilename == null || now - focusLockTime.get() > FOCUS_LOCK_DURATION) {
            focusFilename = filename;
            focusLockTime.set(now);
        }
    }

    /**
     * 判断指定文件是否是当前的焦点文件
     */
    private boolean isFocusFile(String filename) {
        return filename.equals(focusFilename);
    }

    /**
     * 获取文件在UI上的显示名称。
     * 多线程同时下载时，焦点文件正常显示文件名；
     * 非焦点文件不更新单文件进度条（只更新总进度）。
     *
     * @param filename 文件名
     * @return 显示名称
     */
    private String getDisplayName(String filename) {
        int active = activeDownloadCount.get();
        if (active > 1) {
            // 多线程下载时，焦点文件名后标注并行数
            return filename + " (+" + (active - 1) + ")";
        }
        return filename;
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
                servers, config, window, totalDownloaded, totalBytes, speed, uiTimer, executor,
                activeDownloadCount, focusFilename, focusLockTime);

        // 失败结果收集（线程安全）
        ConcurrentLinkedQueue<DownloadResult> failures = new ConcurrentLinkedQueue<>();

        // 提交下载任务
        List<Future<?>> futures = new ArrayList<>();
        for (TempUpdateFile f : files) {
            futures.add(executor.submit(() -> {
                activeDownloadCount.incrementAndGet();
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
                    activeDownloadCount.decrementAndGet();
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

                            final long dl = totalDownloaded.get();
                            final String speedStr = speed.sampleSpeed2();
                            final long fileDownloaded = bytesCounter.get();
                            final boolean isFocus = isFocusFile(filename);
                            final String displayName = getDisplayName(filename);

                            SwingUtilities.invokeLater(() -> {
                                // 仅焦点文件更新单文件进度，避免多线程闪烁
                                if (isFocus) {
                                    window.setFileProgress(displayName, fileDownloaded, f.length);
                                }

                                // 总进度始终更新
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
                        final boolean isFocus = isFocusFile(filename);

                        SwingUtilities.invokeLater(() -> {
                            if (isFocus) {
                                String displayName = getDisplayName(filename);
                                window.setFileProgress(displayName, 0, f.length);
                            }
                            String eta = calculateETA(dl, totalBytes);
                            window.setTotalProgress(dl, totalBytes, speedStr, eta);
                        });
                    }
                }
            );

            // 修复文件 mtime
            Files.setLastModifiedTime(f.tempPath, FileTime.from(f.modified, TimeUnit.SECONDS));

            // 下载完成，强制刷新总进度（避免限频导致最后几字节未显示）
            forceUpdateTotalProgress();

            // 显示校验状态（仅焦点文件）
            String verifyLabel = filename + " (校验中)";
            if (window != null && isFocusFile(filename)) {
                final String displayName = getDisplayName(verifyLabel);
                SwingUtilities.invokeLater(() -> {
                    window.setFileProgress(displayName, 0, f.length);
                });
            }

            // 校验文件完整性（SHA-256 优先，回退到 CRC 校验）
            if (f.sha256 != null && !f.sha256.isEmpty()) {
                // 优先使用 SHA-256 校验
                String actualSHA256 = HashUtility.calculateSHA256WithProgress(f.tempPath, f.length,
                        (bytesRead, total) -> updateVerifyProgress(verifyLabel, bytesRead, total));

                if (!actualSHA256.equals(f.sha256)) {
                    throw new McpatchBusinessException(
                        String.format("临时文件 SHA-256 校验失败，预期 %s，实际 %s，文件路径 %s",
                            f.sha256, actualSHA256, f.tempPath.toFile().getAbsolutePath()));
                }
            } else {
                // 服务端未提供 SHA-256 时，回退到 CRC 校验
                String hash = HashUtility.calculateHashWithProgress(f.tempPath, f.length,
                        (bytesRead, total) -> updateVerifyProgress(verifyLabel, bytesRead, total));

                if (!hash.equals(f.hash)) {
                    throw new McpatchBusinessException(
                        String.format("临时文件 CRC 校验失败，预期 %s，实际 %s，文件路径 %s",
                            f.hash, hash, f.tempPath.toFile().getAbsolutePath()));
                }
            }
        }
    }

    /**
     * 更新校验进度（带限频，同时更新单文件进度和总进度）
     */
    private void updateVerifyProgress(String filename, long bytesRead, long totalFileBytes) {
        if (window == null) return;

        long now = System.currentTimeMillis();
        if (now - uiTimer.get() <= 300) return;
        uiTimer.set(now);

        final long br = bytesRead;
        final long tb = totalFileBytes;
        final long dl = totalDownloaded.get();
        final String speedStr = speed.sampleSpeed2();
        final boolean isFocus = isFocusFile(filename);
        final String displayName = getDisplayName(filename);

        SwingUtilities.invokeLater(() -> {
            // 仅焦点文件更新单文件进度
            if (isFocus) {
                window.setFileProgress(displayName, br, tb);
            }
            // 校验期间也要更新总进度，避免UI冻结
            String eta = calculateETA(dl, totalBytes);
            window.setTotalProgress(dl, totalBytes, speedStr, eta);
        });
    }

    /**
     * 强制刷新总进度到当前实际值（避免限频导致UI滞后）
     */
    private void forceUpdateTotalProgress() {
        if (window == null) return;

        final long dl = totalDownloaded.get();
        final String speedStr = speed.sampleSpeed2();
        String eta = calculateETA(dl, totalBytes);

        SwingUtilities.invokeLater(() -> {
            window.setTotalProgress(dl, totalBytes, speedStr, eta);
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
