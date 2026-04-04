# Mcpatch2JavaClient 自动更新方案

## 方案概述

由于客户端作为 Java Agent 运行，JAR 文件在运行时被锁定无法直接替换，因此采用 **"下载-标记-替换"** 的延迟更新策略。

---

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    启动流程                              │
├─────────────────────────────────────────────────────────┤
│  1. 启动 Minecraft                                      │
│         ↓                                               │
│  2. Java Agent (update.jar) 加载                        │
│         ↓                                               │
│  3. 检查客户端自身更新 ←─────────────────┐              │
│         ↓                                │               │
│  4. 如有更新: 下载新版本到临时位置        │               │
│         ↓                                │               │
│  5. 检查游戏文件更新                     │               │
│         ↓                                │               │
│  6. 启动 Minecraft                       │               │
│         ↓                                │               │
│  7. 程序退出时/下次启动: 替换旧版本 ──────┘               │
└─────────────────────────────────────────────────────────┘
```

---

## 实现方案

### 1. 版本检测

在配置文件 `mcpatch.yml` 中添加客户端更新配置：

```yaml
# 客户端自身更新配置
client-update:
  # 客户端版本检查 URL
  version-url: "https://your-server.com/mcpatch/client-version.json"
  # 是否启用自动更新
  enabled: true
  # 更新渠道: stable/beta/alpha
  channel: stable
```

### 2. 版本信息文件 (服务端)

`client-version.json`:
```json
{
  "latest_version": "0.0.12",
  "min_version": "0.0.10",
  "download_url": "https://your-server.com/mcpatch/Mcpatch-0.0.12.jar",
  "checksum": "abc123def456...",
  "changelog": "修复了若干bug",
  "release_date": "2025-04-04",
  "force_update": false
}
```

### 3. 核心代码结构

```
src/main/java/com/github/balloonupdate/mcpatch/client/
├── selfupdate/
│   ├── SelfUpdateChecker.java    # 检查更新
│   ├── SelfUpdateDownloader.java # 下载新版本
│   ├── SelfUpdateInstaller.java  # 安装更新
│   └── ClientVersionInfo.java    # 版本信息
```

---

## 详细设计

### 阶段 1: 启动时检查

```java
// Main.java 中的修改
public static void premain(String agentArgs, Instrumentation ins) throws Throwable {
    // 1. 先检查客户端自身更新
    if (checkSelfUpdate()) {
        downloadSelfUpdate();
    }

    // 2. 检查是否有待安装的更新
    installPendingUpdate();

    // 3. 继续正常的游戏文件更新流程
    // ...
}
```

### 阶段 2: 下载新版本

```java
public class SelfUpdateDownloader {
    /**
     * 下载新版本到临时文件
     * Windows: %TEMP%\mcpatch-update.jar
     * Linux/Mac: /tmp/mcpatch-update.jar
     */
    public static Path downloadNewVersion(String downloadUrl, String expectedChecksum) {
        Path tempFile = getUpdateTempFile();

        // 下载文件
        downloadFile(downloadUrl, tempFile);

        // 校验 checksum
        if (!verifyChecksum(tempFile, expectedChecksum)) {
            throw new RuntimeException("Checksum verification failed");
        }

        // 创建标记文件，表示有待安装的更新
        createUpdateMarker(tempFile);

        return tempFile;
    }
}
```

### 阶段 3: 延迟替换

```java
public class SelfUpdateInstaller {
    /**
     * 安装待处理的更新
     * 这个方法在程序启动最开始执行
     */
    public static boolean installPendingUpdate() {
        Path markerFile = getUpdateMarkerFile();

        if (!Files.exists(markerFile)) {
            return false; // 没有待安装的更新
        }

        // 读取标记文件获取新版本路径
        String newVersionPath = Files.readString(markerFile);
        Path newJar = Paths.get(newVersionPath);
        Path currentJar = Env.getJarPath();

        if (!Files.exists(newJar)) {
            // 新版本文件不存在，清理标记
            Files.delete(markerFile);
            return false;
        }

        // 替换 JAR 文件
        backupCurrentVersion(currentJar);
        Files.move(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(markerFile);

        Log.info("客户端已更新到最新版本");
        return true;
    }

    /**
     * 备份当前版本，以防回滚
     */
    private static void backupCurrentVersion(Path currentJar) {
        Path backup = currentJar.resolveSibling(currentJar.getFileName() + ".backup");
        Files.copy(currentJar, backup, StandardCopyOption.REPLACE_EXISTING);
    }
}
```

---

## 更新流程图

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   程序启动   │────→│ 检查标记文件 │────→│ 有待更新?    │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
                    ┌─────────────────────────────┴─────┐
                    │ Yes                               │ No
                    ↓                                   ↓
            ┌──────────────┐                    ┌──────────────┐
            │ 替换 JAR 文件│                    │ 正常启动流程 │
            └──────┬───────┘                    └──────────────┘
                    │
                    ↓
            ┌──────────────┐
            │ 检查新版本   │
            └──────┬───────┘
                    │
                    ├── 有新版本 ──→ 下载到临时位置 ──→ 创建标记文件 ──→ 继续启动
                    │
                    └── 无新版本 ──→ 继续正常启动流程
```

---

## 文件结构

```
.minecraft/versions/xxx/
├── update.jar              # 当前运行的客户端
├── update.jar.backup       # 备份文件（用于回滚）
├── update.jar.new         # 待安装的新版本（Windows）
└── .mcpatch-temp/
    └── self-update.marker # 更新标记文件
```

---

## 配置示例

```yaml
# mcpatch.yml 完整配置示例

# 游戏文件更新服务器
urls:
  - "https://update.example.com/mcpatch/"

# 客户端自身更新配置
client-update:
  enabled: true
  version-url: "https://update.example.com/client-version.json"
  channel: stable
  auto-install: true      # 自动安装更新
  backup-enabled: true    # 是否备份旧版本
  rollback-on-failure: true # 更新失败时回滚

# 其他配置...
version-file-path: "version-label.txt"
window-title: "游戏更新器"
```

---

## 优势

| 特性 | 说明 |
|------|------|
| ✅ 非阻塞更新 | 不影响当前游戏运行 |
| ✅ 自动回滚 | 更新失败可恢复 |
| ✅ 校验机制 | Checksum 防止损坏 |
| ✅ 跨平台 | Windows/Linux/macOS 兼容 |
| ✅ 可配置 | 支持多渠道更新 |

---

## 注意事项

1. **首次运行**: 需要手动部署初始版本
2. **权限问题**: 确保有写入 JAR 目录的权限
3. **网络超时**: 需要处理网络异常情况
4. **版本回退**: 保留备份文件支持回滚