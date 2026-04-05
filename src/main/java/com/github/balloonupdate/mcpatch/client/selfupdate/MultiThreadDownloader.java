package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.logging.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多线程下载器
 * 支持分段并行下载，提升下载速度
 * 使用 OkHttp 支持配置文件中的 headers
 */
public class MultiThreadDownloader {
    /**
     * 默认线程数
     */
    private static final int DEFAULT_THREAD_COUNT = 6;

    /**
     * 最小分片大小（1MB以下不分片）
     */
    private static final long MIN_CHUNK_SIZE = 1024 * 1024;

    /**
     * 连接超时（毫秒）
     */
    private static final int CONNECT_TIMEOUT = 15000;

    /**
     * 读取超时（毫秒）
     */
    private static final int READ_TIMEOUT = 60000;

    /**
     * OkHttp 客户端（复用连接池）
     */
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
            .build();

    /**
     * 下载线程池（有界，最多8个线程，daemon线程不阻止JVM退出）
     */
    private static final ExecutorService executor = new ThreadPoolExecutor(
            DEFAULT_THREAD_COUNT, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(16),
            new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "mcpatch-downloader");
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        void onProgress(long downloaded, long total, int percent);
        void onComplete();
        void onError(Exception e);
    }

    /**
     * 多线程下载文件
     * @param fileUrl 文件URL
     * @param targetFile 目标文件
     * @param threadCount 线程数
     * @param callback 进度回调
     * @throws Exception 下载异常
     */
    public static void download(String fileUrl, Path targetFile, int threadCount, ProgressCallback callback) throws Exception {
        if (threadCount <= 0) {
            threadCount = DEFAULT_THREAD_COUNT;
        }

        // 1. 获取文件大小
        long fileSize = getFileSize(fileUrl);
        Log.info("文件大小: " + formatSize(fileSize));

        // 2. 判断是否需要多线程下载
        if (fileSize < MIN_CHUNK_SIZE || fileSize <= 0) {
            // 小文件，单线程下载
            Log.debug("文件较小，使用单线程下载");
            singleThreadDownload(fileUrl, targetFile, callback);
            return;
        }

        // 3. 分片下载
        Log.info("使用 " + threadCount + " 线程并行下载");
        multiThreadDownload(fileUrl, targetFile, fileSize, threadCount, callback);
    }

    /**
     * 获取文件大小
     */
    private static long getFileSize(String fileUrl) throws Exception {
        Request request = new Request.Builder()
                .url(fileUrl)
                .head()
                .header("User-Agent", "Mcpatch2JavaClient-Updater")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("无法获取文件大小: HTTP " + response.code());
            }

            String contentLength = response.header("Content-Length");
            if (contentLength == null) {
                return -1;
            }
            return Long.parseLong(contentLength);
        }
    }

    /**
     * 单线程下载（小文件）
     */
    private static void singleThreadDownload(String fileUrl, Path targetFile, ProgressCallback callback) throws Exception {
        Request request = new Request.Builder()
                .url(fileUrl)
                .header("User-Agent", "Mcpatch2JavaClient-Updater")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 206) {
                throw new RuntimeException("下载失败: HTTP " + response.code());
            }

            long fileSize = response.body().contentLength();
            long downloaded = 0;

            try (InputStream input = response.body().byteStream();
                 OutputStream output = Files.newOutputStream(targetFile)) {

                byte[] buffer = new byte[64 * 1024];
                int len;

                while ((len = input.read(buffer)) != -1) {
                    output.write(buffer, 0, len);
                    downloaded += len;

                    if (callback != null) {
                        int percent = fileSize > 0 ? (int)(downloaded * 100 / fileSize) : 0;
                        callback.onProgress(downloaded, fileSize, percent);
                    }
                }
            }

            if (callback != null) {
                callback.onComplete();
            }
        }
    }

    /**
     * 多线程分片下载
     */
    private static void multiThreadDownload(String fileUrl, Path targetFile, long fileSize, int threadCount, ProgressCallback callback) throws Exception {
        // 计算每个线程下载的大小
        long chunkSize = fileSize / threadCount;
        if (fileSize % threadCount != 0) {
            chunkSize++;
        }

        // 创建临时文件
        Path tempDir = targetFile.getParent();
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        // 下载状态
        AtomicLong totalDownloaded = new AtomicLong(0);
        List<Future<Path>> futures = new ArrayList<>();
        List<Exception> errors = new CopyOnWriteArrayList<>();

        // 分配下载任务
        for (int i = 0; i < threadCount; i++) {
            long start = i * chunkSize;
            long end = Math.min(start + chunkSize - 1, fileSize - 1);

            if (start >= fileSize) break;

            final int threadIndex = i;
            final long threadStart = start;
            final long threadEnd = end;

            Path chunkFile = targetFile.resolveSibling(targetFile.getFileName() + ".part" + threadIndex);

            futures.add(executor.submit(() -> {
                try {
                    return downloadChunk(fileUrl, threadStart, threadEnd, chunkFile, totalDownloaded, fileSize, callback);
                } catch (Exception e) {
                    errors.add(e);
                    throw e;
                }
            }));
        }

        // 等待所有下载完成
        try {
            for (Future<Path> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new Exception("多线程下载失败: " + e.getMessage(), e);
        }

        // 检查是否有错误
        if (!errors.isEmpty()) {
            throw errors.get(0);
        }

        // 合并文件
        Log.debug("合并下载分片...");
        mergeChunks(targetFile, fileSize);

        if (callback != null) {
            callback.onComplete();
        }
    }

    /**
     * 下载一个分片
     */
    private static Path downloadChunk(String fileUrl, long start, long end, Path chunkFile, AtomicLong totalDownloaded, long totalSize, ProgressCallback callback) throws Exception {
        Request request = new Request.Builder()
                .url(fileUrl)
                .header("User-Agent", "Mcpatch2JavaClient-Updater")
                .header("Range", "bytes=" + start + "-" + end)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 206 && !response.isSuccessful()) {
                throw new RuntimeException("分片下载失败: HTTP " + response.code());
            }

            long chunkSize = end - start + 1;

            try (InputStream input = response.body().byteStream();
                 OutputStream output = Files.newOutputStream(chunkFile)) {

                byte[] buffer = new byte[64 * 1024];
                int len;
                long lastCallbackTime = System.currentTimeMillis();

                while ((len = input.read(buffer)) != -1) {
                    output.write(buffer, 0, len);
                    long newTotal = totalDownloaded.addAndGet(len);

                    // 每500ms回调一次进度
                    long now = System.currentTimeMillis();
                    if (callback != null && now - lastCallbackTime > 500) {
                        int percent = totalSize > 0 ? (int)(newTotal * 100 / totalSize) : 0;
                        callback.onProgress(newTotal, totalSize, percent);
                        lastCallbackTime = now;
                    }
                }
            }

            return chunkFile;
        }
    }

    /**
     * 合并分片文件
     */
    private static void mergeChunks(Path targetFile, long expectedSize) throws Exception {
        Path tempDir = targetFile.getParent();
        String fileName = targetFile.getFileName().toString();

        // 找到所有分片文件
        List<Path> chunkFiles = new ArrayList<>();
        int index = 0;
        while (true) {
            Path chunkFile = tempDir.resolve(fileName + ".part" + index);
            if (!Files.exists(chunkFile)) break;
            chunkFiles.add(chunkFile);
            index++;
        }

        if (chunkFiles.isEmpty()) {
            throw new RuntimeException("没有找到分片文件");
        }

        // 合并到目标文件
        try (OutputStream output = Files.newOutputStream(targetFile)) {
            for (Path chunkFile : chunkFiles) {
                Files.copy(chunkFile, output);
                Files.delete(chunkFile);  // 删除分片
            }
        }

        // 验证文件大小
        long actualSize = Files.size(targetFile);
        if (actualSize != expectedSize) {
            Files.delete(targetFile);
            throw new RuntimeException("文件合并失败: 大小不匹配 (预期: " + expectedSize + ", 实际: " + actualSize + ")");
        }

        Log.debug("文件合并完成: " + formatSize(actualSize));
    }

    /**
     * 格式化文件大小
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /**
     * 批量下载多个小文件
     * @param urlPaths 下载 URL 和目标路径对
     * @param maxConcurrent 最大并发数
     * @param callback 进度回调
     * @throws Exception 下载异常
     */
    public static void batchDownload(List<UrlPathPair> urlPaths, int maxConcurrent, BatchProgressCallback callback) throws Exception {
        if (urlPaths == null || urlPaths.isEmpty()) {
            return;
        }
        
        if (maxConcurrent <= 0) {
            maxConcurrent = Math.min(urlPaths.size(), 8);
        }
        
        Log.info("批量下载 " + urlPaths.size() + " 个文件，最大并发: " + maxConcurrent);
        
        // 使用信号量控制并发
        Semaphore semaphore = new Semaphore(maxConcurrent);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger completed = new AtomicInteger(0);
        List<Exception> errors = new CopyOnWriteArrayList<>();
        
        for (int i = 0; i < urlPaths.size(); i++) {
            final int index = i;
            final UrlPathPair pair = urlPaths.get(i);
            
            futures.add(executor.submit(() -> {
                try {
                    semaphore.acquire();
                    try {
                        if (callback != null) {
                            callback.onFileStart(index, pair.url);
                        }
                        
                        // 下载单个文件（使用单线程下载）
                        singleThreadDownload(pair.url, pair.path, new ProgressCallback() {
                            @Override
                            public void onProgress(long downloaded, long total, int percent) {
                                if (callback != null) {
                                    callback.onFileProgress(index, downloaded, total, percent);
                                }
                            }
                            
                            @Override
                            public void onComplete() {
                                // 文件下载完成回调
                            }
                            
                            @Override
                            public void onError(Exception e) {
                                // 错误处理在外部
                            }
                        });
                        
                        if (callback != null) {
                            callback.onFileComplete(index, pair.url);
                        }
                        
                        int done = completed.incrementAndGet();
                        if (callback != null) {
                            callback.onBatchProgress(done, urlPaths.size());
                        }
                        
                    } finally {
                        semaphore.release();
                    }
                } catch (Exception e) {
                    errors.add(e);
                    if (callback != null) {
                        callback.onFileError(index, pair.url, e);
                    }
                    throw new RuntimeException("文件下载失败: " + pair.url, e);
                }
            }));
        }
        
        // 等待所有下载完成
        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new Exception("批量下载失败", e);
        }
        
        // 检查错误
        if (!errors.isEmpty()) {
            throw errors.get(0);
        }
        
        if (callback != null) {
            callback.onBatchComplete();
        }
    }
    
    /**
     * URL 和路径对
     */
    public static class UrlPathPair {
        public final String url;
        public final Path path;
        
        public UrlPathPair(String url, Path path) {
            this.url = url;
            this.path = path;
        }
    }
    
    /**
     * 批量下载进度回调
     */
    public interface BatchProgressCallback {
        void onFileStart(int index, String url);
        void onFileProgress(int index, long downloaded, long total, int percent);
        void onFileComplete(int index, String url);
        void onFileError(int index, String url, Exception e);
        void onBatchProgress(int completed, int total);
        void onBatchComplete();
    }

    /**
     * 关闭线程池
     */
    public static void shutdown() {
        executor.shutdown();
    }
}