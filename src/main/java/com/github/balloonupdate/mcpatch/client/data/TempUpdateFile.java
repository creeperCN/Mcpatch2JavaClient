package com.github.balloonupdate.mcpatch.client.data;

import java.nio.file.Path;

/**
 * 代表更新过程中创建的文件更新信息。用来将多个文件变动列表合并成一个，并且尽可能剔除掉刚下载又马上要被删的文件，提高更新效率
 */
public class TempUpdateFile {
    /**
     * 所属更新包文件名
     */
    public String containerName;

    /**
     * 所属版本号
     */
    public String label;

    /**
     * 要更新的文件路径
     */
    public String path;

    /**
     * 文件校验值（CRC64+CRC16 组合校验）
     */
    public String hash;

    /**
     * 文件的 SHA-256 校验值（可选，服务端不支持时为 null）
     */
    public String sha256;

    /**
     * 文件长度
     */
    public long length;

    /**
     * 文件的修改时间，单位秒
     */
    public long modified;

    /**
     * 文件二进制数据在更新包中的偏移值
     */
    public long offset;

    /**
     * 临时文件的存放位置
     */
    public Path tempPath;

    public TempUpdateFile(String containerName, String label, FileChange.UpdateFile op, Path tempPath) {
        this.containerName = containerName;
        this.label = label;

        path = op.path;
        hash = op.hash;
        sha256 = op.sha256;
        length = op.len;
        modified = op.modified;
        offset = op.offset;

        this.tempPath = tempPath;
    }

    @Override
    public String toString() {
        return path + " (" + label + ")";
    }
}