// app/src/main/java/com/upuaut/xposedsearch/ui/DarkWordScreen.kt
package com.upuaut.xposedsearch.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun DarkWordScreen(
    viewModel: DarkWordViewModel = viewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
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
                title = "搜索热词",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
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
            // 启用模块 Card
            item {
                DarkWordEnableCard(
                    isEnabled = uiState.isModuleEnabled,
                    onEnabledChange = { enabled: Boolean ->
                        viewModel.setModuleEnabled(enabled)
                        val msg = if (enabled) "热词管理已启用" else "热词管理已禁用"
                        Toast.makeText(context, "$msg，重启浏览器生效", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 设置小标题
            item {
                SmallTitle(text = "设置")
            }

            // 设置 Card
            item {
                DarkWordSettingsCard(
                    isDarkWordDisabled = uiState.isDarkWordDisabled,
                    moduleEnabled = uiState.isModuleEnabled,
                    onDarkWordDisabledChange = { disabled: Boolean ->
                        viewModel.setDarkWordDisabled(disabled)
                        val msg = if (disabled) "热词已禁用" else "热词已启用"
                        Toast.makeText(context, "$msg，重启浏览器生效", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 说明
            item {
                DarkWordInfoCard()
            }
        }
    }
}

private val CardPadding = 20.dp

@Composable
private fun DarkWordEnableCard(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding)
        ) {
            SuperSwitch(
                title = "启用热词管理",
                summary = "开启后可控制搜索栏热词显示",
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                insideMargin = PaddingValues(0.dp)
            )
        }
    }
}

@Composable
private fun DarkWordSettingsCard(
    isDarkWordDisabled: Boolean,
    moduleEnabled: Boolean,
    onDarkWordDisabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding)
        ) {
            SuperSwitch(
                title = "禁用热词显示",
                summary = "关闭搜索栏的热词推荐（完全不显示）",
                checked = isDarkWordDisabled,
                onCheckedChange = onDarkWordDisabledChange,
                enabled = moduleEnabled,
                insideMargin = PaddingValues(0.dp)
            )
        }
    }
}

@Composable
private fun DarkWordInfoCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "说明",
            style = MiuixTheme.textStyles.headline2,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Text(
            text = "• 启用「热词管理」后，模块才会生效\n• 开启「禁用热词显示」可隐藏搜索栏的热门搜索词\n• 修改设置后需要重启浏览器才能生效",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}