package com.github.balloonupdate.mcpatch.client.selfupdate;

import org.json.JSONObject;

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
     * 发布日期
     */
    public String releaseDate;

    /**
     * 是否强制更新
     */
    public boolean forceUpdate;

    /**
     * 从 JSON 字符串解析版本信息
     */
    public static ClientVersionInfo fromJson(String json) {
        JSONObject obj = new JSONObject(json);

        ClientVersionInfo info = new ClientVersionInfo();
        info.latestVersion = obj.optString("latest_version", obj.optString("latestVersion", "0.0.0"));
        info.minVersion = obj.optString("min_version", obj.optString("minVersion", "0.0.0"));
        info.downloadUrl = obj.optString("download_url", obj.optString("downloadUrl", ""));
        info.checksum = obj.optString("checksum", "");
        info.changelog = obj.optString("changelog", "");
        info.releaseDate = obj.optString("release_date", obj.optString("releaseDate", ""));
        info.forceUpdate = obj.optBoolean("force_update", obj.optBoolean("forceUpdate", false));

        return info;
    }

    /**
     * 转换为 JSON 字符串
     */
    public String toJson() {
        JSONObject obj = new JSONObject();
        obj.put("latest_version", latestVersion);
        obj.put("min_version", minVersion);
        obj.put("download_url", downloadUrl);
        obj.put("checksum", checksum);
        obj.put("changelog", changelog);
        obj.put("release_date", releaseDate);
        obj.put("force_update", forceUpdate);
        return obj.toString();
    }

    @Override
    public String toString() {
        return "ClientVersionInfo{" +
                "latestVersion='" + latestVersion + '\'' +
                ", downloadUrl='" + downloadUrl + '\'' +
                ", forceUpdate=" + forceUpdate +
                '}';
    }
}