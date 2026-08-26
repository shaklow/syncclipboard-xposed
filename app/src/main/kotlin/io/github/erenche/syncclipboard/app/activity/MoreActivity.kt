package io.github.erenche.syncclipboard.app.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.PackageNames
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

class MoreActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MoreScreen() }
    }
}

@Composable
fun MoreScreen() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var showCleanupDialog by remember { mutableStateOf(false) }

    AppToolBarListContainer(
        title = stringResource(R.string.item_more),
        canBack = true,
        onBack = { activity?.finish() }
    ) {
        item("server_settings") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_server_settings),
                    summary = stringResource(R.string.item_server_settings_summary),
                    onClick = {
                        context.startActivity(
                            Intent(context, ServerConfigActivity::class.java)
                        )
                    }
                )
            }
        }

        item("sync_settings") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_sync_settings),
                    summary = stringResource(R.string.item_sync_settings_summary),
                    onClick = {
                        context.startActivity(
                            Intent(context, SettingsActivity::class.java)
                        )
                    }
                )
            }
        }

        item("cleanup") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_clean_engine_data),
                    summary = stringResource(R.string.item_clean_engine_data_summary),
                    onClick = { showCleanupDialog = true }
                )
            }
        }

        item("log") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
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

        item("about") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.item_about_app),
                    summary = stringResource(R.string.item_about_app_summary),
                    onClick = {
                        context.startActivity(Intent(context, AboutActivity::class.java))
                    }
                )
            }
        }
    }

    if (showCleanupDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.item_clean_engine_data),
            summary = stringResource(R.string.clean_engine_data_confirm),
            onDismissRequest = { showCleanupDialog = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showCleanupDialog = false },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        showCleanupDialog = false
                        SyncClipboardBridge.with(context)
                            .to(PackageNames.SYSTEM_UI)
                            .key(BridgeKeys.CLEAR_ENGINE_DATA)
                            .send()
                        Toast.makeText(
                            context,
                            R.string.clean_engine_data_done,
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
