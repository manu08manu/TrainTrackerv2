package com.traintracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    private var queryCrs = ""

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
        val headcode     = intent.getStringExtra(EXTRA_HEADCODE) ?: ""
        val origin       = intent.getStringExtra(EXTRA_ORIGIN)   ?: ""
        val dest         = intent.getStringExtra(EXTRA_DEST)     ?: ""
        queryCrs         = intent.getStringExtra(EXTRA_QUERY_CRS) ?: ""
        val boardUnits   = intent.getStringArrayListExtra(EXTRA_UNITS) ?: emptyList<String>()
        val boardCoaches = intent.getIntExtra(EXTRA_COACHES, 0)

        binding.tvServiceTitle.text = "$origin → $dest"
        binding.rvCallingPoints.layoutManager = LinearLayoutManager(this)

        // ── Toggle: Simple (default) vs Detailed ──────────────────────────────
        // We null-out the adapter on each switch so the RecyclerView rebinds all
        // items from scratch — avoiding DiffUtil's "nothing changed" short-circuit.
        binding.chipSimple.setOnClickListener {
            if (showDetailed) {
                showPassing  = false
                showDetailed = false
                binding.chipSimple.isChecked   = true
                binding.chipDetailed.isChecked = false
                binding.tvPassingNote.visibility = android.view.View.GONE
                // Destroy adapter → rebuildAdapter will create fresh one
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
                // Show passing station note
                binding.tvPassingNote.visibility = android.view.View.VISIBLE
                callingAdapter = null
                binding.rvCallingPoints.adapter = null
                cachedDetails?.let { rebuildAdapter(it) }
            }
        }

        observeState(headcode, boardUnits, boardCoaches)
        observeLiveLocation(headcode)
        viewModel.fetchServiceDetails(serviceId)
    }

    private fun observeState(headcode: String, boardUnits: List<String>, boardCoaches: Int) {
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
                            bindHeader(state.details, headcode, boardUnits, boardCoaches)
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

    /**
     * Observes the TRUST last-known-location map and updates the live location
     * banner ("Know Your Train" style) whenever the train reports a movement.
     */
    private fun observeLiveLocation(headcode: String) {
        if (headcode.isEmpty()) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.lastKnownLocations.collect { locations ->
                    val loc = locations[headcode.uppercase()]
                    if (loc != null) {
                        val verb = if (loc.eventType == "DEPARTURE") "Departed" else "Arrived"
                        binding.tvLiveLocationText.text = "$verb ${loc.stationName}"
                        binding.tvLiveLocationTime.text = loc.time
                        binding.bannerLiveLocation.visibility = android.view.View.VISIBLE
                    } else {
                        binding.bannerLiveLocation.visibility = android.view.View.GONE
                    }
                }
            }
        }
    }

    private fun bindHeader(d: ServiceDetails, headcode: String,
                           boardUnits: List<String>, boardCoaches: Int) {
        // ── TOC logo / badge ──────────────────────────────────────────────────
        val logoName = TocData.logoDrawableName(d.operatorCode).ifEmpty { TocData.logoDrawableName(d.operator) }
        val resId    = if (logoName.isNotEmpty()) resources.getIdentifier(logoName, "drawable", packageName) else 0
        if (resId != 0) {
            binding.imgTocLogo.setImageResource(resId)
            binding.imgTocLogo.visibility = View.VISIBLE
            binding.tvTocBadge.visibility = View.GONE
        } else {
            showTocFallback(d)
        }

        binding.tvOperator.text = d.operator

        // ── Headcode pill ─────────────────────────────────────────────────────
        val hc = d.trainId.ifEmpty { headcode }
        if (hc.isNotEmpty()) {
            binding.tvTrainId.text = hc
            binding.tvTrainId.visibility = View.VISIBLE
        } else {
            binding.tvTrainId.visibility = View.GONE
        }

        // ── Platform pill ─────────────────────────────────────────────────────
        binding.tvPlatform.text = if (d.platform.isNotEmpty()) "Plat ${d.platform}" else ""
        binding.tvPlatform.visibility = if (d.platform.isNotEmpty()) View.VISIBLE else View.GONE

        // ── Unit / formation info ─────────────────────────────────────────────
        // boardUnits = actual vehicle numbers from Darwin formation (e.g. "387001")
        // d.rsid     = OpenLDBWS retail service ID — NOT a unit number
        // d.coachCount = count from OpenLDBWS formation element
        val coaches = when {
            boardCoaches > 0     -> boardCoaches
            d.coachCount != null -> d.coachCount
            else                 -> null
        }
        val unitLine = when {
            boardUnits.isNotEmpty() -> {
                // Decode class from unit numbers via RollingStockData
                RollingStockData.describeFormation(boardUnits, coaches ?: 0)
            }
            d.rsid.isNotEmpty() -> buildString {
                append("RSID: ${d.rsid}")
                if (coaches != null) append("  ·  ${coaches} coaches")
            }
            coaches != null -> "${coaches} coaches"
            else -> ""
        }
        if (unitLine.isNotEmpty()) {
            binding.tvRsid.text = unitLine
            binding.tvRsid.visibility = View.VISIBLE
        } else {
            binding.tvRsid.visibility = View.GONE
        }
        binding.tvFormation.visibility = View.GONE

        // ── "Dep HH:MM from Origin · Xhr Ymin" ───────────────────────────────
        // Fall back to intent extras when the service details API returns blank origin
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

        // ── Cancel/delay reason ───────────────────────────────────────────────
        val reason = when {
            d.cancelReason.isNotEmpty() -> "⚠ ${d.cancelReason}"
            d.delayReason.isNotEmpty()  -> "ℹ ${d.delayReason}"
            d.adhocAlerts.isNotEmpty()  -> d.adhocAlerts
            else                        -> ""
        }
        binding.tvReason.text = reason
        binding.tvReason.visibility = if (reason.isNotEmpty()) View.VISIBLE else View.GONE

        // ── Cancelled banner ──────────────────────────────────────────────────
        val allSubsCanc = d.subsequentCallingPoints.isNotEmpty()
                && d.subsequentCallingPoints.all {
                    it.isCancelled || it.et.equals("Cancelled", ignoreCase = true)
                }
        binding.tvCancelledBanner.visibility =
            if (d.isCancelled || allSubsCanc) View.VISIBLE else View.GONE
    }

    private fun rebuildAdapter(d: ServiceDetails) {
        val allPoints = buildPointList(d)
        if (allPoints.isEmpty()) {
            binding.tvNoCallingPoints.visibility = View.VISIBLE
            binding.rvCallingPoints.visibility   = View.GONE
            return
        }
        binding.tvNoCallingPoints.visibility = View.GONE
        binding.rvCallingPoints.visibility   = View.VISIBLE

        // Always create a fresh adapter — this guarantees all rows are re-bound
        // regardless of what DiffUtil thinks about the data
        callingAdapter = CallingPointAdapter(
            highlightCrs   = queryCrs,
            showPassing    = showPassing,
            showDetailed   = showDetailed,
            onStationClick = { pt -> StationBoardActivity.start(this, pt.crs, pt.locationName) }
        )
        binding.rvCallingPoints.adapter = callingAdapter
        callingAdapter!!.submitFiltered(allPoints)
    }

    private fun buildPointList(d: ServiceDetails): List<CallingPoint> {
        val all = ArrayList<CallingPoint>(
            d.previousCallingPoints.size + d.subsequentCallingPoints.size + 1
        )
        all.addAll(d.previousCallingPoints)

        val alreadyPresent = all.any { it.crs == queryCrs } ||
                d.subsequentCallingPoints.any { it.crs == queryCrs }
        if (!alreadyPresent && queryCrs.isNotEmpty()) {
            // Use the scheduled + estimated times passed from the board card so that
            // the queried station (e.g. Ware) shows the correct time and on-time status
            val boardStd = intent.getStringExtra(EXTRA_STD) ?: ""
            val boardEtd = intent.getStringExtra(EXTRA_ETD) ?: ""
            val info = StationData.findByCrs(queryCrs)
            all.add(CallingPoint(
                locationName = info?.name ?: queryCrs,
                crs          = queryCrs,
                st           = boardStd.ifEmpty { "—" },
                et           = boardEtd,   // "On time", a delayed time, or "" — drives displayed status
                at           = "",
                isCancelled  = false,
                length       = null,
                platform     = d.platform
            ))
        }
        all.addAll(d.subsequentCallingPoints)
        return all
    }

    private fun showTocFallback(d: ServiceDetails) {
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

    private fun shareCurrentService() {
        val d = cachedDetails ?: return
        val headcode   = d.trainId.ifEmpty { intent.getStringExtra(EXTRA_HEADCODE) ?: "" }
        val boardUnits = intent.getStringArrayListExtra(EXTRA_UNITS) ?: emptyList<String>()
        val journeyStr = if (d.journeyDurationMinutes > 0)
            "\nJourney: ${formatDuration(d.journeyDurationMinutes)}" else ""
        val unitStr = when {
            boardUnits.isNotEmpty() -> "\nUnit: " + boardUnits.joinToString(" + ")
            d.rsid.isNotEmpty()     -> "\nRSID: ${d.rsid}"
            else                    -> ""
        }
        val text = buildString {
            val origin = d.origin.ifEmpty { intent.getStringExtra(EXTRA_ORIGIN) ?: "" }
            append("$origin → ${d.destination}")
            if (d.platform.isNotEmpty()) append(" · Plat ${d.platform}")
            if (headcode.isNotEmpty()) append(" · $headcode")
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

    override fun onDestroy() {
        super.onDestroy()
        viewModel.clearDetailState()
    }
}
