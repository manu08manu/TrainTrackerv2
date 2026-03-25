package com.traintracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.traintracker.databinding.ActivityStationBoardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StationBoardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStationBoardBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: TrainAdapter

    private var currentCrs = ""
    private var currentBoardType = BoardType.ALL

    private var liveService: DarwinService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as DarwinService.LocalBinder).getService()
            liveService = svc
            viewModel.attachAllocationFormations(svc.allocations)
            viewModel.attachTrustMovements(svc.trustMovements, svc.trustActivations, svc.trustConnected)
            svc.setFilterCrs(currentCrs)
        }
        override fun onServiceDisconnected(name: ComponentName) { liveService = null }
    }

    // Instance of KnowledgebaseService for station lookups
    private val kbService = KnowledgebaseService()

    companion object {
        private const val EXTRA_CRS  = "crs"
        private const val EXTRA_NAME = "name"

        fun start(ctx: Context, crs: String, name: String) {
            ctx.startActivity(Intent(ctx, StationBoardActivity::class.java).apply {
                putExtra(EXTRA_CRS, crs)
                putExtra(EXTRA_NAME, name)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStationBoardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        currentCrs = intent.getStringExtra(EXTRA_CRS) ?: run { finish(); return }
        val stationName = intent.getStringExtra(EXTRA_NAME) ?: currentCrs
        supportActionBar?.title = stationName

        // Tap toolbar title to show station facility info
        binding.toolbar.setOnClickListener {
            showStationInfoDialog(currentCrs, stationName)
        }

        setupTabs()
        setupAdapter()
        setupHeadcodeSearch()
        observeState()
        observeIncidents()
        observeNsi()

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchBoard(currentCrs, currentBoardType)
        }

        bindService(Intent(this, DarwinService::class.java), serviceConnection, BIND_AUTO_CREATE)
        viewModel.fetchBoard(currentCrs, currentBoardType)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }

    // ── Station info dialog ───────────────────────────────────────────────────
    private fun showStationInfoDialog(crs: String, name: String) {
        lifecycleScope.launch {
            val station = withContext(Dispatchers.IO) {
                kbService.getStation(crs)
            }
            if (!isFinishing && !isDestroyed) {
                if (station == null) {
                    AlertDialog.Builder(this@StationBoardActivity)
                        .setTitle("Station Information")
                        .setMessage("No facility data available for this station.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }

                val sb = StringBuilder()
                fun row(label: String, value: String) {
                    if (value.isNotEmpty()) sb.appendLine("$label: $value")
                }

                if (station.address.isNotEmpty()) sb.appendLine(station.address).appendLine()
                row("Phone",          station.telephone)
                row("Staffing",       station.staffingNote)
                row("Ticket office",  station.ticketOfficeHours)
                row("Ticket machine", station.sstmAvailability)
                row("Step-free",      station.stepFreeAccess)
                row("Assistance",     station.assistanceAvail)
                row("WiFi",           station.wifi)
                row("Toilets",        station.toilets)
                row("Waiting room",   station.waitingRoom)
                row("CCTV",           station.cctv)
                row("Taxi",           station.taxi)
                row("Bus interchange",station.busInterchange)
                row("Car parking",    station.carParking)

                val msg = sb.toString().trimEnd().ifEmpty {
                    "No facility details available for $name."
                }

                AlertDialog.Builder(this@StationBoardActivity)
                    .setTitle(name)
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private fun setupTabs() {
        BoardType.values().forEach { type ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(type.label))
        }
        binding.tabLayout.getTabAt(2)?.select()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentBoardType = BoardType.values()[tab.position]
                viewModel.fetchBoard(currentCrs, currentBoardType)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    private fun setupAdapter() {
        adapter = TrainAdapter(
            onServiceClick = { service ->
                val destCrs = service.subsequentCallingPoints.lastOrNull()?.crs ?: ""
                ServiceDetailActivity.start(
                    ctx         = this,
                    serviceId   = service.serviceID,
                    headcode    = service.trainId,
                    origin      = service.origin,
                    destination = service.destination,
                    std         = service.std,
                    etd         = service.etd,
                    queryCrs    = currentCrs,
                    destCrs     = destCrs,
                    units       = service.units,
                    coachCount  = service.darwinCoachCount.takeIf { it in 1..20 } ?: 0,
                    previousCallingPoints   = service.previousCallingPoints,
                    subsequentCallingPoints = service.subsequentCallingPoints,
                    isPassingService        = service.isServicePassing,
                    platform    = service.platform
                )
            }
        )
        binding.rvTrains.layoutManager = LinearLayoutManager(this)

        val swipe = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0, androidx.recyclerview.widget.ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView,
                                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                                t:  androidx.recyclerview.widget.RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {
                val pos     = vh.bindingAdapterPosition
                val service = adapter.currentList.getOrNull(pos) ?: return
                adapter.notifyItemChanged(pos)
                val detail  = adapter.tocDetailLookup?.invoke(service.operatorCode)
                TocInfoBottomSheet.newInstance(service, detail)
                    .show(supportFragmentManager, "toc_info")
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val item = viewHolder.itemView
                val service = adapter.currentList.getOrNull(viewHolder.bindingAdapterPosition)
                if (service != null && dX > 0) {
                    val brandColor = TocData.brandColor(
                        service.operatorCode.ifEmpty { service.operator })
                    val paint = android.graphics.Paint().apply { color = brandColor; alpha = 180 }
                    c.drawRect(item.left.toFloat(), item.top.toFloat(),
                        item.left + dX, item.bottom.toFloat(), paint)
                    val iconPaint = android.graphics.Paint().apply {
                        color       = android.graphics.Color.WHITE
                        textSize    = item.height * 0.45f
                        textAlign   = android.graphics.Paint.Align.LEFT
                        isAntiAlias = true
                    }
                    val textY = item.top + (item.height - iconPaint.textSize) / 2 + iconPaint.textSize
                    c.drawText("ℹ", item.left + 24f, textY, iconPaint)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun getSwipeThreshold(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) = 0.3f
        }
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(swipe)
        touchHelper.attachToRecyclerView(binding.rvTrains)
        binding.rvTrains.adapter = adapter
    }

    // ── Headcode search ───────────────────────────────────────────────────────
    private fun setupHeadcodeSearch() {
        binding.etHeadcode.setOnEditorActionListener { _, _, event ->
            if (event?.keyCode == KeyEvent.KEYCODE_ENTER || event == null) {
                applyHeadcodeFilter(); true
            } else false
        }
        binding.etHeadcode.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && binding.chipHeadcode.visibility != View.VISIBLE) applyHeadcodeFilter()
        }
        binding.chipHeadcode.setOnCloseIconClickListener {
            clearHeadcodeFilter()
        }
    }

    private fun applyHeadcodeFilter() {
        val hc = binding.etHeadcode.text.toString().trim().uppercase()
        if (hc.isEmpty()) { clearHeadcodeFilter(); return }
        viewModel.setHeadcodeFilter(hc)
        binding.chipHeadcode.text = hc
        binding.chipHeadcode.visibility = View.VISIBLE
        hideKeyboard()
    }

    private fun clearHeadcodeFilter() {
        viewModel.clearHeadcodeFilter()
        binding.etHeadcode.setText("")
        binding.chipHeadcode.visibility = View.GONE
        hideKeyboard()
    }

    // ── State observer ────────────────────────────────────────────────────────
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    when (state) {
                        is UiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.tvError.visibility     = View.GONE
                            adapter.submitList(emptyList())
                        }
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.visibility     = View.GONE
                            if (state.board.services.isEmpty()) {
                                binding.tvError.text = "No services found"
                                binding.tvError.visibility = View.VISIBLE
                            } else {
                                adapter.nsiLookup = { code -> viewModel.nsiForOperator(code) }
                                adapter.tocDetailLookup = { code -> viewModel.tocDetails.value[code.uppercase()] }
                                adapter.submitAll(state.board.services, state.board.stationName)
                            }
                            showNrccMessages(state.board.nrccMessages)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.text = "⚠ ${state.message}"
                            binding.tvError.visibility = View.VISIBLE
                            adapter.submitList(emptyList())
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    // ── NRCC messages ─────────────────────────────────────────────────────────
    private fun showNrccMessages(messages: List<String>) {
        android.util.Log.d("StationBoard", "showNrccMessages called: ${messages.size} messages")
        if (messages.isEmpty()) return
        val container = binding.incidentChipContainer
        val toRemove = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.tag == "nrcc" }
        toRemove.forEach { container.removeView(it) }

        messages.reversed().forEach { msg ->
            val severity = when {
                msg.contains("cancel", ignoreCase = true) ||
                        msg.contains("delayed", ignoreCase = true) ||
                        msg.contains("disruption", ignoreCase = true) -> 0xFFB71C1C.toInt()
                else -> 0xFF1565C0.toInt()
            }
            val chip = Chip(this).apply {
                tag = "nrcc"
                text = "ℹ ${msg.take(50)}${if (msg.length > 50) "…" else ""}"
                textSize = 11f
                isClickable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(severity)
                setTextColor(Color.WHITE)
                layoutParams = android.view.ViewGroup.MarginLayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener {
                    AlertDialog.Builder(this@StationBoardActivity)
                        .setTitle("Station Notice")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            container.addView(chip, 0)
        }
        binding.incidentsBanner.visibility = View.VISIBLE
    }

    // ── Incidents banner ──────────────────────────────────────────────────────
    private fun observeIncidents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.incidents.collect { incidents ->
                    val container = binding.incidentChipContainer
                    val toRemove = (0 until container.childCount)
                        .map { container.getChildAt(it) }
                        .filter { it.tag != "nrcc" }
                    toRemove.forEach { container.removeView(it) }
                    val now = java.util.Date().toString()
                    val active = incidents.filter { it.endTime.isEmpty() || it.endTime > now }
                    if (active.isEmpty()) {
                        if (container.childCount == 0) binding.incidentsBanner.visibility = View.GONE
                        return@collect
                    }
                    binding.incidentsBanner.visibility = View.VISIBLE
                    active.forEach { incident ->
                        val chip = Chip(this@StationBoardActivity).apply {
                            text = if (incident.isPlanned) "🔧 ${incident.summary}" else "⚠ ${incident.summary}"
                            textSize = 11f
                            chipBackgroundColor = android.content.res.ColorStateList.valueOf(0xFFB71C1C.toInt())
                            setTextColor(Color.WHITE)
                            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = 8 }
                            setOnClickListener {
                                val msg = if (incident.description.isNotEmpty() &&
                                    incident.description != incident.summary)
                                    "${incident.summary}\n\n${incident.description}"
                                else
                                    incident.summary
                                AlertDialog.Builder(this@StationBoardActivity)
                                    .setTitle("Service Disruption")
                                    .setMessage(msg)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                        container.addView(chip)
                    }
                }
            }
        }
    }

    // ── NSI status strip ──────────────────────────────────────────────────────
    private fun observeNsi() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nsi.collect { nsiList ->
                    val container = binding.nsiChipContainer
                    container.removeAllViews()

                    if (nsiList.isEmpty()) {
                        binding.nsiStrip.visibility = View.GONE
                        return@collect
                    }

                    binding.nsiStrip.visibility = View.VISIBLE
                    val disrupted = nsiList.filter { !it.isGood }

                    if (disrupted.isEmpty()) {
                        val chip = Chip(this@StationBoardActivity).apply {
                            text = "✓ Good service on all operators"
                            textSize = 11f
                            chipBackgroundColor = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
                            setTextColor(Color.WHITE)
                        }
                        container.addView(chip)
                        return@collect
                    }

                    disrupted.forEach { entry ->
                        val bgColor = when (entry.statusLevel) {
                            2    -> 0xFFE65100.toInt()
                            3    -> 0xFFB71C1C.toInt()
                            4    -> 0xFF4A0000.toInt()
                            else -> 0xFF555555.toInt()
                        }
                        val chip = Chip(this@StationBoardActivity).apply {
                            text = "${entry.tocName} · ${entry.statusLabel}"
                            textSize = 11f
                            chipBackgroundColor = android.content.res.ColorStateList.valueOf(bgColor)
                            setTextColor(Color.WHITE)
                            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = 8 }
                            setOnClickListener {
                                // Match incident by TOC code for full description,
                                // fall back to statusLabel if none found
                                val matchingIncident = viewModel.incidents.value
                                    .firstOrNull { inc -> entry.tocCode in inc.operators }

                               val msg = if (matchingIncident != null) {
                                if (matchingIncident.description.isNotEmpty() &&
                                    matchingIncident.description != matchingIncident.summary)
                                    "${matchingIncident.summary}\n\n${matchingIncident.description}"
                                else
                                    matchingIncident.summary
                            } else if (entry.statusDescription.isNotEmpty()) {
                                entry.statusDescription
                            } else {
                                entry.statusLabel
                            }

                                val dialog = AlertDialog.Builder(this@StationBoardActivity)
                                    .setTitle(entry.tocName)
                                    .setMessage(msg)
                                    .setPositiveButton("OK", null)
                                if (entry.customUrl.isNotEmpty()) {
                                    dialog.setNeutralButton("More info") { _, _ ->
                                        startActivity(Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse(entry.customUrl)
                                        ))
                                    }
                                }
                                dialog.show()
                            }
                        }
                        container.addView(chip)
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}