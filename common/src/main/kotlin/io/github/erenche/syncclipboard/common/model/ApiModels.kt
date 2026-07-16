package io.github.erenche.syncclipboard.common.model

import kotlinx.serialization.Serializable

/**
 * 剪贴板配置 DTO — 与服务器端 ProfileDto 对应
 */
@Serializable
data class ProfileDto(
    /** 剪贴板内容类型 */
    val type: ClipboardContentType,
    /** Profile SHA256 哈希值（用于去重和验证） */
    val hash: String? = null,
    /** 预览文本或完整文本内容 */
    val text: String,
    /** 是否有额外的数据文件 */
    val hasData: Boolean,
    /** 数据文件名 */
    val dataName: String? = null,
    /** 文件大小（字节） */
    val size: Long? = null
)

/**
 * 服务器配置
 */
@Serializable
data class ServerConfig(
    /** 服务器类型 */
    val type: ServerType,
    /** 服务器显示名称（可选） */
    val name: String? = null,
    /** 服务器 URL */
    val url: String,
    /** 用户名（S3 时为 Access Key ID） */
    val username: String? = null,
    /** 密码（S3 时为 Secret Access Key） */
    val password: String? = null,
    /** S3 区域（仅 S3 类型） */
    val region: String? = null,
    /** S3 存储桶名称（仅 S3 类型） */
    val bucketName: String? = null,
    /** S3 对象 key 前缀（仅 S3 类型） */
    val objectPrefix: String? = null,
    /** S3 是否使用路径风格寻址（仅 S3 类型） */
    val forcePathStyle: Boolean = false
)

/**
 * 服务器信息
 */
data class ServerInfo(
    /** 服务器版本 */
    val version: String,
    /** 服务器时间 */
    val serverTime: Long,
    /** 是否在线 */
    val online: Boolean
)

/**
 * 历史记录 DTO — 与 SyncClipboard 服务器 `/api/history` API 对应。
 *
 * 时间字段使用 ISO 8601 字符串（服务器约定）。
 */
@Serializable
data class HistoryRecordDto(
    val hash: String,
    val type: ClipboardContentType,
    val text: String? = null,
    val createTime: String? = null,
    val lastModified: String? = null,
    val lastAccessed: String? = null,
    val starred: Boolean? = null,
    val pinned: Boolean? = null,
    val size: Long? = null,
    val hasData: Boolean? = null,
    val version: Int? = null,
    val isDeleted: Boolean? = null
)

/**
 * 历史记录更新 DTO — 对应原项目 `PATCH /api/history/{type}/{id}` 请求体。
 */
@Serializable
data class HistoryRecordUpdateDto(
    val starred: Boolean? = null,
    val pinned: Boolean? = null,
    val isDelete: Boolean? = null,
    val version: Int? = null,
    val lastModified: String? = null,
    val lastAccessed: String? = null
)

/**
 * 历史记录统计 DTO — 对应原项目 `GET /api/history/statistics` 响应。
 */
@Serializable
data class HistoryStatisticsDto(
    val totalCount: Long = 0,
    val totalSize: Long = 0,
    val totalFileSizeMB: Double = 0.0
)
