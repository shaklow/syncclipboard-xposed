package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.util.AppThemeUtils
import io.github.erenche.syncclipboard.app.util.ThemeColor
import io.github.erenche.syncclipboard.app.util.ThemeState
import io.github.erenche.syncclipboard.app.util.UiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 主题设置页 — 深色模式 / Monet（壁纸与种子色）配色设置。
 *
 * 移植自 SukiSU-Ultra 的 ColorPaletteScreen（miuix 版）：
 * - 主题模式下拉（沿用原设置页样式）
 * - Monet 开关：开启后由 materialKolor（经 miuix ThemeController）从种子色生成全套配色
 * - 种子色（SukiSU 同款 16 色调色板）、配色风格（9 种）、颜色规格（2021/2025）
 * - 底栏样式（模糊 / 悬浮底栏 / 液态玻璃）
 *
 * 所有变更实时生效（ThemeState 可观察），无需重启 App。
 *
 * 许可说明：本文件为本项目自有代码（Apache 2.0），Monet 配色能力基于 miuix
 * ThemeController 公共 API（Apache 2.0）实现；UI 组织与调色板仅参考 SukiSU-Ultra（GPLv3）
 * 的设计，不包含其源代码。详见 THIRD_PARTY_NOTICES.md。
 */
class ThemeSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ThemeSettingsScreen() }
    }
}

@Composable
fun ThemeSettingsScreen(
    bottomPadding: Dp = 0.dp,
    canBack: Boolean = true,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val modeItems = listOf(
        stringResource(R.string.option_theme_system),
        stringResource(R.string.option_theme_light),
        stringResource(R.string.option_theme_dark),
    )
    val currentMode = ThemeState.mode
    val monetEnabled = ThemeState.monetEnabled

    AppToolBarListContainer(
        title = stringResource(R.string.setting_theme),
        canBack = canBack,
        onBack = { activity?.finish() },
        bottomPadding = bottomPadding,
    ) {
        // ── 主题模式（沿用原设置页的下拉样式）──────────────────
        item("mode") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.setting_theme_mode),
                    items = modeItems,
                    selectedIndex = currentMode.coerceIn(0, 2),
                    onSelectedIndexChange = { index ->
                        ThemeState.updateMode(context, index)
                    },
                )
            }
        }

        // ── Monet 配色 ─────────────────────────────────────────
        item("monet") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(R.string.settings_monet),
                    summary = stringResource(R.string.settings_monet_summary),
                    checked = monetEnabled,
                    onCheckedChange = {
                        ThemeState.updateMonet(context, it)
                    },
                )

                AnimatedVisibility(visible = monetEnabled) {
                    Column {
                        // 种子色：默认（跟随系统壁纸）+ SukiSU 同款 15 色
                        val colorItems = ThemeColor.entries.map { colorItem ->
                            stringResource(
                                when (colorItem) {
                                    ThemeColor.DEFAULT -> R.string.color_default
                                    ThemeColor.RED -> R.string.color_red
                                    ThemeColor.PINK -> R.string.color_pink
                                    ThemeColor.PURPLE -> R.string.color_purple
                                    ThemeColor.DEEP_PURPLE -> R.string.color_deep_purple
                                    ThemeColor.INDIGO -> R.string.color_indigo
                                    ThemeColor.BLUE -> R.string.color_blue
                                    ThemeColor.CYAN -> R.string.color_cyan
                                    ThemeColor.TEAL -> R.string.color_teal
                                    ThemeColor.GREEN -> R.string.color_green
                                    ThemeColor.YELLOW -> R.string.color_yellow
                                    ThemeColor.AMBER -> R.string.color_amber
                                    ThemeColor.ORANGE -> R.string.color_orange
                                    ThemeColor.BROWN -> R.string.color_brown
                                    ThemeColor.BLUE_GREY -> R.string.color_blue_grey
                                    ThemeColor.SAKURA -> R.string.color_sakura
                                }
                            )
                        }
                        val colorValues = ThemeColor.entries.map { it.argb }
                        val keyColor = ThemeState.keyColor
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_key_color),
                            items = colorItems,
                            selectedIndex = colorValues.indexOf(keyColor).takeIf { it >= 0 } ?: 0,
                            onSelectedIndexChange = { index ->
                                ThemeState.updateKeyColor(context, colorValues[index])
                            },
                        )

                        // 配色风格（miuix ThemePaletteStyle，9 种）
                        val paletteStyles = ThemePaletteStyle.entries
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_color_style),
                            items = paletteStyles.map { it.name },
                            selectedIndex = paletteStyles
                                .indexOfFirst { it.name == ThemeState.paletteStyle }
                                .coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                ThemeState.updatePaletteStyle(context, paletteStyles[index].name)
                            },
                        )

                        // 颜色规格（2021 / 2025）
                        val colorSpecs = ThemeColorSpec.entries
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_color_spec),
                            items = colorSpecs.map { it.name },
                            selectedIndex = colorSpecs
                                .indexOfFirst { it.name == ThemeState.colorSpec }
                                .coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                ThemeState.updateColorSpec(context, colorSpecs[index].name)
                            },
                        )
                    }
                }
            }
        }

        // ── 底栏样式（与主题同属外观设置）────────────────────
        item("bottom_bar") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(R.string.setting_blur),
                    summary = stringResource(R.string.setting_blur_summary),
                    checked = UiState.blur,
                    onCheckedChange = { UiState.updateBlur(context, it) }
                )
                SwitchPreference(
                    title = stringResource(R.string.setting_floating_bottom_bar),
                    summary = stringResource(R.string.setting_floating_bottom_bar_summary),
                    checked = UiState.floatingBottomBar,
                    onCheckedChange = { UiState.updateFloatingBottomBar(context, it) }
                )
                // 液态玻璃仅在悬浮底栏开启时可用
                AnimatedVisibility(visible = UiState.floatingBottomBar) {
                    SwitchPreference(
                        title = stringResource(R.string.setting_bottom_bar_blur),
                        summary = stringResource(R.string.setting_bottom_bar_blur_summary),
                        checked = UiState.bottomBarBlur,
                        onCheckedChange = { UiState.updateBottomBarBlur(context, it) }
                    )
                }
            }
        }
    }
}

/** 当前主题摘要（设置页入口 summary 用） */
fun themeSummary(context: Context): String {
    val mode = when (ThemeState.mode) {
        AppThemeUtils.MODE_LIGHT -> context.getString(R.string.option_theme_light)
        AppThemeUtils.MODE_DARK -> context.getString(R.string.option_theme_dark)
        else -> context.getString(R.string.option_theme_system)
    }
    val colorPart = if (ThemeState.monetEnabled) {
        val name = if (ThemeState.keyColor == 0) {
            context.getString(R.string.color_default)
        } else {
            val color = ThemeColor.entries.firstOrNull { it.argb == ThemeState.keyColor }
            if (color != null) {
                when (color) {
                    ThemeColor.RED -> context.getString(R.string.color_red)
                    ThemeColor.PINK -> context.getString(R.string.color_pink)
                    ThemeColor.PURPLE -> context.getString(R.string.color_purple)
                    ThemeColor.DEEP_PURPLE -> context.getString(R.string.color_deep_purple)
                    ThemeColor.INDIGO -> context.getString(R.string.color_indigo)
                    ThemeColor.BLUE -> context.getString(R.string.color_blue)
                    ThemeColor.CYAN -> context.getString(R.string.color_cyan)
                    ThemeColor.TEAL -> context.getString(R.string.color_teal)
                    ThemeColor.GREEN -> context.getString(R.string.color_green)
                    ThemeColor.YELLOW -> context.getString(R.string.color_yellow)
                    ThemeColor.AMBER -> context.getString(R.string.color_amber)
                    ThemeColor.ORANGE -> context.getString(R.string.color_orange)
                    ThemeColor.BROWN -> context.getString(R.string.color_brown)
                    ThemeColor.BLUE_GREY -> context.getString(R.string.color_blue_grey)
                    ThemeColor.SAKURA -> context.getString(R.string.color_sakura)
                    ThemeColor.DEFAULT -> context.getString(R.string.color_default)
                }
            } else {
                context.getString(R.string.color_default)
            }
        }
        "${context.getString(R.string.settings_monet)} · $name"
    } else {
        context.getString(R.string.color_default)
    }
    return "$mode · $colorPart"
}
