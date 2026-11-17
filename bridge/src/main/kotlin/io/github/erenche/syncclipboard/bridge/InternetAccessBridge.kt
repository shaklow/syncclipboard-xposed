package io.github.erenche.syncclipboard.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 向 system_server 同步 SystemUI 的 INTERNET 兼容开关，接收端通过模块签名权限校验来源。 */
object InternetAccessBridge {
    const val ACTION = "io.github.erenche.syncclipboard.SET_SYSTEMUI_INTERNET_ACCESS"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_CALLBACK = "callback"

    fun send(context: Context, enabled: Boolean) {
        runCatching {
            BridgeSecurity.sendBroadcast(context, Intent(ACTION).apply {
                setPackage(PackageNames.ANDROID)
                putExtra(EXTRA_ENABLED, enabled)
            })
        }.onFailure { Logger.warn("InetBridge", "Failed to send INTERNET setting: ${it.message}") }
    }

    /** 查询 system_server 中的开关与钩子状态；启用参数为空时不修改设置。 */
    suspend fun status(context: Context, enabled: Boolean? = null): Bundle {
        val reply = CompletableDeferred<Bundle>()
        return try {
            val intent = Intent(ACTION).setPackage(PackageNames.ANDROID)
            if (enabled != null) intent.putExtra(EXTRA_ENABLED, enabled)
            intent.putExtras(Bundle().apply {
                putBinder(EXTRA_CALLBACK, object : IBridgeCallback.Stub() {
                    override fun onReply(res: Bundle) {
                        if (BridgeSecurity.isTrustedCallback(context, PackageNames.ANDROID)) reply.complete(res)
                    }
                })
            })
            BridgeSecurity.sendBroadcast(context, intent)
            withTimeoutOrNull(5000) { reply.await() } ?: Bundle.EMPTY
        } finally { reply.cancel() }
    }
}
