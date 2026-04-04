# Mcpatch2JavaClient 自动更新方案

## 方案概述

客户端通过 GitHub Release API 自动检查并更新自身版本，支持国内镜像加速。

---

## 架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                         启动流程                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   1. 启动 Minecraft                                                  │
│          │                                                          │
│          ▼                                                          │
│   2. Java Agent (update.jar) 加载                                    │
│          │                                                          │
│          ▼                                                          │
│   3. 检查标记文件（有待安装的更新？）                                   │
│          ├── 有 → 安装更新 → 继续启动                                 │
│          └── 无 → 继续                                               │
│          │                                                          │
│          ▼                                                          │
│   4. 请求 GitHub Release API                                         │
│          │                                                          │
│          ▼                                                          │
│   5. 比较版本号                                                       │
│          ├── 有新版本 → 下载 → 创建标记文件                            │
│          └── 无新版本 → 继续                                         │
│          │                                                          │
│          ▼                                                          │
│   6. 检查游戏文件更新 → 启动 Minecraft                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 核心组件

```
src/main/java/com/github/balloonupdate/mcpatch/client/selfupdate/
├── SelfUpdateManager.java      # 更新管理器（统一调度）
├── SelfUpdateChecker.java      # 版本检查器
├── SelfUpdateDownloader.java   # 下载器
├── SelfUpdateInstaller.java    # 安装器
├── ClientVersionInfo.java      # 版本信息模型
├── GitHubReleaseClient.java    # GitHub Release API 客户端
└── GitHubMirror.java           # GitHub 镜像加速器
```

---

## GitHub Release API

### 获取最新版本

```bash
GET https://api.github.com/repos/{owner}/{repo}/releases/latest
```

### 响应示例

```json
{
  "tag_name": "v0.0.12",
  "name": "Mcpatch v0.0.12",
  "body": "- 修复空文件下载崩溃问题\n- 新增镜像加速功能",
  "published_at": "2025-04-04T12:00:00Z",
  "prerelease": false,
  "assets": [
    {
      "name": "Mcpatch-0.0.12.jar",
      "browser_download_url": "https://github.com/xxx/releases/download/v0.0.12/Mcpatch-0.0.12.jar",
      "size": 8000000
    }
  ]
}
```

---

## 镜像加速

### 支持的镜像站

| 镜像站 | 说明 |
|--------|------|
| `https://gh-proxy.org/` | 主站 |
| `https://hk.gh-proxy.org/` | 香港节点 |
| `https://cdn.gh-proxy.org/` | CDN 加速 |
| `https://edgeone.gh-proxy.org/` | EdgeOne 加速 |

### 镜像选择流程

1. Ping 测试所有镜像站延迟
2. 选择延迟最低的镜像站
3. 缓存最优镜像 10 分钟
4. 如果所有镜像不可达，使用原始链接

---

## 配置示例

```yaml
client-update:
  # 是否启用客户端自动更新
  enabled: true

  # GitHub 仓库配置
  github-repo: "BalloonUpdate/Mcpatch2JavaClient"

  # 镜像加速（国内环境推荐开启）
  mirror: auto

  # 更新渠道
  channel: stable

  # 是否自动安装更新
  auto-install: true

  # 是否备份当前版本
  backup-enabled: true

  # 更新失败时是否自动回滚
  rollback-on-failure: true
```

---

## 更新流程

```
时间线：
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   第 N 次启动                                                    │
│   1. 检查标记文件 → 不存在                                       │
│   2. 请求 GitHub Release API                                    │
│   3. 发现新版本 0.0.12                                          │
│   4. 下载到临时目录                                              │
│   5. 创建标记文件                                                │
│   6. 继续正常运行                                                │
│                                                                 │
│   第 N 次退出                                                    │
│   JVM 退出，JAR 文件解锁                                         │
│                                                                 │
│   第 N+1 次启动                                                  │
│   1. 检查标记文件 → 存在！                                       │
│   2. 备份当前版本                                                │
│   3. 替换 JAR 文件                                               │
│   4. 删除标记文件                                                │
│   5. 继续启动（已是新版本）                                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 文件结构

```
临时目录（系统 temp）：
├── .mcpatch-selfupdate-marker    # 标记文件
└── mcpatch-update-new.jar        # 新版本 JAR

游戏目录：
├── update.jar                    # 当前版本
└── update.jar.backup            # 备份文件
```

---

## 功能特性

| 特性 | 说明 |
|------|------|
| ✅ GitHub Release | 免费托管，CDN 加速 |
| ✅ 镜像加速 | 国内网络优化 |
| ✅ 自动选择 | Ping 测试选择最快镜像 |
| ✅ 安全校验 | SHA-256 文件校验 |
| ✅ 自动备份 | 更新前备份当前版本 |
| ✅ 失败回滚 | 更新失败自动恢复 |
| ✅ 延迟安装 | 避免 JAR 文件锁定问题 |