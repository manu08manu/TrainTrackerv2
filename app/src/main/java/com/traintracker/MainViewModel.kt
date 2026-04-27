package com.traintracker

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── UI state ──────────────────────────────────────────────────────────────────

/**
 * State of the station board screen.
 *
 *   Idle    – before any search has been performed.
 *   Loading – a network fetch is in progress.
 *   Success – board data is available and ready to display.
 *   Error   – network or configuration problem; the message string is user-facing text.
 */
sealed class UiState {
    object Idle    : UiState()
    object Loading : UiState()
    data class Success(val board: BoardResult) : UiState()
    data class Error(val message: String)      : UiState()
}

/**
 * State of the service detail screen (calling points, formation, live times).
 * Same Idle / Loading / Success / Error pattern as [UiState].
 */
sealed class DetailState {
    object Idle    : DetailState()
    object Loading : DetailState()
    data class Success(val details: ServiceDetails) : DetailState()
    data class Error(val message: String)           : DetailState()
}

/**
 * Real-time overlay for the service detail screen, built up incrementally as
 * TRUST movement events arrive over SSE (Server-Sent Events).
 *
 * All maps use CRS station codes as keys.
 *
 * @property departureActuals  Confirmed departure times, keyed by CRS.
 * @property arrivalActuals    Confirmed arrival times, keyed by CRS.
 * @property platforms         Confirmed platform numbers, keyed by CRS.
 * @property cancelledStops    Stops where this service has been cancelled.
 * @property isCancelled       True if the whole service is cancelled.
 * @property latestDelayMins   Most recent delay in whole minutes (0 = on time).
 * @property vsptAmended       True if Darwin has issued a VSP (Very Short-term Plan)
 *                             amendment changing this service's path or stops.
 * @property estimatedEtas     Projected arrival times for stops downstream of the
 *                             last known TRUST position, computed by adding
 *                             [latestDelayMins] to each stop's WTT (Working Timetable)
 *                             time.  Keyed by CRS.
 */
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

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Top-level ViewModel — survives configuration changes (e.g. screen rotation).
 *
 * Acts as an orchestrator: it holds all observable state that the UI needs,
 * and delegates the heavy lifting to focused repositories:
 *
 *   [BoardRepository] — live and historic board building, server→model mapping,
 *                        filter logic, TRUST movement overlay on the board.
 *
 *   [KbRepository]    — Knowledge Base data: incidents, NSI operator status,
 *                        TOC branding details.
 *
 * Everything else — detail screen data, HSP punctuality, rolling-stock
 * allocation, TRUST polling, coupling resolution — lives here because it
 * crosses the board/detail boundary or has no natural repository home.
 *
 * Public functions are safe to call multiple times. If a fetch is already
 * in flight the state is simply re-emitted; there is no explicit debounce.
 */
class MainViewModel : ViewModel() {

    private val server = ServerApiClient()

    // Repositories share the ViewModel's coroutine scope so their coroutines
    // are cancelled automatically when the ViewModel is cleared.
    val boardRepo = BoardRepository(server)
    val kbRepo    = KbRepository(server, viewModelScope)

    // ── Board state ───────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    /** Current state of the station board. Observed by StationBoardActivity. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Filter/operator flows live in BoardRepository but exposed here so
    // Activities can observe them through a single ViewModel reference.
    val filterCallingAt:    StateFlow<String>       = boardRepo.filterCallingAt
    val filterOperator:     StateFlow<String>       = boardRepo.filterOperator
    val availableOperators: StateFlow<List<String>> = boardRepo.availableOperators

    // KB flows — direct pass-through from KbRepository.
    val incidents:  StateFlow<List<KbIncident>>       = kbRepo.incidents
    val nsi:        StateFlow<List<KbNsiEntry>>        = kbRepo.nsi
    val tocDetails: StateFlow<Map<String, KbTocEntry>> = kbRepo.tocDetails

    // ── Detail state ──────────────────────────────────────────────────────────

    private val _detailState = MutableStateFlow<DetailState>(DetailState.Idle)
    /** Calling-point data for the currently open service detail screen. */
    val detailState: StateFlow<DetailState> = _detailState.asStateFlow()

    private val _detailLiveState = MutableStateFlow(DetailLiveState())
    /** Real-time TRUST overlay for the service detail screen. */
    val detailLiveState: StateFlow<DetailLiveState> = _detailLiveState.asStateFlow()

    private val _detailFormation = MutableStateFlow<DarwinFormation?>(null)
    /**
     * Darwin rolling-stock formation for the service in detail view.
     * Null until Darwin responds; once set, suppresses the server allocation
     * fetch (Darwin data is more authoritative).
     */
    val detailFormation: StateFlow<DarwinFormation?> = _detailFormation.asStateFlow()

    private val _serverAllocation = MutableStateFlow<AllocationInfo?>(null)
    /**
     * Rolling-stock allocation from the server's /allocation endpoint.
     * Used as a fallback when Darwin formation data hasn't arrived yet.
     */
    val serverAllocation: StateFlow<AllocationInfo?> = _serverAllocation.asStateFlow()

    private val _splitAllocation = MutableStateFlow<AllocationInfo?>(null)
    /**
     * Allocation for the *other* portion of a split/coupled service.
     * Populated when the detail screen shows a joining or dividing service.
     * See [fetchSplitAllocation].
     */
    val splitAllocation: StateFlow<AllocationInfo?> = _splitAllocation.asStateFlow()

    private val _hspSummary = MutableStateFlow<HspSummary?>(null)
    /**
     * HSP (Historic Service Performance) punctuality badge data for the
     * service in detail view.  Null until [fetchHspForDetail] completes.
     */
    val hspSummary: StateFlow<HspSummary?> = _hspSummary.asStateFlow()

    private val _couplingInfo = MutableStateFlow<CouplingInfo?>(null)
    /**
     * Resolved coupling metadata when a joined/divided service's partner UID
     * was not included in the Intent extras that launched the detail screen.
     * Populated by [fetchCoupledFromIfNeeded].
     */
    val couplingInfo: StateFlow<CouplingInfo?> = _couplingInfo.asStateFlow()

    /**
     * Metadata about the other half of a coupled or split service.
     *
     * @property uid        UID of the partner service in the CIF timetable.
     * @property headcode   4-character headcode of the partner service.
     * @property tiploc     TIPLOC where the split/join occurs.
     * @property tiplocName Human-readable station name for [tiploc].
     * @property assocType  "JOIN" or "DIVIDE" as reported by the server.
     */
    data class CouplingInfo(
        val uid: String, val headcode: String,
        val tiploc: String, val tiplocName: String, val assocType: String
    )

    // ── Misc ──────────────────────────────────────────────────────────────────

    private val _tick = MutableStateFlow(0)
    /**
     * Increments every [Constants.COUNTDOWN_REFRESH_SECS] seconds.
     * The adapter observes this to redraw "3 min" / "Due" labels without
     * triggering a network call on every tick.
     */
    val tick: StateFlow<Int> = _tick.asStateFlow()

    private val _timeOffset = MutableStateFlow(0)
    /**
     * Minute offset from now for the board query window start.
     *   0   = current time (default)
     *   60  = look one hour ahead
     *  -30  = look half an hour back
     * Clamped to [-480, 1439] (−8 hours / just before midnight).
     */
    val timeOffset: StateFlow<Int> = _timeOffset.asStateFlow()

    private val _historicDate = MutableStateFlow<String?>(null)
    /**
     * When non-null ("YYYY-MM-DD"), the board is in historic mode: data comes
     * from HSP rather than Darwin/LDB, and TRUST polling is disabled.
     * Null means live mode (today's services).
     */
    val historicDate: StateFlow<String?> = _historicDate.asStateFlow()

    private val _recentStations = MutableStateFlow<List<RecentStation>>(emptyList())
    /**
     * Up to [MAX_RECENT_STATIONS] stations the user has searched recently,
     * most-recent first.  Shown as quick-tap chips on the main screen.
     */
    val recentStations: StateFlow<List<RecentStation>> = _recentStations.asStateFlow()

    private val _trustConnected = MutableStateFlow(false)
    /** True while the SSE connection to the TRUST stream is active. */
    val trustConnected: StateFlow<Boolean> = _trustConnected.asStateFlow()

    private val _lastKnownLocations = MutableStateFlow<Map<String, TrainLocation>>(emptyMap())
    /**
     * The most recent confirmed position of each headcode seen in this session,
     * keyed by headcode string.  Used to show a "last seen at X" indicator on
     * the board for services that haven't yet reached this station.
     */
    val lastKnownLocations: StateFlow<Map<String, TrainLocation>> = _lastKnownLocations.asStateFlow()

    /**
     * A confirmed TRUST position for a headcode seen during this session.
     *
     * @property headcode      4-character train reporting number.
     * @property stationName   Human-readable name of the last reported station.
     * @property crs           CRS code of the last reported station.
     * @property time          Actual time of the movement (HH:MM).
     * @property eventType     "ARRIVAL" or "DEPARTURE".
     * @property delayMinutes  Calculated delay at this event (0 = on time).
     */
    data class TrainLocation(
        val headcode: String, val stationName: String, val crs: String,
        val time: String, val eventType: String, val delayMinutes: Int = 0
    )

    private val _nextService = MutableStateFlow("")
    /**
     * Description of the next scheduled service at an international station
     * (AMS / BXS / PBN / ROT).  Empty for domestic stations.
     * Example: "Next departure: 14:04 to Amsterdam Centraal"
     */
    val nextService: StateFlow<String> = _nextService.asStateFlow()

    private val _unitBoard     = MutableStateFlow<UiState?>(null)
    /** Board of all current services for a specific rolling-stock unit number. */
    val unitBoard: StateFlow<UiState?> = _unitBoard.asStateFlow()

    private val _headcodeBoard = MutableStateFlow<UiState?>(null)
    /** Board of all current services for a specific headcode. */
    val headcodeBoard: StateFlow<UiState?> = _headcodeBoard.asStateFlow()

    // ── Private fields ────────────────────────────────────────────────────────

    private var lastCrs       = ""
    private var lastBoardType = BoardType.DEPARTURES
    private var autoRefreshJob: Job? = null

    // These two track the service currently open in ServiceDetailActivity so
    // that incoming TRUST movements can be applied to it in real time.
    private var detailTrainId       = ""
    private var detailCallingPoints: List<CallingPoint> = emptyList()

    /**
     * HSP punctuality cache for the detail screen, keyed as
     * "{headcode}-{fromCrs}-{toCrs}".  Capped at 100 entries — large enough
     * to cover a session without unbounded growth.
     */
    private val hspCache = androidx.collection.LruCache<String, HspSummary>(100)

    /** CRS codes of Eurostar international stations. */
    private val intlCrs = setOf("AMS", "BXS", "PBN", "ROT")

    init {
        // Drive countdown labels by incrementing a tick counter every N seconds.
        // The adapter redraws "3 min" / "Due" labels without any network call.
        viewModelScope.launch {
            while (isActive) {
                delay(Constants.COUNTDOWN_REFRESH_SECS * 1_000L)
                _tick.value++
            }
        }
    }

    // ── Board ─────────────────────────────────────────────────────────────────

    /**
     * Initiates a board fetch for [crs] and starts auto-refresh.
     * Also triggers background KB data loads (incidents, NSI, TOC details).
     *
     * Clears the cached board data if the station has changed since the last call.
     *
     * @param crs       3-letter CRS station code, e.g. "EUS".
     * @param boardType DEPARTURES, ARRIVALS, or ALL.
     */
    fun fetchBoard(crs: String, boardType: BoardType) {
        if (crs.isBlank()) {
            _uiState.value = UiState.Error("Enter a station name or code")
            return
        }
        val newCrs = crs.trim().uppercase()
        if (newCrs != lastCrs) boardRepo.clearCaches()
        lastCrs       = newCrs
        lastBoardType = boardType
        doFetch()
        startAutoRefresh()
        kbRepo.fetchIncidents()
        kbRepo.fetchNsi()
        kbRepo.fetchTocDetails()
    }

    /** Manually re-fetches the board without changing the station or board type. */
    fun refresh() { if (lastCrs.isNotEmpty()) doFetch() }

    /** Internal: sets Loading state, dispatches to the appropriate board builder. */
    private fun doFetch() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                when {
                    _historicDate.value != null ->
                        boardRepo.buildHistoricBoard(lastCrs, lastBoardType, _historicDate.value!!, _timeOffset.value)
                    server.isEnabled ->
                        boardRepo.buildServerBoard(lastCrs, lastBoardType, _timeOffset.value)
                    else ->
                        UiState.Error("No server configured")
                }
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Starts (or restarts) the background auto-refresh job.
     * Refreshes every [Constants.AUTO_REFRESH_SECS] seconds while in live mode.
     * Cancelled automatically when a new station is searched or the screen closes.
     */
    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(Constants.AUTO_REFRESH_SECS * 1_000L)
                if (lastCrs.isNotEmpty() && _historicDate.value == null) doFetch()
            }
        }
    }

    // ── Historic mode ─────────────────────────────────────────────────────────

    /**
     * Switches the board between live and historic modes.
     *
     * @param date "YYYY-MM-DD" for a past date, or null to return to live mode.
     *
     * Switching mode clears both board caches and the time offset, then
     * immediately re-fetches so the UI updates without any user interaction.
     */
    fun setHistoricDate(date: String?) {
        _historicDate.value = date
        _timeOffset.value   = 0
        boardRepo.clearCaches()
        if (lastCrs.isNotEmpty()) doFetch()
    }

    // ── Filters ───────────────────────────────────────────────────────────────
    // Each setter delegates to BoardRepository (which owns filter state) then
    // triggers a re-fetch so the visible board updates immediately.

    fun setCallingAtFilter(crs: String)  { boardRepo.setCallingAtFilter(crs);     if (lastCrs.isNotEmpty()) doFetch() }
    fun clearCallingAtFilter()           { boardRepo.clearCallingAtFilter();       if (lastCrs.isNotEmpty()) doFetch() }
    fun setOperatorFilter(op: String)    { boardRepo.setOperatorFilter(op);        if (lastCrs.isNotEmpty()) doFetch() }
    fun clearOperatorFilter()            { boardRepo.clearOperatorFilter();        if (lastCrs.isNotEmpty()) doFetch() }
    fun setHeadcodeFilter(hc: String)    { boardRepo.setHeadcodeFilter(hc);        if (lastCrs.isNotEmpty()) doFetch() }
    fun clearHeadcodeFilter()            { boardRepo.clearHeadcodeFilter();        if (lastCrs.isNotEmpty()) doFetch() }

    /**
     * Shifts the board query window by [offsetMinutes] from the current time.
     * Clamped to [-480, 1439] to stay within a single calendar day.
     * Clears caches because the window change invalidates the existing services.
     */
    fun setTimeOffset(offsetMinutes: Int) {
        _timeOffset.value = offsetMinutes.coerceIn(MIN_TIME_OFFSET, MAX_TIME_OFFSET)
        boardRepo.clearCaches()
        if (lastCrs.isNotEmpty()) doFetch()
    }

    // ── Recent stations ───────────────────────────────────────────────────────

    /**
     * Records a station visit, prepending it to the recent list and trimming
     * to [MAX_RECENT_STATIONS].  Duplicate CRS entries are removed first so
     * the most recent visit always appears at position 0.
     */
    fun recordRecentStation(crs: String, name: String) {
        val list = _recentStations.value.toMutableList()
        list.removeAll { it.crs == crs }
        list.add(0, RecentStation(crs, name))
        _recentStations.value = list.take(MAX_RECENT_STATIONS)
    }

    /** Removes a specific station from the recent list (e.g. on long-press). */
    fun clearRecentStation(crs: String) {
        _recentStations.value = _recentStations.value.filter { it.crs != crs }
    }

    // ── KB passthrough ────────────────────────────────────────────────────────
    // These exist so callers can go through the ViewModel rather than reaching
    // directly into kbRepo, keeping the single-ViewModel contract intact.

    fun fetchNsi()       = kbRepo.fetchNsi()
    /** Returns the NSI status entry for the given ATOC operator code, or null. */
    fun nsiForOperator(operatorCode: String) = kbRepo.nsiForOperator(operatorCode)

    // ── Service detail ────────────────────────────────────────────────────────

    /**
     * Fetches full calling-point detail for a service and posts it to [detailState].
     *
     * In **live mode**: calls the server's calling-points endpoint for [uid].
     * If the server response lacks actual times, also fetches TRUST movements
     * for the headcode and overlays them onto the calling points.
     *
     * In **historic mode** ([historicDate] non-null): calls the HSP detail
     * endpoint instead, which returns actual times from the past date.
     *
     * @param uid      CIF UID of the service, e.g. "C12345".
     * @param atCrs    CRS of the query station — used to split calling points
     *                 into "previous" (already passed) and "subsequent" (upcoming).
     * @param fallback A [ServiceDetails] object built from the board data, used
     *                 when the server detail call fails or returns nothing new.
     */
    fun fetchCifServiceDetails(uid: String, atCrs: String, fallback: ServiceDetails) {
        _detailState.value = DetailState.Loading
        viewModelScope.launch(Dispatchers.IO) {

            if (_historicDate.value != null) {
                // Historic mode — uid is a Darwin RID in this context.
                val hspResult = server.getHspDetails(uid)
                _detailState.value = if (hspResult != null) {
                    val (prev, subs) = hspCallingPoints(hspResult, atCrs)
                    DetailState.Success(fallback.copy(
                        previousCallingPoints   = prev.ifEmpty { fallback.previousCallingPoints },
                        subsequentCallingPoints = subs.ifEmpty { fallback.subsequentCallingPoints }
                    ))
                } else {
                    DetailState.Success(fallback)  // HSP detail unavailable — show board data
                }
                return@launch
            }

            // Live mode
            val result = if (server.isEnabled) {
                val serverResult = server.getCallingPoints(uid, atCrs)
                if (serverResult != null) {
                    // Only fetch TRUST movements if the server hasn't already included actuals.
                    val serverHasActuals = (serverResult.previous + serverResult.subsequent)
                        .any { it.at.isNotEmpty() }
                    val movements = if (serverHasActuals) emptyList()
                        else server.getMovementsForHeadcode(fallback.trainId.ifEmpty { uid }, uid)

                    val depActuals = movements.filter { it.type == "DEPARTURE" }.associateBy { it.crs }
                    val arrActuals = movements.filter { it.type == "ARRIVAL"   }.associateBy { it.crs }

                    // Overlay confirmed TRUST actuals onto the calling-point list.
                    fun overlay(points: List<CallingPoint>) = points.map { cp ->
                        val actual = depActuals[cp.crs]?.actualTime?.ifEmpty { arrActuals[cp.crs]?.actualTime ?: "" }
                            ?: arrActuals[cp.crs]?.actualTime ?: ""
                        if (actual.isNotEmpty()) cp.copy(at = actual, et = actual) else cp
                    }

                    fallback.copy(
                        isCancelled = false,
                        serviceType = when (serverResult.serviceType) {
                            // Darwin reports replacement buses differently from the board API.
                            "BUS_REPLACEMENT", "COACH_REPLACEMENT" -> "bus"
                            else -> fallback.serviceType
                        },
                        previousCallingPoints   = overlay(serverResult.previous.ifEmpty  { fallback.previousCallingPoints }),
                        subsequentCallingPoints = overlay(serverResult.subsequent.ifEmpty { fallback.subsequentCallingPoints })
                    )
                } else fallback
            } else fallback

            _detailState.value = DetailState.Success(result)
        }
    }

    /**
     * Converts HSP location records into split "previous / subsequent" calling-point
     * lists relative to [atCrs].
     *
     * HSP detail locations carry actual times for a past-date service; this maps
     * them onto the same [CallingPoint] model used by the live detail screen so
     * the UI doesn't need a separate code path.
     *
     * @return Pair(previousCallingPoints, subsequentCallingPoints).
     */
    private fun hspCallingPoints(
        result: HspDetailsResult,
        atCrs: String
    ): Pair<List<CallingPoint>, List<CallingPoint>> {
        val points = result.locations.map { loc ->
            val name      = loc.name.ifEmpty { null } ?: StationData.findByCrs(loc.crs)?.name ?: loc.tiploc
            val actual    = loc.actualDep.ifEmpty { loc.actualArr }
            val scheduled = loc.scheduledDep.ifEmpty { loc.scheduledArr }
            // A stop is considered cancelled if scheduled times exist but no actual times do.
            val cancelled = loc.actualDep.isEmpty() && loc.actualArr.isEmpty() && loc.scheduledDep.isNotEmpty()
            CallingPoint(
                locationName = name,
                crs          = loc.crs,
                st           = scheduled,
                et           = if (cancelled) "Cancelled" else actual,
                at           = actual,
                isCancelled  = cancelled,
                length       = null,
                platform     = "",
                isPassing    = loc.scheduledDep.isEmpty() && loc.scheduledArr.isEmpty()
            )
        }
        val idx  = points.indexOfFirst { it.crs == atCrs }
        val prev = if (idx > 0) points.subList(0, idx) else emptyList()
        val subs = if (idx in 0 until points.size - 1) points.subList(idx + 1, points.size) else points
        return prev to subs
    }

    /** Resets the detail state to Idle (called when the detail screen is closed). */
    fun clearDetailState() { _detailState.value = DetailState.Idle }

    // ── Detail screen tracking ────────────────────────────────────────────────

    /**
     * Registers the service currently visible in the detail screen so that
     * incoming TRUST movements can be applied to its live overlay.
     *
     * Resolves [trainId] to a canonical headcode via the server (in case the
     * board held an unresolved UID rather than a headcode), then pre-populates
     * [detailFormation] from the formation cache if available.
     *
     * @param trainId       Headcode or UID of the service being viewed.
     * @param callingPoints Full ordered list of calling points — used to compute
     *                      downstream ETA estimates from the latest TRUST delay.
     */
    fun trackDetailService(trainId: String, callingPoints: List<CallingPoint> = emptyList()) {
        viewModelScope.launch {
            val resolved = try {
                server.resolveHeadcode(trainId.uppercase())
            } catch (_: Exception) { trainId.uppercase() }
            detailTrainId       = resolved
            detailCallingPoints = callingPoints
            _detailLiveState.value = DetailLiveState()
            boardRepo.formationCache.get(detailTrainId)?.let { _detailFormation.value = it }
        }
    }

    /**
     * Clears all detail tracking state.
     * Called when the detail screen is destroyed to avoid stale TRUST overlays
     * persisting into the next service that is opened.
     */
    fun clearDetailTracking() {
        detailTrainId           = ""
        detailCallingPoints     = emptyList()
        _detailFormation.value  = null
        _detailLiveState.value  = DetailLiveState()
        _hspSummary.value       = null
        _serverAllocation.value = null
        _splitAllocation.value  = null
    }

    // ── HSP punctuality ───────────────────────────────────────────────────────

    /**
     * Fetches a punctuality summary for the [headcode] between [fromCrs] and [toCrs]
     * over the past [Constants.HSP_DAYS_LOOKBACK] days and posts it to [hspSummary].
     *
     * Results are cached in [hspCache] for the session (key = "headcode-from-to")
     * so re-opening the same service doesn't re-fetch. No-ops if the server is
     * not configured or required parameters are empty.
     */
    fun fetchHspForDetail(headcode: String, fromCrs: String, toCrs: String) {
        if (!server.isEnabled || headcode.isEmpty() || fromCrs.isEmpty() || toCrs.isEmpty()) return
        val cacheKey = "$headcode-$fromCrs-$toCrs"
        hspCache.get(cacheKey)?.let { _hspSummary.value = it; return }
        viewModelScope.launch {
            try {
                val today    = java.time.LocalDate.now()
                val fromDate = today.minusDays(Constants.HSP_DAYS_LOOKBACK.toLong()).toString()
                val result   = withContext(Dispatchers.IO) {
                    server.getHspMetrics(fromCrs, toCrs, fromDate, today.toString())
                }
                if (result != null && result.services.isNotEmpty()) {
                    val totalOnTime = result.services.sumOf { it.onTime }
                    val totalRuns   = result.services.sumOf { it.total }
                    val pct         = if (totalRuns > 0) totalOnTime * 100 / totalRuns else -1
                    val summary     = HspSummary(headcode, "", totalRuns, totalOnTime, pct)
                    hspCache.put(cacheKey, summary)
                    _hspSummary.value = summary
                }
            } catch (_: Exception) {}
        }
    }

    // ── Rolling-stock allocation ──────────────────────────────────────────────

    /**
     * Fetches the expected rolling-stock allocation from the server for display
     * in the unit/coach card on the detail screen.
     *
     * Skipped if [detailFormation] is already populated (Darwin data takes
     * precedence over the server allocation endpoint).
     *
     * @param headcode 4-character headcode.
     * @param date     Service date "YYYY-MM-DD".
     * @param uid      CIF UID (optional — improves match accuracy if supplied).
     */
    fun fetchServerAllocation(headcode: String, date: String, uid: String = "") {
        if (!server.isEnabled || headcode.isEmpty() || _detailFormation.value != null) return
        viewModelScope.launch {
            try {
                server.getAllocation(headcode, date, uid)?.let { _serverAllocation.value = it }
            } catch (e: Exception) {
                Log.e("MainViewModel", "fetchServerAllocation: ${e.message}", e)
            }
        }
    }

    /**
     * Fetches the allocation for the *other* portion of a split service.
     * Used alongside the main [fetchServerAllocation] when the detail screen
     * shows a service that divides en route.
     *
     * @param uid  CIF UID of the split portion.
     * @param date Service date "YYYY-MM-DD".
     */
    fun fetchSplitAllocation(uid: String, date: String) {
        if (!server.isEnabled || uid.isEmpty()) return
        viewModelScope.launch {
            try {
                server.getAllocation("", date, uid)?.let { _splitAllocation.value = it }
            } catch (e: Exception) {
                Log.e("MainViewModel", "fetchSplitAllocation: ${e.message}", e)
            }
        }
    }

    // ── TRUST polling ─────────────────────────────────────────────────────────

    /**
     * Subscribes to the TRUST SSE stream for [headcode] and applies incoming
     * movements to both the board and the detail overlay via [applyTrustMovement].
     *
     * The stream is torn down automatically when the ViewModel is cleared.
     * No-op if the server is not configured or [headcode] is blank.
     */
    fun startServerTrustPolling(headcode: String) {
        if (!server.isEnabled || headcode.isEmpty()) return
        viewModelScope.launch {
            server.movements.onEach { applyTrustMovement(it) }.launchIn(viewModelScope)
            server.pollTrustForHeadcode(headcode)
        }
    }

    /**
     * Routes a single TRUST movement event to the relevant state it affects:
     *
     *  1. [_lastKnownLocations] — always updated for any headcode, any station.
     *  2. [_detailLiveState]    — updated if the movement is for the service
     *                             currently open in ServiceDetailActivity.
     *  3. [_uiState]            — updated via [BoardRepository.applyTrustToBoardState]
     *                             if the movement is at the currently displayed station.
     *
     * Called on every SSE event and must be fast — no network calls here.
     */
    private fun applyTrustMovement(m: TrustMovement) {
        if (_historicDate.value != null) return  // TRUST not applicable in historic mode

        // 1. Update last known location for this headcode.
        if (m.headcode.isNotEmpty() && m.crs.isNotEmpty() && m.actualTime.isNotEmpty()) {
            val stationName = StationData.findByCrs(m.crs)?.name ?: m.crs
            val prev  = _lastKnownLocations.value.toMutableMap()
            val delay = if (m.scheduledTime.isNotEmpty())
                minuteDelay(m.scheduledTime, m.actualTime)
            else prev[m.headcode]?.delayMinutes ?: 0
            prev[m.headcode] = TrainLocation(m.headcode, stationName, m.crs, m.actualTime, m.type, delay)
            _lastKnownLocations.value = prev
        }

        // 2. Apply to detail live state if this movement is for the tracked service.
        if (detailTrainId.isNotEmpty() && m.crs.isNotEmpty()) {
            val h = m.headcode.ifEmpty { m.trainId }.uppercase()
            if (h == detailTrainId) {
                val prev  = _detailLiveState.value
                val delay = if (m.scheduledTime.isNotEmpty() && m.actualTime.isNotEmpty())
                    minuteDelay(m.scheduledTime, m.actualTime)
                else prev.latestDelayMins

                val next = when (m.type) {
                    "DEPARTURE"     -> prev.copy(
                        departureActuals = prev.departureActuals + (m.crs to m.actualTime),
                        platforms        = if (m.platform.isNotEmpty()) prev.platforms + (m.crs to m.platform) else prev.platforms,
                        latestDelayMins  = delay)
                    "ARRIVAL"       -> prev.copy(
                        arrivalActuals   = prev.arrivalActuals  + (m.crs to m.actualTime),
                        platforms        = if (m.platform.isNotEmpty()) prev.platforms + (m.crs to m.platform) else prev.platforms,
                        latestDelayMins  = delay)
                    "CANCELLATION"  -> prev.copy(isCancelled = true)
                    "REINSTATEMENT" -> prev.copy(isCancelled = false)
                    else -> prev
                }
                if (next != prev) _detailLiveState.value = next

                // Propagate the confirmed delay to all stops after the current one.
                if (next.latestDelayMins >= 0 && detailCallingPoints.isNotEmpty()) {
                    val etas        = mutableMapOf<String, String>()
                    var pastCurrent = false
                    for (cp in detailCallingPoints) {
                        if (cp.crs == m.crs) { pastCurrent = true; continue }
                        if (!pastCurrent) continue
                        val sched = formatTimeFromIso(cp.st).ifEmpty { cp.st }
                        if (sched.isEmpty() || sched == "—") continue
                        etas[cp.crs] = addMinutesToTime(sched, next.latestDelayMins)
                    }
                    val withEtas = next.copy(estimatedEtas = etas)
                    if (withEtas != next) _detailLiveState.value = withEtas
                }
            }
        }

        // 3. Overlay movement onto the board caches if at the displayed station.
        val currentState = _uiState.value as? UiState.Success ?: return
        boardRepo.applyTrustToBoardState(m, lastCrs, lastBoardType, currentState)
            ?.let { _uiState.value = it }
    }

    // ── Unit board ────────────────────────────────────────────────────────────

    /**
     * Fetches all current workings for a specific rolling-stock unit number
     * (e.g. "387101") and posts the result to [unitBoard].
     *
     * @param unit       Unit number string (normalised to uppercase internally).
     * @param onNotFound Called if the server returns no services for this unit.
     */
    fun fetchUnitBoard(unit: String, onNotFound: () -> Unit) {
        if (!server.isEnabled) { onNotFound(); return }
        autoRefreshJob?.cancel()
        _unitBoard.value = UiState.Loading
        viewModelScope.launch {
            val services = try {
                server.getUnitBoard(unit.uppercase().trim())
            } catch (_: Exception) { null }
            if (services.isNullOrEmpty()) { _unitBoard.value = null; onNotFound(); return@launch }
            val trains = services.map { s ->
                boardRepo.serverServiceToTrain(s, BoardType.DEPARTURES, null).copy(units = s.units)
            }
            _unitBoard.value = UiState.Success(boardResultNow("Unit $unit", unit, trains))
        }
    }

    // ── Headcode board ────────────────────────────────────────────────────────

    /** Clears the headcode board state (called when navigating away). */
    fun clearHeadcodeBoard() { _headcodeBoard.value = null }

    /**
     * Fetches all current services sharing the given [headcode] and posts the
     * result to [headcodeBoard].  Useful for seeing all instances of a diagram
     * that runs multiple times a day under the same headcode.
     *
     * @param headcode   4-character headcode, e.g. "1A34".
     * @param onNotFound Called if the server returns no matching services.
     */
    fun fetchHeadcodeBoard(headcode: String, onNotFound: () -> Unit) {
        if (!server.isEnabled) { onNotFound(); return }
        autoRefreshJob?.cancel()
        _headcodeBoard.value = UiState.Loading
        viewModelScope.launch {
            val services = try {
                server.getHeadcodeBoard(headcode.uppercase().trim())
            } catch (_: Exception) { emptyList() }
            if (services.isNullOrEmpty()) { _headcodeBoard.value = null; onNotFound(); return@launch }
            val trains = services.map { s -> boardRepo.serverServiceToTrain(s, BoardType.DEPARTURES, null) }
            _headcodeBoard.value = UiState.Success(boardResultNow(headcode, headcode, trains))
        }
    }

    // ── International next service ────────────────────────────────────────────

    /**
     * For international Eurostar stations (AMS, BXS, PBN, ROT), fetches the
     * next scheduled service and posts a summary string to [nextService].
     *
     * These stations have very infrequent services (several hours apart) so we
     * query a 24-hour window and show the very first result.
     * No-op for domestic CRS codes.
     */
    fun fetchNextInternationalService(crs: String, boardType: BoardType) {
        if (crs !in intlCrs) return
        viewModelScope.launch {
            try {
                val services = if (boardType == BoardType.ARRIVALS)
                    server.getArrivals(crs, 1440) else server.getDepartures(crs, 1440)
                val next = services.firstOrNull()
                _nextService.value = if (next != null) {
                    val dest = next.destName?.ifEmpty { null }   ?: next.destCrs ?: ""
                    val orig = next.originName?.ifEmpty { null } ?: next.originCrs ?: ""
                    if (boardType == BoardType.ARRIVALS)
                        "Next arrival: ${next.scheduledTime} from $orig"
                    else
                        "Next departure: ${next.scheduledTime} to $dest"
                } else ""
            } catch (_: Exception) { _nextService.value = "" }
        }
    }

    // ── Coupling resolution ───────────────────────────────────────────────────

    /**
     * Resolves the UID of the coupled-from service when it wasn't included in
     * the Intent extras that launched the detail screen.
     *
     * This can happen when the board entry was built from a TRUST movement before
     * the server had returned the full association data.  We look up the headcode
     * board to find the coupling metadata, then kick off a split allocation fetch.
     *
     * @param uid         UID of the *current* service.
     * @param headcode    Headcode of the *current* service.
     * @param serviceDate "YYYY-MM-DD" — required for the allocation lookup.
     */
    fun fetchCoupledFromIfNeeded(uid: String, headcode: String, serviceDate: String) {
        if (!server.isEnabled || uid.isEmpty() || headcode.isEmpty()) return
        viewModelScope.launch {
            try {
                val match = server.getHeadcodeBoard(headcode)?.firstOrNull { it.uid == uid }
                if (match != null && match.coupledFromUid.isNotEmpty()) {
                    _couplingInfo.value = CouplingInfo(
                        uid        = match.coupledFromUid,
                        headcode   = match.coupledFromHeadcode,
                        tiploc     = match.couplingTiploc,
                        tiplocName = match.couplingTiplocName.ifEmpty { match.couplingTiploc },
                        assocType  = match.couplingAssocType
                    )
                    fetchSplitAllocation(match.coupledFromUid, serviceDate)
                }
            } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a [BoardResult] timestamped to the current time. Used by unit/headcode boards. */
    private fun boardResultNow(name: String, crs: String, services: List<TrainService>) = BoardResult(
        stationName = name, crs = crs, services = services,
        generatedAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK)
            .format(java.util.Date()),
        boardType = BoardType.DEPARTURES
    )

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /** Maximum number of recent stations to remember across searches. */
        private const val MAX_RECENT_STATIONS = 8

        /** Minimum time offset (−8 hours): the earliest a board window can look back. */
        private const val MIN_TIME_OFFSET = -480

        /**
         * Maximum time offset (+23 h 59 min expressed in minutes): keeps the window
         * within a single calendar day so HSP date arithmetic stays correct.
         */
        private const val MAX_TIME_OFFSET = 1439
    }
}
