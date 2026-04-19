package com.github.balloonupdate.mcpatch.client.utils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件 hash 计算类，所有计算文件哈希值时都会调用此函数，可以在此函数中替换任意哈希算法
 */
public class HashUtility {

    /**
     * 十六进制字符查表，用于快速 byte → hex 转换（替代 String.format）
     */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /**
     * 将字节数组快速转换为十六进制小写字符串
     */
    private static String bytesToHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            chars[i * 2] = HEX_CHARS[b >>> 4];
            chars[i * 2 + 1] = HEX_CHARS[b & 0x0F];
        }
        return new String(chars);
    }

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * @param bytesRead  已读取字节数
         * @param totalBytes 文件总字节数
         */
        void onProgress(long bytesRead, long totalBytes);
    }

    /**
     * 计算一个文件的 SHA-256 校验值（线程安全：每次调用创建新的 MessageDigest 实例）
     *
     * @param file 要计算校验值的文件路径
     * @return SHA-256 十六进制小写字符串
     */
    public static String calculateSHA256(Path file) throws IOException {
        return calculateSHA256WithProgress(file, -1, null);
    }

    /**
     * 计算一个文件的 SHA-256 校验值，带进度回调
     *
     * @param file       要计算校验值的文件路径
     * @param fileSize   文件总大小（用于进度计算），如果 <= 0 则自动获取
     * @param callback   进度回调（可为 null）
     * @return SHA-256 十六进制小写字符串
     */
    public static String calculateSHA256WithProgress(Path file, long fileSize, ProgressCallback callback) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }

        byte[] buf = new byte[128 * 1024];
        long totalSize = fileSize > 0 ? fileSize : Files.size(file);
        long totalRead = 0;
        long lastCallbackBytes = 0;
        // 每读 1MB 回调一次，避免过于频繁
        final long CALLBACK_INTERVAL = 1024 * 1024;

        try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = stream.read(buf)) != -1) {
                digest.update(buf, 0, read);
                totalRead += read;

                if (callback != null && totalRead - lastCallbackBytes >= CALLBACK_INTERVAL) {
                    callback.onProgress(totalRead, totalSize);
                    lastCallbackBytes = totalRead;
                }
            }
        }

        // 最终回调：确保报告100%
        if (callback != null && totalRead > 0) {
            callback.onProgress(totalRead, totalSize);
        }

        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }

    /**
     * 计算一个文件的校验值（线程安全：每次调用创建新的CRC实例）
     */
    public static String calculateHash(Path file) throws IOException {
        return calculateHashWithProgress(file, -1, null);
    }

    /**
     * 计算一个文件的校验值，带进度回调（线程安全：每次调用创建新的CRC实例）
     *
     * @param file     要计算校验值的文件路径
     * @param fileSize 文件总大小（用于进度计算），如果 <= 0 则自动获取
     * @param callback 进度回调（可为 null）
     */
    public static String calculateHashWithProgress(Path file, long fileSize, ProgressCallback callback) throws IOException {
        // 每次创建新实例，避免线程安全问题
        Crc64_XZ crc64 = new Crc64_XZ();
        Crc16_IBM_SDLC crc16 = new Crc16_IBM_SDLC();

        long totalSize = fileSize > 0 ? fileSize : Files.size(file);

        // 两个 CRC 共享同一个流读取，手动读取并分别 update
        byte[] buf = new byte[128 * 1024];
        long totalRead = 0;
        long lastCallbackBytes = 0;
        final long CALLBACK_INTERVAL = 1024 * 1024;

        try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = stream.read(buf)) != -1) {
                crc64.update(buf, 0, read);
                crc16.update(buf, 0, read);
                totalRead += read;

                if (callback != null && totalRead - lastCallbackBytes >= CALLBACK_INTERVAL) {
                    callback.onProgress(totalRead, totalSize);
                    lastCallbackBytes = totalRead;
                }
            }
        }

        // 最终回调
        if (callback != null && totalRead > 0) {
            callback.onProgress(totalRead, totalSize);
        }

        long a = crc64.getValue();
        long b = crc16.getValue();

        String crc64Str = String.format("%016x", a);
        String crc16Str = String.format("%04x", b);

        return crc64Str + "_" + crc16Str;
    }
}

class Crc64_XZ {
    private static final long POLYNOMIAL = 0x42f0e1eba9ea3693L;
    private static final long INITIAL_VALUE = 0xffffffffffffffffL;
    private static final long FINAL_XOR_VALUE = 0xffffffffffffffffL;
    private static final boolean REFLECT_INPUT = true;
    private static final boolean REFLECT_OUTPUT = true;

    private long crc = INITIAL_VALUE;

    public void reset() {
        crc = INITIAL_VALUE;
    }

    public void update(byte[] data, int offset, int len) {
        for (int x = offset; x < offset + len; x++) {
            byte b = data[x];
            long value = b & 0xFF;
            if (REFLECT_INPUT) {
                value = reflect(value, 8);
            }
            crc ^= (value << 56);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000000000000000L) != 0) {
                    crc = (crc << 1) ^ POLYNOMIAL;
                } else {
                    crc <<= 1;
                }
            }
        }
    }

    public long getValue() {
        long result = crc;
        if (REFLECT_OUTPUT) {
            result = reflect(result, 64);
        }
        return result ^ FINAL_XOR_VALUE;
    }

    private long reflect(long value, int bits) {
        long reflected = 0;
        for (int i = 0; i < bits; i++) {
            if ((value & (1L << i)) != 0) {
                reflected |= (1L << (bits - 1 - i));
            }
        }
        return reflected;
    }
}

class Crc16_IBM_SDLC {
    private static final int POLYNOMIAL = 0x1021;
    private static final int INITIAL_VALUE = 0xffff;
    private static final int FINAL_XOR_VALUE = 0xffff;
    private static final boolean REFLECT_INPUT = true;
    private static final boolean REFLECT_OUTPUT = true;

    private int crc = INITIAL_VALUE;

    public void reset() {
        crc = INITIAL_VALUE;
    }

    public void update(byte[] data, int offset, int len) {
        for (int x = offset; x < offset + len; x++) {
            byte b = data[x];
            int value = b & 0xFF;
            if (REFLECT_INPUT) {
                value = reflect(value, 8);
            }
            crc ^= (value << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ POLYNOMIAL;
                } else {
                    crc <<= 1;
                }
            }
        }
    }

    public int getValue() {
        int result = crc;
        if (REFLECT_OUTPUT) {
            result = reflect(result, 16);
        }
        return (result ^ FINAL_XOR_VALUE) & 0xFFFF;
    }

    private int reflect(int value, int bits) {
        int reflected = 0;
        for (int i = 0; i < bits; i++) {
            if ((value & (1 << i)) != 0) {
                reflected |= (1 << (bits - 1 - i));
            }
        }
        return reflected;
    }
}