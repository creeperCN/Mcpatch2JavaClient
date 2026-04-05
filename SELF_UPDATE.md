# Mcpatch 客户端自我更新文档

## 概述

Mcpatch 客户端内置自我更新功能，允许客户端从服主 McPatch2 管理端自动获取新版本。更新采用延迟安装策略，确保在不同操作系统上都能安全可靠地完成。

## 工作原理

### 延迟安装策略

自我更新采用延迟安装策略，分两次启动完成：

第一次启动：检测更新 -> 下载新版本 -> 创建标记文件 -> 继续运行（旧版本）
第二次启动：检查标记 -> 替换 JAR -> 删除标记 -> 正常运行（新版本）

### 流程图

第一次启动：
  main()/premain()
    -> installPendingUpdate() -> 无标记
    -> AppMain()
       -> performSelfUpdate()
          -> 检测更新
          -> 下载新版本
          -> 创建标记文件

第二次启动：
  main()/premain()
    -> installPendingUpdate()
       -> 检测到标记文件
       -> 重命名替换 JAR
       -> 删除标记文件
    -> AppMain() -> 使用新版本

## 文件锁定问题

### 问题背景

程序运行时，操作系统会锁定 JAR 文件：
- Windows：严格锁定，不允许修改
- Linux/macOS：较宽松，但仍有限制

### 解决方案：重命名交换

使用重命名操作绕过 Windows 文件锁定：

Mcpatch.jar（旧版本，被锁定）
Mcpatch.jar.new（新版本，未锁定）

步骤：
1. Mcpatch.jar -> Mcpatch.jar.old（重命名，Windows 允许）
2. Mcpatch.jar.new -> Mcpatch.jar（重命名）
3. 删除 Mcpatch.jar.old（清理）

## 配置说明

### mcpatch.yml 配置

client-update:
  enabled: true
  server-url: http://your-server:6700

## API 接口

### 版本信息格式

服主服务器需要提供 client-version.json：

{
  version: 1.0.0,
  download_url: http://server/Mcpatch-1.0.0.jar,
  checksum: abc123,
  changelog: 更新说明,
  force_update: false
}

## 文件说明

| 文件 | 说明 |
|------|------|
| Mcpatch.jar | 当前版本 |
| Mcpatch.jar.new | 新版本（待安装）|
| .update-pending | 更新标记文件 |
| Mcpatch.jar.old | 旧版本备份（临时）|

## 故障排除

### 问题：只下载不替换

可能原因：
1. 标记文件未正确创建
2. installPendingUpdate() 未被调用
3. 重命名操作失败

排查步骤：
1. 检查是否存在 .update-pending 文件
2. 检查是否存在 .jar.new 文件
3. 查看日志中的错误信息

### 问题：Windows 上替换失败

可能原因：
1. 文件权限不足
2. 杀毒软件拦截
3. 文件被其他进程占用

解决方案：
1. 以管理员权限运行
2. 添加杀毒软件白名单
3. 关闭占用该文件的程序
