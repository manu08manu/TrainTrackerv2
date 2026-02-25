package com.traintracker

import java.util.Calendar

// ─── Time helpers ─────────────────────────────────────────────────────────────

/** Parse "HH:MM" into minutes-since-midnight, returns -1 on failure. */
private fun timeToMinutes(t: String): Int {
    val colon = t.indexOf(':')
    if (colon < 0) return -1
    return try {
        t.substring(0, colon).toInt() * 60 + t.substring(colon + 1).toInt()
    } catch (_: NumberFormatException) { -1 }
}

/** Minutes from now until a given "HH:MM" time. Negative = in the past. */
fun minutesUntilTime(timeStr: String): Int {
    val target = timeToMinutes(timeStr)
    if (target < 0) return Int.MAX_VALUE
    val cal  = Calendar.getInstance()
    val now  = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    var diff = target - now
    if (diff < -120) diff += 1440   // midnight wrap
    return diff
}

internal fun minuteDelay(scheduled: String, estimated: String): Int {
    if (estimated == "On time" || estimated.isEmpty()) return 0
    val sch = timeToMinutes(scheduled)
    val est = timeToMinutes(estimated)
    if (sch < 0 || est < 0) return 0
    var diff = est - sch
    if (diff < -120) diff += 1440
    return if (diff > 0) diff else 0
}

/** Format a minute duration as "Xhr Ymin" or just "Ymin". */
fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return ""
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}min"
        m == 0 -> "${h}hr"
        else   -> "${h}hr ${m}min"
    }
}

/** Compute duration in minutes between two "HH:MM" strings, handling midnight wrap. */
fun durationMinutes(from: String, to: String): Int {
    val a = timeToMinutes(from)
    val b = timeToMinutes(to)
    if (a < 0 || b < 0) return 0
    var diff = b - a
    if (diff < 0) diff += 1440
    return diff
}

// ─── Schedule tag ─────────────────────────────────────────────────────────────

/** Short tag shown on each card. Mirrors RTT's WTT / VAR / CAN / BUS labels. */
enum class ScheduleTag(val label: String) {
    WTT("WTT"),   // Working Timetable — normal service
    VAR("VAR"),   // Variation — running but with a changed time
    CAN("CAN"),   // Cancelled
    BUS("BUS"),   // Bus replacement
    FERRY("FRY")  // Ferry
}

// ─── TrainService ─────────────────────────────────────────────────────────────

data class TrainService(
    val std: String,
    val etd: String,
    val sta: String,
    val eta: String,
    val destination: String,
    val origin: String,
    val platform: String,
    val operator: String,
    val operatorCode: String = "",
    val isCancelled: Boolean,
    val serviceID: String,
    val trainId: String,
    val boardType: BoardType,
    val serviceType: String = "train",
    val isPassenger: Boolean = true,
    val cancelReason: String = "",
    val delayReason: String = "",
    val actualDeparture: String = "",   // set by Darwin when train has departed
    val rsid: String = "",              // unit/RSID from OpenLDBWS
    val units: List<String> = emptyList(), // actual unit numbers from Darwin formation
    val darwinCoachCount: Int = 0,      // coach count from Darwin formation
    val powerType: String = "",         // traction type from RTT: EMU, DMU, HST, DEMU etc.
    val rollingStockDesc: String = "",  // human-readable formation e.g. "Class 387 Electrostar · EMU · 387001 + 387002 · 8c"
    val journeyMinutes: Int = 0,        // scheduled journey duration in minutes
    val hasAlert: Boolean = false       // true if service has cancel/delay reason text
) {
    val scheduledTime: String get() = if (boardType == BoardType.ARRIVALS) sta else std
    val estimatedTime: String get() = if (boardType == BoardType.ARRIVALS) eta else etd

    val delayMinutes: Int by lazy { if (isCancelled) 0 else minuteDelay(scheduledTime, estimatedTime) }
    val isDelayed:    Boolean by lazy { !isCancelled && delayMinutes > 0 }
    val hasDeparted:  Boolean get() = actualDeparture.isNotEmpty()

    /** Effective time used for countdown — ETD if it's a real time, else STD. */
    private val effectiveTime: String get() {
        val t = estimatedTime
        return if (t.matches(Regex("\\d{2}:\\d{2}"))) t else scheduledTime
    }

    /** Minutes until this service departs/arrives from now. */
    val minutesUntil: Int get() = minutesUntilTime(effectiveTime)

    /** Human-readable countdown label: "Due", "1 min", "5 min", "Dep 14:32" etc. */
    val countdownLabel: String get() = when {
        isCancelled       -> ""
        hasDeparted       -> "Dep $actualDeparture"
        minutesUntil <= 0 -> "Due"
        minutesUntil == 1 -> "1 min"
        minutesUntil < 60 -> "${minutesUntil} min"
        else              -> ""
    }

    /** Approach state label shown below the countdown. */
    val approachState: String get() = when {
        isCancelled       -> ""
        hasDeparted       -> "Departed"
        minutesUntil <= 0 -> "Approaching"
        minutesUntil <= 2 -> "Arriving"
        else              -> ""
    }

    /** RTT-style schedule tag for this service. */
    val scheduleTag: ScheduleTag get() = when {
        isCancelled                     -> ScheduleTag.CAN
        serviceType == "bus"            -> ScheduleTag.BUS
        serviceType == "ferry"          -> ScheduleTag.FERRY
        isDelayed                       -> ScheduleTag.VAR
        else                            -> ScheduleTag.WTT
    }

    val statusDisplay: String get() = when {
        isCancelled                        -> "Cancelled"
        isDelayed                          -> "+${delayMinutes}m"
        estimatedTime == "Delayed"         -> "Delayed"
        else                               -> "On time"
    }

    val isFreight: Boolean get() = !isPassenger

    val specialServiceType: String get() {
        if (isPassenger && serviceType !in listOf("bus","ferry")) return "passenger"
        if (serviceType == "bus")   return "bus"
        if (serviceType == "ferry") return "ferry"
        val h = trainId.uppercase()
        return when {
            h.length >= 2 && h[0] == '6' -> "freight"
            h.length >= 2 && h[0] == '7' -> "freight"
            h.length >= 2 && h[0] == '5' && h[1] == 'Z' -> "railtour"
            h.length >= 2 && h[0] == '1' && h[1] == 'Z' -> "railtour"
            h.length >= 2 && h[0] == '0' && h[1] == 'Z' -> "railtour"
            h.length >= 2 && h[0] == '1' && h[1] == 'X' -> "railtour"
            h.length >= 2 && h[0] == '0' && h[1] == 'X' -> "railtour"
            h.length >= 2 && h[0] == '5' -> "ecs"
            h.length >= 2 && h[0] == '0' -> "lightengine"
            else -> "special"
        }
    }

    val serviceTypeLabel: String get() = when (specialServiceType) {
        "passenger"   -> ""
        "bus"         -> "Bus replacement"
        "ferry"       -> "Ferry"
        "freight"     -> "Not in normal passenger service · Freight"
        "railtour"    -> "Not in normal passenger service · Railtour"
        "ecs"         -> "Not in normal passenger service · Empty stock"
        "lightengine" -> "Not in normal passenger service · Light engine"
        else          -> "Not in normal passenger service"
    }

    val serviceTypeIcon: String get() = when (specialServiceType) {
        "bus"         -> "🚌"
        "ferry"       -> "⛴️"
        "freight"     -> "🚛"
        "railtour"    -> "🚂"
        "ecs"         -> "🚃"
        "lightengine" -> "🚂"
        "special"     -> "⚡"
        else          -> ""
    }

    /** Summary text for sharing this service. */
    fun shareText(stationName: String): String = buildString {
        append("$scheduledTime $origin → $destination")
        if (platform.isNotEmpty()) append(" · Plat $platform")
        append("\n")
        append("Status: $statusDisplay")
        if (delayMinutes > 0) append(" (+${delayMinutes} min)")
        append("\n")
        if (trainId.isNotEmpty()) append("Headcode: $trainId\n")
        if (operator.isNotEmpty()) append("Operator: $operator\n")
        if (journeyMinutes > 0) append("Journey: ${formatDuration(journeyMinutes)}\n")
        append("via Train Tracker · $stationName")
    }
}

// ─── BoardResult ──────────────────────────────────────────────────────────────

data class BoardResult(
    val stationName: String,
    val crs: String,
    val services: List<TrainService>,
    val generatedAt: String,
    val boardType: BoardType,
    val filterCallingAt: String = "",
    val timeOffset: Int = 0
)

// ─── CallingPoint ─────────────────────────────────────────────────────────────

data class CallingPoint(
    val locationName: String,
    val crs: String,
    val st: String,
    val et: String,
    val at: String,
    val isCancelled: Boolean,
    val length: Int?,
    val platform: String = "",
    val isPassing: Boolean = false
) {
    val delayMinutes: Int by lazy { if (isCancelled) 0 else minuteDelay(st, et) }
    val isDelayed:    Boolean by lazy { !isCancelled && delayMinutes > 0 }
}

// ─── ServiceDetails ───────────────────────────────────────────────────────────

data class ServiceDetails(
    val generatedAt: String,
    val serviceType: String,
    val trainId: String,
    val rsid: String,
    val operator: String,
    val operatorCode: String,
    val isCancelled: Boolean,
    val platform: String,
    val origin: String,
    val destination: String,
    val previousCallingPoints: List<CallingPoint>,
    val subsequentCallingPoints: List<CallingPoint>,
    val coachCount: Int?,
    val formation: String,
    val cancelReason: String = "",
    val delayReason: String = "",
    val adhocAlerts: String = ""
) {
    /** All stops in order: prev + subsequent. */
    val allCallingPoints: List<CallingPoint> get() =
        previousCallingPoints + subsequentCallingPoints

    /** Total scheduled journey duration across all calling points. */
    val journeyDurationMinutes: Int get() {
        val first = allCallingPoints.firstOrNull()?.st ?: return 0
        val last  = allCallingPoints.lastOrNull()?.st  ?: return 0
        return durationMinutes(first, last)
    }
}

// ─── RecentStation ────────────────────────────────────────────────────────────

data class RecentStation(val crs: String, val name: String, val timestamp: Long = System.currentTimeMillis())
