package io.github.erenche.syncclipboard.app.receiver

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.common.util.VerificationCodeExtractor

/**
 * 通知监听服务 — 拦截所有应用通知，提取验证码后通过 IPC 桥接
 * 通知 SystemUI 进程的 SyncEngine 复制到剪贴板并上传到服务器。
 *
 * 与 [SmsReceiver] 互补：短信接收器只能拿到 SMS，本服务能捕获
 * IM（微信/QQ/Telegram）、邮件、银行 App 等通过通知下发的验证码。
 *
 * 功能由 [Prefs.loadConfig].enableNotificationUpload 开关控制；
 * 开关关闭时直接返回，不做任何处理。
 *
 * 需要"通知访问权限"：用户在设置开关打开时跳转系统设置授权
 * （见 SettingsActivity 中 toggleNotificationUpload）。
 *
 * 去重策略与 SmsReceiver 一致：5 分钟内相同验证码不重复转发。
 */
class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 开关关闭时快速退出
        if (!Prefs.loadConfig(this).enableNotificationUpload) return

        // 跳过本应用自身的通知，避免自环
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 拼接通知标题与正文（部分 App 验证码在 title，部分在 text）
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        val body = buildString {
            if (title.isNotBlank()) append(title).append(' ')
            if (text.isNotBlank()) append(text).append(' ')
            if (bigText.isNotBlank()) append(bigText)
        }.trim()

        if (body.isBlank()) return

        // 快速过滤：不含验证码特征则跳过
        if (!VerificationCodeExtractor.contains(body)) return

        val code = VerificationCodeExtractor.extract(body) ?: return

        // 与 SMS_RECEIVED 共享去重状态，避免短信广播和短信应用通知各上传一次。
        if (!VerificationCodeExtractor.shouldForward(code)) {
            Logger.info(TAG, "Notification code dedup: skipping duplicate within 300s")
            return
        }

        // 不把验证码正文写入日志，只记录来源和长度。
        Logger.info(TAG, "Notification verification code detected from ${sbn.packageName} (length=${code.length})")

        // 通过桥接发送给 SystemUI 的 SyncEngine，由其负责复制到剪贴板并上传
        try {
            SyncClipboardBridge.with(this)
                .to("com.android.systemui")
                .key(BridgeKeys.UPLOAD_TEXT)
                .payload(Bundle().apply { putString("text", code) })
                .send()
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to forward notification code via bridge", e)
        }
    }

    companion object {
        private const val TAG = "NotificationListener"
    }
}
