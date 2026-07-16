package io.github.erenche.syncclipboard.app

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.util.Logger
import java.util.concurrent.CopyOnWriteArraySet

/**
 * SyncClipboardApp — Application 入口。
 *
 * 遵循 lyricon 项目中 LyriconApp 的模式：
 * - 管理 XposedService 连接生命周期
 * - 提供全局 Application 实例
 * - 封装 SharedPreferences 访问
 */
class SyncClipboardApp : Application() {

    init {
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        // 同步日志开关到 app 进程（Logger 是进程内单例）
        val config = Prefs.loadConfig(this)
        Logger.enabled = config.enableLogging
        Logger.logLevel = config.logLevel
        Logger.info(TAG, "SyncClipboardApp created")

        // SyncEngine 在 system_server 中运行（由 GeneralHooker 初始化）
        // App 进程不初始化 SyncEngine，通过 bridge 向 system_server 查询

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Logger.info(TAG, "XposedService bind")
                xposedService = service
                xposedServiceStateListeners.forEach {
                    it.onServiceStateChanged(service)
                }
            }

            override fun onServiceDied(service: XposedService) {
                Logger.info(TAG, "XposedService died")
                xposedService = null
                xposedServiceStateListeners.forEach {
                    it.onServiceStateChanged(null)
                }
            }
        })
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        super.getSharedPreferences(name, mode)

    companion object {
        const val TAG: String = "SyncClipboardApp"
        private val xposedServiceStateListeners =
            CopyOnWriteArraySet<XposedServiceStateListener>()

        lateinit var instance: SyncClipboardApp
            private set

        var xposedService: XposedService? = null
            private set

        fun addXposedServiceStateListener(
            listener: XposedServiceStateListener,
            notifyImmediately: Boolean = true
        ) {
            xposedServiceStateListeners.add(listener)
            if (notifyImmediately && xposedService != null) {
                listener.onServiceStateChanged(xposedService)
            }
        }

        fun removeXposedServiceStateListener(listener: XposedServiceStateListener) {
            xposedServiceStateListeners.remove(listener)
        }

        fun get(): SyncClipboardApp = instance
    }

    interface XposedServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }
}
