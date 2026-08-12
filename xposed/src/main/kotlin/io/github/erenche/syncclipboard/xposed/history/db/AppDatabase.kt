package io.github.erenche.syncclipboard.xposed.history.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room 数据库 — 剪贴板历史持久化。
 *
 * 数据库文件位于 SystemUI 私有目录（context.filesDir/clipboard_history.db），
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
                    "clipboard_history.db"
                ).build().also { instance = it }
            }
        }
    }
}
