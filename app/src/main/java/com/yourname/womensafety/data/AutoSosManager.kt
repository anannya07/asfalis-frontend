package com.yourname.womensafety.data

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.data.repository.ProtectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * AutoSosManager — Stage 1 + Stage 2 of the Auto SOS pipeline.
 *
 * Stage 1 (on-device): reads accelerometer/gyroscope, checks magnitude against the
 *   user-configured threshold. Only readings that exceed the threshold trigger Stage 2.
 *
 * Stage 2 (backend): snapshots the rolling buffer and POSTs it to POST /api/protection/predict.
 *   If the backend returns prediction=1 (danger), emits the alert_id via [dangerDetected].
 *
 * Sensitivity → magnitude threshold mapping (m/s²):
 *   high   → 12.0
 *   medium → 18.0
 *   low    → 25.0
 */
class AutoSosManager(
    private val context: Context,
    private val sensorManager: SensorManager,
    private val protectionRepository: ProtectionRepository,
    private val scope: CoroutineScope
) {

    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(context)
    companion object {
        private const val TAG = "AutoSosManager"
        private const val WINDOW_SIZE = 40          // recommended reading count per window
        private const val COOLDOWN_MS = 600_000L    // 10-minute cooldown after any Auto SOS trigger
    }

    // Emits the alert_id whenever the backend declares danger (prediction = 1, sos_sent = true)
    private val _dangerDetected = MutableSharedFlow<DangerEvent>(extraBufferCapacity = 1)
    val dangerDetected: SharedFlow<DangerEvent> = _dangerDetected

    // Emits Unit when the 10-minute cooldown begins (danger confirmed by backend)
    private val _cooldownStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cooldownStarted: SharedFlow<Unit> = _cooldownStarted

    data class DangerEvent(
        val alertId: String,
        /** "auto_fall" for accelerometer, "auto_shake" for gyroscope */
        val triggerType: String
    )

    private var magnitudeThreshold = 18f // default: medium
    private var activeSensorType = "accelerometer"

    // Rolling window — protected only by single-thread sensor callback
    private val rollingBuffer = ArrayDeque<List<Float>>(WINDOW_SIZE + 1)

    private var isCooldownActive = false
    private var isWindowBeingSent = false
    private var cooldownJob: Job? = null

    /**
     * True only while [start] has been called and [stop] has not.
     * Checked inside [sendWindowToBackend] to prevent a late-arriving coroutine
     * from sending sensor data after the user disarms the system.
     * Declared @Volatile so writes from any thread are immediately visible.
     */
    @Volatile private var isArmed = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // --- DATA COLLECTION START ---
            val gravity = 9.80665f
            // Set label to 0 for SAFE data, 1 for DANGER data
            Log.d("REAL_DATA_CSV", "${x/gravity},${y/gravity},${z/gravity},accelerometer,0,${System.currentTimeMillis()}")
            // --- DATA COLLECTION END ---

            // Add to rolling buffer
            if (rollingBuffer.size >= WINDOW_SIZE) rollingBuffer.removeFirst()
            rollingBuffer.addLast(listOf(x, y, z))

            // Stage 1: magnitude threshold guard
            val magnitude = sqrt(x * x + y * y + z * z)
            if (magnitude > magnitudeThreshold) {
                Log.d(TAG, "Threshold exceeded: magnitude=%.2f > threshold=%.2f (%s) " .format(
                    magnitude, magnitudeThreshold, activeSensorType
                ) + "isArmed=$isArmed isCooldown=$isCooldownActive isSending=$isWindowBeingSent " +
                    "bufferSize=${rollingBuffer.size}")
            }
            if (magnitude > magnitudeThreshold && !isCooldownActive && !isWindowBeingSent && isArmed) {
                if (rollingBuffer.size >= 3) {
                    Log.w(TAG, "Stage 1 passed — sending ${rollingBuffer.size} readings to /predict")
                    sendWindowToBackend(rollingBuffer.toList())
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Start monitoring sensors.
     *
     * @param sensitivity "low" | "medium" | "high"
     * @param sensorType  "accelerometer" | "gyroscope"
     */
    fun start(sensitivity: String = "medium", sensorType: String = "accelerometer") {
        isArmed = true
        activeSensorType = sensorType
        magnitudeThreshold = when (sensitivity.lowercase()) {
            "high" -> 12f
            "low"  -> 25f
            else   -> 18f
        }

        val sensorKind = if (sensorType == "gyroscope")
            Sensor.TYPE_GYROSCOPE
        else
            Sensor.TYPE_ACCELEROMETER

        val sensor = sensorManager.getDefaultSensor(sensorKind)
        if (sensor == null) {
            Log.w(TAG, "Sensor not available on this device: $sensorType")
            return
        }
        sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        Log.d(TAG, "Started monitoring ($sensorType, sensitivity=$sensitivity, threshold=$magnitudeThreshold)")
    }

    /** Stop monitoring and clear state. */
    fun stop() {
        isArmed = false          // disarm first so any in-flight coroutine bails early
        sensorManager.unregisterListener(sensorListener)
        rollingBuffer.clear()
        isCooldownActive = false
        isWindowBeingSent = false
        cooldownJob?.cancel()
        Log.d(TAG, "Stopped monitoring")
    }

    /** Called externally after an SOS alert is resolved to reset the cooldown. */
    fun notifySosResolved() {
        startCooldown()
    }

    // --- Internal ---

    private data class LocationData(
        val address: String?,
        val latitude: Double?,
        val longitude: Double?
    )

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(): LocationData {
        return try {
            val cts = CancellationTokenSource()
            val location = suspendCancellableCoroutine { cont ->
                fusedLocationClient
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            } ?: fusedLocationClient.lastLocation.let { task ->
                // Fall back to lastLocation
                suspendCancellableCoroutine { cont ->
                    task.addOnSuccessListener { loc -> cont.resume(loc) }
                        .addOnFailureListener { cont.resume(null) }
                }
            }

            if (location == null) return LocationData(null, null, null)
            val lat = location.latitude
            val lng = location.longitude

            // Try to get a human-readable address via Geocoder
            val address = if (Geocoder.isPresent()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        suspendCancellableCoroutine { cont ->
                            Geocoder(context).getFromLocation(lat, lng, 1) { addresses ->
                                val a = addresses.firstOrNull()
                                cont.resume(
                                    if (a != null)
                                        listOfNotNull(
                                            a.thoroughfare,
                                            a.locality,
                                            a.adminArea
                                        ).joinToString(", ").ifEmpty { null }
                                    else null
                                )
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = Geocoder(context).getFromLocation(lat, lng, 1)
                        val a = addresses?.firstOrNull()
                        if (a != null)
                            listOfNotNull(
                                a.thoroughfare,
                                a.locality,
                                a.adminArea
                            ).joinToString(", ").ifEmpty { null }
                        else null
                    }
                } catch (e: Exception) { null }
            } else null

            LocationData(address, lat, lng)
        } catch (e: Exception) {
            Log.w(TAG, "Could not get location: ${e.message}")
            LocationData(null, null, null)
        }
    }

    private fun sendWindowToBackend(snapshot: List<List<Float>>) {
        isWindowBeingSent = true
        scope.launch {
            // Guard: the system may have been disarmed between the time the sensor
            // callback fired and this coroutine started.  Bail out immediately.
            if (!isArmed) {
                Log.w(TAG, "sendWindowToBackend: system DISARMED — aborting (snapshot discarded)")
                isWindowBeingSent = false
                return@launch
            }
            Log.d(TAG, "Sending window (${snapshot.size} readings) to /predict — " +
                    "sensorType=$activeSensorType threshold=$magnitudeThreshold m/s²")
            try {
                val loc = getCurrentLocation()
                Log.d(TAG, "Location: address=${loc.address} lat=${loc.latitude} lng=${loc.longitude}")
                when (val result = protectionRepository.predict(
                    window = snapshot,
                    sensorType = activeSensorType,
                    location = loc.address,
                    latitude = loc.latitude,
                    longitude = loc.longitude
                )) {
                    is NetworkResult.Success -> {
                        val pred = result.data
                        Log.d(TAG, "Prediction: prediction=${pred.prediction} confidence=${pred.confidence} " +
                                "sos_sent=${pred.sosSent} alertId=${pred.alertId} " +
                                "message='${pred.message}' isArmed=$isArmed")
                        if (pred.sosSent && pred.alertId != null) {
                            if (!isArmed) {
                                // System was disarmed while the network call was in-flight.
                                // The backend already sent the SOS — log prominently for debugging.
                                Log.e(TAG, "BUG: backend returned sosSent=true but system is DISARMED. " +
                                        "alertId=${pred.alertId} triggerType=$activeSensorType " +
                                        "— suppressing UI navigation to avoid phantom SOS.")
                            } else {
                                // Backend confirmed danger — start 10-min cooldown and emit events
                                startCooldown()
                                _cooldownStarted.emit(Unit)
                                val triggerType = if (activeSensorType == "gyroscope") "auto_shake" else "auto_fall"
                                Log.w(TAG, "Danger confirmed: alertId=${pred.alertId} triggerType=$triggerType")
                                _dangerDetected.emit(DangerEvent(pred.alertId, triggerType))
                            }
                        } else if (pred.retryAfterSeconds != null && pred.retryAfterSeconds > 0) {
                            // Rate-limited by backend — sync local cooldown to backend's remaining window
                            val remainingMs = pred.retryAfterSeconds * 1_000L
                            Log.d(TAG, "Rate-limited by backend — cooldown ${pred.retryAfterSeconds}s: ${pred.message}")
                            startCooldown(remainingMs)
                        } else if (pred.message?.contains("cooldown", ignoreCase = true) == true ||
                                   pred.message?.contains("rate", ignoreCase = true) == true) {
                            // Fallback: legacy cooldown message without retry_after_seconds
                            startCooldown()
                        }
                    }
                    is NetworkResult.Error -> {
                        Log.e(TAG, "Predict failed: ${result.code} — ${result.message}")
                    }
                    is NetworkResult.Loading -> Unit
                }
            } finally {
                isWindowBeingSent = false
                // Clear buffer after sending so we don't re-send the same readings
                rollingBuffer.clear()
            }
        }
    }

    private fun startCooldown(durationMs: Long = COOLDOWN_MS) {
        isCooldownActive = true
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            delay(durationMs)
            isCooldownActive = false
            Log.d(TAG, "Cooldown expired — resuming monitoring")
        }
    }
}
