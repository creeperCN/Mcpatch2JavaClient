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
     * 计算一个文件的 SHA-256 校验值（线程安全：每次调用创建新的 MessageDigest 实例）
     *
     * @param file 要计算校验值的文件路径
     * @return SHA-256 十六进制小写字符串
     */
    public static String calculateSHA256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }

        byte[] buf = new byte[128 * 1024];

        try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = stream.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 计算一个文件的校验值（线程安全：每次调用创建新的CRC实例）
     */
    public static String calculateHash(Path file) throws IOException {
        // 每次创建新实例，避免线程安全问题
        Crc64_XZ crc64 = new Crc64_XZ();
        Crc16_IBM_SDLC crc16 = new Crc16_IBM_SDLC();

        crc64.update(file);
        crc16.update(file);

        long a = crc64.getValue();
        long b = crc16.getValue();

        String crc64Str = String.format("%016x", a);
        String crc16Str = String.format("%04x", b);

        return crc64Str + "_" + crc16Str;
    }
}

class Crc64_XZ {
    private final long polynomial = 0x42f0e1eba9ea3693L;
    private final long initialValue = 0xffffffffffffffffL;
    private final long finalXorValue = 0xffffffffffffffffL;
    private final boolean reflectInput = true;
    private final boolean reflectOutput = true;

    private long crc = initialValue;

    byte[] buf = new byte[128 * 1024];

    public void reset() {
        crc = initialValue;
    }

    public void update(Path file) throws IOException {
        try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(file))) {
            int read;

            while ((read = stream.read(buf)) != -1)
                update(buf, 0, read);
        }
    }

    public void update(byte[] data, int offset, int len) {
        for (int x = offset; x < len; x++) {
            byte b = data[x];
            long value = b & 0xFF;
            if (reflectInput) {
                value = reflect(value, 8);
            }
            crc ^= (value << 56);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000000000000000L) != 0) {
                    crc = (crc << 1) ^ polynomial;
                } else {
                    crc <<= 1;
                }
            }
        }
    }

    public long getValue() {
        long result = crc;
        if (reflectOutput) {
            result = reflect(result, 64);
        }
        return result ^ finalXorValue;
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
    private final int polynomial = 0x1021;
    private final int initialValue = 0xffff;
    private final int finalXorValue = 0xffff;
    private final boolean reflectInput = true;
    private final boolean reflectOutput = true;

    private int crc = initialValue;

    byte[] buf = new byte[128 * 1024];

    public void reset() {
        crc = initialValue;
    }

    public void update(Path file) throws IOException {
        try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(file))) {
            int read;

            while ((read = stream.read(buf)) != -1)
                update(buf, 0, read);
        }
    }

    public void update(byte[] data, int offset, int len) {
        for (int x = offset; x < len; x++) {
            byte b = data[x];
            int value = b & 0xFF;
            if (reflectInput) {
                value = reflect(value, 8);
            }
            crc ^= (value << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ polynomial;
                } else {
                    crc <<= 1;
                }
            }
        }
    }

    public int getValue() {
        int result = crc;
        if (reflectOutput) {
            result = reflect(result, 16);
        }
        return (result ^ finalXorValue) & 0xFFFF;
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