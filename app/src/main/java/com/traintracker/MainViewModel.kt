package com.traintracker

import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.collection.LruCache
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

    private val kb     = KnowledgebaseService()
    private val server = ServerApiClient()

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
    // null = live (today); "YYYY-MM-DD" = historic board from HSP via server
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

    private val cifDepartures     = LinkedHashMap<String, TrainService>()
    private val cifArrivals       = LinkedHashMap<String, TrainService>()
    // LruCache caps: formationCache at 50 entries (~last 50 headcodes seen per session),
    // hspCache at 100 route-date combinations. Both are evicted automatically when full.
    private val formationCache    = LruCache<String, DarwinFormation>(50)
    private val headcodeFromTrust = HashMap<String, String>()
    private val hspCache          = LruCache<String, HspSummary>(100)

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
                        buildHistoricBoard()
                    server.isEnabled            ->
                        buildServerBoard()
                    else                        ->
                        UiState.Error("No server configured")
                }
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Historic board (past dates via HSP through server) ────────────────────

    private suspend fun buildHistoricBoard(): UiState {
        val date        = _historicDate.value ?: return UiState.Error("No date set")
        val stationName = StationData.findByCrs(lastCrs)?.name ?: lastCrs
        val offset      = _timeOffset.value
        val fromHHmm    = offsetToHHmm(offset, 0)
        val toHHmm      = offsetToHHmm(offset, 120)

        if (!server.isEnabled) {
            return UiState.Error("Server not configured — historic boards unavailable")
        }

        val result = withContext(Dispatchers.IO) {
            server.getHspMetrics(
                fromCrs  = lastCrs,
                toCrs    = lastCrs,
                fromDate = date,
                fromTime = fromHHmm,
                toTime   = toHHmm
            )
        }

        if (result == null) {
            return UiState.Error("HSP data unavailable — try again shortly")
        }

        // Convert HspServiceMetrics → TrainService so the existing board UI works unchanged
        val services = result.services.mapNotNull { s ->
            val originCrs  = s.originTiploc
            val destCrs    = s.destTiploc
            val originName = StationData.findByCrs(originCrs)?.name ?: originCrs
            val destName   = StationData.findByCrs(destCrs)?.name ?: destCrs
            val operator   = TocData.get(s.tocCode)?.name ?: ""

            TrainService(
                std              = if (lastBoardType == BoardType.ARRIVALS) "" else s.scheduledDep,
                etd              = "",
                sta              = if (lastBoardType == BoardType.ARRIVALS) s.scheduledArr else "",
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
                boardType        = lastBoardType,
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
                hasAlert         = false,
                punctualityPercent = s.punctualityPct,
                hspSampleSize    = s.total
            )
        }.let { svcs ->
            when (lastBoardType) {
                BoardType.ARRIVALS -> svcs.sortedBy { it.sta }
                else               -> svcs.sortedBy { it.std }
            }
        }

        _availableOperators.value = services.map { it.operator }.filter { it.isNotEmpty() }.distinct().sorted()

        val board = BoardResult(
            stationName  = stationName,
            crs          = lastCrs,
            services     = services,
            generatedAt  = formatHistoricDate(date),
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

    // ── Server board (live) ───────────────────────────────────────────────────

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

        val originName = s.originCrs?.let { StationData.findByCrs(it)?.name } ?: s.originCrs ?: ""
        val destName   = s.destCrs?.let { StationData.findByCrs(it)?.name } ?: s.destCrs ?: ""

        val formation    = formationCache.get(h.uppercase()) ?: formationCache.get(s.uid.uppercase())
        val units        = formation?.units ?: existing?.units ?: emptyList()
        val coaches      = formation?.coachCount ?: existing?.darwinCoachCount ?: 0

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
            cancelReason     = s.cancelReason.ifEmpty { existing?.cancelReason ?: "" },
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
            tourName             = RailTourData.tourNameFor(h, s.uid, s.atocCode),
            hasAlert             = s.hasAlert || s.isCancelled || existing?.hasAlert ?: false,
            splitTiploc          = s.splitTiploc,
            splitTiplocName      = s.splitTiplocName,
            splitToHeadcode      = s.splitToHeadcode,
            couplingTiploc       = s.couplingTiploc,
            couplingTiplocName   = s.couplingTiplocName,
            coupledFromHeadcode  = s.coupledFromHeadcode,
            formsUid             = s.formsUid,
            formsHeadcode        = s.formsHeadcode
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

    /**
     * Attempts to locate a headcode globally (no station required).
     * Calls back [onFound] with the CRS if the server can place it,
     * or [onNotFound] if it cannot.
     */
    fun locateHeadcodeGlobally(
        headcode: String,
        onFound: (crs: String) -> Unit,
        onNotFound: () -> Unit
    ) {
        if (!server.isEnabled) { onNotFound(); return }
        viewModelScope.launch {
            // Try TRUST live location first
            val trustCrs = try {
                server.findHeadcodeStation(headcode.uppercase())
            } catch (_: Exception) { null }
            if (!trustCrs.isNullOrEmpty()) { onFound(trustCrs); return@launch }

            // Fall back to headcode board — use the origin CRS of the first service
            val services = try {
                server.getHeadcodeBoard(headcode.uppercase())
            } catch (_: Exception) { null }
            val fallbackCrs = services?.firstOrNull()?.originCrs
            if (!fallbackCrs.isNullOrEmpty()) onFound(fallbackCrs) else onNotFound()
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(Constants.AUTO_REFRESH_SECS * 1000)
                if (lastCrs.isNotEmpty() && _historicDate.value == null) doFetch()
            }
        }
    }

    // ── Calling points ────────────────────────────────────────────────────────

    // ── KB Incidents ──────────────────────────────────────────────────────────

    fun fetchIncidents() {
        viewModelScope.launch {
            try { _incidents.value = withContext(Dispatchers.IO) { kb.getIncidents() } } catch (_: Exception) {}
        }
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
        _timeOffset.value = offsetMinutes.coerceIn(-480, 1439)
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

    fun fetchCifServiceDetails(uid: String, atCrs: String, fallback: ServiceDetails) {
        _detailState.value = DetailState.Loading
        viewModelScope.launch(Dispatchers.IO) {

            // Historic mode — fetch calling points from HSP via server
            if (_historicDate.value != null) {
                val hspResult = server.getHspDetails(uid) // uid is the RID in historic mode
                if (hspResult != null) {
                    val callingPoints = hspResult.locations.map { loc ->
                        val crs  = loc.crs
                        val name = StationData.findByCrs(crs)?.name ?: loc.name.ifEmpty { loc.tiploc }
                        val actualTime = loc.actualDep.ifEmpty { loc.actualArr }
                        val schedTime  = loc.scheduledDep.ifEmpty { loc.scheduledArr }
                        val etDisplay  = when {
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
                } else {
                    // HSP details unavailable — fall back to what we already have
                    _detailState.value = DetailState.Success(fallback)
                }
                return@launch
            }

            // Live mode — try server first, fall back to CIF
            val result = if (server.isEnabled) {
                val serverResult = server.getCallingPoints(uid, atCrs)
                if (serverResult != null) {
                    val movements = server.getMovementsForHeadcode(
                        fallback.trainId.ifEmpty { uid }
                    )
                    val actualsByDep = movements.filter { it.type == "DEPARTURE" }.associateBy { it.crs }
                    val actualsByArr = movements.filter { it.type == "ARRIVAL" }.associateBy { it.crs }

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
            } else fallback
            _detailState.value = DetailState.Success(result)
        }
    }

    fun clearDetailState() { _detailState.value = DetailState.Idle }

    // ── Detail screen tracking ────────────────────────────────────────────────

    fun trackDetailService(trainId: String, callingPoints: List<CallingPoint> = emptyList()) {
        viewModelScope.launch {
            val resolved = try { server.resolveHeadcode(trainId.uppercase()) } catch (e: Exception) { trainId.uppercase() }
            detailTrainId       = resolved
            detailCallingPoints = callingPoints
            _detailLiveState.value = DetailLiveState()
            val cached = formationCache.get(detailTrainId)
            if (cached != null) _detailFormation.value = cached
        }
    }

    fun clearDetailTracking() {
        detailTrainId       = ""
        detailCallingPoints = emptyList()
        _detailFormation.value = null
        _detailLiveState.value = DetailLiveState()
        _hspSummary.value = null
        _serverAllocation.value = null
    }

    // ── HSP punctuality (via server) ──────────────────────────────────────────

    fun fetchHspForDetail(headcode: String, fromCrs: String, toCrs: String) {
        if (!server.isEnabled || headcode.isEmpty() || fromCrs.isEmpty() || toCrs.isEmpty()) return
        val cacheKey = "$headcode-$fromCrs-$toCrs"
        hspCache.get(cacheKey)?.let { _hspSummary.value = it; return }
        viewModelScope.launch {
            try {
                val today = java.time.LocalDate.now()
                val fromDate = today.minusDays(Constants.HSP_DAYS_LOOKBACK.toLong()).toString()
                val toDate   = today.toString()
                val result = withContext(Dispatchers.IO) {
                    server.getHspMetrics(fromCrs, toCrs, fromDate, toDate)
                }
                if (result != null && result.services.isNotEmpty()) {
                    val totalOnTime = result.services.sumOf { it.onTime }
                    val totalRuns   = result.services.sumOf { it.total }
                    val pct         = if (totalRuns > 0) (totalOnTime * 100 / totalRuns) else -1
                    val summary = HspSummary(
                        headcode       = headcode,
                        operatorCode   = "",
                        totalRuns      = totalRuns,
                        onTimeCount    = totalOnTime,
                        punctualityPct = pct
                    )
                    hspCache.put(cacheKey, summary)
                    _hspSummary.value = summary
                }
            } catch (_: Exception) {}
        }
    }

    // ── Server allocation (consist) for service detail ────────────────────────

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

    // ── Server TRUST polling for service detail ───────────────────────────────

    fun startServerTrustPolling(headcode: String) {
        if (!server.isEnabled || headcode.isEmpty()) return
        viewModelScope.launch {
            server.movements.onEach { applyTrustMovement(it) }.launchIn(viewModelScope)
            server.pollTrustForHeadcode(headcode)
        }
    }

    // ── TRUST ─────────────────────────────────────────────────────────────────

    private fun applyTrustMovement(m: TrustMovement) {
        if (_historicDate.value != null) return

        if (m.headcode.isNotEmpty() && m.crs.isNotEmpty() && m.actualTime.isNotEmpty()) {
            val stationName = StationData.findByCrs(m.crs)?.name ?: m.crs
            val locMap = _lastKnownLocations.value.toMutableMap()
            val prevDelay = locMap[m.headcode]?.delayMinutes ?: 0
            val delay = if (m.scheduledTime.isNotEmpty()) minuteDelay(m.scheduledTime, m.actualTime) else prevDelay
            locMap[m.headcode] = TrainLocation(m.headcode, stationName, m.crs, m.actualTime, m.type, delay)
            _lastKnownLocations.value = locMap
        }

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
    // ── Unit board ────────────────────────────────────────────────────────────

    private val _unitBoard = MutableStateFlow<UiState?>(null)
    val unitBoard: StateFlow<UiState?> = _unitBoard.asStateFlow()

    fun clearUnitBoard() { _unitBoard.value = null }

    fun fetchUnitBoard(unit: String, onNotFound: () -> Unit) {
        if (!server.isEnabled) { onNotFound(); return }
        autoRefreshJob?.cancel()
        val u = unit.uppercase().trim()
        _unitBoard.value = UiState.Loading
        viewModelScope.launch {
            val services = try { server.getUnitBoard(u) } catch (_: Exception) { null }
            if (services == null || services.isEmpty()) {
                _unitBoard.value = null
                onNotFound()
                return@launch
            }
            val trainServices = services.map { s ->
                serverServiceToTrain(s, BoardType.DEPARTURES, null).copy(
                    units = s.units
                )
            }
            _unitBoard.value = UiState.Success(
                BoardResult(
                    stationName = "Unit $u",
                    crs         = u,
                    services    = trainServices,
                    generatedAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK)
                        .format(java.util.Date()),
                    boardType   = BoardType.DEPARTURES
                )
            )
        }
    }

    // ── Headcode board ────────────────────────────────────────────────────────

    private val _headcodeBoard = MutableStateFlow<UiState?>(null)
    val headcodeBoard: StateFlow<UiState?> = _headcodeBoard.asStateFlow()

    fun clearHeadcodeBoard() {
        _headcodeBoard.value = null
        // Do not clear _unitBoard here
    }

    fun fetchHeadcodeBoard(headcode: String, onNotFound: () -> Unit) {
        if (!server.isEnabled) { onNotFound(); return }
        autoRefreshJob?.cancel()
        val h = headcode.uppercase().trim()
        _headcodeBoard.value = UiState.Loading
        viewModelScope.launch {
            val services = try { server.getHeadcodeBoard(h) } catch (_: Exception) { emptyList() }
            if (services == null || services.isEmpty()) {
                _headcodeBoard.value = null
                onNotFound()
                return@launch
            }
            val trainServices = services.map { s -> serverServiceToTrain(s, BoardType.DEPARTURES, null) }
            _headcodeBoard.value = UiState.Success(
                BoardResult(
                    stationName = h,
                    crs         = h,
                    services    = trainServices,
                    generatedAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK)
                        .format(java.util.Date()),
                    boardType   = BoardType.DEPARTURES
                )
            )
        }
    }
}
