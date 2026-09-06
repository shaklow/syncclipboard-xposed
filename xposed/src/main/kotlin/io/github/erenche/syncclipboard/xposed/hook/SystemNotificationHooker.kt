package io.github.erenche.syncclipboard.xposed.hook

import android.app.Notification
import android.content.Context
import android.content.Intent
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.libxposed.api.XposedInterface

/**
 * system_server 进程的通知截获器。
 *
 * 钩住 NotificationManagerService.enqueueNotificationInternal——所有 App 通知必经的入队点，
 * 不依赖 App 进程 / NotificationListenerService 是否存活。
 *
 *  system_server 只做最轻的判定与搬运，正则/提取全部留在 SystemUI 引擎：
 *  1. 拼接一次正文（仅在确有文本字段时）；
 *  2. 短窗去重（同一条通知重复入队 / 同正文重复出现，5s 内只转发一次）；
 *  3. 快速筛选（仅 indexOf 关键词 + 连续≥4 位数字，无正则）；
 *  4. 通过则把「原始正文 + pkg」广播给 SystemUI 引擎，由引擎做 contains/extract/上传。
 *
 * 进程与热重载：system_server 中由 ModuleEntry.onSystemServerStarting 安装；
 * 热重载后在 onHotReloaded 中重装。旧代钩子由框架默认卸载。
 */
object SystemNotificationHooker {
    private const val TAG = "NotifHook"
    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    /** 关键词（与引擎提取器的提示词保持近似覆盖，但这里不用正则） */
    private val CODE_KEYWORDS = arrayOf(
        "验证码", "动态码", "授权码", "校验码", "验证代码", "动态口令", "口令",
        "一次性", "确认码", "安全码", "代码", "verification", "passcode",
        "security", "otp", "code",
    )

    private const val BODY_DEDUP_MS = 5 * 1000L
    private const val BODY_DEDUP_MAX = 256

    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()
    private val recentBodies = HashMap<String, Long>()
    @Volatile private var installed = false
    @Volatile private var systemContext: Context? = null

    /** 开机时 onSystemServerStarting 提供的加载器（可加载 NMS），用于跨热重载传递复用 */
    @Volatile
    var bootClassLoader: ClassLoader? = null
        private set

    /** 安装 NMS 钩子（幂等）。@param classLoader 可为 null，将按顺序查找可加载 NMS 的加载器 */
    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader?) {
        if (installed) return
        try {
            if (classLoader != null) bootClassLoader = classLoader
            val loader = classLoader ?: findNmsLoader()
            val nmsClass = Class.forName(NMS_CLASS, false, loader)
            var hookedAny = false
            nmsClass.declaredMethods
                .filter { m ->
                    m.name == "enqueueNotificationInternal" &&
                        m.parameterTypes.any { it == Notification::class.java }
                }
                .forEach { method ->
                    hookHandles.add(
                        module.hook(method)
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .intercept { chain -> captureAndProceed(chain) }
                    )
                    hookedAny = true
                }
            if (!hookedAny) {
                Logger.warn(TAG, "enqueueNotificationInternal(Notification) not found, hook skipped")
                return
            }
            installed = true
            Logger.info(TAG, "NMS enqueueNotificationInternal hooked (system_server)")
        } catch (e: Throwable) {
            Logger.warn(TAG, "Failed to hook NMS: ${e.message}")
        }
    }

    /** 卸载 NMS 钩子（热重载拆除时调用） */
    @Synchronized
    fun unhook() {
        hookHandles.forEach { runCatching { it.unhook() } }
        hookHandles.clear()
        installed = false
    }

    private fun captureAndProceed(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        try {
            val args = chain.args ?: return result
            val notifIdx = chain.executable.parameterTypes.indexOfFirst { it == Notification::class.java }
            if (notifIdx !in args.indices) return result
            val notif = args[notifIdx] as? Notification ?: return result
            // enqueueNotificationInternal 的首个 String 参数为来源包名
            val pkg = (args.firstOrNull { it is String } as? String) ?: "?"
            handleNotification(pkg, notif)
        } catch (_: Throwable) {
        }
        return result
    }

    private fun handleNotification(pkg: String, notif: Notification) {
        // 排除系统自身的通知，避免自环与无意义处理
        if (pkg == PackageNames.SYSTEM_UI || pkg == PackageNames.ANDROID || pkg == PackageNames.APPLICATION) return

        val body = buildBody(notif)
        if (body.isBlank()) return
        // 短窗去重：同正文重复入队/重复出现，5s 内只处理一次
        if (!claimBody(pkg, body)) return
        // 廉价快筛（无正则）：命中关键词且含连续≥4 位数字才转发
        if (!fastLooksLikeCode(body)) return

        forwardToEngine(body, pkg)
    }

    /** 拼接通知正文（title/text/bigText/多行/ticker） */
    private fun buildBody(notif: Notification): String = try {
        val extras = notif.extras ?: return ""
        val sb = StringBuilder(128)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!title.isNullOrBlank()) sb.append(title).append(' ')
        if (!text.isNullOrBlank()) sb.append(text).append(' ')
        if (!bigText.isNullOrBlank()) sb.append(bigText).append(' ')
        if (lines != null) {
            lines.forEach { if (!it.isNullOrBlank()) sb.append(it).append(' ') }
        }
        notif.tickerText?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        sb.toString().trim()
    } catch (_: Throwable) {
        ""
    }

    /** 正文级短窗去重（同步、定容） */
    @Synchronized
    private fun claimBody(pkg: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        if (recentBodies.size > BODY_DEDUP_MAX) {
            val it = recentBodies.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > BODY_DEDUP_MS) it.remove()
            }
        }
        val key = "$pkg\u0001${body.length}\u0001${body.hashCode()}"
        val last = recentBodies[key]
        if (last != null && now - last < BODY_DEDUP_MS) return false
        recentBodies[key] = now
        return true
    }

    /** 廉价快筛：无正则，仅“连续≥4 位数字 + indexOf 关键词”（先扫数字可跳过绝大多数无关通知的分配） */
    private fun fastLooksLikeCode(body: String): Boolean {
        var run = 0
        for (c in body) {
            if (c in '0'..'9') {
                if (++run >= 4) break
            } else {
                run = 0
            }
        }
        if (run < 4) return false
        val lower = body.lowercase()
        for (kw in CODE_KEYWORDS) {
            if (lower.contains(kw)) return true
        }
        return false
    }

    private fun forwardToEngine(body: String, pkg: String) {
        val ctx = systemServerContext() ?: return
        try {
            val intent = Intent(BridgeKeys.ACTION_NOTIF_CAPTURE).apply {
                putExtra(BridgeKeys.EXTRA_NOTIF_BODY, body)
                putExtra(BridgeKeys.EXTRA_NOTIF_PKG, pkg)
            }
            sendBroadcastAsUser(ctx, intent)
            Logger.info(TAG, "body (len=${body.length}) from $pkg broadcast sent")
        } catch (e: Throwable) {
            Logger.warn(TAG, "forward failed: ${e.message}")
        }
    }

    /** 系统进程发送广播必须指定用户（system_server 的 system context 无用户限定，否则广播不投递） */
    private fun sendBroadcastAsUser(ctx: Context, intent: Intent) {
        try {
            // sendBroadcastAsUser 与 UserHandle.SYSTEM 均为 @hide，编译期不可见；
            // system_server 无 hidden API 限制，反射调用即可
            val uhClass = android.os.UserHandle::class.java
            val systemUser = uhClass.getField("SYSTEM").get(null) as android.os.UserHandle
            val method = Context::class.java.getMethod(
                "sendBroadcastAsUser",
                Intent::class.java,
                android.os.UserHandle::class.java
            )
            method.invoke(ctx, intent, systemUser)
        } catch (e: Throwable) {
            // 反射失败时退回无用户限定的广播（部分版本仍会投递）
            ctx.sendBroadcast(intent)
        }
    }

    /** 获取 system_server 的 system context（ActivityThread.systemMain 的 systemContext），惰性缓存 */
    private fun systemServerContext(): Context? {
        systemContext?.let { return it }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
                ?: return null
            val method = atClass.getDeclaredMethod("getSystemContext")
            method.isAccessible = true
            val ctx = method.invoke(at) as? Context
            if (ctx != null) systemContext = ctx
            ctx
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 查找可加载 NMS 的类加载器。
     *
     * NMS（com.android.server.*）不在 boot classpath，而在 system_server 进程自身的应用
     * 类加载器（services.jar 等）里；模块类加载器的父链可能只到 BootClassLoader，钩不上。
     * 依次尝试：getSystemClassLoader（system_server 应用加载器）→ 模块类加载器父链 → 兜底。
     */
    private fun findNmsLoader(): ClassLoader {
        try {
            val sys = ClassLoader.getSystemClassLoader()
            if (canLoadNms(sys)) return sys
        } catch (_: Throwable) {
        }
        var loader = SystemNotificationHooker::class.java.classLoader
        while (loader != null) {
            try {
                if (canLoadNms(loader)) return loader
            } catch (_: Throwable) {
            }
            loader = loader.parent
        }
        return ClassLoader.getSystemClassLoader()
    }

    private fun canLoadNms(loader: ClassLoader): Boolean = try {
        Class.forName(NMS_CLASS, false, loader)
        true
    } catch (_: Throwable) {
        false
    }
}
