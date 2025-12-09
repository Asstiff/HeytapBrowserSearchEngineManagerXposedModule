package com.upuaut.xposedsearch.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upuaut.xposedsearch.SearchEngineConfig
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
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

    val builtinEngines = remember(uiState.engines) {
        uiState.engines.filter { it.isBuiltin }
    }
    val customEngines = remember(uiState.engines) {
        uiState.engines.filter { !it.isBuiltin }
    }

    val showAddDialog = remember { mutableStateOf(uiState.showAddDialog) }
    val showEditDialog = remember { mutableStateOf(uiState.editingEngine != null) }
    val showDeleteDialog = remember { mutableStateOf(uiState.deletingEngine != null) }
    val showHideIconDialog = remember { mutableStateOf(uiState.showHideIconConfirmDialog) }
    val showUpdateDialog = remember { mutableStateOf(uiState.updatingEngine != null) }
    val showRemovedDialog = remember { mutableStateOf(uiState.removedEngine != null) }
    val showConflictDialog = remember { mutableStateOf(uiState.conflictEngine != null) }

    LaunchedEffect(uiState.showAddDialog) { showAddDialog.value = uiState.showAddDialog }
    LaunchedEffect(uiState.editingEngine) { showEditDialog.value = uiState.editingEngine != null }
    LaunchedEffect(uiState.deletingEngine) { showDeleteDialog.value = uiState.deletingEngine != null }
    LaunchedEffect(uiState.showHideIconConfirmDialog) { showHideIconDialog.value = uiState.showHideIconConfirmDialog }
    LaunchedEffect(uiState.updatingEngine) { showUpdateDialog.value = uiState.updatingEngine != null }
    LaunchedEffect(uiState.removedEngine) { showRemovedDialog.value = uiState.removedEngine != null }
    LaunchedEffect(uiState.conflictEngine) { showConflictDialog.value = uiState.conflictEngine != null }

    // 获取导航条高度
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 滚动行为 - 用于动态标题栏效果
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "HeytapEngineManager",
                scrollBehavior = topAppBarScrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddDialog(true) },
                modifier = Modifier.padding(bottom = navigationBarPadding)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加引擎",
                    tint = Color.White
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic() // 滚动到底部振动效果
                .overScrollVertical() // 过度滚动回弹效果
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection), // 连接滚动行为
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 12.dp,
                bottom = 80.dp + navigationBarPadding
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null // 禁用默认的 overscroll 效果，使用 miuix 的
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

            if (uiState.engines.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无搜索引擎\n\n请先打开浏览器的搜索引擎设置页面\n模块会自动获取引擎列表",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            if (builtinEngines.isNotEmpty()) {
                item {
                    SmallTitle(text = "浏览器内置引擎")
                }
                items(builtinEngines, key = { it.key }) { engine ->
                    EngineItem(
                        engine = engine,
                        onEnabledChange = { enabled ->
                            viewModel.updateEngineEnabled(engine.key, enabled)
                        },
                        onEdit = { viewModel.showEditDialog(engine) },
                        onDelete = null,
                        onReset = if (engine.canReset()) {
                            {
                                viewModel.resetEngine(engine.key)
                                Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
                            }
                        } else null,
                        onUpdate = if (engine.hasPendingUpdate()) {
                            { viewModel.showUpdateDialog(engine) }
                        } else null,
                        onRemoved = if (engine.isRemovedFromBrowser) {
                            { viewModel.showRemovedDialog(engine) }
                        } else null,
                        onConflict = null
                    )
                }
            }

            if (customEngines.isNotEmpty()) {
                item {
                    SmallTitle(text = "自定义引擎")
                }
                items(customEngines, key = { it.key }) { engine ->
                    EngineItem(
                        engine = engine,
                        onEnabledChange = { enabled ->
                            viewModel.updateEngineEnabled(engine.key, enabled)
                        },
                        onEdit = { viewModel.showEditDialog(engine) },
                        onDelete = { viewModel.showDeleteDialog(engine) },
                        onReset = null,
                        onUpdate = null,
                        onRemoved = null,
                        onConflict = if (engine.hasBuiltinConflict()) {
                            { viewModel.showConflictDialog(engine) }
                        } else null
                    )
                }
            }
        }
    }

    // 对话框
    AddEngineDialog(
        show = showAddDialog,
        onDismiss = { viewModel.showAddDialog(false) },
        onConfirm = { key, name, url ->
            val success = viewModel.addCustomEngine(key, name, url)
            if (success) {
                Toast.makeText(context, "已添加", Toast.LENGTH_SHORT).show()
                viewModel.showAddDialog(false)
            } else {
                Toast.makeText(context, "标识符已存在", Toast.LENGTH_SHORT).show()
            }
        }
    )

    uiState.editingEngine?.let { engine ->
        EditEngineDialog(
            show = showEditDialog,
            engine = engine,
            onDismiss = { viewModel.showEditDialog(null) },
            onConfirm = { newKey, name, url ->
                if (engine.isBuiltin) {
                    viewModel.updateEngine(engine.key, name, url, engine.enabled)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    viewModel.showEditDialog(null)
                } else {
                    val success = viewModel.updateCustomEngine(engine.key, newKey, name, url, engine.enabled)
                    if (success) {
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                        viewModel.showEditDialog(null)
                    } else {
                        Toast.makeText(context, "标识符已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onKeyConflict = {
                Toast.makeText(context, "标识符已存在", Toast.LENGTH_SHORT).show()
            }
        )
    }

    uiState.deletingEngine?.let { engine ->
        DeleteConfirmDialog(
            show = showDeleteDialog,
            engineName = engine.name,
            onDismiss = { viewModel.showDeleteDialog(null) },
            onConfirm = {
                viewModel.deleteEngine(engine.key)
                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                viewModel.showDeleteDialog(null)
            }
        )
    }

    uiState.updatingEngine?.let { engine ->
        UpdateAvailableDialog(
            show = showUpdateDialog,
            engine = engine,
            onDismiss = { viewModel.showUpdateDialog(null) },
            onApply = {
                viewModel.applyPendingUpdate(engine.key)
                Toast.makeText(context, "已更新", Toast.LENGTH_SHORT).show()
                viewModel.showUpdateDialog(null)
            },
            onIgnore = {
                viewModel.ignorePendingUpdate(engine.key)
                Toast.makeText(context, "已忽略更新", Toast.LENGTH_SHORT).show()
                viewModel.showUpdateDialog(null)
            }
        )
    }

    uiState.removedEngine?.let { engine ->
        EngineRemovedDialog(
            show = showRemovedDialog,
            engine = engine,
            onDismiss = { viewModel.showRemovedDialog(null) },
            onConvertToCustom = {
                viewModel.convertToCustomEngine(engine.key)
                Toast.makeText(context, "已转为自定义引擎", Toast.LENGTH_SHORT).show()
                viewModel.showRemovedDialog(null)
            },
            onDelete = {
                viewModel.deleteEngine(engine.key)
                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                viewModel.showRemovedDialog(null)
            }
        )
    }

    uiState.conflictEngine?.let { engine ->
        BuiltinConflictDialog(
            show = showConflictDialog,
            engine = engine,
            onDismiss = { viewModel.showConflictDialog(null) },
            onConvertToBuiltin = {
                viewModel.convertCustomToBuiltin(engine.key)
                Toast.makeText(context, "已转为内置引擎", Toast.LENGTH_SHORT).show()
                viewModel.showConflictDialog(null)
            },
            onCreateCopy = {
                val newKey = viewModel.createCustomEngineCopy(engine.key)
                if (newKey != null) {
                    Toast.makeText(context, "已创建副本: $newKey", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "创建副本失败", Toast.LENGTH_SHORT).show()
                }
                viewModel.showConflictDialog(null)
            }
        )
    }

    HideIconConfirmDialog(
        show = showHideIconDialog,
        onDismiss = { viewModel.showHideIconConfirmDialog(false) },
        onConfirm = {
            viewModel.setIconHidden(true)
            viewModel.showHideIconConfirmDialog(false)
            Toast.makeText(context, "桌面图标已隐藏\n可通过 LSPosed 打开本应用", Toast.LENGTH_LONG).show()
        }
    )
}

// 其余组件保持不变...
private val CardPadding = 20.dp
private val ActionButtonColor = Color(0xFFBDBDBD)

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
fun EngineItem(
    engine: SearchEngineConfig,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onReset: (() -> Unit)?,
    onUpdate: (() -> Unit)?,
    onRemoved: (() -> Unit)?,
    onConflict: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CardPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = engine.name,
                color = MiuixTheme.colorScheme.onSurface
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                onRemoved?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "已从浏览器消失",
                            tint = Color(0xFFF44336)
                        )
                    }
                }

                onConflict?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "与内置引擎冲突",
                            tint = Color(0xFFFF9800)
                        )
                    }
                }

                onUpdate?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = "有可用更新",
                            tint = Color(0xFF2196F3)
                        )
                    }
                }

                onDelete?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = ActionButtonColor
                        )
                    }
                }

                onReset?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "恢复默认",
                            tint = ActionButtonColor
                        )
                    }
                }

                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = ActionButtonColor
                    )
                }

                Switch(
                    checked = engine.enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    show: MutableState<Boolean>,
    engine: SearchEngineConfig,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onIgnore: () -> Unit
) {
    SuperDialog(
        title = "发现更新",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "浏览器中的 \"${engine.name}\" 有更新：",
                color = MiuixTheme.colorScheme.onSurface
            )

            Surface(
                color = MiuixTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = engine.updateDescription,
                    modifier = Modifier.padding(12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "忽略",
                    onClick = onIgnore,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "应用更新",
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun EngineRemovedDialog(
    show: MutableState<Boolean>,
    engine: SearchEngineConfig,
    onDismiss: () -> Unit,
    onConvertToCustom: () -> Unit,
    onDelete: () -> Unit
) {
    SuperDialog(
        title = "引擎已从浏览器消失",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "\"${engine.name}\" 已不再是浏览器的内置引擎。\n\n你可以将其转为自定义引擎继续使用，或者删除它。",
                color = MiuixTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "转为自定义",
                    onClick = onConvertToCustom,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun BuiltinConflictDialog(
    show: MutableState<Boolean>,
    engine: SearchEngineConfig,
    onDismiss: () -> Unit,
    onConvertToBuiltin: () -> Unit,
    onCreateCopy: () -> Unit
) {
    SuperDialog(
        title = "发现同名内置引擎",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "浏览器新增了标识符同为「${engine.key}」的内置引擎。",
                color = MiuixTheme.colorScheme.onSurface
            )

            Surface(
                color = MiuixTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "内置引擎信息：",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    if (engine.conflictBuiltinName != null) {
                        Text(
                            text = "名称: ${engine.conflictBuiltinName}",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    if (engine.conflictBuiltinSearchUrl != null) {
                        Text(
                            text = "URL: ${engine.conflictBuiltinSearchUrl}",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2
                        )
                    }
                }
            }

            Text(
                text = "你可以选择：\n• 转为内置：保留你的自定义设置，但作为内置引擎的修改版\n• 创建副本：保留自定义引擎并改名，同时添加内置引擎",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "转为内置",
                    onClick = onConvertToBuiltin,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "创建副本",
                    onClick = onCreateCopy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun AddEngineDialog(
    show: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onConfirm: (key: String, name: String, url: String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(show.value) {
        if (show.value) {
            key = ""
            name = ""
            url = ""
            keyError = null
            nameError = null
            urlError = null
        }
    }

    SuperDialog(
        title = "添加自定义搜索引擎",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextField(
                value = key,
                onValueChange = {
                    key = it
                    keyError = null
                },
                label = "标识符",
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            if (keyError != null) {
                Text(text = keyError!!, color = Color(0xFFF44336))
            }

            TextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = "显示名称",
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            if (nameError != null) {
                Text(text = nameError!!, color = Color(0xFFF44336))
            }

            TextField(
                value = url,
                onValueChange = {
                    url = it
                    urlError = null
                },
                label = "搜索 URL · {searchTerms}",
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
            if (urlError != null) {
                Text(text = urlError!!, color = Color(0xFFF44336))
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "添加",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        var hasError = false
                        if (key.isBlank()) {
                            keyError = "标识符不能为空"
                            hasError = true
                        }
                        if (name.isBlank()) {
                            nameError = "名称不能为空"
                            hasError = true
                        }
                        if (url.isBlank()) {
                            urlError = "URL不能为空"
                            hasError = true
                        } else if (!url.contains("%s") && !url.contains("{searchTerms}")) {
                            urlError = "URL必须包含 %s 或 {searchTerms}"
                            hasError = true
                        }

                        if (!hasError) {
                            onConfirm(key.trim(), name.trim(), url.trim())
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun EditEngineDialog(
    show: MutableState<Boolean>,
    engine: SearchEngineConfig,
    onDismiss: () -> Unit,
    onConfirm: (key: String, name: String, url: String) -> Unit,
    onKeyConflict: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var key by remember(engine) { mutableStateOf(engine.key) }
    var name by remember(engine) { mutableStateOf(engine.name) }
    var url by remember(engine) { mutableStateOf(engine.searchUrl ?: "") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }

    val isBuiltin = engine.isBuiltin

    SuperDialog(
        title = "编辑搜索引擎",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isBuiltin) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MiuixTheme.colorScheme.disabledSecondaryVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Column {
                        Text(
                            text = "标识符",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = engine.key,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        )
                    }
                }
            } else {
                TextField(
                    value = key,
                    onValueChange = {
                        key = it
                        keyError = null
                    },
                    label = "标识符",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                if (keyError != null) {
                    Text(text = keyError!!, color = Color(0xFFF44336))
                }
            }

            TextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = "显示名称",
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            if (nameError != null) {
                Text(text = nameError!!, color = Color(0xFFF44336))
            }

            TextField(
                value = url,
                onValueChange = { url = it },
                label = "搜索 URL",
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "保存",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        var hasError = false

                        if (!isBuiltin && key.isBlank()) {
                            keyError = "标识符不能为空"
                            hasError = true
                        }

                        if (name.isBlank()) {
                            nameError = "名称不能为空"
                            hasError = true
                        }

                        if (!hasError) {
                            val finalKey = if (isBuiltin) engine.key else key.trim()
                            onConfirm(finalKey, name.trim(), url.trim())
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    show: MutableState<Boolean>,
    engineName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SuperDialog(
        title = "删除搜索引擎",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "确定要删除 \"$engineName\" 吗？",
                color = MiuixTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
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
fun HideIconConfirmDialog(
    show: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SuperDialog(
        title = "隐藏桌面图标",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "隐藏后，只能通过 LSPosed 模块管理界面打开本应用。\n\n确定要隐藏图标吗？",
                color = MiuixTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "确定",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}