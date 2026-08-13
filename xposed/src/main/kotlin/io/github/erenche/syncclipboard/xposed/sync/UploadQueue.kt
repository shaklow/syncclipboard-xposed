package io.github.erenche.syncclipboard.xposed.sync

import android.content.Context
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 上传失败重试队列。
 *
 * 本地剪贴板内容上传失败时入队（不丢弃），网络恢复后由
 * [UploadQueueFlusher] 触发重放，避免"复制时网络不可达 → 内容永久丢失"。
 *
 * 持久化到 SharedPreferences（JSON）：SystemUI 进程重启后队列仍在。
 * 注意：文件类内容保存的是原始 content:// URI，重启后可能失去读取权限，
 * 重试时读取失败会跳过该条（尽力而为）。
 */
@Serializable
data class QueuedUpload(
    val content: ClipboardContent,
    val retryCount: Int = 0,
    val enqueuedAt: Long = System.currentTimeMillis()
)

object UploadQueue {
    private const val TAG = "UploadQueue"
    private const val KEY_QUEUE = "upload_queue"
    private const val MAX_QUEUE_SIZE = 20

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun prefs(context: Context) = Prefs.getPrefs(context)

    /** 内容唯一键：优先 profileHash，其次文本/文件 URI 的 sha256（与上传去重一致） */
    fun contentKey(content: ClipboardContent): String {
        return content.profileHash
            ?: if (content.hasData && content.fileUri != null) {
                HashUtils.sha256(content.fileUri!!)
            } else {
                HashUtils.sha256(content.text)
            }
    }

    fun isEmpty(context: Context): Boolean = snapshot(context).isEmpty()

    fun size(context: Context): Int = snapshot(context).size

    /** 读取队列快照（已持久化，仅内存操作） */
    fun snapshot(context: Context): List<QueuedUpload> {
        return try {
            val raw = prefs(context).getString(KEY_QUEUE, null) ?: return emptyList()
            json.decodeFromString(ListSerializer(QueuedUpload.serializer()), raw)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to load upload queue: ${e.message}")
            emptyList()
        }
    }

    /**
     * 入队。相同内容（同 key）只保留最新一条；超过上限丢弃最旧的。
     */
    fun enqueue(context: Context, content: ClipboardContent) {
        val key = contentKey(content)
        val list = snapshot(context).toMutableList()
        list.removeAll { contentKey(it.content) == key }
        list.add(QueuedUpload(content = content))
        val trimmed = list.takeLast(MAX_QUEUE_SIZE)
        persist(context, trimmed)
        Logger.info(TAG, "Upload queued (size=${trimmed.size}): ${content.text.take(30)}")
    }

    /** 取出队首（不移除） */
    fun peek(context: Context): QueuedUpload? = snapshot(context).firstOrNull()

    /** 从队列移除（上传成功后调用） */
    fun remove(context: Context, key: String) {
        val list = snapshot(context).filterNot { contentKey(it.content) == key }
        if (list.size != snapshot(context).size) {
            persist(context, list)
            Logger.info(TAG, "Upload de-queued, remaining=${list.size}")
        }
    }

    /** 清空队列 */
    fun clear(context: Context) {
        persist(context, emptyList())
    }

    /** 记录一次失败重试计数（达到上限后由调用方丢弃） */
    fun markRetry(context: Context, key: String) {
        val list = snapshot(context).map { item ->
            if (contentKey(item.content) == key) {
                item.copy(retryCount = item.retryCount + 1)
            } else {
                item
            }
        }
        persist(context, list)
    }

    private fun persist(context: Context, list: List<QueuedUpload>) {
        try {
            prefs(context).edit()
                .putString(KEY_QUEUE, json.encodeToString(ListSerializer(QueuedUpload.serializer()), list))
                .apply()
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to persist upload queue: ${e.message}")
        }
    }
}
