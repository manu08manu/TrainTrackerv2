package com.traintracker

/**
 * DarwinClient — Darwin Push Port (Kafka)
 *
 * Removed. Board population is CIF-only; real-time updates via TRUST.
 * DarwinFormation data class retained here as it is still used by
 * AllocationClient and MainViewModel for formation/unit data.
 */

// ── Data models retained for AllocationClient compatibility ──────────────────

data class DarwinUpdate(
    val trainRid: String,
    val trainUid: String,
    val headcode: String,
    val stationCrs: String,
    val stationTiploc: String,
    val scheduledDeparture: String,
    val scheduledArrival: String,
    val estimatedDeparture: String?,
    val estimatedArrival: String?,
    val actualDeparture: String?,
    val platform: String?,
    val isCancelled: Boolean,
    val cancelReason: String?,
    val delayReason: String?,
    val isPass: Boolean = false,
    val scheduledPass: String = ""
)

sealed class DarwinConnectionState {
    object Connecting   : DarwinConnectionState()
    object Connected    : DarwinConnectionState()
    object Disconnected : DarwinConnectionState()
    data class Error(val message: String) : DarwinConnectionState()
}

data class DarwinFormation(
    val rid: String,
    val uid: String,
    val trainId: String,
    val units: List<String>,
    val coachCount: Int
)