package io.github.erenche.syncclipboard.xposed.api

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.github.erenche.syncclipboard.common.model.HistoryRecordDto
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 轻量级 ASP.NET Core SignalR 客户端（WebSocket 传输）。
 *
 * 协议要点：
 * - 握手：连接建立后首帧发送 `{"protocol":"json","version":1}\u001e`（0x1E 为 Record Separator）
 * - 服务端握手响应：成功时返回 `{"connectionId":"...","connectionTimeout":...}\u001e`（无 type 字段），
 *   失败时返回 `{"error":"..."}\u001e`
 * - 后续消息以 `\u001e` 分隔，单条 JSON 即一帧
 * - 推送格式：`{"type":1,"target":"RemoteProfileChanged","arguments":[{...}]}\u001e`
 *   type=1 为 Invocation（服务端调用客户端方法）
 * - Ping/Pong：type=6 为 Ping，需回复 type=6 的 Pong 保持连接
 * - type=7 为 Close（服务端主动关闭连接）
 *
 * 仅支持 SyncClipboard 官方服务器模式，WebDAV/S3 不创建此客户端。
 */
class SignalRClient(
    private val baseUrl: String,
    private val username: String?,
    private val password: String?,
    private val hubPath: String = DEFAULT_HUB_PATH
) {
    companion object {
        private const val TAG = "SignalRClient"
        private const val DEFAULT_HUB_PATH = "/SyncClipboardHub"
        /** SignalR Record Separator，每条消息以此分隔 */
        private const val RECORD_SEPARATOR = '\u001E'
        /** 轮询间隔 60s（推送连接成功时的兜底间隔，由 SyncEngine 读取） */
        const val PUSH_ACTIVE_POLLING_MS = 60_000L
        /** 重连初始间隔 */
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        /** 重连最大间隔 */
        private const val MAX_RECONNECT_DELAY_MS = 5 * 60_000L
        /** 连接保持超过该时长视为"成功连接"，断开重连时重置退避间隔 */
        private const val CONNECTED_KEEP_MS = 20_000L
    }

    /** 推送回调：远程剪贴板变化 */
    var onProfileChanged: ((ProfileDto) -> Unit)? = null
    /** 推送回调：远程历史记录变化 */
    var onHistoryChanged: ((HistoryRecordDto) -> Unit)? = null
    /** 连接状态变化回调 */
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    /** 当前连接状态（true=已连接，false=断开） */
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var connectionJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isStopped = false
    /** 带 id 的 WebSocket 路径是否失败过：失败一次后优先使用直连，避免每次重连都多一次失败往返 */
    @Volatile
    private var preferDirect = false

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(OkHttp) {
        install(WebSockets)
    }

    /** 构建 WebSocket 握手 URL（http→ws, https→wss） */
    private fun buildWebSocketUrl(connectionId: String? = null): String {
        val base = baseUrl.trimEnd('/')
        val wsUrl = when {
            base.startsWith("https://", ignoreCase = true) ->
                "wss://" + base.substring("https://".length)
            base.startsWith("http://", ignoreCase = true) ->
                "ws://" + base.substring("http://".length)
            base.startsWith("ws://", ignoreCase = true) ||
                base.startsWith("wss://", ignoreCase = true) -> base
            else -> "ws://$base"
        }
        val url = "$wsUrl$hubPath"
        return if (connectionId != null) "$url?id=$connectionId" else url
    }

    /** SignalR negotiate 阶段：POST /hub/negotiate 获取 connectionId。
     *  ASP.NET Core SignalR 要求先 negotiate 再建立 WebSocket 连接，
     *  部分反代/网关（Nginx、Cloudflare）会强制要求此步骤。 */
    private suspend fun negotiate(): String? {
        val base = baseUrl.trimEnd('/')
        val negotiateUrl = "$base$hubPath/negotiate?negotiateVersion=1"
        val authHeader = buildAuthHeader()
        return try {
            val response = httpClient.post(negotiateUrl) {
                if (authHeader != null) {
                    header("Authorization", authHeader)
                }
                header("User-Agent", "SyncClipboard-Android")
            }
            if (response.status.value !in 200..299) {
                Log.w(TAG, "Negotiate failed: HTTP ${response.status.value}")
                return null
            }
            val body = response.bodyAsText()
            val obj = json.parseToJsonElement(body).jsonObject
            val connectionId = obj["connectionId"]?.jsonPrimitive?.contentOrNull
            Logger.info(TAG, "Negotiate success: connectionId=$connectionId")
            connectionId
        } catch (e: Exception) {
            Log.w(TAG, "Negotiate error: ${e.message}")
            Logger.warn(TAG, "Negotiate error: ${e.message}")
            null
        }
    }

    /** 构建 Basic Auth header 值 */
    private fun buildAuthHeader(): String? {
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) return null
        val credentials = "$username:$password"
        return "Basic " + Base64.encodeToString(
            credentials.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
    }

    /** 启动 SignalR 连接（带自动重连） */
    fun start() {
        if (connectionJob?.isActive == true) {
            Logger.info(TAG, "start: already running")
            return
        }
        isStopped = false
        connectionJob = scope.launch {
            var retryDelay = INITIAL_RECONNECT_DELAY_MS
            while (isActive && !isStopped) {
                val attemptStart = System.currentTimeMillis()
                try {
                    connectAndListen()
                    // 正常断开（不应发生，除非 stop()）：退出循环
                    if (isStopped) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Connection error: ${e.message}")
                    Logger.warn(TAG, "Connection error: ${e.message}")
                }
                // 连接断开，更新状态
                updateConnectionState(false)
                if (isStopped) break
                // 本次连接保持超过 CONNECTED_KEEP_MS 视为成功连接：重连退避重置，
                // 避免指数退避只增不减（服务器偶发闪断后重连越来越慢）
                if (System.currentTimeMillis() - attemptStart > CONNECTED_KEEP_MS) {
                    retryDelay = INITIAL_RECONNECT_DELAY_MS
                }
                // 指数退避重连
                Log.w(TAG, "Reconnecting in ${retryDelay}ms...")
                Logger.info(TAG, "Reconnecting in ${retryDelay}ms...")
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
        Logger.info(TAG, "start: SignalR client started, url=${buildWebSocketUrl()}")
    }

    /** 停止连接 */
    fun stop() {
        isStopped = true
        connectionJob?.cancel()
        connectionJob = null
        updateConnectionState(false)
        Logger.info(TAG, "stop: SignalR client stopped")
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        httpClient.close()
        Logger.info(TAG, "dispose: resources released")
    }

    /** 建立连接并监听消息。
     *  先尝试 negotiate + WebSocket（标准流程），失败时降级为直连 WebSocket（兼容部分反代）。
     *  带 id 路径失败过一次后（[preferDirect]）后续连接直接走直连，减少无谓失败与重连延迟。 */
    private suspend fun connectAndListen() {
        val authHeader = buildAuthHeader()
        if (!preferDirect) {
            // negotiate 阶段（失败时降级为直连，兼容默认配置的服务器）
            val connectionId = negotiate()
            val urlWithId = buildWebSocketUrl(connectionId)
            Log.w(TAG, "Connecting to $urlWithId")
            Logger.info(TAG, "Connecting to $urlWithId")

            try {
                httpClient.webSocket(
                    urlString = urlWithId,
                    request = {
                        if (authHeader != null) {
                            header("Authorization", authHeader)
                        }
                        header("User-Agent", "SyncClipboard-Android")
                    }
                ) {
                    onConnected(this)
                    listenForMessages(this)
                }
                return
            } catch (e: Exception) {
                // 带 connectionId 的 WebSocket 升级失败（如反代返回 502/404），
                // 降级为不带 id 的直连模式（部分服务器/反代不支持 id 参数路由）
                Log.w(TAG, "WebSocket with connectionId failed: ${e.message}, falling back to direct connect")
                Logger.warn(TAG, "WebSocket with id failed, falling back to direct: ${e.message}")
                // 记住该路径不通，后续直连优先
                preferDirect = true
            }
        }

        // 直连：不带 connectionId（negotiate 失败或带 id 路径已确认不通）
        val urlDirect = buildWebSocketUrl(null)
        Log.w(TAG, "Fallback connecting to $urlDirect")
        Logger.info(TAG, "Fallback connecting to $urlDirect")
        httpClient.webSocket(
            urlString = urlDirect,
            request = {
                if (authHeader != null) {
                    header("Authorization", authHeader)
                }
                header("User-Agent", "SyncClipboard-Android")
            }
        ) {
            onConnected(this)
            listenForMessages(this)
        }
    }

    /** 连接建立后：发送握手帧 */
    private suspend fun onConnected(session: DefaultClientWebSocketSession) {
        // 发送 SignalR 握手：JSON protocol, version 1
        val handshake = buildJsonObject {
            put("protocol", "json")
            put("version", 1)
        }.toString() + RECORD_SEPARATOR
        session.send(Frame.Text(handshake))
        Logger.info(TAG, "Handshake sent")
    }

    /** 监听并解析消息 */
    private suspend fun listenForMessages(session: DefaultClientWebSocketSession) {
        var handshakeConfirmed = false
        for (frame in session.incoming) {
            if (isStopped) break
            if (frame !is Frame.Text) continue
            val raw = frame.readText()
            // SignalR 消息可能包含多条（以 \u001E 分隔）
            for (msg in raw.split(RECORD_SEPARATOR)) {
                if (msg.isBlank()) continue
                try {
                    val obj = json.parseToJsonElement(msg).jsonObject
                    // 握手响应处理：成功（含 connectionId，无 type）或失败（含 error）
                    if (!handshakeConfirmed) {
                        val error = obj["error"]?.jsonPrimitive?.contentOrNull
                        if (error != null) {
                            throw RuntimeException("SignalR handshake failed: $error")
                        }
                        // 握手成功
                        handshakeConfirmed = true
                        updateConnectionState(true)
                        Log.w(TAG, "Handshake confirmed, connection established")
                        Logger.info(TAG, "Handshake confirmed")
                        // 握手响应本身不包含 type，不继续处理
                        if (obj["type"] == null) continue
                    }
                    handleMessage(obj, session)
                } catch (e: Exception) {
                    Logger.warn(TAG, "Failed to parse message: ${e.message}, raw=${msg.take(200)}")
                    if (e is RuntimeException && e.message?.contains("handshake") == true) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * 处理握手后的 SignalR 消息。
     */
    private suspend fun handleMessage(
        obj: JsonObject,
        session: DefaultClientWebSocketSession
    ) {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return

        when (type) {
            // type=1: Invocation（服务端调用客户端方法）
            1 -> {
                val target = obj["target"]?.jsonPrimitive?.contentOrNull ?: return
                val args = obj["arguments"]?.jsonArray ?: return
                handleInvocation(target, args)
            }
            // type=6: Ping，回复 Pong
            6 -> {
                session.send(Frame.Text("{\"type\":6}$RECORD_SEPARATOR"))
            }
            // type=7: Close（服务端主动关闭）
            7 -> {
                val error = obj["error"]?.jsonPrimitive?.contentOrNull
                Logger.warn(TAG, "Server sent close: $error")
                throw RuntimeException("Server closed connection: $error")
            }
            // type=3: InvocationBindingResult, type=4: StreamItem, type=5: Completion
            // SyncClipboard 不使用这些，忽略
            else -> {}
        }
    }

    /** 处理服务端调用客户端方法的推送 */
    private fun handleInvocation(target: String, args: JsonArray) {
        try {
            when (target) {
                "RemoteProfileChanged" -> {
                    val profile = json.decodeFromJsonElement(
                        ProfileDto.serializer(),
                        args.firstOrNull() ?: return
                    )
                    Log.w(TAG, "Push received: RemoteProfileChanged, hash=${profile.hash}")
                    Logger.info(TAG, "RemoteProfileChanged: type=${profile.type}, hash=${profile.hash}")
                    onProfileChanged?.invoke(profile)
                }
                "RemoteHistoryChanged" -> {
                    val dto = json.decodeFromJsonElement(
                        HistoryRecordDto.serializer(),
                        args.firstOrNull() ?: return
                    )
                    Log.w(TAG, "Push received: RemoteHistoryChanged, hash=${dto.hash}")
                    Logger.info(TAG, "RemoteHistoryChanged: hash=${dto.hash}, type=${dto.type}")
                    onHistoryChanged?.invoke(dto)
                }
                else -> {
                    Logger.debug(TAG, "Unknown target: $target")
                }
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to handle invocation '$target': ${e.message}")
        }
    }

    private fun updateConnectionState(connected: Boolean) {
        if (_isConnected.value != connected) {
            _isConnected.value = connected
            onConnectionStateChanged?.invoke(connected)
            Logger.info(TAG, "Connection state: ${if (connected) "connected" else "disconnected"}")
        }
    }
}
