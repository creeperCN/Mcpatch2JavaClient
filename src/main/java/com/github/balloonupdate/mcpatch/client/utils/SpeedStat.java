package com.github.balloonupdate.mcpatch.client.utils;

import java.util.ArrayDeque;

/**
 * 代表一个速度统计，用来计算下载文件时的网速
 */
public class SpeedStat {
    /**
     * 采样周期
     */
    long period;

    /**
     * 记录的数据帧，新的采样数据会放到队列开头
     */
    ArrayDeque<Sample> frames = new ArrayDeque<>();

    /**
     * 对象池（使用 ArrayDeque 替代遗留的 Stack 类）
     */
    ArrayDeque<Sample> cache = new ArrayDeque<>();

    public SpeedStat(int samplingPeriod) {
        this.period = samplingPeriod;
    }

    // 获取格式化后的采样速度
    public synchronized String sampleSpeed2() {
        return BytesUtils.convertBytes(sampleSpeed());
    }

    // 获取采样速度
    public synchronized long sampleSpeed() {
        // 如果数据不够，直接返回0
        if (frames.size() <= 2) {
            return 0;
        }

        // 计算总bytes
        long totalBytes = 0;

        for (Sample sample : frames) {
            totalBytes += sample.bytes;
        }

        // 计算时间跨度（从最旧到最新）
        long delta = frames.getFirst().time - frames.getLast().time;

        // 防止除零：时间跨度为0时返回0
        if (delta <= 0) {
            return 0;
        }

        // 计算平均速度（先乘后除，避免整数截断精度损失）
        return totalBytes * 1000 / delta;
    }

    // 增加了新的字节数
    public synchronized void feed(long bytes) {
        long now = System.currentTimeMillis();

        if (!frames.isEmpty()) {
            Sample front = frames.getFirst();

            // 如果调用速度太快，就将数据合并进最近的采样数据里
            if (front.time == now) {
                front.bytes += bytes;
                return;
            }
        }

        // 正常添加一个采样数据
        frames.addFirst(GetSample(bytes, now));

        // 清理超过采样周期的旧数据
        while (frames.size() > 2) {
            Sample last = frames.getLast();
            long diff = now - last.time;

            if (diff > period) {
                ReleaseSample(frames.removeLast());
            } else {
                break;
            }
        }

        // 不能太多
        RuntimeAssert.isTrue(frames.size() < 100000);
    }

    Sample GetSample(long bytes, long time) {
        Sample sample = cache.isEmpty() ? new Sample() : cache.pollLast();

        sample.bytes = bytes;
        sample.time = time;

        return sample;
    }

    void ReleaseSample(Sample sample) {
        cache.addLast(sample);
    }

    // 代表一个采样数据
    static class Sample {
        /**
         * 记录的字节数量
         */
        public long bytes;

        /**
         * 采样生成的时间
         */
        public long time;
    }
}

