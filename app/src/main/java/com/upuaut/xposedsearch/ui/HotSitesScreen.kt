// app/src/main/java/com/upuaut/xposedsearch/ui/HotSitesScreen.kt
package com.upuaut.xposedsearch.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upuaut.xposedsearch.HotSiteConfig
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HotSitesScreen(
    viewModel: HotSitesViewModel = viewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 监听生命周期，返回时刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSites()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 编辑模式状态
    var isEditMode by remember { mutableStateOf(false) }

    // 拖拽状态
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 使用可变列表来实时更新排序
    val sites = remember { mutableStateListOf<HotSiteConfig>() }
    var draggedItemIndex by remember { mutableIntStateOf(-1) }
    var draggedOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uiState.sites) {
        if (draggedItemIndex < 0) {
            sites.clear()
            sites.addAll(uiState.sites)
        }
    }

    val showAddDialog = remember { mutableStateOf(false) }
    val showEditDialog = remember { mutableStateOf(false) }
    val showDeleteDialog = remember { mutableStateOf(false) }
    val showResetDialog = remember { mutableStateOf(false) }

    LaunchedEffect(uiState.showAddDialog) { showAddDialog.value = uiState.showAddDialog }
    LaunchedEffect(uiState.editingSite) { showEditDialog.value = uiState.editingSite != null }
    LaunchedEffect(uiState.deletingSite) { showDeleteDialog.value = uiState.deletingSite != null }
    LaunchedEffect(uiState.showResetDialog) { showResetDialog.value = uiState.showResetDialog }

    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "热门网站",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (uiState.hasDefaultSites) {
                        IconButton(onClick = { viewModel.showResetDialog(true) }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "恢复默认"
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddDialog(true) }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加网站",
                    tint = Color.White
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null
        ) {
            item {
                ModuleEnableCard(
                    isEnabled = uiState.isModuleEnabled,
                    onEnabledChange = { enabled ->
                        viewModel.setModuleEnabled(enabled)
                        val msg = if (enabled) "热门网站管理已启用" else "热门网站管理已禁用"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (sites.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无热门网站\n\n请先打开浏览器首页\n模块会自动获取网站列表",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                // 小标题和编辑按钮
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallTitle(text = "网站", modifier = Modifier.padding(0.dp))

                        Text(
                            text = if (isEditMode) "完成" else "编辑",
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (isEditMode && draggedItemIndex < 0) {
                                        // 退出编辑模式时保存排序
                                        viewModel.reorderSites(sites.map { it.id })
                                    }
                                    isEditMode = !isEditMode
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                itemsIndexed(
                    items = sites,
                    key = { _, site -> site.id }
                ) { index, site ->
                    val isDragging = draggedItemIndex == index

                    DraggableHotSiteItem(
                        site = site,
                        isEditMode = isEditMode,
                        isDragging = isDragging,
                        dragOffset = if (isDragging) draggedOffset else 0f,
                        onEnabledChange = { enabled ->
                            viewModel.updateSiteEnabled(site.id, enabled)
                        },
                        onEdit = { viewModel.showEditDialog(site) },
                        onDelete = { viewModel.showDeleteDialog(site) },
                        onDragStart = {
                            draggedItemIndex = index
                            draggedOffset = 0f
                        },
                        onDrag = { delta ->
                            draggedOffset += delta

                            // 计算目标位置
                            val itemHeight = 72f
                            val offsetItems = (draggedOffset / itemHeight).toInt()
                            val targetIndex = (index + offsetItems).coerceIn(0, sites.size - 1)

                            if (targetIndex != index) {
                                // 移动项目
                                val item = sites.removeAt(index)
                                sites.add(targetIndex, item)
                                draggedItemIndex = targetIndex
                                draggedOffset = draggedOffset - (offsetItems * itemHeight)
                            }
                        },
                        onDragEnd = {
                            draggedItemIndex = -1
                            draggedOffset = 0f
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    // 对话框
    AddHotSiteDialog(
        show = showAddDialog,
        onDismiss = { viewModel.showAddDialog(false) },
        onConfirm = { name, url, iconUrl ->
            val success = viewModel.addSite(name, url, iconUrl)
            if (success) {
                Toast.makeText(context, "已添加", Toast.LENGTH_SHORT).show()
                viewModel.showAddDialog(false)
            } else {
                Toast.makeText(context, "URL已存在", Toast.LENGTH_SHORT).show()
            }
        }
    )

    uiState.editingSite?.let { site ->
        EditHotSiteDialog(
            show = showEditDialog,
            site = site,
            onDismiss = { viewModel.showEditDialog(null) },
            onConfirm = { name, url, iconUrl ->
                viewModel.updateSite(site.id, name, url, iconUrl, site.enabled)
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                viewModel.showEditDialog(null)
            }
        )
    }

    uiState.deletingSite?.let { site ->
        DeleteHotSiteDialog(
            show = showDeleteDialog,
            siteName = site.name,
            onDismiss = { viewModel.showDeleteDialog(null) },
            onConfirm = {
                viewModel.deleteSite(site.id)
                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                viewModel.showDeleteDialog(null)
            }
        )
    }

    ResetToDefaultDialog(
        show = showResetDialog,
        onDismiss = { viewModel.showResetDialog(false) },
        onConfirm = {
            viewModel.resetToDefault()
            Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
            viewModel.showResetDialog(false)
        }
    )
}

private val CardPadding = 20.dp
private val ActionButtonColor = Color(0xFFBDBDBD)

@Composable
fun ModuleEnableCard(
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
                title = "启用热门网站管理",
                summary = "自定义浏览器首页热门网站",
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                insideMargin = PaddingValues(0.dp)
            )
        }
    }
}

@Composable
fun DraggableHotSiteItem(
    site: HotSiteConfig,
    isEditMode: Boolean,
    isDragging: Boolean,
    dragOffset: Float,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        label = "elevation"
    )

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        label = "scale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                scaleX = scale
                scaleY = scale
                shadowElevation = with(density) { elevation.toPx() }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CardPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 拖动把手 - 编辑模式显示
            AnimatedVisibility(
                visible = isEditMode,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "拖动排序",
                    tint = ActionButtonColor,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.y)
                                }
                            )
                        }
                )
            }

            // 网站名称
            Text(
                text = site.name,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = ActionButtonColor
                    )
                }

                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = ActionButtonColor
                    )
                }

                Switch(
                    checked = site.enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }
    }
}

@Composable
fun AddHotSiteDialog(
    show: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, iconUrl: String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var iconUrl by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(show.value) {
        if (show.value) {
            name = ""; url = ""; iconUrl = ""
            nameError = null; urlError = null
        }
    }

    SuperDialog(
        title = "添加网站",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it; nameError = null },
                label = "网站名称",
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            if (nameError != null) {
                Text(text = nameError!!, color = Color(0xFFF44336))
            }

            TextField(
                value = url,
                onValueChange = { url = it; urlError = null },
                label = "网站 URL",
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().height(68.dp),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            if (urlError != null) {
                Text(text = urlError!!, color = Color(0xFFF44336))
            }

            TextField(
                value = iconUrl,
                onValueChange = { iconUrl = it },
                label = "图标 URL (可选)",
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().height(68.dp),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "添加",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        var hasError = false
                        if (name.isBlank()) { nameError = "名称不能为空"; hasError = true }
                        if (url.isBlank()) { urlError = "URL不能为空"; hasError = true }
                        if (!hasError) onConfirm(name.trim(), url.trim(), iconUrl.trim())
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun EditHotSiteDialog(
    show: MutableState<Boolean>,
    site: HotSiteConfig,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, iconUrl: String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var name by remember(site) { mutableStateOf(site.name) }
    var url by remember(site) { mutableStateOf(site.url) }
    var iconUrl by remember(site) { mutableStateOf(site.iconUrl ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }

    SuperDialog(
        title = "编辑网站",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it; nameError = null },
                label = "网站名称",
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            if (nameError != null) {
                Text(text = nameError!!, color = Color(0xFFF44336))
            }

            TextField(
                value = url,
                onValueChange = { url = it; urlError = null },
                label = "网站 URL",
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().height(68.dp),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            if (urlError != null) {
                Text(text = urlError!!, color = Color(0xFFF44336))
            }

            TextField(
                value = iconUrl,
                onValueChange = { iconUrl = it },
                label = "图标 URL (可选)",
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().height(68.dp),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "保存",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        var hasError = false
                        if (name.isBlank()) { nameError = "名称不能为空"; hasError = true }
                        if (url.isBlank()) { urlError = "URL不能为空"; hasError = true }
                        if (!hasError) onConfirm(name.trim(), url.trim(), iconUrl.trim())
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun DeleteHotSiteDialog(
    show: MutableState<Boolean>,
    siteName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SuperDialog(
        title = "删除网站",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "确定要删除 \"$siteName\" 吗？",
                color = MiuixTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "删除",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun ResetToDefaultDialog(
    show: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SuperDialog(
        title = "恢复默认",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "确定要恢复为浏览器默认的热门网站吗？\n\n你的自定义设置将被清除。",
                color = MiuixTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "恢复默认",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}