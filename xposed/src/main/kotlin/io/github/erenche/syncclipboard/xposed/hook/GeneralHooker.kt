package io.github.erenche.syncclipboard.xposed.hook

import android.app.Application
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.sync.SyncEngine
import io.github.libxposed.api.XposedModule

/**
 * GeneralHooker — 在所有作用域包中运行的通用 Hook。
 *
 * SyncEngine 仅在 SystemUI 进程中初始化：
 * - SystemUI（com.android.systemui）始终在前台运行（状态栏），
 *   不会被系统冻结，OnPrimaryClipChangedListener 持续有效
 * - 是标准 Application 生命周期，doOnAppCreated 可靠触发
 * - bridge 广播能被 SystemUI 正常接收
 *
 * App 进程不初始化 SyncEngine，通过 bridge 向 SystemUI 查询状态。
 */
object GeneralHooker : PackageHooker() {
    const val TAG = "GeneralHooker"

    override fun onHook() {
        Logger.info(TAG, "onHook() called, packageName=$packageName, isMainProcess=${isMainProcess()}")

        // 允许明文 HTTP（局域网自建服务器 http:// 场景）：
        // SystemUI 进程的网络安全策略默认禁止 cleartext，Ktor/OkHttp 会直接抛异常，
        // 钩住 NetworkSecurityPolicy 让 HTTP 服务器可用
        hookCleartextTrafficPermitted(module)

        doOnAppCreated { app ->
            Logger.info(TAG, "App created: ${app.packageName}")

            // 仅在 SystemUI 中初始化 SyncEngine
            if (app.packageName == PackageNames.SYSTEM_UI) {
                initializeEngine(app)
            }
        }
    }

    /**
     * 热重载后重装（onHotReloaded 中调用）。
     *
     * onPackageLoaded / onModuleLoaded 不会自动重放，旧 hook 已被框架默认卸载，
     * 需手动重装；Application 已创建（onCreate 不会再触发），通过反射获取实例。
     */
    fun onHotReloaded(module: XposedModule) {
        Logger.info(TAG, "onHotReloaded() re-installing hooks")
        hookCleartextTrafficPermitted(module)

        val app = currentApplication() ?: run {
            Logger.warn(TAG, "onHotReloaded: Application not available yet")
            return
        }
        if (app.packageName == PackageNames.SYSTEM_UI) {
            initializeEngine(app)
        }
    }

    private fun initializeEngine(app: Application) {
        try {
            SyncEngine.getInstance().initialize(app)
            Logger.info(TAG, "SyncEngine initialized in SystemUI")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to initialize SyncEngine", e)
        }
    }

    /** 反射获取当前进程的 Application（热重载时 onCreate 已错过，无法通过 hook 捕获） */
    private fun currentApplication(): Application? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getDeclaredMethod("currentApplication").invoke(null) as? Application
    } catch (e: Throwable) {
        Logger.warn(TAG, "currentApplication failed: ${e.message}")
        null
    }

    /** 钩住 NetworkSecurityPolicy.isCleartextTrafficPermitted，允许明文 HTTP 流量 */
    private fun hookCleartextTrafficPermitted(module: XposedModule) {
        try {
            val clazz = Class.forName("android.security.NetworkSecurityPolicy")
            clazz.getDeclaredMethod("isCleartextTrafficPermitted").apply {
                module.hook(this).intercept { true }
            }
            clazz.getDeclaredMethod("isCleartextTrafficPermitted", String::class.java).apply {
                module.hook(this).intercept { true }
            }
            Logger.info(TAG, "Cleartext traffic policy hooked (HTTP allowed)")
        } catch (e: Throwable) {
            Logger.warn(TAG, "Failed to hook NetworkSecurityPolicy: ${e.message}")
        }
    }
}
