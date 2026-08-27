package io.github.erenche.syncclipboard.app.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.extensions.editCommit

/**
 * 界面可观察状态单例（模糊 / 悬浮底栏 / 液态玻璃）。
 *
 * 模式与 [ThemeState] 一致：Compose state 驱动实时 recomposition，
 * 变更同时写入 SharedPreferences 持久化。
 */
object UiState {
    private const val KEY_BLUR = "ui_blur"
    private const val KEY_FLOATING_BOTTOM_BAR = "ui_floating_bottom_bar"
    private const val KEY_BOTTOM_BAR_BLUR = "ui_bottom_bar_blur"

    /** 常规底栏的模糊背景（设备不支持时自动降级为纯色） */
    var blur by mutableStateOf(true)
        private set

    /** 悬浮胶囊式底栏（关闭则使用常规 NavigationBar） */
    var floatingBottomBar by mutableStateOf(true)
        private set

    /** 悬浮底栏液态玻璃效果（设备不支持时自动降级为纯色） */
    var bottomBarBlur by mutableStateOf(true)
        private set

    /** 服务器编辑页打开中（此时主页底栏隐藏、禁用滑动切换） */
    var serverEditing by mutableStateOf(false)

    /** 从 SharedPreferences 同步最新值。 */
    fun sync(context: Context) {
        blur = context.defaultSharedPreferences.getBoolean(KEY_BLUR, true)
        floatingBottomBar = context.defaultSharedPreferences.getBoolean(KEY_FLOATING_BOTTOM_BAR, true)
        bottomBarBlur = context.defaultSharedPreferences.getBoolean(KEY_BOTTOM_BAR_BLUR, true)
    }

    fun updateBlur(context: Context, enable: Boolean) {
        context.defaultSharedPreferences.editCommit { putBoolean(KEY_BLUR, enable) }
        blur = enable
    }

    fun updateFloatingBottomBar(context: Context, enable: Boolean) {
        context.defaultSharedPreferences.editCommit { putBoolean(KEY_FLOATING_BOTTOM_BAR, enable) }
        floatingBottomBar = enable
    }

    fun updateBottomBarBlur(context: Context, enable: Boolean) {
        context.defaultSharedPreferences.editCommit { putBoolean(KEY_BOTTOM_BAR_BLUR, enable) }
        bottomBarBlur = enable
    }
}
