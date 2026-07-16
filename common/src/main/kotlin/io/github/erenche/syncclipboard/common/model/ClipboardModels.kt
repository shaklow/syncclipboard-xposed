package io.github.erenche.syncclipboard.common.model

import kotlinx.serialization.Serializable

/**
 * 剪贴板内容 — 统一的剪贴板数据模型
 */
data class ClipboardContent(
    /** 内容类型 */
    val type: ClipboardContentType,
    /** 文本内容 */
    val text: String,
    /** 文件 URI（本地文件路径） */
    val fileUri: String? = null,
    /** 文件名 */
    val fileName: String? = null,
    /** 文件大小 */
    val fileSize: Long? = null,
    /** Profile hash（用于服务器上传，遵循服务器规则） */
    val profileHash: String? = null,
    /** 本地剪贴板 hash（用于本地变化检测） */
    val localClipboardHash: String? = null,
    /** 是否有额外数据文件 */
    val hasData: Boolean = false,
    /** 创建时间戳 */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 历史记录项
 */
@Serializable
data class HistoryItem(
    /** 唯一标识 */
    val id: String,
    /** 内容类型 */
    val type: ClipboardContentType,
    /** 文本内容（预览或完整） */
    val text: String,
    /** Profile hash 值 */
    val profileHash: String,
    /** 是否有额外数据 */
    val hasData: Boolean,
    /** 数据文件名 */
    val dataName: String? = null,
    /** 文件大小（字节） */
    val size: Long? = null,
    /** 创建时间戳 */
    val timestamp: Long,
    /** 设备名称 */
    val deviceName: String? = null,
    /** 是否已标记（收藏） */
    val starred: Boolean = false,
    /** 同步状态 */
    val syncStatus: HistorySyncStatus = HistorySyncStatus.LocalOnly,
    /** 版本号（乐观锁） */
    val version: Int = 0,
    /** 最后修改时间（UTC时间戳） */
    val lastModified: Long = System.currentTimeMillis(),
    /** 最后访问时间（UTC时间戳） */
    val lastAccessed: Long = System.currentTimeMillis(),
    /** 是否已删除（软删除标记） */
    val isDeleted: Boolean = false,
    /** 是否置顶 */
    val pinned: Boolean = false,
    /** 本地文件 URI */
    val fileUri: String? = null,
    /** 来源设备 */
    val from: String? = null
)

/**
 * 创建 HistoryItem 的工厂函数，提供合理默认值
 */
fun createHistoryItem(
    id: String,
    type: ClipboardContentType,
    text: String,
    profileHash: String,
    hasData: Boolean,
    dataName: String? = null,
    size: Long? = null,
    timestamp: Long = System.currentTimeMillis(),
    deviceName: String? = null,
    starred: Boolean = false,
    syncStatus: HistorySyncStatus = HistorySyncStatus.LocalOnly,
    version: Int = 0,
    fileUri: String? = null,
    from: String? = null
): HistoryItem {
    val now = System.currentTimeMillis()
    return HistoryItem(
        id = id,
        type = type,
        text = text,
        profileHash = profileHash,
        hasData = hasData,
        dataName = dataName,
        size = size,
        timestamp = timestamp,
        deviceName = deviceName,
        starred = starred,
        syncStatus = syncStatus,
        version = version,
        lastModified = now,
        lastAccessed = now,
        isDeleted = false,
        pinned = false,
        fileUri = fileUri,
        from = from
    )
}
