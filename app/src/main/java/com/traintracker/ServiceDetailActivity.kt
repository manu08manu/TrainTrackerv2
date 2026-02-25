package com.traintracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
    private var queryCrs  = ""
    private var trainHeadcode = ""

    // ── DarwinService binding ─────────────────────────────────────────────────
    private val darwinConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as DarwinService.LocalBinder).getService()
            // Attach TRUST so Know Your Train works even when opened from a cold start
            viewModel.attachTrustMovements(svc.trustMovements, svc.trustActivations, svc.trustConnected)
            viewModel.attachDarwinUpdates(svc.updates, svc.connectionState)
            viewModel.attachDarwinFormations(svc.formations)
        }
        override fun onServiceDisconnected(name: ComponentName) {}
    }

    companion object {
        private const val EXTRA_SERVICE_ID = "service_id"
        private const val EXTRA_HEADCODE   = "headcode"
        private const val EXTRA_ORIGIN     = "origin"
        private const val EXTRA_DEST       = "destination"
        private const val EXTRA_STD        = "std"
        private const val EXTRA_ETD        = "etd"
        private const val EXTRA_QUERY_CRS  = "query_crs"
        private const val EXTRA_UNITS      = "units"
        private const val EXTRA_COACHES    = "coaches"

        fun start(ctx: Context, serviceId: String, headcode: String,
                  origin: String, destination: String, std: String, etd: String = "",
                  queryCrs: String,
                  units: List<String> = emptyList(), coachCount: Int = 0) {
            ctx.startActivity(Intent(ctx, ServiceDetailActivity::class.java).apply {
                putExtra(EXTRA_SERVICE_ID, serviceId)
                putExtra(EXTRA_HEADCODE,   headcode)
                putExtra(EXTRA_ORIGIN,     origin)
                putExtra(EXTRA_DEST,       destination)
                putExtra(EXTRA_STD,        std)
                putExtra(EXTRA_ETD,        etd)
                putExtra(EXTRA_QUERY_CRS,  queryCrs)
                putStringArrayListExtra(EXTRA_UNITS, ArrayList(units))
                putExtra(EXTRA_COACHES,    coachCount)
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
        val dest         = intent.getStringExtra(EXTRA_DEST)     ?: ""
        queryCrs         = intent.getStringExtra(EXTRA_QUERY_CRS) ?: ""
        val boardUnits   = intent.getStringArrayListExtra(EXTRA_UNITS) ?: emptyList<String>()
        val boardCoaches = intent.getIntExtra(EXTRA_COACHES, 0)

        binding.tvServiceTitle.text = "$origin → $dest"
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

        // Bind to DarwinService so TRUST data flows into this screen's ViewModel
        DarwinService.start(this)
        bindService(Intent(this, DarwinService::class.java), darwinConnection, BIND_AUTO_CREATE)

        observeDetailState(boardUnits, boardCoaches)
        observeLiveJourney()
        viewModel.fetchServiceDetails(serviceId)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(darwinConnection)
        viewModel.clearDetailState()
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
                            binding.contentGroup.visibility = View.GONE
                        }
                        is DetailState.Success -> {
                            binding.progressBar.visibility  = View.GONE
                            binding.tvError.visibility      = View.GONE
                            binding.contentGroup.visibility = View.VISIBLE
                            cachedDetails = state.details
                            // Use headcode from detail response if we didn't have it from the board
                            if (trainHeadcode.isEmpty()) trainHeadcode = state.details.trainId
                            bindHeader(state.details, boardUnits, boardCoaches)
                            rebuildAdapter(state.details)
                        }
                        is DetailState.Error   -> {
                            binding.progressBar.visibility  = View.GONE
                            binding.tvError.text = "Error: ${state.message}"
                            binding.tvError.visibility      = View.VISIBLE
                            binding.contentGroup.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    // ── Know Your Train live observer ─────────────────────────────────────────
    // Watches both the last-known location and the full journey trail.
    // Whenever either updates for our headcode, we refresh the banner and
    // rebuild the calling points list with passed/current states applied.

    private fun observeLiveJourney() {
        // Banner: last known location
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.lastKnownLocations.collect { locations ->
                    val hc = trainHeadcode.uppercase()
                    val loc = if (hc.isNotEmpty()) locations[hc] else null
                    if (loc != null) {
                        val verb = if (loc.eventType == "DEPARTURE") "Departed" else "At"
                        val delayStr = when {
                            loc.delayMinutes > 0 -> " · +${loc.delayMinutes}m late"
                            loc.delayMinutes < 0 -> " · ${-loc.delayMinutes}m early"
                            else                 -> " · On time"
                        }
                        binding.tvLiveLocationText.text = "$verb ${loc.stationName}$delayStr"
                        binding.tvLiveLocationTime.text = loc.time
                        binding.bannerLiveLocation.visibility = View.VISIBLE
                    } else {
                        binding.bannerLiveLocation.visibility = View.GONE
                    }
                }
            }
        }

        // Rebuild calling points with journey trail applied
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.journeyActuals.collect { actuals ->
                    val d = cachedDetails ?: return@collect
                    val hc = trainHeadcode.uppercase()
                    val passedCrs = actuals[hc]?.keys?.toSet() ?: emptySet()
                    rebuildAdapter(d, passedCrs)
                }
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private fun rebuildAdapter(d: ServiceDetails, passedCrs: Set<String> = emptySet()) {
        val allPoints = buildPointList(d)
        if (allPoints.isEmpty()) {
            binding.tvNoCallingPoints.visibility = View.VISIBLE
            binding.rvCallingPoints.visibility   = View.GONE
            return
        }
        binding.tvNoCallingPoints.visibility = View.GONE
        binding.rvCallingPoints.visibility   = View.VISIBLE

        // Determine the "current" CRS from the last known location
        val hc = trainHeadcode.uppercase()
        val currentCrs = viewModel.lastKnownLocations.value[hc]
            ?.takeIf { it.eventType == "DEPARTURE" }   // if departed, we're "after" that stop
            ?.crs ?: ""

        if (callingAdapter == null) {
            callingAdapter = CallingPointAdapter(
                highlightCrs   = queryCrs,
                showPassing    = showPassing,
                showDetailed   = showDetailed,
                onStationClick = { pt -> StationBoardActivity.start(this, pt.crs, pt.locationName) }
            )
            binding.rvCallingPoints.adapter = callingAdapter
        }
        callingAdapter!!.submitFiltered(allPoints, passedCrs, currentCrs)
    }

    private fun buildPointList(d: ServiceDetails): List<CallingPoint> {
        val all = ArrayList<CallingPoint>(
            d.previousCallingPoints.size + d.subsequentCallingPoints.size + 1
        )
        all.addAll(d.previousCallingPoints)

        val alreadyPresent = all.any { it.crs == queryCrs } ||
                d.subsequentCallingPoints.any { it.crs == queryCrs }
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
                platform     = d.platform
            ))
        }
        all.addAll(d.subsequentCallingPoints)
        return all
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private fun bindHeader(d: ServiceDetails, boardUnits: List<String>, boardCoaches: Int) {
        val logoName = TocData.logoDrawableName(d.operatorCode).ifEmpty { TocData.logoDrawableName(d.operator) }
        val resId    = if (logoName.isNotEmpty()) resources.getIdentifier(logoName, "drawable", packageName) else 0
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

        binding.tvPlatform.text = if (d.platform.isNotEmpty()) "Plat ${d.platform}" else ""
        binding.tvPlatform.visibility = if (d.platform.isNotEmpty()) View.VISIBLE else View.GONE

        val coaches = when {
            boardCoaches > 0     -> boardCoaches
            d.coachCount != null -> d.coachCount
            else                 -> null
        }
        val unitLine = when {
            boardUnits.isNotEmpty() -> RollingStockData.describeFormation(boardUnits, coaches ?: 0)
            d.rsid.isNotEmpty()     -> buildString {
                append("RSID: ${d.rsid}")
                if (coaches != null) append("  ·  ${coaches} coaches")
            }
            coaches != null -> "${coaches} coaches"
            else -> ""
        }
        binding.tvRsid.text = unitLine
        binding.tvRsid.visibility = if (unitLine.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvFormation.visibility = View.GONE

        val originName = d.origin.ifEmpty { intent.getStringExtra(EXTRA_ORIGIN) ?: "" }
        val originDep  = d.previousCallingPoints.firstOrNull()?.st
            ?: d.subsequentCallingPoints.firstOrNull()?.st ?: ""
        val journeyStr = if (d.journeyDurationMinutes > 0) "  ·  ${formatDuration(d.journeyDurationMinutes)}" else ""
        binding.tvServiceSubtitle.text = when {
            originDep.isNotEmpty() && originName.isNotEmpty() -> "Dep $originDep from $originName$journeyStr"
            originDep.isNotEmpty()                            -> "Dep $originDep$journeyStr"
            journeyStr.isNotEmpty()                           -> "Journey$journeyStr"
            else                                              -> ""
        }
        binding.tvServiceSubtitle.visibility =
            if (binding.tvServiceSubtitle.text.isNotEmpty()) View.VISIBLE else View.GONE

        val reason = when {
            d.cancelReason.isNotEmpty() -> "⚠ ${d.cancelReason}"
            d.delayReason.isNotEmpty()  -> "ℹ ${d.delayReason}"
            d.adhocAlerts.isNotEmpty()  -> d.adhocAlerts
            else                        -> ""
        }
        binding.tvReason.text = reason
        binding.tvReason.visibility = if (reason.isNotEmpty()) View.VISIBLE else View.GONE

        val allSubsCanc = d.subsequentCallingPoints.isNotEmpty()
                && d.subsequentCallingPoints.all {
                    it.isCancelled || it.et.equals("Cancelled", ignoreCase = true)
                }
        binding.tvCancelledBanner.visibility =
            if (d.isCancelled || allSubsCanc) View.VISIBLE else View.GONE
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    private fun shareCurrentService() {
        val d = cachedDetails ?: return
        val boardUnits = intent.getStringArrayListExtra(EXTRA_UNITS) ?: emptyList<String>()
        val journeyStr = if (d.journeyDurationMinutes > 0)
            "\nJourney: ${formatDuration(d.journeyDurationMinutes)}" else ""
        val unitStr = when {
            boardUnits.isNotEmpty() -> "\nUnit: " + boardUnits.joinToString(" + ")
            d.rsid.isNotEmpty()     -> "\nRSID: ${d.rsid}"
            else                    -> ""
        }
        val origin = d.origin.ifEmpty { intent.getStringExtra(EXTRA_ORIGIN) ?: "" }
        val text = buildString {
            append("$origin → ${d.destination}")
            if (d.platform.isNotEmpty()) append(" · Plat ${d.platform}")
            if (trainHeadcode.isNotEmpty()) append(" · $trainHeadcode")
            append("\n${d.operator}")
            append(journeyStr)
            append(unitStr)
            if (d.cancelReason.isNotEmpty()) append("\n⚠ ${d.cancelReason}")
            else if (d.delayReason.isNotEmpty()) append("\nℹ ${d.delayReason}")
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
