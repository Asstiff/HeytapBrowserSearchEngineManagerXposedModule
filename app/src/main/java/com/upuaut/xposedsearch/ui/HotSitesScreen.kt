package com.upuaut.xposedsearch.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.onSizeChanged
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
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

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

    // UI 临时列表：用于拖拽时的实时显示
    val sites = remember { mutableStateListOf<HotSiteConfig>() }

    // 【关键修改1】分离状态
    // draggingSiteId: 控制位移逻辑、zIndex。在回弹动画结束前保持有值。
    var draggingSiteId by remember { mutableStateOf<Long?>(null) }
    // heldSiteId: 控制缩放、阴影视觉。手指松开立刻变空。
    var heldSiteId by remember { mutableStateOf<Long?>(null) }

    // 动画状态管理 - 只在父级管理位移
    val dragOffset = remember { Animatable(0f) }

    // 记录列表项的高度（像素），用于计算拖拽阈值
    var itemHeightPx by remember { mutableIntStateOf(with(density) { (72 + 12).dp.roundToPx() }) }

    LaunchedEffect(uiState.sites) {
        // 只有在非拖拽状态下才同步数据，防止拖拽时数据跳变
        if (draggingSiteId == null) {
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
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallTitle(text = "网站")

                        Text(
                            text = if (isEditMode) "完成" else "编辑",
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (isEditMode) {
                                        viewModel.reorderSites(sites.map { it.id })
                                    }
                                    isEditMode = !isEditMode
                                }
                                .padding(horizontal = 24.dp)
                        )
                    }
                }

                itemsIndexed(
                    items = sites,
                    key = { _, site -> site.id }
                ) { _, site ->
                    val isDragging = draggingSiteId == site.id
                    // 【关键修改2】判断是否被按住
                    val isHeld = heldSiteId == site.id

                    DraggableHotSiteItem(
                        site = site,
                        isEditMode = isEditMode,
                        isDragging = isDragging, // 用于 zIndex 和位移判断
                        isHeld = isHeld,         // 用于 缩放/阴影 视觉判断
                        dragOffset = if (isDragging) dragOffset.value else 0f,
                        onEnabledChange = { enabled ->
                            viewModel.updateSiteEnabled(site.id, enabled)
                        },
                        onEdit = { viewModel.showEditDialog(site) },
                        onDelete = { viewModel.showDeleteDialog(site) },
                        onDragStart = {
                            draggingSiteId = site.id
                            heldSiteId = site.id // 同时激活按住状态
                            scope.launch { dragOffset.snapTo(0f) }
                        },
                        onDrag = { delta ->
                            scope.launch {
                                val currentOffset = dragOffset.value + delta
                                dragOffset.snapTo(currentOffset)

                                val currentIndex = sites.indexOfFirst { it.id == site.id }
                                if (currentIndex == -1) return@launch

                                val threshold = itemHeightPx.toFloat()

                                // 向下拖拽
                                if (currentOffset > threshold) {
                                    if (currentIndex < sites.lastIndex) {
                                        val item = sites.removeAt(currentIndex)
                                        sites.add(currentIndex + 1, item)
                                        dragOffset.snapTo(currentOffset - threshold)
                                    }
                                }
                                // 向上拖拽
                                else if (currentOffset < -threshold) {
                                    if (currentIndex > 0) {
                                        val item = sites.removeAt(currentIndex)
                                        sites.add(currentIndex - 1, item)
                                        dragOffset.snapTo(currentOffset + threshold)
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            // 【关键修改3】松手时，立即清除按住状态，视觉立刻回弹
                            heldSiteId = null

                            scope.launch {
                                // 启动位移回弹
                                dragOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                // 动画完全结束后，才清除位移逻辑状态
                                draggingSiteId = null
                            }
                        },
                        onSizeChanged = { height ->
                            val spacingPx = with(density) { 12.dp.roundToPx() }
                            itemHeightPx = height + spacingPx
                        },
                        modifier = if (isDragging) Modifier else Modifier.animateItem()
                    )
                }
            }
        }
    }

    // Dialogs 代码保持不变...
    AddHotSiteDialog(
        show = showAddDialog,
        onDismiss = { viewModel.showAddDialog(false) },
        onConfirm = { name, url, iconUrl ->
            val success = viewModel.addSite(name, url, iconUrl)
            if (success) {
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

// 辅助组件
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
    isDragging: Boolean, // 仍需此参数来控制 zIndex 和是否应用 dragOffset
    isHeld: Boolean,     // 新增：专门控制缩放和阴影
    dragOffset: Float,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSizeChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    // 【关键修改4】使用 isHeld 控制视觉动画
    val scale by animateFloatAsState(
        targetValue = if (isHeld) 1.05f else 1f,
        animationSpec = springSpec,
        label = "scale"
    )

    val elevation by animateDpAsState(
        targetValue = if (isHeld) 8.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "elevation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .onSizeChanged { onSizeChanged(it.height) }
            // zIndex 必须依赖 isDragging，因为即使松手了（isHeld=false），在回弹过程中它仍需在最上层
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                scaleX = scale
                scaleY = scale
                shadowElevation = with(density) { elevation.toPx() }

                shape = RoundedCornerShape(20.dp)
                clip = false
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CardPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 拖动把手
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

// Dialogs 保持不变...
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