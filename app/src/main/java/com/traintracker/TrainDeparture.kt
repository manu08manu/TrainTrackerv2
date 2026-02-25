package com.traintracker

/**
 * Represents a single train service from the departure board.
 */
data class TrainDeparture(
    val scheduledDeparture: String,    // std - Scheduled Time of Departure e.g. "10:30"
    val estimatedDeparture: String,    // etd - Estimated Time of Departure e.g. "On time", "Delayed", "14:35"
    val destination: String,           // Final destination station name
    val platform: String?,             // Platform number (may be null if not yet assigned)
    val operator: String,              // Train Operating Company name
    val serviceId: String,             // Unique service ID for fetching details
    val length: String?,               // Number of coaches (may be null)
    val isCancelled: Boolean = false,  // Whether the service is canceled
    val cancelReason: String? = null,  // Reason for cancellation
    val delayReason: String? = null    // Reason for delay
)
