package io.github.erenche.syncclipboard.app.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.api.ClientFactory
import io.github.erenche.syncclipboard.xposed.api.SyncClipboardApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 分享接收页 — 作为系统分享目标，把收到的文本/文件上传到服务器。
 *
 * 文本：走 UPLOAD_TEXT 桥接（引擎同时复制到剪贴板），Toast 提示后自动关闭。
 * 文件：复制到 App 私有 cacheDir 后**由 App 进程直接上传**（不再经 SystemUI，
 * 因此不受 SystemUI 重启/进程回收影响），进度在本地 StateFlow 实时驱动 UI；
 * 上传成功后通过 REGISTER_UPLOADED 通知引擎登记历史并更新远端缓存。
 *
 * 界面：MIUIX Card + LinearProgressIndicator，每文件独立进度条/速度/状态。
 */
class ShareActivity : BaseActivity() {

    /** 单个分享项的上传状态 */
    enum class ItemState { WAITING, UPLOADING, SUCCESS, FAILED }

    /** 单个分享文件的可变状态（由本地上传协程驱动） */
    data class ShareItem(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val isImage: Boolean,
        val state: ItemState = ItemState.WAITING,
        val progress: Float = 0f,
        val sent: Long = 0L,
        val total: Long = 0L,
        val speed: Long = 0L,
        val error: String? = null,
    )

    /** 分享页整体状态 */
    data class ShareUiState(
        val items: List<ShareItem> = emptyList(),
        /** 全部文件已出结果（成功+失败），延时自动关闭 */
        val finished: Boolean = false,
    )

    private data class SpeedSample(val bytes: Long, val time: Long)

    /** 距上次进度事件的采样点，用于估算实时速度 */
    private val speedSamples = mutableStateMapOf<String, SpeedSample>()

    private val _uiState = MutableStateFlow(ShareUiState())
    private val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    /** 等待结果期间的本地缓存文件（transferId → File）；失败立即清理，成功后交给过期兜底 */
    private val pendingFiles = mutableMapOf<String, File>()
    private var isFinishing = false

    // ─── 生命周期 ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShareScreen(uiState.collectAsState().value, onFinish = { finish() })
        }
        handleShare(intent)
    }

    override fun onDestroy() {
        cleanupPendingFiles()
        super.onDestroy()
    }

    // ─── 分享分发 ──────────────────────────────────────────────

    private fun handleShare(intent: Intent?) {
        // 主页"上传文件"入口：显式传入 content:// URI（已授权读取），不需要走系统分享 Intent
        val explicitUri = intent?.getStringExtra(EXTRA_FILE_URI)
        if (!explicitUri.isNullOrBlank()) {
            handleFileShare(listOf(Uri.parse(explicitUri)), intent.getStringExtra(EXTRA_FILE_MIME))
            return
        }
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val stream = parcelableStream(intent)
                if (stream != null) {
                    handleFileShare(listOf(stream), intent.type)
                } else {
                    handleTextShare(intent)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = parcelableStreamList(intent)
                if (streams.isNotEmpty()) {
                    handleFileShare(streams, intent.type)
                } else {
                    handleTextShare(intent)
                }
            }
            else -> {
                Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
                finishAfterDelay(600)
            }
        }
    }

    /** 文本分享：直接走 UPLOAD_TEXT（引擎会同时复制到剪贴板），提示后关闭 */
    private fun handleTextShare(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            finishAfterDelay(600)
            return
        }
        Toast.makeText(this, R.string.share_text_uploading, Toast.LENGTH_SHORT).show()
        SyncClipboardBridge.with(this)
            .to(PackageNames.SYSTEM_UI)
            .key(BridgeKeys.UPLOAD_TEXT)
            .payload(Bundle().apply { putString("text", text) })
            .send()
        finishAfterDelay(800)
    }

    /** 文件分享：复制到 cacheDir → App 进程直接上传（进度本地驱动，不依赖 SystemUI 存续） */
    private fun handleFileShare(uris: List<Uri>, intentType: String?) {
        lifecycleScope.launch {
            // 清理超过 1 小时的历史残留文件
            withContext(Dispatchers.IO) { cleanupStaleFiles() }

            data class Prepared(val localFile: File, val displayName: String, val isImage: Boolean)

            val prepared = mutableListOf<Prepared>()
            for (uri in uris) {
                val display = sanitizeFileName(queryDisplayName(uri) ?: "share_${System.currentTimeMillis()}")
                val localFile = withContext(Dispatchers.IO) { copyToCache(uri, display) }
                if (localFile != null) {
                    prepared.add(Prepared(localFile, display, isImageUri(uri, intentType)))
                } else {
                    Logger.warn(TAG, "ShareActivity: failed to copy $uri")
                }
            }

            if (prepared.isEmpty()) {
                Toast.makeText(this@ShareActivity, R.string.share_empty, Toast.LENGTH_SHORT).show()
                finishAfterDelay(800)
                return@launch
            }

            // 服务器配置（与引擎一致：以 app 侧 prefs 为准）
            val config = Prefs.loadConfig(this@ShareActivity)
            val server = config.servers.getOrNull(config.activeServerIndex)
            val api = server?.let { runCatching { ClientFactory.createClient(it) }.getOrNull() }
            if (api == null) {
                Toast.makeText(this@ShareActivity, R.string.share_no_server, Toast.LENGTH_SHORT).show()
                finishAfterDelay(1200)
                return@launch
            }

            val items = prepared.map { p ->
                ShareItem(
                    transferId = UUID.randomUUID().toString(),
                    fileName = p.displayName,
                    fileSize = p.localFile.length(),
                    isImage = p.isImage,
                )
            }
            _uiState.value = ShareUiState(items = items)
            prepared.zip(items).forEach { (p, item) ->
                pendingFiles[item.transferId] = p.localFile
            }
            // 逐文件串行上传，进度实时更新本地状态
            prepared.zip(items).forEach { (p, item) ->
                uploadFile(api, item, p.localFile)
            }
        }
    }

    /** 单个文件上传：App 进程直接调用服务器 API，成功/失败同步更新 UI */
    private suspend fun uploadFile(api: SyncClipboardApi, item: ShareItem, file: File) {
        updateItem(item.transferId) { it.copy(state = ItemState.UPLOADING, progress = 0f) }
        val content = ClipboardContent(
            type = if (item.isImage) ClipboardContentType.Image else ClipboardContentType.File,
            text = item.fileName,
            fileUri = file.absolutePath,
            fileName = item.fileName,
            fileSize = item.fileSize.takeIf { it > 0 },
            hasData = true,
            timestamp = System.currentTimeMillis()
        )
        var error: String? = null
        val ok = try {
            withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
                api.putContent(content) { sent, total ->
                    val speed = estimateSpeed(item.transferId, sent)
                    val progress = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
                    updateItem(item.transferId) {
                        it.copy(progress = progress, sent = sent, total = total, speed = speed)
                    }
                }
                true
            } == true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消（页面销毁）：继续传播，不当作上传失败
            throw e
        } catch (e: Exception) {
            // 网络异常等：标记失败而非让协程崩溃
            Logger.error(TAG, "uploadFile failed: ${e.message}", e)
            error = e.message
            false
        }
        if (!ok && error == null) error = getString(R.string.share_timeout)

        if (ok) {
            updateItem(item.transferId) { it.copy(state = ItemState.SUCCESS, progress = 1f) }
            notifyEngineRegistered(item, file)
        } else {
            updateItem(item.transferId) { it.copy(state = ItemState.FAILED, error = error) }
            // 失败文件立即清理（成功文件留给引擎登记 + 1 小时过期兜底）
            pendingFiles.remove(item.transferId)?.takeIf { it.exists() }?.delete()
        }
        maybeFinish()
    }

    /** 上传成功后通知引擎：登记历史 + 更新远端缓存（避免轮询误判重复下载） */
    private fun notifyEngineRegistered(item: ShareItem, file: File) {
        try {
            val providerUri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            grantUriPermission(PackageNames.SYSTEM_UI, providerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SyncClipboardBridge.with(this)
                .to(PackageNames.SYSTEM_UI)
                .key(BridgeKeys.REGISTER_UPLOADED)
                .payload(Bundle().apply {
                    putString("fileUri", providerUri.toString())
                    putString("fileName", item.fileName)
                    putBoolean("isImage", item.isImage)
                    putLong("fileSize", item.fileSize)
                })
                .send()
            Logger.info(TAG, "notifyEngineRegistered name=${item.fileName}, size=${item.fileSize}")
        } catch (e: Exception) {
            Logger.warn(TAG, "notifyEngineRegistered failed: ${e.message}")
        }
    }

    // ─── 状态更新 ──────────────────────────────────────────────

    private fun updateItem(transferId: String, transform: (ShareItem) -> ShareItem) {
        _uiState.update { s ->
            if (s.items.any { it.transferId == transferId }) {
                s.copy(items = s.items.map { if (it.transferId == transferId) transform(it) else it })
            } else {
                s
            }
        }
    }

    /** 估算上传速度（bytes/s）：用距上次进度事件的字节差/时间差 */
    private fun estimateSpeed(transferId: String, sent: Long): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = speedSamples[transferId]
        val speed = if (prev != null && sent > prev.bytes && now > prev.time) {
            ((sent - prev.bytes) * 1000L) / (now - prev.time)
        } else 0L
        speedSamples[transferId] = SpeedSample(sent, now)
        return speed
    }

    /** 所有文件都有结果后：标记完成并延时关闭 */
    private fun maybeFinish() {
        val s = _uiState.value
        if (s.items.isEmpty() || s.finished) return
        val allDone = s.items.all { it.state == ItemState.SUCCESS || it.state == ItemState.FAILED }
        if (allDone) {
            _uiState.update { it.copy(finished = true) }
            finishAfterDelay(1200)
        }
    }

    // ─── 工具方法 ──────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun parcelableStream(intent: Intent): Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun parcelableStreamList(intent: Intent): List<Uri> {
        val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return emptyList()
        return list.filterNotNull()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
    } catch (e: Exception) {
        Logger.warn(TAG, "queryDisplayName failed: ${e.message}")
        uri.lastPathSegment
    }

    /** 文件名清理：去路径分隔符与非法字符，限制长度 */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_")
            .trim()
            .take(120)
        return cleaned.ifBlank { "share_${System.currentTimeMillis()}" }
    }

    private fun copyToCache(uri: Uri, displayName: String): File? {
        return try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val dest = File(dir, "${System.currentTimeMillis()}_$displayName")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest
        } catch (e: Exception) {
            Logger.error(TAG, "copyToCache failed: ${e.message}", e)
            null
        }
    }

    private fun isImageUri(uri: Uri, intentType: String?): Boolean {
        if (intentType?.startsWith("image/") == true) return true
        return try {
            contentResolver.getType(uri)?.startsWith("image/") == true
        } catch (_: Exception) {
            false
        }
    }

    /** 清理 cacheDir/share 下超过 1 小时的残留文件（成功上传的文件也由这里兜底清理） */
    private fun cleanupStaleFiles() {
        try {
            val dir = File(cacheDir, "share")
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - 3_600_000L
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        } catch (_: Exception) {}
    }

    /** 页面销毁时清理尚未成功上传的缓存文件（成功文件保留给引擎登记，由过期清理兜底） */
    private fun cleanupPendingFiles() {
        val successIds = _uiState.value.items
            .filter { it.state == ItemState.SUCCESS }
            .map { it.transferId }
            .toSet()
        val files = pendingFiles.filterKeys { it !in successIds }.values.toList()
        pendingFiles.clear()
        if (files.isEmpty()) return
        Thread {
            files.forEach { runCatching { it.delete() } }
        }.start()
    }

    private fun finishAfterDelay(delayMs: Long = 1200) {
        if (isFinishing) return
        isFinishing = true
        lifecycleScope.launch {
            delay(delayMs)
            finish()
        }
    }

    companion object {
        const val TAG = "ShareActivity"
        /** 单个文件上传超时（毫秒） */
        const val UPLOAD_TIMEOUT_MS = 5 * 60_000L

        /** 主页"上传文件"入口：显式传入的待上传文件 URI（content://，调用方已授权读取） */
        const val EXTRA_FILE_URI = "extra_file_uri"

        /** 显式传入 URI 时的 MIME 类型（用于图片/文件判定，可为 null） */
        const val EXTRA_FILE_MIME = "extra_file_mime"
    }
}

/** 文件大小格式化：B / KB / MB / GB */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

// ─── 界面 ─────────────────────────────────────────────────────

@Composable
private fun ShareScreen(state: ShareActivity.ShareUiState, onFinish: () -> Unit) {
    val doneCount = state.items.count { it.state == ShareActivity.ItemState.SUCCESS }
    val failedCount = state.items.count { it.state == ShareActivity.ItemState.FAILED }
    val total = state.items.size

    AppToolBarListContainer(
        title = stringResource(R.string.share_title),
        canBack = true,
        onBack = onFinish,
        actions = {
            if (state.finished) {
                TextButton(
                    text = stringResource(R.string.share_finish),
                    onClick = onFinish,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    minHeight = 36.dp,
                    minWidth = 0.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    ) {
        if (state.items.isEmpty()) {
            item("empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.share_empty),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            item("summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.finished) {
                            stringResource(R.string.share_status_done, doneCount, failedCount)
                        } else {
                            stringResource(R.string.share_status_uploading, doneCount + failedCount, total)
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (state.finished) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
            state.items.forEach { shareItem ->
                item(shareItem.transferId) {
                    ShareItemCard(shareItem)
                }
            }
        }
        item("footer") { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/** 单个分享文件卡片：文件名 + 类型 + 大小/速度 + 实时进度条 + 状态 */
@Composable
private fun ShareItemCard(item: ShareActivity.ShareItem) {
    // 进度值事件间平滑过渡，避免分段跳变看着生硬
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = tween(150),
        label = "share-progress",
    )
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 第一行：类型标签 + 文件名 + 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(if (item.isImage) R.string.type_image else R.string.type_file),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = item.fileName,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = when (item.state) {
                        ShareActivity.ItemState.WAITING -> stringResource(R.string.share_waiting)
                        ShareActivity.ItemState.UPLOADING ->
                            stringResource(R.string.transfer_progress, (item.progress * 100).roundToInt())
                        ShareActivity.ItemState.SUCCESS -> stringResource(R.string.share_done)
                        ShareActivity.ItemState.FAILED -> stringResource(R.string.share_failed)
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = shareStatusColor(item.state),
                )
            }

            // 第二行：文件大小 + 实时速度
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatBytes(item.fileSize),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                if (item.state == ShareActivity.ItemState.UPLOADING && item.speed > 0L) {
                    Text(
                        text = "${formatBytes(item.speed)}/s",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }

            // 第三行：进度条 / 状态明细
            when (item.state) {
                ShareActivity.ItemState.WAITING -> Unit

                ShareActivity.ItemState.UPLOADING -> {
                    LinearProgressIndicator(
                        progress = if (animatedProgress > 0f) animatedProgress else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ShareActivity.ItemState.SUCCESS -> {
                    LinearProgressIndicator(
                        progress = if (animatedProgress > 0f) animatedProgress else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ShareActivity.ItemState.FAILED -> {
                    val err = item.error
                    if (!err.isNullOrBlank()) {
                        Text(
                            text = err,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun shareStatusColor(state: ShareActivity.ItemState): Color {
    return when (state) {
        ShareActivity.ItemState.SUCCESS -> MiuixTheme.colorScheme.primary
        ShareActivity.ItemState.FAILED -> MiuixTheme.colorScheme.error
        ShareActivity.ItemState.UPLOADING -> MiuixTheme.colorScheme.primary
        ShareActivity.ItemState.WAITING -> MiuixTheme.colorScheme.onBackgroundVariant
    }
}
