package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
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

class ServerConfigActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ServerConfigScreen() }
    }
}

/**
 * 服务器管理界面 — MIUI X 风格
 */
@Composable
fun ServerConfigScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var appConfig by remember { mutableStateOf(Prefs.loadConfig(context)) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Push current config to both app and systemui process on screen load
    LaunchedEffect(Unit) {
        try {
            val configJson = Json.encodeToString(AppConfig.serializer(), appConfig)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context).to("com.android.systemui").key(BridgeKeys.PUSH_CONFIG).payload(payload).send()
        } catch (_: Exception) {}
    }

    fun refreshConfig() {
        appConfig = Prefs.loadConfig(context)
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

    AppToolBarListContainer(
        title = stringResource(R.string.activity_server_config),
        canBack = true,
        onBack = { activity?.finish() },
        actions = {
            IconButton(onClick = {
                editingServer = null
                editingIndex = -1
                showEditDialog = true
            }) {
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
                            onClick = {
                                editingServer = null
                                editingIndex = -1
                                showEditDialog = true
                            }
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
                                onClick = {
                                    editingServer = server
                                    editingIndex = index
                                    showEditDialog = true
                                }
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

        // ── 对话框必须放在 Scaffold 内部才能渲染 OverlayDialog ──
        item("dialogs") {
            ServerEditDialog(
                show = showEditDialog,
                server = editingServer,
                serverIndex = editingIndex,
                existingServers = appConfig.servers,
                activeServerIndex = appConfig.activeServerIndex,
                onSave = { newServer ->
                    val servers = appConfig.servers.toMutableList()
                    if (editingIndex >= 0) {
                        servers[editingIndex] = newServer
                    } else {
                        servers.add(newServer)
                    }
                    var newConfig = appConfig.copy(servers = servers)
                    if (appConfig.activeServerIndex < 0) {
                        newConfig = newConfig.copy(activeServerIndex = 0)
                    }
                    saveConfig(newConfig)
                    showEditDialog = false
                    Toast.makeText(
                        context,
                        if (editingIndex >= 0) context.getString(R.string.server_test_success)
                            .replace("successful", "updated")
                        else context.getString(R.string.server_test_success)
                            .replace("successful", "added"),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onDelete = if (editingIndex >= 0) {{
                    showDeleteConfirm = true
                }} else null,
                onSetActive = if (editingIndex >= 0 && editingIndex != appConfig.activeServerIndex) {{
                    saveConfig(appConfig.copy(activeServerIndex = editingIndex))
                    showEditDialog = false
                }} else null,
                onDismiss = { showEditDialog = false }
            )

            // ── 删除确认对话框（常驻组合以保留关闭动画）─────────────────────
            OverlayDialog(
                show = showDeleteConfirm && editingIndex >= 0,
                title = stringResource(R.string.server_delete),
                summary = stringResource(
                    R.string.server_delete_confirm,
                    editingServer?.name ?: editingServer?.url ?: ""
                ),
                onDismissRequest = { showDeleteConfirm = false }
            ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { showDeleteConfirm = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.action_delete),
                            onClick = {
                                val servers = appConfig.servers.toMutableList()
                                servers.removeAt(editingIndex)
                                var newConfig = appConfig.copy(servers = servers)
                                if (editingIndex == appConfig.activeServerIndex) {
                                    newConfig = newConfig.copy(
                                        activeServerIndex = if (servers.isEmpty()) -1 else 0
                                    )
                                } else if (editingIndex < appConfig.activeServerIndex) {
                                    newConfig = newConfig.copy(activeServerIndex = appConfig.activeServerIndex - 1)
                                }
                                saveConfig(newConfig)
                                showEditDialog = false
                                showDeleteConfirm = false
                            },
                            modifier = Modifier.weight(1f)
                        )
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
// 服务器编辑/添加对话框 — MIUI X 风格（美化版）
// ═══════════════════════════════════════════════════════════════

@Composable
fun ServerEditDialog(
    show: Boolean,
    server: ServerConfig?,
    serverIndex: Int,
    existingServers: List<ServerConfig>,
    activeServerIndex: Int,
    onSave: (ServerConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onSetActive: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEditing = server != null

    // Form state — String-based for miuix TextField
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

    // 对话框常驻组合以保留关闭动画：每次打开时按当前 server 重置表单
    LaunchedEffect(show, server) {
        if (show) {
            serverType = server?.type ?: ServerType.syncclipboard
            name = server?.name ?: ""
            url = server?.url ?: ""
            username = server?.username ?: ""
            password = server?.password ?: ""
            region = server?.region ?: ""
            bucketName = server?.bucketName ?: ""
            objectPrefix = server?.objectPrefix ?: ""
            forcePathStyle = server?.forcePathStyle ?: false
            showPassword = false
            isTesting = false
        }
    }

    OverlayDialog(
        show = show,
        title = stringResource(if (isEditing) R.string.server_edit else R.string.server_add),
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            // ── 服务器类型选择 ─────────────────────────────
            DialogSectionTitle(text = stringResource(R.string.server_type_label))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    ServerType.entries.forEach { type ->
                        val isSelected = serverType == type
                        ServerTypeOption(
                            type = type,
                            isSelected = isSelected,
                            onClick = { serverType = type }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── 连接信息字段 ───────────────────────────────
            DialogSectionTitle(text = stringResource(R.string.server_section_connection))

            Card(modifier = Modifier.fillMaxWidth()) {
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
                            IconButton(
                                onClick = { showPassword = !showPassword }
                            ) {
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

            // ── S3 专用字段 ─────────────────────────────────
            if (serverType == ServerType.s3) {
                Spacer(modifier = Modifier.height(18.dp))
                DialogSectionTitle(text = stringResource(R.string.server_section_s3))

                Card(modifier = Modifier.fillMaxWidth()) {
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

            Spacer(modifier = Modifier.height(18.dp))

            // ── 操作区 ─────────────────────────────────────
            DialogSectionTitle(text = stringResource(R.string.server_section_actions))

            // 设为当前服务器 (仅编辑时)
            if (onSetActive != null) {
                TextButton(
                    text = stringResource(R.string.server_set_active),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = onSetActive,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 测试连接 — 通过 bridge 实际测试
            TextButton(
                text = if (isTesting) stringResource(R.string.server_testing)
                else stringResource(R.string.action_test_connection),
                onClick = {
                    if (isTesting) return@TextButton
                    // 先验证必填字段
                    val validateError = when (serverType) {
                        ServerType.s3 -> {
                            when {
                                url.isBlank() -> null  // S3 URL is optional
                                username.isBlank() -> context.getString(R.string.server_access_key_required)
                                password.isBlank() -> context.getString(R.string.server_secret_key_required)
                                bucketName.isBlank() -> context.getString(R.string.server_bucket_required)
                                else -> null
                            }
                        }
                        else -> {
                            when {
                                url.isBlank() -> context.getString(R.string.server_url_required)
                                username.isBlank() -> context.getString(R.string.server_username_required)
                                password.isBlank() -> context.getString(R.string.server_password_required)
                                else -> null
                            }
                        }
                    }
                    if (validateError != null) {
                        Toast.makeText(context, validateError, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }

                    isTesting = true
                    scope.launch {
                        try {
                            val testConfig = ServerConfig(
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
                            val success = performTestConnection(testConfig)
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

            Spacer(modifier = Modifier.height(10.dp))

            // 底部操作行：三个按钮统一为 MIUIX 填充式按钮（删除/取消/保存），按语义配色
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 删除按钮 (仅编辑时) — 错误色填充
                if (onDelete != null) {
                    Button(
                        onClick = onDelete,
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

                // 取消 — 次级实心按钮
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    minHeight = 40.dp,
                    minWidth = 0.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonColors(
                        color = MiuixTheme.colorScheme.secondary,
                        disabledColor = MiuixTheme.colorScheme.disabledSecondary,
                        contentColor = MiuixTheme.colorScheme.onSecondary,
                        disabledContentColor = MiuixTheme.colorScheme.disabledOnSecondary
                    )
                ) {
                    Text(stringResource(R.string.action_cancel))
                }

                // 保存 — 主按钮（主色填充）
                Button(
                    onClick = {
                        // 验证
                        when (serverType) {
                            ServerType.s3 -> {
                                if (bucketName.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_bucket_required), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (username.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_access_key_required), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (password.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_secret_key_required), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                            }
                            else -> {
                                if (url.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_url_required), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (username.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_username_required), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (password.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_password_required), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                            }
                        }

                        onSave(
                            ServerConfig(
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
                        )
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
 * 对话框内的小节标题 — 使用 MIUIX SmallTitle，适配对话框内边距。
 */
@Composable
private fun DialogSectionTitle(text: String) {
    Box(modifier = Modifier.padding(bottom = 8.dp)) {
        SmallTitle(
            text = text,
            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        )
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
