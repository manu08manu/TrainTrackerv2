package com.traintracker

import java.util.Calendar

// ─── Time helpers ──────────────────────────────────────────────────────────────
// Previously scattered across Models.kt — extracted here so Models.kt stays
// focused on data classes.
//
// ── Railway time format primer ─────────────────────────────────────────────────
// The server returns times in two formats depending on the endpoint:
//   • "HH:MM"               — station board times, e.g. "09:45"
//   • "YYYY-MM-DDTHH:MM:SS" — ISO 8601, e.g. "2026-04-10T09:45:00"
//
// Some fields are also populated with the literal string "On time" (Darwin's
// estimated time when no delay is reported) or left empty ("") when unavailable.
// All functions here handle these gracefully.
// ──────────────────────────────────────────────────────────────────────────────

/** Minutes in a day — used for past-midnight arithmetic throughout this file. */
private const val MINUTES_PER_DAY = 1440

/**
 * Normalises a time string to "HH:MM".
 *
 * Accepts either ISO 8601 ("2026-04-10T09:45:00") or a bare "HH:MM" string.
 * Returns "" if the input is empty or cannot be parsed.
 */
fun formatTimeFromIso(timeStr: String): String {
    if (timeStr.isEmpty()) return ""
    if (timeStr.matches(Regex("\\d{2}:\\d{2}"))) return timeStr
    val tIndex = timeStr.indexOf('T')
    if (tIndex >= 0 && tIndex + 5 < timeStr.length) {
        val timeOnly = timeStr.substring(tIndex + 1, tIndex + 6)   // extract HH:MM
        if (timeOnly.matches(Regex("\\d{2}:\\d{2}"))) return timeOnly
    }
    return ""
}

/**
 * Converts a "HH:MM" string to total minutes since midnight.
 * Returns -1 for empty, malformed, or non-time strings (e.g. "On time").
 *
 * Internal — callers outside this file should use the higher-level helpers.
 */
internal fun timeToMinutes(t: String): Int {
    val colon = t.indexOf(':')
    if (colon < 0) return -1
    return try {
        t.substring(0, colon).toInt() * 60 + t.substring(colon + 1).toInt()
    } catch (_: NumberFormatException) { -1 }
}

/**
 * Returns a sort key (minutes-since-midnight, possibly +1440) for ordering
 * services on a station board that may span midnight.
 *
 * Problem: a board fetched at 23:50 shows services running past midnight
 * (00:05, 00:30 …).  Sorting naively by minutes places them at positions 5
 * and 30 — before 23:50 — which is wrong on the display.
 *
 * Solution: if the current local time is ≥ 20:00 AND the service time is
 * < 06:00, treat it as next-day by adding 1440.  This keeps post-midnight
 * services sorted after late-evening ones on the same board view.
 *
 * Returns 9999 for unparseable input so those entries sink to the bottom.
 */
fun midnightAwareSortKey(scheduledTime: String): Int {
    val formatted = formatTimeFromIso(scheduledTime).ifEmpty { scheduledTime }
    val mins = timeToMinutes(formatted)
    if (mins < 0) return 9999
    val cal = Calendar.getInstance()
    val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return if (nowMins >= 20 * 60 && mins < 6 * 60) mins + MINUTES_PER_DAY else mins
}

/**
 * Returns how many minutes remain until [timeStr] from the current local time.
 *
 * Handles past-midnight wrap: if the result is less than -120 minutes (the
 * service is clearly from yesterday, not imminent), adds 1440 to correct it.
 *
 * Returns [Int.MAX_VALUE] for unparseable input so such services sort to the
 * end of any countdown list.
 */
fun minutesUntilTime(timeStr: String): Int {
    val formatted = formatTimeFromIso(timeStr).ifEmpty { timeStr }
    val target = timeToMinutes(formatted)
    if (target < 0) return Int.MAX_VALUE
    val cal = Calendar.getInstance()
    val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    var diff = target - now
    if (diff < -120) diff += MINUTES_PER_DAY
    return diff
}

/**
 * Calculates delay in whole minutes between a scheduled and an estimated/actual time.
 *
 * Returns 0 for:
 *   • "On time" estimated value (Darwin's way of saying no delay)
 *   • Empty strings (data not available)
 *   • Early arrivals (negative difference) — we never show "−2 min" on the board
 *
 * Handles midnight wrap identically to [minutesUntilTime].
 */
internal fun minuteDelay(scheduled: String, estimated: String): Int {
    if (estimated == "On time" || estimated.isEmpty()) return 0
    val sch = timeToMinutes(formatTimeFromIso(scheduled).ifEmpty { scheduled })
    val est = timeToMinutes(formatTimeFromIso(estimated).ifEmpty { estimated })
    if (sch < 0 || est < 0) return 0
    var diff = est - sch
    if (diff < -120) diff += MINUTES_PER_DAY
    return if (diff > 0) diff else 0
}

/**
 * Adds [delayMins] to a "HH:MM" time string, wrapping correctly past midnight.
 *
 * Used to propagate a confirmed delay to downstream calling points: when TRUST
 * reports a departure 5 minutes late at an intermediate stop, we add 5 min to
 * the WTT (Working Timetable) times for all remaining stops on the detail screen.
 *
 * Returns [time] unchanged on parse failure — safe no-op for the caller.
 */
internal fun addMinutesToTime(time: String, delayMins: Int): String {
    if (delayMins == 0 || time.isEmpty()) return time
    val formatted = formatTimeFromIso(time).ifEmpty { time }
    val colon = formatted.indexOf(':')
    if (colon < 0) return time
    return try {
        val baseMins = formatted.substring(0, colon).toInt() * 60 +
                formatted.substring(colon + 1, colon + 3).toInt()
        val result = (baseMins + delayMins).let {
            when {
                it < 0              -> it + MINUTES_PER_DAY
                it >= MINUTES_PER_DAY -> it - MINUTES_PER_DAY
                else                -> it
            }
        }
        "%02d:%02d".format(result / 60, result % 60)
    } catch (_: Exception) { time }
}

/** Formats an integer minute duration for display, e.g. 95 → "1hr 35min". */
fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return ""
    val h = minutes / 60; val m = minutes % 60
    return when { h == 0 -> "${m}min"; m == 0 -> "${h}hr"; else -> "${h}hr ${m}min" }
}

/**
 * Elapsed minutes between two "HH:MM" strings.
 * Handles overnight journeys: 23:30 → 00:15 returns 45, not −1395.
 */
fun durationMinutes(from: String, to: String): Int {
    val a = timeToMinutes(from); val b = timeToMinutes(to)
    if (a < 0 || b < 0) return 0
    var diff = b - a
    if (diff < 0) diff += MINUTES_PER_DAY
    return diff
}

/**
 * Converts a minute offset from now into a "HHmm" string for the HSP API.
 *
 * The HSP (Historic Service Performance) endpoint takes a time window as
 * plain "HHmm" strings with no date component, e.g. "0900" to "1100".
 *
 * @param offsetMins  Base offset from now in minutes (negative = look back).
 * @param addMins     Additional minutes on top — used to specify window end.
 *
 * Example: offsetMins=0, addMins=120 → current time + 2 hours as "HHmm".
 */
internal fun offsetToHHmm(offsetMins: Int, addMins: Int): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.MINUTE, offsetMins + addMins)
    return "%02d%02d".format(
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
    )
}

/**
 * Formats an ISO date ("YYYY-MM-DD") for display in the historic board header,
 * e.g. "2026-04-07" → "Tue 07 Apr 2026". Falls back to the raw string on error.
 *
 * Includes the abbreviated day-of-week to make it immediately clear which day
 * is being viewed without the user having to work it out from the date alone.
 */
internal fun formatHistoricDate(date: String): String = try {
    val parts = date.split("-")
    val cal   = Calendar.getInstance().apply {
        set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
    }
    val dow   = arrayOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")[cal.get(Calendar.DAY_OF_WEEK) - 1]
    val month = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")[parts[1].toInt() - 1]
    "$dow ${parts[2]} $month ${parts[0]}"
} catch (_: Exception) { date }
