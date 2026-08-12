package io.github.erenche.syncclipboard.xposed.history.db

import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一次性迁移工具 — 从旧版 JSON 文件导入历史记录到 Room 数据库。
 *
 * 迁移条件：clipboard_history.json 文件存在。
 * 迁移完成后将 JSON 重命名为 .bak，避免重复迁移。
 */
object HistoryMigrator {

    private const val TAG = "HistoryMigrator"
    private const val JSON_FILE = "clipboard_history.json"

    /**
     * 如果旧版 JSON 文件存在，反序列化并导入数据库。
     *
     * @param filesDir 上下文 filesDir（SystemUI 私有目录）
     * @param dao 历史 DAO
     * @return 导入的记录数（0 表示无需迁移）
     */
    fun migrateIfNeeded(filesDir: File, dao: HistoryItemDao): Int {
        val jsonFile = File(filesDir, JSON_FILE)
        if (!jsonFile.exists()) {
            Logger.debug(TAG, "No legacy JSON file found, skipping migration")
            return 0
        }

        return try {
            val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
            val items: List<HistoryItem> = json.decodeFromString(
                ListSerializer(HistoryItem.serializer()),
                jsonFile.readText()
            )

            if (items.isEmpty()) {
                Logger.info(TAG, "Legacy JSON was empty, marking as migrated")
            } else {
                // 修复旧版同步遗留的 Image/File hasData=false / dataName=null
                val fixed = items.map { item ->
                    if ((item.type == ClipboardContentType.Image || item.type == ClipboardContentType.File)
                        && (!item.hasData || item.dataName.isNullOrBlank())
                    ) {
                        item.copy(
                            hasData = true,
                            dataName = item.dataName ?: item.text?.takeIf { it.isNotBlank() }
                                ?: "${item.type.name.lowercase()}_${item.profileHash.take(8)}"
                        )
                    } else item
                }
                val entities = fixed.map { HistoryItemEntity.from(it) }
                dao.upsertAll(entities)
                Logger.info(TAG, "Migrated ${entities.size} records from JSON to Room")
            }

            // 重命名为 .bak 避免重复迁移
            val bakFile = File(filesDir, "$JSON_FILE.bak")
            jsonFile.renameTo(bakFile)
            items.size
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to migrate JSON to Room", e)
            0
        }
    }
}
