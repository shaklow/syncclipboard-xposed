package io.github.erenche.syncclipboard.xposed

import androidx.annotation.Keep
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.hook.GeneralHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed 模块入口。
 *
 * - com.android.systemui: SyncEngine 在此进程运行，SystemUI 始终在前台，
 *   OnPrimaryClipChangedListener 可靠捕获全局剪贴板变化，实现后台同步
 * - io.github.erenche.syncclipboard (App): App UI 通过 bridge 与 SystemUI 通信
 *
 * 剪贴板变化仅通过 OnPrimaryClipChangedListener 监听（系统级，单一路径），
 * 避免 ClipboardHooker / 轮询多路径竞态导致重复上传。
 */
@Keep
class ModuleEntry : XposedModule() {

    companion object {
        private const val TAG = "ModuleEntry"
        private val scopes = listOf(
            PackageNames.SYSTEM_UI,
            PackageNames.APPLICATION
        )
        lateinit var instance: ModuleEntry
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        Logger.info(TAG, "onModuleLoaded: processName=${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg !in scopes) return
        Logger.info(TAG, "onPackageLoaded: $pkg")

        GeneralHooker.hook(this, param)
    }
}
