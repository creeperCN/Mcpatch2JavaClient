package com.github.balloonupdate.mcpatch.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 焦点文件管理器（线程安全）
 * <p>
 * 在多文件并行下载（ParallelDownloader）和单文件分片下载（ChunkedDownloader）之间共享焦点状态，
 * 确保焦点文件机制的一致性。
 * <p>
 * 设计背景：多线程下载时，如果每个线程都更新单文件进度条，文件名会疯狂闪烁。
 * 焦点机制只让一个"焦点文件"更新单文件进度条，其他文件只更新总进度。
 * 焦点锁定2秒，期间不会被其他文件抢占。
 */
public class FocusManager {

    /** 焦点文件最小显示时长（毫秒） */
    private static final long FOCUS_LOCK_DURATION = 2000;

    /** 当前焦点文件名 */
    private volatile String focusFilename;

    /** 焦点文件锁定时间（毫秒时间戳） */
    private final AtomicLong focusLockTime;

    /** 当前活跃下载数（用于UI显示并行数） */
    private final AtomicInteger activeDownloadCount;

    /** 焦点机制的互斥锁，防止 acquireFocus() 的 TOCTOU 竞态条件 */
    private final Object focusLock = new Object();

    public FocusManager() {
        this.focusFilename = null;
        this.focusLockTime = new AtomicLong(0);
        this.activeDownloadCount = new AtomicInteger(0);
    }

    /**
     * 尝试获取焦点。如果当前没有焦点文件或锁定时间已过，则成为焦点文件。
     * <p>
     * 使用 synchronized 保证 check-then-act 的原子性，避免 TOCTOU 竞态条件。
     *
     * @param filename 要获取焦点的文件名
     */
    public void acquireFocus(String filename) {
        synchronized (focusLock) {
            long now = System.currentTimeMillis();
            // 没有焦点文件，或者焦点锁定时间已过，可以抢占
            if (focusFilename == null || now - focusLockTime.get() > FOCUS_LOCK_DURATION) {
                this.focusFilename = filename;
                focusLockTime.set(now);
            }
        }
    }

    /**
     * 判断指定文件是否是当前的焦点文件
     *
     * @param filename 文件名
     * @return 是否是焦点文件
     */
    public boolean isFocusFile(String filename) {
        synchronized (focusLock) {
            return filename.equals(focusFilename);
        }
    }

    /**
     * 获取文件在UI上的显示名称。
     * 多线程同时下载时，焦点文件名后标注并行数（如 "file.zip (+2)"）。
     *
     * @param filename 文件名
     * @return 显示名称
     */
    public String getDisplayName(String filename) {
        int active = activeDownloadCount.get();
        if (active > 1) {
            return filename + " (+" + (active - 1) + ")";
        }
        return filename;
    }

    /**
     * 增加活跃下载数
     */
    public int incrementActiveDownloadCount() {
        return activeDownloadCount.incrementAndGet();
    }

    /**
     * 减少活跃下载数
     */
    public int decrementActiveDownloadCount() {
        return activeDownloadCount.decrementAndGet();
    }

    /**
     * 获取当前活跃下载数
     */
    public int getActiveDownloadCount() {
        return activeDownloadCount.get();
    }
}
