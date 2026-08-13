package io.github.erenche.syncclipboard.xposed.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.DEFAULT_APP_CONFIG
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.HistoryRecordDto
import io.github.erenche.syncclipboard.common.model.HistorySyncStatus
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.model.ServerType
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.api.ClientFactory
import io.github.erenche.syncclipboard.xposed.api.SignalRClient
import io.github.erenche.syncclipboard.xposed.api.SyncClipboardApi
import io.github.erenche.syncclipboard.xposed.history.HistoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * SyncEngine — 同步引擎核心。
 *
 * 在 system_server 进程中运行，由 GeneralHooker 初始化。
 * 负责监听剪贴板变化（来自 ClipboardServiceHooker）、上传/下载、历史记录、IPC 路由。
 *
 * 去重策略：
 * - onLocalClipboardChanged 的哈希检查在调用线程同步执行，防止竞态
 * - system_server 中不注册 OnPrimaryClipChangedListener / 不轮询本地剪贴板
 *   仅依赖 ClipboardServiceHooker 提供的事件
 */
class SyncEngine private constructor() {

    companion object {
        private const val TAG = "SyncEngine"

        @Volatile
        private var instance: SyncEngine? = null

        fun getInstance(): SyncEngine = instance ?: synchronized(this) {
            instance ?: SyncEngine().also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var config: AppConfig = DEFAULT_APP_CONFIG
    private var apiClient: SyncClipboardApi? = null
    private var appContext: Context? = null
    private var historyService: HistoryService? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** SignalR 推送客户端（仅官方服务器模式创建） */
    @Volatile
    private var signalRClient: SignalRClient? = null
    /** SignalR 是否已连接（连接时轮询降级为 60s 兜底） */
    @Volatile
    var isSignalRConnected: Boolean = false
        private set
    /** 历史推送事件通道：串行化处理，避免批量推送时协程堆积 */
    private val historyPushChannel = Channel<HistoryRecordDto>(Channel.UNLIMITED)
    /** 历史推送通道消费者协程 */
    private var historyPushConsumer: Job? = null

    private var processName: String = "unknown"

    /** 本地哈希去重 — 同步检查防止竞态 */
    @Volatile
    private var lastLocalHash: String? = null
    @Volatile
    private var lastRemoteHash: String? = null

    /** 最近一次从服务器拉取（或上传）的 ProfileDto — 供 app 端通过 IPC 查询 */
    @Volatile
    private var lastRemoteProfile: ProfileDto? = null
    /** 最近一次下载到本地的文件路径 — 供 app 端预览 */
    @Volatile
    private var lastRemoteFilePath: String? = null

    @Volatile
    private var isRunning = false

    /** 正在从服务器拉取中（防止轮询与 IPC 触发的 force fetch 并发） */
    @Volatile
    private var isFetching = false

    /** 手动刷新请求排队标志：轮询 fetch 进行中收到 force 时置位，当前拉取结束后补跑 */
    @Volatile
    private var pendingForceFetch = false

    @Volatile
    var isConnected: Boolean = false
        private set

    /** 轮询是否处于活动状态（未因省电/熄屏/不可达而停止） */
    @Volatile
    var isPollingActive: Boolean = false
        private set

    // ─── 息屏/省电/移动网络断开（SyncClipboard 模式断开 SignalR，WebDAV/S3 模式停止轮询）───
    private var powerStateReceiver: BroadcastReceiver? = null
    private var screenOffDisconnectJob: Job? = null
    /** 息屏延迟断开已触发 */
    @Volatile
    private var screenOffPaused = false
    /** 省电模式断开已触发 */
    @Volatile
    private var batterySaverPaused = false
    /** 移动网络下断开已触发（WiFi 不可用且配置了 disconnectOnMobileData） */
    @Volatile
    private var mobileDataPaused = false
    /** 是否已应用暂停（断开 SignalR + 暂停轮询） */
    @Volatile
    private var powerPauseApplied = false
    /** 网络状态监听回调 */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    @Volatile
    var lastSyncTime: Long = 0
        private set

    /** 历史同步互斥锁，防止并发 syncHistory 调用 */
    private val historySyncMutex = Mutex()

    /** 每次同步最多 PATCH 的记录数。
     *  PATCH 只传元数据（starred/pinned/isDelete/version），无文件上传，单条很轻量；
     *  放宽到 100 以保证批量改动（如 clearAll、批量置顶）能在少数几次同步内推完。 */
    private val MAX_PATCH_PER_SYNC = 100

    /** 连续失败次数。达到 [maxConsecutiveFailures] 后停止轮询，等待手动同步恢复。
     *  失败时按 [retryBackoffMs] 退避重试。 */
    @Volatile
    private var consecutiveFailures: Int = 0

    /** 失败重试退避间隔（毫秒）：30s, 60s, 2min, 5min */
    private val retryBackoffMs = longArrayOf(
        30_000L,
        60_000L,
        120_000L,
        300_000L
    )

    /** 最大连续失败次数 = 原始失败 + 4 次退避重试全失败 */
    private val maxConsecutiveFailures: Int = retryBackoffMs.size + 1

    /** 上传重试队列单条最大重试次数，超限丢弃 */
    private val MAX_UPLOAD_RETRY = 5

    fun initialize(context: Context) {
        if (appContext != null) {
            Logger.info(TAG, "Already initialized, skipping")
            return
        }
        appContext = context.applicationContext
        processName = getProcessName(context)
        Logger.info(TAG, "initialize() process=$processName")

        historyService = HistoryService(context)
        config = Prefs.loadConfig(context)
        // 加载持久化的历史同步游标（增量同步用）
        lastSyncTime = Prefs.loadHistoryLastSyncTime(context)
        // 加载持久化的远程内容状态（SystemUI 重启后避免重复下载/重复历史）
        lastRemoteHash = Prefs.loadLastRemoteHash(context)
        lastRemoteFilePath = Prefs.loadLastRemoteFilePath(context)
        Logger.enabled = config.enableLogging
        Logger.logLevel = config.logLevel
        Logger.maxBufferSize = config.logBufferSize
        rebuildApiClient()
        setupBridgeRouting(context)
        registerPowerStateMonitor()
        start()

        Logger.info(TAG, "SyncEngine initialized, servers=${config.servers.size}, activeIdx=${config.activeServerIndex}")
    }

    private fun getProcessName(context: Context): String {
        return try {
            val pid = android.os.Process.myPid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        // 唯一剪贴板变化源：OnPrimaryClipChangedListener（系统级，捕获全局变化）
        // 不使用 ClipboardHooker / 本地轮询，避免多路径竞态导致重复上传
        registerClipListener()

        // 注册网络监听（移动网络下断开 SignalR / 停止轮询）
        registerNetworkMonitor()

        // 启动 SignalR 推送连接（仅官方服务器模式生效）
        signalRClient?.start()

        // 远程轮询 — 定期从服务器拉取新内容
        scope.launch {
            while (isActive && isRunning) {
                val shouldPoll = config.enableAutoSync &&
                        !powerPauseApplied &&
                        !isPowerSaveModeBlocked() &&
                        !isScreenOffBlocked() &&
                        consecutiveFailures < maxConsecutiveFailures
                if (shouldPoll) {
                    // SyncClipboard 官方服务器模式 + SignalR 推送开启时，完全依赖推送，不轮询 fetch。
                    // 与 syncclipboard-mobile 行为一致：SignalR 负责实时同步，断连期间不同步，
                    // 重连成功后由 onConnectionStateChanged 触发补拉。
                    val skipFetch = isSignalRDisconnectEnabled()
                    if (skipFetch) {
                        // isPollingActive 跟随 SignalR 连接状态，让 UI 反映断连
                        val expectedActive = isSignalRConnected
                        if (isPollingActive != expectedActive) {
                            isPollingActive = expectedActive
                            Logger.info(TAG, "Polling active = $expectedActive (SignalR mode, no fetch)")
                            notifySyncStateChanged()
                        }
                        consecutiveFailures = 0
                    } else {
                        val success = try {
                            fetchRemoteClipboard()
                        } catch (e: Exception) {
                            Logger.error(TAG, "Remote fetch error", e)
                            false
                        }
                    if (success) {
                        consecutiveFailures = 0
                        if (!isPollingActive) {
                            isPollingActive = true
                            Logger.info(TAG, "Polling active")
                            notifySyncStateChanged()
                        }
                        // 网络可达：顺带重放上传失败队列
                        appContext?.let { flushUploadQueue(it) }
                            // 历史增量同步：每轮轮询后尝试增量拉取（tryLock 跳过并发）
                            // 与 Win 端一致：后台定期增量同步，仅拉 modifiedAfter 之后的记录
                            // 独立协程执行，避免首次全量同步（游标为 0 时）阻塞剪贴板轮询
                            // 仅 SyncClipboard 官方服务器模式生效；WebDAV/S3 不支持历史 API
                            val server = config.servers.getOrNull(config.activeServerIndex)
                            if (config.enableHistorySync && server?.type == ServerType.syncclipboard) {
                                scope.launch {
                                    try {
                                        syncHistory(force = false)
                                    } catch (e: Exception) {
                                        Logger.warn(TAG, "Polling history sync error: ${e.message}")
                                    }
                                }
                            }
                        } else {
                            consecutiveFailures++
                            // 失败即停止活动标记：让状态即时反映真实连通性，
                            // 退避重试期间保持 false，成功时再恢复
                            if (isPollingActive) {
                                isPollingActive = false
                                Logger.warn(TAG, "Polling paused: $consecutiveFailures consecutive failures")
                                notifySyncStateChanged()
                            } else if (consecutiveFailures <= retryBackoffMs.size) {
                                Logger.info(TAG, "Remote fetch failed ($consecutiveFailures/${retryBackoffMs.size+1}), retry in ${retryBackoffMs[consecutiveFailures-1]}ms")
                            }
                        }
                    }
                    // 历史同步：轮询中自动增量同步（独立协程），手动刷新走 FORCE_SYNC_HISTORY
                } else {
                    if (isPollingActive) {
                        isPollingActive = false
                        notifySyncStateChanged()
                    }
                    if (!config.enableAutoSync) {
                        setConnected(false)
                    }
                }
                // 失败时按退避间隔重试；SignalR 模式 60s 状态检查；正常时使用配置间隔
                val delayMs = when {
                    consecutiveFailures in 1..retryBackoffMs.size ->
                        retryBackoffMs[consecutiveFailures - 1]
                    isSignalRDisconnectEnabled() ->
                        SignalRClient.PUSH_ACTIVE_POLLING_MS
                    else -> pollingIntervalMs()
                }
                delay(delayMs)
            }
        }

        Logger.info(TAG, "SyncEngine started, process=$processName")
    }

    /** 轮询间隔（毫秒）。
     *  SignalR 推送连接成功时，轮询 fetch 被跳过（由 start() 循环判断），
     *  但保留 60s 状态检查循环；断开时恢复用户配置的间隔做正常 fetch。 */
    private fun pollingIntervalMs(): Long {
        val sec = config.pollingIntervalSec
        return if (sec > 0) sec * 1000L else config.remotePollingInterval
    }

    /** 省电模式且配置了省电停止 */
    private fun isPowerSaveModeBlocked(): Boolean {
        if (!config.stopPollingOnBatterySaver) return false
        val ctx = appContext ?: return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isPowerSaveMode == true
    }

    /** 熄屏且配置了熄屏停止 */
    private fun isScreenOffBlocked(): Boolean {
        if (!config.stopPollingOnScreenOff) return false
        val ctx = appContext ?: return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isInteractive == false
    }

    // ─── 息屏/省电断开 SignalR（仅 SyncClipboard 官方服务器模式）───

    /** SignalR 断开功能是否可用：官方服务器模式且开启 SignalR 推送 */
    private fun isSignalRDisconnectEnabled(): Boolean {
        val server = config.servers.getOrNull(config.activeServerIndex)
        return server?.type == ServerType.syncclipboard && config.enableSignalRPush
    }

    /** 注册屏幕开关与省电模式广播监听 */
    private fun registerPowerStateMonitor() {
        try {
            val ctx = appContext ?: return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> onScreenOff()
                        Intent.ACTION_SCREEN_ON -> onScreenOn()
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> onPowerSaveModeChanged()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            ctx.registerReceiver(receiver, filter)
            powerStateReceiver = receiver
            Logger.info(TAG, "Power state monitor registered")
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to register power state monitor: ${e.message}")
        }
    }

    /** 息屏：延迟 [AppConfig.screenOffDisconnectDelaySec] 秒后仍息屏则断开 SignalR */
    private fun onScreenOff() {
        val delaySec = config.screenOffDisconnectDelaySec
        if (delaySec <= 0 || !isSignalRDisconnectEnabled()) return
        if (screenOffDisconnectJob?.isActive == true) return
        Logger.info(TAG, "Screen off, disconnecting SignalR in ${delaySec}s")
        screenOffDisconnectJob = scope.launch {
            delay(delaySec * 1000L)
            val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive == false) {
                Logger.info(TAG, "Screen still off after ${delaySec}s, pausing SignalR")
                screenOffPaused = true
                applyPowerPause()
            }
        }
    }

    /** 亮屏：取消延迟断开任务，恢复 SignalR 并补拉一次 */
    private fun onScreenOn() {
        screenOffDisconnectJob?.cancel()
        screenOffDisconnectJob = null
        if (screenOffPaused) {
            Logger.info(TAG, "Screen on, resuming SignalR")
            screenOffPaused = false
            applyPowerPause()
        }
    }

    /** 省电模式开关：开启且配置断开时暂停，关闭时恢复 */
    private fun onPowerSaveModeChanged() {
        if (!isSignalRDisconnectEnabled() && !batterySaverPaused) return
        val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isPowerSaveMode) {
            if (config.stopPollingOnBatterySaver && !batterySaverPaused) {
                Logger.info(TAG, "Battery saver on, pausing SignalR")
                batterySaverPaused = true
                applyPowerPause()
            }
        } else if (batterySaverPaused) {
            Logger.info(TAG, "Battery saver off, resuming SignalR")
            batterySaverPaused = false
            applyPowerPause()
        }
    }

    /** 根据暂停标志应用/解除暂停：断开或重连 SignalR，暂停/恢复轮询。
     *  适用于息屏/省电/移动网络三种触发条件。 */
    private fun applyPowerPause() {
        val paused = screenOffPaused || batterySaverPaused || mobileDataPaused
        if (paused && !powerPauseApplied) {
            powerPauseApplied = true
            signalRClient?.stop()
            if (isPollingActive) {
                isPollingActive = false
                notifySyncStateChanged()
            }
            Logger.info(TAG, "Paused (screenOff=$screenOffPaused, battery=$batterySaverPaused, mobile=$mobileDataPaused)")
        } else if (!paused && powerPauseApplied) {
            powerPauseApplied = false
            if (isRunning) {
                signalRClient?.start()
                // 补拉暂停期间错过的内容
                scope.launch {
                    try {
                        Logger.info(TAG, "Catch-up fetch after resume")
                        fetchRemoteClipboard(force = true)
                    } catch (e: Exception) {
                        Logger.warn(TAG, "Catch-up fetch after resume failed: ${e.message}")
                    }
                }
            }
            Logger.info(TAG, "Resumed")
        }
    }

    // ─── 移动网络监听 ───────────────────────────────────────────

    /** 注册默认网络监听，WiFi 不可用且配置开启时触发暂停 */
    private fun registerNetworkMonitor() {
        try {
            val ctx = appContext ?: return
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    handleNetworkChange(cm)
                }
                override fun onLost(network: android.net.Network) {
                    handleNetworkChange(cm)
                }
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    handleNetworkChange(cm)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            handleNetworkChange(cm) // 初始化当前状态
            Logger.info(TAG, "Network monitor registered")
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to register network monitor: ${e.message}")
        }
    }

    /** 判断当前活动网络是否为 WiFi，更新移动网络暂停状态 */
    private fun handleNetworkChange(cm: android.net.ConnectivityManager) {
        val isWifi = cm.activeNetwork?.let { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: false
        val shouldPause = config.disconnectOnMobileData && !isWifi
        if (shouldPause != mobileDataPaused) {
            mobileDataPaused = shouldPause
            Logger.info(TAG, "Network changed: isWifi=$isWifi, mobileDataPaused=$mobileDataPaused")
            applyPowerPause()
        }
    }

    /** 配置变更后重新评估移动网络暂停状态 */
    private fun reevaluateMobileDataState() {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        handleNetworkChange(cm)
    }

    private fun registerClipListener() {
        try {
            val context = appContext ?: return
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
            cm.addPrimaryClipChangedListener(clipChangedListener)
            Logger.info(TAG, "OnPrimaryClipChangedListener registered")
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to register clip listener: ${e.message}")
        }
    }

    private val clipChangedListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        val ctx = appContext ?: return@OnPrimaryClipChangedListener
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return@OnPrimaryClipChangedListener
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        val content = extractFromClip(ctx, clip) ?: return@OnPrimaryClipChangedListener
        onLocalClipboardChanged(content)
    }

    fun stop() {
        isRunning = false
        isConnected = false
        isPollingActive = false
        // 停止息屏/省电监听与延迟任务
        screenOffDisconnectJob?.cancel()
        screenOffDisconnectJob = null
        powerStateReceiver?.let { receiver ->
            runCatching { appContext?.unregisterReceiver(receiver) }
            powerStateReceiver = null
        }
        // 停止网络监听
        networkCallback?.let { cb ->
            runCatching {
                val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }
            networkCallback = null
        }
        // 停止 SignalR 连接
        signalRClient?.stop()
        try {
            val cm = appContext?.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cm?.removePrimaryClipChangedListener(clipChangedListener)
        } catch (_: Exception) {}
        Logger.info(TAG, "SyncEngine stopped")
    }

    /**
     * 从 ClipData 提取统一内容 — 优先处理图片/文件（URI），再处理文本。
     *
     * 关键：必须优先检查 item.uri，因为图片剪贴板中 item.text 可能返回文件名而非 null，
     * 导致图片被误当作文本处理。
     */
    private fun extractFromClip(
        context: Context,
        clip: android.content.ClipData
    ): ClipboardContent? {
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)

        // 优先处理 URI（图片/文件）
        val uri = item.uri
        if (uri != null) {
            val isImage = isImageUri(context, clip.description, uri)
            val type = if (isImage) ClipboardContentType.Image else ClipboardContentType.File
            val fileName = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
            val fileSize = queryFileSize(context, uri)

            Logger.debug(TAG, "Extracted URI content: type=$type, name=$fileName, size=$fileSize")

            return ClipboardContent(
                type = type,
                text = "",
                fileUri = uri.toString(),
                fileName = fileName,
                fileSize = fileSize,
                hasData = true,
                timestamp = System.currentTimeMillis()
            )
        }

        // 文本类型
        val text = item.text?.toString()
            ?: item.htmlText?.toString()
            ?: return null

        return ClipboardContent(
            type = ClipboardContentType.Text,
            text = text,
            hasData = false,
            timestamp = System.currentTimeMillis()
        )
    }

    /** 判断 URI 是否为图片 */
    private fun isImageUri(
        context: Context,
        desc: android.content.ClipDescription,
        uri: android.net.Uri
    ): Boolean {
        // 1. 通过 ClipDescription 的 MIME 类型判断
        if (desc.hasMimeType("image/*")) return true
        // 2. 通过 ContentResolver 查询实际 MIME 类型
        try {
            val mime = context.contentResolver.getType(uri)
            if (mime != null && mime.startsWith("image/")) return true
        } catch (_: Exception) {}
        // 3. 通过文件扩展名判断
        val path = uri.lastPathSegment ?: return false
        val lower = path.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    /** 查询 URI 指向文件的大小（字节） */
    private fun queryFileSize(context: Context, uri: android.net.Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (_: Exception) { null }
    }

    /**
     * 当检测到本地剪贴板变化时由 ClipboardServiceHooker / ClipboardHooker / Listener 调用。
     *
     * 哈希去重在调用线程同步执行，防止多个调用者竞态导致重复上传。
     *
     * @param force 是否强制处理（跳过去重），用于手动上传
     */
    fun onLocalClipboardChanged(content: ClipboardContent, force: Boolean = false) {
        // 仅在已初始化的进程（SystemUI）中处理，App 进程的未初始化实例直接跳过
        if (appContext == null) return

        // 文本用内容 hash 去重；图片/文件用 fileUri 去重（text 为空，无法区分不同文件）
        val hash = content.profileHash
            ?: if (content.hasData && content.fileUri != null) HashUtils.sha256(content.fileUri!!)
            else HashUtils.sha256(content.text)

        if (!force && hash.equals(lastLocalHash, ignoreCase = true)) return
        lastLocalHash = hash

        scope.launch {
            try {
                Logger.debug(TAG, "Local clipboard changed: ${content.text.take(50)}...")
                historyService?.addLocalContent(content)
                notifyContentChanged()
                if (config.enableAutoSync && config.enableBackgroundUpload) {
                    val uploaded = uploadContent(content)
                    if (!uploaded) {
                        // 上传失败：入队重试队列，网络恢复后自动重放
                        val ctx = appContext
                        if (ctx != null) {
                            UploadQueue.enqueue(ctx, content)
                            flushUploadQueue(ctx)
                        }
                    }
                    // 历史同步改为手动触发，上传后不再自动 syncHistory
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Error handling local clipboard change", e)
            }
        }
    }

    fun onConfigChanged(newConfig: AppConfig) {
        // 幂等去重：app 启动/页面进入会多次推送相同配置，直接跳过重建，
        // 避免 SignalR 客户端被反复 dispose/重建导致连接竞态与重复日志
        if (newConfig == config) {
            Logger.enabled = newConfig.enableLogging
            Logger.logLevel = newConfig.logLevel
            Logger.maxBufferSize = newConfig.logBufferSize
            Logger.debug(TAG, "Config unchanged, skipping client rebuild")
            return
        }
        val oldServers = config.servers
        val oldActiveIdx = config.activeServerIndex
        config = newConfig
        rebuildApiClient()  // 内部会重建 SignalR 客户端
        // 配置变更后重启 SignalR 连接（若已创建且未被息屏/省电暂停）
        if (isRunning && !powerPauseApplied) {
            signalRClient?.start()
        }
        // 同步日志开关到 Logger
        Logger.enabled = newConfig.enableLogging
        Logger.logLevel = newConfig.logLevel
        Logger.maxBufferSize = newConfig.logBufferSize
        // 切换服务器时重置历史同步游标，触发全量同步
        val serverChanged = oldServers != newConfig.servers || oldActiveIdx != newConfig.activeServerIndex
        if (serverChanged) {
            lastSyncTime = 0L
            appContext?.let { Prefs.resetHistoryLastSyncTime(it) }
            Logger.info(TAG, "Server changed, history sync cursor reset")
        }
        // 总开关关闭时立即置为未连接并停止轮询
        if (!newConfig.enableAutoSync) {
            setConnected(false)
            if (isPollingActive) {
                isPollingActive = false
                notifySyncStateChanged()
            }
        }
        // 配置变更后重新评估移动网络暂停状态（用户可能刚开启/关闭了移动网络断开）
        reevaluateMobileDataState()
        Logger.info(TAG, "Config changed, client rebuilt, logging=${newConfig.enableLogging}, autoSync=${newConfig.enableAutoSync}, pollingInterval=${newConfig.pollingIntervalSec}s, signalRPush=${newConfig.enableSignalRPush}")
    }

    fun forceSync() {
        scope.launch {
            var success = false
            var message: String? = null
            try {
                success = fetchRemoteClipboard(force = true)
                // 历史同步改为手动触发（FORCE_SYNC_HISTORY），不在 forceSync 中执行
                if (success) {
                    // 恢复轮询
                    consecutiveFailures = 0
                    if (!isPollingActive && config.enableAutoSync) {
                        isPollingActive = true
                        notifySyncStateChanged()
                    }
                    message = "Sync OK"
                } else {
                    message = "Sync failed"
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Force sync failed", e)
                message = "Sync error: ${e.message}"
            }
            notifyActionResult("sync", success, message)
        }
    }

    /**
     * 从服务器获取历史，合并到本地；再将本地变更推送到服务器。
     * 仅在 [AppConfig.enableHistorySync] 开启时调用。
     * 使用原项目 /api/history API（仅 SyncClipboard HTTP 服务器支持）。
     *
     * 流程（完整对齐 RN HistorySyncService.executeSync）：
     * 0. 服务端时间校验（与 RN 一致，总是执行，仅警告不阻止）
     * 1. 分页拉取服务器记录（全量或增量）
     * 2. 合并到本地（版本冲突解决）
     * 3. 全量同步时检测孤儿记录
     * 4. 推送本地 NeedSync 记录（PATCH）—— 单条失败不中断整体
     * 5. 上传 LocalOnly 记录（POST）—— 单条失败不中断整体
     * 6. 保存 lastSyncTime 游标
     *
     * @param force true 时（用户手动触发）：等待已有同步完成（不跳过），但走增量同步；
     *              false 时（轮询触发）：已有同步进行中则直接跳过（tryLock）。
     * @param fullSync true 时强制全量同步（重置游标）；仅在首次启动/配置切换等场景使用。
     */
    private suspend fun syncHistory(force: Boolean = false, fullSync: Boolean = false): SyncHistoryResult {
        val client = apiClient ?: return SyncHistoryResult(false, 0, "API client is null")
        val hs = historyService ?: return SyncHistoryResult(false, 0, "History service is null")
        // 互斥锁：防止轮询、FORCE_SYNC_HISTORY、剪贴板变化触发并发 syncHistory
        if (force || fullSync) {
            // 手动触发/全量：等待正在进行的同步完成（最多 15s，防止轮询同步卡住时
            // 手动同步无限等待，导致 app 端刷新指示器一直转圈）
            val locked = withTimeoutOrNull(15_000L) { historySyncMutex.lock() } != null
            if (!locked) {
                Logger.warn(TAG, "syncHistory: timed out waiting for in-progress sync")
                return SyncHistoryResult(false, 0, "History sync timeout")
            }
            if (fullSync) {
                // 全量同步：锁内重置游标
                lastSyncTime = 0L
                appContext?.let { Prefs.resetHistoryLastSyncTime(it) }
                Logger.info(TAG, "syncHistory: full sync requested, cursor reset")
            }
        } else {
            if (!historySyncMutex.tryLock()) {
                Logger.debug(TAG, "syncHistory: skipped (another sync in progress)")
                return SyncHistoryResult(true, 0, null)
            }
        }
        try {
            // 批量模式：syncHistory 期间所有 saveToDisk 只标记脏，结束时统一落盘
            hs.beginBatch()
            // 判断全量 or 增量：fullSync 参数或 lastSyncTime == 0 表示全量
            val isFullSync = fullSync || lastSyncTime == 0L
            val modifiedAfter: String? = if (isFullSync) null else {
                java.time.Instant.ofEpochMilli(lastSyncTime).toString()
            }
            Logger.info(TAG, "syncHistory: ${if (isFullSync) "full" else "incremental"} sync, modifiedAfter=$modifiedAfter")

            // 0. 服务端时间校验（与 RN validateServerTime 一致，总是执行，仅警告不阻止）
            try {
                val serverTime = client.getServerTime()
                if (serverTime != null) {
                    val localTime = System.currentTimeMillis()
                    val diffMs = Math.abs(localTime - serverTime)
                    val diffMin = diffMs / 60000
                    if (diffMin > 5) {
                        Logger.warn(TAG, "syncHistory: server time diff = ${diffMin}min (local=$localTime, server=$serverTime)")
                    }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "syncHistory: getServerTime failed (non-fatal): ${e.message}")
            }

            // 1. 分页拉取服务器记录（与 RN fetchRemoteRecords 一致）
            val allRecords = mutableListOf<io.github.erenche.syncclipboard.common.model.HistoryRecordDto>()
            val seenHashes = mutableSetOf<String>()
            var page = 1
            val maxPages = 1000 // 安全上限
            while (page <= maxPages) {
                val batch = client.queryHistoryRecords(page, modifiedAfter, 15)
                Logger.info(TAG, "syncHistory: page=$page, batch size=${batch.size}")
                if (batch.isEmpty()) break
                // 按 hash 去重（同一 hash 可能跨页出现，保留首次出现版本）
                batch.forEach { dto ->
                    val key = dto.hash.lowercase()
                    if (seenHashes.add(key)) {
                        allRecords.add(dto)
                    }
                }
                page++
            }
            Logger.info(TAG, "syncHistory: fetched ${allRecords.size} records from server (pages=${page - 1})")

            // 2. 合并到本地（版本冲突解决）—— 与 RN mergeRemoteRecords 一致
            if (allRecords.isNotEmpty()) {
                hs.mergeFromServerDtos(allRecords)
                Logger.info(TAG, "syncHistory: after merge, local total=${hs.getAll().size}")
            }

            // 3. 全量同步时检测孤儿记录（与 RN detectOrphanData 一致）
            // 安全保护：仅当服务器确实返回了记录时才检测孤儿
            if (isFullSync && allRecords.isNotEmpty()) {
                val serverHashes = allRecords.map { it.hash.lowercase() }.toSet()
                val orphans = hs.detectOrphanRecords(serverHashes)
                if (orphans > 0) {
                    Logger.info(TAG, "syncHistory: detected $orphans orphan records")
                }
            } else if (isFullSync && allRecords.isEmpty()) {
                Logger.warn(TAG, "syncHistory: full sync returned 0 records, skipping orphan detection to protect local data")
            }

            // 4. 推送 NeedSync 记录的元数据变更到服务器（PATCH）—— 并发化
            // 与 RN pushLocalChanges 一致，单条失败用 try-catch 包裹不中断整体
            val needSyncItems = hs.getNeedSyncItems().take(MAX_PATCH_PER_SYNC)
            val patchSemaphore = Semaphore(5) // 限制并发度，避免压垮服务器
            val patchResults = coroutineScope {
                needSyncItems.map { item ->
                    async(Dispatchers.IO) {
                        patchSemaphore.withPermit {
                            processPatchItem(item, client, hs)
                        }
                    }
                }.awaitAll()
            }
            val patchSuccess = patchResults.count { it == PatchOutcome.SUCCESS }
            val patchConflict = patchResults.count { it == PatchOutcome.CONFLICT }
            val patchNotFound = patchResults.count { it == PatchOutcome.NOT_FOUND }
            val patchFailed = patchResults.count { it == PatchOutcome.FAILED }
            Logger.info(TAG, "syncHistory: PATCH needSync=${needSyncItems.size}, success=$patchSuccess, conflict=$patchConflict, notFound=$patchNotFound, failed=$patchFailed")

            // 5. 上传 LocalOnly 记录（POST）——仅无数据文件的小记录，并发化
            // 与 RN pushLocalOnlyRecords 一致，单条失败用 try-catch 包裹不中断整体
            val localOnlyItems = hs.getUnsyncedRecords().filter { !it.first.hasData }
            val postSemaphore = Semaphore(5)
            val postResults = coroutineScope {
                localOnlyItems.map { (item, _) ->
                    async(Dispatchers.IO) {
                        postSemaphore.withPermit {
                            processPostItem(item, client, hs)
                        }
                    }
                }.awaitAll()
            }
            val uploadSuccess = postResults.count { it == PostOutcome.SUCCESS }
            val uploadConflict = postResults.count { it == PostOutcome.CONFLICT }
            val uploadFailed = postResults.count { it == PostOutcome.FAILED }
            Logger.info(TAG, "syncHistory: POST localOnly=${localOnlyItems.size}, success=$uploadSuccess, conflict=$uploadConflict, failed=$uploadFailed")

            // 6. 保存 lastSyncTime 游标（与 RN 一致，在整个同步流程完成后保存）
            lastSyncTime = System.currentTimeMillis()
            appContext?.let { Prefs.saveHistoryLastSyncTime(it, lastSyncTime) }

            // 仅统计活跃记录：服务器返回值含软删除墓碑（isDeleted==true），
            // 这些记录不会加入本地列表，若计入会导致"服务器数量"远大于实际可见数量
            val activeFetched = allRecords.count { it.isDeleted != true }
            return SyncHistoryResult(true, activeFetched, null)
        } catch (e: Exception) {
            Logger.warn(TAG, "syncHistory failed: ${e.message}", e)
            return SyncHistoryResult(false, 0, e.message ?: "Unknown error")
        } finally {
            // 统一落盘一次（批量模式期间的所有变更）
            hs.endBatch()
            historySyncMutex.unlock()
        }
    }

    /** PATCH 单条处理结果 */
    private enum class PatchOutcome { SUCCESS, CONFLICT, NOT_FOUND, FAILED }

    /** 处理单条 NeedSync 记录的 PATCH（含 409 冲突重试一次） */
    private suspend fun processPatchItem(
        item: HistoryItem,
        client: SyncClipboardApi,
        hs: HistoryService
    ): PatchOutcome {
        return try {
            var update = io.github.erenche.syncclipboard.common.model.HistoryRecordUpdateDto(
                starred = item.starred,
                pinned = item.pinned,
                isDelete = item.isDeleted,
                version = item.version,
                lastModified = java.time.Instant.ofEpochMilli(item.lastModified).toString()
            )
            var result = client.updateHistoryRecord(item.type, item.profileHash, update)
            if (result == null) {
                // 404：服务器不存在，降级为 LocalOnly（与 RN RecordNotFoundError 一致）
                hs.markAsLocalOnly(item.profileHash)
                PatchOutcome.NOT_FOUND
            } else if (result.version != item.version) {
                // 409 冲突：服务器 version 较新
                // 仅更新本地 version，保持 NeedSync 和 isDeleted，然后重试一次
                hs.updateVersionOnly(item.profileHash, result)
                val retryItem = hs.getItemByProfileHash(item.profileHash)
                if (retryItem != null) {
                    update = io.github.erenche.syncclipboard.common.model.HistoryRecordUpdateDto(
                        starred = retryItem.starred,
                        pinned = retryItem.pinned,
                        isDelete = retryItem.isDeleted,
                        version = retryItem.version,
                        lastModified = java.time.Instant.ofEpochMilli(retryItem.lastModified).toString()
                    )
                    val retryResult = client.updateHistoryRecord(retryItem.type, retryItem.profileHash, update)
                    if (retryResult == null) {
                        hs.markAsLocalOnly(retryItem.profileHash)
                        PatchOutcome.NOT_FOUND
                    } else if (retryResult.version != retryItem.version) {
                        // 仍然冲突：放弃，以服务器为准
                        hs.applyServerUpdate(retryItem.profileHash, retryResult)
                        PatchOutcome.CONFLICT
                    } else {
                        hs.applyServerUpdate(retryItem.profileHash, retryResult)
                        PatchOutcome.SUCCESS
                    }
                } else {
                    PatchOutcome.CONFLICT
                }
            } else {
                // 成功：以服务器版本为准
                hs.applyServerUpdate(item.profileHash, result)
                PatchOutcome.SUCCESS
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "syncHistory: PATCH failed for ${item.profileHash}: ${e.message}")
            PatchOutcome.FAILED
        }
    }

    /** POST 单条处理结果 */
    private enum class PostOutcome { SUCCESS, CONFLICT, FAILED }

    /** 上传单条 LocalOnly 记录到服务器 */
    private suspend fun processPostItem(
        item: HistoryItem,
        client: SyncClipboardApi,
        hs: HistoryService
    ): PostOutcome {
        return try {
            val dto = hs.toDto(item)
            val result = client.uploadHistoryRecord(dto, null)
            if (result != null) {
                hs.applyServerUpdate(item.profileHash, result)
                if (result.version != item.version) PostOutcome.CONFLICT else PostOutcome.SUCCESS
            } else {
                PostOutcome.FAILED
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "syncHistory: POST failed for ${item.profileHash}: ${e.message}")
            PostOutcome.FAILED
        }
    }

    /** syncHistory 的返回结果 */
    private data class SyncHistoryResult(
        val success: Boolean,
        val recordsFetched: Int,
        val error: String?
    )

    fun forceUpload() {
        scope.launch {
            var success = false
            var message: String? = null
            try {
                val context = appContext ?: run {
                    message = "No context"
                    notifyActionResult("upload", false, message)
                    return@launch
                }
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: run {
                    message = "No clipboard"
                    notifyActionResult("upload", false, message)
                    return@launch
                }
                val clipData = cm.primaryClip ?: run {
                    message = "Empty clipboard"
                    notifyActionResult("upload", false, message)
                    return@launch
                }
                val content = extractFromClip(context, clipData) ?: run {
                    message = "No content"
                    notifyActionResult("upload", false, message)
                    return@launch
                }
                // 手动上传绕过 autoSync/bgUpload 开关，直接上传
                val hash = content.profileHash ?: HashUtils.sha256(content.text)
                lastLocalHash = hash
                historyService?.addLocalContent(content)
                notifyContentChanged()
                success = uploadContent(content)
                // 历史同步改为手动触发，上传后不再自动 syncHistory
                message = if (success) "Upload OK" else "Upload failed"
            } catch (e: Exception) {
                Logger.error(TAG, "Force upload failed", e)
                message = "Upload error: ${e.message}"
            }
            notifyActionResult("upload", success, message)
        }
    }

    /**
     * 直接上传一段文本（如短信验证码）到服务器，绕过 autoSync/bgUpload 开关。
     * 上传前先复制到剪贴板，并通过 profileHash 去重，避免相同内容重复上传。
     */
    fun uploadText(text: String) {
        if (appContext == null || text.isBlank()) return
        scope.launch {
            try {
                // 先设置 lastLocalHash，阻止 clipChangedListener 在 setPrimaryClip 后把相同
                // 内容当作"新剪贴板变化"再次上传（clipChangedListener 在主线程异步回调，若
                // hash 未先设置将触发第二次 uploadContent，造成重复上传）
                lastLocalHash = HashUtils.sha256(text)

                // 1. 服务端去重：profileHash 与上次上传内容相同则只复制不上传
                val profileHash = HashUtils.sha256(text)
                val alreadyRemote = profileHash.equals(lastRemoteHash, ignoreCase = true)

                // 2. 自动复制到剪贴板（SystemUI 进程拥有完整 ClipboardService 访问权限）
                val ctx = appContext
                if (ctx != null) {
                    try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("SyncClipboard", text)
                        )
                        Logger.info(TAG, "uploadText: copied to clipboard")
                    } catch (e: Exception) {
                        Logger.warn(TAG, "uploadText: clipboard copy failed: ${e.message}")
                    }
                }

                if (alreadyRemote) {
                    Logger.info(TAG, "uploadText: skipped upload (already remote)")
                    return@launch
                }

                val content = ClipboardContent(
                    type = ClipboardContentType.Text,
                    text = text,
                    hasData = false,
                    timestamp = System.currentTimeMillis()
                )
                historyService?.addLocalContent(content)
                notifyContentChanged()
                val ok = uploadContent(content)
                // 历史同步改为手动触发，上传后不再自动 syncHistory
                Logger.info(TAG, "uploadText: ok=$ok text=${text.take(20)}")
            } catch (e: Exception) {
                Logger.error(TAG, "uploadText failed", e)
            }
        }
    }

    // ─── 私有方法 ────────────────────────────────────────────────

    private suspend fun fetchRemoteClipboard(force: Boolean = false): Boolean {
        // 防护：避免轮询与 IPC 触发的 force fetch 并发下载同一内容
        if (isFetching) {
            if (force) {
                // 手动刷新与轮询撞车：排队，当前拉取结束后补跑一次
                pendingForceFetch = true
                Logger.info(TAG, "fetchRemoteClipboard: fetching in progress, force fetch queued")
            } else {
                Logger.debug(TAG, "fetchRemoteClipboard: already fetching, skipping")
            }
            return true  // 已有拉取在进行中，视为成功（缓存稍后会更新）
        }
        isFetching = true

        // 拉取 profile 并判断内容是否变化（唯一需要持有 isFetching 的临界区）。
        // 网络请求完成后立即释放 isFetching：广播/下载/历史均为异步执行，
        // 不占用 fetch 锁，避免下载卡住时阻塞轮询与后续手动刷新。
        val client = apiClient
        if (client == null) {
            isFetching = false
            Logger.warn(TAG, "fetchRemoteClipboard: apiClient is null")
            return false
        }

        val profile = try {
            client.getClipboard()
        } catch (e: Exception) {
            isFetching = false
            setConnected(false)
            Logger.warn(TAG, "Remote fetch error", e)
            return false
        }
        if (profile == null) {
            isFetching = false
            setConnected(false)
            Logger.warn(TAG, "fetchRemoteClipboard: getClipboard returned null")
            return false
        }
        Logger.info(TAG, "fetchRemoteClipboard: type=${profile.type}, hash=${profile.hash}, text=${profile.text.take(50)}, hasData=${profile.hasData}")
        val hash = profile.hash
        if (hash == null) {
            isFetching = false
            setConnected(false)
            Logger.warn(TAG, "fetchRemoteClipboard: profile.hash is null, skipping")
            return false
        }

        setConnected(true)
        lastSyncTime = System.currentTimeMillis()

        // 网络部分完成，释放 isFetching，补跑排队的手动刷新
        isFetching = false
        if (pendingForceFetch) {
            pendingForceFetch = false
            if (apiClient != null) {
                Logger.info(TAG, "Executing queued force fetch")
                scope.launch {
                    try {
                        fetchRemoteClipboard(force = true)
                    } catch (e: Exception) {
                        Logger.warn(TAG, "Queued force fetch failed", e)
                    }
                }
            }
        }

        val contentChanged = !hash.equals(lastRemoteHash, ignoreCase = true)

        if (!force && !contentChanged) {
            // 轮询且内容未变化：快速返回
            return true
        }

        if (contentChanged) {
            // 内容变化：先广播 profile（app 立即刷新并自行下载预览），
            // 文件下载/历史记录/自动保存由 notifyAndApplyAsync 后台继续，避免大文件阻塞 UI
            lastRemoteHash = hash
            appContext?.let { Prefs.saveLastRemoteHash(it, hash) }
            Logger.info(TAG, "Remote clipboard changed: ${profile.text.take(50)}...")

            if (force || config.enableBackgroundDownload) {
                notifyAndApplyAsync(profile)
            } else {
                // 未开启后台下载：仅更新 profile 缓存，filePath 不变
                lastRemoteProfile = profile
                notifyContentChanged()
            }
        } else {
            // 内容未变化：通知 app 停止转圈
            // 补救：内容有数据但 lastRemoteFilePath 为空（重启/下载缺失/失败）时
            // 先尝试按路径规则重建（文件可能仍在），重建不了则重新下载
            if (profile.hasData && lastRemoteFilePath.isNullOrBlank() &&
                (profile.type == ClipboardContentType.Image || profile.type == ClipboardContentType.File)) {
                val restored = restoreRemoteFilePath(profile)
                if (restored) {
                    notifyContentChanged()
                } else if (force || config.enableBackgroundDownload) {
                    Logger.info(TAG, "Fetch: content unchanged but file missing, re-downloading")
                    notifyAndApplyAsync(profile)
                } else {
                    if (lastRemoteProfile == null) {
                        lastRemoteProfile = profile
                    }
                    notifyContentChanged()
                }
            } else {
                if (lastRemoteProfile == null) {
                    lastRemoteProfile = profile
                }
                notifyContentChanged()
            }
        }
        return true
    }

    /** 尝试按路径规则重建已下载文件的路径（文件可能仍在磁盘，重启后路径丢失）。
     *  返回是否成功恢复。 */
    private fun restoreRemoteFilePath(profile: ProfileDto): Boolean {
        val name = profile.dataName ?: return false
        val ctx = appContext ?: return false
        val path = "${ctx.filesDir}/downloads/$name"
        val file = java.io.File(path)
        if (file.exists() && file.length() > 0) {
            lastRemoteFilePath = path
            Prefs.saveLastRemoteFilePath(ctx, path)
            Logger.info(TAG, "Remote file path restored: $path (size=${file.length()})")
            return true
        }
        return false
    }

    /** 先广播 profile 供 app 立即刷新（app 自行下载预览文件），
     *  文件下载/历史记录/自动保存放到后台协程执行，避免大文件下载阻塞 fetch 返回与轮询。 */
    private fun notifyAndApplyAsync(profile: ProfileDto) {
        lastRemoteProfile = profile
        notifyContentChanged()
        scope.launch {
            try {
                downloadAndApplyContent(profile)
            } catch (e: Exception) {
                Logger.error(TAG, "Background download and apply failed", e)
            }
        }
    }

    /** 上传失败重试队列是否正在重放（防重入） */
    @Volatile
    private var uploadQueueFlushing = false

    /**
     * 重放上传重试队列：按序重试，成功即出队，失败保留（下次再试）。
     * 触发时机：新上传成功、轮询 fetch 成功（网络可用信号）、SignalR 重连成功。
     */
    private fun flushUploadQueue(context: Context) {
        if (uploadQueueFlushing) return
        if (UploadQueue.isEmpty(context)) return
        uploadQueueFlushing = true
        scope.launch {
            try {
                while (isActive) {
                    val item = UploadQueue.peek(context) ?: break
                    val key = UploadQueue.contentKey(item.content)
                    val ok = uploadContent(item.content)
                    if (ok) {
                        UploadQueue.remove(context, key)
                    } else {
                        // 单条失败：记录重试次数，超过上限丢弃（防止死循环堆积）
                        UploadQueue.markRetry(context, key)
                        Logger.warn(TAG, "Upload queue flush failed (retry=${item.retryCount + 1}): ${item.content.text.take(30)}")
                        if (item.retryCount >= MAX_UPLOAD_RETRY) {
                            Logger.warn(TAG, "Upload queue: dropping item after $MAX_UPLOAD_RETRY retries")
                            UploadQueue.remove(context, key)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Upload queue flush error: ${e.message}")
            } finally {
                uploadQueueFlushing = false
            }
        }
    }

    private suspend fun uploadContent(content: ClipboardContent): Boolean {
        val client = apiClient ?: run {
            Logger.warn(TAG, "No API client configured, skipping upload")
            return false
        }

        try {
            // 如果 fileUri 是 content:// URI，先复制到临时文件（putFile 需要文件路径）
            val fileUri = content.fileUri
            val uploadContent = if (content.hasData && fileUri != null &&
                fileUri.startsWith("content://")) {
                val context = appContext ?: return false
                val tempFile = copyUriToTempFile(context, fileUri, content.fileName)
                if (tempFile != null) {
                    content.copy(fileUri = tempFile.absolutePath)
                } else {
                    Logger.warn(TAG, "Failed to copy URI to temp file, uploading as text")
                    content.copy(hasData = false)
                }
            } else {
                content
            }

            client.putContent(uploadContent)
            lastSyncTime = System.currentTimeMillis()
            // 设置 lastRemoteHash，防止轮询循环立即下载刚上传的内容
            // 使用 uploadContent（已将 content:// 转为本地路径）保证文件 hash 可读
            lastRemoteHash = HashUtils.computeContentHash(uploadContent)
            appContext?.let { Prefs.saveLastRemoteHash(it, lastRemoteHash) }
            // 上传成功说明网络可用：顺带重放失败队列
            appContext?.let { flushUploadQueue(it) }
            // 更新缓存，使 app 端能立即显示刚上传的内容
            lastRemoteProfile = ProfileDto(
                type = uploadContent.type,
                hash = lastRemoteHash,
                text = uploadContent.text,
                hasData = uploadContent.hasData,
                dataName = uploadContent.fileName,
                size = uploadContent.fileSize
            )
            lastRemoteFilePath = uploadContent.fileUri?.takeIf { it.startsWith("/") }
            Logger.info(TAG, "Content uploaded successfully")
            return true
        } catch (e: Exception) {
            Logger.error(TAG, "Upload failed", e)
            return false
        }
    }

    /** 将 content:// URI 复制到临时文件，返回临时 File */
    private fun copyUriToTempFile(
        context: Context,
        uriString: String,
        fileName: String?
    ): java.io.File? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            val name = fileName ?: "temp_${System.currentTimeMillis()}"
            val tempFile = java.io.File(context.cacheDir, "upload_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Logger.debug(TAG, "Copied URI to temp file: $uriString -> ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to copy URI to temp file: $uriString", e)
            null
        }
    }

    private suspend fun downloadAndApplyContent(profile: ProfileDto) {
        val client = apiClient ?: run {
            Logger.warn(TAG, "downloadAndApplyContent: apiClient is null, skipping")
            return
        }
        val context = appContext ?: run {
            Logger.warn(TAG, "downloadAndApplyContent: context is null, skipping")
            return
        }
        Logger.info(TAG, "downloadAndApplyContent: start, type=${profile.type}, hash=${profile.hash}, hasData=${profile.hasData}, dataName=${profile.dataName}")

        try {
            var downloadedFileUri: android.net.Uri? = null
            var downloadedFilePath: String? = null
            if (profile.hasData && profile.dataName != null) {
                val name = profile.dataName!!
                val destPath = "${context.filesDir}/downloads/$name"
                Logger.info(TAG, "downloadAndApplyContent: downloading file $name")
                client.downloadFile(name, destPath)
                val destFile = java.io.File(destPath)
                downloadedFileUri = android.net.Uri.fromFile(destFile)
                downloadedFilePath = destPath
                Logger.info(TAG, "File downloaded: $name -> $destPath (size=${destFile.length()})")
            } else {
                Logger.debug(TAG, "downloadAndApplyContent: no file data, skipping download")
            }

            // 写入剪贴板前设置 lastLocalHash，防止 listener 二次上传
            // 同时设置 lastRemoteHash，防止下次轮询把刚写入剪贴板的内容误判为新内容
            val localHash = HashUtils.sha256(profile.text)
            lastLocalHash = localHash
            lastRemoteHash = profile.hash ?: localHash
            appContext?.let { Prefs.saveLastRemoteHash(it, lastRemoteHash) }

            // 仅纯文本（type=Text 且无文件数据且无文件名）才写入剪贴板。
            // 图片/文件类型不写入（避免输入法只读取到文件名），
            // 有 dataName 但 hasData=false 时也不写入（可能是文件上传中间状态）。
            if (profile.type == ClipboardContentType.Text && !profile.hasData &&
                profile.dataName.isNullOrBlank()) {
                Logger.debug(TAG, "downloadAndApplyContent: writing text to clipboard")
                writeToClipboard(profile.text)
            }

            // 自动保存：若开启且为图片/文件类型，则保存到相册或下载目录
            if (config.enableAutoSave && downloadedFilePath != null &&
                (profile.type == ClipboardContentType.Image || profile.type == ClipboardContentType.File)) {
                try {
                    Logger.debug(TAG, "downloadAndApplyContent: auto-saving file")
                    autoSaveToFile(context, downloadedFilePath!!, profile.type, profile.dataName)
                } catch (e: Exception) {
                    Logger.warn(TAG, "Auto save failed: ${e.message}")
                }
            }

            // 记录到历史（syncStatus = Synced，参考原项目 addRemoteContent）
            // 传入服务器的 hash 作为 profileHash，避免本地重新计算时格式不一致
            val historyContent = ClipboardContent(
                type = profile.type,
                text = profile.text,
                fileUri = downloadedFilePath,
                fileName = profile.dataName,
                fileSize = profile.size,
                hasData = profile.hasData,
                profileHash = profile.hash,
                timestamp = System.currentTimeMillis()
            )
            historyService?.addRemoteContent(historyContent, downloadedFilePath)
            Logger.debug(TAG, "downloadAndApplyContent: history recorded")
            lastRemoteFilePath = downloadedFilePath
            appContext?.let { Prefs.saveLastRemoteFilePath(it, downloadedFilePath) }
            // 缓存一致性：profile 与 filePath 同时更新后再通知 UI
            lastRemoteProfile = profile
            notifyContentChanged()
            Logger.debug(TAG, "downloadAndApplyContent: notified app")

            lastSyncTime = System.currentTimeMillis()
            Logger.info(TAG, "Remote content applied to local clipboard")
        } catch (e: Exception) {
            Logger.error(TAG, "Download and apply failed", e)
        }
    }

    /** 根据文件名猜测 MIME 类型 */
    private fun guessMimeFromName(name: String?): String {
        if (name == null) return "application/octet-stream"
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".bmp") -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    /** 自动保存文件到相册或下载目录 */
    private fun autoSaveToFile(
        context: Context,
        filePath: String,
        type: ClipboardContentType,
        fileName: String?
    ) {
        val srcFile = java.io.File(filePath)
        if (!srcFile.exists()) return
        val resolver = context.contentResolver
        val name = fileName ?: srcFile.name
        val mime = guessMimeFromName(fileName)

        if (type == ClipboardContentType.Image) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                }
                Logger.info(TAG, "Auto saved image to gallery: $name")
            }
        } else {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                }
                Logger.info(TAG, "Auto saved file to downloads: $name")
            }
        }
    }

    /** 写入文本到剪贴板 */
    private fun writeToClipboard(text: String) {
        try {
            val context = appContext ?: return
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return

            val clipData = android.content.ClipData.newPlainText("SyncClipboard", text)
            clipboardManager.setPrimaryClip(clipData)

            Logger.debug(TAG, "Written to clipboard: ${text.take(50)}...")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to write to clipboard", e)
        }
    }

    /** 写入 URI（图片/文件）到剪贴板 */
    private fun writeToClipboardUri(uri: android.net.Uri, label: String, mime: String) {
        try {
            val context = appContext ?: return
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return

            val clipData = android.content.ClipData.newUri(
                context.contentResolver,
                "SyncClipboard",
                uri
            )
            clipboardManager.setPrimaryClip(clipData)

            Logger.info(TAG, "Written URI to clipboard: $uri, mime=$mime, label=$label")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to write URI to clipboard, falling back to text", e)
            writeToClipboard(label)
        }
    }

    /** 通知 app 进程内容已变化（本地或远程），触发 UI 刷新 */
    private fun notifyContentChanged() {
        val context = appContext ?: return
        try {
            val intent = Intent(BridgeKeys.EVENT_CLIPBOARD_CHANGED)
                .setPackage("io.github.erenche.syncclipboard")
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to notify content changed: ${e.message}")
        }
    }

    /** 更新连接状态，状态翻转时通知 app 进程 */
    private fun setConnected(connected: Boolean) {
        if (isConnected != connected) {
            isConnected = connected
            notifySyncStateChanged()
        }
    }

    /** 通知 app 进程同步状态变化（轮询启停） */
    private fun notifySyncStateChanged() {
        val context = appContext ?: return
        try {
            val intent = Intent(BridgeKeys.EVENT_SYNC_STATE_CHANGED)
                .setPackage("io.github.erenche.syncclipboard")
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to notify sync state changed: ${e.message}")
        }
    }

    /** 通知 app 进程手动操作结果（同步/上传） */
    private fun notifyActionResult(action: String, success: Boolean, message: String?) {
        val context = appContext ?: return
        try {
            val intent = Intent(BridgeKeys.EVENT_ACTION_RESULT)
                .setPackage("io.github.erenche.syncclipboard")
                .putExtra("action", action)
                .putExtra("success", success)
                .putExtra("message", message ?: "")
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to notify action result: ${e.message}")
        }
    }

    /** 通知 app 进程历史同步已完成（异步通知，配合 FORCE_SYNC_HISTORY 异步化） */
    private fun notifyHistorySyncCompleted(success: Boolean, fetched: Int, count: Int, error: String?) {
        val context = appContext ?: return
        try {
            val intent = Intent(BridgeKeys.EVENT_HISTORY_SYNC_COMPLETED)
                .setPackage("io.github.erenche.syncclipboard")
                .putExtra("success", success)
                .putExtra("fetched", fetched)
                .putExtra("count", count)
            if (error != null) intent.putExtra("error", error)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to notify history sync completed: ${e.message}")
        }
    }

    private fun rebuildApiClient() {
        val server = config.servers.getOrNull(config.activeServerIndex)
        apiClient = if (server != null) {
            try {
                ClientFactory.createClient(server)
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to create API client: ${e.message}")
                null
            }
        } else {
            null
        }
        // SignalR 仅在官方服务器模式且配置开启时创建
        rebuildSignalRClient(server)
    }

    /** 根据服务器类型和配置创建/销毁 SignalR 客户端 */
    private fun rebuildSignalRClient(server: ServerConfig?) {
        // 先停止并释放旧连接
        signalRClient?.let { old ->
            old.dispose()
            signalRClient = null
            isSignalRConnected = false
            Logger.info(TAG, "SignalR client disposed")
        }
        // 取消旧的推送消费者
        historyPushConsumer?.cancel()
        historyPushConsumer = null

        if (server == null) return
        // 仅官方服务器模式且配置开启时启用
        if (server.type != ServerType.syncclipboard) {
            Logger.info(TAG, "SignalR skipped: server type=${server.type}")
            return
        }
        if (!config.enableSignalRPush) {
            Logger.info(TAG, "SignalR skipped: disabled by config")
            return
        }

        try {
            val client = SignalRClient(
                baseUrl = server.url,
                username = server.username,
                password = server.password
            )
            client.onProfileChanged = { profile ->
                // 推送回调：触发强制拉取（复用 fetchRemoteClipboard 的去重和下载逻辑）
                scope.launch {
                    Logger.info(TAG, "SignalR push: RemoteProfileChanged, triggering fetch")
                    fetchRemoteClipboard(force = true)
                }
            }
            client.onHistoryChanged = { dto ->
                // 推送事件送入通道串行化处理，避免批量推送时协程堆积
                historyPushChannel.trySend(dto)
            }
            client.onConnectionStateChanged = { connected ->
                isSignalRConnected = connected
                Logger.info(TAG, "SignalR connection: ${if (connected) "connected" else "disconnected"}")
                if (connected) {
                    // 重连成功后补拉断连期间的数据（与 syncclipboard-mobile 行为一致）
                    scope.launch {
                        try {
                            Logger.info(TAG, "SignalR reconnected, triggering catch-up fetch")
                            fetchRemoteClipboard(force = true)
                        } catch (e: Exception) {
                            Logger.warn(TAG, "Catch-up fetch on SignalR reconnect failed: ${e.message}")
                        }
                    }
                    // 网络可达：顺带重放上传失败队列
                    appContext?.let { flushUploadQueue(it) }
                }
                notifySyncStateChanged()
            }
            signalRClient = client
            // 启动历史推送通道消费者（串行处理，避免批量推送时协程堆积）
            startHistoryPushConsumer()
            Logger.info(TAG, "SignalR client created for server: ${server.url}")
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to create SignalR client: ${e.message}")
        }
    }

    /** 启动历史推送通道消费者：串行处理推送事件，避免并发协程堆积 */
    private fun startHistoryPushConsumer() {
        historyPushConsumer?.cancel()
        historyPushConsumer = scope.launch {
            for (dto in historyPushChannel) {
                try {
                    handleRemoteHistoryChanged(dto)
                } catch (e: Exception) {
                    Logger.warn(TAG, "History push consumer error: ${e.message}")
                }
            }
        }
    }

    /** 处理 SignalR 推送的历史记录变化（增量合并单条 DTO）。
     *  与 syncHistory 互斥，避免全量合并覆盖推送写入的新版本。 */
    private suspend fun handleRemoteHistoryChanged(dto: HistoryRecordDto) {
        val hs = historyService ?: return
        if (!config.enableHistorySync) return
        historySyncMutex.withLock {
            try {
                Logger.info(TAG, "SignalR push: RemoteHistoryChanged, hash=${dto.hash}, merging")
                hs.mergeFromServerDtos(listOf(dto))
                // 通知 app 端历史记录已变化，触发 UI 刷新
                notifyContentChanged()
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to merge remote history push: ${e.message}")
            }
        }
    }

    /** 单条历史记录变更后即时 PATCH 推送到服务器。
     *  获取 historySyncMutex 避免与 syncHistory 并发，复用 processPatchItem 逻辑。
     *  仅 SyncClipboard 官方服务器模式生效；WebDAV/S3 不支持历史 PATCH，仅做本地变更。 */
    private suspend fun pushSingleHistoryUpdate(id: String) {
        val hs = historyService ?: return
        val client = apiClient ?: return
        val server = config.servers.getOrNull(config.activeServerIndex)
        if (server == null || server.type != ServerType.syncclipboard) return
        val item = hs.getById(id) ?: return
        if (item.syncStatus != HistorySyncStatus.NeedSync) return
        historySyncMutex.withLock {
            // 重新读取，避免在等待锁期间状态被其他流程改变
            val current = hs.getById(id) ?: return@withLock
            if (current.syncStatus != HistorySyncStatus.NeedSync) return@withLock
            try {
                val outcome = processPatchItem(current, client, hs)
                Logger.info(TAG, "pushSingleHistoryUpdate: hash=${current.profileHash}, outcome=$outcome")
            } catch (e: Exception) {
                Logger.warn(TAG, "pushSingleHistoryUpdate failed: ${e.message}")
            }
        }
    }

    private fun setupBridgeRouting(context: Context) {
        SyncClipboardBridge.routing(context) {
            onQuery(BridgeKeys.GET_CONFIG) {
                val configJson = Json.encodeToString(AppConfig.serializer(), config)
                reply(Bundle().apply { putString("config", configJson) })
            }

            onCommand(BridgeKeys.PUSH_CONFIG) { data ->
                val configJson = data.getString("config") ?: return@onCommand
                try {
                    val newConfig = Json.decodeFromString(AppConfig.serializer(), configJson)
                    onConfigChanged(newConfig)
                    Prefs.saveConfig(context, newConfig)
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to parse config", e)
                }
            }

            onQuery(BridgeKeys.GET_SYNC_STATUS) {
                reply(Bundle().apply {
                    putBoolean("connected", isConnected)
                    putBoolean("running", isRunning)
                    putBoolean("pollingActive", isPollingActive)
                    putBoolean("signalRConnected", isSignalRConnected)
                    putLong("lastSyncTime", lastSyncTime)
                })
            }

            onQuery(BridgeKeys.GET_CURRENT_CLIPBOARD) {
                // 立即返回当前缓存用于快速渲染，绝不阻塞 IPC
                // 仅当 payload forceFetch=true（用户下拉刷新）时触发异步强制拉取
                // 广播触发的刷新只读缓存，避免 fetch→notify→fetch 循环
                val forceFetch = data.getBoolean("forceFetch", false)
                if (forceFetch && apiClient != null) {
                    scope.launch {
                        try {
                            fetchRemoteClipboard(force = true)
                        } catch (e: Exception) {
                            Logger.warn(TAG, "GET_CURRENT_CLIPBOARD: async fetch failed", e)
                        }
                    }
                }
                val profile = lastRemoteProfile
                val profileJson = profile?.let {
                    Json.encodeToString(ProfileDto.serializer(), it)
                }
                reply(Bundle().apply {
                    if (profileJson != null) putString("profile", profileJson)
                    if (lastRemoteFilePath != null) putString("filePath", lastRemoteFilePath)
                })
            }

            onCommand(BridgeKeys.TRIGGER_SYNC) {
                forceSync()
            }

            onCommand(BridgeKeys.UPLOAD_NOW) {
                forceUpload()
            }

            onCommand(BridgeKeys.UPLOAD_TEXT) { data ->
                val text = data.getString("text") ?: return@onCommand
                uploadText(text)
            }

            onQuery(BridgeKeys.GET_HISTORY) {
                val items = historyService?.getAll() ?: emptyList()
                Logger.info(TAG, "GET_HISTORY: historyService=${if (historyService != null) "exists" else "null"}, items=${items.size}")
                val itemsJson = Json.encodeToString(
                    ListSerializer(HistoryItem.serializer()), items
                )
                reply(Bundle().apply { putString("items", itemsJson) })
            }

            onQuery(BridgeKeys.GET_HISTORY_PAGED) {
                // 分页查询：减少 IPC 序列化数据量，避免 UI 全量重载卡顿
                val offset = data.getInt("offset", 0)
                val limit = data.getInt("limit", 50)
                val searchText = data.getString("searchText")
                val hs = historyService
                if (hs != null) {
                    val pageItems = hs.getPaged(offset, limit, searchText)
                    val totalCount = hs.count(searchText)
                    val itemsJson = Json.encodeToString(
                        ListSerializer(HistoryItem.serializer()), pageItems
                    )
                    reply(Bundle().apply {
                        putString("items", itemsJson)
                        putInt("totalCount", totalCount)
                    })
                } else {
                    reply(Bundle().apply {
                        putString("items", "[]")
                        putInt("totalCount", 0)
                    })
                }
            }

            onQuery(BridgeKeys.FORCE_SYNC_HISTORY) {
                if (config.enableHistorySync) {
                    // 异步化：立即回复"已开始"，后台执行增量同步，完成后广播通知
                    // 注意：手动刷新走增量同步（force=true 等待锁，但不重置游标）
                    // 全量同步仅在首次启动/配置切换时通过 forceFullSync() 触发
                    reply(Bundle().apply { putBoolean("started", true) })
                    scope.launch {
                        val result = syncHistory(force = true, fullSync = false)
                        val localCount = historyService?.getAll()?.size ?: 0
                        Logger.info(TAG, "FORCE_SYNC_HISTORY: success=${result.success}, fetched=${result.recordsFetched}, local=$localCount, error=${result.error}")
                        notifyHistorySyncCompleted(result.success, result.recordsFetched, localCount, result.error)
                    }
                } else {
                    Logger.warn(TAG, "FORCE_SYNC_HISTORY: history sync disabled")
                    reply(Bundle().apply {
                        putBoolean("started", false)
                        putString("error", "History sync disabled")
                    })
                }
            }

            onCommand(BridgeKeys.DELETE_HISTORY_ITEM) { data ->
                val id = data.getString("id") ?: return@onCommand
                historyService?.delete(id)
                Logger.info(TAG, "History item deleted: $id")
            }

            onCommand(BridgeKeys.UPDATE_HISTORY_ITEM) { data ->
                val id = data.getString("id") ?: return@onCommand
                val action = data.getString("action") ?: "toggleStar"
                val hs = historyService ?: return@onCommand
                when (action) {
                    "toggleStar" -> {
                        hs.toggleStar(id)
                        Logger.info(TAG, "History item star toggled: $id")
                        scope.launch { pushSingleHistoryUpdate(id) }
                    }
                    "togglePin" -> {
                        hs.togglePin(id)
                        Logger.info(TAG, "History item pin toggled: $id")
                        scope.launch { pushSingleHistoryUpdate(id) }
                    }
                }
            }

            onCommand(BridgeKeys.CLEAR_HISTORY) {
                historyService?.clearAll()
                // 重置历史同步游标，强制下次全量同步
                // 这样 mergeFromServerDtos 会从服务器恢复活跃记录
                lastSyncTime = 0L
                appContext?.let { Prefs.resetHistoryLastSyncTime(it) }
                Logger.info(TAG, "History cleared, sync cursor reset for full sync")
            }

            onQuery(BridgeKeys.TEST_CONNECTION) {
                val serverJson: String = data.getString("server") ?: run {
                    reply(Bundle().apply {
                        putBoolean("success", false)
                        putString("error", "No server configuration provided")
                    })
                    return@onQuery
                }
                try {
                    val serverConfig = Json { ignoreUnknownKeys = true }
                        .decodeFromString(ServerConfig.serializer(), serverJson)
                    val client = ClientFactory.createClient(serverConfig)
                    client.testConnection()
                    reply(Bundle().apply { putBoolean("success", true) })
                } catch (e: Exception) {
                    Logger.error(TAG, "Test connection failed", e)
                    reply(Bundle().apply {
                        putBoolean("success", false)
                        putString("error", e.message ?: "Connection failed")
                    })
                }
            }

            onQuery(BridgeKeys.GET_LOGS) {
                val logs = Logger.getLogs()
                reply(Bundle().apply { putString("logs", logs) })
            }

            onQuery(BridgeKeys.CLEAR_LOGS) {
                Logger.clear()
                reply(Bundle().apply { putBoolean("success", true) })
            }
        }
    }
}
