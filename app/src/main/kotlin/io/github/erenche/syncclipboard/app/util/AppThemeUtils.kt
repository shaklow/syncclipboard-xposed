package io.github.erenche.syncclipboard.app.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.extensions.editCommit

object AppThemeUtils {
    const val MODE_SYSTEM: Int = 0
    const val MODE_LIGHT: Int = 1
    const val MODE_DARK: Int = 2

    /** 默认颜色（miuix 内置蓝色）的标识，使用空字符串以兼容旧版本。 */
    const val COLOR_DEFAULT: String = ""
    /** 动态颜色（Monet）的标识，由 [isEnableMonet] 单独控制，此处仅占位。 */

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MONET_COLOR = "theme_monet_color"
    private const val KEY_THEME_COLOR = "theme_color"

    fun getMode(context: Context): Int =
        context.defaultSharedPreferences.getInt(KEY_THEME_MODE, MODE_SYSTEM)

    fun setMode(context: Context, mode: Int) {
        context.defaultSharedPreferences.editCommit { putInt(KEY_THEME_MODE, mode) }
    }

    fun isEnableMonet(context: Context): Boolean =
        context.defaultSharedPreferences.getBoolean(KEY_MONET_COLOR, false)

    fun setEnableMonet(context: Context, enable: Boolean) {
        context.defaultSharedPreferences.editCommit { putBoolean(KEY_MONET_COLOR, enable) }
    }

    /** 读取用户选择的主题颜色标识，默认为 [COLOR_DEFAULT]。 */
    fun getThemeColor(context: Context): String =
        context.defaultSharedPreferences.getString(KEY_THEME_COLOR, COLOR_DEFAULT)
            ?: COLOR_DEFAULT

    fun setThemeColor(context: Context, color: String) {
        context.defaultSharedPreferences.editCommit { putString(KEY_THEME_COLOR, color) }
    }
}

/**
 * 主题可观察状态单例。
 *
 * 持有主题模式、主题颜色、Monet 开关的 Compose state，
 * 使主题变更能实时触发 recomposition，无需重启 Activity。
 *
 * 在每个 Activity 创建时调用 [sync] 从 SharedPreferences 加载最新值。
 */
object ThemeState {
    var mode by mutableStateOf(AppThemeUtils.MODE_SYSTEM)
        private set
    var themeColorId by mutableStateOf(AppThemeUtils.COLOR_DEFAULT)
        private set
    var monetEnabled by mutableStateOf(false)
        private set

    /** 从 SharedPreferences 同步最新值（不触发额外 recomposition）。 */
    fun sync(context: Context) {
        mode = AppThemeUtils.getMode(context)
        themeColorId = AppThemeUtils.getThemeColor(context)
        monetEnabled = AppThemeUtils.isEnableMonet(context)
    }

    /** 更新主题模式，同时写入 SharedPreferences 与 state。 */
    fun updateMode(context: Context, newMode: Int) {
        AppThemeUtils.setMode(context, newMode)
        mode = newMode
    }

    /** 更新主题颜色，同时写入 SharedPreferences 与 state。 */
    fun updateThemeColor(context: Context, colorId: String) {
        AppThemeUtils.setThemeColor(context, colorId)
        themeColorId = colorId
    }

    /** 更新 Monet 开关，同时写入 SharedPreferences 与 state。 */
    fun updateMonet(context: Context, enable: Boolean) {
        AppThemeUtils.setEnableMonet(context, enable)
        monetEnabled = enable
    }
}

/**
 * 预设主题颜色方案。
 *
 * @param id 持久化标识。
 * @param lightPrimary 亮色模式下的 primary 颜色。
 * @param darkPrimary 暗色模式下的 primary 颜色。
 * @param swatch 用于颜色选择器预览的色块（取亮色 primary）。
 */
enum class ThemeColor(
    val id: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
    val swatch: Color = lightPrimary,
) {
    DEFAULT(AppThemeUtils.COLOR_DEFAULT, Color(0xFF3482FF), Color(0xFF277AF7)),
    PURPLE("purple", Color(0xFF7C4DFF), Color(0xFFB39DDB)),
    GREEN("green", Color(0xFF00A862), Color(0xFF6BCF94)),
    ORANGE("orange", Color(0xFFFF6D00), Color(0xFFFFAB40)),
    PINK("pink", Color(0xFFE91E63), Color(0xFFF48FB1)),
    RED("red", Color(0xFFE94634), Color(0xFFEF6E5C)),
    CYAN("cyan", Color(0xFF00B8D4), Color(0xFF4DD0E1)),
    GRAY("gray", Color(0xFF607D8B), Color(0xFF90A4AE));

    companion object {
        fun fromId(id: String?): ThemeColor =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
