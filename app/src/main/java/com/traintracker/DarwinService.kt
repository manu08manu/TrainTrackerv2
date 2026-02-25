package com.traintracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that maintains the Darwin Push Port STOMP connection.
 *
 * Android will kill a background app's network connections; a foreground service
 * with a persistent notification keeps the STOMP stream alive when the user
 * leaves the app.
 *
 * Lifecycle:
 *   startService() / bindService() → connect()
 *   stopService()                  → disconnect() and stopSelf()
 *
 * Activities bind to this service to receive [DarwinUpdate] events via
 * [updates] and [connectionState] SharedFlows.
 *
 * Usage from Activity:
 *   DarwinService.start(this)
 *   bindService(Intent(this, DarwinService::class.java), connection, BIND_AUTO_CREATE)
 */
class DarwinService : Service() {

    companion object {
        private const val TAG = "DarwinService"
        private const val CHANNEL_ID = "darwin_live"
        private const val NOTIFICATION_ID = 1001

        /** Start (and if not yet running, create) the service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DarwinService::class.java))
        }
    }

    // --- Binder -----------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): DarwinService = this@DarwinService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // --- Core components --------------------------------------------------

    val darwinClient = DarwinClient()
    val trustClient  = TrustClient()

    val updates: SharedFlow<DarwinUpdate>
        get() = darwinClient.updates

    val connectionState: SharedFlow<DarwinConnectionState>
        get() = darwinClient.connectionState

    val formations: SharedFlow<DarwinFormation>
        get() = darwinClient.formations

    val trustMovements: SharedFlow<TrustMovement>
        get() = trustClient.movements

    val trustActivations: SharedFlow<TrustActivation>
        get() = trustClient.activations

    val trustConnected: SharedFlow<Boolean>
        get() = trustClient.connected

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Service lifecycle ------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Connecting to live feed…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        Log.d(TAG, "DarwinService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        darwinClient.connect()
        trustClient.connect()

        // Update notification text when connection state changes
        serviceScope.launch {
            darwinClient.connectionState.collect { state ->
                val text = when (state) {
                    is DarwinConnectionState.Connecting    -> "Connecting to live feed…"
                    is DarwinConnectionState.Connected     -> "Live departures active"
                    is DarwinConnectionState.Disconnected  -> "Live feed disconnected"
                    is DarwinConnectionState.Error         -> "Live feed error — retrying"
                }
                updateNotification(text)
            }
        }

        return START_STICKY  // system will restart us if killed
    }

    override fun onDestroy() {
        darwinClient.disconnect()
        trustClient.disconnect()
        serviceScope.cancel()
        Log.d(TAG, "DarwinService destroyed")
        super.onDestroy()
    }

    // --- Forwarding to client ---------------------------------------------

    fun setFilterCrs(crs: String) {
        darwinClient.setFilterCrs(crs)
        // Resolve CRS→STANOX via CORPUS for TRUST filtering
        trustClient.setFilterCrs(crs)
    }

    // --- Notification helpers ---------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live Train Feed",
            NotificationManager.IMPORTANCE_LOW  // silent, no vibration
        ).apply {
            description = "Keeps the Darwin real-time train feed running"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrainTracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        // Only post if the app holds POST_NOTIFICATIONS permission (required on API 33+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
