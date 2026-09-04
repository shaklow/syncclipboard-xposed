package io.github.erenche.syncclipboard.app.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
    /** 自定义左上角图标区（提供时替代默认返回键；内部可组合返回键 + 附加图标） */
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    listState: LazyListState? = null,
    /** 固定在顶栏下方的常驻内容（如搜索框），随顶栏毛玻璃背景固定，不随列表滚动 */
    stickyContent: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    AppTheme {
        // 顶栏毛玻璃背景（设置中"模糊"开关控制，设备不支持时自动降级为纯色）
        val backdrop = rememberBlurBackdrop(UiState.blur)
        val blurActive = backdrop != null
        val defaultListState = rememberLazyListState()
        val state = listState ?: defaultListState
        Scaffold(
            topBar = {
                BlurredBar(backdrop) {
                    Column {
                        SmallTopAppBar(
                            title = title,
                            navigationIcon = {
                                when {
                                    navigationIcon != null -> navigationIcon()
                                    canBack -> NavigationBackIcon(onBack)
                                }
                            },
                            actions = actions,
                            color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
                        )
                        // 常驻固定区：背景色与顶栏一致（毛玻璃关闭时）
                        if (stickyContent != null) {
                            Box(
                                modifier = if (blurActive) Modifier
                                else Modifier.background(MiuixTheme.colorScheme.surface)
                            ) {
                                stickyContent()
                            }
                        }
                    }
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
                            state = state,
                            modifier = Modifier
                                .fillMaxSize()
                                .scrollEndHaptic()
                                .overScrollVertical(),
                            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                            overscrollEffect = null,
                            content = content
                        )
                    }
                } else {
                    LazyColumn(
                        state = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
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
