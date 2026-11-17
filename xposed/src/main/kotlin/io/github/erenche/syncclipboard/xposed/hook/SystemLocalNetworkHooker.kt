package io.github.erenche.syncclipboard.xposed.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SparseIntArray
import io.github.erenche.syncclipboard.bridge.LocalNetworkAccessBridge
import io.github.erenche.syncclipboard.bridge.BridgeSecurity
import io.github.erenche.syncclipboard.bridge.IBridgeCallback
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 在 BPF 权限下发时补充 SystemUI 的局域网权限，兼容 appId 流量表和 UID 分块表。
 * 仅修改局域网权限位；Java 权限查询结果无法解除内核限制。
 */
object SystemLocalNetworkHooker {
    private const val TAG = "LocalNetHook"
    private const val BPF_CLASS = "com.android.server.BpfNetMaps"
    private const val MONITOR_CLASS = "com.android.server.connectivity.PermissionMonitor"
    // Connectivity 模块经 jarjar 打包，部分 ROM 上内部类名带此前缀。
    private const val JARJAR_PREFIX = "android.net.connectivity."
    // system_server 的系统 Context 没有数据目录，不能用 SharedPreferences。
    private val settingStore = ToggleSettingStore(File("/data/system/syncclipboard_local_network"))

    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val hookedClasses = HashSet<Class<*>>()
    private val baselines = ConcurrentHashMap<String, Int>()
    private val internalWrite = ThreadLocal<Boolean>()
    private var module: XposedInterface? = null
    @Volatile private var backend: Any? = null
    @Volatile private var context: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var callbacks: Handler? = null
    @Volatile private var writeHandler: Handler? = null
    @Volatile private var running = false
    private val enabled: Boolean get() = settingStore.enabled
    @Volatile private var systemUiUid = -1
    @Volatile private var activeWriter: Writer? = null
    @Volatile private var loggedGrant = false

    private data class Writer(val method: Method, val chunk: Boolean, val localBit: Int, val internetBit: Int)

    @Synchronized
    fun install(xposed: XposedInterface, loader: ClassLoader?, saved: Map<*, *>? = null) {
        if (Build.VERSION.SDK_INT < 37 || running) return
        running = true
        module = xposed
        callbacks = Looper.getMainLooper()?.let { Handler(it) }
        saved?.get("baselines")?.let { values ->
            (values as? Map<*, *>)?.forEach { (key, value) ->
                if (key is String && value is Int) baselines[key] = value
            }
        }
        writeHandler = saved?.get("handler") as? Handler
        val liveBackend = saved?.get("backend")
        if (liveBackend != null) {
            installNetworkHooks(liveBackend.javaClass.classLoader)
            backend = liveBackend
            val writerName = saved["writer"] as? String
            activeWriter = writers(liveBackend.javaClass).firstOrNull { it.method.name == writerName }
        }
        attachContext(saved?.get("context") as? Context ?: systemContext())
        installNetworkHooks(loader)
        installNetworkHooks(ClassLoader.getSystemClassLoader())
        // Connectivity 使用 APEX 类加载器，需在服务加载时安装钩子。
        runCatching {
            val manager = Class.forName("com.android.server.SystemServiceManager", false, loader)
            val startService = manager.getDeclaredMethod("startService", Class::class.java)
            handles += xposed.hook(startService).intercept { chain ->
                val serviceClass = chain.args.firstOrNull() as? Class<*>
                if (serviceClass?.name?.contains("Connectivity") == true) {
                    installNetworkHooks(serviceClass.classLoader)
                }
                chain.proceed()
            }
        }.onFailure { Logger.warn(TAG, "Service-loader hook unavailable: ${it.message}") }
        // 热重载时服务已创建，直接查找现有实例。
        discoverLiveService(0)
        Logger.info(TAG, "Installed Android 17 SystemUI local-network permission hook")
    }

    /** 热重载仅传递系统对象和原始权限，避免保留旧模块类。 */
    @Synchronized
    fun detachForReload(): Map<String, Any> {
        running = false
        callbacks?.removeCallbacksAndMessages(null)
        receiver?.let { r -> runCatching { context?.unregisterReceiver(r) } }
        receiver = null
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
        return hashMapOf<String, Any>("baselines" to HashMap(baselines)).apply {
            backend?.let { put("backend", it) }
            context?.let { put("context", it) }
            writeHandler?.let { put("handler", it) }
            activeWriter?.let { put("writer", it.method.name) }
        }
    }

    @Synchronized
    private fun installNetworkHooks(loader: ClassLoader?) {
        if (!running || loader == null) return
        val bpfClass = findClass(BPF_CLASS, loader) ?: return
        hookBpfClass(bpfClass, loader)
    }

    @Synchronized
    private fun hookBpfClass(bpfClass: Class<*>, loader: ClassLoader?) {
        if (!running) return
        val xposed = module ?: return
        if (!hookedClasses.add(bpfClass)) return
        val available = writers(bpfClass)
        available.forEach { writer ->
            runCatching {
                handles += xposed.hook(writer.method).intercept { chain ->
                    if (!running || internalWrite.get() == true) return@intercept chain.proceed()
                    backend = chain.thisObject
                    activeWriter = writer
                    Looper.myLooper()?.let { writeHandler = Handler(it) }
                    attachContext(context ?: systemContext())
                    if (writer.chunk) interceptChunkWrite(chain, writer) else interceptTrafficWrite(chain, writer)
                }
                Logger.info(TAG, "Hooked ${writer.method.name}, layout=${if (writer.chunk) "uid-chunk" else "appId-traffic"}, localBit=${writer.localBit}")
            }.onFailure { Logger.warn(TAG, "Cannot hook ${writer.method.name}: ${it.message}") }
        }
        if (available.isEmpty()) Logger.warn(TAG, "No supported local-network permission writer; ROM adapter required")
        bpfClass.declaredConstructors.forEach { constructor ->
            runCatching {
                handles += xposed.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    backend = chain.thisObject
                    attachContext(chain.args.firstOrNull { it is Context } as? Context ?: systemContext())
                    result
                }
            }
        }
        findClass(MONITOR_CLASS, loader ?: bpfClass.classLoader)?.declaredConstructors?.forEach { constructor ->
            runCatching {
                handles += xposed.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    attachContext(chain.args.firstOrNull { it is Context } as? Context ?: systemContext())
                    scheduleRefresh()
                    result
                }
            }
        }
    }

    private fun writers(clazz: Class<*>): List<Writer> {
        val loader = clazz.classLoader
        val trafficBit = constant(loader, "TRAFFIC_PERMISSION_ACCESS_LOCAL_NETWORK", "PERMISSION_ACCESS_LOCAL_NETWORK")
        val internetBit = constant(loader, "TRAFFIC_PERMISSION_INTERNET", "PERMISSION_INTERNET") ?: 0
        val chunkBit = runCatching {
            findClass("com.android.net.module.util.bpf.UidPermissionChunk", loader)!!
                .getField("PERMISSION_BIT_ACCESS_LOCAL_NETWORK").getInt(null)
        }.getOrNull()
        return clazz.declaredMethods.mapNotNull { method ->
            val parameters = method.parameterTypes
            when {
                method.name == "setChunkPermListForUids" && parameters.contentEquals(arrayOf(SparseIntArray::class.java)) && chunkBit != null ->
                    Writer(method.apply { isAccessible = true }, true, chunkBit, 0)
                method.name in setOf("setNetPermForUids", "setPermissionForUids") &&
                    parameters.contentEquals(arrayOf(Int::class.javaPrimitiveType, IntArray::class.java)) && trafficBit != null && internetBit != 0 ->
                    Writer(method.apply { isAccessible = true }, false, trafficBit, internetBit)
                else -> null
            }
        }
    }

    private fun constant(loader: ClassLoader?, vararg names: String): Int? {
        for (className in arrayOf("com.android.server.connectivity.NetworkPermissions", "android.net.INetd")) {
            val clazz = findClass(className, loader) ?: continue
            for (name in names) {
                runCatching { clazz.getDeclaredField(name).apply { isAccessible = true }.getInt(null) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun interceptChunkWrite(chain: XposedInterface.Chain, writer: Writer): Any? {
        val permissions = chain.args.firstOrNull() as? SparseIntArray ?: return chain.proceed()
        val target = systemUiUid
        val index = permissions.indexOfKey(target)
        if (target < 0 || index < 0) return chain.proceed()
        val original = permissions.valueAt(index)
        baselines[baselineKey(writer, target)] = original
        val adjusted = LocalNetworkPermissionPolicy.supplement(original, writer.localBit, enabled)
        if (adjusted == original) return chain.proceed()
        // 复制权限表，保留原参数和其他 UID 的权限。
        val copy = permissions.clone().apply { put(target, adjusted) }
        val result = chain.proceed(arrayOf(copy))
        logGrant(writer, target, original, adjusted)
        return result
    }

    private fun interceptTrafficWrite(chain: XposedInterface.Chain, writer: Writer): Any? {
        val original = chain.args.getOrNull(0) as? Int ?: return chain.proceed()
        val ids = chain.args.getOrNull(1) as? IntArray ?: return chain.proceed()
        if (systemUiUid < 0) return chain.proceed()
        val target = LocalNetworkPermissionPolicy.permissionId(systemUiUid, appIdKeyed = true)
        if (target !in ids) return chain.proceed()
        baselines[baselineKey(writer, target)] = original
        val adjusted = LocalNetworkPermissionPolicy.supplement(original, writer.localBit, enabled)
        if ((original >= 0 && (original and writer.internetBit) == 0) || adjusted == original) return chain.proceed()
        val others = LocalNetworkPermissionPolicy.otherIds(ids, target)
        // 批量接口共用掩码，单独处理 SystemUI，避免给其他 UID 授权。
        if (others.isEmpty()) {
            val result = chain.proceed(arrayOf(adjusted, intArrayOf(target)))
            logGrant(writer, target, original, adjusted)
            return result
        }
        val result = chain.proceed(arrayOf(original, others))
        runCatching { write(writer, adjusted, target) }
            .onSuccess { logGrant(writer, target, original, adjusted) }
            .onFailure {
                Logger.warn(TAG, "LAN supplement failed; restoring target's original mask: ${it.message}")
                write(writer, original, target)
            }
        return result
    }

    @Synchronized
    private fun attachContext(candidate: Context?) {
        if (Build.VERSION.SDK_INT < 37 || !running || candidate == null) return
        if (callbacks == null) callbacks = Looper.getMainLooper()?.let { Handler(it) }
        context = candidate
        if (systemUiUid < 0) {
            systemUiUid = runCatching { candidate.packageManager.getApplicationInfo(PackageNames.SYSTEM_UI, 0).uid }.getOrDefault(-1)
        }
        if (receiver != null || systemUiUid < 0) return
        runCatching { settingStore.load() }
            .onFailure { Logger.warn(TAG, "Cannot load LAN setting; override disabled: ${it.message}") }
        runCatching {
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (!running || intent.action != LocalNetworkAccessBridge.ACTION ||
                        !BridgeSecurity.isTrustedSender(ctx, this, SyncClipboardBridge.PERMISSION_BRIDGE)) return
                    val update = intent.hasExtra(LocalNetworkAccessBridge.EXTRA_ENABLED)
                    if (update) {
                        val requested = intent.getBooleanExtra(LocalNetworkAccessBridge.EXTRA_ENABLED, false)
                        runCatching { settingStore.update(requested) }
                            .onFailure { Logger.warn(TAG, "Cannot persist LAN setting: ${it.message}") }
                        Logger.info(TAG, "SystemUI uid=$systemUiUid LAN override enabled=$enabled")
                        loggedGrant = false
                    }
                    val callback = intent.extras?.getBinder(LocalNetworkAccessBridge.EXTRA_CALLBACK)
                        ?.let { IBridgeCallback.Stub.asInterface(it) }
                    scheduleRefresh(callback, refresh = update)
                }
            }
            // onReceive 中的 Binder UID 不能标识发送方，使用签名权限限制广播来源。
            candidate.registerReceiver(r, IntentFilter(LocalNetworkAccessBridge.ACTION),
                SyncClipboardBridge.PERMISSION_BRIDGE, callbacks, Context.RECEIVER_EXPORTED)
            receiver = r
            Logger.info(TAG, "Resolved SystemUI uid=$systemUiUid, enabled=$enabled")
        }.onFailure { Logger.warn(TAG, "LAN setting receiver not ready: ${it.message}") }
    }

    private fun scheduleRefresh(callback: IBridgeCallback? = null, refresh: Boolean = true) {
        val handler = writeHandler
        if (refresh && handler == null) {
            Logger.info(TAG, "LAN refresh pending: waiting for the framework permission writer")
        }
        (handler ?: callbacks)?.post {
            if (!running) return@post
            if (refresh && handler != null) refreshPermission()
            // 补写与回执在同一任务内执行，避免先回复旧状态。
            callback?.let { runCatching { it.onReply(permissionStatus()) } }
        }
    }

    private fun refreshPermission() {
        val writer = activeWriter ?: return
        if (systemUiUid < 0 || backend == null) return
        val target = LocalNetworkPermissionPolicy.permissionId(systemUiUid, appIdKeyed = !writer.chunk)
        runCatching {
            val key = baselineKey(writer, target)
            val original = baselines[key] ?: readCurrentPermission(writer, target).also { baselines[key] = it }
            val adjusted = LocalNetworkPermissionPolicy.supplement(original, writer.localBit, enabled)
            if (!writer.chunk && original >= 0 && original and writer.internetBit == 0) return@runCatching
            write(writer, adjusted, target)
            val actual = readCurrentPermission(writer, target)
            if (actual != adjusted) {
                Logger.warn(TAG, "LAN permission verification failed: id=$target, expected=$adjusted, actual=$actual")
            } else {
                Logger.info(TAG, "LAN permission refreshed: id=$target, original=$original, effective=$actual, enabled=$enabled")
            }
        }.onFailure { Logger.warn(TAG, "Could not refresh LAN permission: ${it.cause?.message ?: it.message}") }
    }

    private fun permissionStatus(): Bundle = Bundle().apply {
        putBoolean("running", running)
        putBoolean("enabled", enabled)
        putBoolean("storageFailed", settingStore.storageFailed)
        putBoolean("backend", backend != null)
        putBoolean("writer", activeWriter != null)
        putBoolean("handler", writeHandler != null)
        val writer = activeWriter
        if (writer != null && writeHandler != null && backend != null && systemUiUid >= 0) {
            runCatching {
                val target = LocalNetworkPermissionPolicy.permissionId(systemUiUid, !writer.chunk)
                val actual = readCurrentPermission(writer, target)
                val granted = actual >= 0 && actual and writer.localBit != 0
                putBoolean("granted", granted)
                putBoolean("verified", if (enabled) granted else baselines[baselineKey(writer, target)] == actual)
            }.onFailure { putBoolean("readbackFailed", true) }
        }
    }

    private fun readCurrentPermission(writer: Writer, target: Int): Int {
        val instance = backend ?: error("BPF service unavailable")
        if (writer.chunk) {
            return instance.javaClass.getMethod("getChunkPermForUid", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(instance, target) as Int
        }
        val map = field(instance, "sUidPermissionMap") ?: error("UID permission map unavailable")
        val keyClass = findClass("com.android.net.module.util.Struct\$S32", instance.javaClass.classLoader)
            ?: error("Struct.S32 unavailable")
        val key = keyClass.getConstructor(Int::class.javaPrimitiveType).newInstance(target)
        val getter = map.javaClass.methods.first { it.name == "getValue" && it.parameterCount == 1 }
        val value = getter.invoke(map, key) ?: return writer.internetBit // 流量表无条目时默认具有 INTERNET 权限。
        return (value.javaClass.getField("val").get(value) as Number).toInt()
    }

    private fun write(writer: Writer, permissions: Int, target: Int) {
        val instance = backend ?: return
        internalWrite.set(true)
        try {
            if (writer.chunk) writer.method.invoke(instance, SparseIntArray().apply { put(target, permissions) })
            else writer.method.invoke(instance, permissions, intArrayOf(target))
        } finally {
            internalWrite.remove()
        }
    }

    private fun baselineKey(writer: Writer, target: Int): String = "${if (writer.chunk) "chunk" else "traffic"}:$target"

    private fun logGrant(writer: Writer, target: Int, original: Int, adjusted: Int) {
        if (loggedGrant) return
        runCatching { readCurrentPermission(writer, target) }
            .onSuccess { actual ->
                if (actual == adjusted) {
                    loggedGrant = true
                    Logger.info(TAG, "Applied SystemUI LAN permission: id=$target, writer=${writer.method.name}, original=$original, effective=$actual")
                } else {
                    Logger.warn(TAG, "LAN permission verification failed: id=$target, expected=$adjusted, actual=$actual")
                }
            }
            .onFailure { Logger.warn(TAG, "LAN permission submitted; readback unavailable: ${it.cause?.message ?: it.message}") }
    }

    private fun discoverLiveService(attempt: Int) {
        callbacks?.postDelayed({
            if (!running) return@postDelayed
            runCatching {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val service = serviceManager.getDeclaredMethod("getService", String::class.java).invoke(null, "connectivity")
                if (service != null) {
                    installNetworkHooks(service.javaClass.classLoader)
                    attachContext(field(service, "mContext") as? Context ?: systemContext())
                    val live = field(service, "mBpfNetMaps")
                    if (live != null) {
                        backend = live
                        // 按实例的真实类挂钩，不依赖类名（jarjar 可能改名）。
                        Logger.info(TAG, "Connectivity BPF backend: ${live.javaClass.name}")
                        hookBpfClass(live.javaClass, live.javaClass.classLoader)
                        // 开机时可能错过框架写入，借 ConnectivityService 的线程补写。
                        if (writeHandler == null) writeHandler = field(service, "mHandler") as? Handler ?: callbacks
                        // 根据运行时开关选择实际使用的权限表。
                        val chunk = runCatching { live.javaClass.getMethod("isUidMigrationEnabled").invoke(live) as Boolean }.getOrNull()
                        val available = writers(live.javaClass)
                        val detected = chunk?.let { migrated -> available.firstOrNull { it.chunk == migrated } }
                        // ROM 可能重命名开关接口，此时保留实际调用中识别的写入接口。
                        activeWriter = detected ?: activeWriter ?: available.singleOrNull()
                        // 沿用权限写入线程，避免分块表并发更新。
                        scheduleRefresh()
                    }
                }
            }.onFailure { Logger.warn(TAG, "Connectivity discovery: ${it.message}") }
            if (backend == null && attempt < 5) discoverLiveService(attempt + 1)
            else if (backend == null) Logger.warn(TAG, "Connectivity BPF backend not found; reboot and check system_server scope")
        }, if (attempt == 0) 1_000L else 5_000L)
    }

    private fun findClass(name: String, loader: ClassLoader?): Class<*>? {
        for (candidate in arrayOf(name, JARJAR_PREFIX + name)) {
            runCatching { Class.forName(candidate, false, loader) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun field(instance: Any, name: String): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            val current = clazz
            runCatching { current.getDeclaredField(name).apply { isAccessible = true }.get(instance) }
                .getOrNull()?.let { return it }
            clazz = current.superclass
        }
        return null
    }

    private fun systemContext(): Context? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getDeclaredMethod("currentActivityThread").invoke(null)
        thread.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(instance) as? Context
    }.getOrNull()
}
