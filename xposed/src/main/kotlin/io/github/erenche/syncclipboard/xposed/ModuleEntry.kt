package io.github.erenche.syncclipboard.xposed

import androidx.annotation.Keep
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.hook.GeneralHooker
import io.github.erenche.syncclipboard.xposed.hook.SystemNotificationHooker
import io.github.erenche.syncclipboard.xposed.sync.SyncEngine
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
 *
 * 热重载（API 102）：更新模块无需重启 SystemUI。
 * - onHotReloading（旧代码）：拆除引擎（协程/监听器/SignalR/IPC 路由/数据库）
 * - onHotReloaded（新代码）：重装 hook 并重建引擎（生命周期回调不自动重放）
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

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        Logger.info(TAG, "onSystemServerStarting: installing NMS notification hook")
        // 钩住 NotificationManagerService 截获所有通知，验证码自动上传不再依赖 App 进程存活
        try {
            SystemNotificationHooker.install(this, param.classLoader)
        } catch (e: Throwable) {
            Logger.error(TAG, "SystemNotificationHooker.install failed", e)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        Logger.info(TAG, "onHotReloading: tearing down old generation")
        // 把 system_server 开机时可加载 NMS 的类加载器跨代保存，供新世代重装钩子复用
        try {
            SystemNotificationHooker.bootClassLoader?.let { param.setSavedInstanceState(it) }
        } catch (_: Throwable) {
        }
        return try {
            // SystemUI 进程：完整拆除引擎，清除模块对系统对象的所有引用
            if (SyncEngine.isInitialized()) {
                SyncEngine.getInstance().shutdown()
            }
            true
        } catch (e: Throwable) {
            // 拆除失败时拒绝热重载，避免新旧两代并存（回退为需重启 SystemUI）
            Logger.error(TAG, "Hot reload teardown failed, rejecting hot reload", e)
            false
        }
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 默认实现卸载全部旧 hook
        super.onHotReloaded(param)
        Logger.info(TAG, "onHotReloaded: new generation active")
        // 生命周期回调不重放，手动重装 hook 与引擎
        GeneralHooker.onHotReloaded(this)
        // system_server 世代：重装 NMS 通知钩子（优先复用跨代保存的加载器）
        if (param.isSystemServer()) {
            Logger.info(TAG, "onHotReloaded: re-installing NMS notification hook (system_server)")
            try {
                SystemNotificationHooker.install(this, param.getSavedInstanceState() as? ClassLoader)
            } catch (e: Throwable) {
                Logger.error(TAG, "SystemNotificationHooker reinstall failed", e)
            }
        }
    }
}
