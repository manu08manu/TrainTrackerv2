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
 * Bound-only service holding the TRUST, Allocation and VSTP Kafka connections.
 * Connects when the first Activity binds, disconnects when the last unbinds.
 */
class DarwinService : Service() {

    companion object {
        private const val TAG = "DarwinService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): DarwinService = this@DarwinService
    }

    private val binder = LocalBinder()

    val trustClient      = TrustClient()
    val allocationClient = AllocationClient()
    val vstpClient       = VstpClient()

    val trustMovements:   SharedFlow<TrustMovement>   get() = trustClient.movements
    val trustActivations: SharedFlow<TrustActivation> get() = trustClient.activations
    val trustConnected:   SharedFlow<Boolean>         get() = trustClient.connected
    val allocations:      SharedFlow<DarwinFormation> get() = allocationClient.allocations
    val vstpConnected:    SharedFlow<Boolean>         get() = vstpClient.connected

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent): IBinder {
        trustClient.connect()
        allocationClient.connect()
        vstpClient.connect()
        Log.d(TAG, "Clients bound — TRUST + Allocation + VSTP started")
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        trustClient.disconnect()
        allocationClient.disconnect()
        vstpClient.disconnect()
        Log.d(TAG, "All clients unbound")
        return false
    }

    override fun onDestroy() {
        trustClient.disconnect()
        allocationClient.disconnect()
        vstpClient.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun setFilterCrs(crs: String) {
        trustClient.setFilterCrs(crs)
    }
}