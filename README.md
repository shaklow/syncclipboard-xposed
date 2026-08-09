# SyncClipboard Xposed

**简体中文** | [English](README_EN.md)

一个 LSposed/Xposed 模块，通过 [SyncClipboard Server](https://github.com/Jeric-X/SyncClipboard)、WebDAV 或 S3 兼容存储在多设备之间同步剪贴板内容。

> **注意：** 本模块注入 `com.android.systemui` 以获取系统级剪贴板访问权限 —— 只要 LSposed 处于激活状态，无需 Root。

## 功能特性

- **自动上传** —— 检测本地剪贴板变化并在后台上传到服务器
- **自动下载** —— 通过轮询或推送通知获取远程剪贴板变化
- **剪贴板历史** —— 浏览、搜索、删除已同步的历史记录
- **短信验证码** —— 自动提取并上传来自短信的验证码（可选）
- **通知验证码** —— 监听所有应用通知自动提取验证码（IM/邮件/银行等，可选，需通知访问权限）
- **快捷磁贴** —— 快捷设置磁贴一键切换自动同步开关，长按进入应用
- **多种后端** —— SyncClipboard HTTP 服务器（支持 SignalR 推送）、WebDAV、S3 兼容存储
- **退避重试** —— 服务器不可达时按 30s/60s/2min/5min 间隔重试，四次失败后停止，避免无效耗电

## 使用要求

- Android 10+（API 29+）
- [LSposed](https://github.com/LSPosed/LSPosed)
- 一个兼容的剪贴板同步服务器（参见 [SyncClipboard](https://github.com/Jeric-X/SyncClipboard)）

## 模块作用域

必须在 LSposed 管理器中为 **`com.android.systemui`** 启用本模块。  

## 项目结构

```
clipboard-xposed/
├── app/        # 配套应用 —— 设置、历史记录 UI、短信接收器
├── bridge/     # IPC 层 —— 基于广播的 app ↔ SystemUI 双向消息通信
├── common/     # 共享模型、工具类、偏好设置
├── xposed/     # Hook 模块 —— 注入 SystemUI，包含 SyncEngine
└── shell/      # 极简 APK 壳 —— 将所有模块打包成一个可安装的 APK
```

## 构建

**环境要求：**
- JDK 17 或更新版本（`java -version`）
- 包含 API 37 的 Android SDK —— 通过 `ANDROID_HOME` 环境变量设置，或创建 `local.properties`：
  ```
  sdk.dir=/path/to/android-sdk        # Linux/macOS
  sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk  # Windows
  ```
- 调试密钥库（`~/.android/debug.keystore`）在不存在时会由构建**自动生成** —— 无需手动配置。

**构建命令：**

```bash
# Linux / macOS
./gradlew shell:assembleRelease

# Windows
gradlew.bat shell:assembleRelease
```

一次构建同时输出 4 个 APK：

```
shell/build/outputs/apk/release/
├── shell-arm64-v8a-release.apk    
├── shell-armeabi-v7a-release.apk 
├── shell-x86_64-release.apk       
└── shell-universal-release.apk   
```

> 发布版 APK 使用标准 Android 调试密钥签名（用于侧载安装）。如需使用自定义密钥库，请编辑 `shell/build.gradle.kts` → `signingConfigs`。

## 架构

同步引擎（`SyncEngine`）运行在 `com.android.systemui` 进程内，由 `GeneralHooker` 通过 LSposed 初始化。它在系统 `ClipboardManager` 上注册 `OnPrimaryClipChangedListener`，以全局检测所有剪贴板变化（无需轮询），上传新内容，并定期拉取远程变化。

配套应用通过 `SyncClipboardBridge` 与引擎通信 —— 这是一个在 `:bridge` 模块中定义的基于广播的轻量级 IPC 层。

## 许可证

Apache 2.0
