package com.traintracker

/**
 * DarwinClient — Darwin Push Port (Kafka)
 *
 * All live feed consumers run server-side on the Oracle VM.
 * DarwinFormation is retained here as it is used by MainViewModel
 * for formation/unit data received via the server allocation API.
 */
data class DarwinFormation(
    val rid: String,
    val uid: String,
    val trainId: String,
    val units: List<String>,
    val coachCount: Int
)