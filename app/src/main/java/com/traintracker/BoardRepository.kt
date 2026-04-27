package com.traintracker

import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Owns everything to do with building and maintaining the station departure/arrival board.
 *
 * ── What it does ──────────────────────────────────────────────────────────────
 *
 *  1. **Live board** — fetches services from the TrainTracker server (which in
 *     turn calls Darwin/LDB) and merges them into an in-memory cache so that
 *     TRUST movements can be overlaid without re-fetching the whole board.
 *
 *  2. **Historic board** — fetches past-date performance data from the HSP
 *     (Historic Service Performance) API via the server, used for the date-picker
 *     mode in the UI.
 *
 *  3. **Server → model mapping** — converts the server's lightweight [ServerService]
 *     DTO to the richer [TrainService] model that the UI consumes.
 *
 *  4. **Filter application** — operator, calling-at, and headcode filters are
 *     stored here and applied at query time.
 *
 *  5. **TRUST overlay** — when a TRUST movement arrives via SSE (Server-Sent
 *     Events), [applyTrustToBoardState] updates the in-memory caches and returns
 *     a new [UiState.Success] without hitting the network.
 *
 * ── Railway domain terms ──────────────────────────────────────────────────────
 *
 *  • **CRS** — Computer Reservation System code.  The 3-letter station identifier
 *    used by National Rail, e.g. "EUS" = London Euston, "BHM" = Birmingham New St.
 *
 *  • **TIPLOC** — Timing Point Location.  A more granular location code used in
 *    the CIF schedule and by TRUST; several TIPLOCs can map to one CRS station.
 *
 *  • **TOC / ATOC code** — Train Operating Company.  Two-letter code, e.g. "VT"
 *    (Avanti West Coast), "GW" (Great Western Railway), "SW" (South Western Railway).
 *
 *  • **TRUST** — Train Running and System Technology.  Network Rail's real-time
 *    train describer.  Emits movement events (arrival, departure, cancellation,
 *    reinstatement) keyed on headcode and scheduled time.
 *
 *  • **HSP** — Historic Service Performance API.  Provides punctuality statistics
 *    for past dates; used in the historic board and the per-service punctuality badge.
 *
 *  • **Darwin / LDB** — Network Rail's real-time data feed / Live Departure Boards
 *    API.  Supplies estimated times, platform numbers, and formation data.
 *
 *  • **Headcode** — The 4-character train reporting number, e.g. "1A34".
 *    Format: digit (service class) + letter (destination area) + 2 digits (sequence).
 *    ECS (Empty Coaching Stock) begins with 5; freight with 6 or 7; light engine 0.
 *
 *  • **UID** — Unique identifier for a schedule entry in the CIF (Common Interface
 *    File) timetable, e.g. "C12345".  Stable across dates; used as a join key.
 *
 *  • **RID** — RTTI ID.  Darwin's own unique service identifier per run-date;
 *    used for calling-point and formation lookups.
 */
class BoardRepository(
    private val server: ServerApiClient
) {
    // ── Filter state ──────────────────────────────────────────────────────────

    private val _filterOperator     = MutableStateFlow("")
    /** Currently active operator filter (ATOC code, e.g. "GW").  "" = no filter. */
    val filterOperator: StateFlow<String> = _filterOperator.asStateFlow()

    private val _filterCallingAt    = MutableStateFlow("")
    /** CRS of a via-station filter, e.g. "OXF" = only services calling at Oxford. */
    val filterCallingAt: StateFlow<String> = _filterCallingAt.asStateFlow()

    /** Headcode substring filter — applied in-memory at render time. */
    private val _headcodeFilter = MutableStateFlow("")

    private val _availableOperators = MutableStateFlow<List<String>>(emptyList())
    /**
     * Sorted list of operator names present in the current board result.
     * Drives the "Filter by operator" dialog options.
     */
    val availableOperators: StateFlow<List<String>> = _availableOperators.asStateFlow()

    // ── In-memory board caches ────────────────────────────────────────────────
    //
    // Keyed as: "{HEADCODE}-{SCHEDULED_TIME}"   for departures
    //           "{HEADCODE}-arr-{SCHEDULED_TIME}" for arrivals
    //
    // These maps are the source of truth for the displayed board.  Each full
    // fetch from the server merges new data in (preserving TRUST-only "ghost"
    // entries); TRUST movements update individual entries in-place.
    //
    // Named "cif" for historical reasons — they previously held CIF schedule
    // data; now populated from the server API but the merge strategy is the same.

    val cifDepartures = LinkedHashMap<String, TrainService>()
    val cifArrivals   = LinkedHashMap<String, TrainService>()

    /**
     * Cache of Darwin formation data keyed by headcode or UID (whichever was
     * used when populating).  Shared with the detail screen: the detail screen
     * populates it; the board map reads it when building [TrainService] entries.
     *
     * Capped at 50 entries — enough to cover a typical session's worth of
     * formations without unbounded memory growth.
     */
    val formationCache = LruCache<String, DarwinFormation>(50)

    // ── Filter setters ────────────────────────────────────────────────────────

    fun setOperatorFilter(op: String)   { _filterOperator.value = op }
    fun clearOperatorFilter()           { _filterOperator.value = "" }
    fun setCallingAtFilter(crs: String) { _filterCallingAt.value = crs.uppercase().trim() }
    fun clearCallingAtFilter()          { _filterCallingAt.value = "" }
    fun setHeadcodeFilter(hc: String)   { _headcodeFilter.value = hc.uppercase().trim() }
    fun clearHeadcodeFilter()           { _headcodeFilter.value = "" }

    /** Clears both board caches.  Called when the station or time offset changes. */
    fun clearCaches() { cifDepartures.clear(); cifArrivals.clear() }

    // ── Live board ────────────────────────────────────────────────────────────

    /**
     * Fetches the live departure/arrival board from the server and returns a
     * [UiState.Success] containing the filtered result.
     *
     * New server data is *merged* into [cifDepartures]/[cifArrivals] rather than
     * replacing them, so that TRUST-injected ghost services (see [ghostService])
     * that aren't yet visible in the API continue to appear on the board.
     *
     * @param crs        3-letter CRS code of the station to query.
     * @param boardType  DEPARTURES, ARRIVALS, or ALL.
     * @param timeOffset Minutes offset from now for the window start.
     *                   0 = current time, 60 = look one hour ahead, -30 = half hour ago.
     */
    suspend fun buildServerBoard(crs: String, boardType: BoardType, timeOffset: Int): UiState {
        val stationName   = StationData.findByCrs(crs)?.name ?: crs
        // International stations (Amsterdam, Brussels, Paris, Rotterdam) have
        // infrequent services so we extend the window to 4 hours.
        val windowMinutes = if (crs in INTERNATIONAL_CRS) 240 else 30

        val serverDeps = if (boardType == BoardType.DEPARTURES)
            server.getDepartures(crs, windowMinutes, timeOffset) else emptyList()
        val serverArrs = when (boardType) {
            BoardType.ARRIVALS -> server.getArrivals(crs, windowMinutes, timeOffset)
            BoardType.ALL      -> server.getAllServices(crs, windowMinutes, timeOffset)
            else               -> emptyList()
        }

        if (boardType != BoardType.ARRIVALS)   mergeDepartures(serverDeps)
        if (boardType != BoardType.DEPARTURES) mergeArrivals(serverArrs)

        val services = sortedServices(boardType)
        _availableOperators.value = services.map { it.operator }.filter { it.isNotEmpty() }.distinct().sorted()

        return UiState.Success(applyFilters(BoardResult(
            stationName  = stationName, crs = crs,
            services     = services,   generatedAt = "",
            boardType    = boardType,  nrccMessages = emptyList()
        )))
    }

    // ── Historic board ────────────────────────────────────────────────────────

    /**
     * Builds a historic board for a past date using HSP performance data.
     *
     * HSP returns aggregate punctuality statistics per service rather than
     * live Darwin data, so the resulting [TrainService] objects have no estimated
     * times, platforms, or formation — only scheduled times and punctuality %.
     *
     * This is used when the user selects a past date via the date picker, putting
     * the board into "historic mode" (indicated by a non-null historic date in
     * the ViewModel).
     *
     * @param crs        Station CRS code.
     * @param boardType  DEPARTURES or ARRIVALS (ALL not supported in historic mode).
     * @param date       ISO date string "YYYY-MM-DD".
     * @param timeOffset Minutes offset — adjusts the 2-hour HSP query window.
     */
    suspend fun buildHistoricBoard(crs: String, boardType: BoardType, date: String, timeOffset: Int): UiState {
        if (!server.isEnabled) return UiState.Error("Server not configured — historic boards unavailable")

        val stationName = StationData.findByCrs(crs)?.name ?: crs
        val fromHHmm    = offsetToHHmm(timeOffset, 0)
        val toHHmm      = offsetToHHmm(timeOffset, 120)   // 2-hour query window

        val result = withContext(Dispatchers.IO) {
            server.getHspMetrics(crs, crs, date, fromHHmm, toHHmm)
        } ?: return UiState.Error("HSP data unavailable — try again shortly")

        val services = result.services.map { s ->
            val originName = StationData.findByCrs(s.originTiploc)?.name ?: s.originTiploc
            val destName   = StationData.findByCrs(s.destTiploc)?.name   ?: s.destTiploc
            TrainService(
                std              = if (boardType == BoardType.ARRIVALS) "" else s.scheduledDep,
                etd              = "",
                sta              = if (boardType == BoardType.ARRIVALS) s.scheduledArr else "",
                eta              = "",
                destination      = destName,
                origin           = originName,
                platform         = "",          // HSP does not include platform data
                operator         = TocData.get(s.tocCode)?.name ?: "",
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
                units            = emptyList(), // not available from HSP
                darwinCoachCount = 0,
                rollingStockDesc = "",
                unitAllocation   = RollingStockData.toUnitAllocation(emptyList()),
                tourName         = "",
                hasAlert         = false,
                punctualityPercent = s.punctualityPct,
                hspSampleSize    = s.total
            )
        }.sortedBy { if (boardType == BoardType.ARRIVALS) it.sta else it.std }

        _availableOperators.value = services.map { it.operator }.filter { it.isNotEmpty() }.distinct().sorted()

        return UiState.Success(applyFilters(BoardResult(
            stationName  = stationName, crs = crs,
            services     = services,   generatedAt = formatHistoricDate(date),
            boardType    = boardType,  nrccMessages = emptyList()
        )))
    }

    // ── ServerService → TrainService ──────────────────────────────────────────

    /**
     * Converts the server's lightweight [ServerService] DTO to the full [TrainService]
     * model consumed by the UI.
     *
     * The [existing] parameter is the previous [TrainService] for the same headcode
     * (from [cifDepartures] or [cifArrivals]).  It is used to preserve fields the
     * server doesn't always return on every poll:
     *   • Rolling stock / formation (populated separately via Darwin, may not yet
     *     be present on the first fetch but arrives soon after)
     *   • TRUST-confirmed platform number
     *   • Existing cancellation or delay reason text
     *
     * Pass null for [existing] when building a service for the first time (e.g.
     * unit board, headcode board) where there is no prior cached version.
     *
     * @param s        Lightweight service object from the server API.
     * @param bType    Whether this is a departure or arrival entry.
     * @param existing Previous [TrainService] for this headcode, or null.
     */
    fun serverServiceToTrain(s: ServerService, bType: BoardType, existing: TrainService?): TrainService {
        val h = s.headcode.uppercase()

        val operatorName = TocData.get(s.atocCode)?.name ?: ""

        // Prefer rich name from server; fall back to CRS lookup; fall back to raw CRS code.
        val originName = s.originName?.ifEmpty { null }
            ?: s.originCrs?.let { StationData.findByCrs(it)?.name }
            ?: s.originCrs ?: ""
        val destName = s.destName?.ifEmpty { null }
            ?: s.destCrs?.let { StationData.findByCrs(it)?.name }
            ?: s.destCrs ?: ""

        // Prefer formation from cache (set when detail screen was opened for this
        // headcode) over whatever the existing board entry recorded.
        val formation    = formationCache.get(h) ?: formationCache.get(s.uid.uppercase())
        val units        = formation?.units ?: existing?.units ?: emptyList()
        val coaches      = formation?.coachCount ?: existing?.darwinCoachCount ?: 0

        val actualDep = if (bType != BoardType.ARRIVALS && s.actualTime.isNotEmpty()) s.actualTime else ""
        val actualArr = if (bType == BoardType.ARRIVALS && s.actualTime.isNotEmpty()) s.actualTime else ""

        // "etd" / "eta" for non-arrival / arrival sides:
        //  • If the server gave us an actual time, use it (train has already moved).
        //  • Otherwise keep the existing estimate or default "On time".
        val etd = when {
            bType == BoardType.ARRIVALS -> existing?.etd ?: "On time"
            s.actualTime.isNotEmpty()   -> s.actualTime
            else                        -> existing?.etd ?: "On time"
        }
        val eta = when {
            bType != BoardType.ARRIVALS -> existing?.eta ?: "On time"
            s.actualTime.isNotEmpty()   -> s.actualTime
            else                        -> existing?.eta ?: "On time"
        }

        return TrainService(
            std              = if (bType == BoardType.ARRIVALS) "" else s.scheduledTime,
            etd              = etd,
            sta              = if (bType == BoardType.ARRIVALS) s.scheduledTime else "",
            eta              = eta,
            destination      = destName.ifEmpty { s.destCrs ?: s.destTiploc.ifEmpty { h } },
            origin           = originName.ifEmpty { s.originCrs ?: s.originTiploc.ifEmpty { h } },
            platform         = existing?.platform ?: s.platform ?: "",
            operator         = operatorName,
            operatorCode     = s.atocCode,
            isCancelled      = s.isCancelled || (existing?.isCancelled ?: false),
            cancelReason     = s.cancelReason.ifEmpty { existing?.cancelReason ?: "" },
            delayReason      = existing?.delayReason ?: "",
            serviceID        = s.uid,
            trainId          = existing?.trainId?.ifEmpty { h } ?: h,
            boardType        = bType,
            // 0B / 0C headcode prefixes indicate a bus replacement service.
            serviceType      = if (h.startsWith("0B") || h.startsWith("0C")) "bus" else "train",
            isPassenger      = true,
            // isPass = service passes through this station without a scheduled stop.
            isServicePassing = s.isPass,
            actualDeparture  = actualDep,
            actualArrival    = actualArr,
            units            = units,
            darwinCoachCount = coaches,
            rollingStockDesc = if (formation != null)
                RollingStockData.describeFormation(units, coaches)
            else existing?.rollingStockDesc ?: "",
            unitAllocation   = if (units.isNotEmpty())
                RollingStockData.toUnitAllocation(units, coaches)
            else RollingStockData.toUnitAllocation(listOf(h)),
            tourName            = RailTourData.tourNameFor(h, s.uid, s.atocCode),
            hasAlert            = s.hasAlert || s.isCancelled || existing?.hasAlert ?: false,
            splitTiploc         = s.splitTiploc,
            splitTiplocName     = s.splitTiplocName,
            splitToHeadcode     = s.splitToHeadcode,
            splitToUid          = s.splitToUid,
            splitToDestName     = s.splitToDestName,
            couplingTiploc      = s.couplingTiploc,
            couplingTiplocName  = s.couplingTiplocName,
            coupledFromUid      = s.coupledFromUid,
            coupledFromHeadcode = s.coupledFromHeadcode,
            couplingAssocType   = s.couplingAssocType,
            formsUid            = s.formsUid,
            formsHeadcode       = s.formsHeadcode
        )
    }

    // ── TRUST board overlay ───────────────────────────────────────────────────

    /**
     * Applies a single TRUST movement event to the in-memory board caches and
     * returns an updated [UiState.Success] if the board changed, or null if the
     * movement was irrelevant to the currently displayed station.
     *
     * TRUST movements arrive via SSE from the server and are processed without
     * a network fetch, keeping the board live between scheduled auto-refreshes.
     *
     * Movement types handled:
     *   • DEPARTURE / ARRIVAL — update actual time, platform, headcode; create
     *     a ghost entry if the service isn't yet in the cache.
     *   • CANCELLATION / REINSTATEMENT — flip [TrainService.isCancelled].
     *
     * @param m            The incoming TRUST movement.
     * @param currentCrs   CRS of the station currently displayed; movements at
     *                     other stations are ignored.
     * @param boardType    Current board type, used to select which cache to sort.
     * @param currentState The current [UiState.Success] to base the update on.
     * @return Updated state, or null if the movement didn't affect this board.
     */
    fun applyTrustToBoardState(
        m: TrustMovement,
        currentCrs: String,
        boardType: BoardType,
        currentState: UiState.Success
    ): UiState.Success? {
        if (m.crs != currentCrs) return null

        val h      = m.headcode.ifEmpty { m.trainId }.uppercase()
        val depKey = "$h-${m.scheduledTime}"
        val arrKey = "$h-arr-${m.scheduledTime}"

        // Try exact key first; fall back to scheduled-time-only match for services
        // whose headcode wasn't known when the cache entry was created.
        val existingDep = cifDepartures[depKey]
            ?: cifDepartures.values.firstOrNull { it.std == m.scheduledTime }
        val existingArr = cifArrivals[arrKey]
            ?: cifArrivals.values.firstOrNull { it.sta == m.scheduledTime }

        when (m.type) {
            "DEPARTURE" -> {
                if (existingDep != null) {
                    updateMap(cifDepartures, depKey, existingDep) {
                        copy(trainId = h.ifEmpty { trainId },
                             platform = m.platform.ifEmpty { platform },
                             actualDeparture = m.actualTime,
                             etd = m.actualTime.ifEmpty { etd })
                    }
                } else {
                    // Ghost service: TRUST knows about a departure that the server
                    // hasn't surfaced yet (e.g. a late-running service now departing).
                    cifDepartures[depKey] = ghostService(m, h, BoardType.DEPARTURES)
                }
            }
            "ARRIVAL" -> {
                if (existingArr != null) {
                    updateMap(cifArrivals, arrKey, existingArr) {
                        copy(trainId = h.ifEmpty { trainId },
                             platform = m.platform.ifEmpty { platform },
                             actualArrival = m.actualTime,
                             eta = m.actualTime.ifEmpty { eta })
                    }
                } else {
                    cifArrivals[arrKey] = ghostService(m, h, BoardType.ARRIVALS)
                }
            }
            "CANCELLATION" -> {
                updateMap(cifDepartures, depKey, existingDep) { copy(isCancelled = true) }
                updateMap(cifArrivals,   arrKey, existingArr) { copy(isCancelled = true) }
            }
            "REINSTATEMENT" -> {
                updateMap(cifDepartures, depKey, existingDep) { copy(isCancelled = false) }
                updateMap(cifArrivals,   arrKey, existingArr) { copy(isCancelled = false) }
            }
            else -> return null
        }

        val services = sortedServices(boardType)
        return currentState.copy(board = applyFilters(currentState.board.copy(services = services)))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Applies the current operator and headcode filters to [board] and returns
     * the filtered copy.  Called after every fetch and after every TRUST update.
     */
    fun applyFilters(board: BoardResult): BoardResult {
        var result = board
        val op = _filterOperator.value
        if (op.isNotEmpty()) result = result.copy(services = result.services.filter { it.operator == op })
        val hc = _headcodeFilter.value
        if (hc.isNotEmpty()) result = result.copy(services = result.services.filter {
            it.trainId.uppercase().contains(hc)
        })
        return result
    }

    /**
     * Returns services from the appropriate cache, sorted by [midnightAwareSortKey]
     * so that post-midnight services on a late-night board sort correctly.
     */
    fun sortedServices(boardType: BoardType): List<TrainService> = when (boardType) {
        BoardType.DEPARTURES -> cifDepartures.values.sortedBy { midnightAwareSortKey(it.scheduledTime) }
        BoardType.ARRIVALS   -> cifArrivals.values.sortedBy   { midnightAwareSortKey(it.scheduledTime) }
        BoardType.ALL        -> (cifDepartures.values + cifArrivals.values)
            .sortedBy { midnightAwareSortKey(it.scheduledTime) }
    }

    /**
     * Merges a fresh list of server departure services into [cifDepartures].
     *
     * For each new service, the existing cache entry (if any) is passed as the
     * `existing` argument to [serverServiceToTrain] so that TRUST-confirmed fields
     * (platform, actuals) are preserved across polls.
     *
     * Ghost services (entries with an empty [TrainService.serviceID], created
     * by TRUST movements for services not yet in the server API) are kept if the
     * fresh server response doesn't include a matching key for them.
     */
    private fun mergeDepartures(serverDeps: List<ServerService>) {
        val newDeps = LinkedHashMap<String, TrainService>()
        for (s in serverDeps) {
            val key = "${s.headcode.uppercase()}-${s.scheduledTime}"
            newDeps[key] = serverServiceToTrain(s, BoardType.DEPARTURES, cifDepartures[key])
        }
        cifDepartures.entries
            .filter { it.value.serviceID.isEmpty() && !newDeps.containsKey(it.key) }
            .forEach { newDeps[it.key] = it.value }   // preserve ghost services
        cifDepartures.clear()
        cifDepartures.putAll(newDeps)
    }

    /** Mirrors [mergeDepartures] for the arrivals cache. */
    private fun mergeArrivals(serverArrs: List<ServerService>) {
        val newArrs = LinkedHashMap<String, TrainService>()
        for (s in serverArrs) {
            val key = "${s.headcode.uppercase()}-arr-${s.scheduledTime}"
            newArrs[key] = serverServiceToTrain(s, BoardType.ARRIVALS, cifArrivals[key])
        }
        cifArrivals.entries
            .filter { it.value.serviceID.isEmpty() && !newArrs.containsKey(it.key) }
            .forEach { newArrs[it.key] = it.value }
        cifArrivals.clear()
        cifArrivals.putAll(newArrs)
    }

    /**
     * Creates a minimal "ghost" [TrainService] from a raw TRUST movement.
     *
     * Ghost services exist when TRUST reports a movement for a headcode that
     * isn't yet in the server's Darwin-sourced board response (e.g. a heavily
     * delayed service now approaching the station, or an out-of-plan working).
     * They have a blank [TrainService.serviceID] so [mergeDepartures] knows to
     * preserve rather than overwrite them until the server catches up.
     */
    private fun ghostService(m: TrustMovement, h: String, bType: BoardType) = TrainService(
        std = if (bType == BoardType.DEPARTURES) m.scheduledTime else "",
        etd = if (bType == BoardType.DEPARTURES) m.actualTime.ifEmpty { "On time" } else "On time",
        sta = if (bType == BoardType.ARRIVALS)   m.scheduledTime else "",
        eta = if (bType == BoardType.ARRIVALS)   m.actualTime.ifEmpty { "On time" } else "On time",
        destination = h.ifEmpty { "Special" }, origin = h.ifEmpty { "Special" },
        platform    = m.platform, operator = "", operatorCode = "",
        isCancelled = false,
        serviceID   = "",       // intentionally empty — marks this as a ghost entry
        trainId     = h,
        boardType   = bType, serviceType = "train", isPassenger = false,
        actualDeparture = if (bType == BoardType.DEPARTURES) m.actualTime else "",
        actualArrival   = if (bType == BoardType.ARRIVALS)   m.actualTime else "",
        unitAllocation  = RollingStockData.toUnitAllocation(listOf(h)),
        tourName        = RailTourData.tourNameFor(h, "", "")
    )

    /**
     * Updates an existing entry in [map] in-place using [updater], preserving
     * the canonical map key even if [primaryKey] doesn't match due to the
     * scheduled-time fallback lookup in [applyTrustToBoardState].
     */
    private fun updateMap(
        map: LinkedHashMap<String, TrainService>,
        primaryKey: String,
        existing: TrainService?,
        updater: TrainService.() -> TrainService
    ) {
        val svc = existing ?: return
        val key = map.entries.firstOrNull { it.value === svc }?.key ?: primaryKey
        map[key] = svc.updater()
    }

    companion object {
        /** CRS codes of international stations served by Eurostar. */
        private val INTERNATIONAL_CRS = setOf("AMS", "BXS", "PBN", "ROT")
    }
}
