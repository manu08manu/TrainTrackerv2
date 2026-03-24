package com.traintracker

import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * HspHistoricBoard
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Converts HSP API data into the same [TrainService] / [CallingPoint] types
 * used by the live boards, so the existing UI renders historic boards
 * with no changes needed in adapters or activities.
 *
 * Usage (from a coroutine / background thread):
 *
 *   val board = HspHistoricBoard(hspService)
 *   val services = board.getDepartures("MAN", "2026-03-01", fromTime="0600", toTime="1200")
 *   // → List<TrainService> with actual times stamped as 'atd'/'ata' in calling points
 *
 * Limitations:
 *   • HSP from_loc == to_loc query returns all services calling at a station,
 *     but the scheduled times in serviceMetrics are the times at the query station
 *     (not origin/destination). Use gbtt_ptd for departures, gbtt_pta for arrivals.
 *   • Calling point detail requires one /serviceDetails POST per service.
 *     Fetch lazily (only when user taps a service) to respect rate limits.
 *   • HSP times are in HHmm (no colon). HspService.hhmm() converts to HH:mm.
 *   • Days parameter: "WEEKDAY" | "SATURDAY" | "SUNDAY" — must match the
 *     day-of-week of [date], otherwise HSP returns no results.
 */
class HspHistoricBoard(private val hsp: HspService) {

    companion object {
        private const val TAG = "HspHistoricBoard"

        /** Map the day of week of a YYYY-MM-DD date string to an HSP days param. */
        fun daysParam(date: String): String {
            return try {
                val parts = date.split("-")
                val cal = java.util.Calendar.getInstance().apply {
                    set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                }
                when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                    java.util.Calendar.SATURDAY -> "SATURDAY"
                    java.util.Calendar.SUNDAY   -> "SUNDAY"
                    else                        -> "WEEKDAY"
                }
            } catch (e: Exception) {
                "WEEKDAY"
            }
        }

        /** Format YYYY-MM-DD for display as "Fri 1 Mar 2026". */
        fun formatDate(date: String): String {
            return try {
                val parts = date.split("-")
                val cal = java.util.Calendar.getInstance().apply {
                    set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                }
                val day = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                val month = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                "${day[cal.get(java.util.Calendar.DAY_OF_WEEK)]} " +
                        "${cal.get(java.util.Calendar.DAY_OF_MONTH)} " +
                        "${month[cal.get(java.util.Calendar.MONTH) + 1]} " +
                        "${cal.get(java.util.Calendar.YEAR)}"
            } catch (e: Exception) {
                date
            }
        }
    }

    // ── Board queries ─────────────────────────────────────────────────────────

    /**
     * Historic departures board for [stationCrs] on [date] (YYYY-MM-DD).
     * [fromTime] / [toTime] are HHmm strings, e.g. "0600" and "1200".
     */
    fun getDepartures(
        stationCrs: String,
        date: String,
        fromTime: String = "0000",
        toTime: String = "2359"
    ): List<TrainService> {
        if (!hsp.isAvailable) return emptyList()
        val days = daysParam(date)
        val services = hsp.getStationBoard(stationCrs, date, fromTime, toTime, days)
        return services.mapNotNull { s ->
            buildTrainService(s, BoardType.DEPARTURES, stationCrs)
        }.sortedBy { it.std }
    }

    /**
     * Historic arrivals board for [stationCrs] on [date].
     */
    fun getArrivals(
        stationCrs: String,
        date: String,
        fromTime: String = "0000",
        toTime: String = "2359"
    ): List<TrainService> {
        if (!hsp.isAvailable) return emptyList()
        val days = daysParam(date)
        val services = hsp.getStationBoard(stationCrs, date, fromTime, toTime, days)
        return services.mapNotNull { s ->
            buildTrainService(s, BoardType.ARRIVALS, stationCrs)
        }.sortedBy { it.sta }
    }

    /**
     * Fetch calling points with actual times for a specific RID.
     * Call this lazily when the user taps a service card.
     * Returns empty list if HSP is unavailable or the call fails.
     */
    fun getCallingPoints(rid: String): List<CallingPoint> {
        if (!hsp.isAvailable) return emptyList()
        val detail = hsp.getServiceDetail(rid) ?: return emptyList()
        return detail.locations.map { loc ->
            val crs = CorpusData.crsFromTiploc(loc.tiploc) ?: ""
            val name = crs.let { StationData.findByCrs(it)?.name }
                ?: CorpusData.nameFromTiploc(loc.tiploc)
                ?: loc.tiploc

            val actualTime = loc.actualDep.ifEmpty { loc.actualArr }
            val schedTime  = loc.scheduledDep.ifEmpty { loc.scheduledArr }

            val etDisplay = when {
                loc.cancelReason.isNotBlank() -> "Cancelled"
                actualTime.isNotEmpty()       -> actualTime
                else                          -> ""
            }

            CallingPoint(
                locationName = name,
                crs          = crs,
                st           = schedTime,
                et           = etDisplay,
                at           = actualTime,
                isCancelled  = loc.cancelReason.isNotBlank(),
                length       = null,
                platform     = "",
                isPassing    = loc.scheduledDep.isEmpty() && loc.scheduledArr.isEmpty()
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildTrainService(
        s: HspService.HspService,
        boardType: BoardType,
        @Suppress("UNUSED_PARAMETER") atCrs: String
    ): TrainService? {
        val originCrs  = CorpusData.crsFromTiploc(s.originTiploc) ?: s.originTiploc
        val destCrs    = CorpusData.crsFromTiploc(s.destTiploc) ?: s.destTiploc
        val originName = StationData.findByCrs(originCrs)?.name
            ?: CorpusData.nameFromTiploc(s.originTiploc)
            ?: originCrs
        val destName   = StationData.findByCrs(destCrs)?.name
            ?: CorpusData.nameFromTiploc(s.destTiploc)
            ?: destCrs

        val operator = TocData.get(s.tocCode)?.name ?: ""

        return TrainService(
            std              = if (boardType == BoardType.ARRIVALS) "" else s.scheduledDep,
            etd              = "",
            sta              = if (boardType == BoardType.ARRIVALS) s.scheduledArr else "",
            eta              = "",
            destination      = destName,
            origin           = originName,
            platform         = "",
            operator         = operator,
            operatorCode     = s.tocCode,
            isCancelled      = false,
            cancelReason     = "",
            delayReason      = "",
            serviceID        = s.rid,
            trainId          = s.rid,
            boardType        = boardType,
            serviceType      = "train",
            isPassenger      = true,
            isServicePassing = false,
            actualDeparture  = "",
            actualArrival    = "",
            units            = emptyList(),
            darwinCoachCount = 0,
            rollingStockDesc = "",
            unitAllocation   = RollingStockData.toUnitAllocation(emptyList()),
            tourName         = "",
            hasAlert         = false
        )
    }

    /**
     * Compute delay in minutes between scheduled HH:mm and actual HH:mm.
     * Returns 0 if either is blank or parsing fails.
     */
    private fun computeDelayMins(scheduled: String, actual: String): Int {
        if (scheduled.isEmpty() || actual.isEmpty()) return 0
        return try {
            val (sh, sm) = scheduled.split(":").map { it.toInt() }
            val (ah, am) = actual.split(":").map { it.toInt() }
            val schedMins = sh * 60 + sm
            val actMins   = ah * 60 + am
            actMins - schedMins
        } catch (e: Exception) { 0 }
    }
}