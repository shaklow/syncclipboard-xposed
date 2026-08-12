package io.github.erenche.syncclipboard.bridge

import android.content.Context
import android.os.Binder
import io.github.erenche.syncclipboard.common.PackageNames

/**
 * Bridge 安全工具 — 校验广播发送方是否可信。
 *
 * IPC 是双向的：App ↔ SystemUI，两者都需放行；第三方 App 一律拒绝。
 *
 * 注意：不要用 [android.os.Process.SYSTEM_UID] 判断 SystemUI，部分 OEM（OPPO/小米等）
 * 将 SystemUI 运行在独立的应用 UID 上（如 u0_a242），而非 system uid（1000）。
 * 用包名匹配跨设备通用。
 */
object BridgeSecurity {

    /**
     * 校验当前广播发送方是否可信（本 App 或 SystemUI）。
     *
     * 应在 [android.content.BroadcastReceiver.onReceive] 开头调用：
     * ```
     * override fun onReceive(context: Context, intent: Intent) {
     *     if (!BridgeSecurity.isTrustedSender(context)) return
     *     // ... 原有逻辑
     * }
     * ```
     */
    fun isTrustedSender(context: Context): Boolean {
        val callerUid = Binder.getCallingUid()
        val pkgs = context.packageManager.getPackagesForUid(callerUid) ?: return false
        return PackageNames.APPLICATION in pkgs || PackageNames.SYSTEM_UI in pkgs
    }
}
