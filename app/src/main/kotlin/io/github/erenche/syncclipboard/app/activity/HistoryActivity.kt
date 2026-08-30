package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.net.ServerApi
import io.github.erenche.syncclipboard.app.transfer.HistoryTransferQueue
import io.github.erenche.syncclipboard.app.transfer.TransferState
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.BridgeSecurity
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.ServerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowListPopup
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

class HistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HistoryScreen() }
    }

    companion object {
        /** 图片预览缓存：itemId -> 已下载的本地文件 */
        val previewCache = mutableMapOf<String, File>()
    }
}
/**
 * 剪贴板历史页面 — Miuix 全组件风格。
 * 通过 bridge 从 SystemUI 进程的 SyncEngine 加载数据。
 */
@OptIn(FlowPreview::class)
@Composable
fun HistoryScreen(
    bottomPadding: Dp = 0.dp,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var pageSize by remember { mutableStateOf(50) }
    var currentPage by remember { mutableStateOf(1) }
    // 列表滚动状态：翻页后内容切换即回顶（无可视滚动跳变）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 翻页来源：顶部翻页条 = 内容切换式（不回顶，仅条目渐显）；
    // 底部翻页条 = 瞬移回顶 + 条目渐显（不做滚动动画）
    var pageSourceBottom by remember { mutableStateOf(false) }
    // 条目区渐显动画（翻页时播一次；完成后值恒定 1f，滚动不会重放/闪变）
    val pageFade = remember { androidx.compose.animation.core.Animatable(1f) }
    // 翻页加载中：禁用分页按钮、指示器显示加载态，避免重复点击
    var paging by remember { mutableStateOf(false) }
    // 多选模式：选中的记录 id 集合，非空即进入多选模式
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedIds.isNotEmpty()

    // 传输队列进行中任务数（角标）
    val transferTasks by HistoryTransferQueue.tasks.collectAsState()
    val transferActive = transferTasks.count {
        it.state == TransferState.PENDING || it.state == TransferState.DOWNLOADING
    }
    // 右上角菜单
    var showHistoryMenu by remember { mutableStateOf(false) }

    // 服务端分页：totalPages 基于服务端返回的 totalCount
    val totalPages = remember(totalCount, pageSize) {
        if (totalCount == 0) 1 else (totalCount + pageSize - 1) / pageSize
    }

    // 多选模式下按返回键先退出多选，而非结束页面
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    fun toggleSelect(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    // ─── 数据加载（服务端分页）────────────────────────────────────

    suspend fun loadHistoryPage(page: Int = currentPage) {
        try {
            val offset = (page - 1) * pageSize
            val payload = Bundle().apply {
                putInt("offset", offset)
                putInt("limit", pageSize)
                if (searchQuery.isNotBlank()) putString("searchText", searchQuery)
            }
            val bundle = SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.GET_HISTORY_PAGED)
                .payload(payload)
                .await(timeout = 10000)
            val json = bundle.getString("items")
            items = if (!json.isNullOrBlank()) {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(ListSerializer(HistoryItem.serializer()), json)
            } else {
                emptyList()
            }
            totalCount = bundle.getInt("totalCount", 0)
        } catch (_: Exception) {
        } finally {
            loading = false
            refreshing = false
            paging = false
        }
    }

    fun refreshFromServer() {
        // WebDAV/S3 模式不支持从服务器获取剪贴板历史，仅刷新本地数据
        val config = Prefs.loadConfig(context)
        val activeServer = config.activeServerIndex.let { idx ->
            if (idx in config.servers.indices) config.servers[idx] else null
        }
        if (activeServer?.type != ServerType.syncclipboard) {
            refreshing = true
            scope.launch { loadHistoryPage(currentPage) }
            return
        }
        refreshing = true
        scope.launch {
            try {
                val result = SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.FORCE_SYNC_HISTORY)
                    .await(timeout = 10000)
                val started = result.getBoolean("started", false)
                if (!started) {
                    val error = result.getString("error") ?: "Sync failed"
                    Toast.makeText(context, "同步失败: $error", Toast.LENGTH_LONG).show()
                    refreshing = false
                } else {
                    // 同步在后台进行，等待 EVENT_HISTORY_SYNC_COMPLETED 广播
                    Toast.makeText(context, "正在从服务器同步...", Toast.LENGTH_SHORT).show()
                    // refreshing 保持 true，由广播接收器收到完成后置 false；
                    // 兜底：广播丢失/同步超时时 15s 后强制停止转圈
                    scope.launch {
                        delay(15000)
                        refreshing = false
                    }
                }
            } catch (e: Exception) {
                refreshing = false
                Toast.makeText(context, "同步异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 删除单条记录（同步到 SystemUI + 本地列表移除）。 */
    fun deleteItem(id: String) {
        scope.launch {
            val payload = Bundle().apply { putString("id", id) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.DELETE_HISTORY_ITEM)
                .payload(payload)
                .send()
        }
        items = items.filterNot { it.id == id }
        HistoryActivity.previewCache.remove(id)
    }

    /** 切换收藏状态（同步到 SystemUI 触发即时 PATCH + 本地列表乐观更新）。 */
    fun toggleStarItem(id: String) {
        scope.launch {
            val payload = Bundle().apply {
                putString("id", id)
                putString("action", "toggleStar")
            }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.UPDATE_HISTORY_ITEM)
                .payload(payload)
                .send()
        }
        // 乐观更新本地列表的收藏状态
        items = items.map { if (it.id == id) it.copy(starred = !it.starred) else it }
    }

    /** 批量删除选中的记录。 */
    fun deleteSelected() {
        val ids = selectedIds
        if (ids.isEmpty()) return
        scope.launch {
            ids.forEach { id ->
                val payload = Bundle().apply { putString("id", id) }
                SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.DELETE_HISTORY_ITEM)
                    .payload(payload)
                    .send()
            }
        }
        items = items.filterNot { it.id in ids }
        ids.forEach { HistoryActivity.previewCache.remove(it) }
        selectedIds = emptySet()
    }

    // ─── 生命周期 ────────────────────────────────────────────────

    // 首次进入加载第 1 页
    LaunchedEffect(Unit) { loadHistoryPage(1) }

    // 翻页 / 切换每页数量 → 重新加载对应页。
    // 动画设计（内容切换式，全部渐显）：
    // - 顶部翻页 / 切页数 / 搜索重置：内容替换后保持当前滚动位置（不回顶），条目渐显
    // - 底部翻页：内容替换后瞬移回顶（内容已更换，无可视滚动），条目渐显
    //   注意：不做 animateScrollToItem(0) 滚动动画（用户反馈效果不佳）；
    //   不使用 animateItem 的 fadeIn —— 它在条目滚动回收后会重放导致闪变。
    LaunchedEffect(currentPage, pageSize) {
        if (!loading || items.isNotEmpty()) {
            loadHistoryPage(currentPage)
            if (pageSourceBottom) {
                // 底部翻页：瞬移回顶（新页内容已在，无可见滚动）
                listState.scrollToItem(0)
            }
            // 条目渐显（仅条目，搜索框/统计/分页条不动；播放一次，完成后值恒定）
            pageFade.snapTo(0f)
            pageFade.animateTo(
                1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 240,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
        }
    }

    // 搜索框防抖（500ms）：重置到第 1 页并触发服务端查询
    // drop(1) 跳过初始空值，避免与首次加载重复
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery }
            .drop(1)
            .debounce(500)
            .distinctUntilChanged()
            .collect {
                if (currentPage != 1) {
                    currentPage = 1
                } else {
                    loadHistoryPage(1)
                }
            }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch { loadHistoryPage(currentPage) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        // 剪贴板变化：刷新列表
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!BridgeSecurity.isTrustedSender(ctx)) return
                if (intent.action == BridgeKeys.EVENT_CLIPBOARD_CHANGED) {
                    scope.launch { loadHistoryPage(currentPage) }
                }
            }
        }
        val filter = IntentFilter(BridgeKeys.EVENT_CLIPBOARD_CHANGED)
        ContextCompat.registerReceiver(
            context, receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // 历史同步完成：刷新列表并提示结果
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!BridgeSecurity.isTrustedSender(ctx)) return
                scope.launch { loadHistoryPage(currentPage) }
                val success = intent.getBooleanExtra("success", false)
                if (success) {
                    val fetched = intent.getIntExtra("fetched", -1)
                    val localCount = intent.getIntExtra("count", -1)
                    Toast.makeText(
                        context,
                        "同步完成: 服务器 $fetched 条, 本地 $localCount 条",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val error = intent.getStringExtra("error") ?: "未知错误"
                    Toast.makeText(context, "同步失败: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(BridgeKeys.EVENT_HISTORY_SYNC_COMPLETED),
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    // ─── UI ──────────────────────────────────────────────────────

    AppToolBarListContainer(
        title = if (selectionMode)
            stringResource(R.string.history_selected_count, selectedIds.size)
        else
            stringResource(R.string.activity_history),
        canBack = !embedded,
        onBack = { if (selectionMode) selectedIds = emptySet() else if (!embedded) activity?.finish() },
        isRefreshing = refreshing,
        onRefresh = { refreshFromServer() },
        bottomPadding = bottomPadding,
        listState = listState,
        actions = {
            if (!loading && items.isNotEmpty()) {
                if (selectionMode) {
                    // 多选模式：批量下载（仅图片/文件项计入）+ 删除选中
                    val downloadCount = items.count {
                        it.id in selectedIds &&
                            (it.type == ClipboardContentType.Image || it.type == ClipboardContentType.File) &&
                            it.hasData
                    }
                    if (downloadCount > 0) {
                        TextButton(
                            text = stringResource(R.string.transfer_download_selected, downloadCount),
                            onClick = {
                                val selected = items.filter { it.id in selectedIds }
                                HistoryTransferQueue.enqueue(context, selected)
                                selectedIds = emptySet()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.transfer_added, selected.size),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 36.dp,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.history_delete_selected, selectedIds.size),
                        onClick = { deleteSelected() },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        minHeight = 36.dp,
                        minWidth = 0.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    )
                } else {
                    // 右上角菜单：传输队列 + 清空历史（与主界面重启菜单同款样式）
                    IconButton(
                        onClick = { showHistoryMenu = true },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = stringResource(R.string.action_more),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    val menuLabels = listOf(
                        if (transferActive > 0) {
                            stringResource(R.string.transfer_menu_with_count, transferActive)
                        } else {
                            stringResource(R.string.activity_transfer)
                        },
                        stringResource(R.string.action_clear)
                    )
                    WindowListPopup(
                        show = showHistoryMenu,
                        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                        alignment = PopupPositionProvider.Align.TopEnd,
                        onDismissRequest = { showHistoryMenu = false },
                        content = {
                            ListPopupColumn {
                                menuLabels.forEachIndexed { index, label ->
                                    DropdownImpl(
                                        text = label,
                                        optionSize = menuLabels.size,
                                        isSelected = false,
                                        index = index,
                                        onSelectedIndexChange = { selectedIdx ->
                                            showHistoryMenu = false
                                            when (selectedIdx) {
                                                0 -> context.startActivity(
                                                    Intent(context, TransferActivity::class.java)
                                                )
                                                1 -> {
                                                    scope.launch {
                                                        SyncClipboardBridge.with(context)
                                                            .to("com.android.systemui")
                                                            .key(BridgeKeys.CLEAR_HISTORY)
                                                            .send()
                                                        items = emptyList()
                                                        Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    ) {
        when {
            loading -> {
                item("loading") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                            .fillMaxWidth()
                    ) {
                        BasicComponent(title = stringResource(R.string.main_loading))
                    }
                }
            }

            items.isEmpty() -> {
                item("empty") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                            .fillMaxWidth()
                    ) {
                        BasicComponent(title = stringResource(R.string.history_empty))
                    }
                }
            }

            else -> {
                item("search_bar") {
                    val tfValue = remember(searchQuery) {
                        TextFieldValue(
                            text = searchQuery,
                            selection = TextRange(searchQuery.length)
                        )
                    }
                    TextField(
                        label = stringResource(R.string.history_search),
                        value = tfValue,
                        onValueChange = { searchQuery = it.text },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true,
                    )
                }

                item("stats_row") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallTitle(
                            text = stringResource(R.string.history_total_count, totalCount),
                            insideMargin = PaddingValues(0.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.history_page_size),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                            listOf(50, 100).forEach { size ->
                                TextButton(
                                    text = size.toString(),
                                    onClick = {
                                        if (pageSize != size) {
                                            pageSize = size
                                            currentPage = 1
                                        }
                                    },
                                    colors = if (pageSize == size)
                                        ButtonDefaults.textButtonColorsPrimary()
                                    else
                                        ButtonDefaults.textButtonColors(),
                                    minHeight = 32.dp,
                                    minWidth = 0.dp,
                                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                if (items.isEmpty()) {
                    item("no_results") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth()
                        ) {
                            BasicComponent(title = stringResource(R.string.history_no_results))
                        }
                    }
                } else {
                    if (totalPages > 1) {
                        item("pager_top") {
                            HistoryPaginationBar(
                                currentPage = currentPage,
                                totalPages = totalPages,
                                paging = paging,
                                onPageChange = { page ->
                                    if (!paging && page != currentPage) {
                                        paging = true
                                        pageSourceBottom = false
                                        currentPage = page
                                    }
                                }
                            )
                        }
                    }

                    items(items, key = { it.id }) { historyItem ->
                        SwipeableHistoryCard(
                            item = historyItem,
                            context = context,
                            scope = scope,
                            selectionMode = selectionMode,
                            selected = historyItem.id in selectedIds,
                            onToggleSelect = { toggleSelect(historyItem.id) },
                            onDelete = { deleteItem(historyItem.id) },
                            onToggleStar = { toggleStarItem(historyItem.id) },
                            // 禁用 item 级 fadeIn（滚动回收会重放导致闪变），只保留弹簧位移；
                            // 渐显由 pageFade 一次性驱动，完成值恒定不闪
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = null,
                                    placementSpec = folmeSpring(damping = 0.9f, response = 0.22f),
                                    fadeOutSpec = null,
                                )
                                .graphicsLayer { alpha = pageFade.value },
                        )
                    }

                    if (totalPages > 1) {
                        item("pager_bottom") {
                            HistoryPaginationBar(
                                currentPage = currentPage,
                                totalPages = totalPages,
                                paging = paging,
                                onPageChange = { page ->
                                    if (!paging && page != currentPage) {
                                        paging = true
                                        pageSourceBottom = true
                                        currentPage = page
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        item("footer") { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
// ─── 分页控件 ──────────────────────────────────────────────────

@Composable
private fun HistoryPaginationBar(
    currentPage: Int,
    totalPages: Int,
    paging: Boolean,
    onPageChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onPageChange(currentPage - 1) },
            enabled = !paging && currentPage > 1,
            minHeight = 36.dp,
            minWidth = 0.dp,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(stringResource(R.string.history_page_prev))
        }
        Text(
            text = stringResource(R.string.history_page_indicator, currentPage, totalPages),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Button(
            onClick = { onPageChange(currentPage + 1) },
            enabled = !paging && currentPage < totalPages,
            minHeight = 36.dp,
            minWidth = 0.dp,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(stringResource(R.string.history_page_next))
        }
    }
}

// ─── 可左滑删除 / 右滑收藏 / 长按多选的记录卡片 ──────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableHistoryCard(
    item: HistoryItem,
    context: Context,
    scope: CoroutineScope,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(16.dp)
    // 长文本展开状态
    var expanded by remember(item.id) { mutableStateOf(false) }

    // 滑动偏移量（0f = 原位，正 = 右滑，负 = 左滑）。
    // 用普通 state 同步更新，避免 Animatable + launch 的帧级延迟导致卡片跟不上手指
    var offsetX by remember(item.id) { mutableStateOf(0f) }
    // 卡片宽度（像素），用于计算触发阈值和左滑退出距离
    var cardWidthPx by remember { mutableStateOf(0) }
    // 回弹/滑出动画的 Job：拖动开始时取消，防止动画与拖动手势互相覆盖
    var settleJob by remember(item.id) { mutableStateOf<Job?>(null) }

    val currentOnToggleStar by rememberUpdatedState(onToggleStar)
    val currentOnDelete by rememberUpdatedState(onDelete)

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // ── 背景层：根据偏移方向显示收藏或删除背景 ──
        // 静止（|offsetX| 小于 1px）时隐藏，避免按压缩放（Sink）露出红色/主题色边框。
        // 注意用阈值而非 ==0：spring 回弹浮点不会精确归零
        val bgAlpha by animateFloatAsState(
            targetValue = if (abs(offsetX) < 1f) 0f else 1f,
            animationSpec = folmeSpring(damping = 0.9f, response = 0.05f),
            label = "swipeBgAlpha",
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(cardShape)
                // alpha 必须在 background 之前（外层）：graphicsLayer 只作用于其后/更内层的绘制，
                // 放在 background 后面时背景色不受透明度影响，红色会一直露出来
                .alpha(bgAlpha)
                .background(
                    if (offsetX > 0f) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.error
                ),
            contentAlignment = if (offsetX > 0f) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            if (offsetX > 0f) {
                // 右滑：收藏背景
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 24.dp)
                ) {
                    Text(
                        text = if (item.starred) "☆" else "★",
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                    Text(
                        text = stringResource(
                            if (item.starred) R.string.history_unstar else R.string.history_star
                        ),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            } else {
                // 左滑：删除背景
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MiuixTheme.colorScheme.onError,
                    modifier = Modifier
                        .padding(end = 24.dp)
                        .size(24.dp),
                )
            }
        }

        // ── 前景层：可滑动的卡片 ──
        // 交互（点击展开/长按多选）与按压反馈交由 Miuix Card 处理，
        // 滑动手势在此层独立注册，与点击互斥（drag 消费事件后点击自动取消）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .onSizeChanged { cardWidthPx = it.width }
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(selectionMode, item.id) {
                    if (selectionMode) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            // 开始拖动：取消进行中的回弹/滑出动画，以手指为准
                            settleJob?.cancel()
                            settleJob = null
                        },
                        onDragEnd = {
                            val threshold = cardWidthPx * 0.25f
                            val current = offsetX
                            when {
                                current > threshold -> {
                                    // 右滑超过阈值 → 收藏 + 回弹
                                    currentOnToggleStar()
                                    settleJob = scope.launch {
                                        val anim = Animatable(offsetX)
                                        anim.animateTo(0f, folmeSpring(damping = 0.9f, response = 0.35f)) { offsetX = value }
                                        offsetX = 0f // 精确归零，避免浮点残留
                                    }
                                }
                                current < -threshold -> {
                                    // 左滑超过阈值 → 滑出 + 删除
                                    settleJob = scope.launch {
                                        val anim = Animatable(offsetX)
                                        anim.animateTo(-cardWidthPx.toFloat(), folmeSpring(damping = 0.9f, response = 0.24f)) { offsetX = value }
                                        currentOnDelete()
                                    }
                                }
                                else -> {
                                    // 未超过阈值 → 回弹（结束后精确归零）
                                    settleJob = scope.launch {
                                        val anim = Animatable(offsetX)
                                        anim.animateTo(0f, folmeSpring(damping = 0.9f, response = 0.3f)) { offsetX = value }
                                        offsetX = 0f
                                    }
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            // 若回弹动画进行中则先取消，由手指接管
                            settleJob?.cancel()
                            settleJob = null
                            offsetX += dragAmount
                        }
                    )
                },
            // 不用 Sink 缩放反馈：按压缩放会与点击展开的高度动画叠加导致鬼畜，改为无缩放
            pressFeedbackType = PressFeedbackType.None,
            onClick = {
                if (selectionMode) onToggleSelect() else expanded = !expanded
            },
            onLongPress = { if (!selectionMode) onToggleSelect() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        state = if (selected) ToggleableState.On else ToggleableState.Off,
                        onClick = { onToggleSelect() },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    HistoryItemRow(
                        item = item,
                        context = context,
                        showActions = !selectionMode,
                        expanded = expanded,
                    )
                }
            }
        }
    }
}
// ─── 单条历史记录内容（无删除按钮，删除走左滑） ────────────────

@Composable
private fun HistoryItemRow(
    item: HistoryItem,
    context: Context,
    showActions: Boolean = true,
    expanded: Boolean = false,
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    // 图片预览
    var previewFile by remember(item.id) {
        mutableStateOf(HistoryActivity.previewCache[item.id])
    }
    var previewLoading by remember(item.id) { mutableStateOf(false) }

    if (item.type == ClipboardContentType.Image && item.hasData && previewFile == null) {
        LaunchedEffect(item.id, item.dataName) {
            previewLoading = true
            try {
                val config = Prefs.loadConfig(context)
                val server = config.servers.getOrNull(config.activeServerIndex)
                if (server != null) {
                    val api = ServerApi(server)
                    val safeName = item.dataName ?: "img_${item.id}"
                    val destFile = File(context.cacheDir, "hist_${item.id}_$safeName")
                    val downloaded = withContext(Dispatchers.IO) {
                        api.downloadHistoryData(item.type, item.profileHash, destFile)
                    }
                    if (downloaded != null) {
                        HistoryActivity.previewCache[item.id] = downloaded
                        previewFile = downloaded
                    }
                }
            } catch (_: Exception) {
            } finally {
                previewLoading = false
            }
        }
    }

    // 类型标签与内容文本
    val typeLabel = when (item.type) {
        ClipboardContentType.Image -> stringResource(R.string.type_image)
        ClipboardContentType.File -> stringResource(R.string.type_file)
        ClipboardContentType.Text -> stringResource(R.string.type_text)
        ClipboardContentType.Group -> stringResource(R.string.type_group)
    }
    val typeLabelColor = when (item.type) {
        ClipboardContentType.Image, ClipboardContentType.File -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onBackgroundVariant
    }
    val contentText = when (item.type) {
        ClipboardContentType.Image, ClipboardContentType.File -> item.dataName ?: item.text
        else -> item.text
    }

    BasicComponent(
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .wrapContentSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = typeLabel,
                    style = MiuixTheme.textStyles.body2,
                    color = typeLabelColor,
                )
            }
        },
    ) {
        // animateContentSize 包裹内容区，使展开/收起平滑过渡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                )
        ) {
            // 主内容文本：折叠时最多 3 行，点击卡片展开完整内容
            Text(
                text = contentText,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 日期 + 操作按钮同一行，节省空间
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateFormat.format(Date(item.timestamp)),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                if (item.starred) {
                    Text(
                        text = "★",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (showActions) {
                    if (item.type == ClipboardContentType.Text) {
                        TextButton(
                            text = stringResource(R.string.action_copy),
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("SyncClipboard", item.text))
                                Toast.makeText(context, R.string.history_copied, Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 32.dp,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if ((item.type == ClipboardContentType.Image || item.type == ClipboardContentType.File)
                        && item.hasData
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_download),
                            onClick = {
                                HistoryTransferQueue.enqueue(context, listOf(item))
                                Toast.makeText(context, R.string.transfer_added_single, Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 32.dp,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (item.type == ClipboardContentType.Image) {
        when {
            previewLoading -> {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                BasicComponent(
                    title = stringResource(R.string.main_loading),
                    titleColor = BasicComponentDefaults.summaryColor(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            previewFile != null -> {
                val bitmap = remember(previewFile) {
                    BitmapFactory.decodeFile(previewFile!!.absolutePath)
                }
                if (bitmap != null) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

