package io.github.erenche.syncclipboard.xposed.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SparseIntArray
import androidx.core.content.ContextCompat
import io.github.erenche.syncclipboard.bridge.BridgeSecurity
import io.github.erenche.syncclipboard.bridge.IBridgeCallback
import io.github.erenche.syncclipboard.bridge.InternetAccessBridge
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method

/**
 * 在 system_server 中为 SystemUI 补齐 INTERNET 权限。
 *
 * 部分 ROM 的 SystemUI manifest 未声明 INTERNET，因此同一个 socket 请求会在两处被拒绝：
 * - `PermissionMonitor` 把 SystemUI 的 appId 以 `PERMISSION_NONE` 写入 netd 的 uid 权限表，
 *   eBPF `cgroupsock/inet_create` 程序据此拒绝创建 socket；
 * - `PackageManager.getPackageGids` 不返回 `AID_INET(3003)`，zygote fork 出的进程没有
 *   inet 补充组，内核 paranoid-network 检查同样拒绝 `socket()`（连 127.0.0.1 也不行）。
 * INTERNET 是 normal 权限，`pm grant` 无效，也无法在进程运行中追加补充组，所以只能在
 * system_server 里补：
 *
 * 1. 拦截 `PermissionMonitor.sendAppIdsTrafficPermission`（旧版为
 *    `sendPackagePermissionsToNetd`），为 SystemUI 的 appId 补上 INTERNET 权限位；
 * 2. 拦截 `PackageManager.getPackageGids`（Android 14+ 的实现在
 *    `IPackageManagerBase`），为 `com.android.systemui` 追加 3003，
 *    AMS/ProcessList 启动进程时据此生成 zygote 的 `--setgroups`；
 * 3. 拦截 `checkUidPermission`，让 Java 层的 INTERNET 检查同样返回 GRANTED。
 *
 * 不修改 APK / packages.xml，只在内存中生效；关闭开关后重启 SystemUI 即恢复原状。
 * 仅对 `com.android.systemui` 生效，配置默认开启（与 App 侧默认值一致）。
 */
object SystemUiInternetHooker {
    private const val TAG = "InetHook"
    private const val INTERNET = "android.permission.INTERNET"
    private const val PMS_CLASS = "com.android.server.pm.PackageManagerService"
    private const val SYSTEM_SERVICE_MANAGER_CLASS = "com.android.server.SystemServiceManager"
    private const val PERMISSION_MONITOR_CLASS = "com.android.server.connectivity.PermissionMonitor"
    // Connectivity 模块经 jarjar 打包，部分 ROM 上内部类名带此前缀。
    private const val JARJAR_PREFIX = "android.net.connectivity."
    // ActivityManager 就绪后系统 Context 与 AMS 才可安全注册广播接收器。
    private const val BOOT_PHASE_ACTIVITY_MANAGER_READY = 550
    private val PERMISSION_CLASSES = listOf(
        // Android 14+ 的 binder 实现位于 IPackageManagerBase，方法为 final。
        "com.android.server.pm.IPackageManagerBase",
        PMS_CLASS,
        "com.android.server.pm.permission.PermissionManagerService",
        "com.android.server.pm.permission.PermissionManagerServiceImpl",
    )
    // 新版本用 sendAppIdsTrafficPermission，部分 Android 15/旧版用
    // sendUidsTrafficPermission 或 sendPackagePermissionsToNetd。
    private val TRAFFIC_WRITERS = listOf(
        "sendAppIdsTrafficPermission",
        "sendAppIdTrafficPermission",
        "sendUidsTrafficPermission",
        "sendPackagePermissionsToNetd",
    )
    private val settingStore = ToggleSettingStore(
        File("/data/system/syncclipboard_internet_access"),
        defaultEnabled = true
    )

    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val hookedClasses = HashSet<Class<*>>()
    private var module: XposedInterface? = null
    @Volatile private var running = false
    @Volatile private var gidsHookInstalled = false
    @Volatile private var permissionHookInstalled = false
    @Volatile private var trafficHookInstalled = false
    @Volatile private var context: Context? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile private var systemUiUid = -1
    @Volatile private var loggedGrant = false
    @Volatile private var loggedPermission = false
    @Volatile private var loggedTraffic = false
    @Volatile private var trafficRefreshed = false
    @Volatile private var monitor: Any? = null
    @Volatile private var trafficWriter: Method? = null
    @Volatile private var observedTrafficMask = 0
    private val enabled: Boolean get() = settingStore.enabled

    @Synchronized
    fun install(xposed: XposedInterface, loader: ClassLoader?, saved: Map<*, *>? = null) {
        if (running) return
        running = true
        module = xposed
        runCatching { settingStore.load() }
            .onFailure { Logger.warn(TAG, "Cannot load INTERNET setting; override disabled: ${it.message}") }
        attachContext(saved?.get("context") as? Context ?: systemContext())
        installHooks(loader)
        Logger.info(TAG, "Installed SystemUI INTERNET hook, enabled=$enabled, gids=$gidsHookInstalled, traffic=$trafficHookInstalled")
    }

    /** 热重载仅传递系统对象，避免保留旧模块类。 */
    @Synchronized
    fun detachForReload(): Map<String, Any> {
        running = false
        receiver?.let { r -> runCatching { context?.unregisterReceiver(r) } }
        receiver = null
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
        hookedClasses.clear()
        gidsHookInstalled = false
        permissionHookInstalled = false
        trafficHookInstalled = false
        trafficRefreshed = false
        monitor = null
        trafficWriter = null
        return hashMapOf<String, Any>().apply { context?.let { put("context", it) } }
    }

    @Synchronized
    private fun installHooks(loader: ClassLoader?) {
        if (!running) return
        val loaders = listOfNotNull(loader, ClassLoader.getSystemClassLoader(), javaClass.classLoader).distinct()
        PERMISSION_CLASSES.forEach { name ->
            loadClass(name, loaders)?.let { hookPermissionClass(it) }
        }
        loadClass(PERMISSION_MONITOR_CLASS, loaders)?.let { hookPermissionMonitor(it) }
        installServiceLoaderHook(loaders)
        installBootPhaseHook(loaders)
        // 热重载时 ConnectivityService 已存在，直接取现场实例补一次权限。
        discoverMonitor()
        refreshTrafficPermission()
        if (!gidsHookInstalled) Logger.warn(TAG, "PackageManager.getPackageGids not hooked; SystemUI gids cannot be supplemented")
        if (!trafficHookInstalled) Logger.warn(TAG, "PermissionMonitor not hooked; netd INTERNET permission cannot be granted")
    }

    @Synchronized
    private fun hookPermissionClass(clazz: Class<*>) {
        val xposed = module ?: return
        if (!running || !hookedClasses.add(clazz)) return
        for (method in clazz.declaredMethods) {
            when {
                method.name == "getPackageGids" &&
                    method.returnType == IntArray::class.java &&
                    method.parameterTypes.firstOrNull() == String::class.java -> hookGids(method, xposed)
                method.name == "checkUidPermission" &&
                    method.returnType == Int::class.javaPrimitiveType -> hookPermissionCheck(method, xposed)
            }
        }
    }

    private fun hookGids(method: Method, xposed: XposedInterface) {
        runCatching {
            handles += xposed.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (!running || !enabled) return@intercept result
                if (chain.args.firstOrNull() != PackageNames.SYSTEM_UI) return@intercept result
                val gids = result as? IntArray ?: return@intercept result
                if (gids.contains(SystemUiProcess.AID_INET)) return@intercept result
                if (!loggedGrant) {
                    loggedGrant = true
                    Logger.info(TAG, "Supplying AID_INET(${SystemUiProcess.AID_INET}) to SystemUI gids, existing=${gids.joinToString()}")
                }
                gids + SystemUiProcess.AID_INET
            }
            gidsHookInstalled = true
            Logger.info(TAG, "Hooked ${method.declaringClass.name}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
        }.onFailure { Logger.warn(TAG, "Cannot hook ${method.declaringClass.name}.${method.name}: ${it.message}") }
    }

    private fun hookPermissionCheck(method: Method, xposed: XposedInterface) {
        val permissionIndex = method.parameterTypes.indexOf(String::class.java)
        val uidIndex = method.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType }
        if (permissionIndex < 0 || uidIndex < 0) return
        runCatching {
            handles += xposed.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (!running || !enabled || result == PackageManager.PERMISSION_GRANTED) return@intercept result
                if (chain.args[permissionIndex] != INTERNET) return@intercept result
                val uid = chain.args[uidIndex] as? Int ?: return@intercept result
                if (!isSystemUiUid(uid)) return@intercept result
                if (!loggedPermission) {
                    loggedPermission = true
                    Logger.info(TAG, "Granting INTERNET to SystemUI uid=$uid in permission check")
                }
                PackageManager.PERMISSION_GRANTED
            }
            permissionHookInstalled = true
        }.onFailure { Logger.warn(TAG, "Cannot hook ${method.declaringClass.name}.${method.name}: ${it.message}") }
    }

    /** PermissionMonitor 写入 netd 前补 INTERNET 位；优先安装应在 ConnectivityService 构造之前。 */
    @Synchronized
    private fun hookPermissionMonitor(clazz: Class<*>) {
        val xposed = module ?: return
        if (!running || !hookedClasses.add(clazz)) return
        clazz.declaredMethods.filter {
            it.name in TRAFFIC_WRITERS && it.parameterTypes.contentEquals(arrayOf(SparseIntArray::class.java))
        }.forEach { method -> hookTrafficWriter(method, xposed) }
        clazz.declaredConstructors.forEach { constructor ->
            runCatching {
                handles += xposed.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    monitor = chain.thisObject
                    refreshTrafficPermission()
                    result
                }
            }.onFailure { Logger.warn(TAG, "Cannot hook PermissionMonitor constructor: ${it.message}") }
        }
        if (!trafficHookInstalled) {
            val methods = clazz.declaredMethods.joinToString { m ->
                "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
            }
            Logger.warn(TAG, "PermissionMonitor traffic permission writer not found; methods=$methods")
        }
    }

    private fun hookTrafficWriter(method: Method, xposed: XposedInterface) {
        runCatching {
            handles += xposed.hook(method).intercept { chain ->
                val permissions = chain.args.getOrNull(0) as? SparseIntArray
                if (running && enabled && permissions != null) supplementTrafficPermission(permissions)
                chain.proceed()
            }
            trafficHookInstalled = true
            trafficWriter = method.apply { isAccessible = true }
            trafficRefreshed = false
            Logger.info(TAG, "Hooked ${method.declaringClass.name}.${method.name}")
        }.onFailure { Logger.warn(TAG, "Cannot hook ${method.declaringClass.name}.${method.name}: ${it.message}") }
    }

    /** 只修改 SystemUI 的条目；兼容 appId 与完整 uid 两种键。 */
    private fun supplementTrafficPermission(permissions: SparseIntArray) {
        val target = resolveSystemUiUid()
        if (target < 0) return
        val appId = target % SystemUiPermissionPolicy.PER_USER_RANGE
        for (i in 0 until permissions.size()) {
            val key = permissions.keyAt(i)
            if (!SystemUiPermissionPolicy.isTargetUid(key, appId)) continue
            val original = permissions.valueAt(i)
            observedTrafficMask = original.coerceAtLeast(0)
            val adjusted = SystemUiPermissionPolicy.supplementMask(original, enabled)
            if (adjusted == original) continue
            permissions.put(key, adjusted)
            if (!loggedTraffic) {
                loggedTraffic = true
                Logger.info(TAG, "Supplying INTERNET to SystemUI netd permission, appId=$appId, original=$original")
            }
        }
    }

    /** 开关变化后让 PermissionMonitor 在自身线程重发 SystemUI 的权限；无需重启 netd。 */
    private fun refreshTrafficPermission() {
        if (!running) return
        discoverMonitor()
        val instance = monitor ?: return
        val writer = trafficWriter ?: return
        val target = resolveSystemUiUid()
        if (target < 0) return
        val appId = target % SystemUiPermissionPolicy.PER_USER_RANGE
        // sendUidsTrafficPermission / sendPackagePermissionsToNetd 以完整 uid 为键，
        // sendAppIdsTrafficPermission 以 appId 为键。
        val key = if (writer.name.contains("Uids") || writer.name.contains("Package")) target else appId
        val permissions = SparseIntArray().apply { put(key, readTrafficPermission(instance, target)) }
        val task = Runnable {
            if (!running) return@Runnable
            runCatching { writer.invoke(instance, permissions) }
                .onSuccess {
                    trafficRefreshed = true
                    Logger.info(TAG, "Refreshed SystemUI netd INTERNET permission, appId=$appId, enabled=$enabled")
                }
                .onFailure { Logger.warn(TAG, "Cannot refresh netd INTERNET permission: ${it.cause?.message ?: it.message}") }
        }
        val looper = monitorLooper(instance)
        if (looper != null && looper.thread !== Thread.currentThread()) Handler(looper).post(task) else task.run()
    }

    private fun readTrafficPermission(instance: Any, uid: Int): Int = runCatching {
        val method = declareMethods(instance.javaClass).firstOrNull {
            it.name in setOf("getTrafficPermissionForUid", "getPermissionForUid") &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                it.returnType == Int::class.javaPrimitiveType
        } ?: error("PermissionMonitor getter unavailable")
        method.isAccessible = true
        (method.invoke(instance, uid) as Int).takeIf { it >= 0 } ?: observedTrafficMask
    }.getOrDefault(observedTrafficMask)

    private fun declareMethods(clazz: Class<*>): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            methods += current.declaredMethods
            current = current.superclass
        }
        return methods
    }

    private fun monitorLooper(instance: Any): Looper? = runCatching {
        val thread = field(instance, "mThread") ?: return null
        thread.javaClass.getMethod("getLooper").invoke(thread) as? Looper
    }.getOrNull()

    /** 热重载或延迟安装时，从 ConnectivityService 取现有 PermissionMonitor 实例。 */
    @Synchronized
    private fun discoverMonitor() {
        if (!running) return
        if (monitor != null && trafficWriter != null) return
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val service = serviceManager.getDeclaredMethod("getService", String::class.java)
                .invoke(null, "connectivity") ?: return
            val live = field(service, "mPermissionMonitor") ?: return
            live.javaClass.classLoader?.let { hookPermissionMonitor(live.javaClass) }
            monitor = live
        }.onFailure { Logger.warn(TAG, "PermissionMonitor discovery: ${it.message}") }
    }

    /** Connectivity 使用 APEX 类加载器，需在服务加载时安装钩子。 */
    private fun installServiceLoaderHook(loaders: List<ClassLoader>) {
        val xposed = module ?: return
        val clazz = loadClass(SYSTEM_SERVICE_MANAGER_CLASS, loaders) ?: return
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "startService" && it.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: return
        runCatching {
            handles += xposed.hook(method).intercept { chain ->
                // ConnectivityService 构造 PermissionMonitor 之前安装钩子。
                val serviceClass = chain.args.firstOrNull() as? Class<*>
                val loader = serviceClass?.classLoader
                if (serviceClass?.name?.contains("Connectivity") == true && loader != null) {
                    findClass(PERMISSION_MONITOR_CLASS, listOf(loader))?.let { hookPermissionMonitor(it) }
                }
                val result = chain.proceed()
                if (serviceClass?.name?.contains("Connectivity") == true) {
                    // AMS 此时已就绪，可安全注册开关接收器并补一次权限。
                    attachContext(systemContext())
                    if (!trafficRefreshed) refreshTrafficPermission()
                }
                result
            }
        }.onFailure { Logger.warn(TAG, "Cannot hook SystemServiceManager.startService: ${it.message}") }
    }

    /** 在 AMS 就绪后注册开关接收器；此时系统 Context 与 PackageManager 均可用。 */
    private fun installBootPhaseHook(loaders: List<ClassLoader>) {
        val xposed = module ?: return
        val clazz = loadClass(SYSTEM_SERVICE_MANAGER_CLASS, loaders) ?: return
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "startBootPhase" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return
        runCatching {
            handles += xposed.hook(method).intercept { chain ->
                val result = chain.proceed()
                val phase = chain.args.firstOrNull() as? Int ?: 0
                if (phase >= BOOT_PHASE_ACTIVITY_MANAGER_READY) {
                    attachContext(systemContext())
                    discoverMonitor()
                    if (!trafficRefreshed) refreshTrafficPermission()
                }
                result
            }
        }.onFailure { Logger.warn(TAG, "Cannot hook SystemServiceManager.startBootPhase: ${it.message}") }
    }

    @Synchronized
    private fun attachContext(candidate: Context?) {
        if (!running) return
        val ctx = candidate ?: return
        context = ctx
        resolveSystemUiUid()
        if (receiver != null) return
        runCatching {
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (!running || intent.action != InternetAccessBridge.ACTION ||
                        !BridgeSecurity.isTrustedSender(ctx, this, SyncClipboardBridge.PERMISSION_BRIDGE)) return
                    if (intent.hasExtra(InternetAccessBridge.EXTRA_ENABLED)) {
                        val requested = intent.getBooleanExtra(InternetAccessBridge.EXTRA_ENABLED, false)
                        runCatching { settingStore.update(requested) }
                            .onFailure { Logger.warn(TAG, "Cannot persist INTERNET setting: ${it.message}") }
                        loggedGrant = false
                        Logger.info(TAG, "SystemUI uid=$systemUiUid INTERNET override enabled=$enabled")
                        refreshTrafficPermission()
                    }
                    intent.extras?.getBinder(InternetAccessBridge.EXTRA_CALLBACK)
                        ?.let { IBridgeCallback.Stub.asInterface(it) }
                        ?.let { callback -> runCatching { callback.onReply(status()) } }
                }
            }
            // onReceive 中的 Binder UID 不能标识发送方，使用签名权限限制广播来源。
            ContextCompat.registerReceiver(ctx, r, IntentFilter(InternetAccessBridge.ACTION),
                SyncClipboardBridge.PERMISSION_BRIDGE, null, ContextCompat.RECEIVER_EXPORTED)
            receiver = r
            Logger.info(TAG, "INTERNET setting receiver ready, SystemUI uid=$systemUiUid, enabled=$enabled")
        }.onFailure { Logger.warn(TAG, "INTERNET setting receiver not ready: ${it.message}") }
    }

    private fun status(): Bundle = Bundle().apply {
        putBoolean("running", running)
        putBoolean("enabled", enabled)
        putBoolean("storageFailed", settingStore.storageFailed)
        // SystemUI 联网需要 netd 权限位与 inet 补充组同时就绪。
        putBoolean("hooks", gidsHookInstalled && trafficHookInstalled)
        putBoolean("gidsHook", gidsHookInstalled)
        putBoolean("trafficHook", trafficHookInstalled)
        putBoolean("permissionHook", permissionHookInstalled)
    }

    private fun isSystemUiUid(uid: Int): Boolean {
        val target = resolveSystemUiUid()
        return target >= 0 && SystemUiPermissionPolicy.isTargetUid(uid, target % SystemUiPermissionPolicy.PER_USER_RANGE)
    }

    private fun resolveSystemUiUid(): Int {
        val cached = systemUiUid
        if (cached >= 0) return cached
        val uid = resolveSystemUiUidFromContext() ?: resolveSystemUiUidFromPackageManager() ?: return -1
        if (uid < 0) return -1
        systemUiUid = uid
        return uid
    }

    private fun resolveSystemUiUidFromContext(): Int? = context?.let { ctx ->
        runCatching { ctx.packageManager.getApplicationInfo(PackageNames.SYSTEM_UI, 0).uid }.getOrNull()
    }

    private fun resolveSystemUiUidFromPackageManager(): Int? = runCatching {
        val globals = Class.forName("android.app.AppGlobals")
        val pm = globals.getDeclaredMethod("getPackageManager").invoke(null) ?: return null
        val method = pm.javaClass.methods.firstOrNull {
            it.name == "getPackageUid" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        } ?: return null
        val flags: Any = if (method.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
        method.invoke(pm, PackageNames.SYSTEM_UI, flags, 0) as Int
    }.getOrNull()

    private fun loadClass(name: String, loaders: List<ClassLoader>): Class<*>? = findClass(name, loaders)

    private fun findClass(name: String, loaders: List<ClassLoader>): Class<*>? {
        for (candidate in arrayOf(name, JARJAR_PREFIX + name)) {
            for (loader in loaders) {
                runCatching { Class.forName(candidate, false, loader) }.getOrNull()?.let { return it }
            }
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
        val instance = thread.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
        thread.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(instance) as? Context
    }.getOrNull()
}
