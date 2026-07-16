package io.github.erenche.syncclipboard.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import top.yukonga.miuix.kmp.basic.Card
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
}
