// app/src/main/java/com/upuaut/xposedsearch/ui/HomeScreen.kt
package com.upuaut.xposedsearch.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomeScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToEngines: () -> Unit,
    onNavigateToHotSites: () -> Unit,
    onNavigateToDarkWord: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "HeyTap 浏览器管理",
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null
        ) {
            item {
                StatusCard(
                    hasRootAccess = uiState.hasRootAccess,
                    isCheckingRoot = uiState.isCheckingRoot,
                    isXposedActive = uiState.isXposedActive
                )
            }

            item {
                SettingsCard(
                    isIconHidden = uiState.isIconHidden,
                    hasRootAccess = uiState.hasRootAccess,
                    isForceStoppingBrowser = uiState.isForceStoppingBrowser,
                    onIconHiddenChange = { hidden ->
                        if (hidden) {
                            viewModel.setIconHidden(true)
                            Toast.makeText(context, "桌面图标已隐藏\n可通过 LSPosed 打开本应用", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.setIconHidden(false)
                            Toast.makeText(context, "桌面图标已恢复显示", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onForceStop = {
                        val success = viewModel.forceStopBrowser()
                        if (success) {
                            Toast.makeText(context, "浏览器已强制停止", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "需要 Root 权限", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenBrowser = {
                        viewModel.openBrowser()
                    }
                )
            }

            item {
                SmallTitle(text = "功能")
            }

            item {
                FunctionCard(
                    onNavigateToEngines = onNavigateToEngines,
                    onNavigateToHotSites = onNavigateToHotSites,
                    onNavigateToDarkWord = onNavigateToDarkWord
                )
            }
        }
    }
}

private val CardPadding = 20.dp

@Composable
fun StatusCard(
    hasRootAccess: Boolean,
    isCheckingRoot: Boolean,
    isXposedActive: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "状态",
                style = MiuixTheme.textStyles.headline1
            )

            StatusItem(
                label = "Root 状态",
                isActive = hasRootAccess,
                isLoading = isCheckingRoot,
                activeText = "已获取",
                inactiveText = "未获取",
                loadingText = "检测中..."
            )

            StatusItem(
                label = "Xposed 状态",
                isActive = isXposedActive,
                isLoading = false,
                activeText = "已激活",
                inactiveText = "未激活",
                loadingText = ""
            )
        }
    }
}

@Composable
private fun StatusItem(
    label: String,
    isActive: Boolean,
    isLoading: Boolean,
    activeText: String,
    inactiveText: String,
    loadingText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isLoading) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                )
            }
            Text(
                text = when {
                    isLoading -> loadingText
                    isActive -> activeText
                    else -> inactiveText
                },
                style = MiuixTheme.textStyles.body2,
                color = when {
                    isLoading -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    isActive -> Color(0xFF4CAF50)
                    else -> Color(0xFFF44336)
                }
            )
        }
    }
}

@Composable
fun SettingsCard(
    isIconHidden: Boolean,
    hasRootAccess: Boolean,
    isForceStoppingBrowser: Boolean,
    onIconHiddenChange: (Boolean) -> Unit,
    onForceStop: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "设置",
                style = MiuixTheme.textStyles.headline1
            )

            SuperSwitch(
                title = "隐藏桌面图标",
                summary = "隐藏后可通过 LSPosed 打开",
                checked = isIconHidden,
                onCheckedChange = onIconHiddenChange,
                insideMargin = PaddingValues(0.dp)
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = if (isForceStoppingBrowser) "停止中..." else "强行停止",
                    onClick = onForceStop,
                    enabled = hasRootAccess && !isForceStoppingBrowser,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    text = "打开浏览器",
                    onClick = onOpenBrowser,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun FunctionCard(
    onNavigateToEngines: () -> Unit,
    onNavigateToHotSites: () -> Unit,
    onNavigateToDarkWord: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            SuperArrow(
                title = "搜索引擎",
                summary = "管理浏览器搜索引擎",
                onClick = onNavigateToEngines
            )

            SuperArrow(
                title = "热门网站",
                summary = "管理首页热门网站",
                onClick = onNavigateToHotSites
            )

            SuperArrow(
                title = "搜索热词",
                summary = "自定义搜索栏热词或禁用热词",
                onClick = onNavigateToDarkWord
            )
        }
    }
}