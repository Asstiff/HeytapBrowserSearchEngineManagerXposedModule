package com.example.xposedsearch.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.xposedsearch.AppUtils
import com.example.xposedsearch.ConfigManager
import com.example.xposedsearch.RootUtils
import com.example.xposedsearch.SearchEngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val engines: List<SearchEngineConfig> = emptyList(),
    val hasRootAccess: Boolean = false,
    val isCheckingRoot: Boolean = true,
    val isRequestingRoot: Boolean = false,
    val isForceStoppingBrowser: Boolean = false,
    val isIconHidden: Boolean = false,
    val isXposedActive: Boolean = false,
    val showAddDialog: Boolean = false,
    val editingEngine: SearchEngineConfig? = null,
    val deletingEngine: SearchEngineConfig? = null,
    val showHideIconConfirmDialog: Boolean = false,
    // 新增状态
    val updatingEngine: SearchEngineConfig? = null,  // 显示更新对话框
    val removedEngine: SearchEngineConfig? = null    // 显示已消失对话框
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TARGET_PACKAGE = "com.heytap.browser"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val context get() = getApplication<Application>()

    init {
        refreshAll()
    }

    fun refreshAll() {
        refreshEngines()
        checkRootStatus()
        refreshIconState()
        checkXposedStatus()
    }

    fun refreshEngines() {
        val engines = ConfigManager.loadEngines(context)
        _uiState.value = _uiState.value.copy(engines = engines)
    }

    private fun refreshIconState() {
        _uiState.value = _uiState.value.copy(
            isIconHidden = AppUtils.isIconHidden(context)
        )
    }

    private fun checkXposedStatus() {
        _uiState.value = _uiState.value.copy(
            isXposedActive = isXposedModuleActive()
        )
    }

    private fun checkRootStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingRoot = true)
            val hasRoot = withContext(Dispatchers.IO) {
                RootUtils.checkRootAccess()
            }
            _uiState.value = _uiState.value.copy(
                hasRootAccess = hasRoot,
                isCheckingRoot = false
            )
        }
    }

    fun forceStopBrowser(): Boolean {
        if (!_uiState.value.hasRootAccess) return false

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isForceStoppingBrowser = true)
            withContext(Dispatchers.IO) {
                RootUtils.forceStopApp(TARGET_PACKAGE)
            }
            _uiState.value = _uiState.value.copy(isForceStoppingBrowser = false)
        }
        return true
    }

    fun setIconHidden(hidden: Boolean) {
        AppUtils.setIconHidden(context, hidden)
        _uiState.value = _uiState.value.copy(isIconHidden = hidden)
    }

    fun showHideIconConfirmDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showHideIconConfirmDialog = show)
    }

    fun updateEngineEnabled(key: String, enabled: Boolean) {
        ConfigManager.updateEngineEnabled(context, key, enabled)
        refreshEngines()
    }

    fun addCustomEngine(key: String, name: String, searchUrl: String): Boolean {
        val success = ConfigManager.addCustomEngine(context, key, name, searchUrl)
        if (success) refreshEngines()
        return success
    }

    fun updateEngine(key: String, name: String, searchUrl: String, enabled: Boolean) {
        ConfigManager.updateEngineByUser(context, key, name, searchUrl, enabled)
        refreshEngines()
    }

    fun updateCustomEngine(oldKey: String, newKey: String, name: String, url: String, enabled: Boolean) {
        val success = ConfigManager.updateCustomEngineWithKey(context, oldKey, newKey, name, url, enabled)
        if (success) {
            viewModelScope.launch {
                refreshEngines()
            }
        }
    }

    fun deleteEngine(key: String): Boolean {
        val success = ConfigManager.deleteEngine(context, key)
        if (success) refreshEngines()
        return success
    }

    fun resetEngine(key: String): Boolean {
        val success = ConfigManager.resetEngine(context, key)
        if (success) refreshEngines()
        return success
    }

    // 新增方法：应用待更新
    fun applyPendingUpdate(key: String) {
        ConfigManager.applyPendingUpdate(context, key)
        refreshEngines()
    }

    // 新增方法：忽略待更新
    fun ignorePendingUpdate(key: String) {
        ConfigManager.ignorePendingUpdate(context, key)
        refreshEngines()
    }

    // 新增方法：转换为自定义引擎
    fun convertToCustomEngine(key: String) {
        ConfigManager.convertToCustomEngine(context, key)
        refreshEngines()
    }

    fun showAddDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAddDialog = show)
    }

    fun showEditDialog(engine: SearchEngineConfig?) {
        _uiState.value = _uiState.value.copy(editingEngine = engine)
    }

    fun showDeleteDialog(engine: SearchEngineConfig?) {
        _uiState.value = _uiState.value.copy(deletingEngine = engine)
    }

    // 新增方法：显示更新对话框
    fun showUpdateDialog(engine: SearchEngineConfig?) {
        _uiState.value = _uiState.value.copy(updatingEngine = engine)
    }

    // 新增方法：显示已消失对话框
    fun showRemovedDialog(engine: SearchEngineConfig?) {
        _uiState.value = _uiState.value.copy(removedEngine = engine)
    }

    fun openBrowser() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * 此方法会被 Xposed 模块 hook，返回 true 表示模块已激活
 */
private fun isXposedModuleActive(): Boolean {
    return false
}