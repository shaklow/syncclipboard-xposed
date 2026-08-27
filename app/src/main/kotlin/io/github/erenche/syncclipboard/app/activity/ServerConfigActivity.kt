package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.util.UiState
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.model.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 编辑目标：null = 服务器列表，-1 = 新建，>=0 = 编辑对应下标 */
private data class EditTarget(val index: Int)

/**
 * 服务器管理界面 — 列表（常驻背景层）与编辑页（前景层）。
 *
 * 编辑页支持**预测性返回**：从左边缘返回手势时编辑页跟随手指向右平移，
 * 松手提交则滑出返回列表、取消则回弹；顶部返回键/保存/删除走同一条滑出动画。
 */
@Composable
fun ServerConfigScreen(
    bottomPadding: Dp = 0.dp,
    canBack: Boolean = true,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var appConfig by remember { mutableStateOf(Prefs.loadConfig(context)) }
    // null = 列表；EditTarget(-1) = 新建；EditTarget(i) = 编辑第 i 个
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val showEdit = editTarget != null

    // 编辑状态同步给主界面（隐藏底栏、禁用 pager 滑动）；离开组合时务必重置
    LaunchedEffect(editTarget) { UiState.serverEditing = editTarget != null }
    DisposableEffect(Unit) {
        onDispose { UiState.serverEditing = false }
    }

    // Push current config to both app and systemui process on screen load
    LaunchedEffect(Unit) {
        try {
            val configJson = Json.encodeToString(AppConfig.serializer(), appConfig)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context).to("com.android.systemui").key(BridgeKeys.PUSH_CONFIG).payload(payload).send()
        } catch (_: Exception) {}
    }

    fun saveConfig(config: AppConfig) {
        Prefs.saveConfig(context, config)
        appConfig = config
        // Push config to system_server so SyncEngine can use it
        scope.launch {
            try {
                val configJson = Json.encodeToString(AppConfig.serializer(), config)
                val payload = android.os.Bundle().apply { putString("config", configJson) }
                SyncClipboardBridge.with(context).to("com.android.systemui").key(BridgeKeys.PUSH_CONFIG).payload(payload).send()
            } catch (_: Exception) {}
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val editPageWidth = with(LocalDensity.current) { maxWidth.toPx() }

        // ─── 背景层：服务器列表（常驻，编辑页滑出时从下方露出）──────────
        ServerListPane(
            appConfig = appConfig,
            bottomPadding = bottomPadding,
            canBack = canBack,
            onBack = { activity?.finish() },
            onAddServer = { editTarget = EditTarget(-1) },
            onEditServer = { index -> editTarget = EditTarget(index) },
        )

        // ─── 前景层：服务器编辑页 ───────────────────────────────────
        if (showEdit) {
            // Animatable 在编辑页首次组合时即初始化为"屏外"，避免入场第一帧闪现在就位状态
            val editOffsetX = remember { Animatable(editPageWidth) }
            // 入场：从屏幕右侧滑入盖住列表
            LaunchedEffect(Unit) {
                editOffsetX.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            }

            /** 关闭编辑页：滑出到屏幕外后再移除（保存/删除/顶部返回键共用） */
            fun closeEdit() {
                scope.launch {
                    editOffsetX.animateTo(editPageWidth, tween(280, easing = FastOutSlowInEasing))
                    editTarget = null
                }
            }

            // 预测性返回：跟随手指平移，提交=滑出返回列表，取消=回弹
            PredictiveBackHandler(enabled = true) { progress ->
                try {
                    progress.collect { event ->
                        scope.launch { editOffsetX.snapTo(editPageWidth * event.progress) }
                    }
                    // 手势完成（或硬件返回键：无进度事件）→ 补完滑出并返回列表
                    scope.launch {
                        editOffsetX.animateTo(editPageWidth, tween(200))
                        editTarget = null
                    }
                } catch (e: CancellationException) {
                    // 手势取消 → 回弹复位
                    scope.launch {
                        editOffsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                    }
                    throw e
                }
            }

            val editingServer = editTarget?.index?.takeIf { it >= 0 }?.let { appConfig.servers.getOrNull(it) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = editOffsetX.value }
            ) {
                ServerEditPage(
                    server = editingServer,
                    serverIndex = editTarget?.index ?: -1,
                    isActive = (editTarget?.index ?: -1) >= 0 &&
                        editTarget?.index == appConfig.activeServerIndex,
                    bottomPadding = bottomPadding,
                    showDeleteConfirm = showDeleteConfirm,
                    onRequestDelete = { showDeleteConfirm = true },
                    onDismissDelete = { showDeleteConfirm = false },
                    onConfirmDelete = {
                        val index = editTarget?.index
                        if (index != null) {
                            val servers = appConfig.servers.toMutableList()
                            servers.removeAt(index)
                            var newConfig = appConfig.copy(servers = servers)
                            if (index == appConfig.activeServerIndex) {
                                newConfig = newConfig.copy(
                                    activeServerIndex = if (servers.isEmpty()) -1 else 0
                                )
                            } else if (index < appConfig.activeServerIndex) {
                                newConfig = newConfig.copy(activeServerIndex = appConfig.activeServerIndex - 1)
                            }
                            saveConfig(newConfig)
                            showDeleteConfirm = false
                            closeEdit()
                        }
                    },
                    onSetActive = {
                        val index = editTarget?.index
                        if (index != null) {
                            saveConfig(appConfig.copy(activeServerIndex = index))
                            closeEdit()
                        }
                    },
                    onBack = { closeEdit() },
                    onSave = { newServer ->
                        val index = editTarget?.index ?: -1
                        val servers = appConfig.servers.toMutableList()
                        if (index >= 0) {
                            servers[index] = newServer
                        } else {
                            servers.add(newServer)
                        }
                        var newConfig = appConfig.copy(servers = servers)
                        if (appConfig.activeServerIndex < 0) {
                            newConfig = newConfig.copy(activeServerIndex = 0)
                        }
                        saveConfig(newConfig)
                        closeEdit()
                        Toast.makeText(
                            context,
                            if (index >= 0) R.string.server_updated else R.string.server_added,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }
}

/**
 * 服务器列表页（背景层）
 */
@Composable
private fun ServerListPane(
    appConfig: AppConfig,
    bottomPadding: Dp,
    canBack: Boolean,
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (Int) -> Unit,
) {
    val context = LocalContext.current

    AppToolBarListContainer(
        title = stringResource(R.string.activity_server_config),
        canBack = canBack,
        onBack = onBack,
        bottomPadding = bottomPadding,
        actions = {
            IconButton(onClick = onAddServer) {
                Icon(
                    modifier = Modifier.size(26.dp),
                    imageVector = MiuixIcons.Add,
                    contentDescription = stringResource(R.string.server_add)
                )
            }
        }
    ) {
        val servers = appConfig.servers
        val activeIndex = appConfig.activeServerIndex

        if (servers.isEmpty()) {
            item("empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_server_configured),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.no_server_hint),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(
                            text = stringResource(R.string.server_add),
                            onClick = onAddServer
                        )
                    }
                }
            }
        } else {
            item("server_list") {
                Card(
                    modifier = Modifier
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                ) {
                    servers.forEachIndexed { index, server ->
                        val isActive = index == activeIndex
                        Column {
                            ArrowPreference(
                                title = server.name ?: server.url,
                                summary = buildServerSummary(server, context),
                                startAction = {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(
                                                color = MiuixTheme.colorScheme.primary.copy(alpha = if (isActive) 0.12f else 0.06f),
                                                shape = RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = serverTypeIcon(server.type),
                                            contentDescription = null,
                                            tint = if (isActive) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                endActions = {
                                    if (isActive) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = MiuixIcons.Ok,
                                                contentDescription = stringResource(R.string.server_active),
                                                tint = MiuixTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.server_active),
                                                fontSize = 12.sp,
                                                color = MiuixTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                onClick = { onEditServer(index) }
                            )
                            if (index < servers.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MiuixTheme.colorScheme.dividerLine
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 服务器类型对应图标
 */
private fun serverTypeIcon(type: ServerType): androidx.compose.ui.graphics.vector.ImageVector =
    when (type) {
        ServerType.syncclipboard -> MiuixIcons.Link
        ServerType.webdav -> MiuixIcons.CloudFill
        ServerType.s3 -> MiuixIcons.Store
    }

/**
 * 构建服务器摘要文本（"当前使用"状态由条目右侧徽章展示，摘要不再重复）
 */
private fun buildServerSummary(server: ServerConfig, context: android.content.Context): String {
    val typeLabel = when (server.type) {
        ServerType.syncclipboard -> context.getString(R.string.server_type_syncclipboard)
        ServerType.webdav -> context.getString(R.string.server_type_webdav)
        ServerType.s3 -> context.getString(R.string.server_type_s3)
    }
    return typeLabel
}

// ═══════════════════════════════════════════════════════════════
// 服务器编辑页 — 整页表单（新建/编辑共用）
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ServerEditPage(
    server: ServerConfig?,
    serverIndex: Int,
    isActive: Boolean,
    bottomPadding: Dp,
    showDeleteConfirm: Boolean,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onSetActive: (() -> Unit)?,
    onBack: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEditing = server != null

    // 表单状态：进入编辑页时重新组合，remember 初值即当前服务器
    var serverType by remember { mutableStateOf(server?.type ?: ServerType.syncclipboard) }
    var name by remember { mutableStateOf(server?.name ?: "") }
    var url by remember { mutableStateOf(server?.url ?: "") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var region by remember { mutableStateOf(server?.region ?: "") }
    var bucketName by remember { mutableStateOf(server?.bucketName ?: "") }
    var objectPrefix by remember { mutableStateOf(server?.objectPrefix ?: "") }
    var forcePathStyle by remember { mutableStateOf(server?.forcePathStyle ?: false) }
    var showPassword by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }

    fun buildServerConfig() = ServerConfig(
        type = serverType,
        name = name.ifBlank { null },
        url = url,
        username = username,
        password = password,
        region = if (serverType == ServerType.s3 && region.isNotBlank()) region else null,
        bucketName = if (serverType == ServerType.s3) bucketName else null,
        objectPrefix = if (serverType == ServerType.s3 && objectPrefix.isNotBlank()) objectPrefix else null,
        forcePathStyle = serverType == ServerType.s3 && forcePathStyle
    )

    /** 必填字段校验，返回错误提示（null 表示通过） */
    fun validateForm(): String? = when (serverType) {
        ServerType.s3 -> when {
            username.isBlank() -> context.getString(R.string.server_access_key_required)
            password.isBlank() -> context.getString(R.string.server_secret_key_required)
            bucketName.isBlank() -> context.getString(R.string.server_bucket_required)
            else -> null
        }
        else -> when {
            url.isBlank() -> context.getString(R.string.server_url_required)
            username.isBlank() -> context.getString(R.string.server_username_required)
            password.isBlank() -> context.getString(R.string.server_password_required)
            else -> null
        }
    }

    AppToolBarListContainer(
        title = stringResource(if (isEditing) R.string.server_edit else R.string.server_add),
        canBack = true,
        onBack = onBack,
        bottomPadding = bottomPadding
    ) {
        // ── 服务器类型 ─────────────────────────────────────
        item("type") {
            SectionTitle(text = stringResource(R.string.server_type_label))
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    ServerType.entries.forEach { type ->
                        ServerTypeOption(
                            type = type,
                            isSelected = serverType == type,
                            onClick = { serverType = type }
                        )
                    }
                }
            }
        }

        // ── 连接信息 ───────────────────────────────────────
        item("connection") {
            SectionTitle(text = stringResource(R.string.server_section_connection))
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.server_name),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.server_url),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = if (serverType == ServerType.s3) stringResource(R.string.server_access_key)
                            else stringResource(R.string.server_username),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = if (serverType == ServerType.s3) stringResource(R.string.server_secret_key)
                            else stringResource(R.string.server_password),
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) MiuixIcons.Hide else MiuixIcons.Show,
                                    contentDescription = stringResource(
                                        if (showPassword) R.string.password_hide else R.string.password_show
                                    ),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        // ── S3 专用字段 ────────────────────────────────────
        if (serverType == ServerType.s3) {
            item("s3") {
                SectionTitle(text = stringResource(R.string.server_section_s3))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = region,
                            onValueChange = { region = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.server_region),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        TextField(
                            value = bucketName,
                            onValueChange = { bucketName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.server_bucket),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        TextField(
                            value = objectPrefix,
                            onValueChange = { objectPrefix = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.server_prefix),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { forcePathStyle = !forcePathStyle }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = forcePathStyle,
                                onCheckedChange = { forcePathStyle = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.server_path_style),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // ── 操作 ──────────────────────────────────────────
        item("actions") {
            SectionTitle(text = stringResource(R.string.server_section_actions))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // 设为当前服务器（编辑且未激活时）
                if (onSetActive != null && !isActive) {
                    TextButton(
                        text = stringResource(R.string.server_set_active),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = onSetActive,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // 测试连接
                TextButton(
                    text = if (isTesting) stringResource(R.string.server_testing)
                    else stringResource(R.string.action_test_connection),
                    onClick = {
                        if (isTesting) return@TextButton
                        // 测试允许 URL 为空（S3），只校验非 S3 的 URL
                        val testError = when (serverType) {
                            ServerType.s3 -> null
                            else -> if (url.isBlank()) context.getString(R.string.server_url_required) else null
                        }
                        if (testError != null) {
                            Toast.makeText(context, testError, Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }

                        isTesting = true
                        scope.launch {
                            try {
                                val success = performTestConnection(buildServerConfig())
                                Toast.makeText(
                                    context,
                                    if (success) context.getString(R.string.server_test_success)
                                    else context.getString(R.string.server_test_fail),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.server_test_fail) + ": ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isTesting
                )
            }
        }

        // ── 底部按钮行：删除（编辑时）/ 保存 ────────────────
        item("bottom_buttons") {
            Row(
                modifier = Modifier
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 删除按钮（仅编辑时）— 错误色填充
                if (isEditing) {
                    Button(
                        onClick = onRequestDelete,
                        modifier = Modifier.weight(1f),
                        minHeight = 40.dp,
                        minWidth = 0.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonColors(
                            color = MiuixTheme.colorScheme.errorContainer,
                            disabledColor = MiuixTheme.colorScheme.errorContainer,
                            contentColor = MiuixTheme.colorScheme.error,
                            disabledContentColor = MiuixTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                }

                // 保存 — 主按钮
                Button(
                    onClick = {
                        validateForm()?.let {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onSave(buildServerConfig())
                    },
                    modifier = Modifier.weight(1f),
                    minHeight = 40.dp,
                    minWidth = 0.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }

        // ── 删除确认对话框（常驻组合以保留关闭动画）─────────
        item("delete_confirm") {
            OverlayDialog(
                show = showDeleteConfirm,
                title = stringResource(R.string.server_delete),
                summary = stringResource(
                    R.string.server_delete_confirm,
                    server?.name ?: server?.url ?: ""
                ),
                onDismissRequest = onDismissDelete
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onDismissDelete,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.action_delete),
                        onClick = onConfirmDelete,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 服务器类型选项行 — 图标 + 标题/说明 + 单选，选中态高亮。
 */
@Composable
private fun ServerTypeOption(
    type: ServerType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val primary = MiuixTheme.colorScheme.primary
    val label = when (type) {
        ServerType.syncclipboard -> stringResource(R.string.server_type_syncclipboard)
        ServerType.webdav -> stringResource(R.string.server_type_webdav)
        ServerType.s3 -> stringResource(R.string.server_type_s3)
    }
    val desc = when (type) {
        ServerType.syncclipboard -> stringResource(R.string.server_type_syncclipboard_desc)
        ServerType.webdav -> stringResource(R.string.server_type_webdav_desc)
        ServerType.s3 -> stringResource(R.string.server_type_s3_desc)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        color = if (isSelected) primary.copy(alpha = 0.15f)
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = serverTypeIcon(type),
                    contentDescription = null,
                    tint = if (isSelected) primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) primary else MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
        }
    }
}

/**
 * 页面内的小节标题
 */
@Composable
private fun SectionTitle(text: String) {
    Box(modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp)) {
        SmallTitle(text = text)
    }
}

/**
 * 直接 HTTP 测试服务器连接 — 绕过 Bridge IPC 避免跨进程广播被系统屏蔽。
 */
private suspend fun performTestConnection(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
    try {
        val urlStr = buildTestUrl(config)
        val url = java.net.URL(urlStr)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true

        if (!config.username.isNullOrBlank() && !config.password.isNullOrBlank()) {
            val credentials = "${config.username}:${config.password}"
            val encoded = android.util.Base64.encodeToString(
                credentials.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }

        // 任意 HTTP 响应（包括 401/404）表示服务器可达
        conn.responseCode > 0
    } catch (e: Exception) {
        false
    }
}

/** 根据服务器类型构建测试 URL */
private fun buildTestUrl(config: ServerConfig): String {
    return when (config.type) {
        ServerType.syncclipboard -> "${config.url.trimEnd('/')}/clipboard"
        ServerType.webdav -> config.url.trimEnd('/')
        ServerType.s3 -> {
            config.url.ifBlank {
                "https://s3.${config.region ?: "us-east-1"}.amazonaws.com"
            }
        }
    }
}
