package com.yourname.womensafety.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.network.dto.UpdateSettingsRequest
import com.yourname.womensafety.data.network.dto.ToggleProtectionRequest
import com.yourname.womensafety.data.network.RetrofitClient
import com.yourname.womensafety.data.network.api.ProtectionApiService
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.data.repository.SettingsRepository
import com.yourname.womensafety.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessaging

class DashboardViewModel : ViewModel() {

    private val protectionApi: ProtectionApiService by lazy {
        RetrofitClient.createService(AppServiceLocator.tokenManager)
    }

    private val userRepository: UserRepository = AppServiceLocator.userRepository
    private val settingsRepository: SettingsRepository = AppServiceLocator.settingsRepository

    private val _isProtectionActive = MutableStateFlow(false)
    val isProtectionActive: StateFlow<Boolean> = _isProtectionActive

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName

    /** "low" | "medium" | "high" — read from settings, passed to AutoSosManager. */
    private val _shakeSensitivity = MutableStateFlow("medium")
    val shakeSensitivity: StateFlow<String> = _shakeSensitivity

    /** True when protection is armed — sensors run whenever the shield is armed. */
    private val _autoSosMonitoring = MutableStateFlow(false)
    val autoSosMonitoring: StateFlow<Boolean> = _autoSosMonitoring

    init {
        // Sync FCM token on app launch as per POSTMAN_GUIDE Pro Tips
        // Wrapped in try-catch to guarantee Dashboard rendering even if Firebase is disabled
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrEmpty()) {
                        viewModelScope.launch {
                            try {
                                userRepository.updateFcmToken(token)
                            } catch (e: Exception) { /* ignore network error */ }
                        }
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // FirebaseApp is not initialized (e.g. missing google-services.json)
        } catch (e: Exception) {
            // Failsafe catch-all
        }
    }

    fun loadProtectionStatus() {
        viewModelScope.launch {
            try {
                val response = protectionApi.getProtectionStatus()
                if (response.isSuccessful && response.body()?.success == true) {
                    val active = response.body()?.data?.isActive ?: _isProtectionActive.value
                    _isProtectionActive.value = active
                    // Explicitly sync autoSosMonitoring with the loaded state
                    _autoSosMonitoring.value = active
                }
            } catch (e: Exception) {
                // Ignore — use local state
            }
        }
        // Load shake_sensitivity for AutoSosManager threshold
        viewModelScope.launch {
            when (val result = settingsRepository.getSettings()) {
                is NetworkResult.Success -> _shakeSensitivity.value = result.data.shakeSensitivity
                else -> Unit
            }
        }
    }

    fun toggleProtection(isActive: Boolean) {
        viewModelScope.launch {
            try {
                val response = protectionApi.toggleProtection(ToggleProtectionRequest(isActive))
                if (response.isSuccessful && response.body()?.success == true) {
                    val confirmedActive = response.body()?.data?.isActive ?: isActive
                    _isProtectionActive.value = confirmedActive
                    // Only update autoSosMonitoring here, not via a collect{} observer,
                    // to prevent the shield from flickering on cold start.
                    _autoSosMonitoring.value = confirmedActive
                } else {
                    // Optimistic update on API failure
                    _isProtectionActive.value = isActive
                    _autoSosMonitoring.value = isActive
                }
            } catch (e: Exception) {
                _isProtectionActive.value = isActive
                _autoSosMonitoring.value = isActive
            }
            // Sync auto_sos_enabled to backend so POST /predict requests are accepted.
            try {
                settingsRepository.updateSettings(
                    UpdateSettingsRequest(autoSosEnabled = _isProtectionActive.value)
                )
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun loadGreeting() {
        viewModelScope.launch {
            when (val result = userRepository.getProfile()) {
                is NetworkResult.Success -> {
                    _userName.value = result.data.fullName.split(" ").firstOrNull()
                }
                else -> Unit
            }
        }
    }

    fun clearError() { _errorMessage.value = null }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel() as T
            }
        }
    }
}
