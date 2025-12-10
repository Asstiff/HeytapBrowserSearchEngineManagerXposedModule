// app/src/main/java/com/upuaut/xposedsearch/ui/HotSitesUiState.kt
package com.upuaut.xposedsearch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.upuaut.xposedsearch.HotSiteConfig
import com.upuaut.xposedsearch.HotSiteConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HotSitesUiState(
    val sites: List<HotSiteConfig> = emptyList(),
    val isModuleEnabled: Boolean = true,
    val hasDefaultSites: Boolean = false,
    val showAddDialog: Boolean = false,
    val editingSite: HotSiteConfig? = null,
    val deletingSite: HotSiteConfig? = null,
    val showResetDialog: Boolean = false
)

class HotSitesViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HotSitesUiState())
    val uiState: StateFlow<HotSitesUiState> = _uiState.asStateFlow()

    private val context get() = getApplication<Application>()

    init {
        refreshSites()
    }

    fun refreshSites() {
        viewModelScope.launch {
            val sites = HotSiteConfigManager.loadSites(context)
            val moduleEnabled = HotSiteConfigManager.isModuleEnabled(context)
            val hasDefault = HotSiteConfigManager.hasDefaultSites(context)
            _uiState.value = _uiState.value.copy(
                sites = sites,
                isModuleEnabled = moduleEnabled,
                hasDefaultSites = hasDefault
            )
        }
    }

    fun setModuleEnabled(enabled: Boolean) {
        HotSiteConfigManager.setModuleEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(isModuleEnabled = enabled)
    }

    fun updateSiteEnabled(id: Long, enabled: Boolean) {
        HotSiteConfigManager.updateSiteEnabled(context, id, enabled)
        refreshSites()
    }

    fun addSite(name: String, url: String, iconUrl: String): Boolean {
        val success = HotSiteConfigManager.addSite(context, name, url, iconUrl)
        if (success) refreshSites()
        return success
    }

    fun updateSite(id: Long, name: String, url: String, iconUrl: String, enabled: Boolean) {
        HotSiteConfigManager.updateSite(context, id, name, url, iconUrl, enabled)
        refreshSites()
    }

    fun deleteSite(id: Long): Boolean {
        val success = HotSiteConfigManager.deleteSite(context, id)
        if (success) refreshSites()
        return success
    }

    fun moveSite(fromIndex: Int, toIndex: Int) {
        HotSiteConfigManager.moveSite(context, fromIndex, toIndex)
        refreshSites()
    }

    /**
     * 根据 ID 列表重新排序网站
     */
    fun reorderSites(orderedIds: List<Long>) {
        HotSiteConfigManager.reorderSites(context, orderedIds)
        refreshSites()
    }

    fun resetToDefault() {
        HotSiteConfigManager.resetToDefault(context)
        refreshSites()
    }

    fun showAddDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAddDialog = show)
    }

    fun showEditDialog(site: HotSiteConfig?) {
        _uiState.value = _uiState.value.copy(editingSite = site)
    }

    fun showDeleteDialog(site: HotSiteConfig?) {
        _uiState.value = _uiState.value.copy(deletingSite = site)
    }

    fun showResetDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showResetDialog = show)
    }
}