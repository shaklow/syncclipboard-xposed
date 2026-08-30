package io.github.erenche.syncclipboard.app.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import io.github.erenche.syncclipboard.app.util.AppThemeUtils
import io.github.erenche.syncclipboard.app.util.ThemeState
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

object CurrentThemeConfigs {
    var isDark: Boolean = false
    var primary: Color = Color.Transparent
}

/**
 * App 主题入口。
 *
 * 基于 miuix 0.9.3 的 [ThemeController]（Monet 配色管线，materialKolor 生成方案）：
 * - 非 Monet：System / Light / Dark（miuix 默认配色）
 * - Monet：MonetSystem / MonetLight / MonetDark + 种子色（自定义或系统壁纸动态色）
 *   + [ThemePaletteStyle]（9 种配色风格）+ [ThemeColorSpec]（2021/2025 规格）
 *
 * 所有参数来自可观察的 [ThemeState]，变更实时触发 recomposition。
 *
 * 许可说明：基于 miuix（Apache 2.0）公共 API 实现；设置组织参考 SukiSU-Ultra（GPLv3）
 * 设计，不包含其源代码。详见 THIRD_PARTY_NOTICES.md。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = view.context as? Activity
    val dark = resolveDarkMode()

    val schemeMode = when {
        ThemeState.monetEnabled -> when (ThemeState.mode) {
            AppThemeUtils.MODE_LIGHT -> ColorSchemeMode.MonetLight
            AppThemeUtils.MODE_DARK -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetSystem
        }

        else -> when (ThemeState.mode) {
            AppThemeUtils.MODE_LIGHT -> ColorSchemeMode.Light
            AppThemeUtils.MODE_DARK -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.System
        }
    }
    val keyColor = if (ThemeState.keyColor != 0) Color(ThemeState.keyColor) else null
    val paletteStyle = runCatching {
        ThemePaletteStyle.valueOf(ThemeState.paletteStyle)
    }.getOrDefault(ThemePaletteStyle.TonalSpot)
    val colorSpec = runCatching {
        ThemeColorSpec.valueOf(ThemeState.colorSpec)
    }.getOrDefault(ThemeColorSpec.Spec2021)

    val controller = remember(schemeMode, keyColor, paletteStyle, colorSpec, dark) {
        ThemeController(
            colorSchemeMode = schemeMode,
            keyColor = keyColor,
            paletteStyle = paletteStyle,
            colorSpec = colorSpec,
            isDark = dark,
        )
    }

    CurrentThemeConfigs.isDark = dark

    SideEffect {
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }

    MiuixTheme(
        controller = controller,
        content = {
            CurrentThemeConfigs.primary = MiuixTheme.colorScheme.primary
            content()
        }
    )
}

@Composable
private fun resolveDarkMode(): Boolean =
    when (ThemeState.mode) {
        AppThemeUtils.MODE_LIGHT -> false
        AppThemeUtils.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
