package io.github.erenche.syncclipboard.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.SyncClipboardApp
import io.github.erenche.syncclipboard.app.net.ServerApi
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.BridgeSecurity
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.model.ServerType
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncClipboardApp>()

    val isModuleActive = mutableStateOf(false)

    private val _syncStatus = mutableStateOf(R.string.sync_status_checking)
    val syncStatus: State<Int> = _syncStatus

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _isBusy = mutableStateOf(false)
    val isBusy: State<Boolean> = _isBusy

    /** 服务器最新 profile */
    private val _remoteProfile = mutableStateOf<ProfileDto?>(null)
    val remoteProfile: State<ProfileDto?> = _remoteProfile

    /** 下载到本地的图片文件路径 */
    private val _downloadedFile = mutableStateOf<File?>(null)
    val downloadedFile: State<File?> = _downloadedFile

    /** 已下载文件的 profile hash 缓存，避免重复下载同一内容 */
    @Volatile
    private var downloadedHash: String? = null

    /** 是否正在加载远程内容 */
    private val _isLoadingRemote = mutableStateOf(false)
    val isLoadingRemote: State<Boolean> = _isLoadingRemote

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (context != null && !BridgeSecurity.isTrustedSender(context)) return
            when (intent.action) {
                BridgeKeys.EVENT_ACTION_RESULT -> {
                    val action = intent.getStringExtra("action") ?: return
                    val success = intent.getBooleanExtra("success", false)
                    val message = intent.getStringExtra("message").orEmpty()
                    val label = when (action) {
                        "sync" -> "同步"
                        "upload" -> "上传"
                        else -> action
                    }
                    _toast.value = "$label${if (success) "成功" else "失败"}: $message"
                    refreshStatus()
                }
                BridgeKeys.EVENT_SYNC_STATE_CHANGED -> {
                    refreshStatus()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BridgeKeys.EVENT_ACTION_RESULT)
            addAction(BridgeKeys.EVENT_SYNC_STATE_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(resultReceiver, filter)
        }
        refreshStatus()
    }

    override fun onCleared() {
        try { app.unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        super.onCleared()
    }

    /** 清除已下载的预览文件状态（文件被外部删除后调用，如"清除缓存"） */
    fun clearDownloadedState() {
        _downloadedFile.value = null
        downloadedHash = null
    }

    fun refreshStatus() {
        viewModelScope.launch {
            try {
                val bundle = SyncClipboardBridge.with(app)
                    .to("com.android.systemui")
                    .key(BridgeKeys.GET_SYNC_STATUS)
                    .await()
                val running = bundle.getBoolean("running", false)
                val pollingActive = bundle.getBoolean("pollingActive", false)
                _syncStatus.value = when {
                    !running -> R.string.sync_status_stopped
                    pollingActive -> R.string.sync_status_running
                    else -> R.string.sync_status_stopped
                }
            } catch (e: Exception) {
                _syncStatus.value = R.string.sync_status_unavailable
            }
        }
    }

    /** 通过 IPC 从 SyncEngine 获取服务器最新内容（支持所有服务器类型）。
     *  用户下拉刷新调用：await 引擎即时回包（缓存）即停转圈；
     *  回包非空时立即应用（含引擎缓存的最新 profile，即使 fetch/广播链路异常也能刷新预览），
     *  fetch 完成后由 EVENT_CLIPBOARD_CHANGED 广播（refreshRemoteContentCache）再次刷新。 */
    fun refreshRemoteContent() {
        _isLoadingRemote.value = true
        val payload = android.os.Bundle().apply { putBoolean("forceFetch", true) }
        viewModelScope.launch {
            try {
                val bundle = SyncClipboardBridge.with(app)
                    .to("com.android.systemui")
                    .key(BridgeKeys.GET_CURRENT_CLIPBOARD)
                    .payload(payload)
                    .await(timeout = 5000)
                if (!bundle.isEmpty) {
                    applyRemoteBundle(bundle)
                }
            } catch (_: Exception) {
            } finally {
                _isLoadingRemote.value = false
            }
        }
        // 兜底超时保护：IPC 异常时 10 秒后强制停止转圈
        viewModelScope.launch {
            kotlinx.coroutines.delay(10000)
            _isLoadingRemote.value = false
        }
    }

    /** 仅读取缓存更新 UI，不触发服务端拉取。
     *  供 EVENT_CLIPBOARD_CHANGED 广播调用：fetch 已完成，读取最新缓存并停止转圈。
     *  超时/空回包时保留现有 UI（不清空），仅停止转圈。 */
    fun refreshRemoteContentCache() {
        viewModelScope.launch {
            try {
                val bundle = SyncClipboardBridge.with(app)
                    .to("com.android.systemui")
                    .key(BridgeKeys.GET_CURRENT_CLIPBOARD)
                    .await(timeout = 5000)
                if (bundle.isEmpty) {
                    Logger.warn("MainViewModel", "refreshRemoteContentCache: empty reply (timeout/engine not ready), keep current UI")
                    return@launch
                }
                applyRemoteBundle(bundle)
            } catch (_: Exception) {
            } finally {
                // fetch 完成，停止转圈
                _isLoadingRemote.value = false
            }
        }
    }

    private fun applyRemoteBundle(bundle: android.os.Bundle) {
        val profileJson = bundle.getString("profile")
        val profile = profileJson?.let {
            Json.decodeFromString(ProfileDto.serializer(), it)
        }
        _remoteProfile.value = profile

        // 文件类内容：app 自行下载到自己的 cacheDir（不依赖 SystemUI 私有目录）
        // 引擎返回的 filePath 仅作 fallback（SystemUI 进程的文件 app 读不了，通常为 null）
        val engineFilePath = bundle.getString("filePath")
        if (profile != null && profile.hasData && !profile.dataName.isNullOrBlank() &&
            (profile.type == ClipboardContentType.Image || profile.type == ClipboardContentType.File)) {
            val hash = profile.hash
            // hash 未变化且已有下载文件：复用缓存，不重复下载
            if (hash != null && hash == downloadedHash && _downloadedFile.value?.exists() == true) {
                return
            }
            // 先尝试引擎下载的文件（仅同 UID 场景可用，通常读不了）
            val engineFile = engineFilePath?.let { File(it) }?.takeIf { it.exists() }
            if (engineFile != null) {
                _downloadedFile.value = engineFile
                downloadedHash = hash
                return
            }
            // app 自行下载到 cacheDir
            viewModelScope.launch {
                val downloaded = withContext(Dispatchers.IO) {
                    downloadRemoteFile(profile)
                }
                if (downloaded != null) {
                    _downloadedFile.value = downloaded
                    downloadedHash = hash
                } else {
                    _downloadedFile.value = null
                    downloadedHash = null
                }
            }
        } else {
            _downloadedFile.value = null
            downloadedHash = null
        }
    }

    /** 通过 ServerApi 下载远程文件到 app cacheDir，按 hash 缓存文件名 */
    private fun downloadRemoteFile(profile: ProfileDto): File? {
        return try {
            val config = io.github.erenche.syncclipboard.common.Prefs.loadConfig(app)
            val server = config.servers.getOrNull(config.activeServerIndex)
            if (server == null) {
                Logger.warn("MainViewModel", "downloadRemoteFile: no active server (servers=${config.servers.size}, activeIdx=${config.activeServerIndex})")
                return null
            }
            Logger.info("MainViewModel", "downloadRemoteFile: type=${server.type}, url=${server.url}, dataName=${profile.dataName}")
            val safeName = (profile.dataName ?: "remote").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val hashPart = profile.hash?.take(16) ?: "nohash"
            val destFile = File(app.cacheDir, "remote_${hashPart}_$safeName")
            // 已存在且非空：复用缓存文件
            if (destFile.exists() && destFile.length() > 0) {
                Logger.info("MainViewModel", "Reusing cached remote file: ${destFile.name}")
                return destFile
            }
            val api = ServerApi(server)
            val result = when (server.type) {
                ServerType.s3 -> api.downloadFileS3(profile.dataName!!, destFile)
                else -> api.downloadFile(profile.dataName!!, destFile)
            }
            if (result != null) {
                Logger.info("MainViewModel", "Downloaded remote file: ${destFile.name}, size=${destFile.length()}")
            } else {
                Logger.warn("MainViewModel", "Failed to download remote file: ${profile.dataName}")
            }
            result
        } catch (e: Exception) {
            Logger.error("MainViewModel", "downloadRemoteFile error: ${e.message}", e)
            null
        }
    }

    fun triggerSync() {
        SyncClipboardBridge.with(app)
            .to("com.android.systemui")
            .key(BridgeKeys.TRIGGER_SYNC)
            .send()
        _toast.value = "正在同步..."
    }

    fun uploadNow() {
        SyncClipboardBridge.with(app)
            .to("com.android.systemui")
            .key(BridgeKeys.UPLOAD_NOW)
            .send()
        _toast.value = "正在上传..."
    }

    fun onToastShown() {
        _toast.value = null
    }
}
