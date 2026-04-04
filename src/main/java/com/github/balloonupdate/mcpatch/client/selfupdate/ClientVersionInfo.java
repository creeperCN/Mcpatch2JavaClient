package com.github.balloonupdate.mcpatch.client.selfupdate;

/**
 * 客户端版本信息
 */
public class ClientVersionInfo {
    /**
     * 最新版本号
     */
    public String latestVersion;

    /**
     * 最低支持版本
     */
    public String minVersion;

    /**
     * 下载地址
     */
    public String downloadUrl;

    /**
     * 文件校验值 (SHA-256)
     */
    public String checksum;

    /**
     * 更新日志
     */
    public String changelog;

    /**
     * 原始更新内容（来自 GitHub Release body）
     */
    public String body;

    /**
     * 发布日期
     */
    public String releaseDate;

    /**
     * 是否强制更新
     */
    public boolean forceUpdate;

    /**
     * 是否预发布版本
     */
    public boolean prerelease;

    /**
     * 文件大小（字节）
     */
    public long fileSize;

    @Override
    public String toString() {
        return "ClientVersionInfo{" +
                "latestVersion='" + latestVersion + '\'' +
                ", downloadUrl='" + downloadUrl + '\'' +
                ", prerelease=" + prerelease +
                '}';
    }
}