package com.traintracker

import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiState {
    object Idle    : UiState()
    object Loading : UiState()
    data class Success(val board: BoardResult) : UiState()
    data class Error(val message: String)      : UiState()
}

sealed class DetailState {
    object Idle    : DetailState()
    object Loading : DetailState()
    data class Success(val details: ServiceDetails) : DetailState()
    data class Error(val message: String)           : DetailState()
}

data class DetailLiveState(
    val departureActuals: Map<String, String> = emptyMap(),
    val arrivalActuals:   Map<String, String> = emptyMap(),
    val platforms:        Map<String, String> = emptyMap(),
    val cancelledStops:   Set<String>         = emptySet(),
    val isCancelled:      Boolean             = false,
    val latestDelayMins:  Int                 = 0,
    val vsptAmended:      Boolean             = false,
    val estimatedEtas:    Map<String, String> = emptyMap()
)

class MainViewModel : ViewModel() {

    private val kb       = KnowledgebaseService()
    private val hsp      = HspService()
    private val hspBoard = HspHistoricBoard(hsp)
    private val server   = ServerApiClient()

    private val _uiState     = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _detailState = MutableStateFlow<DetailState>(DetailState.Idle)
    val detailState: StateFlow<DetailState> = _detailState.asStateFlow()

    private val _incidents = MutableStateFlow<List<KbIncident>>(emptyList())
    val incidents: StateFlow<List<KbIncident>> = _incidents.asStateFlow()

    private val _nsi = MutableStateFlow<List<KbNsiEntry>>(emptyList())
    val nsi: StateFlow<List<KbNsiEntry>> = _nsi.asStateFlow()

    private val _tocDetails = MutableStateFlow<Map<String, KbTocEntry>>(emptyMap())
    val tocDetails: StateFlow<Map<String, KbTocEntry>> = _tocDetails.asStateFlow()

    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    private val _filterCallingAt    = MutableStateFlow("")
    val filterCallingAt: StateFlow<String> = _filterCallingAt.asStateFlow()

    private val _filterOperator     = MutableStateFlow("")
    val filterOperator: StateFlow<String> = _filterOperator.asStateFlow()

    private val _timeOffset         = MutableStateFlow(0)
    val timeOffset: StateFlow<Int> = _timeOffset.asStateFlow()

    private val _availableOperators = MutableStateFlow<List<String>>(emptyList())
    val availableOperators: StateFlow<List<String>> = _availableOperators.asStateFlow()

    private val _headcodeFilter     = MutableStateFlow("")
    val headcodeFilter: StateFlow<String> = _headcodeFilter.asStateFlow()

    private val _trustConnected = MutableStateFlow(false)
    val trustConnected: StateFlow<Boolean> = _trustConnected.asStateFlow()

    // ── Historic date ─────────────────────────────────────────────────────────
    // null = live (today); "YYYY-MM-DD" = historic board from HSP
    private val _historicDate = MutableStateFlow<String?>(null)
    val historicDate: StateFlow<String?> = _historicDate.asStateFlow()

    /** Set to a past date (YYYY-MM-DD) for an HSP historic board, or null for live. */
    fun setHistoricDate(date: String?) {
        _historicDate.value = date
        _timeOffset.value = 0
        cifDepartures.clear()
        cifArrivals.clear()
        if (lastCrs.isNotEmpty()) doFetch()
    }

    data class TrainLocation(
        val headcode: String, val stationName: String, val crs: String,
        val time: String, val eventType: String, val delayMinutes: Int = 0
    )
    private val _lastKnownLocations = MutableStateFlow<Map<String, TrainLocation>>(emptyMap())
    val lastKnownLocations: StateFlow<Map<String, TrainLocation>> = _lastKnownLocations.asStateFlow()

    private val _journeyActuals = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val journeyActuals: StateFlow<Map<String, Map<String, String>>> = _journeyActuals.asStateFlow()

    private val _recentStations = MutableStateFlow<List<RecentStation>>(emptyList())
    val recentStations: StateFlow<List<RecentStation>> = _recentStations.asStateFlow()

    private val _hspSummary = MutableStateFlow<HspSummary?>(null)
    val hspSummary: StateFlow<HspSummary?> = _hspSummary.asStateFlow()

    private val _detailFormation = MutableStateFlow<DarwinFormation?>(null)
    val detailFormation: StateFlow<DarwinFormation?> = _detailFormation.asStateFlow()

    private val _detailLiveState = MutableStateFlow(DetailLiveState())
    val detailLiveState: StateFlow<DetailLiveState> = _detailLiveState.asStateFlow()

    /** Consist data fetched from the server allocation endpoint. Null until loaded. */
    private val _serverAllocation = MutableStateFlow<AllocationInfo?>(null)
    val serverAllocation: StateFlow<AllocationInfo?> = _serverAllocation.asStateFlow()

    private var detailTrainId = ""
    private var detailCallingPoints: List<CallingPoint> = emptyList()

    private var lastCrs       = ""
    private var lastBoardType = BoardType.DEPARTURES
    private var autoRefreshJob: Job? = null

    private val cifDepartures     = LinkedHashMap<String, TrainService>() // server overlay cache
    private val cifArrivals       = LinkedHashMap<String, TrainService>() // server overlay cache
    private val formationCache    = HashMap<String, DarwinFormation>()
    private val headcodeFromTrust = HashMap<String, String>()
    private val hspCache          = HashMap<String, HspSummary>()

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(Constants.COUNTDOWN_REFRESH_SECS * 1000)
                _tick.value++
            }
        }
    }

    // ── Board fetching ────────────────────────────────────────────────────────

    fun fetchBoard(crs: String, boardType: BoardType) {
        if (crs.isBlank()) { _uiState.value = UiState.Error("Enter a station name or code"); return }
        val newCrs = crs.trim().uppercase()
        if (newCrs != lastCrs) { cifDepartures.clear(); cifArrivals.clear() }
        lastCrs       = newCrs
        lastBoardType = boardType
        doFetch()
        startAutoRefresh()
        fetchIncidents()
        fetchNsi()
        fetchTocDetails()
    }

    fun refresh() { if (lastCrs.isNotEmpty()) doFetch() }

    private fun doFetch() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                when {
                    _historicDate.value != null ->
                        withContext(Dispatchers.IO) { buildHistoricBoard() }
                    server.isEnabled            ->
                        buildServerBoard()   // already suspend, runs on IO internally
                    else                        ->
                        UiState.Error("No server configured")
                }
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Historic board (past dates via HSP) ───────────────────────────────────

    private fun buildHistoricBoard(): UiState {
        val date        = _historicDate.value ?: return UiState.Error("No date set")
        val stationName = StationData.findByCrs(lastCrs)?.name ?: lastCrs
        val offset      = _timeOffset.value

        // Build HHmm window from current offset
        val fromHHmm = offsetToHHmm(offset, 0)
        val toHHmm   = offsetToHHmm(offset, 120)

        if (!hsp.isAvailable) {
            return UiState.Error("HSP credentials not configured — historic boards unavailable")
        }

        val services: List<TrainService> = when (lastBoardType) {
            BoardType.DEPARTURES -> hspBoard.getDepartures(lastCrs, date, fromHHmm, toHHmm)
            BoardType.ARRIVALS   -> hspBoard.getArrivals(lastCrs, date, fromHHmm, toHHmm)
            BoardType.ALL        -> hspBoard.getArrivals(lastCrs, date, "0000", "2359")
        }

        _availableOperators.value = services.map { it.operator }.filter { it.isNotEmpty() }.distinct().sorted()

        val board = BoardResult(
            stationName  = stationName,
            crs          = lastCrs,
            services     = services,
            generatedAt  = HspHistoricBoard.formatDate(date),
            boardType    = lastBoardType,
            nrccMessages = emptyList()
        )
        return UiState.Success(applyFilters(board))
    }

    /** Convert minute offset from now + addMins to HHmm string for HSP queries. */
    private fun offsetToHHmm(offsetMins: Int, addMins: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, offsetMins + addMins)
        return "%02d%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    // ── Server board (when SERVER_BASE_URL is set) ────────────────────────────

    private suspend fun buildServerBoard(): UiState {
        val stationName   = StationData.findByCrs(lastCrs)?.name ?: lastCrs
        val windowMinutes = 120 + _timeOffset.value.coerceAtLeast(0)
        val offset        = _timeOffset.value

        val serverDeps = if (lastBoardType == BoardType.DEPARTURES)
            server.getDepartures(lastCrs, windowMinutes, offset) else emptyList()
        val serverArrs = when (lastBoardType) {
            BoardType.ARRIVALS -> server.getArrivals(lastCrs, windowMinutes, offset)
            BoardType.ALL      -> server.getAllServices(lastCrs, windowMinutes, offset)
            else               -> emptyList()
        }

        if (lastBoardType != BoardType.ARRIVALS) {
            val newDeps = LinkedHashMap<String, TrainService>()
            for (s in serverDeps) {
                val key = "${s.headcode.uppercase()}-${s.scheduledTime}"
                val existing = cifDepartures[key]
                newDeps[key] = serverServiceToTrain(s, BoardType.DEPARTURES, existing)
            }
            cifDepartures.entries
                .filter { it.value.serviceID.isEmpty() && !newDeps.containsKey(it.key) }
                .forEach { newDeps[it.key] = it.value }
            cifDepartures.clear()
            cifDepartures.putAll(newDeps)
        }

        if (lastBoardType != BoardType.DEPARTURES) {
            val newArrs = LinkedHashMap<String, TrainService>()
            for (s in serverArrs) {
                val key = "${s.headcode.uppercase()}-arr-${s.scheduledTime}"
                val existing = cifArrivals[key]
                newArrs[key] = serverServiceToTrain(s, BoardType.ARRIVALS, existing)
            }
            cifArrivals.entries
                .filter { it.value.serviceID.isEmpty() && !newArrs.containsKey(it.key) }
                .forEach { newArrs[it.key] = it.value }
            cifArrivals.clear()
            cifArrivals.putAll(newArrs)
        }

        val services = when (lastBoardType) {
            BoardType.DEPARTURES -> cifDepartures.values.sortedBy { midnightAwareSortKey(it.scheduledTime) }
            BoardType.ARRIVALS   -> cifArrivals.values.sortedBy { midnightAwareSortKey(it.scheduledTime) }
            BoardType.ALL        -> (cifDepartures.values + cifArrivals.values)
                .sortedBy { midnightAwareSortKey(it.scheduledTime) }
        }

        _availableOperators.value = services.map { it.operator }.filter { it.isNotEmpty() }.distinct().sorted()

        val board = BoardResult(
            stationName  = stationName, crs = lastCrs,
            services     = services, generatedAt = "",
            boardType    = lastBoardType, nrccMessages = emptyList()
        )
        return UiState.Success(applyFilters(board))
    }

    private fun serverServiceToTrain(
        s: ServerService,
        bType: BoardType,
        existing: TrainService?
    ): TrainService {
        val h            = s.headcode.uppercase()
        val operatorName = TocData.get(s.atocCode)?.name ?: ""

        val originName = s.originCrs?.let { StationData.findByCrs(it)?.name ?: CorpusData.nameFromTiploc(it) }
            ?: CorpusData.nameFromTiploc(s.originTiploc)
            ?: s.originCrs ?: ""

        val destName = s.destCrs?.let { StationData.findByCrs(it)?.name ?: CorpusData.nameFromTiploc(it) }
            ?: CorpusData.nameFromTiploc(s.destTiploc)
            ?: s.destCrs ?: ""

        val formation    = formationCache[h.uppercase()] ?: formationCache[s.uid.uppercase()]
        val units        = formation?.units ?: existing?.units ?: emptyList()
        val coaches      = formation?.coachCount ?: existing?.darwinCoachCount ?: 0

        // TRUST actual times from server overlay
        val actualDep = if (bType != BoardType.ARRIVALS && s.actualTime.isNotEmpty())
            s.actualTime else existing?.actualDeparture ?: ""
        val actualArr = if (bType == BoardType.ARRIVALS && s.actualTime.isNotEmpty())
            s.actualTime else existing?.actualArrival ?: ""
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
            cancelReason     = existing?.cancelReason ?: "",
            delayReason      = existing?.delayReason ?: "",
            serviceID        = s.uid,
            trainId          = existing?.trainId?.ifEmpty { h } ?: h,
            boardType        = bType,
            serviceType      = "train",
            isPassenger      = true,
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
            tourName         = RailTourData.tourNameFor(h, s.uid, s.atocCode),
            hasAlert         = existing?.hasAlert ?: false
        )
    }

    private fun applyFilters(board: BoardResult): BoardResult {
        var result = board
        val op = _filterOperator.value
        if (op.isNotEmpty()) result = result.copy(services = result.services.filter { it.operator == op })
        val hc = _headcodeFilter.value.uppercase().trim()
        if (hc.isNotEmpty()) result = result.copy(services = result.services.filter {
            it.trainId.uppercase().contains(hc)
        })
        return result
    }

    fun setHeadcodeFilter(headcode: String) {
        _headcodeFilter.value = headcode.uppercase().trim()
        if (lastCrs.isNotEmpty()) doFetch()
    }
    fun clearHeadcodeFilter() { _headcodeFilter.value = ""; if (lastCrs.isNotEmpty()) doFetch() }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(Constants.AUTO_REFRESH_SECS * 1000)
                // Don't auto-refresh historic boards — data won't change
                if (lastCrs.isNotEmpty() && _historicDate.value == null) doFetch()
            }
        }
    }

    // ── Calling points ────────────────────────────────────────────────────────

    suspend fun fetchCallingPointsFromServer(uid: String, atCrs: String): CallingPointsResult? =
        withContext(Dispatchers.IO) { server.getCallingPoints(uid, atCrs) }

    /**
     * Fetch calling points for a service. In historic mode, uses HSP serviceDetails.
     * Returns list of CallingPoint, or null if fetching from server (caller uses server path).
     */
    suspend fun fetchHistoricCallingPoints(rid: String): List<CallingPoint>? {
        if (_historicDate.value == null) return null   // live mode — caller handles
        return withContext(Dispatchers.IO) { hspBoard.getCallingPoints(rid) }
    }

    // ── KB Incidents ──────────────────────────────────────────────────────────

    fun fetchIncidents() {
        viewModelScope.launch {
            try { _incidents.value = withContext(Dispatchers.IO) { kb.getIncidents() } } catch (_: Exception) {}
        }
    }
    fun incidentsForOperator(operatorCode: String): List<KbIncident> {
        if (operatorCode.isEmpty()) return _incidents.value
        return _incidents.value.filter { it.operators.isEmpty() || operatorCode in it.operators }
    }

    // ── KB NSI ────────────────────────────────────────────────────────────────

    private fun fetchTocDetails() {
        if (_tocDetails.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val list = kb.getToc()
            if (list.isNotEmpty()) {
                _tocDetails.value = list.associateBy { it.code.uppercase() }
                Log.d("MainViewModel", "TOC details loaded: ${list.size} entries")
            }
        }
    }

    fun fetchNsi() {
        viewModelScope.launch {
            try { _nsi.value = withContext(Dispatchers.IO) { kb.getNsi() } } catch (_: Exception) {}
            try { withContext(Dispatchers.IO) { kb.preloadStations() } } catch (_: Exception) {}
        }
    }

    fun stationInfo(crs: String): KbStation? = kb.getStation(crs)

    fun nsiForOperator(operatorCode: String): KbNsiEntry? {
        if (operatorCode.isEmpty()) return null
        return _nsi.value.firstOrNull { it.tocCode.equals(operatorCode, ignoreCase = true) }
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    fun setCallingAtFilter(crs: String) { _filterCallingAt.value = crs.uppercase().trim(); if (lastCrs.isNotEmpty()) doFetch() }
    fun clearCallingAtFilter()          { _filterCallingAt.value = ""; if (lastCrs.isNotEmpty()) doFetch() }
    fun setOperatorFilter(op: String)   { _filterOperator.value = op; if (lastCrs.isNotEmpty()) doFetch() }
    fun clearOperatorFilter()           { _filterOperator.value = ""; if (lastCrs.isNotEmpty()) doFetch() }

    fun setTimeOffset(offsetMinutes: Int) {
        _timeOffset.value = offsetMinutes.coerceIn(-480, 1439)   // up to 8h back, 24h forward
        cifDepartures.clear(); cifArrivals.clear()
        if (lastCrs.isNotEmpty()) doFetch()
    }

    // ── Recent stations ───────────────────────────────────────────────────────

    fun recordRecentStation(crs: String, name: String) {
        val current = _recentStations.value.toMutableList()
        current.removeAll { it.crs == crs }
        current.add(0, RecentStation(crs, name))
        _recentStations.value = current.take(8)
    }
    fun clearRecentStation(crs: String) {
        _recentStations.value = _recentStations.value.filter { it.crs != crs }
    }

    // ── Detail ────────────────────────────────────────────────────────────────

    fun emitCifServiceDetails(details: ServiceDetails) {
        _detailState.value = DetailState.Success(details)
    }

    fun fetchCifServiceDetails(uid: String, atCrs: String, fallback: ServiceDetails) {
        _detailState.value = DetailState.Loading
        viewModelScope.launch(Dispatchers.IO) {

            // Historic mode — fetch calling points from HSP
            if (_historicDate.value != null) {
                val callingPoints = hspBoard.getCallingPoints(uid)
                val atIndex = callingPoints.indexOfFirst { it.crs == atCrs }
                val prev    = if (atIndex > 0) callingPoints.subList(0, atIndex) else emptyList()
                val subseq  = if (atIndex >= 0 && atIndex < callingPoints.size - 1)
                    callingPoints.subList(atIndex + 1, callingPoints.size)
                else callingPoints
                _detailState.value = DetailState.Success(
                    fallback.copy(
                        previousCallingPoints   = prev.ifEmpty { fallback.previousCallingPoints },
                        subsequentCallingPoints = subseq.ifEmpty { fallback.subsequentCallingPoints }
                    )
                )
                return@launch
            }

            // Live mode — try server first, fall back to CIF
            val result = if (server.isEnabled) {
                val serverResult = server.getCallingPoints(uid, atCrs)
                if (serverResult != null) {
                    // Overlay TRUST actuals onto calling points
                    val movements = server.getMovementsForHeadcode(
                        fallback.trainId.ifEmpty { uid }
                    )
                    val actualsByDep = movements.filter { it.type == "DEPARTURE" }
                        .associateBy { it.crs }
                    val actualsByArr = movements.filter { it.type == "ARRIVAL" }
                        .associateBy { it.crs }

                    fun overlayActuals(points: List<CallingPoint>) = points.map { cp ->
                        val dep    = actualsByDep[cp.crs]
                        val arr    = actualsByArr[cp.crs]
                        val actual = dep?.actualTime?.ifEmpty { arr?.actualTime ?: "" }
                            ?: arr?.actualTime ?: ""
                        if (actual.isNotEmpty()) cp.copy(at = actual, et = actual) else cp
                    }

                    fallback.copy(
                        previousCallingPoints   = overlayActuals(
                            serverResult.previous.ifEmpty { fallback.previousCallingPoints }
                        ),
                        subsequentCallingPoints = overlayActuals(
                            serverResult.subsequent.ifEmpty { fallback.subsequentCallingPoints }
                        )
                    )
                } else fallback
            } else {
                fallback
            }
            _detailState.value = DetailState.Success(result)
        }
    }

    fun clearDetailState() { _detailState.value = DetailState.Idle }

    // ── Detail screen tracking ────────────────────────────────────────────────

    fun trackDetailService(trainId: String, callingPoints: List<CallingPoint> = emptyList()) {
        detailTrainId       = trainId.uppercase()
        detailCallingPoints = callingPoints
        _detailLiveState.value = DetailLiveState()
        val cached = formationCache[detailTrainId]
        if (cached != null) _detailFormation.value = cached
    }

    fun clearDetailTracking() {
        detailTrainId       = ""
        detailCallingPoints = emptyList()
        _detailFormation.value = null
        _detailLiveState.value = DetailLiveState()
        _hspSummary.value = null
        _serverAllocation.value = null
    }

    // ── HSP punctuality ───────────────────────────────────────────────────────

    fun fetchHspForService(serviceId: String, headcode: String, fromCrs: String, toCrs: String) {
        if (!hsp.isAvailable) return
        val cacheKey = "$headcode-$fromCrs-$toCrs"
        hspCache[cacheKey]?.let { updateServiceHsp(serviceId, it); return }
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) { hsp.getServiceMetrics(fromCrs, toCrs, headcode) }
                if (summary != null) { hspCache[cacheKey] = summary; updateServiceHsp(serviceId, summary) }
            } catch (_: Exception) {}
        }
    }

    fun fetchHspForDetail(headcode: String, fromCrs: String, toCrs: String) {
        if (!hsp.isAvailable || headcode.isEmpty() || fromCrs.isEmpty()) return
        val cacheKey = "$headcode-$fromCrs-$toCrs"
        hspCache[cacheKey]?.let { _hspSummary.value = it; return }
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) { hsp.getServiceMetrics(fromCrs, toCrs, headcode) }
                if (summary != null) { hspCache[cacheKey] = summary; _hspSummary.value = summary }
            } catch (_: Exception) {}
        }
    }

    private fun updateServiceHsp(serviceId: String, summary: HspSummary) {
        val current = _uiState.value as? UiState.Success ?: return
        val updated = current.board.services.map { svc ->
            if (svc.serviceID == serviceId)
                svc.copy(punctualityPercent = summary.punctualityPct, hspSampleSize = summary.totalRuns)
            else svc
        }
        if (updated != current.board.services)
            _uiState.value = current.copy(board = current.board.copy(services = updated))
    }

    // ── Server allocation (consist) for service detail ────────────────────────

    /**
     * Fetches consist data from GET /api/allocation/{headcode}?date={date}.
     *
     * The endpoint only accepts 4-char headcodes. When multiple services share
     * the same headcode the server returns an array; [uid] (the CIF UID, e.g.
     * "W5781406") disambiguates by matching the element whose coreId ends with it.
     *
     * Results are emitted to [serverAllocation]. A Darwin formation update
     * ([detailFormation]) always takes precedence over the allocation endpoint.
     */
    fun fetchServerAllocation(headcode: String, date: String, uid: String = "") {
        if (!server.isEnabled || headcode.isEmpty()) {
            Log.w("MainViewModel", "fetchServerAllocation: skipped — enabled=${server.isEnabled}, headcode='$headcode'")
            return
        }
        if (_detailFormation.value != null) {
            Log.d("MainViewModel", "fetchServerAllocation: skipped — detailFormation already set")
            return
        }
        Log.d("MainViewModel", "fetchServerAllocation: requesting headcode=$headcode uid=$uid date=$date")
        viewModelScope.launch {
            try {
                val info = server.getAllocation(headcode, date, uid)
                if (info != null) {
                    Log.d("MainViewModel", "fetchServerAllocation: emitting units=${info.units} coaches=${info.coachCount}")
                    _serverAllocation.value = info
                } else {
                    Log.w("MainViewModel", "fetchServerAllocation: got null for $headcode on $date")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "fetchServerAllocation: ${e.message}", e)
            }
        }
    }

    // ── Server TRUST polling for service detail ──────────────────────────────

    /**
     * Polls the server's TRUST endpoint for a specific headcode every 30s.
     * Feeds movements into applyTrustMovement so calling point delays update
     * in real time while the service detail screen is open.
     * Automatically cancelled when the ViewModel is cleared.
     */
    fun startServerTrustPolling(headcode: String) {
        if (!server.isEnabled || headcode.isEmpty()) return
        viewModelScope.launch {
            server.movements.onEach { applyTrustMovement(it) }.launchIn(viewModelScope)
            server.pollTrustForHeadcode(headcode)
        }
    }

    // ── Allocation feed ───────────────────────────────────────────────────────

    private fun applyFormation(f: DarwinFormation) {
        fun shouldUpdate(existing: DarwinFormation?) = existing == null || f.units.isNotEmpty() || existing.units.isEmpty()
        // Store under uppercase keys so lookups are case-insensitive
        if (f.trainId.isNotEmpty() && shouldUpdate(formationCache[f.trainId.uppercase()])) formationCache[f.trainId.uppercase()] = f
        if (f.uid.isNotEmpty()     && shouldUpdate(formationCache[f.uid.uppercase()]))     formationCache[f.uid.uppercase()]     = f
        if (detailTrainId.isNotEmpty() &&
            (f.trainId.uppercase() == detailTrainId || f.uid.uppercase() == detailTrainId)) {
            _detailFormation.value = f
        }
        val current = _uiState.value as? UiState.Success ?: return
        val updated = current.board.services.map { service ->
            // Look up by headcode first, then full schedule UID — both stored uppercase
            val formation = formationCache[service.trainId.uppercase()] ?: formationCache[service.serviceID.uppercase()]
            if (formation != null) {
                val alloc = RollingStockData.toUnitAllocation(formation.units, formation.coachCount)
                service.copy(units = formation.units, darwinCoachCount = formation.coachCount,
                    rollingStockDesc = RollingStockData.describeFormation(formation.units, formation.coachCount),
                    unitAllocation = alloc)
            } else service
        }
        if (updated != current.board.services)
            _uiState.value = current.copy(board = current.board.copy(services = updated))
    }

    // ── TRUST ─────────────────────────────────────────────────────────────────

    fun attachTrustMovements(
        movements:   SharedFlow<TrustMovement>,
        activations: SharedFlow<TrustActivation>,
        connected:   SharedFlow<Boolean>
    ) {
        connected.onEach { _trustConnected.value = it }.launchIn(viewModelScope)
        movements.onEach { applyTrustMovement(it) }.launchIn(viewModelScope)
        activations.onEach { applyTrustActivation(it) }.launchIn(viewModelScope)
    }

    private fun applyTrustActivation(a: TrustActivation) {
        if (a.headcode.isEmpty()) return
        if (a.originDep.isNotEmpty()) headcodeFromTrust[a.originDep] = a.headcode
        val current = _uiState.value as? UiState.Success ?: return
        val updated = current.board.services.map { service ->
            if (service.trainId.isNotEmpty()) return@map service
            if (service.std == a.originDep) service.copy(trainId = a.headcode) else service
        }
        if (updated != current.board.services)
            _uiState.value = current.copy(board = current.board.copy(services = updated))
    }

    private fun applyTrustMovement(m: TrustMovement) {
        // Skip TRUST updates when viewing a historic board
        if (_historicDate.value != null) return

        // Know Your Train updates
        if (m.headcode.isNotEmpty() && m.crs.isNotEmpty() && m.actualTime.isNotEmpty()) {
            val stationName = StationData.findByCrs(m.crs)?.name ?: m.crs
            val locMap = _lastKnownLocations.value.toMutableMap()
            val prevDelay = locMap[m.headcode]?.delayMinutes ?: 0
            val delay = if (m.scheduledTime.isNotEmpty()) minuteDelay(m.scheduledTime, m.actualTime) else prevDelay
            locMap[m.headcode] = TrainLocation(m.headcode, stationName, m.crs, m.actualTime, m.type, delay)
            _lastKnownLocations.value = locMap
            if (m.type == "DEPARTURE") {
                val trail = _journeyActuals.value.toMutableMap()
                val stops = (trail[m.headcode] ?: linkedMapOf()).toMutableMap()
                stops[m.crs] = m.actualTime
                trail[m.headcode] = stops
                _journeyActuals.value = trail
            }
        }

        // Detail screen live overlay
        if (detailTrainId.isNotEmpty() && m.crs.isNotEmpty()) {
            val hDetail = m.headcode.ifEmpty { m.trainId }.uppercase()
            if (hDetail == detailTrainId) {
                val prev = _detailLiveState.value
                val delay = if (m.scheduledTime.isNotEmpty() && m.actualTime.isNotEmpty())
                    minuteDelay(m.scheduledTime, m.actualTime) else prev.latestDelayMins
                val newState = when (m.type) {
                    "DEPARTURE"     -> prev.copy(departureActuals = prev.departureActuals + (m.crs to m.actualTime),
                        platforms = if (m.platform.isNotEmpty()) prev.platforms + (m.crs to m.platform) else prev.platforms,
                        latestDelayMins = delay)
                    "ARRIVAL"       -> prev.copy(arrivalActuals = prev.arrivalActuals + (m.crs to m.actualTime),
                        platforms = if (m.platform.isNotEmpty()) prev.platforms + (m.crs to m.platform) else prev.platforms,
                        latestDelayMins = delay)
                    "CANCELLATION"  -> prev.copy(isCancelled = true)
                    "REINSTATEMENT" -> prev.copy(isCancelled = false)
                    else -> prev
                }
                if (newState != prev) _detailLiveState.value = newState

                val stateAfterMovement = _detailLiveState.value
                if (stateAfterMovement.latestDelayMins >= 0 && detailCallingPoints.isNotEmpty()) {
                    val etas = mutableMapOf<String, String>()
                    var pastCurrentStop = false
                    for (cp in detailCallingPoints) {
                        if (cp.crs == m.crs) { pastCurrentStop = true; continue }
                        if (!pastCurrentStop) continue
                        val sched = formatTimeFromIso(cp.st).ifEmpty { cp.st }
                        if (sched.isEmpty() || sched == "—") continue
                        etas[cp.crs] = addMinutesToTime(sched, stateAfterMovement.latestDelayMins)
                    }
                    val withEtas = stateAfterMovement.copy(estimatedEtas = etas)
                    if (withEtas != stateAfterMovement) _detailLiveState.value = withEtas
                }
            }
        }

        if (m.crs != lastCrs) return
        val current = _uiState.value as? UiState.Success ?: return

        if (m.headcode.isNotEmpty() && m.scheduledTime.isNotEmpty())
            headcodeFromTrust[m.scheduledTime] = m.headcode

        val h      = m.headcode.ifEmpty { m.trainId }.uppercase()
        val depKey = "$h-${m.scheduledTime}"
        val arrKey = "$h-arr-${m.scheduledTime}"

        val existingDep = cifDepartures[depKey]
            ?: cifDepartures.values.firstOrNull { it.std == m.scheduledTime }
        val existingArr = cifArrivals[arrKey]
            ?: cifArrivals.values.firstOrNull { it.sta == m.scheduledTime }

        fun updateMap(map: LinkedHashMap<String, TrainService>, primaryKey: String,
                      existing: TrainService?, updater: TrainService.() -> TrainService) {
            val svc = existing ?: return
            val canonicalKey = map.entries.firstOrNull { it.value === svc }?.key ?: primaryKey
            map[canonicalKey] = svc.updater()
        }

        when (m.type) {
            "DEPARTURE" -> {
                if (existingDep != null) {
                    updateMap(cifDepartures, depKey, existingDep) {
                        copy(trainId = h.ifEmpty { trainId }, platform = m.platform.ifEmpty { platform },
                            actualDeparture = m.actualTime, etd = m.actualTime.ifEmpty { etd })
                    }
                } else {
                    cifDepartures[depKey] = TrainService(
                        std = m.scheduledTime, etd = m.actualTime.ifEmpty { "On time" },
                        sta = "", eta = "On time",
                        destination = h.ifEmpty { "Special" }, origin = h.ifEmpty { "Special" },
                        platform = m.platform, operator = "", operatorCode = "",
                        isCancelled = false, serviceID = "", trainId = h,
                        boardType = BoardType.DEPARTURES, serviceType = "train", isPassenger = false,
                        actualDeparture = m.actualTime,
                        unitAllocation = RollingStockData.toUnitAllocation(listOf(h)),
                        tourName = RailTourData.tourNameFor(h, "", "")
                    )
                }
            }
            "ARRIVAL" -> {
                if (existingArr != null) {
                    updateMap(cifArrivals, arrKey, existingArr) {
                        copy(trainId = h.ifEmpty { trainId }, platform = m.platform.ifEmpty { platform },
                            actualArrival = m.actualTime, eta = m.actualTime.ifEmpty { eta })
                    }
                } else {
                    cifArrivals[arrKey] = TrainService(
                        std = "", etd = "On time",
                        sta = m.scheduledTime, eta = m.actualTime.ifEmpty { "On time" },
                        destination = h.ifEmpty { "Special" }, origin = h.ifEmpty { "Special" },
                        platform = m.platform, operator = "", operatorCode = "",
                        isCancelled = false, serviceID = "", trainId = h,
                        boardType = BoardType.ARRIVALS, serviceType = "train", isPassenger = false,
                        actualArrival = m.actualTime,
                        unitAllocation = RollingStockData.toUnitAllocation(listOf(h)),
                        tourName = RailTourData.tourNameFor(h, "", "")
                    )
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
            else -> return
        }

        val services = when (lastBoardType) {
            BoardType.DEPARTURES -> cifDepartures.values.sortedBy { midnightAwareSortKey(it.scheduledTime) }
            BoardType.ARRIVALS   -> cifArrivals.values.sortedBy { midnightAwareSortKey(it.scheduledTime) }
            BoardType.ALL        -> (cifDepartures.values + cifArrivals.values)
                .sortedBy { midnightAwareSortKey(it.scheduledTime) }
        }
        _uiState.value = current.copy(board = applyFilters(current.board.copy(services = services)))
    }
}