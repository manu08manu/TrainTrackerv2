package com.traintracker

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RTT-style full-day diagram for a single unit number.
 *
 * Layout:
 *   [Service header band]   headcode · origin→dest · formation · status
 *   [Calling point row]     CRS | Station | Plt | Sched | Actual | Delay
 *   [Calling point row]     ...
 *   [Gap / stabling label]  "Stabled 42 min" or "Forms 1P18"
 *   [Service header band]   next headcode...
 *   ...
 *
 * Data:
 *  1. Fetch /api/unit/{unit}  →  ordered list of ServerService
 *  2. For each service, in parallel: fetch /api/service/{uid}?crs={originCrs}
 *  3. Build DiagramItem list and submit to adapter
 *
 * Launched from MainActivity when SearchMode.UNIT is active.
 * Intent extras:
 *   "unit"  — the unit number string (e.g. "375820")
 */
class UnitDiagramActivity : AppCompatActivity() {

    private val server = ServerApiClient()
    private lateinit var adapter: UnitDiagramAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unit_diagram)

        val unit = intent.getStringExtra("unit")?.uppercase()?.trim() ?: run {
            finish(); return
        }

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = "Unit $unit — Diagram"
            setDisplayHomeAsUpEnabled(true)
        }

        progressBar  = findViewById(R.id.progressBar)
        tvError      = findViewById(R.id.tvError)
        recyclerView = findViewById(R.id.recyclerView)

        adapter = UnitDiagramAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        // Remove default item decoration gap — rows are tight like RTT
        recyclerView.itemAnimator = null

        loadDiagram(unit)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadDiagram(unit: String) {
        progressBar.visibility = View.VISIBLE
        tvError.visibility     = View.GONE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val diagramItems = withContext(Dispatchers.IO) { buildDiagram(unit) }
                progressBar.visibility  = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.submitItems(diagramItems)
                if (diagramItems.isEmpty()) showError("No diagram data found for unit $unit today.")
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                showError("Could not load diagram: ${e.message}")
            }
        }
    }

    private suspend fun buildDiagram(unit: String): List<UnitDiagramAdapter.DiagramItem> {
        // Step 1: fetch unit board (ordered list of services for this unit today)
        val services: List<ServerService> = server.getUnitBoard(unit) ?: return emptyList()
        if (services.isEmpty()) return emptyList()

        // Step 2: fetch calling points for every service in parallel
        val callingPointsPerService = coroutineScope {
            services.map { s ->
                async(Dispatchers.IO) {
                    val crs = s.originCrs ?: ""
                    try {
                        server.getCallingPoints(s.uid, crs)
                    } catch (_: Exception) { null }
                }
            }.awaitAll()
        }

        // Step 3: assemble DiagramItem list
        val items = mutableListOf<UnitDiagramAdapter.DiagramItem>()

        for (i in services.indices) {
            val s          = services[i]
            val cpResult   = callingPointsPerService[i]
            val allPoints  = (cpResult?.previous ?: emptyList()) +
                    (cpResult?.subsequent ?: emptyList())

            // ── Gap label between services ─────────────────────────────────
            val gapLabel = if (i == 0) {
                ""
            } else {
                val prevPoints = callingPointsPerService[i - 1]
                val prevAllPoints = (prevPoints?.previous ?: emptyList()) + (prevPoints?.subsequent ?: emptyList())
                val prevArrival = prevAllPoints.lastOrNull()?.st ?: ""
                buildGapLabel(services[i - 1], s, prevArrival)
            }

            // Map ServerService to lightweight TrainService-like info for the header
            val headerService = buildHeaderTrainService(s)

            items.add(UnitDiagramAdapter.DiagramItem.ServiceHeader(
                service  = headerService,
                isFirst  = i == 0,
                gapLabel = gapLabel
            ))

            // ── Calling points ─────────────────────────────────────────────
            if (allPoints.isNotEmpty()) {
                allPoints.forEachIndexed { idx, cp ->
                    items.add(UnitDiagramAdapter.DiagramItem.CallingPoint(
                        cp                   = cp,
                        isOrigin             = idx == 0,
                        isDestination        = idx == allPoints.lastIndex,
                        serviceIsCancelled   = s.isCancelled
                    ))
                }
            } else {
                // No calling points returned — show origin + dest as stub rows
                val originCp = CallingPoint(
                    locationName = s.originCrs?.let { StationData.findByCrs(it)?.name }
                        ?: s.originCrs ?: s.originTiploc,
                    crs          = s.originCrs ?: "",
                    st           = s.scheduledTime,
                    et           = s.actualTime.ifEmpty { "On time" },
                    at           = s.actualTime,
                    isCancelled  = s.isCancelled,
                    length       = null,
                    platform     = s.platform ?: ""
                )
                val destCp = CallingPoint(
                    locationName = s.destCrs?.let { StationData.findByCrs(it)?.name }
                        ?: s.destCrs ?: s.destTiploc,
                    crs          = s.destCrs ?: "",
                    st           = "",
                    et           = "",
                    at           = "",
                    isCancelled  = s.isCancelled,
                    length       = null
                )
                items.add(UnitDiagramAdapter.DiagramItem.CallingPoint(
                    cp = originCp, isOrigin = true, isDestination = false,
                    serviceIsCancelled = s.isCancelled
                ))
                items.add(UnitDiagramAdapter.DiagramItem.CallingPoint(
                    cp = destCp, isOrigin = false, isDestination = true,
                    serviceIsCancelled = s.isCancelled
                ))
            }
        }

        return items
    }

    /**
     * Build a gap/stabling label for the transition between two consecutive services.
     * Shows how long the unit was stabled, or references the "forms" chain.
     */
    private fun buildGapLabel(prev: ServerService, next: ServerService, prevArrivalTime: String = ""): String {
        // Use actual arrival at terminus if available, otherwise scheduled terminus arrival,
        // otherwise fall back to the service's scheduled departure time
        val prevEnd   = prevArrivalTime.ifEmpty { prev.actualTime.ifEmpty { prev.scheduledTime } }
        val nextStart = next.scheduledTime

        val gapMins = gapMinutes(prevEnd, nextStart)

        return buildString {
            if (prev.formsHeadcode.isNotEmpty()) {
                append("→ Forms ${prev.formsHeadcode}")
                if (gapMins > 0) append("  ·  ")
            }
            if (gapMins in 1..599) {
                append("Stabled ${formatDuration(gapMins)}")
            }
        }.trim()
    }

    /** Minutes between two HH:MM or ISO time strings. Returns 0 on any parse failure. */
    private fun gapMinutes(from: String, to: String): Int {
        val f = formatTimeFromIso(from).ifEmpty { from.take(5) }
        val t = formatTimeFromIso(to).ifEmpty  { to.take(5) }
        return durationMinutes(f, t)
    }

    /**
     * Converts a bare [ServerService] into a minimal [TrainService] with just enough
     * fields for the service header row (headcode, route, units, status, split info).
     */
    private fun buildHeaderTrainService(s: ServerService): TrainService {
        val operatorName = TocData.get(s.atocCode)?.name ?: s.atocCode
        val originName   = s.originCrs?.let { StationData.findByCrs(it)?.name } ?: s.originCrs ?: s.originTiploc
        val destName     = s.destCrs?.let   { StationData.findByCrs(it)?.name } ?: s.destCrs   ?: s.destTiploc
        return TrainService(
            std                  = s.scheduledTime,
            etd                  = s.actualTime.ifEmpty { "On time" },
            sta                  = "",
            eta                  = "",
            destination          = destName,
            origin               = originName,
            platform             = s.platform ?: "",
            operator             = operatorName,
            operatorCode         = s.atocCode,
            isCancelled          = s.isCancelled,
            serviceID            = s.uid,
            trainId              = s.headcode,
            boardType            = BoardType.DEPARTURES,
            actualDeparture      = s.actualTime,
            units                = s.units,
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

    private fun showError(msg: String) {
        tvError.text       = msg
        tvError.visibility = View.VISIBLE
    }
}