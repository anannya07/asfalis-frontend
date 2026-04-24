package com.yourname.womensafety.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.network.dto.UpdateSettingsRequest
import com.yourname.womensafety.data.network.dto.UserSettings
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _settings = MutableStateFlow<UserSettings?>(null)
    val settings: StateFlow<UserSettings?> = _settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /**
     * One-shot event emitted on successful save. Using SharedFlow instead of StateFlow
     * prevents the screen from popping immediately on re-entry (stale true value bug).
     */
    private val _saveSuccess = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveSuccess: SharedFlow<Unit> = _saveSuccess.asSharedFlow()

    /** Reflects the persisted auto_sos_enabled value from the backend. */
    private val _autoSosEnabled = MutableStateFlow(false)
    val autoSosEnabled: StateFlow<Boolean> = _autoSosEnabled

    fun loadSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = settingsRepository.getSettings()) {
                is NetworkResult.Success -> {
                    _settings.value = result.data
                    _autoSosEnabled.value = result.data.autoSosEnabled
                    _isLoading.value = false
                }
                is NetworkResult.Error -> {
                    _errorMessage.value = result.message
                    _isLoading.value = false
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun saveSettings(request: UpdateSettingsRequest) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            when (val result = settingsRepository.updateSettings(request)) {
                is NetworkResult.Success -> {
                    _settings.value = result.data
                    _autoSosEnabled.value = result.data.autoSosEnabled
                    _isLoading.value = false
                    // One-shot event — the screen collects this via SharedFlow.collect{}
                    // so it fires exactly once and does NOT re-fire on screen re-entry.
                    _saveSuccess.tryEmit(Unit)
                }
                is NetworkResult.Error -> {
                    _errorMessage.value = result.message
                    _isLoading.value = false
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearError() { _errorMessage.value = null }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(AppServiceLocator.settingsRepository) as T
            }
        }
    }
}
