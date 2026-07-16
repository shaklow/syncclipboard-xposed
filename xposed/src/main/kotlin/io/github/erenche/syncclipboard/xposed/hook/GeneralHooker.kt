package io.github.erenche.syncclipboard.xposed.hook

import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.sync.SyncEngine

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

        doOnAppCreated { app ->
            Logger.info(TAG, "App created: ${app.packageName}")

            // 仅在 SystemUI 中初始化 SyncEngine
            if (app.packageName == PackageNames.SYSTEM_UI) {
                try {
                    SyncEngine.getInstance().initialize(app)
                    Logger.info(TAG, "SyncEngine initialized in SystemUI")
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to initialize SyncEngine", e)
                }
            }
        }
    }
}
