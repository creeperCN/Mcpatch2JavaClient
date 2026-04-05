package com.github.balloonupdate.mcpatch.client.selfupdate;

import com.github.balloonupdate.mcpatch.client.config.AppConfig;
import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.network.UpdatingServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 从服主 McPatch2 管理端获取客户端版本信息
 */
public class ServerVersionClient {
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 从服主服务器获取客户端版本信息
     * @param serverUrl 服主服务器地址
     * @return 版本信息
     */
    public static ClientVersionInfo fetchVersionInfo(String serverUrl) throws Exception {
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new IllegalArgumentException("未配置客户端更新服务器地址");
        }

        // 确保 URL 以 / 结尾
        if (!serverUrl.endsWith("/")) {
            serverUrl += "/";
        }

        String versionUrl = serverUrl + "client-version.json";
        Log.debug("从服主服务器获取客户端版本: " + versionUrl);

        Request request = new Request.Builder()
                .url(versionUrl)
                .header("User-Agent", "Mcpatch2JavaClient-Updater")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new McpatchBusinessException("获取版本信息失败: HTTP " + response.code());
            }

            String body = response.body().string();
            return parseVersionInfo(body);
        }
    }

    /**
     * 解析版本信息 JSON
     */
    private static ClientVersionInfo parseVersionInfo(String json) throws Exception {
        JSONObject obj = new JSONObject(json);
        ClientVersionInfo info = new ClientVersionInfo();

        info.latestVersion = obj.optString("version", "");
        info.downloadUrl = obj.optString("download_url", "");
        info.checksum = obj.optString("checksum", null);
        info.changelog = obj.optString("changelog", "");
        info.fileSize = obj.optLong("file_size", 0);
        info.forceUpdate = obj.optBoolean("force_update", false);

        if (info.latestVersion.isEmpty()) {
            throw new McpatchBusinessException("版本信息中缺少 version 字段");
        }
        if (info.downloadUrl.isEmpty()) {
            throw new McpatchBusinessException("版本信息中缺少 download_url 字段");
        }

        return info;
    }
}