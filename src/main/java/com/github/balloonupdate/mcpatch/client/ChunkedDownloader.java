package com.github.balloonupdate.mcpatch.client;

import com.github.balloonupdate.mcpatch.client.config.AppConfig;
import com.github.balloonupdate.mcpatch.client.data.FileChunk;
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

import javax.swing.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单文件分片多线程下载器
 * 将大文件分成多个 chunk，使用多线程并行下载，最后合并
 */
public class ChunkedDownloader {

    // 默认分片大小：1MB
    public static final long DEFAULT_CHUNK_SIZE = 1024 * 1024;

    // 默认最大分片数
    public static final int DEFAULT_MAX_CHUNKS = 16;

    // 最小分片文件大小（小于此大小不分片）：10MB
    public static final long MIN_CHUNKED_FILE_SIZE = 10 * 1024 * 1024;

    private final Servers servers;
    private final AppConfig config;
    private final McPatchWindow window;
    private final AtomicLong totalDownloaded;
    private final long totalBytes;
    private final SpeedStat speed;
    private final AtomicLong uiTimer;
    private final long chunkSize;
    private final int maxChunks;

    // 焦点文件机制（与 ParallelDownloader 共享）
    private final AtomicInteger sharedActiveDownloadCount;
    private final AtomicLong sharedFocusLockTime;
    private volatile String focusFilename;

    /**
     * 创建一个分片下载器（旧版构造函数，不含焦点机制，向后兼容）
     *
     * @param executor 不再使用，保留参数仅为向后兼容
     */
    public ChunkedDownloader(Servers servers, AppConfig config, McPatchWindow window,
                             AtomicLong totalDownloaded, long totalBytes,
                             SpeedStat speed, AtomicLong uiTimer,
                             ExecutorService executor) {
        this.servers = servers;
        this.config = config;
        this.window = window;
        this.totalDownloaded = totalDownloaded;
        this.totalBytes = totalBytes;
        this.speed = speed;
        this.uiTimer = uiTimer;
        this.sharedActiveDownloadCount = new AtomicInteger(1);
        this.focusFilename = null;
        this.sharedFocusLockTime = new AtomicLong(0);

        // 从配置读取或使用默认值
        this.chunkSize = config.chunkSize > 0 ? config.chunkSize : DEFAULT_CHUNK_SIZE;
        this.maxChunks = config.maxChunks > 0 ? config.maxChunks : DEFAULT_MAX_CHUNKS;
    }

    /**
     * 创建一个分片下载器（新版构造函数，含焦点文件机制）
     *
     * @param executor                    不再使用，保留参数仅为向后兼容
     * @param sharedActiveDownloadCount   与 ParallelDownloader 共享的活跃下载数
     * @param focusFilename               焦点文件名引用（可为 null）
     * @param sharedFocusLockTime         焦点文件锁定时间
     */
    public ChunkedDownloader(Servers servers, AppConfig config, McPatchWindow window,
                             AtomicLong totalDownloaded, long totalBytes,
                             SpeedStat speed, AtomicLong uiTimer,
                             ExecutorService executor,
                             AtomicInteger sharedActiveDownloadCount,
                             String focusFilename,
                             AtomicLong sharedFocusLockTime) {
        this.servers = servers;
        this.config = config;
        this.window = window;
        this.totalDownloaded = totalDownloaded;
        this.totalBytes = totalBytes;
        this.speed = speed;
        this.uiTimer = uiTimer;
        this.sharedActiveDownloadCount = sharedActiveDownloadCount;
        this.focusFilename = focusFilename;
        this.sharedFocusLockTime = sharedFocusLockTime;

        // 从配置读取或使用默认值
        this.chunkSize = config.chunkSize > 0 ? config.chunkSize : DEFAULT_CHUNK_SIZE;
        this.maxChunks = config.maxChunks > 0 ? config.maxChunks : DEFAULT_MAX_CHUNKS;
    }

    // ===== 焦点文件管理 =====

    private static final long FOCUS_LOCK_DURATION = 2000;

    /**
     * 尝试获取焦点
     */
    private void acquireFocus(String filename) {
        this.focusFilename = filename;
        long now = System.currentTimeMillis();
        if (sharedFocusLockTime != null && now - sharedFocusLockTime.get() > FOCUS_LOCK_DURATION) {
            sharedFocusLockTime.set(now);
        }
    }

    /**
     * 判断此文件是否是焦点文件
     */
    private boolean isFocusFile(String filename) {
        // ChunkedDownloader 处理的文件通常是大文件，总是作为焦点显示
        return true;
    }

    /**
     * 获取显示名称（含并行下载提示）
     */
    private String getDisplayName(String filename) {
        int active = sharedActiveDownloadCount != null ? sharedActiveDownloadCount.get() : 1;
        if (active > 1) {
            return filename + " (+" + (active - 1) + ")";
        }
        return filename;
    }

    // ===== 下载逻辑 =====

    /**
     * 判断文件是否需要分片下载
     */
    public boolean shouldUseChunkedDownload(TempUpdateFile file) {
        // 检查配置是否启用分片下载
        if (!config.enableChunkedDownload) {
            return false;
        }

        // 文件小于最小分片大小，不分片
        if (file.length < MIN_CHUNKED_FILE_SIZE) {
            return false;
        }

        // 计算需要的分片数
        int chunkCount = calculateChunkCount(file.length);

        // 至少2个分片才有意义
        return chunkCount >= 2;
    }

    /**
     * 计算分片数量
     */
    private int calculateChunkCount(long fileLength) {
        int count = (int) Math.ceil((double) fileLength / chunkSize);
        return Math.min(count, maxChunks);
    }

    /**
     * 执行分片下载
     *
     * @param file 要下载的文件
     * @throws McpatchBusinessException 下载失败
     */
    public void download(TempUpdateFile file) throws McpatchBusinessException {
        String filename = PathUtility.getFilename(file.path);
        Log.info("开始分片下载: " + file.path + " (大小: " + BytesUtils.convertBytes(file.length) + ")");

        // 尝试获取焦点
        acquireFocus(filename);

        // 1. 计算分片
        List<FileChunk> chunks = createChunks(file);
        Log.debug("文件分成 " + chunks.size() + " 个分片");

        // 2. 并行下载所有分片
        downloadChunks(file, chunks, filename);

        // 下载完成，强制刷新总进度到100%（避免限频导致最后几字节未显示）
        forceUpdateTotalProgress();

        // 3. 合并分片
        mergeChunks(file, chunks, filename);

        // 4. 校验合并后的文件完整性
        verifyMergedFile(file, filename);

        // 5. 修复文件修改时间
        try {
            Files.setLastModifiedTime(file.tempPath, FileTime.from(file.modified, TimeUnit.SECONDS));
        } catch (IOException e) {
            Log.warn("修复文件修改时间失败: " + file.path);
        }

        // 6. 清理临时分片文件
        cleanupChunks(chunks);

        // 7. 显示文件完成
        if (window != null) {
            final String displayName = getDisplayName(filename);
            SwingUtilities.invokeLater(() -> {
                window.setFileProgress(displayName, file.length, file.length);
            });
        }

        Log.info("分片下载完成: " + file.path);
    }

    /**
     * 创建分片列表
     */
    private List<FileChunk> createChunks(TempUpdateFile file) {
        int chunkCount = calculateChunkCount(file.length);

        List<FileChunk> chunks = new ArrayList<>();
        Path chunkDir = file.tempPath.getParent().resolve(file.tempPath.getFileName() + ".chunks");

        try {
            Files.createDirectories(chunkDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建分片临时目录: " + chunkDir, e);
        }

        for (int i = 0; i < chunkCount; i++) {
            // 使用向上取整的方式分配分片大小，避免整数除法导致最后一个分片过大
            // 每个分片的范围在文件内部是均匀分布的
            long startInFile = file.length * i / chunkCount;
            long endInFile = file.length * (i + 1) / chunkCount;

            // 分片的字节范围必须加上 file.offset，因为 offset 是文件在更新包中的起始位置
            long start = file.offset + startInFile;
            long end = file.offset + endInFile;
            Path chunkPath = chunkDir.resolve(String.format("chunk.%d.tmp", i));

            chunks.add(new FileChunk(i, start, end, chunkPath));
        }

        return chunks;
    }

    /**
     * 并行下载所有分片
     *
     * 使用独立的线程池执行分片下载，避免与 ParallelDownloader 共享线程池导致死锁。
     * 死锁场景：ParallelDownloader 的所有线程都在 latch.await() 上等待分片完成，
     * 而分片任务排在同一个线程池的队列中无法获得线程执行，导致永久死锁。
     */
    private void downloadChunks(TempUpdateFile file, List<FileChunk> chunks, String filename)
            throws McpatchBusinessException {

        int maxRetries = 3;
        CountDownLatch latch = new CountDownLatch(chunks.size());
        ConcurrentLinkedQueue<ChunkDownloadException> failures = new ConcurrentLinkedQueue<>();

        // 创建独立的分片下载线程池，避免与外部线程池死锁
        // 线程数 = min(分片数, maxChunks, CPU核心数)，确保不会创建过多线程
        int chunkThreadCount = Math.min(chunks.size(),
                Math.min(maxChunks, Runtime.getRuntime().availableProcessors()));
        chunkThreadCount = Math.max(1, chunkThreadCount);

        ExecutorService chunkExecutor = Executors.newFixedThreadPool(chunkThreadCount, r -> {
            Thread t = new Thread(r, "mcpatch-chunk-downloader");
            t.setDaemon(true);
            return t;
        });

        // 初始化单文件进度显示
        if (window != null) {
            final String displayName = getDisplayName(filename);
            SwingUtilities.invokeLater(() -> {
                window.setFileProgress(displayName, 0, file.length);
            });
        }

        try {
            // 提交所有分片下载任务到独立线程池
            for (FileChunk chunk : chunks) {
                chunkExecutor.submit(() -> {
                    try {
                        downloadSingleChunk(file, chunk, chunks, filename, maxRetries);
                    } catch (Exception e) {
                        failures.add(new ChunkDownloadException(chunk, e));
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 等待所有分片完成
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpatchBusinessException("用户打断了更新", e);
            }
        } finally {
            chunkExecutor.shutdown();
        }

        // 检查是否有失败的
        if (!failures.isEmpty()) {
            ChunkDownloadException firstFailure = failures.poll();
            throw new McpatchBusinessException(
                    String.format("分片下载失败: %s, 分片 %d, 原因: %s",
                            file.path, firstFailure.chunk.index, firstFailure.exception.getMessage()),
                    firstFailure.exception);
        }
    }

    /**
     * 下载单个分片（支持重试）
     *
     * @param allChunks 所有分片列表，用于计算单文件总进度
     */
    private void downloadSingleChunk(TempUpdateFile file, FileChunk chunk, List<FileChunk> allChunks,
                                     String filename, int maxRetries)
            throws Exception {

        int attempts = 0;
        Exception lastException = null;

        while (attempts < maxRetries) {
            attempts++;
            chunk.incrementRetry();

            try (ServerSession session = servers.createSession()) {
                AtomicLong bytesCounter = new AtomicLong();

                session.downloadFile(file.containerName, chunk.toRange(),
                        file.path + " [chunk " + chunk.index + "]", chunk.tempPath,
                        (packageLength, bytesReceived, lengthExpected) -> {
                            // 更新分片进度
                            chunk.downloadedBytes.set(bytesReceived);

                            // 累计本次会话已下载字节（用于回退）
                            bytesCounter.addAndGet(packageLength);

                            // 更新总进度
                            totalDownloaded.addAndGet(packageLength);
                            speed.feed(packageLength);

                            // 聚合所有分片已下载字节，计算单文件进度
                            long fileDownloaded = 0;
                            for (FileChunk c : allChunks) {
                                fileDownloaded += c.downloadedBytes.get();
                            }

                            // 更新UI（单文件进度 + 总进度）
                            updateUI(filename, fileDownloaded, file.length);
                        },
                        (fallback) -> {
                            // 失败回退进度
                            long toFallback = bytesCounter.get();
                            totalDownloaded.addAndGet(-toFallback);
                            // 重置分片已下载字节为0（而非 addAndGet(-toFallback)，
                            // 因为 downloadedBytes 是通过 set(bytesReceived) 设置的，
                            // 而 bytesCounter 可能小于 bytesReceived（ReduceReportingFrequency 限频），
                            // 使用 addAndGet 会导致残留字节，使文件进度计算偏高）
                            chunk.downloadedBytes.set(0);
                            bytesCounter.set(0);

                            // 重新计算单文件进度
                            long fileDownloaded = 0;
                            for (FileChunk c : allChunks) {
                                fileDownloaded += c.downloadedBytes.get();
                            }
                            updateUI(filename, fileDownloaded, file.length);
                        }
                );

                // 验证分片大小
                long actualSize = Files.size(chunk.tempPath);
                if (actualSize != chunk.length) {
                    throw new McpatchBusinessException(
                            String.format("分片 %d 大小不匹配: 预期 %d, 实际 %d",
                                    chunk.index, chunk.length, actualSize));
                }

                chunk.markCompleted();
                return; // 成功

            } catch (Exception e) {
                lastException = e;
                Log.warn(String.format("分片 %d 第 %d 次下载失败: %s",
                        chunk.index, attempts, e.getMessage()));

                if (attempts < maxRetries) {
                    Thread.sleep(1000); // 短暂等待后重试
                }
            }
        }

        throw lastException;
    }

    /**
     * 合并所有分片到目标文件
     */
    private void mergeChunks(TempUpdateFile file, List<FileChunk> chunks, String filename)
            throws McpatchBusinessException {

        Log.debug("开始合并分片: " + file.path);

        String mergeLabel = filename + " (合并中)";

        // 更新单文件进度条为合并状态
        if (window != null) {
            final String displayName = getDisplayName(mergeLabel);
            SwingUtilities.invokeLater(() -> {
                window.setFileProgress(displayName, 0, file.length);
            });
        }

        // 确保目标目录存在
        try {
            Files.createDirectories(file.tempPath.getParent());
        } catch (IOException e) {
            throw new McpatchBusinessException("无法创建目标目录: " + file.tempPath.getParent(), e);
        }

        // 使用 NIO FileChannel 合并，性能更好
        long totalMerged = 0;

        try (FileChannel targetChannel = FileChannel.open(file.tempPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // 预分配文件大小
            targetChannel.truncate(file.length);

            for (FileChunk chunk : chunks) {
                try (FileChannel sourceChannel = FileChannel.open(chunk.tempPath, StandardOpenOption.READ)) {
                    // 使用 transferTo 高效复制
                    long transferred = 0;
                    while (transferred < chunk.length) {
                        long count = sourceChannel.transferTo(transferred, chunk.length - transferred, targetChannel);
                        if (count <= 0) {
                            break;
                        }
                        transferred += count;
                    }

                    if (transferred != chunk.length) {
                        throw new McpatchBusinessException(
                                String.format("分片 %d 合并不完整: 预期 %d, 实际 %d",
                                        chunk.index, chunk.length, transferred));
                    }

                    totalMerged += transferred;
                }

                // 更新合并进度
                final long merged = totalMerged;
                if (window != null) {
                    final String displayName = getDisplayName(mergeLabel);
                    SwingUtilities.invokeLater(() -> {
                        window.setFileProgress(displayName, merged, file.length);
                    });
                }
            }

            // 强制刷盘
            targetChannel.force(true);

        } catch (IOException e) {
            throw new McpatchBusinessException("合并分片失败: " + file.path, e);
        }

        // 校验合并后的文件大小
        try {
            long actualSize = Files.size(file.tempPath);
            if (actualSize != file.length) {
                throw new McpatchBusinessException(
                        String.format("合并后文件大小不匹配: 预期 %d, 实际 %d, 文件 %s",
                                file.length, actualSize, file.path));
            }
        } catch (IOException e) {
            throw new McpatchBusinessException("无法读取合并后文件大小: " + file.path, e);
        }

        Log.debug("合并完成: " + file.path);
    }

    /**
     * 校验合并后文件的完整性（SHA-256 优先，回退到 CRC 校验）
     * 校验过程中会实时更新单文件进度条
     */
    private void verifyMergedFile(TempUpdateFile file, String filename) throws McpatchBusinessException {
        Log.debug("开始校验合并文件: " + file.path);

        String verifyLabel = filename + " (校验中)";

        // 更新单文件进度条为校验状态
        if (window != null) {
            final String displayName = getDisplayName(verifyLabel);
            SwingUtilities.invokeLater(() -> {
                window.setFileProgress(displayName, 0, file.length);
            });
        }

        // 优先使用 SHA-256 校验
        if (file.sha256 != null && !file.sha256.isEmpty()) {
            try {
                String actualSHA256 = HashUtility.calculateSHA256WithProgress(file.tempPath, file.length,
                        (bytesRead, total) -> updateVerifyProgress(verifyLabel, bytesRead, total));
                if (!actualSHA256.equals(file.sha256)) {
                    throw new McpatchBusinessException(
                            String.format("分片下载文件 SHA-256 校验失败: 预期 %s, 实际 %s, 文件路径 %s",
                                    file.sha256, actualSHA256, file.tempPath.toFile().getAbsolutePath()));
                }
                Log.debug("SHA-256 校验通过: " + file.path);
            } catch (IOException e) {
                throw new McpatchBusinessException("计算文件 SHA-256 时出错: " + file.path, e);
            }
        } else {
            // 服务端未提供 SHA-256 时，回退到 CRC 校验
            try {
                String actualHash = HashUtility.calculateHashWithProgress(file.tempPath, file.length,
                        (bytesRead, total) -> updateVerifyProgress(verifyLabel, bytesRead, total));
                if (!actualHash.equals(file.hash)) {
                    throw new McpatchBusinessException(
                            String.format("分片下载文件 CRC 校验失败: 预期 %s, 实际 %s, 文件路径 %s",
                                    file.hash, actualHash, file.tempPath.toFile().getAbsolutePath()));
                }
                Log.debug("CRC 校验通过: " + file.path);
            } catch (IOException e) {
                throw new McpatchBusinessException("计算文件 CRC 校验值时出错: " + file.path, e);
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
        final String speedStr = speed.sampleSpeed2();
        final String displayName = getDisplayName(filename);

        SwingUtilities.invokeLater(() -> {
            window.setFileProgress(displayName, br, tb);
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
     * 清理临时分片文件
     */
    private void cleanupChunks(List<FileChunk> chunks) {
        for (FileChunk chunk : chunks) {
            try {
                if (Files.exists(chunk.tempPath)) {
                    Files.delete(chunk.tempPath);
                }
            } catch (IOException e) {
                Log.warn("无法删除分片临时文件: " + chunk.tempPath);
            }
        }

        // 尝试删除分片目录
        if (!chunks.isEmpty()) {
            try {
                Path chunkDir = chunks.get(0).tempPath.getParent();
                Files.deleteIfExists(chunkDir);
            } catch (IOException e) {
                // 忽略
            }
        }
    }

    /**
     * 更新UI显示（双进度条：单文件进度 + 总进度 + ETA）
     *
     * @param filename 文件名
     * @param fileDownloaded 单文件已下载字节
     * @param fileSize 单文件总字节
     */
    private void updateUI(String filename, long fileDownloaded, long fileSize) {
        if (window == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - uiTimer.get() <= 300) {
            return; // 限频
        }
        uiTimer.set(now);

        final String speedStr = speed.sampleSpeed2();
        final long fd = fileDownloaded;
        final String displayName = getDisplayName(filename);

        SwingUtilities.invokeLater(() -> {
            // 更新单文件进度
            window.setFileProgress(displayName, fd, fileSize);
            // 更新总进度 + 速度 + ETA
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
     * 分片下载异常
     */
    static class ChunkDownloadException {
        final FileChunk chunk;
        final Exception exception;

        ChunkDownloadException(FileChunk chunk, Exception exception) {
            this.chunk = chunk;
            this.exception = exception;
        }
    }
}
