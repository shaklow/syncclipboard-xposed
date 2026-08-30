package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.Intent
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.app.util.AppLangUtils
import io.github.erenche.syncclipboard.app.util.resolveLanguageName
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
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
fun SettingsScreen(
    bottomPadding: Dp = 0.dp,
    canBack: Boolean = true,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    AppToolBarListContainer(
        title = stringResource(R.string.activity_settings),
        canBack = canBack,
        onBack = { activity?.finish() },
        bottomPadding = bottomPadding
    ) {
        // 1. 主题设置入口（模式 / Monet 配色 / 底栏样式均在主题页内）
        item("theme") {
            Card(
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.setting_theme),
                    summary = themeSummary(context),
                    onClick = {
                        context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
                    }
                )
            }
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

        // 5. 日志设置
        item("logging") {
            LoggingSettingsCard(context)
        }

        // 6. 自动保存设置
        item("auto_save") {
            AutoSaveSettingsCard(context)
        }

        // 7. 存储清理（缓存 + 引擎数据）
        item("cache") {
            StorageSettingsCard(context)
        }

        // 8. 关于
        item("about") {
            Card(
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)
                    .fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_about_app),
                    summary = stringResource(R.string.item_about_app_summary),
                    onClick = {
                        context.startActivity(Intent(context, AboutActivity::class.java))
                    }
                )
            }
        }
    }
}

// ─── 主题设置（已迁移至 ThemeSettingsActivity）──────────────────

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
    // 响应式配置：服务器切换等外部修改实时刷新本卡片
    var config by remember { mutableStateOf(Prefs.loadConfig(context)) }
    DisposableEffect(context) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_CONFIG) config = Prefs.loadConfig(context)
        }
        Prefs.registerConfigListener(context, listener)
        onDispose { Prefs.unregisterConfigListener(context, listener) }
    }

    val autoSync = config.enableAutoSync
    val bgUpload = config.enableBackgroundUpload
    val bgDownload = config.enableBackgroundDownload
    val stopOnBattery = config.stopPollingOnBatterySaver
    val stopOnScreenOff = config.stopPollingOnScreenOff
    val stopOnMobileData = config.disconnectOnMobileData
    val pollingIntervalSec = config.pollingIntervalSec.coerceAtLeast(1)
    val screenOffDelaySec = config.screenOffDisconnectDelaySec
    val smsUpload = config.enableSmsUpload
    val notifUpload = config.enableNotificationUpload

    // 当前激活服务器类型：SyncClipboard 官方服务器显示息屏断开/省电断开设置，
    // 其他模式（WebDAV/S3）保留轮询间隔与息屏/省电停止轮询
    val isSyncClipboardServer =
        config.servers.getOrNull(config.activeServerIndex)?.type == io.github.erenche.syncclipboard.common.model.ServerType.syncclipboard

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

    fun pushConfig(newConfig: AppConfig) {
        config = newConfig
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

    /**
     * 判断本应用的"通知访问权限"是否已授予。
     * NotificationListenerService 的授权状态由系统管理，需通过
     * enabled_notification_listeners 检查本服务是否被启用。
     */
    fun isNotificationListenerEnabled(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val componentName = android.content.ComponentName(context, io.github.erenche.syncclipboard.app.receiver.NotificationListener::class.java)
        val target = componentName.flattenToString()
        return flat.split(":").any { it == target }
    }

    // 运行时权限申请 launcher（RECEIVE_SMS 是危险权限，需要运行时申请）
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pushConfig(config.copy(enableSmsUpload = true))
        }
        // 拒绝时保持开关关闭
    }

    // 通知访问权限设置页跳转 launcher：用户从系统设置返回后检查授权状态
    val notifListenerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isNotificationListenerEnabled()) {
            pushConfig(config.copy(enableNotificationUpload = true))
        }
        // 未授权则保持开关关闭
    }

    // 总开关：关闭→子开关一并关闭；打开→若两个子开关都关则默认都开
    fun toggleAutoSync(enabled: Boolean) {
        if (enabled) {
            val restoreUpload = bgUpload || bgDownload
            val newUpload = if (restoreUpload) bgUpload else true
            val newDownload = if (restoreUpload) bgDownload else true
            pushConfig(config.copy(enableAutoSync = true, enableBackgroundUpload = newUpload, enableBackgroundDownload = newDownload))
        } else {
            pushConfig(config.copy(enableAutoSync = false, enableBackgroundUpload = false, enableBackgroundDownload = false))
        }
    }

    // 子开关：当两个子开关都被关闭时，总开关自动关闭（双向联动）
    fun toggleBgUpload(enabled: Boolean) {
        val newAutoSync = !(!enabled && !bgDownload)
        pushConfig(config.copy(enableBackgroundUpload = enabled, enableAutoSync = newAutoSync))
    }

    fun toggleBgDownload(enabled: Boolean) {
        val newAutoSync = !(!enabled && !bgUpload)
        pushConfig(config.copy(enableBackgroundDownload = enabled, enableAutoSync = newAutoSync))
    }

    fun toggleStopOnBattery(enabled: Boolean) {
        pushConfig(config.copy(stopPollingOnBatterySaver = enabled))
    }

    fun toggleStopOnScreenOff(enabled: Boolean) {
        pushConfig(config.copy(stopPollingOnScreenOff = enabled))
    }

    fun toggleStopOnMobileData(enabled: Boolean) {
        pushConfig(config.copy(disconnectOnMobileData = enabled))
    }

    fun updatePollingInterval(sec: Int) {
        pushConfig(config.copy(pollingIntervalSec = sec))
    }

    fun updateScreenOffDisconnectDelay(sec: Int) {
        pushConfig(config.copy(screenOffDisconnectDelaySec = sec))
    }

    fun toggleSmsUpload(enabled: Boolean) {
        if (enabled) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                pushConfig(config.copy(enableSmsUpload = true))
            } else {
                smsPermissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
            }
        } else {
            pushConfig(config.copy(enableSmsUpload = false))
        }
    }

    fun toggleNotificationUpload(enabled: Boolean) {
        if (enabled) {
            if (isNotificationListenerEnabled()) {
                pushConfig(config.copy(enableNotificationUpload = true))
            } else {
                // 跳转到系统"通知访问权限"设置页，用户授权后返回时由 launcher 回调核对状态
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    notifListenerLauncher.launch(intent)
                } catch (e: Exception) {
                    Logger.warn("SettingsActivity", "Failed to open notification listener settings: ${e.message}")
                }
            }
        } else {
            pushConfig(config.copy(enableNotificationUpload = false))
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
                if (isSyncClipboardServer) {
                    // ─── SyncClipboard 官方服务器模式：SignalR 推送，省电优化项 ───
                    val screenOffOptions = listOf(0, 60, 300, 600, 900, 1800, 3600)
                    val disabledLabel = stringResource(R.string.setting_screen_off_disconnect_disabled)
                    val screenOffLabels = remember(screenOffOptions, disabledLabel) {
                        screenOffOptions.map { sec ->
                            if (sec == 0) disabledLabel else "${sec / 60}min"
                        }
                    }
                    val selectedScreenOffIndex = remember(screenOffDelaySec, screenOffOptions) {
                        var idx = screenOffOptions.indexOf(screenOffDelaySec)
                        if (idx < 0) idx = 0 // 默认不启用
                        idx
                    }
                    OverlayDropdownPreference(
                        title = stringResource(R.string.setting_screen_off_disconnect),
                        // 选中"不断开连接"时描述变化；其他选项显示延迟断开说明
                        summary = if (screenOffOptions[selectedScreenOffIndex] == 0) {
                            stringResource(R.string.setting_screen_off_keep_connected_summary)
                        } else {
                            stringResource(
                                R.string.setting_screen_off_disconnect_summary,
                                screenOffLabels[selectedScreenOffIndex]
                            )
                        },
                        items = screenOffLabels,
                        selectedIndex = selectedScreenOffIndex,
                        onSelectedIndexChange = { index ->
                            updateScreenOffDisconnectDelay(screenOffOptions[index])
                        }
                    )
                    SwitchPreference(
                        checked = stopOnBattery,
                        title = stringResource(R.string.setting_disconnect_battery_saver),
                        summary = stringResource(R.string.setting_disconnect_battery_saver_summary),
                        onCheckedChange = { toggleStopOnBattery(it) }
                    )
                    SwitchPreference(
                        checked = stopOnMobileData,
                        title = stringResource(R.string.setting_disconnect_mobile_data),
                        summary = stringResource(R.string.setting_disconnect_mobile_data_summary),
                        onCheckedChange = { toggleStopOnMobileData(it) }
                    )
                } else {
                    // ─── WebDAV/S3 模式：保留轮询相关设置 ───
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
                    SwitchPreference(
                        checked = stopOnMobileData,
                        title = stringResource(R.string.setting_stop_polling_mobile_data),
                        summary = stringResource(R.string.setting_stop_polling_mobile_data_summary),
                        onCheckedChange = { toggleStopOnMobileData(it) }
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
                }
            }
        }
        // 短信验证码自动上传：不依赖自动同步总开关，关闭自动同步后仍可见
        SwitchPreference(
            checked = smsUpload,
            title = stringResource(R.string.setting_sms_upload),
            summary = stringResource(R.string.setting_sms_upload_summary),
            onCheckedChange = { toggleSmsUpload(it) }
        )
        // 通知验证码自动上传：监听所有应用通知，独立于短信开关
        SwitchPreference(
            checked = notifUpload,
            title = stringResource(R.string.setting_notification_upload),
            summary = stringResource(R.string.setting_notification_upload_summary),
            onCheckedChange = { toggleNotificationUpload(it) }
        )
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
    val configInit = remember { Prefs.loadConfig(context) }
    var enableLogging by remember { mutableStateOf(configInit.enableLogging) }
    var logLevel by remember { mutableStateOf(configInit.logLevel) }
    var logBufferSize by remember { mutableStateOf(configInit.logBufferSize) }

    val levelOptions = remember { io.github.erenche.syncclipboard.common.model.LogLevel.entries }
    val levelLabels = listOf(
        stringResource(R.string.log_level_debug),
        stringResource(R.string.log_level_info),
        stringResource(R.string.log_level_warn),
        stringResource(R.string.log_level_error)
    )
    val bufferOptions = remember { listOf(500, 1000, 2000, 5000, 10000) }
    val bufferLabels = remember(bufferOptions) {
        bufferOptions.map { "${it}" }
    }

    fun pushConfig(newConfig: AppConfig) {
        try {
            Prefs.saveConfig(context, newConfig)
            Logger.enabled = newConfig.enableLogging
            Logger.logLevel = newConfig.logLevel
            Logger.maxBufferSize = newConfig.logBufferSize
            val configJson = Json.encodeToString(AppConfig.serializer(), newConfig)
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
            onCheckedChange = { enabled ->
                enableLogging = enabled
                pushConfig(Prefs.loadConfig(context).copy(enableLogging = enabled))
            }
        )
        AnimatedVisibility(
            visible = enableLogging,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                val selectedLevelIndex = remember(logLevel, levelOptions) {
                    levelOptions.indexOf(logLevel).coerceAtLeast(0)
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.setting_log_level),
                    summary = stringResource(R.string.setting_log_level_summary),
                    items = levelLabels,
                    selectedIndex = selectedLevelIndex,
                    onSelectedIndexChange = { index ->
                        val newLevel = levelOptions[index]
                        logLevel = newLevel
                        pushConfig(Prefs.loadConfig(context).copy(logLevel = newLevel))
                    }
                )
            }
        }
        val selectedBufferIndex = remember(logBufferSize, bufferOptions) {
            var idx = bufferOptions.indexOf(logBufferSize)
            if (idx < 0) idx = 2 // 默认 2000
            idx
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.setting_log_buffer_size),
            summary = stringResource(R.string.setting_log_buffer_size_summary, logBufferSize),
            items = bufferLabels,
            selectedIndex = selectedBufferIndex,
            onSelectedIndexChange = { index ->
                val newSize = bufferOptions[index]
                logBufferSize = newSize
                pushConfig(Prefs.loadConfig(context).copy(logBufferSize = newSize))
            }
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

// ─── 存储清理（缓存 + 引擎数据合并卡片）───────────────────────
@Composable
fun StorageSettingsCard(context: android.content.Context) {
    var cacheSize by remember { mutableStateOf(getCacheSize(context)) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    // 引擎侧数据大小（SystemUI 私有目录，需 IPC 查询；null = 查询中/不可用）
    var engineSize by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun queryEngineSize(): Long? = try {
        val result = SyncClipboardBridge.with(context)
            .to(PackageNames.SYSTEM_UI)
            .key(BridgeKeys.GET_ENGINE_STORAGE_SIZE)
            .await()
        result.getLong("bytes", 0L)
    } catch (_: Exception) {
        null
    }

    LaunchedEffect(Unit) { engineSize = queryEngineSize() }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        // 清理缓存（app 侧预览文件等，无害操作）
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

        // 清理引擎数据（引擎侧历史/下载/临时文件，破坏性操作需确认）
        ArrowPreference(
            title = stringResource(R.string.item_clean_engine_data),
            summary = engineSize?.let {
                stringResource(R.string.item_clean_engine_data_summary_size, formatFileSize(it))
            } ?: stringResource(R.string.item_clean_engine_data_summary),
            onClick = { showCleanupDialog = true }
        )
    }

    OverlayDialog(
        show = showCleanupDialog,
        title = stringResource(R.string.item_clean_engine_data),
        summary = stringResource(R.string.clean_engine_data_confirm),
        onDismissRequest = { showCleanupDialog = false }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { showCleanupDialog = false },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.action_confirm),
                onClick = {
                    showCleanupDialog = false
                    SyncClipboardBridge.with(context)
                        .to(PackageNames.SYSTEM_UI)
                        .key(BridgeKeys.CLEAR_ENGINE_DATA)
                        .send()
                    // 引擎异步清理，稍后重查大小
                    scope.launch {
                        kotlinx.coroutines.delay(1500)
                        engineSize = queryEngineSize()
                    }
                    android.widget.Toast.makeText(
                        context,
                        R.string.clean_engine_data_done,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.weight(1f)
            )
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
