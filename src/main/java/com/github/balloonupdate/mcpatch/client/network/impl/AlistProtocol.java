package com.github.balloonupdate.mcpatch.client.network.impl;

import com.github.balloonupdate.mcpatch.client.config.AppConfig;
import com.github.balloonupdate.mcpatch.client.data.Range;
import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.network.UpdatingServer;
import com.github.balloonupdate.mcpatch.client.utils.BytesUtils;
import com.github.balloonupdate.mcpatch.client.utils.ReduceReportingFrequency;
import com.github.balloonupdate.mcpatch.client.utils.RuntimeAssert;
import okhttp3.*;
import okio.BufferedSource;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 代表 Alist 协议的实现
 * Alist 是一个文件列表程序，此协议通过 Alist API 获取文件下载链接，
 * 然后使用 HTTP 下载文件，支持 Range 请求
 */
public class AlistProtocol implements UpdatingServer {
    /**
     * 本协议的编号，用来在出现网络错误时，区分是第几个url出现问题
     */
    int number;

    /**
     * 配置文件
     */
    AppConfig config;

    /**
     * Alist API 基本URL部分（如 http://example.com:5244/）
     */
    String baseUrl;

    /**
     * HTTP 客户端
     */
    OkHttpClient client;

    /**
     * 下载链接缓存 path -> raw_url
     */
    HashMap<String, String> cache = new HashMap<>();

    public AlistProtocol(int number, String url, AppConfig config) {
        this.number = number;

        // 确保 URL 末尾有 `/`
        if (!url.endsWith("/")) {
            url = url + "/";
        }

        // 去掉开头的 alist:// ，留下后面的部分
        url = url.substring("alist://".length());

        baseUrl = url;

        // 创建 HTTP 客户端对象
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        // 忽略证书
        if (config.ignoreSSLCertificate) {
            HttpProtocol.IgnoreSSLCert ignore = new HttpProtocol.IgnoreSSLCert();

            builder.sslSocketFactory(ignore.context.getSocketFactory(), ignore.trustManager);
        }

        client = builder
                .connectTimeout(config.httpTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(config.httpTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(config.httpTimeout, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public String requestText(String path, Range range, String desc) throws McpatchBusinessException {
        // 通过 Alist API 获取文件的真实下载链接
        String downloadUrl = fetchDownloadLink(path);

        boolean partialFile = range.start > 0 || range.end > 0;

        Request.Builder reqBuilder = new Request.Builder().url(downloadUrl);

        // 只请求部分文件
        if (partialFile) {
            RuntimeAssert.isTrue(range.end >= range.start);
            reqBuilder.addHeader("Range", String.format("bytes=%d-%d", range.start, range.end - 1));
        }

        // 添加自定义headers
        for (Map.Entry<String, String> e : config.httpHeaders.entrySet()) {
            reqBuilder.addHeader(e.getKey(), e.getValue());
        }

        Request req = reqBuilder.build();

        try (Response rsp = client.newCall(req).execute()) {
            int code = rsp.code();

            // 检查状态码
            if ((!partialFile && (code < 200 || code >= 300)) || (partialFile && code != 206)) {
                String body = rsp.peekBody(300).string();
                String content = String.format("Alist服务器(%d)返回了 %d: %s (%s)\n%s", number, code, path, desc, body);
                throw new McpatchBusinessException(content);
            }

            return rsp.body().string();
        } catch (ConnectException e) {
            throw new McpatchBusinessException("连接被拒绝，请检查网络。" + downloadUrl, e);
        } catch (SocketException e) {
            throw new McpatchBusinessException("连接中断，请检查网络。" + downloadUrl, e);
        } catch (SocketTimeoutException e) {
            throw new McpatchBusinessException("连接超时，请检查网络。" + downloadUrl, e);
        } catch (IOException e) {
            throw new McpatchBusinessException("无法解码响应体: " + path, e);
        }
    }

    @Override
    public void downloadFile(String path, Range range, String desc, Path writeTo, OnDownload callback, OnFail fallback) throws McpatchBusinessException {
        // 通过 Alist API 获取文件的真实下载链接
        String downloadUrl = fetchDownloadLink(path);

        boolean partialFile = range.start > 0 || range.end > 0;

        Request.Builder reqBuilder = new Request.Builder().url(downloadUrl);

        // 只请求部分文件
        if (partialFile) {
            RuntimeAssert.isTrue(range.end >= range.start);
            reqBuilder.addHeader("Range", String.format("bytes=%d-%d", range.start, range.end - 1));
        }

        // 添加自定义headers
        for (Map.Entry<String, String> e : config.httpHeaders.entrySet()) {
            reqBuilder.addHeader(e.getKey(), e.getValue());
        }

        Request req = reqBuilder.build();

        try (Response rsp = client.newCall(req).execute()) {
            int code = rsp.code();

            // 检查状态码
            if ((!partialFile && (code < 200 || code >= 300)) || (partialFile && code != 206)) {
                String body = rsp.peekBody(300).string();
                String content = String.format("Alist服务器(%d)返回了 %d: %s (%s)\n%s", number, code, path, desc, body);
                throw new McpatchBusinessException(content);
            }

            long contentLength = rsp.body().contentLength();

            // 本次文件传输一共累计传输了多少字节
            long downloaded = 0;

            // 标记下载循环是否成功完成（区别于close()异常导致的失败）
            boolean downloadCompleted = false;

            try (BufferedSource input = rsp.body().source()) {
                try (OutputStream output = Files.newOutputStream(writeTo)) {
                    byte[] buffer = new byte[BytesUtils.chooseBufferSize(contentLength > 0 ? contentLength : 8192)];

                    ReduceReportingFrequency report = new ReduceReportingFrequency();

                    int len;

                    while (true) {
                        len = input.read(buffer);

                        if (len == -1) {
                            break;
                        }

                        output.write(buffer, 0, len);
                        downloaded += len;

                        // 报告进度
                        long d = report.feed(len);

                        if (d > 0) {
                            callback.on(d, downloaded, contentLength);
                        }
                    }

                    // 完成下载：先报告剩余累积字节，再发送完成回调
                    long remaining = report.flush();
                    if (remaining > 0) {
                        callback.on(remaining, downloaded, contentLength);
                    }
                    callback.on(0, contentLength, contentLength);

                    // 标记下载循环成功完成
                    downloadCompleted = true;
                }
            } catch (IOException e) {
                // 只有下载循环未完成时才调用fallback回退进度
                if (!downloadCompleted && fallback != null)
                    fallback.on(downloaded);

                throw new McpatchBusinessException(e);
            }
        } catch (McpatchBusinessException e) {
            throw e;
        } catch (ConnectException e) {
            throw new McpatchBusinessException("连接被拒绝，请检查网络。" + downloadUrl, e);
        } catch (SocketException e) {
            throw new McpatchBusinessException("连接中断，请检查网络。" + downloadUrl, e);
        } catch (SocketTimeoutException e) {
            throw new McpatchBusinessException("连接超时，请检查网络。" + downloadUrl, e);
        } catch (IOException e) {
            throw new McpatchBusinessException(e);
        }
    }

    @Override
    public void close() throws Exception {
        cache.clear();
    }

    /**
     * 获取文件的原始下载链接
     */
    String fetchDownloadLink(String filename) throws McpatchBusinessException {
        if (cache.containsKey(filename))
            return cache.get(filename);

        // 构建 Alist API 请求路径
        // 将 baseUrl + filename 组合成完整 URL 后提取路径部分
        String fullPath = baseUrl + filename;

        int split = fullPath.indexOf("/", "https://".length());
        if (split == -1) {
            split = fullPath.indexOf("/", "http://".length());
        }

        String alistPath = (split != -1) ? fullPath.substring(split) : "/" + filename;

        Log.debug("Alist 请求路径: " + alistPath);

        // 构建 API URL
        String apiUrl = baseUrl + "api/fs/get";

        // 修复：JSON body 需要用 {} 包裹
        String bodyText = String.format("{\"path\": \"%s\",\"password\": \"\"}", alistPath);
        RequestBody body = RequestBody.create(bodyText, MediaType.get("application/json"));

        Request.Builder reqBuilder = new Request.Builder()
                .url(apiUrl)
                .post(body);

        // 添加自定义headers
        for (Map.Entry<String, String> e : config.httpHeaders.entrySet()) {
            reqBuilder.addHeader(e.getKey(), e.getValue());
        }

        Request req = reqBuilder.build();

        try (Response rsp = client.newCall(req).execute()) {
            if (!rsp.isSuccessful()) {
                String b = rsp.peekBody(300).string();
                String content = String.format("Alist服务器(%d)获取下载链接失败，返回了 %d: %s\n%s", number, rsp.code(), alistPath, b);
                throw new McpatchBusinessException(content);
            }

            String responseBody = rsp.body().string();
            JSONObject json = new JSONObject(responseBody);

            // 检查 Alist API 返回的 code 字段
            int apiCode = json.optInt("code", -1);
            String message = json.optString("message", "");

            if (apiCode != 200) {
                throw new McpatchBusinessException(String.format(
                        "Alist服务器(%d) API 返回错误: code=%d, message=%s, path=%s",
                        number, apiCode, message, alistPath));
            }

            String rawUrl = json.optQuery("/data/raw_url") != null ? json.query("/data/raw_url").toString() : null;

            if (rawUrl == null || rawUrl.isEmpty()) {
                throw new McpatchBusinessException(String.format(
                        "Alist服务器(%d) 未返回下载链接: path=%s", number, alistPath));
            }

            cache.put(filename, rawUrl);

            return rawUrl;
        } catch (McpatchBusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new McpatchBusinessException("Alist API 请求失败: " + apiUrl, e);
        }
    }
}
