package com.traintracker

import androidx.lifecycle.ViewModel
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

class MainViewModel : ViewModel() {

    private val api = TrainApiService()
    private val kb  = KnowledgebaseService()

    private val _uiState     = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _detailState = MutableStateFlow<DetailState>(DetailState.Idle)
    val detailState: StateFlow<DetailState> = _detailState.asStateFlow()

    // ─── Darwin connection state ───────────────────────────────────────────────
    private val _darwinConnectionState = MutableStateFlow<DarwinConnectionState>(
        DarwinConnectionState.Disconnected
    )
    val darwinConnectionState: StateFlow<DarwinConnectionState> =
        _darwinConnectionState.asStateFlow()

    // ─── KB incident state ─────────────────────────────────────────────────────
    private val _incidents = MutableStateFlow<List<KbIncident>>(emptyList())
    val incidents: StateFlow<List<KbIncident>> = _incidents.asStateFlow()

    // ─── Countdown tick (every 30s so countdowns redraw without network call) ──
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    // ─── Filter state ──────────────────────────────────────────────────────────
    private val _filterCallingAt = MutableStateFlow("")
    val filterCallingAt: StateFlow<String> = _filterCallingAt.asStateFlow()

    private val _filterOperator = MutableStateFlow("")
    val filterOperator: StateFlow<String> = _filterOperator.asStateFlow()

    private val _timeOffset = MutableStateFlow(0)
    val timeOffset: StateFlow<Int> = _timeOffset.asStateFlow()

    private val _availableOperators = MutableStateFlow<List<String>>(emptyList())
    val availableOperators: StateFlow<List<String>> = _availableOperators.asStateFlow()

    // ─── Headcode search ───────────────────────────────────────────────────────
    private val _headcodeFilter = MutableStateFlow("")
    val headcodeFilter: StateFlow<String> = _headcodeFilter.asStateFlow()

    // ─── TRUST connection state ────────────────────────────────────────────────
    private val _trustConnected = MutableStateFlow(false)
    val trustConnected: StateFlow<Boolean> = _trustConnected.asStateFlow()

    // ─── Last known location per headcode (for "Know Your Train" panel) ───────
    data class TrainLocation(
        val headcode: String,
        val stationName: String,
        val crs: String,
        val time: String,
        val eventType: String   // "DEPARTURE" | "ARRIVAL"
    )
    private val _lastKnownLocations = MutableStateFlow<Map<String, TrainLocation>>(emptyMap())
    val lastKnownLocations: StateFlow<Map<String, TrainLocation>> = _lastKnownLocations.asStateFlow()

    // ─── Recent stations ───────────────────────────────────────────────────────
    private val _recentStations = MutableStateFlow<List<RecentStation>>(emptyList())
    val recentStations: StateFlow<List<RecentStation>> = _recentStations.asStateFlow()

    private var lastCrs       = ""
    private var lastBoardType = BoardType.DEPARTURES
    private var autoRefreshJob: Job? = null
    private var incidentRefreshJob: Job? = null

    init {
        // Tick every 30 seconds for countdown refresh
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                _tick.value++
            }
        }
    }

    // ─── RTT enrichment ────────────────────────────────────────────────────────

    // ─── Board fetching ───────────────────────────────────────────────────────

    fun fetchBoard(crs: String, boardType: BoardType) {
        if (crs.isBlank()) { _uiState.value = UiState.Error("Enter a station name or code"); return }
        lastCrs       = crs.trim().uppercase()
        lastBoardType = boardType
        doFetch()
        startAutoRefresh()
        fetchIncidents()
    }

    fun refresh() { if (lastCrs.isNotEmpty()) doFetch() }

    private fun doFetch() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                val result = withContext(Dispatchers.IO) {
                    api.getBoard(
                        crs        = lastCrs,
                        boardType  = lastBoardType,
                        filterCrs  = _filterCallingAt.value.takeIf { it.isNotEmpty() },
                        timeOffset = _timeOffset.value
                    )
                }
                _availableOperators.value = result.services
                    .map { it.operator }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()

                UiState.Success(applyOperatorFilter(result))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun applyOperatorFilter(board: BoardResult): BoardResult {
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

    fun clearHeadcodeFilter() {
        _headcodeFilter.value = ""
        if (lastCrs.isNotEmpty()) doFetch()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (lastCrs.isNotEmpty()) doFetch()
            }
        }
    }

    // ─── KB Incidents ──────────────────────────────────────────────────────────

    fun fetchIncidents() {
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { kb.getIncidents() }
                _incidents.value = list
            } catch (_: Exception) { /* non-fatal — incidents are supplementary */ }
        }
    }

    /** Returns incidents relevant to the current board's operator code. */
    fun incidentsForOperator(operatorCode: String): List<KbIncident> {
        if (operatorCode.isEmpty()) return _incidents.value
        return _incidents.value.filter { it.operators.isEmpty() || operatorCode in it.operators }
    }

    // ─── Filters ───────────────────────────────────────────────────────────────

    fun setCallingAtFilter(crs: String) {
        _filterCallingAt.value = crs.uppercase().trim()
        if (lastCrs.isNotEmpty()) doFetch()
    }

    fun clearCallingAtFilter() {
        _filterCallingAt.value = ""
        if (lastCrs.isNotEmpty()) doFetch()
    }

    fun setOperatorFilter(operator: String) {
        _filterOperator.value = operator
        // Operator filter is client-side — just re-apply without network call
        val current = _uiState.value as? UiState.Success ?: run { doFetch(); return }
        _uiState.value = current.copy(board = applyOperatorFilter(
            current.board.copy(services = (_uiState.value as? UiState.Success)?.board?.services ?: emptyList())
        )).let {
            // Re-apply against full unfiltered result by triggering a fresh fetch
            doFetch()
            current  // return old state momentarily; doFetch will update
        }
    }

    fun clearOperatorFilter() {
        _filterOperator.value = ""
        if (lastCrs.isNotEmpty()) doFetch()
    }

    // ─── Recent stations ───────────────────────────────────────────────────────

    fun recordRecentStation(crs: String, name: String) {
        val current = _recentStations.value.toMutableList()
        current.removeAll { it.crs == crs }          // remove if already present
        current.add(0, RecentStation(crs, name))      // add to front
        _recentStations.value = current.take(8)       // keep last 8
    }

    fun clearRecentStation(crs: String) {
        _recentStations.value = _recentStations.value.filter { it.crs != crs }
    }

    fun setTimeOffset(offsetMinutes: Int) {
        _timeOffset.value = offsetMinutes.coerceIn(-120, 1439)
        if (lastCrs.isNotEmpty()) doFetch()
    }

    // ─── Detail ────────────────────────────────────────────────────────────────

    fun fetchServiceDetails(serviceId: String) {
        _detailState.value = DetailState.Loading
        viewModelScope.launch {
            _detailState.value = try {
                val details = withContext(Dispatchers.IO) { api.getServiceDetails(serviceId) }
                // Back-propagate journey duration and headcode to the matching board card
                if (details.journeyDurationMinutes > 0 || details.trainId.isNotEmpty()) {
                    val current = _uiState.value as? UiState.Success
                    if (current != null) {
                        val updated = current.board.services.map { svc ->
                            if (svc.serviceID == serviceId) svc.copy(
                                journeyMinutes = if (details.journeyDurationMinutes > 0 && svc.journeyMinutes == 0)
                                    details.journeyDurationMinutes else svc.journeyMinutes,
                                trainId = if (details.trainId.isNotEmpty() && svc.trainId.isEmpty())
                                    details.trainId else svc.trainId
                            ) else svc
                        }
                        if (updated != current.board.services)
                            _uiState.value = current.copy(board = current.board.copy(services = updated))
                    }
                }
                DetailState.Success(details)
            } catch (e: Exception) {
                DetailState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearDetailState() { _detailState.value = DetailState.Idle }

    // ─── Darwin Push Port integration ──────────────────────────────────────────

    fun attachDarwinUpdates(
        updates: SharedFlow<DarwinUpdate>,
        connectionState: SharedFlow<DarwinConnectionState>
    ) {
        connectionState
            .onEach { _darwinConnectionState.value = it }
            .launchIn(viewModelScope)

        updates
            .onEach { applyDarwinUpdate(it) }
            .launchIn(viewModelScope)
    }

    fun attachDarwinFormations(formations: kotlinx.coroutines.flow.SharedFlow<DarwinFormation>) {
        formations
            .onEach { applyFormation(it) }
            .launchIn(viewModelScope)
    }

    private fun applyFormation(f: DarwinFormation) {
        if (f.trainId.isNotEmpty()) formationCache[f.trainId] = f
        if (f.uid.isNotEmpty()) formationCache[f.uid] = f

        val current = _uiState.value as? UiState.Success ?: return
        val updated = current.board.services.map { service ->
            val formation = formationCache[service.trainId]
                ?: formationCache[service.serviceID.take(6)]
            if (formation != null) {
                service.copy(
                    units             = formation.units,
                    darwinCoachCount  = formation.coachCount,
                    rollingStockDesc  = RollingStockData.describeFormation(
                        formation.units, formation.coachCount)
                )
            } else service
        }
        if (updated != current.board.services) {
            _uiState.value = current.copy(board = current.board.copy(services = updated))
        }
    }

    // ─── TRUST integration ─────────────────────────────────────────────────────

    fun attachTrustMovements(
        movements:   SharedFlow<TrustMovement>,
        activations: SharedFlow<TrustActivation>,
        connected:   SharedFlow<Boolean>
    ) {
        connected
            .onEach { _trustConnected.value = it }
            .launchIn(viewModelScope)
        movements
            .onEach { applyTrustMovement(it) }
            .launchIn(viewModelScope)
        activations
            .onEach { applyTrustActivation(it) }
            .launchIn(viewModelScope)
    }

    // Freight trains seen via TRUST but not in the OpenLDBWS board
    private val freightMovements = LinkedHashMap<String, TrainService>()

    // Formation cache: headcode/uid -> DarwinFormation
    private val formationCache = HashMap<String, DarwinFormation>()

    // Headcode caches (multiple sources, all keyed by scheduled departure HH:MM)
    // Priority: Darwin > TRUST movement > TRUST activation
    private val headcodeByStd = HashMap<String, String>()          // from Darwin TS messages
    private val headcodeFromTrust = HashMap<String, String>()      // from TRUST train_id decode

    /**
     * Called for every TRUST 0001 activation message.
     * Caches the headcode against the origin departure time so we can stamp it
     * on the board card even before the train arrives at the displayed station.
     * For through-services (not originating here), the activation fires with
     * the origin time, which won't match our station's STD — that's fine,
     * it'll be filled in by the 0003 movement headcode instead.
     */
    private fun applyTrustActivation(a: TrustActivation) {
        if (a.headcode.isEmpty()) return
        // Cache by origin departure time
        if (a.originDep.isNotEmpty()) {
            headcodeFromTrust[a.originDep] = a.headcode
        }
        // Try to stamp onto board immediately if it matches a service STD
        val current = _uiState.value as? UiState.Success ?: return
        val updated = current.board.services.map { service ->
            if (service.trainId.isNotEmpty()) return@map service  // already have headcode
            if (service.std == a.originDep) {
                service.copy(trainId = a.headcode)
            } else service
        }
        if (updated != current.board.services) {
            _uiState.value = current.copy(board = current.board.copy(services = updated))
        }
    }

    private fun applyTrustMovement(m: TrustMovement) {
        // ── Record last known location for "Know Your Train" display ──────────
        if (m.headcode.isNotEmpty() && m.crs.isNotEmpty() && m.actualTime.isNotEmpty()) {
            val stationName = StationData.findByCrs(m.crs)?.name ?: m.crs
            val updated = _lastKnownLocations.value.toMutableMap()
            updated[m.headcode] = TrainLocation(
                headcode    = m.headcode,
                stationName = stationName,
                crs         = m.crs,
                time        = m.actualTime,
                eventType   = m.type
            )
            _lastKnownLocations.value = updated
        }

        if (m.crs != lastCrs) return
        val current = _uiState.value as? UiState.Success ?: return

        // Cache the headcode from the movement's train_id decode
        if (m.headcode.isNotEmpty() && m.scheduledTime.isNotEmpty()) {
            headcodeFromTrust[m.scheduledTime] = m.headcode
        }

        val matched = current.board.services.any { it.std == m.scheduledTime && !it.isFreight }

        if (matched) {
            val updated = current.board.services.map { service ->
                if (service.std != m.scheduledTime) return@map service
                // Resolve best headcode: existing > Darwin cache > TRUST decode
                val resolvedHc = when {
                    service.trainId.isNotEmpty()                -> service.trainId
                    headcodeByStd.containsKey(m.scheduledTime) -> headcodeByStd[m.scheduledTime]!!
                    m.headcode.isNotEmpty()                     -> m.headcode
                    else                                        -> service.trainId
                }
                when (m.type) {
                    "DEPARTURE"     -> service.copy(
                        actualDeparture = m.actualTime.ifEmpty { service.actualDeparture },
                        platform        = m.platform.ifEmpty { service.platform },
                        trainId         = resolvedHc
                    )
                    "ARRIVAL"       -> service.copy(trainId = resolvedHc)
                    "CANCELLATION"  -> service.copy(isCancelled = true, trainId = resolvedHc)
                    "REINSTATEMENT" -> service.copy(isCancelled = false, trainId = resolvedHc)
                    else            -> service
                }
            }
            if (updated != current.board.services) {
                _uiState.value = current.copy(board = current.board.copy(services = updated))
            }
        } else if (m.type == "DEPARTURE" || m.type == "ARRIVAL") {
            val key = "${m.trainId}-${m.scheduledTime}"
            // Classify using headcode (cleaner than raw trainId)
            val h = m.headcode.ifEmpty { m.trainId }.uppercase()
            val syntheticServiceType = when {
                h.length >= 2 && h[0] in "67"           -> "freight"
                h.length >= 2 && h[0] == '5' && h.length > 1 && h[1] == 'Z' -> "railtour"
                h.length >= 2 && h[0] == '1' && h.length > 1 && h[1] in "ZX" -> "railtour"
                h.length >= 2 && h[0] == '0' && h.length > 1 && h[1] in "ZX" -> "railtour"
                h.length >= 2 && h[0] == '5'            -> "ecs"
                h.length >= 2 && h[0] == '0'            -> "lightengine"
                else                                     -> "special"
            }
            val displayId = m.headcode.ifEmpty { m.trainId }.ifEmpty { "Special working" }
            val freightService = TrainService(
                std         = m.scheduledTime,
                etd         = m.actualTime.ifEmpty { "On time" },
                sta         = m.scheduledTime,
                eta         = m.actualTime.ifEmpty { "On time" },
                destination = displayId,
                origin      = displayId,
                platform    = m.platform,
                operator    = "", operatorCode = "",
                isCancelled = false,
                serviceID   = "",
                trainId     = m.headcode.ifEmpty { m.trainId },
                boardType   = lastBoardType,
                serviceType = syntheticServiceType,
                isPassenger = false,
                actualDeparture = if (m.type == "DEPARTURE") m.actualTime else ""
            )
            freightMovements[key] = freightService
            val merged = (current.board.services + freightMovements.values)
                .sortedBy { it.scheduledTime }
            _uiState.value = current.copy(board = current.board.copy(services = merged))
        } else if (m.type == "CANCELLATION") {
            val key = freightMovements.keys.firstOrNull { it.endsWith("-${m.scheduledTime}") }
            if (key != null) {
                freightMovements[key] = freightMovements[key]!!.copy(isCancelled = true)
                val merged = (current.board.services.filter { !it.isFreight } + freightMovements.values)
                    .sortedBy { it.scheduledTime }
                _uiState.value = current.copy(board = current.board.copy(services = merged))
            }
        }
    }

    // ─── Darwin update ─────────────────────────────────────────────────────────

    private fun applyDarwinUpdate(update: DarwinUpdate) {
        // Cache headcode whenever Darwin provides one — subsequent TS messages
        // for the same service often omit <TrainId>, so we persist it by std time.
        if (update.headcode.isNotEmpty() && update.scheduledDeparture.isNotEmpty()) {
            headcodeByStd[update.scheduledDeparture] = update.headcode
        }

        val current = _uiState.value as? UiState.Success ?: return
        val updated = current.board.services.map { service ->
            val matches = update.scheduledDeparture.isNotEmpty() &&
                    service.std == update.scheduledDeparture
            if (!matches) return@map service

            // Resolve: from this update -> cache -> existing value on service
            val resolvedHeadcode = update.headcode.ifEmpty {
                headcodeByStd[update.scheduledDeparture] ?: service.trainId
            }

            service.copy(
                etd             = update.estimatedDeparture ?: service.etd,
                platform        = update.platform ?: service.platform,
                isCancelled     = if (update.isCancelled) true else service.isCancelled,
                cancelReason    = update.cancelReason ?: service.cancelReason,
                delayReason     = update.delayReason ?: service.delayReason,
                actualDeparture = update.actualDeparture ?: service.actualDeparture,
                trainId         = resolvedHeadcode,
                hasAlert        = (update.cancelReason ?: service.cancelReason).isNotEmpty()
                               || (update.delayReason  ?: service.delayReason).isNotEmpty()
            )
        }
        if (updated != current.board.services) {
            _uiState.value = current.copy(board = current.board.copy(services = updated))
        }
    }
}
