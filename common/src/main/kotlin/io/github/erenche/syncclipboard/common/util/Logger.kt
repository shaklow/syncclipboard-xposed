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
 * - [enabled] = false：关闭所有日志输出（logcat 和缓冲区均不记录）
 * - [enabled] = true：按 [logLevel] 过滤，仅 >= logLevel 的级别输出到 logcat 和缓冲区
 *
 * 缓冲区行数由 [maxBufferSize] 控制，可在设置页调整。
 */
object Logger {

    private const val TAG = "SyncClipboard"

    /** 日志总开关，false 时关闭所有日志输出 */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var logLevel: LogLevel = LogLevel.Info

    /** 内存日志缓冲区最大行数，超限后丢弃最旧条目 */
    @Volatile
    var maxBufferSize: Int = 2000

    /**
     * 框架日志转发挂点：由各进程 ModuleEntry 注入 LSPosed/Xposed 的 log 通道，
     * 使 system_server / SystemUI / App 三个进程的日志统一出现在 LSPosed 模块日志中。
     * 仅转发 Info/Warn/Error（Debug 级别留在 logcat 与内存缓冲，避免刷屏）。
     */
    @Volatile
    var frameworkSink: ((levelChar: Char, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val buffer = ConcurrentLinkedDeque<String>()

    private fun timestamp(): String = dateFormat.format(Date())

    /** 将日志级别字符映射为 [LogLevel] 序数，用于过滤比较 */
    private fun levelOrdinal(level: String): Int = when (level) {
        "D" -> LogLevel.Debug.ordinal
        "I" -> LogLevel.Info.ordinal
        "W" -> LogLevel.Warn.ordinal
        "E" -> LogLevel.Error.ordinal
        else -> Int.MAX_VALUE
    }

    private fun record(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (levelOrdinal(level) < logLevel.ordinal) return
        val ts = timestamp()
        val line = if (throwable != null) {
            "$ts $level/[$tag] $message\n${Log.getStackTraceString(throwable).trim()}"
        } else {
            "$ts $level/[$tag] $message"
        }
        buffer.addLast(line)
        val limit = maxBufferSize
        while (buffer.size > limit) buffer.pollFirst()
        // 转发到框架日志（LSPosed 模块日志，跨进程统一展示）；Debug 留在 logcat/内存避免刷屏
        if (level != "D") {
            runCatching { frameworkSink?.invoke(level[0], tag, message, throwable) }
        }
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
        if (enabled && logLevel.ordinal <= LogLevel.Warn.ordinal) {
            if (throwable != null) {
                Log.w(TAG, "[$tag] $message", throwable)
            } else {
                Log.w(TAG, "[$tag] $message")
            }
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        record("E", tag, message, throwable)
        if (enabled && logLevel.ordinal <= LogLevel.Error.ordinal) {
            if (throwable != null) {
                Log.e(TAG, "[$tag] $message", throwable)
            } else {
                Log.e(TAG, "[$tag] $message")
            }
        }
    }

    /** 获取内存缓冲区中的所有日志（按时间顺序） */
    fun getLogs(): String = buffer.joinToString("\n")

    /** 清空日志缓冲区 */
    fun clear() {
        buffer.clear()
    }
}
