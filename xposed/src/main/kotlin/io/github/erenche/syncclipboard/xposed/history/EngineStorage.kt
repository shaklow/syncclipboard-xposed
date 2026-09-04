package io.github.erenche.syncclipboard.xposed.history

import android.content.Context
import java.io.File

/**
 * 引擎存储目录集中管理。
 *
 * 持久数据统一位于 SystemUI filesDir/SyncClipboard/：
 * - clipboard_history.db（+ wal/shm）
 * - history_files/（历史归档文件）
 * - downloads/（当前文件暂存）
 *
 * 上传临时文件位于 cacheDir/SyncClipboard/（临时数据，可随系统缓存清理）。
 */
object EngineStorage {

    private const val ROOT_DIR = "SyncClipboard"
    private const val DB_NAME = "clipboard_history.db"

    fun rootDir(context: Context): File = File(context.filesDir, ROOT_DIR)
    fun historyDir(context: Context): File = File(rootDir(context), "history_files")
    fun downloadsDir(context: Context): File = File(rootDir(context), "downloads")
    fun databaseFile(context: Context): File = File(rootDir(context), DB_NAME)
    fun uploadTempDir(context: Context): File = File(context.cacheDir, ROOT_DIR)

    /** 确保各目录存在（引擎初始化时调用；Room 打开数据库前父目录必须已建） */
    fun ensureDirs(context: Context) {
        rootDir(context).mkdirs()
        historyDir(context).mkdirs()
        downloadsDir(context).mkdirs()
        uploadTempDir(context).mkdirs()
    }

    /** 清理目录集中化之前的孤儿数据（不存在则忽略）：
     *  - filesDir/history_files、filesDir/downloads（旧归档/下载目录）
     *  - databases/clipboard_history.db（+wal/shm，旧 Room 默认位置）
     *  - cacheDir/upload_*（旧上传临时文件） */
    fun cleanupLegacy(context: Context) {
        runCatching {
            File(context.filesDir, "history_files").deleteRecursively()
            File(context.filesDir, "downloads").deleteRecursively()
            File(context.filesDir, "clipboard_history.json").delete()
            File(context.filesDir, "clipboard_history.json.bak").delete()
            val dbDir = context.getDatabasePath(DB_NAME).parentFile
            if (dbDir != null) {
                for (suffix in listOf("", "-wal", "-shm")) {
                    File(dbDir, DB_NAME + suffix).delete()
                }
            }
            context.cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("upload_") }
                ?.forEach { it.delete() }
        }
    }
}
