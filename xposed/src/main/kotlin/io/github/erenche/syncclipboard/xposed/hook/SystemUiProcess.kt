package io.github.erenche.syncclipboard.xposed.hook

import java.io.File

/**
 * 当前进程的补充组查询。
 *
 * Android 内核的 paranoid-network 检查按补充组放行 socket：只有进程带有
 * `AID_INET(3003)` 才能创建 INET socket。INTERNET 是 normal 权限，缺少声明时
 * SystemUI 进程不会有该组，`socket()` 会直接返回 EPERM。
 */
internal object SystemUiProcess {
    const val AID_INET = 3003

    /** 仅用于 SystemUI 进程：读取自身 /proc/self/status，结果在进程生命周期内不变。 */
    val hasInetGroup: Boolean by lazy {
        runCatching { parseGroups(File("/proc/self/status").readText())?.contains(AID_INET) == true }
            .getOrDefault(false)
    }

    internal fun parseGroups(status: String): IntArray? = status.lineSequence()
        .firstOrNull { it.startsWith("Groups:") }
        ?.substringAfter(':')
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.mapNotNull { it.toIntOrNull() }
        ?.toIntArray()
}
