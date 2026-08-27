package io.github.erenche.syncclipboard.app.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.erenche.syncclipboard.app.activity.BaseActivity
import io.github.erenche.syncclipboard.app.compose.theme.AppTheme
import io.github.erenche.syncclipboard.app.component.BlurredBar
import io.github.erenche.syncclipboard.app.component.rememberBlurBackdrop
import io.github.erenche.syncclipboard.app.util.UiState
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun NavigationBackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Icon(
            modifier = Modifier.size(26.dp).graphicsLayer { scaleX = if (isRtl) -1f else 1f },
            imageVector = MiuixIcons.Back,
            contentDescription = "Back"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppToolBarListContainer(
    title: String = "",
    canBack: Boolean = false,
    onBack: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    content: LazyListScope.() -> Unit
) {
    AppTheme {
        // 顶栏毛玻璃背景（设置中"模糊"开关控制，设备不支持时自动降级为纯色）
        val backdrop = rememberBlurBackdrop(UiState.blur)
        val blurActive = backdrop != null
        Scaffold(
            topBar = {
                BlurredBar(backdrop) {
                    SmallTopAppBar(
                        title = title,
                        navigationIcon = {
                            if (canBack) NavigationBackIcon(onBack)
                        },
                        actions = actions,
                        color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
                    )
                }
            }
        ) { paddingValues ->
            val topPadding = paddingValues.calculateTopPadding()
            Box(modifier = if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier) {
                if (onRefresh != null) {
                    val pullToRefreshState = rememberPullToRefreshState()
                    PullToRefresh(
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        pullToRefreshState = pullToRefreshState,
                        contentPadding = PaddingValues(top = topPadding),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .overScrollVertical(),
                            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                            overscrollEffect = null,
                            content = content
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .overScrollVertical(),
                        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                        overscrollEffect = null,
                        content = content
                    )
                }
            }
        }
    }
}
