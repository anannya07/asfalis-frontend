package com.yourname.womensafety.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.data.repository.SosRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.pow

/**
 * Manages the Classic Bluetooth SPP connection to the ESP32 wearable.
 *
 * ### Press logic (all owned by the app — no backend button-event endpoint)
 *
 * | Condition | Action |
 * |---|---|
 * | Single press (gap ≥ 1.5 s from last) | Trigger SOS like the manual button |
 * | Single press, but hardware 10-min cooldown active | Ignore |
 * | Double press (gap < 1.5 s), SOS in countdown or dispatched < 60 s ago | Cancel / "I'm Safe" |
 * | Double press, no active SOS | Ignore silently |
 *
 * After a hardware-triggered SOS is dispatched a 10-minute cooldown prevents
 * new hardware triggers.  Manual / auto-SOS are unaffected.
 */
class IotWearableManager(
    private val context: Context,
    private val sosRepository: SosRepository
) {
    companion object {
        private const val TAG                  = "IotWearableManager"
        private const val SPP_UUID             = "00001101-0000-1000-8000-00805F9B34FB"

        /** Frame the ESP32 sends on every microswitch press. */
        private const val TRIGGER_FRAME        = "SOS_TRIGGER_RANDOM_MESSAGE"

        /** Two presses within this window = double-tap. */
        private const val DOUBLE_TAP_WINDOW_MS = 1_500L

        // ── Proximity monitoring ───────────────────────────────────────────────
        /** Distance (metres) above which the wearable is considered "too far away". */
        const val PROXIMITY_THRESHOLD_M      = 5f
        /**
         * Total time between the start of consecutive RSSI polls.
         * Wider window = fewer false triggers when the device briefly goes out of range.
         */
        private const val PROXIMITY_POLL_INTERVAL_MS  = 10_000L
        /** BLE scan window per poll — long enough to collect several advertisement packets. */
        private const val PROXIMITY_SCAN_WINDOW_MS    = 3_000L
        /**
         * Consecutive out-of-range readings required before SOS fires (~50 s total).
         * Higher value = more resistant to transient RSSI spikes.
         */
        const val PROXIMITY_BREACH_COUNT     = 5
        /** Stop collecting RSSI samples early once we have this many per window. */
        private const val PROXIMITY_MAX_SAMPLES = 5
        /** Skip a poll entirely if fewer than this many samples were received. */
        private const val PROXIMITY_MIN_SAMPLES = 2
        /** BLE advertisement name the ESP32 broadcasts (must match firmware). */
        private const val BLE_DEVICE_NAME    = "ESP32_SOS_DEVICE"
        /**
         * If the median RSSI is at or above this value the device is definitely within
         * ~3 m regardless of what the formula computes. Transient multipath reflections
         * can briefly weaken the signal even at close range, but the median across
         * [PROXIMITY_MAX_SAMPLES] readings filters those out.
         * −60 dBm ≈ 2 m for a typical ESP32 at 0 dBm TX power.
         */
        private const val PROXIMITY_CLOSE_RSSI_THRESHOLD = -60
        /**
         * RSSI at exactly 1 m.  Calibrate by holding the phone 1 m from the ESP32,
         * logging 20 readings and averaging them.  −59 dBm is a typical ESP32 value.
         */
        private const val TX_POWER_AT_1M     = -59
        /** Path-loss exponent: 2.0 open space, 3.0 indoors, 2.5 mixed (default). */
        private const val PATH_LOSS_N        = 2.5f
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null

    /** Epoch-millis timestamp of the last received press (for double-tap detection). */
    @Volatile private var lastPressTime = 0L

    /**
     * Tracks the coroutine that is waiting to execute a single-tap SOS trigger.
     * Cancelled immediately if a second press arrives within [DOUBLE_TAP_WINDOW_MS],
     * which reclassifies the pair as a double-tap (cancel intent).
     */
    @Volatile private var pendingSingleTapJob: Job? = null

    /**
     * True after the first successful [s.connect()]. Used to fire [IotAction.ConnectionFailed]
     * only on the initial attempt, not on every subsequent retry after a drop.
     */
    @Volatile private var hasEverConnected = false

    /**
     * True while an SPP socket is confirmed open.  Set to false (before [readerJob] is
     * cancelled) by [stopListening] so that the coroutine's finally block does not post
     * [IotAction.Disconnected] for an intentional stop from the UI.
     */
    @Volatile private var isCurrentlyConnected = false

    /** Coroutine that polls BLE RSSI to estimate distance while the SPP socket is open. */
    @Volatile private var proximityJob: Job? = null

    /** Number of consecutive polls where estimated distance exceeded [PROXIMITY_THRESHOLD_M]. */
    @Volatile private var consecutiveBreachCount = 0

    // ------------------------------------------------------------------ //
    // Connect & listen                                                    //
    // ------------------------------------------------------------------ //

    @SuppressLint("MissingPermission")
    fun startListening(scope: CoroutineScope, savedMac: String) {
        readerJob = scope.launch(Dispatchers.IO) {
            // Outer retry loop — reconnects automatically on any socket failure or drop.
            while (isActive) {
                var localSocket: BluetoothSocket? = null
                try {
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: run {
                        Log.e(TAG, "Classic Bluetooth not available on this device")
                        return@launch
                    }
                    val device = adapter.getRemoteDevice(savedMac)

                    // cancelDiscovery() speeds up connect() significantly but requires
                    // BLUETOOTH_SCAN on Android 12+. Wrap it so a missing permission
                    // doesn't abort the connection entirely.
                    try { adapter.cancelDiscovery() } catch (e: Exception) {
                        Log.w(TAG, "cancelDiscovery skipped: ${e.message}")
                    }

                    // ── Socket creation strategy ─────────────────────────────────────────
                    // ORDERING RATIONALE:
                    // "Serial Bluetooth Terminal" (the gold standard app that works with ESP32)
                    // uses createInsecureRfcommSocketToServiceRecord(UUID) — plain SDP, no auth.
                    // Previous code tried reflection methods FIRST; those fail fast against ESP32
                    // and the rapid consecutive failures can put ESP32's RFCOMM acceptor in a
                    // temporary cooldown so that Attempt 3 (SDP) also fails even though it is
                    // the correct method.  Fix: SDP insecure goes FIRST, reflection is fallback.
                    //
                    //   Attempt 1: createInsecureRfcommSocketToServiceRecord(UUID)  ← SBT method
                    //              ↳ SDP lookup, no auth, no pairing dialog.
                    //   Attempt 2: createInsecureRfcommSocket(1) via reflection
                    //              ↳ Direct channel 1, no SDP, no auth.
                    //                Useful when the BT stack responds slowly to SDP queries.
                    //   Attempt 3: createRfcommSocket(1) via reflection
                    //              ↳ Direct channel 1, WITH authentication.
                    //   Attempt 4: createRfcommSocketToServiceRecord(UUID)
                    //              ↳ SDP + auth — last resort, may show pairing dialog.
                    //
                    // isActive is checked between every attempt so a cancelled coroutine does
                    // not make redundant blocking connect() calls after the service is stopped.
                    var connected = false

                    // Attempt 1 — insecure SDP (the exact method used by Serial Bluetooth Terminal)
                    try {
                        Log.d(TAG, "Attempt 1: createInsecureRfcommSocketToServiceRecord (insecure SDP)")
                        val s1 = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
                        localSocket = s1; socket = s1
                        s1.connect()
                        connected = true
                        Log.d(TAG, "Attempt 1 succeeded (insecure SDP)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Attempt 1 failed: ${e.javaClass.simpleName}: ${e.message}")
                        runCatching { localSocket?.close() }; localSocket = null; socket = null
                    }

                    // Attempt 2 — insecure reflection, channel 1
                    if (!connected && isActive) {
                        try {
                            Log.d(TAG, "Attempt 2: createInsecureRfcommSocket(1) via reflection")
                            @Suppress("UNCHECKED_CAST")
                            val s2 = device.javaClass
                                .getMethod("createInsecureRfcommSocket", Int::class.java)
                                .invoke(device, 1) as BluetoothSocket
                            localSocket = s2; socket = s2
                            s2.connect()
                            connected = true
                            Log.d(TAG, "Attempt 2 succeeded (insecure reflection channel 1)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Attempt 2 failed: ${e.javaClass.simpleName}: ${e.message}")
                            runCatching { localSocket?.close() }; localSocket = null; socket = null
                        }
                    }

                    // Attempt 3 — secure reflection, channel 1
                    if (!connected && isActive) {
                        try {
                            Log.d(TAG, "Attempt 3: createRfcommSocket(1) via reflection")
                            @Suppress("UNCHECKED_CAST")
                            val s3 = device.javaClass
                                .getMethod("createRfcommSocket", Int::class.java)
                                .invoke(device, 1) as BluetoothSocket
                            localSocket = s3; socket = s3
                            s3.connect()
                            connected = true
                            Log.d(TAG, "Attempt 3 succeeded (secure reflection channel 1)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Attempt 3 failed: ${e.javaClass.simpleName}: ${e.message}")
                            runCatching { localSocket?.close() }; localSocket = null; socket = null
                        }
                    }

                    // Attempt 4 — secure SDP (last resort, may show pairing dialog)
                    if (!connected && isActive) {
                        Log.d(TAG, "Attempt 4: createRfcommSocketToServiceRecord (secure SDP)")
                        val s4 = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
                        localSocket = s4; socket = s4
                        s4.connect()   // throws on failure → outer catch handles it
                        connected = true
                        Log.d(TAG, "Attempt 4 succeeded (secure SDP)")
                    }

                    // Socket confirmed open — post Connected on every reconnect so the UI
                    // always reflects the true connection state (not just the first time).
                    hasEverConnected = true
                    isCurrentlyConnected = true
                    IotEventBus.post(IotAction.Connected)
                    Log.d(TAG, "SPP connected to $savedMac")

                    // Start RSSI proximity monitoring for this connection.
                    // Cancelled in the finally block when the socket drops.
                    proximityJob?.cancel()
                    consecutiveBreachCount = 0
                    proximityJob = scope.launch(Dispatchers.IO) { monitorProximity() }

                    val reader = BufferedReader(InputStreamReader(localSocket!!.inputStream))
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.trim() == TRIGGER_FRAME) {
                            Log.d(TAG, "ESP32 button press received")
                            handleButtonPress(scope, savedMac)
                        }
                    }
                    Log.d(TAG, "BT stream ended — will reconnect")
                } catch (e: SecurityException) {
                    // BLUETOOTH_CONNECT was not granted at runtime (Android 12+).
                    // Retrying won't help — bail out permanently.
                    Log.e(TAG, "SecurityException: BLUETOOTH_CONNECT not granted — stopping", e)
                    IotEventBus.post(
                        IotAction.ConnectionFailed(
                            "Bluetooth permission denied. Go to App Settings and grant \"Nearby devices\" permission, then reconnect."
                        )
                    )
                    return@launch   // exit the coroutine entirely — no retry
                } catch (e: java.io.IOException) {
                    // Classify common IOException messages into user-readable hints.
                    val msg = e.message?.lowercase() ?: ""
                    val userMessage = when {
                        // ECONNREFUSED — ESP32's SPP port is occupied by another app
                        msg.contains("connection refused") || msg.contains("econnrefused") ||
                        msg.contains("host is down") ->
                            "ESP32 is busy — another app (e.g. Serial Bluetooth Terminal) " +
                            "is still connected. Close it completely and try again."
                        // SDP lookup failed — reflection worked but something else is wrong
                        msg.contains("service discovery failed") ||
                        msg.contains("unable to start") ->
                            "Could not find the SPP service on ESP32. " +
                            "Make sure the firmware calls BluetoothSerial.begin() before advertising."
                        // read failed — happens when the remote device closes the socket
                        msg.contains("read failed") || msg.contains("broken pipe") ->
                            "Wearable disconnected unexpectedly — will retry…"
                        // Bluetooth was disabled mid-connect
                        msg.contains("bluetooth is not enabled") ||
                        msg.contains("bt hardware is not available") ->
                            "Bluetooth was turned off during connection."
                        else ->
                            "Connection error: ${e.message}. Make sure the device is paired and in range."
                    }
                    Log.e(TAG, "IOException (${e.message}) — $userMessage")
                    // Only post ConnectionFailed for errors that the user must act on.
                    // Transient drops (read failed) silently retry.
                    val isFatal = !msg.contains("read failed") && !msg.contains("broken pipe")
                    if (isFatal && !hasEverConnected) {
                        IotEventBus.post(IotAction.ConnectionFailed(userMessage))
                    } else if (isFatal && hasEverConnected) {
                        // Show the hint as an error but keep retrying (e.g. busy port)
                        IotEventBus.post(IotAction.ConnectionFailed(userMessage))
                    }
                } catch (e: Exception) {
                    // Never swallow CancellationException — it means the coroutine was
                    // intentionally cancelled (service stopped). Re-throwing lets the
                    // while(isActive) loop exit cleanly without posting spurious events.
                    if (e is CancellationException) throw e
                    Log.e(TAG, "BT error: ${e.javaClass.simpleName}: ${e.message} — retrying in 5 s")
                    if (!hasEverConnected) {
                        IotEventBus.post(
                            IotAction.ConnectionFailed(
                                "Could not connect to ESP32. Make sure it is powered on, paired in Android Bluetooth settings, and no other app is using it."
                            )
                        )
                    }
                } finally {
                    // Stop proximity monitoring for this connection cycle.
                    proximityJob?.cancel()
                    proximityJob = null
                    consecutiveBreachCount = 0
                    // If we were connected when this finally block runs, the socket dropped
                    // unexpectedly (device off, out of range, BT disruption).  Signal the UI
                    // unless stopListening() already cleared the flag for an intentional stop.
                    if (isCurrentlyConnected) {
                        isCurrentlyConnected = false
                        IotEventBus.post(IotAction.Disconnected)
                    }
                    runCatching { localSocket?.close() }
                    socket = null
                }
                if (isActive) delay(5_000L)   // wait before next reconnect attempt
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Double-tap detection + action dispatch                             //
    // ------------------------------------------------------------------ //

    private fun handleButtonPress(scope: CoroutineScope, mac: String) {
        val now         = System.currentTimeMillis()
        val isDoubleTap = (now - lastPressTime) < DOUBLE_TAP_WINDOW_MS
        lastPressTime   = now

        if (isDoubleTap) {
            // Cancel any in-flight single-tap coroutine before handling the double-tap.
            // This is the key fix: if the user presses once (triggering SOS) and then
            // double-taps to cancel within the countdown, the first press of the
            // cancel double-tap was queued as a single-tap.  Cancelling it here
            // ensures no spurious second SOS is created.
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
            scope.launch { handleDoubleTap() }
        } else {
            // Delay execution by the full double-tap window before committing to a
            // single-tap SOS trigger.  A subsequent second press arriving within that
            // window cancels this job (above), reclassifying the pair as a double-tap.
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = scope.launch {
                delay(DOUBLE_TAP_WINDOW_MS)
                if (isActive) {
                    val location = getLastKnownLocation()
                    handleSingleTap(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
                }
            }
        }
    }

    // ── Single tap → trigger SOS ──────────────────────────────────────

    private suspend fun handleSingleTap(lat: Double, lng: Double) {
        if (IotSosTracker.isInHardwareCooldown()) {
            Log.d(TAG, "Single tap ignored — 10-min hardware cooldown is active")
            return
        }

        Log.d(TAG, "Single tap → triggering SOS (lat=$lat, lng=$lng)")
        when (val result = sosRepository.triggerSos("iot_button", lat, lng)) {
            is NetworkResult.Success -> {
                val alertId = result.data.alertId
                Log.d(TAG, "Hardware SOS triggered — alertId=$alertId")
                IotSosTracker.onHardwareAlertCreated(alertId)
                IotEventBus.post(IotAction.Triggered(alertId))
            }
            is NetworkResult.Error -> {
                Log.e(TAG, "SOS trigger failed: [${result.code}] ${result.message}")
            }
            is NetworkResult.Loading -> Unit
        }
    }

    // ── Double tap → cancel active SOS (or ignore) ───────────────────

    private suspend fun handleDoubleTap() {
        if (!IotSosTracker.isActiveOrRecentlyDispatched()) {
            Log.d(TAG, "Double tap ignored — no SOS active or recently dispatched")
            return
        }

        val alertId  = IotSosTracker.activeAlertId
        val dispatched = IotSosTracker.isDispatched

        // When dispatched and we have an ID → mark user safe.
        // In every other case (countdown, or alertId somehow lost after navigation)
        // call cancelSos with a nullable ID.  The backend looks up the caller's
        // most-recent active countdown alert when alert_id is omitted, so this
        // recovers gracefully even if IotSosTracker lost its state.
        Log.d(TAG, "Double tap → ${if (dispatched && alertId != null) "markUserSafe" else "cancelSos"} alertId=$alertId")

        val result = if (dispatched && alertId != null) {
            sosRepository.markUserSafe(alertId)
        } else {
            sosRepository.cancelSos(alertId)   // alertId may be null — backend handles it
        }

        when (result) {
            is NetworkResult.Success -> {
                IotSosTracker.onAlertResolved()
                IotEventBus.post(IotAction.Cancelled(alertId, "Cancelled via wearable button"))
                Log.d(TAG, "SOS resolved via hardware double-tap")
            }
            is NetworkResult.Error -> {
                Log.e(TAG, "Cancel/safe failed: [${result.code}] ${result.message}")
            }
            is NetworkResult.Loading -> Unit
        }
    }

    // ------------------------------------------------------------------ //
    // Proximity monitoring (RSSI via BLE advertisement)                  //
    // ------------------------------------------------------------------ //

    /**
     * Polls the ESP32's BLE RSSI every [PROXIMITY_POLL_INTERVAL_MS] to estimate
     * distance.  Runs as a child coroutine of the SPP reader loop and is cancelled
     * automatically when the socket drops or [stopListening] is called.
     *
     * Requirements on firmware side: the ESP32 must broadcast a BLE advertisement
     * with local name [BLE_DEVICE_NAME] (trivial one-liner in Arduino BLE library;
     * the dual-mode chip supports Classic SPP + BLE simultaneously).
     *
     * If no BLE advertisement is found in the [PROXIMITY_SCAN_WINDOW_MS] window the
     * reading is skipped — no false breach is counted.  Trigger requires
     * [PROXIMITY_BREACH_COUNT] consecutive readings above [PROXIMITY_THRESHOLD_M].
     */
    @SuppressLint("MissingPermission")
    private suspend fun monitorProximity() {
        val leScanner = context.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE scanner unavailable — proximity monitoring disabled")
            return
        }
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d(TAG, "Proximity monitoring started (threshold=${PROXIMITY_THRESHOLD_M}m, " +
                "breach=$PROXIMITY_BREACH_COUNT×, txPower=${TX_POWER_AT_1M}dBm, n=$PATH_LOSS_N)")

        // while (true) is safe here: delay() and withTimeoutOrNull() both propagate
        // CancellationException when proximityJob is cancelled, exiting the loop.
        while (true) {
            // ── Collect multiple RSSI samples in the scan window ────────────────
            // A SINGLE reading can be ±15 dBm off due to multipath reflections.
            // Using the MEDIAN of several packets removes most of that noise.
            val samples = mutableListOf<Int>()

            withTimeoutOrNull(PROXIMITY_SCAN_WINDOW_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val cb = object : ScanCallback() {
                        override fun onScanResult(type: Int, result: ScanResult) {
                            val advName = result.scanRecord?.deviceName
                                ?: runCatching { result.device.name }.getOrNull()
                            if (advName == BLE_DEVICE_NAME) {
                                samples.add(result.rssi)
                                Log.v(TAG, "BLE sample #${samples.size}: ${result.rssi} dBm")
                                // Once we have enough, stop scanning early to save battery.
                                if (samples.size >= PROXIMITY_MAX_SAMPLES && !cont.isCompleted) {
                                    leScanner.stopScan(this)
                                    cont.resume(Unit)
                                }
                            }
                        }
                        override fun onScanFailed(errorCode: Int) {
                            Log.e(TAG, "BLE scan failed errorCode=$errorCode")
                            if (!cont.isCompleted) cont.resume(Unit)
                        }
                    }
                    leScanner.startScan(null, scanSettings, cb)
                    cont.invokeOnCancellation { runCatching { leScanner.stopScan(cb) } }
                }
            }

            // ── Evaluate samples ─────────────────────────────────────────────────
            if (samples.size < PROXIMITY_MIN_SAMPLES) {
                // Device not heard — ESP32 BLE may not be advertising yet or is genuinely
                // too far for BLE.  Don't count as a breach; wait for firmware to boot.
                Log.d(TAG, "Proximity: only ${samples.size} sample(s) — skipping poll " +
                        "(need $PROXIMITY_MIN_SAMPLES min, ESP32 BLE may not be advertising)")
                delay(PROXIMITY_POLL_INTERVAL_MS - PROXIMITY_SCAN_WINDOW_MS)
                continue
            }

            // Compute median (robust against outlier reflections)
            val sorted = samples.sorted()
            val medianRssi = sorted[sorted.size / 2]

            // ── "Definitely close" guard ─────────────────────────────────────────
            // If the MEDIAN signal is strong, the device is unambiguously nearby.
            // Skip the formula entirely — it is inaccurate below 2 m due to near-field
            // effects — and reset any in-progress breach counter.
            if (medianRssi >= PROXIMITY_CLOSE_RSSI_THRESHOLD) {
                val approxDist = rssiToDistance(medianRssi)
                Log.d(TAG, "Proximity: CLOSE (median=${medianRssi}dBm ≥ threshold, " +
                        "approx=%.1fm, samples=$samples) — resetting breach counter".format(approxDist))
                if (consecutiveBreachCount > 0) {
                    Log.d(TAG, "Breach counter reset from $consecutiveBreachCount → 0 by close-by guard")
                    consecutiveBreachCount = 0
                }
                IotEventBus.post(IotAction.ProximityUpdate(approxDist.coerceAtMost(PROXIMITY_THRESHOLD_M - 1f)))
                delay(PROXIMITY_POLL_INTERVAL_MS - PROXIMITY_SCAN_WINDOW_MS)
                continue
            }

            // ── Distance formula ─────────────────────────────────────────────────
            val dist = rssiToDistance(medianRssi)
            Log.d(TAG, "Proximity: %.1fm (median=%ddBm, samples=%d, raw=%s)".format(
                dist, medianRssi, samples.size, samples.toString()
            ))
            IotEventBus.post(IotAction.ProximityUpdate(dist))

            if (dist > PROXIMITY_THRESHOLD_M) {
                consecutiveBreachCount++
                Log.w(TAG, "Out-of-range $consecutiveBreachCount/$PROXIMITY_BREACH_COUNT " +
                        "(%.1fm, median=${medianRssi}dBm)".format(dist))
                if (consecutiveBreachCount >= PROXIMITY_BREACH_COUNT
                    && !IotSosTracker.isInHardwareCooldown()
                ) {
                    consecutiveBreachCount = 0
                    Log.w(TAG, "Proximity threshold sustained for $PROXIMITY_BREACH_COUNT " +
                            "consecutive readings — triggering proximity SOS")
                    triggerProximitySos()
                }
            } else {
                if (consecutiveBreachCount > 0) {
                    Log.d(TAG, "Back in range (%.1fm) — resetting breach counter from $consecutiveBreachCount".format(dist))
                }
                consecutiveBreachCount = 0
            }

            delay(PROXIMITY_POLL_INTERVAL_MS - PROXIMITY_SCAN_WINDOW_MS)
        }
    }

    /**
     * Converts RSSI (dBm) to distance (metres) using the log-distance path-loss model:
     *   distance = 10 ^ ((txPower − RSSI) / (10 × n))
     * Typical indoor accuracy: ±2–4 m.
     */
    private fun rssiToDistance(rssi: Int): Float {
        val exp = (TX_POWER_AT_1M - rssi) / (10f * PATH_LOSS_N)
        return 10.0.pow(exp.toDouble()).toFloat()
    }

    /**
     * Triggers an SOS because the wearable has been > [PROXIMITY_THRESHOLD_M] m away
     * for [PROXIMITY_BREACH_COUNT] consecutive polls.
     *
     * Reuses [IotAction.Triggered] so AppNavGraph navigates to the 10-second countdown
     * screen — giving the user a chance to cancel before contacts are notified.
     */
    private suspend fun triggerProximitySos() {
        val location = getLastKnownLocation()
        when (val result = sosRepository.triggerSos(
            triggerType = "hardware_distress",
            latitude    = location?.latitude  ?: 0.0,
            longitude   = location?.longitude ?: 0.0
        )) {
            is NetworkResult.Success -> {
                val alertId = result.data.alertId
                IotSosTracker.onHardwareAlertCreated(alertId)
                IotEventBus.post(IotAction.Triggered(alertId))
                Log.w(TAG, "Proximity SOS triggered — alertId=$alertId")
            }
            is NetworkResult.Error ->
                Log.e(TAG, "Proximity SOS failed: [${result.code}] ${result.message}")
            is NetworkResult.Loading -> Unit
        }
    }

    // ------------------------------------------------------------------ //
    // GPS helper                                                          //
    // ------------------------------------------------------------------ //

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): Location? {
        return try {
            suspendCancellableCoroutine { cont ->
                val cts = CancellationTokenSource()
                fusedLocationClient
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location unavailable: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ //
    // Cleanup                                                             //
    // ------------------------------------------------------------------ //

    fun stopListening() {
        // Clear isCurrentlyConnected before cancelling the job. The coroutine's finally
        // block checks this flag; setting it false first prevents a spurious
        // IotAction.Disconnected from being posted for an intentional service stop.
        isCurrentlyConnected = false
        proximityJob?.cancel()
        proximityJob = null
        consecutiveBreachCount = 0
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        readerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        // Do NOT reset hasEverConnected here. Once the device has been paired and
        // connected successfully, a service restart (START_STICKY after SOS) should
        // quietly retry, not post ConnectionFailed and erase the device from the app.
        Log.d(TAG, "Bluetooth listener stopped")
    }
}



