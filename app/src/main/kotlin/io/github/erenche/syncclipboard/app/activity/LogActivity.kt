package io.github.erenche.syncclipboard.app.activity

import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LogScreen() }
    }
}

@Composable
fun LogScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawLogs by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    fun loadLogs() {
        loading = true
        scope.launch {
            try {
                val bundle = SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.GET_LOGS)
                    .await(timeout = 10000)
                rawLogs = bundle.getString("logs") ?: ""
            } catch (_: Exception) {
                rawLogs = ""
            } finally {
                loading = false
            }
        }
    }

    fun clearLogs() {
        scope.launch {
            try {
                // 用 await 替代 send，确保清空完成后再刷新（消除竞态）
                SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.CLEAR_LOGS)
                    .await(timeout = 5000)
            } catch (_: Exception) {}
            rawLogs = ""
            loadLogs()
        }
    }

    fun exportLogs() {
        val content = rawLogs
        if (content.isBlank()) return
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SyncClipboard_log_$ts.txt"
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray())
                }
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.log_export_success, fileName),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.log_export_failed, "insert returned null"),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.log_export_failed, e.message ?: "unknown"),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) { loadLogs() }

    // 按关键字过滤的日志行列表
    val filteredLines = remember(rawLogs, searchQuery) {
        if (rawLogs.isBlank()) return@remember emptyList()
        val q = searchQuery.trim()
        if (q.isEmpty()) rawLogs.split("\n")
        else rawLogs.split("\n").filter { it.contains(q, ignoreCase = true) }
    }

    // 自动滚动到底部
    val verticalScrollState = rememberScrollState()
    LaunchedEffect(filteredLines.size) {
        if (filteredLines.isNotEmpty()) {
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
    }

    AppToolBarListContainer(
        title = stringResource(R.string.item_log),
        canBack = true,
        onBack = { (context as? android.app.Activity)?.finish() }
    ) {
        item("search") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = stringResource(R.string.log_search_hint),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    singleLine = true
                )
            }
        }

        item("buttons") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = stringResource(R.string.action_refresh),
                    onClick = { loadLogs() },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = stringResource(R.string.log_export),
                    onClick = { exportLogs() },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = stringResource(R.string.setting_cache_clear),
                    onClick = { clearLogs() },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item("log_content") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(max = 600.dp)
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    val textColor = MiuixTheme.colorScheme.onSurface
                    when {
                        loading -> {
                            Text(
                                text = stringResource(R.string.main_loading),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = textColor
                            )
                        }
                        filteredLines.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.log_empty),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = textColor
                            )
                        }
                        else -> {
                            Column {
                                filteredLines.forEach { line ->
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
