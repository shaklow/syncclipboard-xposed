package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AboutScreen() }
    }
}

/**
 * 关于页面 — MIUI X 风格
 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val activity = context as? Activity

    val versionName: String = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    AppToolBarListContainer(
        title = stringResource(R.string.activity_about),
        canBack = true,
        onBack = { activity?.finish() }
    ) {
        // 版本信息
        item("version") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SyncClipboard",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_version, versionName),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.about_description),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_supported_backends),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // 工作原理
        item("how_it_works") {
            Card(
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.about_how_it_works_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_how_it_works_text),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 许可协议
        item("license") {
            Card(
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.about_license_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_license_text),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 源代码链接
        item("source_code") {
            Card(
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .fillMaxWidth()
            ) {
                ArrowPreference(
                    title = stringResource(R.string.about_source_code),
                    summary = "github.com/erenche/syncclipboard-mobile",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://github.com/erenche/syncclipboard-mobile")
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}
