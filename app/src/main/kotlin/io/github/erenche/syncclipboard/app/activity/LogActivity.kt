package io.github.erenche.syncclipboard.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    var logs by remember { mutableStateOf("加载中...") }
    var loading by remember { mutableStateOf(true) }

    fun loadLogs() {
        loading = true
        logs = "加载中..."
        scope.launch {
            try {
                val bundle = SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.GET_LOGS)
                    .await(timeout = 10000)
                val result = bundle.getString("logs")
                logs = if (result.isNullOrBlank()) {
                    "(空，bundle=${bundle.size()}，可能 bridge 超时或 handler 未注册)"
                } else {
                    result
                }
            } catch (e: Exception) {
                logs = "加载失败: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadLogs() }

    AppToolBarListContainer(
        title = stringResource(R.string.item_log),
        canBack = true,
        onBack = { (context as? android.app.Activity)?.finish() }
    ) {
        item("buttons") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "刷新",
                    onClick = { loadLogs() }
                )
                Row {
                    TextButton(
                        text = "分享",
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, logs)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "分享日志"))
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        text = "清空",
                        onClick = {
                            scope.launch {
                                SyncClipboardBridge.with(context)
                                    .to("com.android.systemui")
                                    .key(BridgeKeys.CLEAR_LOGS)
                                    .send()
                                loadLogs()
                            }
                        }
                    )
                }
            }
        }

        item("log_content") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = logs,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
