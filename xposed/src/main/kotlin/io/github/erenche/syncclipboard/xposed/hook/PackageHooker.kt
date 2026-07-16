package io.github.erenche.syncclipboard.xposed.hook

import android.app.Application
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PackageHooker — Xposed Hook 的抽象基类。
 *
 * 提供：
 * - Application.onCreate() 的 Hook 逻辑
 * - doOnAppCreated() 延迟初始化回调
 * - isMainProcess() 进程判断
 *
 * 复用自 lyricon 项目中的 PackageHooker 模式。
 */
abstract class PackageHooker {
    companion object {
        private const val TAG = "PackageHooker"
    }

    protected lateinit var module: XposedModule
    protected lateinit var packageParam: XposedModuleInterface.PackageLoadedParam

    val packageName: String get() = packageParam.packageName
    val classLoader: ClassLoader get() = packageParam.defaultClassLoader

    @Volatile
    var appContext: Application? = null
        private set

    private val appOnCreateListeners = CopyOnWriteArraySet<(Application) -> Unit>()
    private val isHooked = AtomicBoolean(false)

    fun isMainProcess(): Boolean =
        packageParam.applicationInfo.processName == packageName

    /**
     * 在 Application 创建时执行回调。
     * 如果 Application 已创建，立即执行回调。
     * 否则注册回调等待 Application 创建。
     */
    fun doOnAppCreated(callback: (Application) -> Unit) {
        val current = appContext
        if (current != null) {
            callback(current)
            return
        }

        appOnCreateListeners.add(callback)

        if (isHooked.compareAndSet(false, true)) {
            hookApplicationOnCreate()
        }
    }

    /**
     * Hook Application.onCreate() 以获取 Application 实例
     */
    private fun hookApplicationOnCreate() {
        val targetClassName = packageParam.applicationInfo.className ?: "android.app.Application"

        try {
            Logger.info(TAG, "Targeting Application class: $targetClassName")

            val targetClass = classLoader.loadClass(targetClassName)
            val onCreateMethod = targetClass.getDeclaredMethod("onCreate")

            module.hook(onCreateMethod)
                .intercept(XposedInterfaceHooker())
        } catch (e: Throwable) {
            Logger.warn(TAG, "Failed to hook $targetClassName, falling back to global Application", e)
            fallbackToGlobalHook()
        }
    }

    /**
     * 兜底方案：Hook 通用的 Application 类
     */
    private fun fallbackToGlobalHook() {
        try {
            val onCreateMethod = Application::class.java.getDeclaredMethod("onCreate")

            module.hook(onCreateMethod).intercept { chain ->
                chain.proceed()
                val instance = chain.thisObject as? Application
                if (instance != null) {
                    handleApplicationInstance(instance)
                }
                null
            }
        } catch (t: Throwable) {
            Logger.error(TAG, "Critical failure: Global Hook failed", t)
        }
    }

    private inner class XposedInterfaceHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            chain.proceed()
            val instance = chain.thisObject as? Application
            if (instance != null) {
                handleApplicationInstance(instance)
            }
            return null
        }
    }

    private fun handleApplicationInstance(instance: Application) {
        if (appContext != null) return

        synchronized(this) {
            if (appContext == null) {
                appContext = instance

                Logger.info(TAG, "Application captured: ${instance.javaClass.name}, package=${instance.packageName}, listeners=${appOnCreateListeners.size}")
                Logger.info(TAG, "Application Context is ready: " + instance.javaClass.name)

                appOnCreateListeners.forEach {
                    runCatching { it(instance) }.onFailure { e ->
                        Logger.error(TAG, "Callback error", e)
                    }
                }
                appOnCreateListeners.clear()
            }
        }
    }

    private var isAttached = false

    fun hook(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (isAttached) {
            Logger.info(TAG, "Already attached")
            return
        }
        isAttached = true
        this.module = module
        this.packageParam = param
        onHook()
    }

    abstract fun onHook()
}
