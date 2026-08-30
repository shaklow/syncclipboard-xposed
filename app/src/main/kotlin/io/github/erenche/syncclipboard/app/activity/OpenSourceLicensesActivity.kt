package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开放源代码许可页 — 列表式卡片（参考 lyricon 风格）。
 *
 * 数据来源：
 * - 顶部手动条目：非 Maven 的参考/借鉴项目（SyncClipboard / lyricon / InstallerX-Revived / SukiSU-Ultra）
 * - 自动条目：`:shell` 模块的 AboutLibraries Gradle 插件在构建时生成的
 *   `raw/aboutlibraries.json`（内容为本项目全部依赖的许可证信息），
 *   通过 [produceLibraries] 解析。点击条目跳转对应项目主页。
 */
class OpenSourceLicensesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenSourceLicensesScreen() }
    }
}

/** 手动参考条目 */
private data class OssReference(
    val nameRes: Int,
    val licenseRes: Int,
    val url: String,
)

/** 渲染用统一条目（参考项与自动依赖同构） */
private data class LicenseEntry(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val license: String,
    val url: String?,
)

/** 非 Maven 依赖的参考 / 借鉴项目（许可依据各仓库 LICENSE 核实） */
private val referenceProjects = listOf(
    OssReference(R.string.oss_syncclipboard, R.string.oss_syncclipboard_license, "https://github.com/Jeric-X/SyncClipboard"),
    OssReference(R.string.oss_lyricon, R.string.oss_lyricon_license, "https://github.com/Kifranei/lyricon"),
    OssReference(R.string.oss_installer, R.string.oss_installer_license, "https://github.com/wxxsfxyzm/InstallerX-Revived"),
    OssReference(R.string.oss_sukisu, R.string.oss_sukisu_license, "https://github.com/SukiSU-Ultra"),
)

@Composable
fun OpenSourceLicensesScreen() {
    val context = LocalContext.current
    val activity = context as? Activity

    // shell 插件生成的依赖许可清单（资源在 apk 内，resource id 跨模块用 getIdentifier 获取）
    val rawId = remember {
        context.resources.getIdentifier("aboutlibraries", "raw", context.packageName)
    }
    val autoLibraries: List<Library> = remember(rawId) {
        if (rawId > 0) {
            try {
                val json = context.resources.openRawResource(rawId)
                    .bufferedReader().use { it.readText() }
                Libs.Builder().withJson(json).build().libraries
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // 参考项目与自动依赖合并为统一列表，按名称字母排序
    val entries = remember(autoLibraries) {
        val refs = referenceProjects.map { p ->
            LicenseEntry(
                id = "ref_${p.url}",
                name = context.getString(p.nameRes),
                version = null,
                description = null,
                license = context.getString(p.licenseRes),
                url = p.url,
            )
        }
        val autos = autoLibraries.map { lib ->
            LicenseEntry(
                id = lib.uniqueId,
                name = lib.name,
                version = lib.artifactVersion,
                description = lib.description?.takeIf { it.isNotBlank() },
                license = lib.licenses
                    .mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
                    .joinToString(", ")
                    .let { if (it.isBlank()) "Open Source" else it },
                url = lib.website?.takeIf { it.isNotBlank() }
                    ?: lib.scm?.url?.takeIf { it.isNotBlank() },
            )
        }
        (refs + autos).sortedBy { it.name.lowercase() }
    }

    AppToolBarListContainer(
        title = stringResource(R.string.about_open_source_licenses),
        canBack = true,
        onBack = { activity?.finish() },
    ) {
        // ── 全部条目（参考项目 + 依赖许可，字母序）────────────
        entries.forEach { entry ->
            item(entry.id) {
                LicenseCard(
                    title = entry.name,
                    version = entry.version,
                    description = entry.description,
                    license = entry.license,
                    url = entry.url,
                    context = context,
                )
            }
        }
    }
}

/** 许可条目卡片（lyricon 风格：名称 + 版本 + 描述 + 分割线 + 许可，点击跳主页） */
@Composable
private fun LicenseCard(
    title: String,
    version: String?,
    description: String?,
    license: String,
    url: String?,
    context: Context,
) {
    val modifier = Modifier
        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        .fillMaxWidth()
    val finalModifier = if (url != null) modifier.clickable { openUrl(context, url) } else modifier

    Card(modifier = finalModifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (!version.isNullOrBlank()) {
                    Text(
                        text = version,
                        modifier = Modifier.padding(start = 16.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = 2,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MiuixTheme.colorScheme.dividerLine,
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = license,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}
