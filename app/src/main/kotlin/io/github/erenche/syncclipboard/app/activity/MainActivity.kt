package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.SyncClipboardApp
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.theme.AppTheme
import io.github.erenche.syncclipboard.app.compose.theme.CurrentThemeConfigs
import io.github.erenche.syncclipboard.app.component.BlurredBar
import io.github.erenche.syncclipboard.app.component.FloatingBottomBar
import io.github.erenche.syncclipboard.app.component.FloatingBottomBarItem
import io.github.erenche.syncclipboard.app.component.rememberBlurBackdrop
import io.github.erenche.syncclipboard.app.component.rememberMainPagerState
import io.github.erenche.syncclipboard.app.util.ThemeState
import io.github.erenche.syncclipboard.app.util.UiState
import io.github.erenche.syncclipboard.app.viewmodel.MainViewModel
import io.github.erenche.syncclipboard.app.viewmodel.UpdateInfo
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.BridgeSecurity
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
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

    override fun onResume() {
        super.onResume()
        // 缓存文件可能被"清除缓存"等外部操作删除，检测后重置预览状态
        if (viewModel.downloadedFile.value?.exists() != true) {
            viewModel.clearDownloadedState()
        }
    }

    override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
        viewModel.isModuleActive.value = service != null
        // 配置推送由 MainScreen 的 LaunchedEffect 统一负责（引擎端对相同配置幂等跳过），
        // 这里不再重复推送，避免 app 启动时多次 PUSH_CONFIG 触发 SignalR 重建
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

/** 底栏导航 tab 定义 */
private data class BottomTab(
    val labelRes: Int,
    val icon: ImageVector,
)

private val bottomTabs = listOf(
    BottomTab(R.string.nav_home, Icons.Rounded.Home),
    BottomTab(R.string.nav_history, Icons.Rounded.History),
    BottomTab(R.string.nav_server, Icons.Rounded.Cloud),
    BottomTab(R.string.nav_settings, Icons.Rounded.Settings),
)

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
        viewModel.refreshRemoteContent()
        viewModel.checkUpdate()
    }

    // 监听内容变化广播，只读取缓存刷新 UI（不触发服务端拉取，避免循环）
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!BridgeSecurity.isTrustedSender(context)) return
                viewModel.refreshRemoteContentCache()
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

    val floatingBar = UiState.floatingBottomBar
    // 液态玻璃：仅悬浮底栏生效
    val barBlur = UiState.bottomBarBlur && isRenderEffectSupported()
    // 服务器编辑页打开时隐藏底栏并禁用滑动切换
    val serverEditing = UiState.serverEditing

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { bottomTabs.size })
    val mainState = rememberMainPagerState(pagerState)

    // 滑动切换结束后回写选中页
    LaunchedEffect(pagerState.currentPage) {
        mainState.syncPage()
    }
    fun selectTab(index: Int) {
        mainState.animateToPage(index)
    }

    // 非 Home tab 按返回键回到主页
    BackHandler(enabled = mainState.selectedPage != 0) {
        selectTab(0)
    }

    AppTheme {
        val surfaceColor = MiuixTheme.colorScheme.surface
        // 常规 NavigationBar 的模糊背景
        val blurBackdrop = rememberBlurBackdrop(UiState.blur)
        // 悬浮底栏的液态玻璃背景
        val backdrop = rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }

        val bottomBar: @Composable () -> Unit = {
            // 编辑页打开时底栏整体滑出隐藏
            AnimatedVisibility(
                visible = !serverEditing,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                if (!floatingBar) {
                BlurredBar(blurBackdrop) {
                    NavigationBar(
                        color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
                    ) {
                        bottomTabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                modifier = Modifier.weight(1f),
                                icon = tab.icon,
                                label = stringResource(tab.labelRes),
                                selected = mainState.selectedPage == index,
                                onClick = { selectTab(index) },
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth()) {
                    FloatingBottomBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            )
                            .padding(
                                bottom = 12.dp + WindowInsets.navigationBars
                                    .asPaddingValues().calculateBottomPadding()
                            ),
                        selectedIndex = { mainState.selectedPage },
                        onSelected = { index -> selectTab(index) },
                        backdrop = backdrop,
                        tabsCount = bottomTabs.size,
                        isBlurEnabled = barBlur,
                    ) {
                        bottomTabs.forEachIndexed { index, tab ->
                            FloatingBottomBarItem(
                                onClick = { selectTab(index) },
                                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.labelRes),
                                )
                                Text(
                                    text = stringResource(tab.labelRes),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        Scaffold(bottomBar = { bottomBar() }) { innerPadding ->
            val bottomPad = innerPadding.calculateBottomPadding()
            Box(
                modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !serverEditing,
                    modifier = Modifier
                        .then(
                            if (floatingBar && barBlur) Modifier.layerBackdrop(backdrop) else Modifier
                        ),
                    beyondViewportPageCount = bottomTabs.size - 1,
                    overscrollEffect = null,
                ) { page ->
                    when (page) {
                        0 -> HomeTab(viewModel, bottomPad)
                        1 -> HistoryScreen(
            bottomPadding = bottomPad,
            embedded = true,
            active = mainState.selectedPage == 1,
        )
                        2 -> ServerConfigScreen(bottomPadding = bottomPad, canBack = false)
                        3 -> SettingsScreen(bottomPadding = bottomPad, canBack = false)
                    }
                }
            }
        }
    }
}

/** 主页 tab：状态 / 远程内容 / 同步操作 / 更多入口 */
@Composable
private fun HomeTab(viewModel: MainViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val isLoadingRemote by viewModel.isLoadingRemote
    val isActive by viewModel.isModuleActive
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
        bottomPadding = bottomPadding,
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
        item("status") {
            StatusCard(viewModel)
            // 模块激活且有新版本时显示更新提示
            val update by viewModel.updateInfo
            AnimatedVisibility(
                visible = isActive && update != null,
                enter = fadeIn() + expandVertically(),
                exit = shrinkVertically() + fadeOut()
            ) {
                UpdateCard(updateInfo = update) {
                    update?.releaseUrl?.let { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }
        }
        item("remote_content") { RemoteContentCard(viewModel) }
        item("sync_controls") { SyncControlsCard(viewModel) }
        item("log") {
            Card(
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp
                ).fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_log),
                    summary = stringResource(R.string.item_log_summary),
                    onClick = {
                        context.startActivity(Intent(context, LogActivity::class.java))
                    }
                )
            }
        }
    }
}

/** 新版本提示卡片（点击跳转 GitHub 最新 release） */
@Composable
private fun UpdateCard(updateInfo: UpdateInfo?, onClick: () -> Unit) {
    val isDark = CurrentThemeConfigs.isDark
    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 12.dp, end = 16.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardColors(
            color = if (isDark) Color(0xFF3E2F1B) else Color(0xFFFFF0DB),
            contentColor = Color(0xFFF5A623)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.update_available, updateInfo?.versionName ?: ""),
                fontSize = 14.sp,
                color = Color(0xFFF5A623)
            )
            Icon(
                imageVector = MiuixIcons.Info,
                contentDescription = null,
                tint = Color(0xFFF5A623)
            )
        }
    }
}

@Composable
fun StatusCard(viewModel: MainViewModel) {
    val isActive by viewModel.isModuleActive
    val syncStatus by viewModel.syncStatus
    // 状态色：Monet 开启时适配主题（激活=主题强调色，未激活=主题错误色）；
    // 关闭 Monet 时保持原来的绿色/红色
    val monet = ThemeState.monetEnabled
    val bgColor: Color = when {
        monet && isActive -> MiuixTheme.colorScheme.primary
        monet -> MiuixTheme.colorScheme.error
        isActive -> Color(0xFF4CAF50)
        else -> Color(0xFFF44336)
    }
    val contentColor: Color = when {
        monet -> if (isActive) MiuixTheme.colorScheme.onPrimary
        else MiuixTheme.colorScheme.onError
        else -> Color.White
    }
    val statusIcon = if (isActive) MiuixIcons.Ok else MiuixIcons.Info

    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardColors(bgColor, contentColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(
                        if (isActive) R.string.module_status_activated
                        else R.string.module_status_not_activated
                    ),
                    color = contentColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.main_sync_status, stringResource(syncStatus)),
                    color = contentColor.copy(alpha = 0.8f),
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

    // "上传文件"：系统文件选择器 → 选中后交给 ShareActivity 上传（复用上传进度界面）
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val activity = context as? Activity
            if (activity == null || activity.isFinishing) return@rememberLauncherForActivityResult
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            activity.startActivity(
                Intent(activity, ShareActivity::class.java).apply {
                    putExtra(ShareActivity.EXTRA_FILE_URI, uri.toString())
                    putExtra(ShareActivity.EXTRA_FILE_MIME, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }

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
        ArrowPreference(
            title = stringResource(R.string.action_upload_file),
            summary = stringResource(R.string.main_upload_file_desc),
            onClick = { filePicker.launch(arrayOf("*/*")) }
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
