package com.github.balloonupdate.mcpatch.client.utils;

import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径相关实用类
 */
public class PathUtility {
    /**
     * 获取文件名部分
     */
    public static String getFilename(String url) {
        String filename = url;

        if (url.contains("/"))
            filename = filename.substring(url.lastIndexOf("/") + 1);

        return filename;
    }

    /**
     * 验证来自服务端的文件路径是否安全，防止路径遍历攻击。
     * 检查规则：
     * 1. 路径 normalize 后不包含 ..
     * 2. 路径不以 / 或 \ 开头（绝对路径）
     * 3. 最终解析路径必须在基础目录内
     *
     * @param path 来自服务端的文件路径
     * @param baseDir 基础目录
     * @return 验证通过的路径字符串
     * @throws McpatchBusinessException 当路径不安全时抛出
     */
    public static String validateServerPath(String path, Path baseDir) throws McpatchBusinessException {
        if (path == null || path.isEmpty()) {
            throw new McpatchBusinessException("服务端返回的文件路径为空");
        }

        // 检查路径是否以 / 或 \ 开头（绝对路径）
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw new McpatchBusinessException("服务端返回的文件路径不允许为绝对路径: " + path);
        }

        // 路径标准化后不应包含 ..
        Path normalized = Paths.get(path).normalize();

        if (normalized.toString().contains("..")) {
            throw new McpatchBusinessException("服务端返回的文件路径包含路径遍历字符: " + path);
        }

        // 确保最终路径在基础目录内
        Path resolved = baseDir.resolve(normalized).normalize();

        if (!resolved.startsWith(baseDir.normalize())) {
            throw new McpatchBusinessException("服务端返回的文件路径超出允许的更新目录范围: " + path);
        }

        return path;
    }

    /**
     * 遍历删除文件夹或者普通文件，如果文件不存在不会抛异常
     */
    public static void delete(Path path) throws IOException {
        if (!Files.exists(path))
            return;

        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    // 递归
                    delete(entry);
                }
            }
        }

        // 删除文件或空目录
        Files.delete(path);
    }
}
