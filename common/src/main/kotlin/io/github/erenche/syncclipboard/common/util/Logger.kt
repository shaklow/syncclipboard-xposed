package io.github.erenche.syncclipboard.common.util

import android.util.Log
import io.github.erenche.syncclipboard.common.model.LogLevel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 应用日志工具 — 封装 android.util.Log，同时保留内存环形缓冲区。
 *
 * 日志开关语义：
 * - [enabled] = false：关闭"详细日志"，但 W/E 级别始终输出到 logcat 和缓冲区（保证异常和警告可见）
 * - [enabled] = true：按 [logLevel] 过滤，所有级别输出到 logcat 和缓冲区
 *
 * 注意：info/debug 级别在 enabled=false 时不输出到 logcat，也不记入缓冲区。
 * 需要始终可见的关键运行信息请使用 warn() 或 error()。
 */
object Logger {

    private const val TAG = "SyncClipboard"
    private const val MAX_BUFFER = 1000

    /** 日志总开关，false 时仅输出 Warn/Error（保证异常可见） */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var logLevel: LogLevel = LogLevel.Info

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val buffer = ConcurrentLinkedDeque<String>()

    private fun timestamp(): String = dateFormat.format(Date())

    private fun record(level: String, tag: String, message: String, throwable: Throwable? = null) {
        // enabled=false 时缓冲区只记录 W/E；enabled=true 时全部记录
        if (!enabled && (level == "D" || level == "I")) return
        val ts = timestamp()
        val line = if (throwable != null) {
            "$ts $level/[$tag] $message\n${Log.getStackTraceString(throwable).trim()}"
        } else {
            "$ts $level/[$tag] $message"
        }
        buffer.addLast(line)
        while (buffer.size > MAX_BUFFER) buffer.pollFirst()
    }

    fun debug(tag: String, message: String) {
        record("D", tag, message)
        if (enabled && logLevel.ordinal <= LogLevel.Debug.ordinal) {
            Log.d(TAG, "[$tag] $message")
        }
    }

    fun info(tag: String, message: String) {
        record("I", tag, message)
        if (enabled && logLevel.ordinal <= LogLevel.Info.ordinal) {
            Log.i(TAG, "[$tag] $message")
        }
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        record("W", tag, message, throwable)
        // W/E 始终输出到 logcat（即使 enabled=false）
        if (throwable != null) {
            Log.w(TAG, "[$tag] $message", throwable)
        } else {
            Log.w(TAG, "[$tag] $message")
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        record("E", tag, message, throwable)
        if (throwable != null) {
            Log.e(TAG, "[$tag] $message", throwable)
        } else {
            Log.e(TAG, "[$tag] $message")
        }
    }

    /** 获取内存缓冲区中的所有日志（按时间顺序） */
    fun getLogs(): String = buffer.joinToString("\n")

    /** 获取最近 N 条日志 */
    fun getRecentLogs(count: Int): String {
        val all = buffer.toList()
        return all.takeLast(count).joinToString("\n")
    }

    /** 清空日志缓冲区 */
    fun clear() {
        buffer.clear()
    }
}
