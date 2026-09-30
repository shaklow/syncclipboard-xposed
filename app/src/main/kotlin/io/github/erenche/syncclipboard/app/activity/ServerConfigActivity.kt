package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
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
import io.github.erenche.syncclipboard.xposed.api.ClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

/**
 * 独立承载服务器编辑页，使系统能够接管 Activity 级预测性返回动画。
 * 编辑页不再在 MainActivity 内部模拟固定方向的平移动画。
 */
class ServerConfigActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ServerEditHost(
                serverIndex = intent.getIntExtra(EXTRA_SERVER_INDEX, -1),
                onFinished = { changed ->
                    if (changed) setResult(Activity.RESULT_OK)
                    finish()
                }
            )
        }
    }

    companion object {
        const val EXTRA_SERVER_INDEX = "server_index"
    }
}

/**
 * 编辑页状态与配置保存逻辑。配置保存后通过结果通知列表页刷新，
 * 同时继续把配置推送给 SystemUI 中的同步引擎。
 */
@Composable
private fun ServerEditHost(
    serverIndex: Int,
    onFinished: (changed: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appConfig by remember { mutableStateOf(Prefs.loadConfig(context)) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val effectiveIndex = serverIndex.takeIf { it in appConfig.servers.indices } ?: -1
    val editingServer = effectiveIndex.takeIf { it >= 0 }?.let { appConfig.servers[it] }

    fun saveAndFinish(config: AppConfig) {
        Prefs.saveConfig(context, config)
        appConfig = config
        scope.launch {
            var pushed = false
            try {
                val configJson = Json.encodeToString(AppConfig.serializer(), config)
                val payload = Bundle().apply { putString("config", configJson) }
                SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.PUSH_CONFIG)
                    .payload(payload)
                    .send()
                pushed = true
            } catch (_: Exception) {
                // The app and SystemUI have separate storage. A later server-page
                // visit retries the push; do not pretend this save reached the engine.
            } finally {
                if (!pushed) {
                    Toast.makeText(context, "配置已保存；同步引擎更新失败，请稍后重试", Toast.LENGTH_LONG).show()
                }
                onFinished(true)
            }
        }
    }

    ServerEditPage(
        server = editingServer,
        serverIndex = effectiveIndex,
        isActive = effectiveIndex >= 0 && effectiveIndex == appConfig.activeServerIndex,
        bottomPadding = 0.dp,
        showDeleteConfirm = showDeleteConfirm,
        onRequestDelete = { showDeleteConfirm = true },
        onDismissDelete = { showDeleteConfirm = false },
        onConfirmDelete = {
            if (effectiveIndex >= 0) {
                val servers = appConfig.servers.toMutableList()
                servers.removeAt(effectiveIndex)
                var newConfig = appConfig.copy(servers = servers)
                if (effectiveIndex == appConfig.activeServerIndex) {
                    newConfig = newConfig.copy(activeServerIndex = if (servers.isEmpty()) -1 else 0)
                } else if (effectiveIndex < appConfig.activeServerIndex) {
                    newConfig = newConfig.copy(activeServerIndex = appConfig.activeServerIndex - 1)
                }
                showDeleteConfirm = false
                saveAndFinish(newConfig)
            }
        },
        onSetActive = if (effectiveIndex >= 0 && effectiveIndex != appConfig.activeServerIndex) {
            { editedServer ->
                val servers = appConfig.servers.toMutableList()
                servers[effectiveIndex] = editedServer
                saveAndFinish(
                    appConfig.copy(
                        servers = servers,
                        activeServerIndex = effectiveIndex
                    )
                )
            }
        } else null,
        onBack = { onFinished(false) },
        onSave = { newServer ->
            val servers = appConfig.servers.toMutableList()
            if (effectiveIndex >= 0) {
                servers[effectiveIndex] = newServer
            } else {
                servers.add(newServer)
            }
            var newConfig = appConfig.copy(servers = servers)
            if (appConfig.activeServerIndex < 0) {
                newConfig = newConfig.copy(activeServerIndex = 0)
            }
            Toast.makeText(
                context,
                if (effectiveIndex >= 0) R.string.server_updated else R.string.server_added,
                Toast.LENGTH_SHORT
            ).show()
            saveAndFinish(newConfig)
        }
    )
}

/**
 * 服务器管理界面 — 服务器列表页。
 *
 * 服务器编辑页由 [ServerConfigActivity] 独立承载，返回时使用系统默认的
 * Activity 预测性返回动画，从而和其他独立页面保持一致。
 */
@Composable
fun ServerConfigScreen(
    bottomPadding: Dp = 0.dp,
    canBack: Boolean = true,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var appConfig by remember { mutableStateOf(Prefs.loadConfig(context)) }

    val editLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 编辑页已将配置写入共享首选项，返回列表后刷新显示。
            appConfig = Prefs.loadConfig(context)
        }
    }

    fun openEditor(index: Int) {
        editLauncher.launch(
            Intent(context, ServerConfigActivity::class.java).apply {
                putExtra(ServerConfigActivity.EXTRA_SERVER_INDEX, index)
            }
        )
    }

    // SystemUI owns a separate preference file.  Never overwrite it with the
    // empty configuration produced by a fresh install or cleared app storage.
    LaunchedEffect(Unit) {
        var configToPush: AppConfig? = appConfig.takeIf { Prefs.isConfigInitialized(context) }
        try {
            val result = SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.GET_CONFIG)
                .await()
            val engineJson = result.getString("config")
            val engineConfig = engineJson?.let {
                runCatching { Json.decodeFromString(AppConfig.serializer(), it) }.getOrNull()
            }
            if (!Prefs.isConfigInitialized(context) && appConfig.servers.isEmpty() &&
                engineConfig != null && engineConfig.servers.isNotEmpty()
            ) {
                Prefs.saveConfig(context, engineConfig)
                appConfig = engineConfig
                configToPush = engineConfig
            }
        } catch (_: Exception) {
            // Engine may not be running yet.  Do not push an unowned empty config.
        }

        configToPush?.let { config ->
            runCatching {
                val configJson = Json.encodeToString(AppConfig.serializer(), config)
                val payload = android.os.Bundle().apply { putString("config", configJson) }
                SyncClipboardBridge.with(context).to("com.android.systemui")
                    .key(BridgeKeys.PUSH_CONFIG).payload(payload).send()
            }
        }
    }

    ServerListPane(
        appConfig = appConfig,
        bottomPadding = bottomPadding,
        canBack = canBack,
        onBack = { activity?.finish() },
        onAddServer = { openEditor(-1) },
        onEditServer = { index -> openEditor(index) },
    )
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
    onSetActive: ((ServerConfig) -> Unit)?,
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

    fun isValidServerUrl(value: String): Boolean = runCatching {
        val parsed = java.net.URI(value.trim())
        val scheme = parsed.scheme?.lowercase()
        (scheme == "https" || scheme == "http") && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)

    /** 必填字段校验，返回错误提示（null 表示通过） */
    fun validateForm(): String? = when (serverType) {
        ServerType.s3 -> when {
            username.isBlank() -> context.getString(R.string.server_access_key_required)
            password.isBlank() -> context.getString(R.string.server_secret_key_required)
            bucketName.isBlank() -> context.getString(R.string.server_bucket_required)
            url.isNotBlank() && !isValidServerUrl(url) -> context.getString(R.string.server_url_invalid)
            else -> null
        }
        else -> when {
            url.isBlank() -> context.getString(R.string.server_url_required)
            !isValidServerUrl(url) -> context.getString(R.string.server_url_invalid)
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
                        onClick = {
                            validateForm()?.let {
                                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            onSetActive(buildServerConfig())
                        },
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
 * Direct backend-specific test, without depending on SystemUI IPC.
 * ClientFactory uses the real SyncClipboard, WebDAV, or signed S3 protocol;
 * a generic GET cannot verify credentials for all three backends.
 */
private suspend fun performTestConnection(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        withTimeout(10000L) {
            ClientFactory.createClient(config).testConnection()
        }
    }.isSuccess
}
