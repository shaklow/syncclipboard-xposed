package io.github.erenche.syncclipboard.xposed.history.db

import androidx.room.TypeConverter
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistorySyncStatus

/**
 * Room TypeConverter — 枚举与 String 互转。
 */
class Converters {

    @TypeConverter
    fun fromContentType(type: ClipboardContentType): String = type.name

    @TypeConverter
    fun toContentType(value: String): ClipboardContentType =
        ClipboardContentType.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(status: HistorySyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): HistorySyncStatus =
        HistorySyncStatus.valueOf(value)
}
