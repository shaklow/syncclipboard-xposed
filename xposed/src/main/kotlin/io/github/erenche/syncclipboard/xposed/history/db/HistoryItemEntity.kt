package io.github.erenche.syncclipboard.xposed.history.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.HistorySyncStatus

/**
 * 剪贴板历史记录 Room Entity — 与 [HistoryItem] 字段一一对应。
 *
 * 索引设计基于现有查询模式：
 * - [profileHash] 唯一索引：按 hash 查询（markAsNeedSync / applyServerUpdate 等）
 * - (isDeleted, pinned, timestamp) 复合索引：分页查询的排序过滤
 * - syncStatus 索引：过滤 NeedSync / LocalOnly 记录
 */
@Entity(
    tableName = "history_items",
    indices = [
        Index(value = ["profileHash"], unique = true),
        Index(value = ["isDeleted", "pinned", "timestamp"]),
        Index(value = ["syncStatus"])
    ]
)
data class HistoryItemEntity(
    @PrimaryKey
    val id: String,
    val type: ClipboardContentType,
    val text: String,
    val profileHash: String,
    val hasData: Boolean,
    val dataName: String?,
    val size: Long?,
    val timestamp: Long,
    val deviceName: String?,
    val starred: Boolean,
    val syncStatus: HistorySyncStatus,
    val version: Int,
    val lastModified: Long,
    val lastAccessed: Long,
    val isDeleted: Boolean,
    val pinned: Boolean,
    val fileUri: String?,
    val from: String?
) {
    /** 转换为领域模型 */
    fun toModel(): HistoryItem = HistoryItem(
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
        lastModified = lastModified,
        lastAccessed = lastAccessed,
        isDeleted = isDeleted,
        pinned = pinned,
        fileUri = fileUri,
        from = from
    )

    companion object {
        /** 从领域模型转换 */
        fun from(item: HistoryItem): HistoryItemEntity = HistoryItemEntity(
            id = item.id,
            type = item.type,
            text = item.text,
            profileHash = item.profileHash,
            hasData = item.hasData,
            dataName = item.dataName,
            size = item.size,
            timestamp = item.timestamp,
            deviceName = item.deviceName,
            starred = item.starred,
            syncStatus = item.syncStatus,
            version = item.version,
            lastModified = item.lastModified,
            lastAccessed = item.lastAccessed,
            isDeleted = item.isDeleted,
            pinned = item.pinned,
            fileUri = item.fileUri,
            from = item.from
        )
    }
}
