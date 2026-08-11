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
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.model.ProfileDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** 是否正在加载远程内容 */
    private val _isLoadingRemote = mutableStateOf(false)
    val isLoadingRemote: State<Boolean> = _isLoadingRemote

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
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
     *  用户下拉刷新调用：fire-and-forget 触发服务端强制拉取，转圈由
     *  EVENT_CLIPBOARD_CHANGED 广播（refreshRemoteContentCache）或超时停止。 */
    fun refreshRemoteContent() {
        _isLoadingRemote.value = true
        val payload = android.os.Bundle().apply { putBoolean("forceFetch", true) }
        SyncClipboardBridge.with(app)
            .to("com.android.systemui")
            .key(BridgeKeys.GET_CURRENT_CLIPBOARD)
            .payload(payload)
            .send()
        // 超时保护：8 秒后强制停止转圈（防止广播丢失导致永远转圈）
        viewModelScope.launch {
            kotlinx.coroutines.delay(8000)
            if (_isLoadingRemote.value) {
                _isLoadingRemote.value = false
            }
        }
    }

    /** 仅读取缓存更新 UI，不触发服务端拉取。
     *  供 EVENT_CLIPBOARD_CHANGED 广播调用：fetch 已完成，读取最新缓存并停止转圈。 */
    fun refreshRemoteContentCache() {
        viewModelScope.launch {
            try {
                val bundle = SyncClipboardBridge.with(app)
                    .to("com.android.systemui")
                    .key(BridgeKeys.GET_CURRENT_CLIPBOARD)
                    .await(timeout = 5000)
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

        val filePath = bundle.getString("filePath")
        _downloadedFile.value = filePath?.let { File(it) }?.takeIf { it.exists() }
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
