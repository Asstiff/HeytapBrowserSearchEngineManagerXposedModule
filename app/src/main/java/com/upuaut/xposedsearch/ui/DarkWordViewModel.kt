// app/src/main/java/com/upuaut/xposedsearch/ui/DarkWordViewModel.kt
package com.upuaut.xposedsearch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.upuaut.xposedsearch.DarkWordConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DarkWordUiState(
    val isModuleEnabled: Boolean = true,
    val isDarkWordDisabled: Boolean = false
)

class DarkWordViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DarkWordUiState())
    val uiState: StateFlow<DarkWordUiState> = _uiState.asStateFlow()

    private val context get() = getApplication<Application>()

    init {
        refresh()
    }

    fun refresh() {
        val moduleEnabled = DarkWordConfigManager.isModuleEnabled(context)
        val darkWordDisabled = DarkWordConfigManager.isDarkWordDisabled(context)
        _uiState.value = DarkWordUiState(
            isModuleEnabled = moduleEnabled,
            isDarkWordDisabled = darkWordDisabled
        )
    }

    fun setModuleEnabled(enabled: Boolean) {
        DarkWordConfigManager.setModuleEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(isModuleEnabled = enabled)
    }

    fun setDarkWordDisabled(disabled: Boolean) {
        DarkWordConfigManager.setDarkWordDisabled(context, disabled)
        _uiState.value = _uiState.value.copy(isDarkWordDisabled = disabled)
    }
}