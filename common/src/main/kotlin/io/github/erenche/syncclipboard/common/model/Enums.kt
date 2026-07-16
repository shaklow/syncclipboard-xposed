package io.github.erenche.syncclipboard.common.model

import kotlinx.serialization.Serializable

/**
 * 剪贴板内容类型枚举
 * 服务器返回 PascalCase（Text/Image/File），与 Kotlin 枚举名一致，无需 @SerialName。
 */
@Serializable
enum class ClipboardContentType {
    Text,
    Image,
    File,
    Group
}

/**
 * 服务器类型
 */
@Serializable
enum class ServerType {
    syncclipboard,
    webdav,
    s3
}

/**
 * 历史记录同步状态
 */
@Serializable
enum class HistorySyncStatus {
    /** 仅本地存在 */
    LocalOnly,
    /** 已与服务器同步 */
    Synced,
    /** 需要同步 */
    NeedSync
}

/**
 * 日志等级
 */
@Serializable
enum class LogLevel {
    Debug,
    Info,
    Warn,
    Error
}
