package io.github.erenche.syncclipboard.app.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
 * 参考 Material Design 3 色调（Tone）系统：
 * - 亮色模式：primary ≈ tone 40，primaryContainer ≈ tone 90，onPrimaryContainer ≈ tone 10
 * - 暗色模式：primary ≈ tone 80，primaryContainer ≈ tone 30，onPrimaryContainer ≈ tone 90
 *
 * 使用 [lerp] 在 primary 与黑/白之间插值，保持色相的同时调整明度和饱和度，
 * 比纯 alpha 叠加 surface 的方式更接近 MD3 视觉效果。
 */
private fun applyPrimaryColor(base: Colors, primary: Color, dark: Boolean): Colors {
    // 根据 primary 亮度决定 onPrimary（保证对比度）
    val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White

    // primaryContainer：亮色偏白(tone 90)，暗色偏黑(tone 30)
    val primaryContainer = if (dark) {
        lerp(primary, Color.Black, 0.6f)
    } else {
        lerp(primary, Color.White, 0.85f)
    }
    // onPrimaryContainer：亮色偏黑(tone 10)，暗色偏白(tone 90)
    val onPrimaryContainer = if (dark) {
        lerp(primary, Color.White, 0.7f)
    } else {
        lerp(primary, Color.Black, 0.7f)
    }
    // tertiaryContainer / onTertiaryContainer 同理
    val tertiaryContainer = if (dark) {
        lerp(primary, Color.Black, 0.5f)
    } else {
        lerp(primary, Color.White, 0.8f)
    }
    val onTertiaryContainer = if (dark) {
        lerp(primary, Color.White, 0.6f)
    } else {
        lerp(primary, Color.Black, 0.6f)
    }

    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryVariant = primary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        tertiaryContainerVariant = tertiaryContainer,
        // 滑块关键点：半透明 primary，前景根据 primary 亮度选黑/白保证可见
        sliderKeyPoint = primary.copy(alpha = if (dark) 0.4f else 0.3f),
        sliderKeyPointForeground = onPrimary,
    )
}
