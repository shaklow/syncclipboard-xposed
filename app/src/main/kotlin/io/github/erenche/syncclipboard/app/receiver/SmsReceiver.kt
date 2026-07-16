package io.github.erenche.syncclipboard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.common.util.VerificationCodeExtractor

/**
 * 静态短信广播接收器 — 拦截 SMS_RECEIVED，提取验证码后通过 IPC 桥接
 * 通知 SystemUI 进程的 SyncEngine 复制到剪贴板并上传到服务器。
 *
 * 功能由 [Prefs.loadConfig].enableSmsUpload 开关控制；
 * 开关关闭时不做任何处理，保持快速退出。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        // 开关检查：关闭时直接返回
        if (!Prefs.loadConfig(context).enableSmsUpload) return

        // 拼接多段短信正文
        @Suppress("DEPRECATION")
        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
        val format = intent.getStringExtra("format")
        val body = pdus.joinToString("") { pdu ->
            val msg = if (format != null)
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            else
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            msg?.messageBody ?: ""
        }

        if (body.isBlank()) return

        // 快速过滤：不含验证码特征则跳过
        if (!VerificationCodeExtractor.contains(body)) return

        // 提取验证码
        val code = VerificationCodeExtractor.extract(body) ?: return

        // 接收端去重：5 分钟内相同验证码不重复转发
        val now = System.currentTimeMillis()
        if (code == lastUploadedCode && (now - lastUploadTimeMs) < DEDUP_WINDOW_MS) {
            Logger.info(TAG, "SMS code dedup: skipping duplicate within ${DEDUP_WINDOW_MS / 1000}s")
            return
        }
        lastUploadedCode = code
        lastUploadTimeMs = now

        Logger.info(TAG, "SMS verification code detected: ${code.take(6)}")

        // 通过桥接发送给 SystemUI 的 SyncEngine，由其负责复制到剪贴板并上传
        try {
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.UPLOAD_TEXT)
                .payload(Bundle().apply { putString("text", code) })
                .send()
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to forward SMS code via bridge", e)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /** 接收端去重窗口：5 分钟内相同验证码只转发一次 */
        private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L

        @Volatile private var lastUploadedCode: String? = null
        @Volatile private var lastUploadTimeMs: Long = 0L
    }
}
