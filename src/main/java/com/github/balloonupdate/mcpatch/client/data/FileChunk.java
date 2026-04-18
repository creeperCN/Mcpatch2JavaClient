package com.github.balloonupdate.mcpatch.client.data;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件分片信息，用于单文件多线程分片下载
 */
public class FileChunk {
    /**
     * 分片序号（0-based）
     */
    public final int index;

    /**
     * 分片起始字节（包含）
     */
    public final long start;

    /**
     * 分片结束字节（不包含）
     */
    public final long end;

    /**
     * 分片文件大小
     */
    public final long length;

    /**
     * 分片临时文件路径
     */
    public final Path tempPath;

    /**
     * 是否下载完成
     */
    private volatile boolean completed = false;

    /**
     * 已下载字节数（用于进度追踪）
     */
    public final AtomicLong downloadedBytes = new AtomicLong(0);

    /**
     * 重试次数
     */
    private volatile int retryCount = 0;

    public FileChunk(int index, long start, long end, Path tempPath) {
        this.index = index;
        this.start = start;
        this.end = end;
        this.length = end - start;
        this.tempPath = tempPath;
    }

    /**
     * 获取 Range 对象用于 HTTP Range 请求
     */
    public Range toRange() {
        return new Range(start, end);
    }

    /**
     * 标记分片下载完成
     */
    public void markCompleted() {
        this.completed = true;
    }

    /**
     * 是否下载完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 增加重试计数
     */
    public void incrementRetry() {
        retryCount++;
    }

    /**
     * 获取重试次数
     */
    public int getRetryCount() {
        return retryCount;
    }

    @Override
    public String toString() {
        return String.format("Chunk[%d](%d-%d, %d bytes)", index, start, end, length);
    }
}
