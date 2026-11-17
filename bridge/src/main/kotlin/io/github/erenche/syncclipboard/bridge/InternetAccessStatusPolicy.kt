package io.github.erenche.syncclipboard.bridge

enum class InternetAccessState {
    /** 尚未拿到 system_server / SystemUI 任一侧的状态。 */
    UNKNOWN,
    /** 开关已打开，但 system_server 中的钩子尚未就绪。 */
    WAITING,
    /** 开关已关闭，原始权限保持不动。 */
    DISABLED,
    /** 钩子已就绪，但当前 SystemUI 进程仍是旧进程，需要重启才能拿到 inet 组。 */
    RESTART_REQUIRED,
    /** 当前 SystemUI 进程已带有 AID_INET(3003) 组。 */
    VERIFIED,
    /** 开关保存失败或 system_server 钩子安装失败。 */
    FAILED
}

/** system_server 侧回执。 */
data class InternetAccessStatus(
    val enabled: Boolean,
    val hookInstalled: Boolean,
    val storageFailed: Boolean
)

object InternetAccessStatusPolicy {
    /**
     * @param systemUiHasInetGroup SystemUI 进程自报的补充组状态；未收到时为 null。
     *   SystemUI 自带 INTERNET 时无需补权，即使没有启用 Android 系统作用域也算完成。
     */
    fun classify(
        desired: Boolean,
        status: InternetAccessStatus?,
        systemUiHasInetGroup: Boolean?
    ): InternetAccessState = when {
        // 关闭时不依赖系统侧回执：即使 Android 作用域未启用，也没有任何补权在生效。
        !desired -> when {
            status == null -> InternetAccessState.DISABLED
            status.storageFailed -> InternetAccessState.FAILED
            status.enabled -> InternetAccessState.WAITING
            else -> InternetAccessState.DISABLED
        }
        // SystemUI 本身已有 inet 组：不需要 system_server 补权。
        systemUiHasInetGroup == true -> InternetAccessState.VERIFIED
        // system_server 未响应：模块未启用 Android 系统作用域或尚未重启。
        status == null -> InternetAccessState.RESTART_REQUIRED
        status.storageFailed -> InternetAccessState.FAILED
        !status.hookInstalled -> InternetAccessState.WAITING
        !status.enabled -> InternetAccessState.WAITING
        else -> InternetAccessState.RESTART_REQUIRED
    }
}
