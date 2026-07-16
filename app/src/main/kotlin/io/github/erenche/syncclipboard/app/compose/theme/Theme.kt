package io.github.erenche.syncclipboard.app.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import io.github.erenche.syncclipboard.app.util.AppThemeUtils
import io.github.erenche.syncclipboard.app.util.ThemeColor
import io.github.erenche.syncclipboard.app.util.ThemeState
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors

object CurrentThemeConfigs {
    var isDark: Boolean = false
    var primary: Color = Color.Transparent
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = view.context as? Activity
    // 从可观察的 ThemeState 读取，切换时实时触发 recomposition
    val dark = resolveDarkMode()
    val colors = resolveColors(context, dark)

    CurrentThemeConfigs.isDark = dark

    SideEffect {
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }

    MiuixTheme(
        colors = colors,
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
        AppThemeUtils.MODE_SYSTEM -> isSystemInDarkTheme()
        else -> isSystemInDarkTheme()
    }

@Composable
private fun resolveColors(context: android.content.Context, dark: Boolean): Colors {
    // 动态颜色优先
    if (ThemeState.monetEnabled) {
        return platformDynamicColors(dark)
    }

    val base = if (dark) darkColorScheme() else lightColorScheme()
    val themeColor = ThemeColor.fromId(ThemeState.themeColorId)
    // 默认颜色直接使用基础方案
    if (themeColor == ThemeColor.DEFAULT) return base

    val primary = if (dark) themeColor.darkPrimary else themeColor.lightPrimary
    return applyPrimaryColor(base, primary, dark)
}

/**
 * 将自定义 primary 颜色应用到基础颜色方案。
 *
 * 调整与 primary 相关的多个字段以保持整体视觉协调。
 */
private fun applyPrimaryColor(base: Colors, primary: Color, dark: Boolean): Colors {
    // 根据 primary 亮度决定 onPrimary（保证对比度）
    val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
    // primary 的半透明变体，用于容器类字段
    val primaryContainer = primary.copy(
        alpha = if (dark) 0.30f else 0.15f
    ).compositeOver(base.surface)
    val onPrimaryContainer = if (dark) primary else primary
    val tertiaryContainer = primary.copy(
        alpha = if (dark) 0.20f else 0.12f
    ).compositeOver(base.surface)

    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryVariant = primary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = primary,
        tertiaryContainerVariant = tertiaryContainer,
        // 滑块关键点颜色跟随 primary
        sliderKeyPoint = primary.copy(alpha = 0.3f),
        sliderKeyPointForeground = primary,
    )
}

/**
 * 将 [this]（带 alpha）合成到 [background] 之上。
 */
private fun Color.compositeOver(background: Color): Color {
    val fgAlpha = alpha
    if (fgAlpha >= 1f) return this
    val a = fgAlpha + background.alpha * (1f - fgAlpha)
    if (a <= 0f) return Color.Transparent
    val r = (red * fgAlpha + background.red * background.alpha * (1f - fgAlpha)) / a
    val g = (green * fgAlpha + background.green * background.alpha * (1f - fgAlpha)) / a
    val b = (blue * fgAlpha + background.blue * background.alpha * (1f - fgAlpha)) / a
    return Color(r, g, b, a)
}
