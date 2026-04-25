package com.traintracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.traintracker.databinding.ActivityServiceDetailBinding
import kotlinx.coroutines.launch

class ServiceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServiceDetailBinding
    private val viewModel: MainViewModel by viewModels()

    private var showPassing  = false
    private var showDetailed = false
    private var callingAdapter: CallingPointAdapter? = null
    private var cachedDetails: ServiceDetails? = null
    private var queryCrs      = ""
    private var splitTiploc     = ""
    private var splitToHeadcode = ""
    private var splitToDestName = ""
    private var splitToUid         = ""
    private var coupledFromUid     = ""
    private var couplingTiplocVar  = ""
    private var coupledFromHcVar   = ""
    private var trainHeadcode = ""
    private var destCrs       = ""   // for HSP lookup

    // ── Server API — TRUST polling handled by MainViewModel.startServerTrustPolling ──

    // ── TIPLOC name resolver ─────────────────────────────────────────────────
    /** Converts a raw TIPLOC (e.g. "WDONPDS") to a readable name via CORPUS NLCDESC. */
    private fun resolveLocationName(raw: String): String =
        raw

    companion object {
        private const val EXTRA_SERVICE_ID = "service_id"
        private const val EXTRA_HEADCODE   = "headcode"
        private const val EXTRA_ORIGIN     = "origin"
        private const val EXTRA_DEST       = "destination"
        private const val EXTRA_DEST_CRS   = "dest_crs"
        private const val EXTRA_STD        = "std"
        private const val EXTRA_ETD        = "etd"
        private const val EXTRA_QUERY_CRS  = "query_crs"
        private const val EXTRA_UNITS      = "units"
        private const val EXTRA_COACHES    = "coaches"
        private const val EXTRA_PREV_CALLING_POINTS = "prev_calling_points"
        private const val EXTRA_SUBS_CALLING_POINTS = "subs_calling_points"
        private const val EXTRA_IS_CANCELLED  = "is_cancelled"
        private const val EXTRA_CANCEL_REASON  = "cancel_reason"
        private const val EXTRA_IS_PASSING          = "is_passing"
        private const val EXTRA_PLATFORM            = "platform"
        private const val EXTRA_SPLIT_TIPLOC        = "split_tiploc_crs"
        private const val EXTRA_SPLIT_HEADCODE      = "split_headcode"
        private const val EXTRA_SPLIT_DEST_NAME     = "split_dest_name"
        private const val EXTRA_SPLIT_TO_UID        = "split_to_uid"
        private const val EXTRA_COUPLING_TIPLOC     = "coupling_tiploc"
        private const val EXTRA_COUPLED_FROM_HC     = "coupled_from_headcode"
        private const val EXTRA_COUPLING_ASSOC_TYPE = "coupling_assoc_type"
        private const val EXTRA_COUPLED_FROM_UID  = "coupled_from_uid"

        fun start(ctx: Context, serviceId: String, headcode: String,
                  origin: String, destination: String, std: String, etd: String = "",
                  queryCrs: String, destCrs: String = "",
                  units: List<String> = emptyList(), coachCount: Int = 0,
                  previousCallingPoints: List<CallingPoint> = emptyList(),
                  subsequentCallingPoints: List<CallingPoint> = emptyList(),
                  isPassingService: Boolean = false,
                  platform: String = "",
                  isCancelled: Boolean = false,
                  cancelReason: String = "",
                  splitTiploc: String = "",
                  splitToHeadcode: String = "",
                  splitToDestName: String = "",
                  splitToUid: String = "",
                  couplingTiploc: String = "",
                  coupledFromHeadcode: String = "",
                  coupledFromUid: String = "",
                  couplingAssocType: String = "") {
            ctx.startActivity(Intent(ctx, ServiceDetailActivity::class.java).apply {
                putExtra(EXTRA_SERVICE_ID, serviceId)
                putExtra(EXTRA_HEADCODE,   headcode)
                putExtra(EXTRA_ORIGIN,     origin)
                putExtra(EXTRA_DEST,       destination)
                putExtra(EXTRA_DEST_CRS,   destCrs)
                putExtra(EXTRA_STD,        std)
                putExtra(EXTRA_ETD,        etd)
                putExtra(EXTRA_QUERY_CRS,  queryCrs)
                putStringArrayListExtra(EXTRA_UNITS, ArrayList(units))
                putExtra(EXTRA_COACHES,    coachCount)
                putParcelableArrayListExtra(EXTRA_PREV_CALLING_POINTS, ArrayList(previousCallingPoints))
                putParcelableArrayListExtra(EXTRA_SUBS_CALLING_POINTS, ArrayList(subsequentCallingPoints))
                putExtra(EXTRA_IS_PASSING, isPassingService)
                putExtra(EXTRA_PLATFORM,   platform)
                putExtra(EXTRA_IS_CANCELLED, isCancelled)
                putExtra(EXTRA_CANCEL_REASON, cancelReason)
                putExtra(EXTRA_SPLIT_TIPLOC, splitTiploc)
                putExtra(EXTRA_SPLIT_HEADCODE, splitToHeadcode)
                putExtra(EXTRA_SPLIT_DEST_NAME, splitToDestName)
                putExtra(EXTRA_SPLIT_TO_UID, splitToUid)
                putExtra(EXTRA_COUPLING_TIPLOC, couplingTiploc)
                putExtra(EXTRA_COUPLED_FROM_HC, coupledFromHeadcode)
                putExtra(EXTRA_COUPLED_FROM_UID, coupledFromUid)
                putExtra(EXTRA_COUPLING_ASSOC_TYPE, couplingAssocType)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServiceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareCurrentService() }

        val serviceId    = intent.getStringExtra(EXTRA_SERVICE_ID) ?: run { finish(); return }
        trainHeadcode    = intent.getStringExtra(EXTRA_HEADCODE) ?: ""
        val origin       = intent.getStringExtra(EXTRA_ORIGIN)   ?: ""
        val dest         = resolveLocationName(intent.getStringExtra(EXTRA_DEST) ?: "")
        queryCrs         = intent.getStringExtra(EXTRA_QUERY_CRS) ?: ""
        splitTiploc      = intent.getStringExtra(EXTRA_SPLIT_TIPLOC) ?: ""
        splitToHeadcode  = intent.getStringExtra(EXTRA_SPLIT_HEADCODE) ?: ""
        splitToDestName  = intent.getStringExtra(EXTRA_SPLIT_DEST_NAME) ?: ""
        splitToUid       = intent.getStringExtra(EXTRA_SPLIT_TO_UID) ?: ""
        coupledFromUid       = intent.getStringExtra(EXTRA_COUPLED_FROM_UID) ?: ""
        val rawCouplingTiploc = intent.getStringExtra(EXTRA_COUPLING_TIPLOC) ?: ""
        couplingTiplocVar    = StationData.findByCrs(rawCouplingTiploc)?.crs ?: rawCouplingTiploc
        coupledFromHcVar     = intent.getStringExtra(EXTRA_COUPLED_FROM_HC) ?: ""
        android.util.Log.d("ServiceDetail", "splitTiploc=$splitTiploc splitToHeadcode=$splitToHeadcode splitToDestName=$splitToDestName splitToUid=$splitToUid")
        destCrs          = intent.getStringExtra(EXTRA_DEST_CRS)  ?: ""
        val boardUnits   = intent.getStringArrayListExtra(EXTRA_UNITS) ?: emptyList()
        // Cap at 20 — formation data can occasionally have bad values
        val boardCoaches  = intent.getIntExtra(EXTRA_COACHES, 0).takeIf { it in 1..20 } ?: 0
        val boardPlatform = intent.getStringExtra(EXTRA_PLATFORM) ?: ""

        val prevCallingPoints = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableArrayListExtra(EXTRA_PREV_CALLING_POINTS, CallingPoint::class.java) ?: emptyList()
        else
            (@Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_PREV_CALLING_POINTS)) ?: emptyList()
        val subsCallingPoints = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableArrayListExtra(EXTRA_SUBS_CALLING_POINTS, CallingPoint::class.java) ?: emptyList()
        else
            (@Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_SUBS_CALLING_POINTS)) ?: emptyList()

        val initialDetails = ServiceDetails(
            generatedAt             = java.time.LocalDateTime.now().toString(),
            serviceType             = "train",
            trainId                 = trainHeadcode,
            rsid                    = serviceId,
            operator                = "",
            operatorCode            = "",
            isCancelled             = intent.getBooleanExtra(EXTRA_IS_CANCELLED, false),
            platform                = boardPlatform,
            origin                  = origin,
            destination             = dest,
            previousCallingPoints   = prevCallingPoints,
            subsequentCallingPoints = subsCallingPoints,
            coachCount              = boardCoaches,
            formation               = "",
            isPassingAtStation      = intent.getBooleanExtra(EXTRA_IS_PASSING, false),
            cifPreviousCallingPoints   = emptyList(),
            cifSubsequentCallingPoints = emptyList()
        )
        cachedDetails = initialDetails
        rebuildAdapter(initialDetails)
        bindUnitInfo(boardUnits, boardCoaches)
        viewModel.fetchCifServiceDetails(serviceId, queryCrs, initialDetails)

        val titleDest = if (splitToDestName.isNotEmpty()) "$dest / $splitToDestName" else dest
        binding.tvServiceTitle.text = getString(R.string.service_title_route, resolveLocationName(origin), titleDest)
        binding.rvCallingPoints.layoutManager = LinearLayoutManager(this)

        binding.chipSimple.setOnClickListener {
            if (showDetailed) {
                showPassing  = false
                showDetailed = false
                binding.chipSimple.isChecked   = true
                binding.chipDetailed.isChecked = false
                binding.tvPassingNote.visibility = View.GONE
                callingAdapter = null
                binding.rvCallingPoints.adapter = null
                cachedDetails?.let { rebuildAdapter(it) }
            }
        }
        binding.chipDetailed.setOnClickListener {
            if (!showDetailed) {
                showPassing  = true
                showDetailed = true
                binding.chipSimple.isChecked   = false
                binding.chipDetailed.isChecked = true
                binding.tvPassingNote.visibility = View.VISIBLE
                callingAdapter = null
                binding.rvCallingPoints.adapter = null
                cachedDetails?.let { rebuildAdapter(it) }
            }
        }

        // Bind to live data service

        // If destCrs wasn't passed, derive it from the last subsequent calling point
        if (destCrs.isEmpty()) {
            destCrs = subsCallingPoints.lastOrNull()?.crs ?: ""
        }

        // Start tracking this headcode for live formation + ETA updates
        if (trainHeadcode.isNotEmpty()) viewModel.trackDetailService(trainHeadcode, subsCallingPoints)

        // Poll server TRUST for all stations on this service (not just board CRS)
        if (trainHeadcode.isNotEmpty()) viewModel.startServerTrustPolling(trainHeadcode)

        // Fetch historical punctuality from HSP
        if (trainHeadcode.isNotEmpty()) {
            viewModel.fetchHspForDetail(trainHeadcode, queryCrs, destCrs)
        }

        // Fetch consist data from the server allocation endpoint.
        //
        // The endpoint only accepts a 4-char headcode. When multiple services share
        // the same headcode the server returns an array; serviceId (CIF UID) is passed
        // as the uid so ServerApiClient can pick the right element by coreId suffix.
        //
        // Date: use the *service date at origin*, not today's wall-clock date.
        // Services departing late at night (e.g. 21:46) have a serviceDate matching
        // their origin departure day even though some stops fall after midnight.
        // Detect this: if std hour < 06:00 AND current hour >= 20:00, use yesterday.
        if (trainHeadcode.isNotEmpty()) {
            val boardStd    = intent.getStringExtra(EXTRA_STD) ?: ""
            val stdFmt      = formatTimeFromIso(boardStd).ifEmpty { boardStd }
            val stdHour     = stdFmt.substringBefore(":").toIntOrNull() ?: 12
            val nowHour     = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val serviceDate = if (stdHour < 6 && nowHour >= 20) {
                java.time.LocalDate.now().minusDays(1).toString()
            } else {
                java.time.LocalDate.now().toString()
            }
            android.util.Log.d("ServiceDetail",
                "fetchServerAllocation: headcode=$trainHeadcode uid=$serviceId date=$serviceDate " +
                        "(std='$boardStd' stdHour=$stdHour nowHour=$nowHour)")
            viewModel.fetchServerAllocation(trainHeadcode, serviceDate, serviceId)
            if (splitToUid.isNotEmpty()) viewModel.fetchSplitAllocation(splitToUid, serviceDate)
            if (coupledFromUid.isNotEmpty()) viewModel.fetchSplitAllocation(coupledFromUid, serviceDate)
        }

        observeDetailState(boardUnits, boardCoaches)
        observeDetailLive()
        observeFormation()
        observeHsp()
        observeServerAllocation()
        observeSplitAllocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.clearDetailState()
        viewModel.clearDetailTracking()
    }

    // ── Detail state observer ─────────────────────────────────────────────────

    private fun observeDetailState(boardUnits: List<String>, boardCoaches: Int) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.detailState.collect { state ->
                    when (state) {
                        is DetailState.Idle    -> Unit
                        is DetailState.Loading -> {
                            binding.progressBar.visibility  = View.VISIBLE
                            binding.tvError.visibility      = View.GONE
                        }
                        is DetailState.Success -> {
                            binding.progressBar.visibility  = View.GONE
                            binding.tvError.visibility      = View.GONE
                            binding.contentGroup.visibility = View.VISIBLE
                            // Preserve board-level cancellation if server doesn't know yet
                            val boardCancelled = intent.getBooleanExtra(EXTRA_IS_CANCELLED, false)
                            val boardCancelReason = intent.getStringExtra(EXTRA_CANCEL_REASON) ?: ""
                            // Only propagate board-level cancellation if the service has no
                            // calling points at all (server has no data) — otherwise trust the
                            // per-stop isCancelled flags from the service endpoint
                            val serverHasData = state.details.cifSubsequentCallingPoints.isNotEmpty()
                                || state.details.subsequentCallingPoints.isNotEmpty()
                            var mergedDetails = if (boardCancelled && !state.details.isCancelled && !serverHasData)
                                state.details.copy(isCancelled = true) else state.details
                            // Use board cancel reason if service detail doesn't have one
                            if (mergedDetails.cancelReason.isEmpty() && boardCancelReason.isNotEmpty()) {
                                mergedDetails = mergedDetails.copy(cancelReason = boardCancelReason)
                            }
                            cachedDetails = mergedDetails
                            if (trainHeadcode.isEmpty()) {
                                trainHeadcode = mergedDetails.trainId
                                viewModel.trackDetailService(trainHeadcode,
                                    mergedDetails.cifSubsequentCallingPoints
                                        .ifEmpty { mergedDetails.subsequentCallingPoints })
                                viewModel.fetchHspForDetail(trainHeadcode, queryCrs, destCrs)
                            }
                            bindHeader(mergedDetails, boardUnits, boardCoaches)
                            rebuildAdapter(mergedDetails)
                        }
                        is DetailState.Error   -> {
                            binding.progressBar.visibility  = View.GONE
                            binding.tvError.text = getString(R.string.error_prefix, state.message)
                            binding.tvError.visibility      = View.VISIBLE
                            // Keep contentGroup visible with whatever we already have
                        }
                    }
                }
            }
        }
    }

    // ── Live formation observer ────────────────────────────────────────────────
    // Fires whenever Darwin pushes a formation update for our headcode.

    private fun observeFormation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.detailFormation.collect { formation ->
                    formation ?: return@collect
                    val current = cachedDetails ?: return@collect
                    // Update unit info section with live Darwin data
                    bindUnitInfo(formation.units, formation.coachCount)
                    // Update coach count in header if detail already loaded
                    if (current.operator.isNotEmpty()) {
                        bindHeader(current, formation.units, formation.coachCount)
                    }
                }
            }
        }
    }

    // ── HSP punctuality observer ───────────────────────────────────────────────

    private fun observeHsp() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.hspSummary.collect { summary ->
                    summary ?: return@collect
                    bindPunctuality(summary)
                }
            }
        }
    }

    // ── Server allocation (consist) observer ──────────────────────────────────

    /**
     * Observes [MainViewModel.serverAllocation] and feeds unit/coach data into
     * [bindUnitInfo].
     *
     * Only applies if Darwin hasn't already pushed a live formation — Darwin data
     * is always preferred since it reflects real-time consist changes.
     */
    private fun observeServerAllocation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.serverAllocation.collect { info ->
                    android.util.Log.d("ServiceDetail", "serverAllocation emitted: $info")
                    info ?: return@collect
                    if (viewModel.detailFormation.value != null) {
                        // serverAllocation skipped — detailFormation already set
                        return@collect
                    }
                    android.util.Log.d("ServiceDetail", "serverAllocation: binding units=${info.units} coaches=${info.coachCount}")
                    bindUnitInfo(info.units, info.coachCount)
                    val d = cachedDetails
                    val boardUnits   = intent.getStringArrayListExtra(EXTRA_UNITS) ?: emptyList()
                    val boardCoaches = intent.getIntExtra(EXTRA_COACHES, 0).takeIf { it in 1..20 } ?: 0
                    if (d != null && d.operator.isNotEmpty()) {
                        bindHeader(d, info.units.ifEmpty { boardUnits }, info.coachCount.takeIf { it > 0 } ?: boardCoaches)
                    }
                }
            }
        }
    }

    // ── Split allocation observer ──────────────────────────────────────────────
    private fun observeSplitAllocation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.splitAllocation.collect { splitInfo ->
                    splitInfo ?: return@collect
                    val mainInfo = viewModel.serverAllocation.value ?: return@collect
                    val isCoupling = coupledFromUid.isNotEmpty()
                    bindUnitInfo(mainInfo.units, mainInfo.coachCount, splitInfo.units, isCoupling)
                }
            }
        }
        // Also trigger when serverAllocation arrives after splitAllocation
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.serverAllocation.collect { mainInfo ->
                    mainInfo ?: return@collect
                    val splitInfo = viewModel.splitAllocation.value ?: return@collect
                    val isCoupling = coupledFromUid.isNotEmpty()
                    bindUnitInfo(mainInfo.units, mainInfo.coachCount, splitInfo.units, isCoupling)
                }
            }
        }
    }

    // ── Know Your Train live observer ─────────────────────────────────────────

    // ── Live TRUST overlay ────────────────────────────────────────────────────

    /**
     * Observes [MainViewModel.detailLiveState] and:
     *  1. Updates the "currently at" banner
     *  2. Overlays actual times, updated platforms, and cancellation state
     *     onto the calling points list without needing a full re-fetch
     */
    private fun observeDetailLive() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.detailLiveState.collect { live ->
                    updateLiveBanner(live)
                    val d = cachedDetails ?: return@collect
                    rebuildAdapter(d, live)
                }
            }
        }
    }


    private fun updateLiveBanner(live: DetailLiveState) {
        val hc  = trainHeadcode.uppercase()
        val loc = viewModel.lastKnownLocations.value[hc]
        when {
            live.isCancelled -> {
                binding.tvLiveLocationText.text = getString(R.string.service_cancelled)
                binding.tvLiveLocationTime.text = ""
                binding.bannerLiveLocation.visibility = View.VISIBLE
            }
            loc != null -> {
                val verb = if (loc.eventType == "DEPARTURE") getString(R.string.verb_departed) else getString(R.string.verb_at)
                val delayStr = when {
                    live.latestDelayMins > 0  -> getString(R.string.delay_late, live.latestDelayMins)
                    live.latestDelayMins < 0  -> getString(R.string.delay_early, -live.latestDelayMins)
                    else                      -> getString(R.string.delay_on_time)
                }
                binding.tvLiveLocationText.text = getString(R.string.live_location_text, verb, loc.stationName, delayStr)
                binding.tvLiveLocationTime.text = loc.time
                binding.bannerLiveLocation.visibility = View.VISIBLE
            }
            else -> binding.bannerLiveLocation.visibility = View.GONE
        }
    }

    // ── Unit info card ────────────────────────────────────────────────────────

    /**
     * Shows class name, traction type, and unit numbers in the unit/HSP card.
     * Called immediately with board data (units may be empty), then again when
     * Darwin pushes a live formation.
     *
     * Only shows rolling stock class info when we have real Darwin unit numbers.
     * Coach count is shown if available and valid.
     */
    private fun bindUnitInfo(units: List<String>, coachCount: Int, splitUnits: List<String> = emptyList(), isCoupling: Boolean = false) {
        val hasUnits   = units.isNotEmpty()
        val hasCoaches = coachCount in 1..20

        if (!hasUnits && !hasCoaches) {
            if (binding.layoutPunctuality.isGone) {
                binding.cardUnitHsp.visibility = View.GONE
            }
            return
        }

        binding.cardUnitHsp.visibility = View.VISIBLE

        if (hasUnits) {
            val info      = RollingStockData.infoFromUnit(units.first())
            val className = info?.name ?: units.firstOrNull()?.let {
                val cls = RollingStockData.classFromUnit(it)
                if (cls != null) "Class $cls" else ""
            } ?: ""
            val traction  = info?.traction ?: ""
            val classTractionLine = listOf(className, traction).filter { it.isNotEmpty() }.joinToString(" · ")
            val unitLine  = buildString {
                append(units.joinToString(" + "))
                if (hasCoaches) append("  ·  ${coachCount}c")
            }
            binding.tvUnitAllocationLabel.text = getString(R.string.label_unit_allocation)
            binding.tvUnitAllocationLabel.visibility = View.VISIBLE
            binding.tvUnitAllocation.text = classTractionLine.ifEmpty { unitLine }
            binding.tvUnitAllocation.visibility = View.VISIBLE
            // Split/join unit breakdown — one line per unit with destination and coach count
            val continueUnits = units.filter { it !in splitUnits }
            val splitStation = if (isCoupling)
                StationData.findByCrs(couplingTiplocVar)?.name ?: couplingTiplocVar
            else
                StationData.findByCrs(splitTiploc)?.name ?: splitTiploc
            val splitBreakdown = if (splitUnits.isNotEmpty() && continueUnits.isNotEmpty()) {
                val mainDest = cachedDetails?.destination ?: ""
                if (isCoupling) {
                    // Joining: splitUnits join at couplingTiploc from their origin
                    val joinCoaches = splitUnits.size * (coachCount / units.size)
                    val mainCoaches = coachCount - joinCoaches
                    buildString {
                        append(continueUnits.joinToString(" + "))
                        append(" → $mainDest ($mainCoaches coaches, full route)")
                        append("\n")
                        append(splitUnits.joinToString(" + "))
                        append(" → $mainDest ($joinCoaches coaches, joins at $splitStation)")
                        if (splitStation.isNotEmpty()) append("\n🔗 Joins at $splitStation")
                    }
                } else {
                    // Splitting: continueUnits go to main dest, splitUnits go to split dest
                    val sDest = splitToDestName.ifEmpty { splitToHeadcode }
                    val mainCoaches = coachCount - (splitUnits.size * (coachCount / units.size))
                    val splitCoaches = splitUnits.size * (coachCount / units.size)
                    buildString {
                        append(continueUnits.joinToString(" + "))
                        append(" → $mainDest ($mainCoaches coaches)")
                        append("\n")
                        append(splitUnits.joinToString(" + "))
                        append(" → $sDest ($splitCoaches coaches)")
                        if (splitStation.isNotEmpty()) append("\nsplits at $splitStation")
                    }
                }
            } else null
            binding.tvUnitNumbers.text = splitBreakdown ?: if (classTractionLine.isNotEmpty()) unitLine else ""
            binding.tvUnitNumbers.visibility = if ((splitBreakdown != null || (classTractionLine.isNotEmpty() && unitLine.isNotEmpty()))) View.VISIBLE else View.GONE
        } else {
            // No Darwin units yet — just show coach count, no class guessing
            binding.tvUnitAllocationLabel.text = getString(R.string.label_formation)
            binding.tvUnitAllocationLabel.visibility = View.VISIBLE
            binding.tvUnitAllocation.text = resources.getQuantityString(R.plurals.coach_count, coachCount, coachCount)
            binding.tvUnitAllocation.visibility = View.VISIBLE
            binding.tvUnitNumbers.visibility = View.GONE
        }
    }

    // ── Punctuality badge ─────────────────────────────────────────────────────

    private fun bindPunctuality(summary: HspSummary) {
        binding.cardUnitHsp.visibility = View.VISIBLE
        binding.layoutPunctuality.visibility = View.VISIBLE

        val pct = summary.punctualityPct
        binding.tvPunctualityPct.text = getString(R.string.punctuality_pct, pct)
        binding.tvPunctualitySample.text = resources.getQuantityString(R.plurals.punctuality_runs, summary.totalRuns, summary.totalRuns)

        // Colour the badge: green ≥ 85%, amber ≥ 70%, red below
        val colour = when {
            pct >= 85 -> "#2E7D32".toColorInt()  // dark green
            pct >= 70 -> "#E65100".toColorInt()  // amber/orange
            else      -> "#B71C1C".toColorInt()  // red
        }
        binding.layoutPunctuality.background.setTint(colour)
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    /**
     * Builds the calling point list, overlays live TRUST actuals/platforms and
     * TRUST-propagated ETAs onto each point, then submits to the adapter.
     * The overlay is purely presentational — it never modifies [cachedDetails].
     */
    private fun rebuildAdapter(d: ServiceDetails, live: DetailLiveState = DetailLiveState()) {
        val allPoints = buildPointList(d).map { pt ->
            val depActual       = live.departureActuals[pt.crs]
            val arrActual       = live.arrivalActuals[pt.crs]
            val updatedPlatform = live.platforms[pt.crs]
            val isCancelledStop = pt.crs in live.cancelledStops

            // A stop is "passed" once we have a confirmed actual time for it
            val hasPassed    = depActual != null || arrActual != null
            // For future stops, use the TRUST-propagated ETA (shown as ~HH:MM)
            val estimatedEta = if (!hasPassed) live.estimatedEtas[pt.crs] else null

            pt.copy(
                at = when {
                    arrActual != null -> arrActual
                    depActual != null -> depActual
                    else              -> ""  // clear server-propagated time — show as estimate instead
                },
                et = when {
                    hasPassed            -> pt.et           // already passed — keep original
                    estimatedEta != null -> estimatedEta    // TRUST-propagated estimate
                    pt.at.isNotEmpty()   -> pt.at           // server-propagated delay — shown as ~HH:MM
                    else                 -> pt.et           // original board ETA
                },
                platform    = updatedPlatform ?: pt.platform,
                isCancelled = isCancelledStop || pt.isCancelled || live.isCancelled
            )
        }

        if (allPoints.isEmpty()) {
            binding.tvNoCallingPoints.visibility = View.VISIBLE
            binding.rvCallingPoints.visibility   = View.GONE
            return
        }
        binding.tvNoCallingPoints.visibility = View.GONE
        binding.rvCallingPoints.visibility   = View.VISIBLE

        val hc         = trainHeadcode.uppercase()
        val passedCrs  = live.departureActuals.keys + live.arrivalActuals.keys
        val currentCrs = viewModel.lastKnownLocations.value[hc]
            ?.takeIf { it.eventType == "DEPARTURE" }
            ?.crs ?: ""

        if (callingAdapter == null) {
            callingAdapter = CallingPointAdapter(
                highlightCrs    = queryCrs,
                showPassing     = showPassing,
                showDetailed    = showDetailed,
                onStationClick  = { pt -> StationBoardActivity.start(this, pt.crs, pt.locationName) },
                splitTiploc       = splitTiploc,
                splitToHeadcode   = splitToHeadcode,
                splitToDestName   = splitToDestName,
                splitToUid        = splitToUid,
                couplingTiplocCrs = couplingTiplocVar,
                coupledFromHc     = coupledFromHcVar,
                onSplitClick      = { headcode, uid ->
                    start(
                        ctx             = this,
                        serviceId       = uid,
                        headcode        = headcode,
                        origin          = StationData.findByCrs(splitTiploc)?.name ?: splitTiploc,
                        destination     = splitToDestName,
                        std             = "",
                        queryCrs        = splitTiploc,
                        splitTiploc     = "",
                        splitToHeadcode = ""
                    )
                }
            )
            binding.rvCallingPoints.adapter = callingAdapter
        }

        // Pass current delay so the "Train is here" divider badge shows "+Nm late"
        callingAdapter!!.submitFiltered(
            points     = allPoints,
            passedCrs  = passedCrs,
            currentCrs = currentCrs,
            delayMins  = live.latestDelayMins
        )

        // Show/hide overall cancellation banner
        // Prefer CIF points (full route) over board points (may only cover part of route)
        val subsForCanc = d.cifSubsequentCallingPoints.ifEmpty { d.subsequentCallingPoints }
        val allSubsCanc = subsForCanc.isNotEmpty() &&
                subsForCanc.all {
                    it.isCancelled || it.et.equals("Cancelled", ignoreCase = true)
                }
        val someSubsCanc = !allSubsCanc && subsForCanc.any {
                    it.isCancelled || it.et.equals("Cancelled", ignoreCase = true)
                }
        val partialCanc1 = !live.isCancelled && !d.isCancelled && someSubsCanc
        binding.tvCancelledBanner.text = getString(
            if (partialCanc1) R.string.service_partially_cancelled_banner
            else R.string.service_cancelled_banner
        )
        binding.tvCancelledBanner.visibility =
            if (live.isCancelled || d.isCancelled || allSubsCanc || someSubsCanc) View.VISIBLE else View.GONE
    }

    private fun buildPointList(d: ServiceDetails): List<CallingPoint> {
        // In Detailed mode, use server calling points if available (includes passing points).
        // In Simple mode, use board calling points (stopping calls only, with real times).
        val useCif = showDetailed &&
                (d.cifPreviousCallingPoints.isNotEmpty() || d.cifSubsequentCallingPoints.isNotEmpty())

        val prevPoints = if (useCif) d.cifPreviousCallingPoints else d.previousCallingPoints
        val subsPoints = if (useCif) d.cifSubsequentCallingPoints else d.subsequentCallingPoints

        val all = ArrayList<CallingPoint>(prevPoints.size + subsPoints.size + 1)
        all.addAll(prevPoints)

        val isPassingAtStation = d.isPassingAtStation ||
                intent.getBooleanExtra(EXTRA_IS_PASSING, false)

        val alreadyPresent = all.any { it.crs == queryCrs } ||
                subsPoints.any { it.crs == queryCrs }
        if (!alreadyPresent && queryCrs.isNotEmpty()) {
            val boardStd = intent.getStringExtra(EXTRA_STD) ?: ""
            val boardEtd = intent.getStringExtra(EXTRA_ETD) ?: ""
            val info = StationData.findByCrs(queryCrs)
            all.add(CallingPoint(
                locationName = info?.name ?: queryCrs,
                crs          = queryCrs,
                st           = boardStd.ifEmpty { "—" },
                et           = boardEtd,
                at           = "",
                isCancelled  = false,
                length       = null,
                platform     = if (isPassingAtStation) "" else d.platform,
                isPassing    = isPassingAtStation
            ))
        }
        all.addAll(subsPoints)
        return all
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private fun bindHeader(d: ServiceDetails, boardUnits: List<String>, boardCoaches: Int) {
        val logoName = TocData.logoDrawableName(d.operatorCode).ifEmpty { TocData.logoDrawableName(d.operator) }
        val resId    = TocData.logoDrawableRes(logoName, this)
        if (resId != 0) {
            binding.imgTocLogo.setImageResource(resId)
            binding.imgTocLogo.visibility = View.VISIBLE
            binding.tvTocBadge.visibility = View.GONE
        } else {
            binding.imgTocLogo.visibility = View.GONE
            val label = d.operatorCode.ifEmpty { d.operator.take(4).uppercase() }
            if (label.isNotEmpty()) {
                binding.tvTocBadge.text = label
                binding.tvTocBadge.background.setTint(TocData.brandColor(d.operatorCode.ifEmpty { d.operator }))
                binding.tvTocBadge.visibility = View.VISIBLE
            } else {
                binding.tvTocBadge.visibility = View.GONE
            }
        }

        binding.tvOperator.text = d.operator

        val hc = d.trainId.ifEmpty { trainHeadcode }
        binding.tvTrainId.text = hc
        binding.tvTrainId.visibility = if (hc.isNotEmpty()) View.VISIBLE else View.GONE

        binding.tvPlatform.visibility = View.GONE

        val coaches = when {
            boardCoaches in 1..20     -> boardCoaches
            (d.coachCount ?: 0) in 1..20 -> d.coachCount
            else -> d.subsequentCallingPoints
                .firstOrNull { !it.isPassing && (it.length ?: 0) in 1..20 }
                ?.length
        }

        // Detect coach count changes along the route (e.g. train divides)
        val allRoutePoints = d.cifSubsequentCallingPoints.ifEmpty { d.subsequentCallingPoints }
        val distinctLengths = allRoutePoints.mapNotNull { it.length?.takeIf { l -> l in 1..20 } }.distinct()
        val coachDisplay: String? = when {
            distinctLengths.size >= 2 -> "${distinctLengths.first()} \u2192 ${distinctLengths.last()} coaches"
            coaches != null           -> "$coaches coaches"
            else                      -> null
        }

        // tvRsid shows RSID / coaches — unit detail goes in the new card
        val rsidLine = when {
            d.rsid.isNotEmpty() && d.rsid != hc -> buildString {
                append("RSID: ${d.rsid}")
                if (coachDisplay != null) append("  ·  $coachDisplay")
            }
            coachDisplay != null -> coachDisplay
            else -> ""
        }
        binding.tvRsid.text = rsidLine
        binding.tvRsid.visibility = if (rsidLine.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvFormation.visibility = View.GONE

        // Only seed the unit card from board data if neither the server allocation
        // nor a Darwin formation has already populated it. Otherwise, we'd erase
        // the richer consist data that arrived asynchronously.
        if (viewModel.detailFormation.value == null && viewModel.serverAllocation.value == null) {
            bindUnitInfo(boardUnits, coaches ?: 0)
        }

        val originName = resolveLocationName(d.origin.ifEmpty { intent.getStringExtra(EXTRA_ORIGIN) ?: "" })
        val originDep  = d.previousCallingPoints.firstOrNull()?.st
            ?: d.subsequentCallingPoints.firstOrNull()?.st ?: ""
        val journeyStr = if (d.journeyDurationMinutes > 0) "  ·  ${formatDuration(d.journeyDurationMinutes)}" else ""
        val platStr    = if (d.platform.isNotEmpty()) "  ·  Plat ${d.platform}" else ""
        binding.tvServiceSubtitle.text = when {
            originDep.isNotEmpty() && originName.isNotEmpty() -> "Dep $originDep from $originName$platStr$journeyStr"
            originDep.isNotEmpty()                            -> "Dep $originDep$platStr$journeyStr"
            journeyStr.isNotEmpty()                           -> "Journey$platStr$journeyStr"
            else                                              -> ""
        }
        binding.tvPlatform.visibility = View.GONE
        binding.tvServiceSubtitle.visibility =
            if (binding.tvServiceSubtitle.text.isNotEmpty()) View.VISIBLE else View.GONE

        val reason = when {
            d.cancelReason.isNotEmpty() -> "⚠ ${resolveCancelCode(d.cancelReason)}"
            d.delayReason.isNotEmpty()  -> "ℹ ${resolveReasonCode(d.delayReason)}"
            d.adhocAlerts.isNotEmpty()  -> d.adhocAlerts
            else                        -> ""
        }
        binding.tvReason.text = reason
        binding.tvReason.visibility = if (reason.isNotEmpty()) View.VISIBLE else View.GONE

        val allSubsCanc = d.subsequentCallingPoints.isNotEmpty()
                && d.subsequentCallingPoints.all {
            it.isCancelled || it.et.equals("Cancelled", ignoreCase = true)
        }
        val someSubsCanc = !allSubsCanc && d.subsequentCallingPoints.any {
            it.isCancelled || it.et.equals("Cancelled", ignoreCase = true)
        }
        val partialCanc2 = !d.isCancelled && someSubsCanc
        binding.tvCancelledBanner.text = getString(
            if (partialCanc2) R.string.service_partially_cancelled_banner
            else R.string.service_cancelled_banner
        )
        binding.tvCancelledBanner.visibility =
            if (d.isCancelled || allSubsCanc || someSubsCanc) View.VISIBLE else View.GONE

        // Split station info in unit card — show coach counts to each destination if known
        if (splitTiploc.isNotEmpty()) {
            val splitStationName = StationData.findByCrs(splitTiploc)?.name ?: splitTiploc
            val allRoute = d.cifSubsequentCallingPoints.ifEmpty { d.subsequentCallingPoints }
            val splitIdx = allRoute.indexOfFirst { it.crs == splitTiploc }
            val beforeLen = if (splitIdx > 0) allRoute.subList(0, splitIdx).lastOrNull { (it.length ?: 0) > 0 }?.length else null
            val afterLen  = if (splitIdx >= 0) allRoute.subList(splitIdx, allRoute.size).firstOrNull { (it.length ?: 0) > 0 }?.length else null
            binding.cardUnitHsp.visibility = View.VISIBLE
            binding.tvSplitInfo.visibility = View.GONE
        } else {
            binding.tvSplitInfo.visibility = View.GONE
        }

        // Formed-from display (this service is a split-off portion)
        val couplingTiploc = intent.getStringExtra(EXTRA_COUPLING_TIPLOC) ?: ""
        val coupledFromHc  = intent.getStringExtra(EXTRA_COUPLED_FROM_HC) ?: ""
        // couplingTiplocVar already set above
        if (coupledFromHc.isNotEmpty() && couplingTiploc.isNotEmpty()) {
            val stationName = StationData.findByCrs(couplingTiploc)?.name ?: couplingTiploc
            binding.cardUnitHsp.visibility = View.VISIBLE
            val couplingAssocType = intent.getStringExtra(EXTRA_COUPLING_ASSOC_TYPE) ?: ""
            val formedLabel = if (couplingAssocType == "NP" || couplingAssocType == "JJ") "Joins with" else "Formed from"
            binding.tvSplitInfo.text = getString(R.string.coupling_formed_from, formedLabel, coupledFromHc, stationName)
            binding.tvSplitInfo.visibility = View.VISIBLE
            if (coupledFromUid.isNotEmpty()) {
                binding.tvSplitInfo.setOnClickListener {
                    start(
                        ctx         = this,
                        serviceId   = coupledFromUid,
                        headcode    = coupledFromHc,
                        origin      = StationData.findByCrs(couplingTiploc)?.name ?: couplingTiploc,
                        destination = cachedDetails?.destination ?: "",
                        std         = "",
                        queryCrs    = couplingTiploc
                    )
                }
            }
        }
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    private fun shareCurrentService() {
        val d = cachedDetails ?: return
        val boardUnits = intent.getStringArrayListExtra(EXTRA_UNITS) ?: emptyList()
        val formation  = viewModel.detailFormation.value
        val liveUnits  = formation?.units ?: boardUnits
        val journeyStr = if (d.journeyDurationMinutes > 0)
            "\nJourney: ${formatDuration(d.journeyDurationMinutes)}" else ""
        val unitStr = when {
            liveUnits.isNotEmpty() -> "\nUnit: " + liveUnits.joinToString(" + ")
            d.rsid.isNotEmpty()    -> "\nRSID: ${d.rsid}"
            else                   -> ""
        }
        val hspStr = viewModel.hspSummary.value?.run {
            "\nOn-time: $punctualityPct% ($totalRuns runs)"
        } ?: ""
        val origin = resolveLocationName(d.origin.ifEmpty { intent.getStringExtra(EXTRA_ORIGIN) ?: "" })
        val shareDest = resolveLocationName(d.destination)
        val text = buildString {
            append("$origin → $shareDest")
            if (d.platform.isNotEmpty()) append(" · Plat ${d.platform}")
            if (trainHeadcode.isNotEmpty()) append(" · $trainHeadcode")
            append("\n${d.operator}")
            append(journeyStr)
            append(unitStr)
            append(hspStr)
            if (d.cancelReason.isNotEmpty()) append("\n⚠ ${resolveCancelCode(d.cancelReason)}")
            else if (d.delayReason.isNotEmpty()) append("\nℹ ${resolveReasonCode(d.delayReason)}")
            append("\n\nvia Train Tracker")
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Share service"
        ))
    }
}