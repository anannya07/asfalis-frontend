package com.yourname.womensafety.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.IotSosTracker
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.data.repository.ProtectionRepository
import com.yourname.womensafety.data.repository.SosRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SosUiState(
    val alertId: String? = null,
    val isSending: Boolean = false,
    val isSent: Boolean = false,
    val isCancelled: Boolean = false,
    /** True while the cancelSos / markUserSafe API call is in-flight. */
    val isCancelling: Boolean = false,
    val errorMessage: String? = null
)

class SosViewModel(
    private val sosRepository: SosRepository,
    private val protectionRepository: ProtectionRepository = AppServiceLocator.protectionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SosUiState())
    val uiState: StateFlow<SosUiState> = _uiState

    fun triggerSos(latitude: Double, longitude: Double, triggerType: String = "manual") {
        Log.d("SosViewModel", "triggerSos called: lat=$latitude, lng=$longitude, type=$triggerType")
        viewModelScope.launch {
            when (val result = sosRepository.triggerSos(triggerType, latitude, longitude)) {
                is NetworkResult.Success -> {
                    Log.d("SosViewModel", "triggerSos success: alertId=${result.data.alertId}")
                    val serverStatus = result.data.status.lowercase()
                    val alreadySent = serverStatus == "sent" || serverStatus == "dispatched"
                    IotSosTracker.onUiAlertCreated(result.data.alertId)
                    if (alreadySent) IotSosTracker.onAlertDispatched(result.data.alertId)
                    _uiState.value = SosUiState(
                        alertId = result.data.alertId,
                        isSent = alreadySent
                    )
                }
                is NetworkResult.Error -> {
                    Log.e("SosViewModel", "triggerSos error: ${result.message}, code: ${result.code}")
                    
                    // Map error codes to user-friendly messages
                    val userMessage = when (result.code) {
                        "NO_CONTACTS" -> "Please add and verify at least one trusted contact before triggering SOS"
                        "INTERNAL_ERROR" -> "Backend error occurred. Please check:\n• Do you have verified contacts?\n• Is the backend server running?\n• Contact your system administrator"
                        "UNAUTHORIZED" -> "Authentication failed. Please log in again."
                        "NETWORK_ERROR" -> "No internet connection. Please check your network."
                        "TIMEOUT" -> "Request timed out. Please try again."
                        else -> result.message
                    }
                    
                    _uiState.value = SosUiState(errorMessage = userMessage)
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun sendNow() {
        val alertId = _uiState.value.alertId ?: run {
            Log.e("SosViewModel", "sendNow called but alertId is null")
            return
        }
        Log.d("SosViewModel", "sendNow called with alertId=$alertId")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            when (val result = sosRepository.sendSosNow(alertId)) {
                is NetworkResult.Success -> {
                    Log.d("SosViewModel", "sendNow success")
                    IotSosTracker.onAlertDispatched(alertId)
                    _uiState.value = _uiState.value.copy(isSending = false, isSent = true)
                }
                is NetworkResult.Error -> {
                    when (result.code) {
                        "ALREADY_CANCELLED" -> {
                            // Backend (409) confirms alert was already cancelled by the wearable
                            // double-tap before the countdown timer fired. Treat as cancel success
                            // so the screen navigates away cleanly instead of showing an error.
                            Log.d("SosViewModel", "sendNow: alert already cancelled — treating as cancel")
                            IotSosTracker.onAlertResolved()
                            _uiState.value = _uiState.value.copy(isSending = false, isCancelled = true)
                        }
                        "ALREADY_DISPATCHED", "ALREADY_SENT" -> {
                            // Backend confirms the alert was already dispatched (e.g. by another
                            // code path). Set isSent = true so a subsequent "I'm Safe" tap
                            // correctly calls markUserSafe instead of cancelSos.
                            Log.d("SosViewModel", "sendNow: alert already dispatched — marking sent")
                            IotSosTracker.onAlertDispatched(alertId)
                            _uiState.value = _uiState.value.copy(isSending = false, isSent = true)
                        }
                        else -> {
                            Log.e("SosViewModel", "sendNow error: [${result.code}] ${result.message}")
                            _uiState.value = _uiState.value.copy(
                                isSending = false,
                                errorMessage = "Failed to dispatch SOS"
                            )
                        }
                    }
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Use this for Auto SOS alerts where the backend already created the alert
     * via POST /protection/predict. Skips the triggerSos() call.
     */
    fun initWithExistingAlert(alertId: String) {
        Log.d("SosViewModel", "initWithExistingAlert: alertId=$alertId")
        // Only update tracker when the alertId is new — avoids overwriting the
        // hardware-triggered flag that IotWearableManager already set.
        IotSosTracker.onUiAlertCreated(alertId)
        _uiState.value = SosUiState(alertId = alertId)
    }

    /**
     * Submit true/false alarm feedback after an Auto SOS alert resolves.
     * Must be called once per auto-triggered alert to re-label ML training data.
     */
    fun submitFeedback(alertId: String, isFalseAlarm: Boolean) {
        Log.d("SosViewModel", "submitFeedback: alertId=$alertId, isFalseAlarm=$isFalseAlarm")
        viewModelScope.launch {
            protectionRepository.submitFeedback(alertId, isFalseAlarm)
            // Feedback is best-effort — we don't surface errors to the user
        }
    }

    fun cancelSos() {
        val alertId = _uiState.value.alertId ?: run {
            // No alertId means SOS wasn't triggered yet — just mark cancelled locally
            IotSosTracker.onAlertResolved()
            _uiState.value = SosUiState(isCancelled = true)
            return
        }
        // Guard: don't fire a second request if one is already in-flight.
        if (_uiState.value.isCancelling) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCancelling = true, errorMessage = null)
            val wasSent = _uiState.value.isSent

            // If not sent yet, cancel pending alert (false alarm without notifying contacts).
            // If already sent, mark user safe (notify contacts that user is safe).
            val result = if (wasSent) {
                sosRepository.markUserSafe(alertId)
            } else {
                sosRepository.cancelSos(alertId)
            }

            when (result) {
                is NetworkResult.Success -> {
                    IotSosTracker.onAlertResolved()
                    _uiState.value = _uiState.value.copy(isCancelling = false, isCancelled = true)
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isCancelling = false,
                        errorMessage = if (wasSent) {
                            "Failed to notify contacts you're safe"
                        } else {
                            "Failed to cancel SOS"
                        }
                    )
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SosViewModel(AppServiceLocator.sosRepository) as T
            }
        }
    }
}
