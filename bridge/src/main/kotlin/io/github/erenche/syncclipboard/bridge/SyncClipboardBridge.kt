package io.github.erenche.syncclipboard.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * IPC 桥接器 — 声明式、协程优先的跨进程异步通信
 *
 * 复用自 lyricon 项目的 LyriconBridge，适配剪贴板同步场景。
 *
 * 使用示例：
 * // 服务端（xposed 进程）注册路由
 * SyncClipboardBridge.routing(context) {
 *     onQuery(GET_CONFIG) { reply(bundleOf("config" to configJson)) }
 *     onCommand(PUSH_CONFIG) { data -> updateConfig(data) }
 * }
 *
 * // 客户端（app 进程）发起请求
 * val result = SyncClipboardBridge.with(context)
 *     .to(SYSTEM_UI_PACKAGE)
 *     .key(GET_STATUS)
 *     .await()
 */
object SyncClipboardBridge {

    private const val TAG = "Bridge"
    private const val ACTION_IPC = "io.github.erenche.syncclipboard.ACTION_IPC_ROUTER"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_PAYLOAD = "payload"
    private const val EXTRA_CALLBACK = "callback"

    /** 处理器存储：支持挂起函数的 Lambda 容器 */
    private val handlers = ConcurrentHashMap<String, suspend (Bundle) -> Bundle?>()

    /** 桥接器全局协程作用域 */
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 广播接收器：处理来自其他进程的请求 */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_IPC) return
            val key = intent.getStringExtra(EXTRA_KEY) ?: return
            val handler = handlers[key] ?: return

            val extras = intent.extras ?: return
            val data = extras.getBundle(EXTRA_PAYLOAD) ?: Bundle.EMPTY
            val binder = extras.getBinder(EXTRA_CALLBACK) ?: return

            bridgeScope.launch {
                val callback = IBridgeCallback.Stub.asInterface(binder)
                try {
                    val result = handler(data) ?: Bundle.EMPTY
                    callback.onReply(result)
                } catch (e: Exception) {
                    Logger.warn(TAG, "Bridge handler error for key=$key: ${e.message}")
                    try {
                        callback.onReply(Bundle.EMPTY)
                    } catch (_: Exception) {
                        // 回调进程（app）可能已死，忽略 DeadObjectException
                    }
                }
            }
        }
    }

    @Volatile
    private var isInitialized = false

    /**
     * 初始化并注册路由（服务端使用）。
     *
     * @param context 上下文
     * @param block 路由配置块
     */
    fun routing(context: Context, block: BridgeRoutingScope.() -> Unit) {
        if (!isInitialized) synchronized(this) {
            if (!isInitialized) {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    receiver,
                    IntentFilter(ACTION_IPC),
                    ContextCompat.RECEIVER_EXPORTED
                )
                isInitialized = true
            }
        }
        BridgeRoutingScope().apply(block)
    }

    /**
     * 启动一个请求构造链（客户端使用）。
     *
     * @param context 发起请求的上下文
     */
    fun with(context: Context) = RequestTask(context.applicationContext)

    /**
     * 直接在当前进程中查找并调用处理器（绕过广播）。
     * 用于同一进程内的通信，避免广播投递失败。
     */
    fun invokeHandler(
        key: String,
        data: Bundle,
        onReply: (Bundle) -> Unit
    ) {
        Logger.debug(TAG, "invokeHandler key=$key, handlersSize=${handlers.size}")
        val handler = handlers[key]
        if (handler == null) {
            Logger.warn(TAG, "Handler NOT FOUND for key=$key, available keys=${handlers.keys()}")
            onReply(Bundle.EMPTY)
            return
        }
        Logger.debug(TAG, "Handler FOUND for key=$key, launching coroutine")
        bridgeScope.launch {
            try {
                val result = handler(data) ?: Bundle.EMPTY
                Logger.debug(TAG, "Handler result for key=$key, isEmpty=${result.isEmpty}, running=${result.getBoolean("running", false)}")
                onReply(result)
            } catch (e: Exception) {
                Logger.error(TAG, "Handler error for key=$key", e)
                onReply(Bundle.EMPTY)
            }
        }
    }

    // ─── 内部作用域类 ──────────────────────────────────────────────

    /**
     * 路由注册 DSL 作用域
     */
    class BridgeRoutingScope {
        /**
         * 注册单向指令处理器（fire-and-forget）。
         */
        fun onCommand(key: String, action: suspend (Bundle) -> Unit) {
            handlers[key] = { data ->
                action(data)
                Bundle.EMPTY
            }
        }

        /**
         * 注册双向查询处理器（request-reply）。
         * 在 [QueryScope] 中需显式调用 reply 回传结果。
         */
        fun onQuery(key: String, action: suspend QueryScope.() -> Unit) {
            handlers[key] = { data ->
                val deferred = CompletableDeferred<Bundle>()
                val scope = QueryScope(data, deferred)
                scope.action()
                deferred.await()
            }
        }
    }

    /**
     * 查询上下文作用域 — 持有请求数据并提供 reply 方法
     */
    class QueryScope(
        val data: Bundle,
        private val deferred: CompletableDeferred<Bundle>
    ) {
        /**
         * 显式回传结果并恢复挂起的处理器。
         */
        fun reply(bundle: Bundle) {
            if (deferred.isActive) {
                deferred.complete(bundle)
            }
        }
    }

    /**
     * 客户端任务构造器 — 封装请求参数并发送
     */
    class RequestTask(private val context: Context) {
        private var key: String? = null
        private var data: Bundle = Bundle.EMPTY
        private var targetPkg: String = context.packageName

        /** 设置目标进程包名 */
        fun to(pkg: String) = apply { this.targetPkg = pkg }

        /** 设置业务唯一标识 */
        fun key(key: String) = apply { this.key = key }

        /** 设置数据载体 */
        fun payload(bundle: Bundle) = apply {
            this.data = bundle
        }

        /** 发送单向指令（不关心返回） */
        fun send() {
            executeInternal(null)
        }

        /**
         * 异步获取结果（支持协程挂起）。
         *
         * @param timeout 超时时间（毫秒），默认 3000ms
         * @return 服务端回传的 [Bundle]
         */
        suspend fun await(timeout: Long = 3000): Bundle {
            return try {
                withTimeout(timeout) {
                    val deferred = CompletableDeferred<Bundle>()
                    executeInternal { deferred.complete(it) }
                    deferred.await()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Bundle.EMPTY
            }
        }

        /**
         * 传统回调方式执行任务。
         */
        fun execute(onReply: ((Bundle) -> Unit)? = null) {
            executeInternal(onReply)
        }

        private fun executeInternal(onReplyAction: ((Bundle) -> Unit)?) {
            val targetKey = key
                ?: throw IllegalArgumentException("SyncClipboardBridge: key missing")

            Logger.debug(TAG, "executeInternal key=$targetKey, targetPkg=$targetPkg, myPkg=${context.packageName}, isSameProcess=${targetPkg == context.packageName}")

            // 同一进程内通信 — 直接调用处理器，绕过广播
            if (targetPkg == context.packageName) {
                Logger.debug(TAG, "Using DIRECT invocation for key=$targetKey")
                SyncClipboardBridge.invokeHandler(
                    key = targetKey,
                    data = data,
                    onReply = { bundle -> onReplyAction?.invoke(bundle) }
                )
                return
            }

            val intent = Intent(ACTION_IPC).apply {
                `package` = targetPkg
                putExtra(EXTRA_KEY, targetKey)
            }

            val bundle = Bundle().apply {
                putBundle(EXTRA_PAYLOAD, data)
                putBinder(EXTRA_CALLBACK, object : IBridgeCallback.Stub() {
                    override fun onReply(res: Bundle) {
                        onReplyAction?.invoke(res)
                    }
                })
            }
            context.sendBroadcast(intent.putExtras(bundle))
        }
    }
}
