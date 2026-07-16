package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.net.ServerApi
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var pageSize by remember { mutableStateOf(50) }
    var currentPage by remember { mutableStateOf(1) }
    // 多选模式：选中的记录 id 集合，非空即进入多选模式
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedIds.isNotEmpty()

    // 本地过滤（服务端不支持搜索）
    val filteredItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) items
        else items.filter {
            it.text.contains(searchQuery, ignoreCase = true) ||
                (it.dataName?.contains(searchQuery, ignoreCase = true) == true)
        }
    }
    val totalPages = remember(filteredItems.size, pageSize) {
        if (filteredItems.isEmpty()) 1 else (filteredItems.size + pageSize - 1) / pageSize
    }
    val pagedItems = remember(filteredItems, currentPage, pageSize) {
        val start = (currentPage - 1) * pageSize
        if (start >= filteredItems.size) emptyList()
        else filteredItems.subList(start, minOf(start + pageSize, filteredItems.size))
    }
    // 搜索条件或每页条数变化时重置到第1页
    LaunchedEffect(searchQuery, pageSize) { currentPage = 1 }

    // 多选模式下按返回键先退出多选，而非结束页面
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    fun toggleSelect(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    // ─── 数据加载 ────────────────────────────────────────────────

    suspend fun loadHistory() {
        try {
            val bundle = SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.GET_HISTORY)
                .await(timeout = 15000)
            val json = bundle.getString("items")
            items = if (!json.isNullOrBlank()) {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(ListSerializer(HistoryItem.serializer()), json)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
        } finally {
            loading = false
            refreshing = false
        }
    }

    fun refreshFromServer() {
        refreshing = true
        scope.launch {
            try {
                val result = SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.FORCE_SYNC_HISTORY)
                    .await(timeout = 60000)
                loadHistory()
                val success = result.getBoolean("success")
                if (!success) {
                    val error = result.getString("error") ?: "Sync failed"
                    Toast.makeText(context, "同步失败: $error", Toast.LENGTH_LONG).show()
                } else {
                    val fetched = result.getInt("fetched", -1)
                    val localCount = result.getInt("count", -1)
                    Toast.makeText(
                        context,
                        "同步完成: 服务器 $fetched 条, 本地 $localCount 条",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                loadHistory()
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

    LaunchedEffect(Unit) { loadHistory() }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch { loadHistory() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                scope.launch { loadHistory() }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(BridgeKeys.EVENT_CLIPBOARD_CHANGED),
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
        canBack = true,
        onBack = { if (selectionMode) selectedIds = emptySet() else activity?.finish() },
        isRefreshing = refreshing,
        onRefresh = { refreshFromServer() },
        actions = {
            if (!loading && items.isNotEmpty()) {
                if (selectionMode) {
                    // 多选模式：清空按钮变为“删除选中”
                    TextButton(
                        text = stringResource(R.string.history_delete_selected, selectedIds.size),
                        onClick = { deleteSelected() },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        minHeight = 36.dp,
                        minWidth = 0.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    )
                } else {
                    TextButton(
                        text = stringResource(R.string.action_clear),
                        onClick = {
                            scope.launch {
                                SyncClipboardBridge.with(context)
                                    .to("com.android.systemui")
                                    .key(BridgeKeys.CLEAR_HISTORY)
                                    .send()
                                items = emptyList()
                                Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        minHeight = 36.dp,
                        minWidth = 0.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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
                // 搜索框（普通项，随列表滚动，无灰色背景）
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

                // 统计行：总条数 + 每页条数切换
                item("stats_row") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallTitle(
                            text = stringResource(R.string.history_total_count, filteredItems.size),
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
                                    onClick = { pageSize = size },
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

                // 无搜索结果
                if (filteredItems.isEmpty()) {
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
                    // 顶部分页
                    if (totalPages > 1) {
                        item("pager_top") {
                            HistoryPaginationBar(
                                currentPage = currentPage,
                                totalPages = totalPages,
                                onPageChange = { currentPage = it }
                            )
                        }
                    }

                    // 历史记录列表：每条独立 Card，左滑删除，长按多选
                    items(pagedItems, key = { it.id }) { historyItem ->
                        SwipeableHistoryCard(
                            item = historyItem,
                            context = context,
                            scope = scope,
                            selectionMode = selectionMode,
                            selected = historyItem.id in selectedIds,
                            onToggleSelect = { toggleSelect(historyItem.id) },
                            onDelete = { deleteItem(historyItem.id) },
                            // 删除 / 补位动画
                            modifier = Modifier.animateItem(),
                        )
                    }

                    // 底部分页
                    if (totalPages > 1) {
                        item("pager_bottom") {
                            HistoryPaginationBar(
                                currentPage = currentPage,
                                totalPages = totalPages,
                                onPageChange = { currentPage = it }
                            )
                        }
                    }
                }
            }
        }

        // 底部留白
        item("footer") { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
// ─── 分页控件 ──────────────────────────────────────────────────

@Composable
private fun HistoryPaginationBar(
    currentPage: Int,
    totalPages: Int,
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
            enabled = currentPage > 1,
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
            enabled = currentPage < totalPages,
            minHeight = 36.dp,
            minWidth = 0.dp,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(stringResource(R.string.history_page_next))
        }
    }
}

// ─── 可左滑删除 / 长按多选的记录卡片 ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableHistoryCard(
    item: HistoryItem,
    context: Context,
    scope: CoroutineScope,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(16.dp)
    // 长文本展开状态
    var expanded by remember(item.id) { mutableStateOf(false) }

    // 左滑：松手确认后先让卡片滑出，再从列表移除（配合 animateItem 补位动画）
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // 只允许左滑方向，且非多选模式；返回 true 使其停在“已滑出”状态并播放退出动画
            !selectionMode && value == SwipeToDismissBoxValue.EndToStart
        }
    )

    // 当滑动稳定到“已滑出”状态时，执行删除（此时卡片已移出屏幕，删除后下方内容平滑补位）
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp),
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            // 左滑露出的红色删除背景 + 图标
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
                    .background(Color(0xFFE84C3D)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = Color.White,
                    modifier = Modifier
                        .padding(end = 24.dp)
                        .size(24.dp),
                )
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .combinedClickable(
                    // 非多选：点击展开/收起长文本；多选：点击切换勾选
                    onClick = {
                        if (selectionMode) onToggleSelect() else expanded = !expanded
                    },
                    onLongClick = { if (!selectionMode) onToggleSelect() },
                ),
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
        ClipboardContentType.Image -> MiuixTheme.colorScheme.primary
        ClipboardContentType.File -> Color(0xFFFF9800)
        else -> MiuixTheme.colorScheme.onBackgroundVariant
    }
    val contentText = when (item.type) {
        ClipboardContentType.Image, ClipboardContentType.File -> item.dataName ?: item.text
        else -> item.text
    }

    BasicComponent(
        startAction = {
            // 类型徽章
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
                    // 文本：复制
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
                    // 图片/文件：下载保存
                    if ((item.type == ClipboardContentType.Image || item.type == ClipboardContentType.File)
                        && item.hasData
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_download),
                            onClick = { scope.launch { downloadHistoryFile(context, item) } },
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

    // 图片预览区（分隔线 + 图片）
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

// ─── 下载历史文件到本地 ────────────────────────────────────────

private suspend fun downloadHistoryFile(context: Context, item: HistoryItem) {
    try {
        val config = Prefs.loadConfig(context)
        val server = config.servers.getOrNull(config.activeServerIndex)
        if (server == null) {
            Toast.makeText(context, "未配置服务器", Toast.LENGTH_SHORT).show()
            return
        }
        val api = ServerApi(server)
        val fileName = item.dataName ?: "file_${System.currentTimeMillis()}"
        val destFile = File(context.cacheDir, "dl_$fileName")
        val downloaded = withContext(Dispatchers.IO) {
            api.downloadHistoryData(item.type, item.profileHash, destFile)
        }
        if (downloaded == null) {
            Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
            return
        }
        val resolver = context.contentResolver
        if (item.type == ClipboardContentType.Image) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/*")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    downloaded.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } else {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "*/*")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    downloaded.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(context, "已保存到下载", Toast.LENGTH_SHORT).show()
            }
        }
        downloaded.delete()
    } catch (e: Exception) {
        Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

