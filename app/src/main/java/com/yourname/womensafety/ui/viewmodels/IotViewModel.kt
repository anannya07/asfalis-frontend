package com.yourname.womensafety.ui.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.IotAction
import com.yourname.womensafety.data.IotEventBus
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.repository.DeviceRepository
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.service.IotWearableService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages the lifecycle of the ESP32 IoT wearable connection:
 *
 * 1. Scans bonded Bluetooth devices for [ESP32_DEVICE_NAME].
 * 2. On connect: calls POST /api/device/register, saves MAC + device-ID to SharedPreferences,
 *    then starts [IotWearableService].
 * 3. On disconnect: calls PUT /api/device/<id>/status with is_connected=false, stops the service.
 * 4. On startup: restores prior connection state from SharedPreferences so the UI reflects reality.
 */
class IotViewModel(
    application: Application,
    private val deviceRepository: DeviceRepository
) : AndroidViewModel(application) {

    companion object {
        private const val TAG               = "IotViewModel"
        const val ESP32_DEVICE_NAME         = "ESP32_SOS_DEVICE"
        private const val PREFS_NAME          = "iot_wearable_prefs"
        private const val PREF_MAC             = "iot_device_mac"
        private const val PREF_ID              = "iot_device_id"
        /**
         * Persisted boolean: true once the device has ever successfully connected.
         * Used to distinguish a first-time setup failure (clear everything) from a
         * temporary post-SOS disconnect (keep the pairing so the service auto-reconnects).
         */
        private const val PREF_EVER_CONNECTED  = "iot_ever_connected"
        private const val FIRMWARE             = "1.0.0"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                IotViewModel(
                    AppServiceLocator.application,
                    AppServiceLocator.deviceRepository
                ) as T
        }
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _foundDevice = MutableStateFlow<BluetoothDevice?>(null)
    /** The bonded ESP32 device found during the last scan, or null. */
    val foundDevice: StateFlow<BluetoothDevice?> = _foundDevice

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /**
     * Most-recent RSSI-based distance estimate from the proximity monitor (metres).
     * Null when the wearable is not connected or BLE advertising has not been detected yet.
     */
    private val _deviceDistance = MutableStateFlow<Float?>(null)
    val deviceDistance: StateFlow<Float?> = _deviceDistance

    /** Stored device-ID needed for the PUT /device/<id>/status endpoint. */
    private val savedDeviceId: String
        get() = prefs.getString(PREF_ID, "") ?: ""

    /** Stored MAC from SharedPreferences (persists across app restarts). */
    val savedMac: String
        get() = prefs.getString(PREF_MAC, "") ?: ""

    /**
     * Listens for [BluetoothAdapter.ACTION_STATE_CHANGED] broadcasts.
     * When the user turns Bluetooth off, immediately stops the wearable service
     * and reverts the UI to DISCONNECTED — without waiting for the SPP socket
     * to time out and post [IotAction.ConnectionFailed].
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF) {
                Log.w(TAG, "Bluetooth turned off — stopping service but preserving device pairing")
                if (_connectionState.value != ConnectionState.DISCONNECTED) {
                    IotWearableService.stop(getApplication())
                    // Do NOT remove PREF_MAC / PREF_ID.  Turning BT off is not the same
                    // as forgetting the device.  When BT is re-enabled the service will
                    // restart via START_STICKY and reconnect automatically.
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _deviceDistance.value = null
                    _errorMessage.value = "Bluetooth was turned off. Turn it back on to restore the wearable."
                }
            }
        }
    }

    init {
        // On startup the service may still be (re)connecting via START_STICKY.
        // Show CONNECTING — not CONNECTED — until the socket handshake is confirmed
        // via IotAction.Connected.  Showing CONNECTED here is a lie that causes Bug 2.
        if (savedMac.isNotEmpty()) {
            _connectionState.value = ConnectionState.CONNECTING
        }

        // Register the BT-off receiver at Application scope so it survives screen rotations.
        getApplication<Application>().registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        // React to BT connection outcomes posted by IotWearableManager.
        viewModelScope.launch {
            IotEventBus.events.collect { action ->
                when (action) {
                    is IotAction.Connected -> {
                        // Socket confirmed open — covers both first connect and reconnects.
                        _connectionState.value = ConnectionState.CONNECTED
                        // Persist: this device has been successfully connected at least once.
                        // Future service restarts use this to avoid clearing the pairing
                        // on temporary disconnects (e.g., device rebooting after SOS).
                        prefs.edit().putBoolean(PREF_EVER_CONNECTED, true).apply()
                    }
                    is IotAction.Disconnected -> {
                        // Socket dropped; the background service is actively retrying.
                        // Only downgrade if we haven't already been set to DISCONNECTED by
                        // a manual disconnect or the BT-off receiver.
                        if (_connectionState.value != ConnectionState.DISCONNECTED) {
                            _connectionState.value = ConnectionState.CONNECTING
                        }
                        _deviceDistance.value = null
                    }
                    is IotAction.ConnectionFailed -> {
                        val everConnected = prefs.getBoolean(PREF_EVER_CONNECTED, false)
                        if (everConnected) {
                            // The device was paired successfully before (e.g., it rebooted
                            // after SOS).  Keep the saved MAC/ID so the foreground service
                            // retries automatically when the hardware is available again.
                            _connectionState.value = ConnectionState.CONNECTING
                            _deviceDistance.value = null
                            _errorMessage.value = "Wearable temporarily unreachable — retrying…"
                            Log.w(TAG, "ConnectionFailed on previously-paired device — keeping MAC, service retries")
                        } else {
                            // Genuine first-time setup failure — device was never reachable.
                            // Clear the pairing so the user can set up fresh.
                            IotWearableService.stop(getApplication())
                            prefs.edit().remove(PREF_MAC).remove(PREF_ID)
                                .remove(PREF_EVER_CONNECTED).apply()
                            _connectionState.value = ConnectionState.DISCONNECTED
                            _deviceDistance.value = null
                            _errorMessage.value = action.reason
                            Log.e(TAG, "ConnectionFailed on first-time setup — clearing pairing")
                        }
                    }
                    is IotAction.ProximityUpdate -> {
                        _deviceDistance.value = action.distanceMetres
                    }
                    else -> Unit // Triggered / Cancelled handled by AppNavGraph
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Scanning                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Checks the list of already-bonded Bluetooth devices for [ESP32_DEVICE_NAME].
     * No runtime scan is needed — the user must pair through Android Settings first.
     */
    @SuppressLint("MissingPermission")
    fun scanForDevice() {
        _foundDevice.value = null
        _errorMessage.value = null
        val adapter = getApplication<Application>()
            .getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _errorMessage.value = "Bluetooth is disabled. Please enable it in Settings."
            return
        }
        // Log all paired devices so the developer can see the exact names in Logcat.
        val allBonded = adapter.bondedDevices?.toList() ?: emptyList()
        Log.d(TAG, "Paired devices (${allBonded.size}): ${allBonded.map { "${it.name} [${it.address}]" }}")

        // Match by name — trim whitespace and ignore case to tolerate minor firmware variations.
        val paired = allBonded.firstOrNull {
            it.name?.trim()?.equals(ESP32_DEVICE_NAME, ignoreCase = true) == true
        }
        if (paired == null) {
            // Also check if there's a device with ESP32 anywhere in the name as a helpful hint
            val anyEsp32 = allBonded.firstOrNull { it.name?.contains("esp32", ignoreCase = true) == true }
            _errorMessage.value = if (anyEsp32 != null) {
                "Found \"${anyEsp32.name}\" but expected \"$ESP32_DEVICE_NAME\".\n" +
                "Check that the firmware sets BT name to exactly \"$ESP32_DEVICE_NAME\"."
            } else {
                "\"$ESP32_DEVICE_NAME\" not found in paired devices.\nPair it via Android Bluetooth Settings first."
            }
        } else {
            Log.d(TAG, "Found paired device: ${paired.name} [${paired.address}]")
            _foundDevice.value = paired
        }
    }

    // ------------------------------------------------------------------ //
    // Connect                                                             //
    // ------------------------------------------------------------------ //

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (_connectionState.value == ConnectionState.CONNECTING) return

        // Android 12+ requires BLUETOOTH_CONNECT at runtime.
        // Check it here before touching any BT API so failures are immediately visible.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                _errorMessage.value =
                    "Bluetooth permission not granted. Open App Settings → Permissions → " +
                    "\"Nearby devices\" and allow it, then try again."
                Log.e(TAG, "connect() aborted — BLUETOOTH_CONNECT not granted")
                return
            }
        }

        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null

        viewModelScope.launch {
            // ── Step 1: Start BT immediately — do NOT gate on the registration API ──
            // Saving the MAC first so the service can recover it on a START_STICKY restart.
            prefs.edit().putString(PREF_MAC, device.address).apply()
            // State stays CONNECTING — it will be updated to CONNECTED by IotEventBus
            // once the SPP socket is actually open, or reverted to DISCONNECTED on failure.
            IotWearableService.startFor(getApplication(), device.address)
            Log.d(TAG, "BT service started for ${device.address} — awaiting connection confirmation")

            // ── Step 2: Register device in background (best-effort, non-blocking) ──
            // Registration failure is non-fatal — the BT listener is already running.
            val result = deviceRepository.registerDevice(
                deviceName      = device.name ?: ESP32_DEVICE_NAME,
                deviceMac       = device.address,
                firmwareVersion = FIRMWARE
            )
            when (result) {
                is NetworkResult.Success -> {
                    prefs.edit().putString(PREF_ID, result.data.deviceId).apply()
                    Log.d(TAG, "Device registered — deviceId=${result.data.deviceId}")
                }
                is NetworkResult.Error -> {
                    // BT is still active — do NOT set ERROR state or stop the service.
                    Log.w(TAG, "Registration failed [${result.code}]: ${result.message}")
                    if (result.code == "NO_CONTACTS") {
                        _errorMessage.value =
                            "Wearable connected. Add a trusted contact to enable SOS."
                    }
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Disconnect                                                          //
    // ------------------------------------------------------------------ //

    fun disconnect() {
        // Update UI state immediately — don’t wait for the network call to complete.
        _connectionState.value = ConnectionState.DISCONNECTED
        _foundDevice.value = null
        _deviceDistance.value = null
        viewModelScope.launch {
            val deviceId = savedDeviceId
            if (deviceId.isNotEmpty()) {
                deviceRepository.updateDeviceStatus(deviceId, isConnected = false)
            }
            IotWearableService.stop(getApplication())
            // Explicit user-initiated disconnect — clear everything including the
            // PREF_EVER_CONNECTED flag so the next setup starts completely fresh.
            prefs.edit()
                .remove(PREF_MAC)
                .remove(PREF_ID)
                .remove(PREF_EVER_CONNECTED)
                .apply()
            Log.d(TAG, "Disconnected from wearable (user-initiated)")
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister the Bluetooth state receiver to avoid leaking the ViewModel context.
        runCatching { getApplication<Application>().unregisterReceiver(bluetoothStateReceiver) }
    }
}
