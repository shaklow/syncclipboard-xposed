package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.transfer.HistoryTransferQueue
import io.github.erenche.syncclipboard.app.transfer.TransferState
import io.github.erenche.syncclipboard.app.transfer.TransferTask
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

class TransferActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TransferScreen() }
    }
}

/**
 * 传输队列页面 — 展示历史文件批量后台下载任务及实时进度。
 * 数据源：HistoryTransferQueue（app 进程内单例，离开页面后继续下载）。
 */
@Composable
fun TransferScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val tasks by HistoryTransferQueue.tasks.collectAsState()

    val hasActive = tasks.any { it.state == TransferState.PENDING || it.state == TransferState.DOWNLOADING }
    val hasFinished = tasks.any {
        it.state == TransferState.COMPLETED || it.state == TransferState.FAILED ||
            it.state == TransferState.CANCELLED
    }
    // 汇总：进行中数量 / 总数量
    val activeCount = tasks.count { it.state == TransferState.PENDING || it.state == TransferState.DOWNLOADING }

    AppToolBarListContainer(
        title = stringResource(R.string.activity_transfer),
        canBack = true,
        onBack = { activity?.finish() },
        actions = {
            if (hasActive) {
                TextButton(
                    text = stringResource(R.string.transfer_cancel_all),
                    onClick = { HistoryTransferQueue.cancelAll() },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    minHeight = 36.dp,
                    minWidth = 0.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (hasFinished) {
                TextButton(
                    text = stringResource(R.string.transfer_clear_finished),
                    onClick = { HistoryTransferQueue.clearFinished() },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    minHeight = 36.dp,
                    minWidth = 0.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    ) {
        if (tasks.isEmpty()) {
            item("empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .fillMaxWidth()
                ) {
                    BasicComponent(title = stringResource(R.string.transfer_empty))
                }
            }
        } else {
            item("summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.transfer_summary, activeCount, tasks.size),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                TransferTaskCard(task = task)
            }
        }
        item("footer") { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/** 单个传输任务卡片：文件名 + 类型 + 实时进度条 + 状态 + 操作 */
@Composable
private fun TransferTaskCard(task: TransferTask) {
    val fileName = task.item.dataName ?: task.item.text.take(30)
    val isImage = task.item.type == ClipboardContentType.Image

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 第一行：类型标签 + 文件名 + 状态文本
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (isImage) R.string.type_image else R.string.type_file
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = fileName,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = statusText(task),
                    style = MiuixTheme.textStyles.body2,
                    color = statusColor(task),
                )
            }

            // 第二行：进度条 + 百分比
            when (task.state) {
                TransferState.PENDING -> {
                    Text(
                        text = stringResource(R.string.transfer_waiting),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }

                TransferState.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = if (task.progress > 0f) task.progress else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.transfer_progress,
                            (task.progress * 100).roundToInt()
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }

                TransferState.COMPLETED -> {
                    Text(
                        text = stringResource(R.string.transfer_completed_detail),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }

                TransferState.FAILED -> {
                    Text(
                        text = task.error ?: stringResource(R.string.transfer_failed),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                TransferState.CANCELLED -> {
                    Text(
                        text = stringResource(R.string.transfer_cancelled),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }

            // 第三行：操作按钮
            when (task.state) {
                TransferState.PENDING, TransferState.DOWNLOADING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(R.string.transfer_cancel),
                            onClick = { HistoryTransferQueue.cancel(task.id) },
                            colors = ButtonDefaults.textButtonColors(),
                            minHeight = 32.dp,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                TransferState.FAILED, TransferState.CANCELLED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(R.string.transfer_retry),
                            onClick = { HistoryTransferQueue.retry(task.id) },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 32.dp,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            text = stringResource(R.string.transfer_remove),
                            onClick = { HistoryTransferQueue.remove(task.id) },
                            colors = ButtonDefaults.textButtonColors(),
                            minHeight = 32.dp,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                TransferState.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(R.string.transfer_remove),
                            onClick = { HistoryTransferQueue.remove(task.id) },
                            colors = ButtonDefaults.textButtonColors(),
                            minHeight = 32.dp,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(task: TransferTask): String {
    return when (task.state) {
        TransferState.PENDING -> stringResource(R.string.transfer_waiting)
        TransferState.DOWNLOADING -> stringResource(
            R.string.transfer_progress, (task.progress * 100).roundToInt()
        )
        TransferState.COMPLETED -> stringResource(R.string.transfer_completed)
        TransferState.FAILED -> stringResource(R.string.transfer_failed)
        TransferState.CANCELLED -> stringResource(R.string.transfer_cancelled)
    }
}

@Composable
private fun statusColor(task: TransferTask): androidx.compose.ui.graphics.Color {
    return when (task.state) {
        TransferState.FAILED -> MiuixTheme.colorScheme.error
        TransferState.COMPLETED -> MiuixTheme.colorScheme.primary
        TransferState.DOWNLOADING -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onBackgroundVariant
    }
}