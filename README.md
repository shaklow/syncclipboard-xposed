# SyncClipboard Xposed

**简体中文** | [English](README_EN.md)

一个 LSposed/Xposed 模块，通过 [SyncClipboard Server](https://github.com/Jeric-X/SyncClipboard)、WebDAV 或 S3 兼容存储在设备间同步剪贴板。

> 免 Root：模块注入 `com.android.systemui` 获取系统级剪贴板访问权限，仅需激活 LSPosed。

## 功能

- **自动同步** —— 本地剪贴板变化自动上传；远端变化经轮询 / SignalR 推送自动下载
- **分享接收** —— 从任何应用分享文件/文本到本应用，即可上传到服务器（逐文件进度）
- **剪贴板历史** —— 浏览/搜索/置顶/收藏，服务端分页，本地文件磁盘 LRU（200MB / 400 文件）
- **验证码提取** —— 短信与通知验证码自动提取上传（可选）
- **快捷磁贴** —— 一键开关自动同步，长按进入应用
- **多后端** —— SyncClipboard 服务器（SignalR 推送）、WebDAV、S3 兼容存储
- **省电策略** —— 退避重试（30s→5min）、息屏/省电/移动网络断开、失败自动停止
- **MIUIX 主题** —— 底栏导航、液态玻璃、Monet 动态配色（种子色/风格/规格）
- **热重载** —— LSPosed API 102：更新模块无需重启 SystemUI

## 要求

- Android 10+（API 29+）
- LSPosed（热重载需 LSPosed 2.0+，支持 API 102）
- 一个剪贴板同步服务器（[SyncClipboard](https://github.com/Jeric-X/SyncClipboard) / WebDAV / S3）

## 模块作用域

为 **`com.android.systemui`** 启用本模块。

## 架构

同步引擎（`SyncEngine`）运行在 `com.android.systemui` 进程，通过系统 `ClipboardManager` 监听全局剪贴板变化；配套 App 经 `SyncClipboardBridge`（广播 IPC）与引擎通信。分享上传与预览下载由 App 进程直接执行，引擎维护历史库与远端内容。

## 构建

**环境：** JDK 17+；Android SDK（API 37，经 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir` 配置）；调试密钥库由构建自动生成。

```bash
./gradlew shell:assembleRelease   # Linux / macOS
gradlew.bat shell:assembleRelease # Windows
```

> 发布版使用 Android 调试密钥签名（侧载用）；自定义密钥库见 `shell/build.gradle.kts`。

## 许可证

Apache 2.0

## 致谢

- **[SyncClipboard](https://github.com/Jeric-X/SyncClipboard)**（MIT）—— 剪贴板同步服务器协议与参考实现
- **[InstallerX-Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)**（GPLv3）—— 液态玻璃底栏实现参考
- **[MIUIX](https://github.com/Yukon123/Compose-Miuix)**（Apache 2.0）—— UI 组件库与 Monet 动态配色管线（`ThemeController`）
- **[materialKolor](https://github.com/materialkolor/material-kolor)**（Apache 2.0）—— Material Color Utilities 配色生成实现
- **[SukiSU-Ultra](https://github.com/SukiSU-Ultra)**（GPLv3）—— 主题设置界面的 UI 设计参考与种子色调色板选型
