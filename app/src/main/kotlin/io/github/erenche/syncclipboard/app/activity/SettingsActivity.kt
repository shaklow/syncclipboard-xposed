package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.app.util.AppLangUtils
import io.github.erenche.syncclipboard.app.util.AppThemeUtils
import io.github.erenche.syncclipboard.app.util.ThemeColor
import io.github.erenche.syncclipboard.app.util.ThemeState
import io.github.erenche.syncclipboard.app.util.resolveLanguageName
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

/**
 * 重启整个 App，让所有 Activity 重新应用主题/语言设置。
 *
 * 主题切换时仅 [recreate] 当前 Activity 会导致返回栈中的旧 Activity 仍用旧主题，
 * 因此需要清空任务栈并重新启动。
 */
private fun restartApp(activity: Activity?) {
    if (activity == null) return
    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
    intent.addFlags(
        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
    )
    activity.startActivity(intent)
    activity.finishAffinity()
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val activity = context as? Activity

    AppToolBarListContainer(
        title = stringResource(R.string.activity_settings),
        canBack = true,
        onBack = { activity?.finish() }
    ) {
        // 1. 主题设置
        item("theme") {
            ThemeSettingsCard(context, activity)
        }

        // 2. 语言切换
        item("language") {
            LanguageCard(context, activity)
        }

        // 3. 同步设置（含后台子开关）
        item("sync") {
            SyncSettingsCard(context)
        }

        // 4. 历史记录设置
        item("history") {
            HistorySettingsCard()
        }

        // 6. 日志设置
        item("logging") {
            LoggingSettingsCard(context)
        }

        // 7. 自动保存设置
        item("auto_save") {
            AutoSaveSettingsCard(context)
        }

        // 8. 缓存管理
        item("cache") {
            CacheSettingsCard(context)
        }
    }
}

// ─── 主题设置 ─────────────────────────────────────────────────
@Composable
fun ThemeSettingsCard(context: android.content.Context, activity: Activity?) {
    val themeModeOptions = listOf(
        R.string.option_theme_system to AppThemeUtils.MODE_SYSTEM,
        R.string.option_theme_light to AppThemeUtils.MODE_LIGHT,
        R.string.option_theme_dark to AppThemeUtils.MODE_DARK
    )
    var showColorPicker by remember { mutableStateOf(false) }
    val monetAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    // 直接读取可观察的 ThemeState，切换时实时触发 recomposition
    val currentMode = ThemeState.mode
    val monetEnabled = ThemeState.monetEnabled
    val currentColorId = ThemeState.themeColorId
    // Monet 开启时颜色选择不可用
    val colorEnabled = !monetEnabled || !monetAvailable

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        val selectedIndex = themeModeOptions.indexOfFirst { it.second == currentMode }
            .coerceAtLeast(0)

        OverlayDropdownPreference(
            title = stringResource(R.string.setting_theme_mode),
            items = themeModeOptions.map { stringResource(it.first) },
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                val newMode = themeModeOptions[index].second
                if (newMode != currentMode) {
                    ThemeState.updateMode(context, newMode)
                }
            }
        )

        // ── 主题颜色选择行 ──
        val currentThemeColor = ThemeColor.fromId(currentColorId)
        val isDark = io.github.erenche.syncclipboard.app.compose.theme.CurrentThemeConfigs.isDark
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = colorEnabled) { showColorPicker = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.setting_theme_color),
                    fontSize = 16.sp,
                    color = if (colorEnabled) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.disabledOnSurface
                )
                Text(
                    text = stringResource(R.string.setting_theme_color_summary),
                    fontSize = 13.sp,
                    color = if (colorEnabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
                        else MiuixTheme.colorScheme.disabledOnSurface
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = if (colorEnabled)
                            (if (isDark) currentThemeColor.darkPrimary else currentThemeColor.lightPrimary)
                        else MiuixTheme.colorScheme.disabledOnSurface,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .border(
                        width = 2.dp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
        }

        if (monetAvailable) {
            SwitchPreference(
                title = stringResource(R.string.setting_theme_monet),
                summary = stringResource(R.string.setting_theme_monet_summary),
                checked = monetEnabled,
                onCheckedChange = {
                    ThemeState.updateMonet(context, it)
                }
            )
        }
    }

    // ── 颜色选择对话框 ──
    if (showColorPicker) {
        ThemeColorPickerDialog(
            currentColorId = currentColorId,
            onColorSelected = { themeColor ->
                ThemeState.updateThemeColor(context, themeColor.id)
            },
            onDismiss = { showColorPicker = false }
        )
    }
}

/**
 * 主题颜色选择对话框 — 以网格形式展示预设颜色。
 */
@Composable
private fun ThemeColorPickerDialog(
    currentColorId: String,
    onColorSelected: (ThemeColor) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = io.github.erenche.syncclipboard.app.compose.theme.CurrentThemeConfigs.isDark
    OverlayDialog(
        show = true,
        title = stringResource(R.string.setting_theme_color),
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 4 列网格
            val rows = ThemeColor.entries.chunked(4)
            rows.forEach { rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowColors.forEach { themeColor ->
                        val isSelected = themeColor.id == currentColorId
                        val color = if (isDark) themeColor.darkPrimary else themeColor.lightPrimary
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                onColorSelected(themeColor)
                                onDismiss()
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(color = color, shape = androidx.compose.foundation.shape.CircleShape)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) MiuixTheme.colorScheme.onSurface
                                            else Color.Transparent,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = MiuixIcons.Ok,
                                        contentDescription = null,
                                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = themeColor.name.lowercase(),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── 语言切换 ─────────────────────────────────────────────────
@Composable
fun LanguageCard(context: android.content.Context, activity: Activity?) {
    val languageCodes = remember {
        buildList {
            addAll(context.resources.getStringArray(R.array.language_codes).toList())
            add(0, AppLangUtils.DEFAULT_LANGUAGE)
        }
    }

    val currentLanguage = remember { AppLangUtils.getCustomizeLang(context) }

    val spinnerItems = remember(languageCodes) {
        languageCodes.map { code ->
            val primaryName = context.resolveLanguageName(code)
            val fallbackName = context.resolveLanguageName(code, AppLangUtils.DEFAULT_LOCALE)
            SpinnerEntry(
                title = primaryName,
                summary = if (primaryName == fallbackName) null else fallbackName
            )
        }
    }

    val selectedIndex = remember(currentLanguage, languageCodes) {
        languageCodes.indexOf(currentLanguage).coerceAtLeast(0)
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        OverlaySpinnerPreference(
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            title = stringResource(R.string.setting_language),
            items = spinnerItems,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                val newLang = languageCodes[index]
                if (currentLanguage != newLang) {
                    AppLangUtils.saveCustomizeLanguage(context, newLang)
                    // 语言改变，重启整个 App 让所有 Activity 应用新语言
                    activity?.let { act ->
                        val intent = act.packageManager.getLaunchIntentForPackage(act.packageName)
                        intent?.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                        act.startActivity(intent)
                        act.finishAffinity()
                    }
                }
            }
        )
    }
}

// ─── 同步设置（含后台子开关与轮询设置）─────────────────────────────
@Composable
fun SyncSettingsCard(context: android.content.Context) {
    var autoSync by remember { mutableStateOf(Prefs.loadConfig(context).enableAutoSync) }
    var bgUpload by remember { mutableStateOf(Prefs.loadConfig(context).enableBackgroundUpload) }
    var bgDownload by remember { mutableStateOf(Prefs.loadConfig(context).enableBackgroundDownload) }
    var stopOnBattery by remember { mutableStateOf(Prefs.loadConfig(context).stopPollingOnBatterySaver) }
    var stopOnScreenOff by remember { mutableStateOf(Prefs.loadConfig(context).stopPollingOnScreenOff) }
    var pollingIntervalSec by remember { mutableStateOf(Prefs.loadConfig(context).pollingIntervalSec.coerceAtLeast(1)) }
    var smsUpload by remember { mutableStateOf(Prefs.loadConfig(context).enableSmsUpload) }

    val intervalOptions = remember { listOf(1, 3, 5, 10, 15, 30, 60, 120, 300, 600) }
    val intervalLabels = remember(intervalOptions) {
        intervalOptions.map { sec ->
            when {
                sec < 60 -> "${sec}s"
                sec % 60 == 0 -> "${sec / 60}min"
                else -> "${sec / 60}min${sec % 60}s"
            }
        }
    }

    // 运行时权限申请 launcher（RECEIVE_SMS 是危险权限，需要运行时申请）
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            smsUpload = true
            val newCfg = Prefs.loadConfig(context).copy(enableSmsUpload = true)
            try {
                Prefs.saveConfig(context, newCfg)
                val payload = android.os.Bundle().apply {
                    putString("config", Json.encodeToString(AppConfig.serializer(), newCfg))
                }
                SyncClipboardBridge.with(context).to("com.android.systemui")
                    .key(BridgeKeys.PUSH_CONFIG).payload(payload).send()
            } catch (_: Exception) {}
        }
        // 拒绝时保持开关关闭
    }

    fun pushConfig(newConfig: AppConfig) {
        try {
            Prefs.saveConfig(context, newConfig)
            val configJson = Json.encodeToString(AppConfig.serializer(), newConfig)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (_: Exception) {}
    }

    // 总开关：关闭→子开关一并关闭；打开→若两个子开关都关则默认都开
    fun toggleAutoSync(enabled: Boolean) {
        autoSync = enabled
        val config = Prefs.loadConfig(context)
        if (enabled) {
            val restoreUpload = bgUpload || bgDownload
            val newUpload = if (restoreUpload) bgUpload else true
            val newDownload = if (restoreUpload) bgDownload else true
            bgUpload = newUpload
            bgDownload = newDownload
            pushConfig(config.copy(enableAutoSync = true, enableBackgroundUpload = newUpload, enableBackgroundDownload = newDownload))
        } else {
            bgUpload = false
            bgDownload = false
            pushConfig(config.copy(enableAutoSync = false, enableBackgroundUpload = false, enableBackgroundDownload = false))
        }
    }

    // 子开关：当两个子开关都被关闭时，总开关自动关闭（双向联动）
    fun toggleBgUpload(enabled: Boolean) {
        bgUpload = enabled
        val config = Prefs.loadConfig(context)
        val newAutoSync = if (!enabled && !bgDownload) { autoSync = false; false } else true
        pushConfig(config.copy(enableBackgroundUpload = enabled, enableAutoSync = newAutoSync))
    }

    fun toggleBgDownload(enabled: Boolean) {
        bgDownload = enabled
        val config = Prefs.loadConfig(context)
        val newAutoSync = if (!enabled && !bgUpload) { autoSync = false; false } else true
        pushConfig(config.copy(enableBackgroundDownload = enabled, enableAutoSync = newAutoSync))
    }

    fun toggleStopOnBattery(enabled: Boolean) {
        stopOnBattery = enabled
        pushConfig(Prefs.loadConfig(context).copy(stopPollingOnBatterySaver = enabled))
    }

    fun toggleStopOnScreenOff(enabled: Boolean) {
        stopOnScreenOff = enabled
        pushConfig(Prefs.loadConfig(context).copy(stopPollingOnScreenOff = enabled))
    }

    fun updatePollingInterval(sec: Int) {
        pollingIntervalSec = sec
        pushConfig(Prefs.loadConfig(context).copy(pollingIntervalSec = sec))
    }

    fun toggleSmsUpload(enabled: Boolean) {
        if (enabled) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                smsUpload = true
                pushConfig(Prefs.loadConfig(context).copy(enableSmsUpload = true))
            } else {
                smsPermissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
            }
        } else {
            smsUpload = false
            pushConfig(Prefs.loadConfig(context).copy(enableSmsUpload = false))
        }
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = autoSync,
            title = stringResource(R.string.setting_auto_sync),
            summary = stringResource(R.string.setting_auto_sync_summary),
            onCheckedChange = { toggleAutoSync(it) }
        )
        AnimatedVisibility(
            visible = autoSync,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                SwitchPreference(
                    checked = bgUpload,
                    title = stringResource(R.string.setting_background_upload),
                    summary = stringResource(R.string.setting_background_upload_summary),
                    onCheckedChange = { toggleBgUpload(it) }
                )
                SwitchPreference(
                    checked = bgDownload,
                    title = stringResource(R.string.setting_background_download),
                    summary = stringResource(R.string.setting_background_download_summary),
                    onCheckedChange = { toggleBgDownload(it) }
                )
                SwitchPreference(
                    checked = stopOnBattery,
                    title = stringResource(R.string.setting_stop_polling_battery_saver),
                    summary = stringResource(R.string.setting_stop_polling_battery_saver_summary),
                    onCheckedChange = { toggleStopOnBattery(it) }
                )
                SwitchPreference(
                    checked = stopOnScreenOff,
                    title = stringResource(R.string.setting_stop_polling_screen_off),
                    summary = stringResource(R.string.setting_stop_polling_screen_off_summary),
                    onCheckedChange = { toggleStopOnScreenOff(it) }
                )
                val selectedIntervalIndex = remember(pollingIntervalSec, intervalOptions) {
                    var idx = intervalOptions.indexOf(pollingIntervalSec)
                    if (idx < 0) idx = 1 // 默认 3s
                    idx
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.setting_polling_interval),
                    summary = stringResource(R.string.setting_polling_interval_summary, pollingIntervalSec),
                    items = intervalLabels,
                    selectedIndex = selectedIntervalIndex,
                    onSelectedIndexChange = { index ->
                        updatePollingInterval(intervalOptions[index])
                    }
                )
                // 短信验证码自动上传
                SwitchPreference(
                    checked = smsUpload,
                    title = stringResource(R.string.setting_sms_upload),
                    summary = stringResource(R.string.setting_sms_upload_summary),
                    onCheckedChange = { toggleSmsUpload(it) }
                )
            }
        }
    }
}

// ─── 历史记录设置 ─────────────────────────────────────────────
@Composable
fun HistorySettingsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var historySync by remember {
        mutableStateOf(Prefs.loadConfig(context).enableHistorySync)
    }

    val onToggle: (Boolean) -> Unit = { enabled ->
        historySync = enabled
        try {
            // 更新 AppConfig 并持久化、推送到 xposed 进程
            val config = Prefs.loadConfig(context).copy(enableHistorySync = enabled)
            Prefs.saveConfig(context, config)
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (_: Exception) {}
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = historySync,
            title = stringResource(R.string.setting_history_sync),
            summary = stringResource(R.string.setting_history_sync_summary),
            onCheckedChange = onToggle
        )
    }
}

// ─── 日志设置 ─────────────────────────────────────────────────
@Composable
fun LoggingSettingsCard(context: android.content.Context) {
    var enableLogging by remember {
        mutableStateOf(Prefs.loadConfig(context).enableLogging)
    }

    val onToggle: (Boolean) -> Unit = { enabled ->
        enableLogging = enabled
        try {
            val config = Prefs.loadConfig(context).copy(enableLogging = enabled)
            Prefs.saveConfig(context, config)
            // 立即同步到 app 进程的 Logger
            Logger.enabled = enabled
            Logger.logLevel = config.logLevel
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (_: Exception) {}
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = enableLogging,
            title = stringResource(R.string.setting_enable_logging),
            summary = stringResource(R.string.setting_enable_logging_summary),
            onCheckedChange = onToggle
        )
    }
}

// ─── 自动保存设置 ─────────────────────────────────────────────
@Composable
fun AutoSaveSettingsCard(context: android.content.Context) {
    var enableAutoSave by remember {
        mutableStateOf(Prefs.loadConfig(context).enableAutoSave)
    }

    val onToggle: (Boolean) -> Unit = { enabled ->
        enableAutoSave = enabled
        try {
            val config = Prefs.loadConfig(context).copy(enableAutoSave = enabled)
            Prefs.saveConfig(context, config)
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (_: Exception) {}
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = enableAutoSave,
            title = stringResource(R.string.setting_auto_save),
            summary = stringResource(R.string.setting_auto_save_summary),
            onCheckedChange = onToggle
        )
    }
}

// ─── 缓存管理 ─────────────────────────────────────────────────
@Composable
fun CacheSettingsCard(context: android.content.Context) {
    var cacheSize by remember { mutableStateOf(getCacheSize(context)) }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.setting_cache),
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.setting_cache_summary, formatFileSize(cacheSize)),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
            Button(
                onClick = {
                    clearCache(context)
                    HistoryActivity.previewCache.clear()
                    cacheSize = getCacheSize(context)
                },
                enabled = cacheSize > 0
            ) {
                Text(text = stringResource(R.string.setting_cache_clear))
            }
        }
    }
}

/** 计算 app cacheDir 大小（字节） */
private fun getCacheSize(context: android.content.Context): Long {
    return try {
        val cacheDir = context.cacheDir
        if (cacheDir.exists()) cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    } catch (_: Exception) {
        0L
    }
}

/** 清除 app cacheDir 下所有文件 */
private fun clearCache(context: android.content.Context) {
    try {
        val cacheDir = context.cacheDir
        if (cacheDir.exists()) {
            cacheDir.walkBottomUp().forEach { if (it.isFile) it.delete() }
        }
    } catch (_: Exception) {
    }
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", size, units[unitIndex])
}
