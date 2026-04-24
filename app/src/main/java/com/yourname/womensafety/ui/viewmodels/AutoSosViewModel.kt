package com.yourname.womensafety.ui.viewmodels

import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.AutoSosManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AutoSosViewModel — owns [AutoSosManager] and orchestrates the Auto SOS pipeline.
 *
 * Usage from DashboardScreen:
 *  1. Call [setActive] whenever the protection toggle changes.
 *  2. Collect [dangerDetected] to navigate to the SOS countdown screen.
 *  3. Call [onAlertResolved] once the SOS alert has been cancelled or sent to
 *     reset the cooldown so monitoring can resume.
 */
class AutoSosViewModel(app: Application) : AndroidViewModel(app) {

    private val sensorManager =
        app.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val autoSosManager = AutoSosManager(
        context              = app.applicationContext,
        sensorManager        = sensorManager,
        protectionRepository = AppServiceLocator.protectionRepository,
        sosRepository        = AppServiceLocator.sosRepository,
        scope                = viewModelScope
    )

    /** True while sensor monitoring is active. */
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    // Track current params to detect restarts needed by sensitivity changes
    private var currentSensitivity = ""
    private var currentSensorType = ""

    /**
     * Emits a [AutoSosManager.DangerEvent] whenever the ML model predicts danger.
     * Collect this in the UI to navigate to the SOS countdown screen.
     */
    private val _dangerDetected = MutableSharedFlow<AutoSosManager.DangerEvent>(extraBufferCapacity = 1)
    val dangerDetected: SharedFlow<AutoSosManager.DangerEvent> = _dangerDetected

    /**
     * Emits Unit when the 10-minute post-trigger cooldown begins.
     * Collect this in the UI to show a Toast informing the user.
     */
    private val _cooldownStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cooldownStarted: SharedFlow<Unit> = _cooldownStarted

    init {
        // Forward events from the manager to the ViewModel's own SharedFlows
        viewModelScope.launch {
            autoSosManager.dangerDetected.collect { event ->
                _dangerDetected.emit(event)
            }
        }
        viewModelScope.launch {
            autoSosManager.cooldownStarted.collect {
                _cooldownStarted.emit(Unit)
            }
        }
    }

    /**
     * Enable or disable sensor monitoring.
     * Always restarts if sensitivity or sensorType changes while active.
     *
     * @param active     Whether to start or stop monitoring.
     * @param sensitivity "low" | "medium" | "high" — determines magnitude threshold.
     * @param sensorType  "accelerometer" | "gyroscope"
     */
    fun setActive(active: Boolean, sensitivity: String = "medium", sensorType: String = "accelerometer") {
        val paramsChanged = sensitivity != currentSensitivity || sensorType != currentSensorType
        if (_isActive.value == active && !paramsChanged) return

        currentSensitivity = sensitivity
        currentSensorType = sensorType
        _isActive.value = active

        // Always stop first to cleanly unregister the previous sensor listener
        autoSosManager.stop()
        if (active) {
            autoSosManager.start(sensitivity, sensorType)
        }
    }

    /**
     * Call this after the SOS alert triggered by Auto SOS has been resolved
     * (cancelled or dispatched). Resets the internal 20-second cooldown so
     * monitoring can continue normally.
     */
    fun onAlertResolved() {
        autoSosManager.notifySosResolved()
    }

    override fun onCleared() {
        super.onCleared()
        autoSosManager.stop()
    }

    companion object {
        /**
         * Correct factory for AndroidViewModel subclasses.
         * Uses [ViewModelProvider.AndroidViewModelFactory] which passes the Application
         * automatically — resolving the crash caused by a missing no-arg constructor.
         */
        fun factory(app: Application): ViewModelProvider.Factory =
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    }
}
