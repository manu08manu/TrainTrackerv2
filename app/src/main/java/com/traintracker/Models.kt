package com.traintracker

import java.util.Calendar
import android.os.Parcel
import android.os.Parcelable

// ─── Darwin reason code lookup ────────────────────────────────────────────────

fun resolveReasonCode(code: String): String  = DarwinReasonCodes.resolveDelay(code)
fun resolveCancelCode(code: String): String  = DarwinReasonCodes.resolveCancel(code)


// ─── Time helpers ─────────────────────────────────────────────────────────────

/**
 * Convert ISO 8601 datetime (2026-03-05T19:16:00) to HH:MM format (19:16)
 * If already in HH:MM format, return as-is.
 * If malformed, return empty string.
 */
fun formatTimeFromIso(timeStr: String): String {
    if (timeStr.isEmpty()) return ""

    // If it's already in HH:MM format, return it
    if (timeStr.matches(Regex("\\d{2}:\\d{2}"))) return timeStr

    // Try to extract HH:MM from ISO 8601 format (2026-03-05T19:16:00)
    val tIndex = timeStr.indexOf('T')
    if (tIndex >= 0 && tIndex + 5 < timeStr.length) {
        val timeOnly = timeStr.substring(tIndex + 1, tIndex + 6)  // HH:MM
        if (timeOnly.matches(Regex("\\d{2}:\\d{2}"))) {
            return timeOnly
        }
    }

    return ""
}

private fun timeToMinutes(t: String): Int {
    val colon = t.indexOf(':')
    if (colon < 0) return -1
    return try {
        t.substring(0, colon).toInt() * 60 + t.substring(colon + 1).toInt()
    } catch (_: NumberFormatException) { -1 }
}

/**
 * Sort key for a scheduled time string that handles services past midnight correctly.
 *
 * The board shows services across a ~6-hour window. Services between 00:00–05:59
 * that appear on a late-night board (current time ≥ 20:00) are post-midnight and
 * should sort after all same-day services rather than jumping to the top.
 *
 * Returns minutes-of-day offset so that e.g. 00:05 viewed at 23:50 sorts as 1445
 * rather than 5, keeping it after 23:50 (1430) on the board.
 */
fun midnightAwareSortKey(scheduledTime: String): Int {
    val formatted = formatTimeFromIso(scheduledTime).ifEmpty { scheduledTime }
    val mins = timeToMinutes(formatted)
    if (mins < 0) return 9999          // missing time → sink to bottom
    val cal = Calendar.getInstance()
    val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    // If it's late evening and the service is in the small hours, it's next-day:
    // offset by +1440 so it sorts after all today's services.
    return if (nowMins >= 20 * 60 && mins < 6 * 60) mins + 1440 else mins
}

fun minutesUntilTime(timeStr: String): Int {
    val formatted = formatTimeFromIso(timeStr).ifEmpty { timeStr }
    val target = timeToMinutes(formatted)
    if (target < 0) return Int.MAX_VALUE
    val cal  = Calendar.getInstance()
    val now  = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    var diff = target - now
    if (diff < -120) diff += 1440
    return diff
}

internal fun minuteDelay(scheduled: String, estimated: String): Int {
    if (estimated == "On time" || estimated.isEmpty()) return 0
    val schFormatted = formatTimeFromIso(scheduled).ifEmpty { scheduled }
    val estFormatted = formatTimeFromIso(estimated).ifEmpty { estimated }
    val sch = timeToMinutes(schFormatted)
    val est = timeToMinutes(estFormatted)
    if (sch < 0 || est < 0) return 0
    var diff = est - sch
    if (diff < -120) diff += 1440
    return if (diff > 0) diff else 0
}

/**
 * Adds [delayMins] to a HH:MM time string and returns the result as HH:MM.
 * Wraps correctly past midnight. Returns [time] unchanged on any parse failure.
 */
internal fun addMinutesToTime(time: String, delayMins: Int): String {
    if (delayMins == 0 || time.isEmpty()) return time
    val formatted = formatTimeFromIso(time).ifEmpty { time }
    val colon = formatted.indexOf(':')
    if (colon < 0) return time
    return try {
        val baseMins = formatted.substring(0, colon).toInt() * 60 +
                formatted.substring(colon + 1, colon + 3).toInt()
        val resultMins = (baseMins + delayMins).let {
            when {
                it < 0     -> it + 1440
                it >= 1440 -> it - 1440
                else       -> it
            }
        }
        "%02d:%02d".format(resultMins / 60, resultMins % 60)
    } catch (_: Exception) { time }
}

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

fun durationMinutes(from: String, to: String): Int {
    val a = timeToMinutes(from)
    val b = timeToMinutes(to)
    if (a < 0 || b < 0) return 0
    var diff = b - a
    if (diff < 0) diff += 1440
    return diff
}

// ─── Schedule tag ─────────────────────────────────────────────────────────────

enum class ScheduleTag(val label: String) {
    WTT("WTT"),
    VAR("VAR"),
    CAN("CAN"),
    BUS("BUS"),
    FERRY("FRY")
}

// ─── Service category (used for filtering + display) ─────────────────────────

enum class ServiceCategory(val displayName: String, val filterKey: String) {
    PASSENGER("Passenger",    "passenger"),
    FREIGHT("Freight",        "freight"),
    ECS("Empty Stock",        "ecs"),
    RAILTOUR("Railtour",      "railtour"),
    LIGHT_ENGINE("Light Eng", "lightengine"),
    BUS("Bus Replace",        "bus"),
    FERRY("Ferry",            "ferry"),
    SPECIAL("Special",        "special")
}

// ─── TrainService ─────────────────────────────────────────────────────────────

/**
 * Headcode prefixes used by scheduled passenger operators.
 * These must never be classified as railtours regardless of headcode pattern.
 *
 * Eurostar:          9O, 9I
 * Thameslink:        9A, 9B, 9C, 9D, 9E, 9F, 9G, 9H
 * London Overground: 9J, 9K, 9L, 9M
 * Elizabeth line:    9R, 9S
 */
val KNOWN_PASSENGER_PREFIXES = setOf(
    "9O", "9I",
    "9A", "9B", "9C", "9D", "9E", "9F", "9G", "9H",
    "9J", "9K", "9L", "9M",
    "9R", "9S"
)

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
    val trainId: String,            // 4-character headcode (e.g. "1A34", "6M21", "5Z99")
    val boardType: BoardType,
    val serviceType: String = "train",
    val isPassenger: Boolean = true,
    val cancelReason: String = "",
    val delayReason: String = "",
    val actualDeparture: String = "",
    val actualArrival: String = "",
    val rsid: String = "",
    val units: List<String> = emptyList(),
    val darwinCoachCount: Int = 0,
    val powerType: String = "",
    val rollingStockDesc: String = "",
    val journeyMinutes: Int = 0,
    val hasAlert: Boolean = false,

    // Calling points (from board response - these include all stops on the journey)
    val previousCallingPoints: List<CallingPoint> = emptyList(),
    val subsequentCallingPoints: List<CallingPoint> = emptyList(),

    // Unit allocation details (from Darwin formations + rolling stock DB)
    val unitAllocation: UnitAllocation? = null,

    // Historical punctuality (from HSP)
    val punctualityPercent: Int = -1,   // -1 = not loaded; 0-100 = % on time
    val hspSampleSize: Int = 0,

    // Railtour / charter name (if known from RSID or known tour database)
    val tourName: String = "",

    // True for passenger services that pass through this station without stopping
    val isServicePassing: Boolean = false
) {
    val isArrival: Boolean get() = boardType == BoardType.ARRIVALS
    val scheduledTime: String get() = if (isArrival) sta else std
    val estimatedTime: String get() = if (isArrival) eta else etd
    // The actual recorded time — arrival time for arriving services, departure for departing
    val actualTime: String get() = if (isArrival) actualArrival else actualDeparture
    val hasActualTime: Boolean get() = actualTime.isNotEmpty()

    val delayMinutes: Int by lazy {
        if (isCancelled) 0
        else when {
            hasActualTime -> minuteDelay(scheduledTime, actualTime)
            else          -> minuteDelay(scheduledTime, estimatedTime)
        }
    }
    val isDelayed: Boolean by lazy { !isCancelled && delayMinutes > 0 }
    val hasDeparted: Boolean get() = hasActualTime

    private val effectiveTime: String get() {
        val t = estimatedTime
        val formatted = formatTimeFromIso(t)
        return if (formatted.isNotEmpty()) formatted else formatTimeFromIso(scheduledTime)
    }

    val minutesUntil: Int get() = minutesUntilTime(effectiveTime)

    val countdownLabel: String get() = when {
        isCancelled       -> ""
        hasDeparted       -> {
            val label = if (isArrival) "Arr" else "Dep"
            "$label ${formatTimeFromIso(actualTime).ifEmpty { actualTime.substringAfter("T").take(5) }}"
        }
        minutesUntil <= 0 -> "Due"
        minutesUntil == 1 -> "1 min"
        minutesUntil < 60 -> "${minutesUntil} min"
        else              -> ""
    }

    val approachState: String get() = when {
        isCancelled       -> ""
        hasDeparted       -> if (isArrival) "Arrived" else "Departed"
        minutesUntil <= 0 -> if (isArrival) "Arriving" else "Approaching"
        minutesUntil <= 2 -> "Arriving"
        else              -> ""
    }

    val scheduleTag: ScheduleTag get() = when {
        isCancelled            -> ScheduleTag.CAN
        serviceType == "bus"   -> ScheduleTag.BUS
        serviceType == "ferry" -> ScheduleTag.FERRY
        isDelayed              -> ScheduleTag.VAR
        else                   -> ScheduleTag.WTT
    }

    val statusDisplay: String get() = when {
        isCancelled    -> "Cancelled"
        hasDeparted    -> {
            val verb = if (isArrival) "Arr" else "Dep"
            if (delayMinutes > 0) "$verb +${delayMinutes}m" else if (isArrival) "Arrived" else "Departed"
        }
        isDelayed      -> "+${delayMinutes}m"
        estimatedTime == "Delayed" -> "Delayed"
        else           -> "On time"
    }

    val isFreight: Boolean get() = !isPassenger

    /** Derive service category from headcode and serviceType. */
    val category: ServiceCategory get() {
        if (serviceType == "bus")   return ServiceCategory.BUS
        if (serviceType == "ferry") return ServiceCategory.FERRY
        val h = trainId.uppercase()
        // Known scheduled passenger operators — always PASSENGER regardless of other flags
        if (h.length >= 2 && h.take(2) in KNOWN_PASSENGER_PREFIXES)
            return ServiceCategory.PASSENGER
        if (isPassenger && serviceType !in listOf("bus", "ferry")) {
            if (isRailtourHeadcode(h)) return ServiceCategory.RAILTOUR
            return ServiceCategory.PASSENGER
        }
        return when {
            isRailtourHeadcode(h)              -> ServiceCategory.RAILTOUR
            h.length >= 1 && h[0] in "67"     -> ServiceCategory.FREIGHT
            h.length >= 2 && h[0] == '5'      -> ServiceCategory.ECS
            h.length >= 2 && h[0] == '0'      -> ServiceCategory.LIGHT_ENGINE
            else                               -> ServiceCategory.SPECIAL
        }
    }

    private fun isRailtourHeadcode(h: String): Boolean {
        if (h.length < 2) return false
        val prefix = h.take(2)
        // Genuine railtour / charter prefixes only
        if (prefix !in setOf("1Z", "0Z", "5Z", "0X", "5X", "2Z", "3Z", "9Z")) return false
        // Exclude any prefix belonging to a known scheduled passenger operator
        if (prefix in KNOWN_PASSENGER_PREFIXES) return false
        return true
    }

    /** Human-readable service type label — shown on non-passenger cards. */
    val serviceTypeLabel: String get() = when (category) {
        ServiceCategory.PASSENGER    -> ""
        ServiceCategory.BUS          -> "Bus replacement"
        ServiceCategory.FERRY        -> "Ferry"
        ServiceCategory.FREIGHT      -> "Freight working"
        ServiceCategory.RAILTOUR     -> if (tourName.isNotEmpty()) "Railtour · $tourName" else "Railtour"
        ServiceCategory.ECS          -> "Empty Coaching Stock"
        ServiceCategory.LIGHT_ENGINE -> "Light Engine"
        ServiceCategory.SPECIAL      -> "Special working"
    }

    val serviceTypeIcon: String get() = when (category) {
        ServiceCategory.BUS          -> "🚌"
        ServiceCategory.FERRY        -> "⛴️"
        ServiceCategory.FREIGHT      -> "🚛"
        ServiceCategory.RAILTOUR     -> "🚂"
        ServiceCategory.ECS          -> "🚃"
        ServiceCategory.LIGHT_ENGINE -> "🔧"
        ServiceCategory.SPECIAL      -> "⚡"
        else                         -> ""
    }

    /** Share / clipboard text for this service. */
    fun shareText(stationName: String): String = buildString {
        append("$scheduledTime $origin → $destination")
        if (platform.isNotEmpty()) append(" · Plat $platform")
        append("\n")
        append("Status: $statusDisplay")
        if (delayMinutes > 0) append(" (+${delayMinutes} min)")
        append("\n")
        if (trainId.isNotEmpty()) append("Headcode: $trainId\n")
        if (operator.isNotEmpty()) append("Operator: $operator\n")
        if (unitAllocation != null) append("Units: ${unitAllocation.summary}\n")
        if (journeyMinutes > 0) append("Journey: ${formatDuration(journeyMinutes)}\n")
        if (tourName.isNotEmpty()) append("Tour: $tourName\n")
        append("via Train Tracker · $stationName")
    }
}

// ─── UnitAllocation ───────────────────────────────────────────────────────────

/**
 * Holds full rolling stock allocation for a service, enriched from Darwin
 * formations and the RollingStockData class database.
 */
data class UnitAllocation(
    val units: List<String>,          // e.g. ["387101", "387215"]
    val classNumbers: List<Int>,      // e.g. [387, 387]
    val className: String,            // e.g. "Class 387 Electrostar"
    val tractionType: String,         // e.g. "EMU"
    val coachCount: Int,
    val multipleUnits: Boolean = false
) {
    val summary: String get() = buildString {
        if (className.isNotEmpty()) append(className)
        if (tractionType.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(tractionType)
        }
        if (units.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(units.joinToString(" + "))
        }
        if (coachCount > 0) append(" · ${coachCount}c")
        if (multipleUnits && units.size > 1) append(" (${units.size}-unit formation)")
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
    val timeOffset: Int = 0,
    val nrccMessages: List<String> = emptyList()
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
) : Parcelable {
    val delayMinutes: Int by lazy {
        if (isCancelled) 0
        else when {
            // Train has actually arrived/departed — compare actual vs scheduled
            at.isNotEmpty() && at != "On time" -> minuteDelay(st, at)
            // Not yet arrived — compare estimate vs scheduled
            et.isNotEmpty() && et != "On time" -> minuteDelay(st, et)
            else -> 0
        }
    }
    val isDelayed: Boolean by lazy { !isCancelled && delayMinutes > 0 }

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte(),
        parcel.readInt().let { if (it == -1) null else it },
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(locationName)
        parcel.writeString(crs)
        parcel.writeString(st)
        parcel.writeString(et)
        parcel.writeString(at)
        parcel.writeByte(if (isCancelled) 1 else 0)
        parcel.writeInt(length ?: -1)
        parcel.writeString(platform)
        parcel.writeByte(if (isPassing) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CallingPoint> {
        override fun createFromParcel(parcel: Parcel) = CallingPoint(parcel)
        override fun newArray(size: Int) = arrayOfNulls<CallingPoint>(size)
    }
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
    val adhocAlerts: String = "",
    // CIF enrichment — set after headcode lookup against CIF DB
    val isPassingAtStation: Boolean = false,
    val cifPreviousCallingPoints: List<CallingPoint> = emptyList(),
    val cifSubsequentCallingPoints: List<CallingPoint> = emptyList()
) {
    val allCallingPoints: List<CallingPoint> get() =
        previousCallingPoints + subsequentCallingPoints

    val journeyDurationMinutes: Int get() {
        val first = allCallingPoints.firstOrNull()?.st ?: return 0
        val last  = allCallingPoints.lastOrNull()?.st  ?: return 0
        return durationMinutes(first, last)
    }
}

// ─── RecentStation ────────────────────────────────────────────────────────────

data class RecentStation(val crs: String, val name: String, val timestamp: Long = System.currentTimeMillis())

// ─── HspRecord ────────────────────────────────────────────────────────────────

/** One historical performance record from the HSP API. */
data class HspRecord(
    val serviceId: String,          // RID or headcode
    val operatorCode: String,
    val scheduledDeparture: String,
    val actualDeparture: String,
    val delayMinutes: Int,
    val cancelled: Boolean,
    val date: String                // yyyy-MM-dd
)

data class HspSummary(
    val headcode: String,
    val operatorCode: String,
    val totalRuns: Int,
    val onTimeCount: Int,           // within 5 minutes
    val punctualityPct: Int         // 0–100
)
// ─── TrustMovement / TrustActivation ─────────────────────────────────────────
// Previously defined in TrustClient.kt — moved here as that file was deleted
// when Kafka consumers moved server-side.

data class TrustMovement(
    val type: String,           // "ARRIVAL" or "DEPARTURE"
    val trainUid: String,
    val trainId: String,
    val headcode: String,
    val stanox: String,
    val crs: String,
    val scheduledTime: String,
    val actualTime: String,
    val platform: String,
    val isCancelled: Boolean,
    val delayMinutes: Int = 0
)

data class TrustActivation(
    val trainUid: String,
    val headcode: String,
    val originDep: String       // scheduled departure time at origin e.g. "0743"
)