package com.github.balloonupdate.mcpatch.client.utils;

/**
 * 减少下载进度的报告频率
 */
public class ReduceReportingFrequency {
    /**
     * 上次报告的时间
     */
    private long lastReport = System.currentTimeMillis() - 200;

    /**
     * 自从上次报告以来，累计了多少字节没有报告
     */
    private long accumulated = 0L;

    public long feed(long bytes) {
        accumulated += bytes;

        long now = System.currentTimeMillis();

        if (now - lastReport > 200) {
            lastReport = now;
            long value = accumulated;
            accumulated = 0;
            return value;
        }

        return 0;
    }

    public void reset() {
        lastReport = System.currentTimeMillis() - 200;
    }

    /**
     * 返回尚未报告的累积字节数，并重置累积器。
     * 用于下载完成时，确保所有字节都被报告。
     *
     * @return 尚未报告的字节数
     */
    public long flush() {
        long value = accumulated;
        accumulated = 0;
        return value;
    }
}