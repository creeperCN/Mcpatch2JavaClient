# Mcpatch2JavaClient 自动更新功能技术文档

## 目录

1. [功能概述](#1-功能概述)
2. [架构设计](#2-架构设计)
3. [核心组件](#3-核心组件)
4. [详细实现](#4-详细实现)
5. [工作流程](#5-工作流程)
6. [配置说明](#6-配置说明)
7. [镜像加速](#7-镜像加速)
8. [安全机制](#8-安全机制)
9. [使用指南](#9-使用指南)

---

## 1. 功能概述

### 1.1 背景

Mcpatch2JavaClient 作为 Minecraft 的 Java Agent 运行，用于自动更新游戏资源文件。当客户端自身需要更新时，传统方式需要用户手动下载新版本替换，体验较差。

### 1.2 解决方案

实现客户端自身自动更新功能：
- 从 GitHub Release 自动获取最新版本
- 后台静默下载新版本
- 下次启动时自动安装
- 支持国内镜像加速

### 1.3 核心特性

| 特性 | 说明 |
|------|------|
| 自动检测 | 启动时自动检查 GitHub Release |
| 镜像加速 | 自动选择最快的国内镜像站 |
| 延迟安装 | 避免 JAR 文件锁定问题 |
| 安全可靠 | SHA-256 校验 + 自动备份回滚 |
| 配置灵活 | 支持多渠道、开关控制 |

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Mcpatch2JavaClient                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      启动入口 (Main.java)                         │   │
│  │  premain() {                                                      │   │
│  │      SelfUpdateManager.performSelfUpdate();  // 自更新检查       │   │
│  │      // ... 游戏文件更新逻辑                                       │   │
│  │  }                                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                │                                        │
│                                ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                   SelfUpdateManager                              │   │
│  │                   (更新管理器 - 统一调度)                          │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                │                                        │
│          ┌─────────────────────┼─────────────────────┐                │
│          │                     │                     │                │
│          ▼                     ▼                     ▼                │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐           │
│  │SelfUpdate     │   │SelfUpdate     │   │SelfUpdate     │           │
│  │Checker        │   │Downloader     │   │Installer      │           │
│  │(版本检查)      │   │(下载更新)      │   │(安装更新)      │           │
│  └───────────────┘   └───────────────┘   └───────────────┘           │
│          │                     │                     │                │
│          │                     │                     │                │
│          ▼                     ▼                     ▼                │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐           │
│  │GitHubRelease  │   │GitHubMirror   │   │本地文件系统    │           │
│  │Client         │   │(镜像加速)      │   │(标记文件/备份)  │           │
│  │(GitHub API)   │   │               │   │               │           │
│  └───────────────┘   └───────────────┘   └───────────────┘           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 文件结构

```
src/main/java/com/github/balloonupdate/mcpatch/client/selfupdate/
├── SelfUpdateManager.java      # 更新管理器（统一调度）
├── SelfUpdateChecker.java      # 版本检查器（版本比较）
├── SelfUpdateDownloader.java   # 下载器（下载+校验）
├── SelfUpdateInstaller.java    # 安装器（替换JAR）
├── ClientVersionInfo.java      # 版本信息模型
├── GitHubReleaseClient.java    # GitHub Release API 客户端
└── GitHubMirror.java           # GitHub 镜像加速器
```

---

## 3. 核心组件

### 3.1 SelfUpdateManager（更新管理器）

**职责**：统一调度更新流程，协调各组件工作。

**核心方法**：
```java
public static boolean performSelfUpdate() {
    // 1. 安装待处理的更新
    if (SelfUpdateInstaller.installPendingUpdate()) {
        return true;
    }
    
    // 2. 配置镜像加速
    configureMirror();
    
    // 3. 从 GitHub Release 获取版本信息
    ClientVersionInfo versionInfo = fetchFromGitHub(githubRepo);
    
    // 4. 比较版本
    if (!SelfUpdateChecker.needUpdate(currentVersion, versionInfo.latestVersion)) {
        return false;
    }
    
    // 5. 下载新版本
    SelfUpdateDownloader.downloadNewVersion(versionInfo.downloadUrl, versionInfo.checksum);
    
    return false;
}
```

---

### 3.2 SelfUpdateChecker（版本检查器）

**职责**：检查是否有待安装的更新，比较版本号。

**核心方法**：

```java
public class SelfUpdateChecker {
    // 标记文件名
    public static final String UPDATE_MARKER_FILE = ".mcpatch-selfupdate-marker";
    
    // 检查是否有待安装的更新
    public static boolean hasPendingUpdate() {
        Path markerFile = getUpdateMarkerFile();
        return Files.exists(markerFile);
    }
    
    // 获取标记文件路径
    public static Path getUpdateMarkerFile() {
        String tempDir = System.getProperty("java.io.tmpdir");
        return Paths.get(tempDir, UPDATE_MARKER_FILE);
    }
    
    // 版本号比较
    public static boolean needUpdate(String currentVersion, String latestVersion) {
        // 逐段比较版本号
        // 0.0.11 < 0.0.12 → true
        // 0.0.12 < 0.0.12 → false
    }
}
```

---

### 3.3 SelfUpdateDownloader（下载器）

**职责**：下载新版本 JAR 文件，校验完整性，创建标记文件。

**核心方法**：

```java
public class SelfUpdateDownloader {
    // 临时文件名
    public static final String UPDATE_TEMP_FILE = "mcpatch-update-new.jar";
    
    // 下载新版本
    public static Path downloadNewVersion(String downloadUrl, String expectedChecksum) {
        Path tempFile = getUpdateTempFile();
        
        // 1. 下载文件
        downloadFile(downloadUrl, tempFile);
        
        // 2. 计算并校验 SHA-256
        String actualChecksum = calculateSHA256(tempFile);
        if (!expectedChecksum.equals(actualChecksum)) {
            throw new RuntimeException("校验失败");
        }
        
        // 3. 创建标记文件
        createUpdateMarker(tempFile);
        
        return tempFile;
    }
    
    // 获取临时文件路径
    public static Path getUpdateTempFile() {
        String tempDir = System.getProperty("java.io.tmpdir");
        return Paths.get(tempDir, UPDATE_TEMP_FILE);
    }
}
```

---

### 3.4 SelfUpdateInstaller（安装器）

**职责**：安装待处理的更新，备份和回滚。

**核心方法**：

```java
public class SelfUpdateInstaller {
    // 备份文件后缀
    public static final String BACKUP_SUFFIX = ".backup";
    
    // 安装待处理的更新
    public static boolean installPendingUpdate() {
        // 1. 检查标记文件
        if (!SelfUpdateChecker.hasPendingUpdate()) {
            return false;
        }
        
        // 2. 读取新版本路径
        Path markerFile = SelfUpdateChecker.getUpdateMarkerFile();
        String newJarPath = Files.readString(markerFile);
        Path newJar = Paths.get(newJarPath);
        
        // 3. 获取当前 JAR 路径
        Path currentJar = Env.getJarPath();
        
        // 4. 备份当前版本
        backupCurrentVersion(currentJar);
        
        // 5. 替换 JAR 文件
        Files.move(newJar, currentJar, REPLACE_EXISTING);
        
        // 6. 删除标记文件
        Files.delete(markerFile);
        
        return true;
    }
    
    // 备份当前版本
    private static void backupCurrentVersion(Path currentJar) {
        Path backup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);
        Files.copy(currentJar, backup, REPLACE_EXISTING);
    }
    
    // 回滚到备份版本
    public static boolean rollbackUpdate() {
        Path backup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);
        Files.move(backup, currentJar, REPLACE_EXISTING);
    }
}
```

---

### 3.5 GitHubReleaseClient（GitHub API 客户端）

**职责**：与 GitHub Release API 交互，获取版本信息。

**核心方法**：

```java
public class GitHubReleaseClient {
    private static final String GITHUB_API_BASE = "https://api.github.com/repos/";
    
    // 获取最新 Release
    public static ClientVersionInfo fetchLatestRelease(String owner, String repo) {
        String apiUrl = GITHUB_API_BASE + owner + "/" + repo + "/releases/latest";
        
        // 使用镜像加速
        String actualUrl = useMirror ? GitHubMirror.convertApiUrl(apiUrl) : apiUrl;
        
        String response = httpGet(actualUrl);
        JSONObject json = new JSONObject(response);
        
        return parseGitHubRelease(json);
    }
    
    // 解析 GitHub Release JSON
    private static ClientVersionInfo parseGitHubRelease(JSONObject json) {
        ClientVersionInfo info = new ClientVersionInfo();
        
        // 版本号（去除 v 前缀）
        String tagName = json.getString("tag_name");
        info.latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
        
        // 更新日志
        info.changelog = json.optString("body", "");
        
        // 是否预发布
        info.prerelease = json.optBoolean("prerelease", false);
        
        // 查找 JAR 文件
        JSONArray assets = json.optJSONArray("assets");
        for (JSONObject asset : assets) {
            if (asset.getString("name").endsWith(".jar")) {
                String originalUrl = asset.getString("browser_download_url");
                info.downloadUrl = GitHubMirror.convertToMirrorUrl(originalUrl);
                break;
            }
        }
        
        return info;
    }
}
```

---

### 3.6 GitHubMirror（镜像加速器）

**职责**：选择最快的镜像站，加速访问 GitHub。

**镜像站列表**：
```java
private static final String[] MIRROR_URLS = {
    "https://gh-proxy.org/",
    "https://hk.gh-proxy.org/",
    "https://cdn.gh-proxy.org/",
    "https://edgeone.gh-proxy.org/"
};
```

**核心方法**：

```java
public class GitHubMirror {
    // 缓存最优镜像
    private static String cachedBestMirror = null;
    private static long cacheTime = 0;
    private static final long CACHE_EXPIRE_MS = 10 * 60 * 1000; // 10分钟
    
    // 获取最优镜像
    public static String getBestMirror() {
        // 检查缓存
        if (cachedBestMirror != null && !isCacheExpired()) {
            return cachedBestMirror;
        }
        
        // Ping 测试所有镜像
        String bestMirror = null;
        long bestLatency = Long.MAX_VALUE;
        
        for (String mirror : MIRROR_URLS) {
            String host = extractHost(mirror);
            long latency = ping(host);
            
            if (latency > 0 && latency < bestLatency) {
                bestLatency = latency;
                bestMirror = mirror;
            }
        }
        
        // 缓存结果
        cachedBestMirror = bestMirror;
        cacheTime = System.currentTimeMillis();
        
        return bestMirror;
    }
    
    // Ping 测试
    private static long ping(String hostname) {
        InetAddress address = InetAddress.getByName(hostname);
        long startTime = System.currentTimeMillis();
        boolean reachable = address.isReachable(3000);
        long endTime = System.currentTimeMillis();
        
        return reachable ? (endTime - startTime) : -1;
    }
    
    // 转换 URL
    public static String convertToMirrorUrl(String originalUrl) {
        if (!isGitHubUrl(originalUrl)) {
            return originalUrl;
        }
        
        String bestMirror = getBestMirror();
        if (bestMirror == null) {
            return originalUrl;
        }
        
        return bestMirror + originalUrl;
    }
}
```

---

## 4. 详细实现

### 4.1 JAR 文件锁定问题

**问题描述**：
Java Agent 运行时，JAR 文件被 JVM 锁定，无法直接替换。

**解决方案**：
采用"延迟安装"策略，在下次启动时替换。

```
┌─────────────────────────────────────────────────────────────────┐
│                     JAR 锁定问题解决                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  传统方式（失败）:                                               │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 运行中 → 下载新版本 → 尝试替换 JAR → ❌ 文件被锁定           ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
│  延迟安装方式（成功）:                                           │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 第N次运行:                                                   ││
│  │   运行中 → 下载新版本 → 保存到临时目录 → 创建标记文件        ││
│  │                                                              ││
│  │ 第N次退出:                                                   ││
│  │   JVM 退出 → JAR 解锁                                        ││
│  │                                                              ││
│  │ 第N+1次启动:                                                 ││
│  │   JVM 启动 → 检查标记文件 → 替换 JAR → 删除标记文件          ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 标记文件机制

**标记文件**：`.mcpatch-selfupdate-marker`

**位置**：系统临时目录
- Windows: `C:\Users\xxx\AppData\Local\Temp\.mcpatch-selfupdate-marker`
- Linux: `/tmp/.mcpatch-selfupdate-marker`
- macOS: `/tmp/.mcpatch-selfupdate-marker`

**内容**：新版本 JAR 文件的绝对路径

```
C:\Users\xxx\AppData\Local\Temp\mcpatch-update-new.jar
```

### 4.3 文件流转过程

```
第 N 次启动：
┌─────────────────────────────────────────────────────────────────┐
│  系统临时目录                                                    │
│  ├── (空)                                                       │
│                                                                 │
│  游戏目录                                                        │
│  └── update.jar (当前版本 0.0.11)                                 │
└─────────────────────────────────────────────────────────────────┘
          │
          ▼ 下载新版本
┌─────────────────────────────────────────────────────────────────┐
│  系统临时目录                                                    │
│  ├── mcpatch-update-new.jar (新版本 0.0.12)                      │
│  └── .mcpatch-selfupdate-marker (内容: 新版本路径)                │
│                                                                 │
│  游戏目录                                                        │
│  └── update.jar (当前版本 0.0.11)                                 │
└─────────────────────────────────────────────────────────────────┘

第 N+1 次启动：
┌─────────────────────────────────────────────────────────────────┐
│  检查标记文件 → 存在！                                           │
│          │                                                       │
│          ▼                                                       │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 1. 备份: update.jar → update.jar.backup                      ││
│  │ 2. 替换: mcpatch-update-new.jar → update.jar                 ││
│  │ 3. 清理: 删除标记文件                                        ││
│  └─────────────────────────────────────────────────────────────┘│
│          │                                                       │
│          ▼                                                       │
┌─────────────────────────────────────────────────────────────────┐
│  系统临时目录                                                    │
│  └── (空)                                                       │
│                                                                 │
│  游戏目录                                                        │
│  ├── update.jar (新版本 0.0.12) ✅                                │
│  └── update.jar.backup (备份 0.0.11)                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. 工作流程

### 5.1 完整流程图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         客户端启动                                        │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Step 1: 检查标记文件                                                     │
│  路径: %TEMP%\.mcpatch-selfupdate-marker                                 │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                 存在                              不存在
                    │                               │
                    ▼                               ▼
        ┌───────────────────────┐      ┌───────────────────────┐
        │ Step 2a: 安装更新      │      │ Step 2b: 检查新版本    │
        │ - 读取新版本路径       │      │ - 配置镜像加速         │
        │ - 备份当前版本         │      │ - 请求 GitHub API     │
        │ - 替换 JAR 文件        │      │ - 比较版本号          │
        │ - 删除标记文件         │      └───────────────────────┘
        └───────────────────────┘                  │
                    │                              │
                    │                  ┌───────────┴───────────┐
                    │                  │                       │
                    │               需要更新                  无需更新
                    │                  │                       │
                    │                  ▼                       ▼
                    │      ┌───────────────────────┐  ┌─────────────────┐
                    │      │ Step 3: 下载更新       │  │ 继续正常流程    │
                    │      │ - 下载 JAR 文件        │  │ (游戏文件更新) │
                    │      │ - SHA-256 校验        │  └─────────────────┘
                    │      │ - 创建标记文件         │
                    │      └───────────────────────┘
                    │                  │
                    └──────────────────┤
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      继续正常启动流程                                     │
│                      (检查游戏文件更新)                                   │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 时序图

```
用户          Minecraft启动器      Java Agent         GitHub API        镜像站
 │                  │                 │                   │               │
 │   启动游戏       │                 │                   │               │
 │─────────────────>│                 │                   │               │
 │                  │   加载 Agent    │                   │               │
 │                  │────────────────>│                   │               │
 │                  │                 │                   │               │
 │                  │                 │  检查标记文件      │               │
 │                  │                 │───┐               │               │
 │                  │                 │<──┘ 不存在         │               │
 │                  │                 │                   │               │
 │                  │                 │  GET /releases/latest             │
 │                  │                 │──────────────────>│               │
 │                  │                 │                   │               │
 │                  │                 │    (镜像加速)     │               │
 │                  │                 │──────────────────────────────────>│
 │                  │                 │                   │               │
 │                  │                 │<──────────────────────────────────│
 │                  │                 │   版本信息 JSON   │               │
 │                  │                 │                   │               │
 │                  │                 │  比较版本号        │               │
 │                  │                 │───┐               │               │
 │                  │                 │<──┘ 需要更新       │               │
 │                  │                 │                   │               │
 │                  │                 │  下载新版本 JAR    │               │
 │                  │                 │──────────────────────────────────>│
 │                  │                 │<──────────────────────────────────│
 │                  │                 │   JAR 文件        │               │
 │                  │                 │                   │               │
 │                  │                 │  创建标记文件      │               │
 │                  │                 │───┐               │               │
 │                  │                 │<──┘               │               │
 │                  │                 │                   │               │
 │                  │                 │  继续游戏文件更新  │               │
 │                  │                 │───┐               │               │
 │                  │                 │<──┘               │               │
 │                  │                 │                   │               │
 │<─────────────────────────────────────  游戏启动        │               │
 │                  │                 │                   │               │
```

---

## 6. 配置说明

### 6.1 完整配置

```yaml
# mcpatch.yml

# 客户端自身更新配置
client-update:
  # 是否启用客户端自动更新
  # true: 启用，客户端启动时会检查自身是否有更新
  # false: 禁用，需要手动更新客户端
  enabled: false

  # GitHub 仓库配置
  # 格式: owner/repo
  # 从 GitHub Release 获取最新版本
  github-repo: "BalloonUpdate/Mcpatch2JavaClient"

  # GitHub 镜像加速（国内环境推荐开启）
  # 使用镜像站加速访问 GitHub，解决国内网络问题
  # auto: 自动检测，Ping 测试选择最快的镜像（推荐）
  # true: 强制启用镜像
  # false: 禁用镜像，直连 GitHub
  mirror: auto

  # 更新渠道
  # stable: 稳定版（正式发布）
  # beta: 测试版（包含预发布版本）
  # alpha: 开发版（包含所有版本）
  channel: stable

  # 是否自动安装更新
  # true: 下载完成后自动安装（下次启动时生效）
  # false: 仅下载，需手动安装
  auto-install: true

  # 是否备份当前版本
  # true: 更新前备份当前版本，失败时可回滚
  # false: 不备份
  backup-enabled: true

  # 更新失败时是否自动回滚
  # true: 失败时自动恢复到备份版本
  # false: 失败后需要手动处理
  rollback-on-failure: true
```

### 6.2 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | false | 是否启用自动更新 |
| `github-repo` | string | "" | GitHub 仓库地址 |
| `mirror` | string | "auto" | 镜像加速模式 |
| `channel` | string | "stable" | 更新渠道 |
| `auto-install` | boolean | true | 是否自动安装 |
| `backup-enabled` | boolean | true | 是否备份 |
| `rollback-on-failure` | boolean | true | 是否自动回滚 |

---

## 7. 镜像加速

### 7.1 支持的镜像站

| 镜像站 | URL | 说明 |
|--------|-----|------|
| gh-proxy.org | `https://gh-proxy.org/` | 主站 |
| hk.gh-proxy.org | `https://hk.gh-proxy.org/` | 香港节点 |
| cdn.gh-proxy.org | `https://cdn.gh-proxy.org/` | CDN 加速 |
| edgeone.gh-proxy.org | `https://edgeone.gh-proxy.org/` | EdgeOne 加速 |

### 7.2 使用方式

镜像站使用方式：在镜像站 URL 后拼接原始 GitHub URL。

```
原始 URL:
https://github.com/BalloonUpdate/Mcpatch2JavaClient/releases/download/v0.0.12/Mcpatch-0.0.12.jar

镜像 URL:
https://hk.gh-proxy.org/https://github.com/BalloonUpdate/Mcpatch2JavaClient/releases/download/v0.0.12/Mcpatch-0.0.12.jar
```

### 7.3 自动选择流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    镜像选择流程                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. 检查缓存                                                     │
│     ├── 缓存有效（< 10分钟）→ 直接使用缓存的最优镜像              │
│     └── 缓存过期或不存在 → 继续测试                              │
│                                                                 │
│  2. Ping 测试                                                    │
│     ┌─────────────────────────────────────────────────────────┐ │
│     │ 镜像站                  │ 延迟    │ 状态                 │ │
│     ├─────────────────────────────────────────────────────────┤ │
│     │ gh-proxy.org            │ 150ms   │ ✓                    │ │
│     │ hk.gh-proxy.org         │ 50ms    │ ✓ ← 最快             │ │
│     │ cdn.gh-proxy.org        │ 120ms   │ ✓                    │ │
│     │ edgeone.gh-proxy.org    │ 80ms    │ ✓                    │ │
│     └─────────────────────────────────────────────────────────┘ │
│                                                                 │
│  3. 选择最优镜像: hk.gh-proxy.org                                 │
│                                                                 │
│  4. 缓存结果（10分钟有效期）                                      │
│                                                                 │
│  5. 如果所有镜像不可达 → 使用原始 GitHub URL                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. 安全机制

### 8.1 文件校验

```java
// 下载时计算 SHA-256
MessageDigest digest = MessageDigest.getInstance("SHA-256");
// ... 边下载边计算 ...

// 校验
if (!expectedChecksum.equals(actualChecksum)) {
    Files.delete(tempFile);
    throw new RuntimeException("文件校验失败");
}
```

### 8.2 备份机制

```java
// 更新前备份
Path backup = currentJar.resolveSibling(currentJar.getFileName() + ".backup");
Files.copy(currentJar, backup, REPLACE_EXISTING);

// 更新失败时回滚
if (updateFailed) {
    Files.move(backup, currentJar, REPLACE_EXISTING);
}
```

### 8.3 版本控制

- 只更新到更高版本，防止降级
- 支持渠道控制（stable/beta/alpha）
- 预发布版本需要显式开启

---

## 9. 使用指南

### 9.1 启用自动更新

1. 编辑 `mcpatch.yml`：

```yaml
client-update:
  enabled: true
  github-repo: "BalloonUpdate/Mcpatch2JavaClient"
  mirror: auto
```

2. 启动游戏，客户端会自动检查更新

### 9.2 发布新版本

使用 GitHub Actions 手动发布：

1. 进入仓库 → Actions → Manual Release
2. 点击 "Run workflow"
3. 输入版本号（如 `0.0.13`）
4. 点击运行

### 9.3 更新日志

更新日志来自 GitHub Release 的 body 内容，支持 Markdown 格式。

---

## 附录

### A. API 响应示例

```json
{
  "tag_name": "v0.0.12",
  "name": "Mcpatch v0.0.12",
  "body": "- 修复空文件下载崩溃问题\n- 新增镜像加速功能\n- 优化更新检查逻辑",
  "published_at": "2025-04-04T12:00:00Z",
  "prerelease": false,
  "assets": [
    {
      "name": "Mcpatch-0.0.12.jar",
      "browser_download_url": "https://github.com/BalloonUpdate/Mcpatch2JavaClient/releases/download/v0.0.12/Mcpatch-0.0.12.jar",
      "size": 8000000
    }
  ]
}
```

### B. 日志示例

```
[Mcpatch] 正在从 GitHub Release 获取版本: BalloonUpdate/Mcpatch2JavaClient
[Mcpatch] 选择最优镜像站: https://hk.gh-proxy.org/ (延迟: 50ms)
[Mcpatch] 当前版本: 0.0.11, 最新版本: 0.0.12
[Mcpatch] 发现新版本: 0.0.12
[Mcpatch] 更新日志: - 修复空文件下载崩溃问题
[Mcpatch] 正在下载客户端更新...
[Mcpatch] 客户端更新下载完成，将在下次启动时安装
```

### C. 故障排除

| 问题 | 解决方案 |
|------|----------|
| 无法连接 GitHub | 检查网络，启用镜像加速 |
| 下载校验失败 | 重新下载，检查磁盘空间 |
| 无法替换 JAR | 关闭游戏后重新启动 |
| 版本检测失败 | 检查 github-repo 配置 |