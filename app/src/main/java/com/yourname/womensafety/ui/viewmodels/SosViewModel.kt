package com.yourname.womensafety.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.IotSosTracker
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.data.repository.ProtectionRepository
import com.yourname.womensafety.data.repository.SosRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class SosUiState(
    val alertId: String? = null,
    /** True while the initial POST /sos/trigger is in-flight. Countdown waits until false. */
    val isTriggering: Boolean = false,
    val isSending: Boolean = false,
    val isSent: Boolean = false,
    val isCancelled: Boolean = false,
    /** True while the cancelSos / markUserSafe API call is in-flight. */
    val isCancelling: Boolean = false,
    val errorMessage: String? = null,
    /**
     * True when the trigger has been in-flight for more than [TRIGGER_TIMEOUT_MS].
     * In this state the UI should always unlock the "I'M SAFE" cancel button so
     * the user is never trapped on the SOS screen.
     */
    val isConnectionTimeout: Boolean = false
)

class SosViewModel(
    private val sosRepository: SosRepository,
    private val protectionRepository: ProtectionRepository = AppServiceLocator.protectionRepository
) : ViewModel() {

    companion object {
        /** After this many ms of isTriggering=true, unlock the cancel button */
        private const val TRIGGER_TIMEOUT_MS = 8_000L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SosViewModel(AppServiceLocator.sosRepository) as T
            }
        }
    }

    private val _uiState = MutableStateFlow(SosUiState())
    val uiState: StateFlow<SosUiState> = _uiState

    /** Reference to the in-flight triggerSos coroutine — used by abortTrigger(). */
    private var triggerJob: Job? = null

    /** Reference to the 8-second timeout watchdog coroutine. */
    private var timeoutJob: Job? = null

    fun triggerSos(triggerType: String = "manual") {
        Log.d("SosViewModel", "triggerSos called: type=$triggerType")
        // Cancel any previously in-flight trigger before starting a new one
        triggerJob?.cancel()
        timeoutJob?.cancel()

        _uiState.value = SosUiState(isTriggering = true)

        // 8-second watchdog — if the trigger hasn't resolved by then, unlock cancel
        timeoutJob = viewModelScope.launch {
            delay(TRIGGER_TIMEOUT_MS)
            if (_uiState.value.isTriggering) {
                Log.w("SosViewModel", "Trigger timeout — unlocking cancel button")
                _uiState.value = _uiState.value.copy(isConnectionTimeout = true)
            }
        }

        triggerJob = viewModelScope.launch {
            // Fetch location first — survives UI recomposition / navigation
            var lat = 0.0
            var lng = 0.0
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(AppServiceLocator.application)
                val cts = CancellationTokenSource()
                val location = suspendCancellableCoroutine { cont ->
                    fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                        .addOnSuccessListener { loc -> cont.resume(loc) }
                        .addOnFailureListener { cont.resume(null) }
                    cont.invokeOnCancellation { cts.cancel() }
                }
                lat = location?.latitude ?: 0.0
                lng = location?.longitude ?: 0.0
            } catch (e: Exception) {
                Log.w("SosViewModel", "Location fetch failed: ${e.message}")
            }

            Log.d("SosViewModel", "Location fetched: lat=$lat, lng=$lng")

            when (val result = sosRepository.triggerSos(triggerType, lat, lng)) {
                is NetworkResult.Success -> {
                    Log.d("SosViewModel", "triggerSos success: alertId=${result.data.alertId}")
                    timeoutJob?.cancel()
                    val serverStatus = result.data.status.lowercase()
                    val alreadySent = serverStatus == "sent" || serverStatus == "dispatched"
                    IotSosTracker.onUiAlertCreated(result.data.alertId)
                    if (alreadySent) IotSosTracker.onAlertDispatched(result.data.alertId)
                    _uiState.value = SosUiState(
                        alertId = result.data.alertId,
                        isTriggering = false,
                        isConnectionTimeout = false,
                        isSent = alreadySent
                    )
                }
                is NetworkResult.Error -> {
                    Log.e("SosViewModel", "triggerSos error: ${result.message}, code: ${result.code}")
                    timeoutJob?.cancel()

                    val userMessage = when (result.code) {
                        "NO_CONTACTS"    -> "⚠️ No verified contacts. Please add and verify a trusted contact first."
                        "INTERNAL_ERROR" -> "Server error — please try again."
                        "UNAUTHORIZED"   -> "Session expired. Please log in again."
                        "NETWORK_ERROR"  -> "No internet connection. Check your network and try again."
                        "TIMEOUT"        -> "Server is starting up. Wait a moment and tap Retry."
                        else             -> "SOS failed: ${result.message}"
                    }

                    _uiState.value = SosUiState(
                        isTriggering = false,
                        isConnectionTimeout = false,
                        errorMessage = userMessage
                    )
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Cancels the in-flight triggerSos request and resets state.
     * Called when the user taps Back or I'M SAFE before the trigger has resolved.
     */
    fun abortTrigger() {
        Log.d("SosViewModel", "abortTrigger called — cancelling in-flight trigger")
        triggerJob?.cancel()
        timeoutJob?.cancel()
        triggerJob = null
        timeoutJob = null
        // Mark as cancelled so the screen navigates away cleanly
        IotSosTracker.onAlertResolved()
        _uiState.value = SosUiState(isCancelled = true)
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
                            Log.d("SosViewModel", "sendNow: alert already cancelled — treating as cancel")
                            IotSosTracker.onAlertResolved()
                            _uiState.value = _uiState.value.copy(isSending = false, isCancelled = true)
                        }
                        "ALREADY_DISPATCHED", "ALREADY_SENT" -> {
                            Log.d("SosViewModel", "sendNow: alert already dispatched — marking sent")
                            IotSosTracker.onAlertDispatched(alertId)
                            _uiState.value = _uiState.value.copy(isSending = false, isSent = true)
                        }
                        else -> {
                            Log.e("SosViewModel", "sendNow error: [${result.code}] ${result.message}")
                            _uiState.value = _uiState.value.copy(
                                isSending = false,
                                errorMessage = "Failed to dispatch SOS — tap Retry"
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
        val alertId = _uiState.value.alertId

        // If trigger is still in-flight (no alertId yet), abort it
        if (alertId == null) {
            if (_uiState.value.isTriggering) {
                abortTrigger()
                return
            }
            // No alertId and not triggering — just mark cancelled locally
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
                            "Failed to cancel SOS — tap to retry"
                        }
                    )
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        triggerJob?.cancel()
        timeoutJob?.cancel()
    }
}
