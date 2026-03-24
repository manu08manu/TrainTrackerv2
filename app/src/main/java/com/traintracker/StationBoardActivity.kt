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
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.traintracker.databinding.ActivityStationBoardBinding
import kotlinx.coroutines.launch

/**
 * Full station board showing arrivals, departures, and all services.
 * Opened by tapping a station in the calling points list.
 *
 * Binds to DarwinService for TRUST movements, Allocation and VSTP data.
 */
class StationBoardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStationBoardBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: TrainAdapter

    private var currentCrs = ""
    private var currentBoardType = BoardType.ALL

    // ── Service binding (TRUST + Allocation + VSTP) ───────────────────────────
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

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private fun setupTabs() {
        BoardType.values().forEach { type ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(type.label))
        }
        // Default to ALL (index 2)
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

    // ── Adapter + category chips ──────────────────────────────────────────────
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
        binding.rvTrains.adapter = adapter

    }

    // ── Headcode search ───────────────────────────────────────────────────────
    private fun setupHeadcodeSearch() {
        // Apply filter on keyboard "Search" action
        binding.etHeadcode.setOnEditorActionListener { _, _, event ->
            if (event?.keyCode == KeyEvent.KEYCODE_ENTER || event == null) {
                applyHeadcodeFilter(); true
            } else false
        }

        // Also apply on focus-lost so swipe-to-refresh doesn't lose it.
        // Guard: if the chip is already visible, focus loss is likely from tapping the close
        // icon — don't re-apply the filter or it fights with onCloseIconClickListener.
        binding.etHeadcode.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && binding.chipHeadcode.visibility != View.VISIBLE) applyHeadcodeFilter()
        }

        // Dismiss chip clears the filter
        binding.chipHeadcode.setOnCloseIconClickListener {
            clearHeadcodeFilter()
        }
    }

    private fun applyHeadcodeFilter() {
        val hc = binding.etHeadcode.text.toString().trim().uppercase()
        if (hc.isEmpty()) {
            clearHeadcodeFilter()
            return
        }
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

    // ── NRCC messages (from board response) ──────────────────────────────────
    private fun showNrccMessages(messages: List<String>) {
        android.util.Log.d("StationBoard", "showNrccMessages called: ${messages.size} messages")
        if (messages.isEmpty()) {
            // Don't hide the banner — KB incidents may still be showing
            return
        }
        val container = binding.incidentChipContainer
        // Remove existing NRCC chips (tagged) before re-adding
        val toRemove = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.tag == "nrcc" }
        toRemove.forEach { container.removeView(it) }

        // Add each NRCC message as a chip at the front of the container
        messages.reversed().forEach { msg ->
            val severity = when {
                msg.contains("cancel", ignoreCase = true) ||
                        msg.contains("delayed", ignoreCase = true) ||
                        msg.contains("disruption", ignoreCase = true) -> 0xFFB71C1C.toInt()  // red
                else -> 0xFF1565C0.toInt()  // blue for info (lifts etc)
            }
            val chip = com.google.android.material.chip.Chip(this).apply {
                tag = "nrcc"
                text = "ℹ ${msg.take(50)}${if (msg.length > 50) "…" else ""}"
                textSize = 11f
                isClickable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(severity)
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.view.ViewGroup.MarginLayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@StationBoardActivity)
                        .setTitle("Station Notice")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            container.addView(chip, 0)
        }
        binding.incidentsBanner.visibility = android.view.View.VISIBLE
    }

    // ── Incidents banner ──────────────────────────────────────────────────────
    private fun observeIncidents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.incidents.collect { incidents ->
                    val container = binding.incidentChipContainer
                    // Remove only KB incident chips (untagged), preserve NRCC chips (tagged "nrcc")
                    val toRemove = (0 until container.childCount)
                        .map { container.getChildAt(it) }
                        .filter { it.tag != "nrcc" }
                    toRemove.forEach { container.removeView(it) }
                    val now = java.util.Date().toString()
                    val active = incidents.filter { it.endTime.isEmpty() || it.endTime > now }
                    // Only hide banner if both KB incidents AND NRCC chips are absent
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
                                val msg = if (incident.description.isNotEmpty() && incident.description != incident.summary)
                                    "${incident.summary}\n\n${incident.description}"
                                else
                                    incident.summary
                                androidx.appcompat.app.AlertDialog.Builder(this@StationBoardActivity)
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
                        // Data not loaded yet — hide
                        binding.nsiStrip.visibility = View.GONE
                        return@collect
                    }

                    binding.nsiStrip.visibility = View.VISIBLE
                    val disrupted = nsiList.filter { !it.isGood }

                    if (disrupted.isEmpty()) {
                        // All good — show a single green confirmation chip
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
                                val incident = viewModel.incidents.value
                                    .firstOrNull { inc -> entry.tocCode in inc.operators }
                                val msg = if (incident != null)
                                    "${incident.summary}\n\n${incident.description}"
                                else
                                    entry.statusLabel
                                androidx.appcompat.app.AlertDialog.Builder(this@StationBoardActivity)
                                    .setTitle(entry.tocName)
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

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}