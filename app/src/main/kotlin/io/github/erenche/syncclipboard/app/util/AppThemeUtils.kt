package io.github.erenche.syncclipboard.app.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.extensions.editCommit
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

object AppThemeUtils {
    const val MODE_SYSTEM: Int = 0
    const val MODE_LIGHT: Int = 1
    const val MODE_DARK: Int = 2

    /** 默认颜色标识（使用 miuix/Monet 默认配色），兼容旧版本空字符串。 */
    const val COLOR_DEFAULT: String = ""

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MONET_COLOR = "theme_monet_color"
    private const val KEY_THEME_COLOR = "theme_color"
    /** Monet 主题色（种子颜色，ARGB；0 = 跟随系统/默认） */
    private const val KEY_KEY_COLOR = "theme_key_color"
    /** 配色风格（ThemePaletteStyle.name） */
    private const val KEY_PALETTE_STYLE = "theme_palette_style"
    /** 颜色规格（ThemeColorSpec.name） */
    private const val KEY_COLOR_SPEC = "theme_color_spec"

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

    /** 旧版主题颜色标识（仅兼容读取；新逻辑改用 [getKeyColor]） */
    fun getThemeColor(context: Context): String =
        context.defaultSharedPreferences.getString(KEY_THEME_COLOR, COLOR_DEFAULT)
            ?: COLOR_DEFAULT

    fun setThemeColor(context: Context, color: String) {
        context.defaultSharedPreferences.editCommit { putString(KEY_THEME_COLOR, color) }
    }

    fun getKeyColor(context: Context): Int =
        context.defaultSharedPreferences.getInt(KEY_KEY_COLOR, 0)

    fun setKeyColor(context: Context, color: Int) {
        context.defaultSharedPreferences.editCommit { putInt(KEY_KEY_COLOR, color) }
    }

    fun getPaletteStyle(context: Context): String =
        context.defaultSharedPreferences.getString(
            KEY_PALETTE_STYLE, ThemePaletteStyle.TonalSpot.name
        ) ?: ThemePaletteStyle.TonalSpot.name

    fun setPaletteStyle(context: Context, style: String) {
        context.defaultSharedPreferences.editCommit { putString(KEY_PALETTE_STYLE, style) }
    }

    fun getColorSpec(context: Context): String =
        context.defaultSharedPreferences.getString(
            KEY_COLOR_SPEC, ThemeColorSpec.Spec2021.name
        ) ?: ThemeColorSpec.Spec2021.name

    fun setColorSpec(context: Context, spec: String) {
        context.defaultSharedPreferences.editCommit { putString(KEY_COLOR_SPEC, spec) }
    }
}

/**
 * 主题可观察状态单例。
 *
 * 持有主题模式、Monet 开关、Monet 种子色/风格/规格的 Compose state，
 * 使主题变更能实时触发 recomposition，无需重启 Activity。
 *
 * 在每个 Activity 创建时调用 [sync] 从 SharedPreferences 加载最新值。
 */
object ThemeState {
    var mode by mutableStateOf(AppThemeUtils.MODE_SYSTEM)
        private set
    /** 旧版主题颜色标识（兼容旧 id，读取后迁移到 [keyColor]，不再写入） */
    var themeColorId by mutableStateOf(AppThemeUtils.COLOR_DEFAULT)
        private set
    var monetEnabled by mutableStateOf(false)
        private set
    /** Monet 种子颜色（ARGB Int，0 = 默认/跟随系统壁纸色） */
    var keyColor by mutableStateOf(0)
        private set
    /** 配色风格（[ThemePaletteStyle] name） */
    var paletteStyle by mutableStateOf(ThemePaletteStyle.TonalSpot.name)
        private set
    /** 颜色规格（[ThemeColorSpec] name） */
    var colorSpec by mutableStateOf(ThemeColorSpec.Spec2021.name)
        private set

    /** 从 SharedPreferences 同步最新值（不触发额外 recomposition）。 */
    fun sync(context: Context) {
        mode = AppThemeUtils.getMode(context)
        themeColorId = AppThemeUtils.getThemeColor(context)
        monetEnabled = AppThemeUtils.isEnableMonet(context)
        paletteStyle = AppThemeUtils.getPaletteStyle(context)
        colorSpec = AppThemeUtils.getColorSpec(context)
        // 一次性迁移：旧主题颜色 id（非默认）→ Monet 种子颜色
        if (AppThemeUtils.getKeyColor(context) == 0 && themeColorId != AppThemeUtils.COLOR_DEFAULT) {
            val legacy = ThemeColor.fromId(themeColorId)
            if (legacy != ThemeColor.DEFAULT) {
                AppThemeUtils.setKeyColor(context, legacy.argb)
            }
        }
        keyColor = AppThemeUtils.getKeyColor(context)
    }

    /** 更新主题模式，同时写入 SharedPreferences 与 state。 */
    fun updateMode(context: Context, newMode: Int) {
        AppThemeUtils.setMode(context, newMode)
        mode = newMode
    }

    /** 更新 Monet 开关，同时写入 SharedPreferences 与 state。 */
    fun updateMonet(context: Context, enable: Boolean) {
        AppThemeUtils.setEnableMonet(context, enable)
        monetEnabled = enable
    }

    /** 更新 Monet 种子颜色，同时写入 SharedPreferences 与 state。 */
    fun updateKeyColor(context: Context, color: Int) {
        AppThemeUtils.setKeyColor(context, color)
        keyColor = color
    }

    /** 更新配色风格，同时写入 SharedPreferences 与 state。 */
    fun updatePaletteStyle(context: Context, style: String) {
        AppThemeUtils.setPaletteStyle(context, style)
        paletteStyle = style
    }

    /** 更新颜色规格，同时写入 SharedPreferences 与 state。 */
    fun updateColorSpec(context: Context, spec: String) {
        AppThemeUtils.setColorSpec(context, spec)
        colorSpec = spec
    }
}

/**
 * Monet 主题颜色方案（SukiSU 同款调色板，移植自 materialKolor 种子色集合）。
 *
 * @param id 持久化标识（兼容旧版 id）。
 * @param argb 种子颜色值（ARGB Int，0 = 默认）。
 */
enum class ThemeColor(
    val id: String,
    val argb: Int,
) {
    DEFAULT(AppThemeUtils.COLOR_DEFAULT, 0),
    RED("red", 0xFFF44336.toInt()),
    PINK("pink", 0xFFE91E63.toInt()),
    PURPLE("purple", 0xFF9C27B0.toInt()),
    DEEP_PURPLE("deep_purple", 0xFF673AB7.toInt()),
    INDIGO("indigo", 0xFF3F51B5.toInt()),
    BLUE("blue", 0xFF2196F3.toInt()),
    CYAN("cyan", 0xFF00BCD4.toInt()),
    TEAL("teal", 0xFF009688.toInt()),
    GREEN("green", 0xFF4FAF50.toInt()),
    YELLOW("yellow", 0xFFFFEB3B.toInt()),
    AMBER("amber", 0xFFFFC107.toInt()),
    ORANGE("orange", 0xFFFF9800.toInt()),
    BROWN("brown", 0xFF795548.toInt()),
    BLUE_GREY("blue_grey", 0xFF607D8F.toInt()),
    SAKURA("sakura", 0xFFFF9CA8.toInt());

    val color: Color get() = Color(argb)

    companion object {
        /** 旧版 "gray" 与 [BLUE_GREY] 为同色，兼容旧 id 迁移。 */
        fun fromId(id: String?): ThemeColor =
            entries.firstOrNull { it.id == id }
                ?: (if (id == "gray") BLUE_GREY else null)
                ?: DEFAULT
    }
}
