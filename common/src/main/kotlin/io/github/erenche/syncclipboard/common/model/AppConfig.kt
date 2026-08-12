package io.github.erenche.syncclipboard.common.model

import kotlinx.serialization.Serializable

/**
 * 应用配置 — 持久化的完整配置（端口自 TypeScript AppConfig）
 */
@Serializable
data class AppConfig(
    /** 服务器配置列表 */
    val servers: List<ServerConfig> = emptyList(),
    /** 当前激活的服务器索引 */
    val activeServerIndex: Int = -1,
    /** 同步间隔（毫秒） */
    val syncInterval: Long = 5000,
    /** 是否启用自动同步（总开关）。关闭后不轮询远程、不上传本地，子开关也会被置为 false */
    val enableAutoSync: Boolean = true,
    /** 冲突解决策略 */
    val conflictResolution: ConflictResolution = ConflictResolution.Newest,
    /** 是否同步大文件 */
    val syncLargeFiles: Boolean = true,
    /** 大文件阈值（字节） */
    val largeFileThreshold: Long = 10 * 1024 * 1024, // 10MB
    /** 是否在后台时下载远程剪贴板 */
    val enableBackgroundDownload: Boolean = true,
    /** 是否在后台时上传本地剪贴板 */
    val enableBackgroundUpload: Boolean = true,
    /** 是否启用历史记录同步 */
    val enableHistorySync: Boolean = false,
    /** 自动下载最大文件大小（字节），默认 5MB */
    val autoDownloadMaxSize: Long = 5 * 1024 * 1024,
    /** 远程轮询间隔（毫秒），用于 WebDAV/S3 回退 */
    val remotePollingInterval: Long = 3000,
    /** 轮询间隔（秒）。优先使用此字段，<=0 时回退到 [remotePollingInterval] */
    val pollingIntervalSec: Int = 3,
    /** 省电模式下停止远程轮询 */
    val stopPollingOnBatterySaver: Boolean = false,
    /** 熄屏时停止远程轮询 */
    val stopPollingOnScreenOff: Boolean = false,
    /** 是否启用详细日志（Debug/Info 级别），关闭时仅输出 Warn/Error */
    val enableLogging: Boolean = true,
    /** 日志等级 */
    val logLevel: LogLevel = LogLevel.Info,
    /** 内存日志缓冲区最大行数 */
    val logBufferSize: Int = 2000,
    /** 历史记录最大保留条数 */
    val maxHistoryItems: Int = 1000,
    /** 自动同步时是否将图片/文件自动保存到相册/下载目录 */
    val enableAutoSave: Boolean = false,
    /** 自动上传短信验证码：收到含验证码的短信时自动提取并上传到服务器 */
    val enableSmsUpload: Boolean = false,
    /** 自动上传通知验证码：监听所有应用通知，提取验证码后上传到服务器。
     *  需要用户在系统设置中授予"通知访问权限"。独立于 [enableSmsUpload]。 */
    val enableNotificationUpload: Boolean = false,
    /** 是否启用 SignalR 推送（仅 SyncClipboard 官方服务器模式生效）。
     *  开启后通过 WebSocket 长连接接收服务器推送，轮询降级为 60s 兜底；
     *  WebDAV/S3 模式忽略此选项，始终走轮询。 */
    val enableSignalRPush: Boolean = true
)

/**
 * 冲突解决策略
 */
@Serializable
enum class ConflictResolution {
    /** 以最新为准 */
    Newest,
    /** 以本地为准 */
    Local,
    /** 以远程为准 */
    Remote
}

/**
 * 默认应用配置
 */
val DEFAULT_APP_CONFIG = AppConfig()
