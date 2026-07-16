package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.SyncClipboardApp
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.viewmodel.MainViewModel
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity(), SyncClipboardApp.XposedServiceStateListener {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen(viewModel) }
        SyncClipboardApp.addXposedServiceStateListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncClipboardApp.removeXposedServiceStateListener(this)
    }

    override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
        viewModel.isModuleActive.value = service != null
        if (service != null) {
            lifecycleScope.launch {
                try {
                    val config = Prefs.loadConfig(this@MainActivity)
                    val configJson = Json.encodeToString(AppConfig.serializer(), config)
                    val payload = android.os.Bundle().apply { putString("config", configJson) }
                    SyncClipboardBridge.with(this@MainActivity)
                        .key(BridgeKeys.PUSH_CONFIG)
                        .payload(payload)
                        .send()
                    SyncClipboardBridge.with(this@MainActivity)
                        .to("com.android.systemui")
                        .key(BridgeKeys.PUSH_CONFIG)
                        .payload(payload)
                        .send()
                } catch (_: Exception) {}
            }
        }
    }
}

/** 重启 App */
private fun restartApp(activity: Activity) {
    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    activity.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

/** 重启 SystemUI（需要 Root 权限） */
private fun restartSystemUI(context: Context) {
    try {
        // 使用 pkill 终止 SystemUI，系统会自动重启它
        // 注意：必须消费 stdout/stderr 防止进程挂起
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f com.android.systemui"))
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        // pkill 找到并杀死进程返回 0；即使 SystemUI 被杀导致 su 会话中断也视为成功
        if (exitCode != 0 && stderr.isBlank() && stdout.isBlank()) {
            android.widget.Toast.makeText(context, context.getString(R.string.restart_no_root), android.widget.Toast.LENGTH_SHORT).show()
        }
        // 成功时不提示（SystemUI 会被系统自动重启）
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, context.getString(R.string.restart_no_root), android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current

    // Push config to SystemUI on startup
    LaunchedEffect(Unit) {
        try {
            val config = Prefs.loadConfig(context)
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (_: Exception) {}
        // 加载服务器最新内容
        viewModel.refreshRemoteContent()
    }

    // 监听内容变化广播，自动刷新服务器最新内容
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                viewModel.refreshRemoteContent()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BridgeKeys.EVENT_CLIPBOARD_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val isLoadingRemote by viewModel.isLoadingRemote
    var showRestartPopup by remember { mutableStateOf(false) }
    val restartItems = listOf(
        stringResource(R.string.action_restart_app),
        stringResource(R.string.action_restart_systemui)
    )

    AppToolBarListContainer(
        title = stringResource(R.string.app_name),
        isRefreshing = isLoadingRemote,
        onRefresh = {
            viewModel.refreshStatus()
            viewModel.refreshRemoteContent()
        },
        actions = {
            IconButton(
                onClick = { showRestartPopup = true },
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
            WindowListPopup(
                show = showRestartPopup,
                popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                alignment = PopupPositionProvider.Align.TopEnd,
                onDismissRequest = { showRestartPopup = false },
                content = {
                    ListPopupColumn {
                        restartItems.forEachIndexed { index, label ->
                            DropdownImpl(
                                text = label,
                                optionSize = restartItems.size,
                                isSelected = false,
                                index = index,
                                onSelectedIndexChange = { selectedIdx ->
                                    showRestartPopup = false
                                    when (selectedIdx) {
                                        0 -> restartApp(context as Activity)
                                        1 -> restartSystemUI(context)
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) {
        item("status") { StatusCard(viewModel) }
        item("remote_content") { RemoteContentCard(viewModel) }
        item("sync_controls") { SyncControlsCard(viewModel) }
        item("history") {
            Card(
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, end = 16.dp
                ).fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_history),
                    summary = stringResource(R.string.item_history_summary),
                    onClick = {
                        context.startActivity(Intent(context, HistoryActivity::class.java))
                    }
                )
            }
        }
        item("more") {
            Card(
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp
                ).fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_more),
                    summary = stringResource(R.string.item_more_summary),
                    onClick = {
                        context.startActivity(Intent(context, MoreActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
fun StatusCard(viewModel: MainViewModel) {
    val isActive by viewModel.isModuleActive
    val syncStatus by viewModel.syncStatus
    val bgColor = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336)
    val statusIcon = if (isActive) MiuixIcons.Ok else MiuixIcons.Info

    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardColors(bgColor, Color.White)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(
                        if (isActive) R.string.module_status_activated
                        else R.string.module_status_not_activated
                    ),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.main_sync_status, syncStatus),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun SyncControlsCard(viewModel: MainViewModel) {
    val isBusy by viewModel.isBusy
    val toast by viewModel.toast.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let { msg ->
            android.widget.Toast.makeText(
                context, msg, android.widget.Toast.LENGTH_SHORT
            ).show()
            viewModel.onToastShown()
        }
    }

    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        ArrowPreference(
            title = stringResource(R.string.action_sync_now),
            summary = if (isBusy) "..." else stringResource(R.string.main_sync_now_desc),
            onClick = { if (!isBusy) viewModel.triggerSync() }
        )
        ArrowPreference(
            title = stringResource(R.string.action_upload_now),
            summary = if (isBusy) "..." else stringResource(R.string.main_upload_now_desc),
            onClick = { if (!isBusy) viewModel.uploadNow() }
        )
    }
}

@Composable
fun RemoteContentCard(viewModel: MainViewModel) {
    val profile by viewModel.remoteProfile
    val downloadedFile by viewModel.downloadedFile
    val isLoading by viewModel.isLoadingRemote
    val context = LocalContext.current

    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.main_remote_content),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Text(
                        text = stringResource(R.string.main_loading),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                profile == null -> {
                    Text(
                        text = stringResource(R.string.main_no_content),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                else -> {
                    val p = profile!!
                    // 类型标签
                    val typeLabel = when (p.type) {
                        ClipboardContentType.Text -> stringResource(R.string.type_text)
                        ClipboardContentType.Image -> stringResource(R.string.type_image)
                        ClipboardContentType.File -> stringResource(R.string.type_file)
                        ClipboardContentType.Group -> stringResource(R.string.type_group)
                    }
                    Text(
                        text = "$typeLabel · ${p.text.take(100)}",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    p.size?.let { size ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatFileSize(size),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    // 图片预览
                    if (p.type == ClipboardContentType.Image && downloadedFile != null) {
                        val bitmap = remember(downloadedFile) {
                            BitmapFactory.decodeFile(downloadedFile!!.absolutePath)
                        }
                        bitmap?.let {
                            Spacer(modifier = Modifier.height(12.dp))
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Preview",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                            )
                        }
                    }

                    // 文件操作按钮
                    if (downloadedFile != null && (p.type == ClipboardContentType.Image || p.type == ClipboardContentType.File)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = stringResource(R.string.action_view),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    openFile(context, downloadedFile!!, p.type)
                                }
                            )
                            Spacer(modifier = Modifier.width(20.dp))
                            Text(
                                text = stringResource(R.string.action_download),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    downloadToGallery(context, downloadedFile!!, p.type)
                                }
                            )
                            Spacer(modifier = Modifier.width(20.dp))
                            Text(
                                text = stringResource(R.string.action_share),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    shareFile(context, downloadedFile!!, p.type)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openFile(context: android.content.Context, file: File, contentType: ClipboardContentType) {
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = if (contentType == ClipboardContentType.Image) "image/*" else "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "打开失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: android.content.Context, file: File, contentType: ClipboardContentType) {
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = if (contentType == ClipboardContentType.Image) "image/*" else "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun downloadToGallery(context: android.content.Context, file: File, contentType: ClipboardContentType) {
    try {
        if (contentType == ClipboardContentType.Image) {
            // 图片保存到 MediaStore.Images
            val resolver = context.contentResolver
            val fileName = file.name.removePrefix("preview_")
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/*")
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                android.widget.Toast.makeText(context, "已保存到相册", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            // 其他文件保存到 Downloads
            val resolver = context.contentResolver
            val fileName = file.name.removePrefix("preview_")
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "*/*")
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                android.widget.Toast.makeText(context, "已保存到下载", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
