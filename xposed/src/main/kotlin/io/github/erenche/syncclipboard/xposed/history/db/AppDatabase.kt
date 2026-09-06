package io.github.erenche.syncclipboard.xposed.history.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.erenche.syncclipboard.xposed.history.EngineStorage

/**
 * Room 数据库 — 剪贴板历史持久化。
 *
 * 数据库文件位于 SystemUI 私有目录（filesDir/SyncClipboard/clipboard_history.db），
 * app 进程不直接访问，仍通过 Bridge IPC 查询。
 */
@Database(
    entities = [HistoryItemEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun historyItemDao(): HistoryItemDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    // 绝对路径：落盘于 filesDir/SyncClipboard/（路径以 / 开头时 Room 不再用 databases/）
                    EngineStorage.databaseFile(context.applicationContext)
                        .apply { parentFile?.mkdirs() }
                        .absolutePath
                ).build().also { instance = it }
            }
        }

        /** 关闭并复位单例（模块热重载前调用，释放旧代数据库连接） */
        fun closeInstance() {
            instance?.let { db -> runCatching { db.close() } }
            instance = null
        }
    }
}
