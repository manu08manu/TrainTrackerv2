package com.traintracker

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bound-only service that holds the Darwin Push Port STOMP + TRUST connections.
 *
 * Connects when the first Activity binds, disconnects when the last unbinds.
 * No foreground notification, no background battery drain.
 *
 * Usage from Activity:
 *   bindService(Intent(this, DarwinService::class.java), connection, BIND_AUTO_CREATE)
 *   // unbindService(connection) in onDestroy() — Android stops the service automatically
 */
class DarwinService : Service() {

    companion object {
        private const val TAG = "DarwinService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): DarwinService = this@DarwinService
    }

    private val binder = LocalBinder()

    val darwinClient = DarwinClient()
    val trustClient  = TrustClient()

    val updates:          SharedFlow<DarwinUpdate>          get() = darwinClient.updates
    val connectionState:  SharedFlow<DarwinConnectionState> get() = darwinClient.connectionState
    val formations:       SharedFlow<DarwinFormation>       get() = darwinClient.formations
    val trustMovements:   SharedFlow<TrustMovement>         get() = trustClient.movements
    val trustActivations: SharedFlow<TrustActivation>       get() = trustClient.activations
    val trustConnected:   SharedFlow<Boolean>               get() = trustClient.connected

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent): IBinder {
        // Connect the first time any Activity binds
        darwinClient.connect()
        trustClient.connect()
        Log.d(TAG, "Client bound — connections started")
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // All Activities have unbound — app is in background, disconnect immediately
        darwinClient.disconnect()
        trustClient.disconnect()
        Log.d(TAG, "All clients unbound — connections stopped")
        // Return false: if a new client binds later, onBind() will be called again
        return false
    }

    override fun onDestroy() {
        darwinClient.disconnect()
        trustClient.disconnect()
        serviceScope.cancel()
        Log.d(TAG, "DarwinService destroyed")
        super.onDestroy()
    }

    fun setFilterCrs(crs: String) {
        darwinClient.setFilterCrs(crs)
        trustClient.setFilterCrs(crs)
    }
}
