package io.github.erenche.syncclipboard.app.transfer

import android.content.Context
import android.provider.MediaStore
import io.github.erenche.syncclipboard.app.net.ServerApi
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.ServerType
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** 传输任务状态 */
enum class TransferState {
    /** 排队等待下载 */
    PENDING,

    /** 下载中 */
    DOWNLOADING,

    /** 下载完成并已保存 */
    COMPLETED,

    /** 下载失败 */
    FAILED,

    /** 用户取消 */
    CANCELLED,
}

/** 传输队列中的单个任务 */
data class TransferTask(
    val id: String,
    val item: HistoryItem,
    val state: TransferState = TransferState.PENDING,
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * HistoryTransferQueue — 历史文件批量后台下载队列（app 进程内单例）。
 *
 * - [enqueue] 批量入队（按 id 去重：进行中/排队中跳过，已完成/失败/取消可重新入队）
 * - 最多 [MAX_PARALLEL] 个下载并行，剩余任务排队等待
 * - 下载进度实时更新到 [tasks]，UI 侧 collectAsState 即可得到进度反馈
 * - 下载完成后自动保存到相册（图片）/下载目录（文件）
 * - 支持取消/重试/清空已完成；页面关闭后队列在 app 进程内继续执行（后台下载）
 */
object HistoryTransferQueue {

    private const val TAG = "TransferQueue"
    private const val MAX_PARALLEL = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    /** 进行中（排队 + 下载中）的任务 id 集合 — 供入队去重与角标展示 */
    val activeIds: Set<String>
        get() = _tasks.value
            .filter { it.state == TransferState.PENDING || it.state == TransferState.DOWNLOADING }
            .map { it.id }
            .toSet()

    private var appContext: Context? = null
    private val activeJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var running = false

    /** 批量入队下载。已在进行中（排队/下载）的任务跳过；已完成/失败/取消的重新入队。 */
    fun enqueue(context: Context, items: List<HistoryItem>) {
        if (appContext == null) appContext = context.applicationContext
        val candidates = items.filter {
            (it.type == ClipboardContentType.Image || it.type == ClipboardContentType.File) && it.hasData
        }
        if (candidates.isEmpty()) return
        val activeIds = activeIds()
        val ids = _tasks.value.map { it.id }.toSet()
        val newTasks = candidates
            .filter { it.id !in activeIds }
            .map { TransferTask(id = it.id, item = it) }
        // 已完成/失败/取消的同 id 任务：替换为重新下载
        _tasks.update { list ->
            val kept = list.filter { it.id !in newTasks.map { t -> t.id } }
            kept + newTasks
        }
        Logger.info(TAG, "enqueue: ${newTasks.size} task(s) added (total=${_tasks.value.size})")
        if (newTasks.isNotEmpty()) pump()
    }

    /** 重新下载失败/取消的任务 */
    fun retry(taskId: String) {
        _tasks.update { list ->
            list.map {
                if (it.id == taskId && (it.state == TransferState.FAILED || it.state == TransferState.CANCELLED)) {
                    it.copy(state = TransferState.PENDING, progress = 0f, error = null)
                } else {
                    it
                }
            }
        }
        pump()
    }

    /** 取消单个任务：下载中立即中止（标记已取消），排队中直接标记已取消 */
    fun cancel(taskId: String) {
        scope.launch {
            mutex.withLock {
                activeJobs[taskId]?.cancel()
            }
            _tasks.update { list ->
                list.map {
                    if (it.id == taskId && it.state == TransferState.PENDING) {
                        it.copy(state = TransferState.CANCELLED)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** 取消全部：停止所有下载中任务，排队任务标记为已取消 */
    fun cancelAll() {
        scope.launch {
            mutex.withLock {
                activeJobs.values.forEach { it.cancel() }
            }
            _tasks.update { list ->
                list.map {
                    if (it.state == TransferState.PENDING) it.copy(state = TransferState.CANCELLED)
                    else it
                }
            }
        }
    }

    /** 清空已完成/失败/已取消的任务（保留进行中的） */
    fun clearFinished() {
        _tasks.update { list ->
            list.filter {
                it.state == TransferState.PENDING || it.state == TransferState.DOWNLOADING
            }
        }
    }

    /** 移除单条任务（进行中任务会一并取消） */
    fun remove(taskId: String) {
        scope.launch {
            mutex.withLock {
                activeJobs[taskId]?.cancel()
            }
            _tasks.update { list -> list.filterNot { it.id == taskId } }
        }
    }

    // ─── 内部实现 ────────────────────────────────────────────────

    private fun activeIds(): Set<String> = _tasks.value
        .filter { it.state == TransferState.PENDING || it.state == TransferState.DOWNLOADING }
        .map { it.id }
        .toSet()

    private fun updateTask(taskId: String, transform: (TransferTask) -> TransferTask) {
        _tasks.update { list -> list.map { if (it.id == taskId) transform(it) else it } }
    }

    /** 调度循环：启动 PENDING 任务直到并发数饱和；无任务时退出 */
    private fun pump() {
        if (running) return
        running = true
        scope.launch {
            while (isActive) {
                var started = false
                mutex.withLock {
                    while (activeJobs.size < MAX_PARALLEL) {
                        val next = _tasks.value.firstOrNull { it.state == TransferState.PENDING }
                            ?: break
                        _tasks.update { list ->
                            list.map {
                                if (it.id == next.id) it.copy(state = TransferState.DOWNLOADING) else it
                            }
                        }
                        val job = scope.launch { processTask(next) }
                        activeJobs[next.id] = job
                        started = true
                    }
                }
                val idle = mutex.withLock {
                    activeJobs.isEmpty() && _tasks.value.none { it.state == TransferState.PENDING }
                }
                if (idle) {
                    running = false
                    return@launch
                }
                if (!started) delay(150)
            }
        }
    }

    /** 执行单个任务：下载（网络或本地拷贝）→ 保存到相册/下载目录 */
    private suspend fun processTask(task: TransferTask) {
        val jobCtx = currentCoroutineContext()
        try {
            val ctx = appContext ?: throw IOException("Transfer queue not initialized")
            val item = task.item
            val config = Prefs.loadConfig(ctx)
            val server = config.servers.getOrNull(config.activeServerIndex)
                ?: throw IOException("No active server")
            val fileName = item.dataName ?: "file_${item.id}"
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tempFile = File(ctx.cacheDir, "transfer_${item.id}_$safeName")

            val source: File
            if (server.type == ServerType.syncclipboard) {
                Logger.info(TAG, "downloading ${item.id} ($fileName) from server")
                val api = ServerApi(server)
                val downloaded = api.downloadHistoryData(
                    type = item.type,
                    hash = item.profileHash,
                    destFile = tempFile,
                    onProgress = { progress ->
                        if (jobCtx.isActive) {
                            updateTask(item.id) { it.copy(progress = progress) }
                        }
                    },
                    isCancelled = { !jobCtx.isActive }
                ) ?: throw IOException("Server returned an error")
                source = downloaded
            } else {
                // WebDAV/S3：历史文件保存在本地，直接使用本地文件
                val local = item.fileUri?.let { File(it) }?.takeIf { it.exists() }
                    ?: throw IOException("Local file not found")
                source = local
            }

            if (!jobCtx.isActive) throw IOException("Download cancelled")
            saveToMediaStore(ctx, source, item, fileName)

            if (source != tempFile) tempFile.delete() // 清理临时下载文件
            if (jobCtx.isActive) {
                updateTask(item.id) { it.copy(state = TransferState.COMPLETED, progress = 1f) }
                Logger.info(TAG, "completed ${item.id} ($fileName)")
            }
        } catch (e: Exception) {
            // 任务协程被取消：标记为已取消而非失败
            if (!jobCtx.isActive) {
                updateTask(task.id) { it.copy(state = TransferState.CANCELLED) }
            } else {
                updateTask(task.id) { it.copy(state = TransferState.FAILED, error = e.message) }
                Logger.warn(TAG, "failed ${task.id}: ${e.message}")
            }
        } finally {
            mutex.withLock { activeJobs.remove(task.id) }
        }
    }

    /** 保存文件到相册（图片）/下载目录（文件） */
    private fun saveToMediaStore(context: Context, file: File, item: HistoryItem, fileName: String) {
        val resolver = context.contentResolver
        if (item.type == ClipboardContentType.Image) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/*")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) throw IOException("Failed to insert into MediaStore")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Failed to open output stream")
        } else {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "*/*")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) throw IOException("Failed to insert into MediaStore")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Failed to open output stream")
        }
    }
}