package com.github.balloonupdate.mcpatch.client.network;

import com.github.balloonupdate.mcpatch.client.config.AppConfig;
import com.github.balloonupdate.mcpatch.client.data.Range;
import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.network.impl.McpatchProtocol;
import com.github.balloonupdate.mcpatch.client.utils.RuntimeAssert;

import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每个下载线程独立的服务器会话。
 * 拥有独立的重试状态和独立的 McpatchProtocol 实例，
 * 同时与其它 ServerSession 共享"推荐服务器索引"。
 */
public class ServerSession implements UpdatingServer {
    /**
     * 共享的服务器列表（HTTP/Webdav等线程安全实例）
     */
    private final List<UpdatingServer> sharedServers;

    /**
     * 每线程独立的 McpatchProtocol 实例缓存：urlIndex -> instance
     */
    private final ConcurrentHashMap<Integer, UpdatingServer> localMcpatchInstances;

    /**
     * 配置
     */
    private final AppConfig config;

    /**
     * 共享的推荐服务器索引，所有线程通过此变量感知服务器切换
     */
    private final AtomicInteger sharedCurrent;

    /**
     * 本会话当前正在使用的服务器索引
     */
    private int localCurrent;

    public ServerSession(List<UpdatingServer> sharedServers, AppConfig config, AtomicInteger sharedCurrent) {
        this.sharedServers = sharedServers;
        this.config = config;
        this.sharedCurrent = sharedCurrent;
        this.localCurrent = sharedCurrent.get();
        this.localMcpatchInstances = new ConcurrentHashMap<>();
    }

    /**
     * 获取指定索引对应的服务器实例。
     * 对于 McpatchProtocol，返回本线程独立创建的实例；
     * 对于 HTTP 系协议，返回共享实例。
     */
    private UpdatingServer getServer(int index) throws McpatchBusinessException {
        UpdatingServer shared = sharedServers.get(index);

        if (shared instanceof McpatchProtocol) {
            // 为本线程创建独立的 McpatchProtocol 实例
            return localMcpatchInstances.computeIfAbsent(index, i -> {
                try {
                    McpatchProtocol proto = (McpatchProtocol) shared;
                    return proto.createNewInstance();
                } catch (McpatchBusinessException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // HTTP/Webdav/Alist 线程安全，直接返回共享实例
        return shared;
    }

    @Override
    public String requestText(String path, Range range, String desc) throws McpatchBusinessException {
        return multipleAvailableServers(e -> e.requestText(path, range, desc));
    }

    @Override
    public void downloadFile(String path, Range range, String desc, Path writeTo, OnDownload callback, OnFail fallback) throws McpatchBusinessException {
        multipleAvailableServers(e -> {
            e.downloadFile(path, range, desc, writeTo, callback, fallback);
            return 114514;
        });
    }

    /**
     * 实现自动重试机制+自动切换服务器源（线程独立版本）
     */
    private <T> T multipleAvailableServers(Retry<T, UpdatingServer> task) throws McpatchBusinessException {
        McpatchBusinessException ex = null;
        int errorTimes = 0;

        // 从共享索引获取最新推荐服务器
        localCurrent = sharedCurrent.get();

        while (localCurrent < sharedServers.size()) {
            UpdatingServer server = getServer(localCurrent);
            int times = config.reties;

            while (--times >= 0) {
                try {
                    return task.runTask(server);
                } catch (McpatchBusinessException e) {
                    try {
                        server.close();
                    } catch (Exception exc) {
                        throw new McpatchBusinessException(exc);
                    }

                    // 移除失效的本地 McpatchProtocol 实例，下次使用时重新创建
                    if (server instanceof McpatchProtocol) {
                        localMcpatchInstances.remove(localCurrent);
                    }

                    // 用户打断了更新
                    if (e.getCause() instanceof ClosedByInterruptException) {
                        throw new McpatchBusinessException("用户打断了更新", (Exception) e.getCause());
                    }

                    // 记录一次错误
                    ex = e;
                    errorTimes += 1;

                    Log.warn("第" + errorTimes + "次出错：" + ex.toString() + " retry " + times + "...");

                    // 不是最后一次机会的话就等待一下下
                    if (times > 0) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }

            // 尝试推进共享索引（CAS），让其它线程也跳过失败的服务器
            sharedCurrent.compareAndSet(localCurrent, localCurrent + 1);
            localCurrent += 1;
        }

        RuntimeAssert.isTrue(ex != null);
        throw ex;
    }

    @Override
    public void close() throws Exception {
        for (UpdatingServer server : localMcpatchInstances.values()) {
            server.close();
        }
        localMcpatchInstances.clear();
    }

    @FunctionalInterface
    public interface Retry<T, S> {
        T runTask(S server) throws McpatchBusinessException;
    }
}
