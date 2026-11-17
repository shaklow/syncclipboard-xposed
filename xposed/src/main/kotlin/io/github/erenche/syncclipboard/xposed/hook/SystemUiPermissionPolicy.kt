package io.github.erenche.syncclipboard.xposed.hook

/**
 * INTERNET 补权的纯逻辑：netd uid 权限表与进程补充组。
 *
 * netd AIDL 的 [TRAFFIC_PERMISSION_INTERNET] 自 Android 9 冻结为 4；
 * `PermissionMonitor` 用同一个值判断 appId 是否允许创建 INET socket。
 */
internal object SystemUiPermissionPolicy {
    const val TRAFFIC_PERMISSION_INTERNET = 4
    const val PER_USER_RANGE = 100_000

    /** 兼容 appId（338）与完整 uid（10338）两种键，负值（已卸载）不算目标。 */
    fun isTargetUid(key: Int, appId: Int): Boolean =
        key >= 0 && appId >= 0 && key % PER_USER_RANGE == appId

    /** 只对非负掩码追加 INTERNET 位；`PERMISSION_UNINSTALLED(-1)` 保持原样。 */
    fun supplementMask(original: Int, enabled: Boolean): Int =
        if (enabled && original >= 0) original or TRAFFIC_PERMISSION_INTERNET else original
}
