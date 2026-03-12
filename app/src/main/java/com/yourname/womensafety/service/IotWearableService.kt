package com.yourname.womensafety.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourname.womensafety.MainActivity
import com.yourname.womensafety.R
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.IotWearableManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground Service that keeps the [IotWearableManager] alive even when the
 * app is backgrounded or the screen is off.
 *
 * The service starts with [startFor] (pass the device MAC) and stops with [stop].
 *
 * If the system kills the service (e.g. due to memory pressure), START_STICKY
 * re-delivers the last Intent so the SPP connection is re-established
 * automatically using the same MAC address.
 */
class IotWearableService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var wearableManager: IotWearableManager

    companion object {
        private const val TAG        = "IotWearableService"
        private const val CHANNEL_ID = "iot_wearable_channel"
        private const val NOTIF_ID   = 1002
        const val EXTRA_MAC          = "device_mac"

        /** Start the foreground service with the given Bluetooth MAC. */
        fun startFor(context: Context, mac: String) {
            val intent = Intent(context, IotWearableService::class.java)
                .putExtra(EXTRA_MAC, mac)
            context.startForegroundService(intent)
            Log.d(TAG, "startFor: $mac")
        }

        /** Stop the foreground service and close the BT connection. */
        fun stop(context: Context) {
            context.stopService(Intent(context, IotWearableService::class.java))
            Log.d(TAG, "stop requested")
        }
    }

    override fun onCreate() {
        super.onCreate()
        wearableManager = IotWearableManager(
            context       = this,
            sosRepository = AppServiceLocator.sosRepository
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On a START_STICKY system restart the intent is null.
        // Fall back to the MAC saved by IotViewModel so the connection is recovered
        // automatically without user intervention.
        val mac = intent?.getStringExtra(EXTRA_MAC)
            ?: getSharedPreferences("iot_wearable_prefs", Context.MODE_PRIVATE)
                .getString("iot_device_mac", null)
        if (mac.isNullOrBlank()) {
            Log.e(TAG, "No MAC available — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        wearableManager.startListening(serviceScope, mac)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        wearableManager.stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ //
    // Notification                                                        //
    // ------------------------------------------------------------------ //

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wearable Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the ESP32 SOS wearable connection alive in the background"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java))
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wearable Connected")
            .setContentText("ESP32 SOS device is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
