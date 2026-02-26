package com.traintracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.traintracker.databinding.ActivityStationBoardBinding
import kotlinx.coroutines.launch

/**
 * Full station board showing arrivals, departures, and all services.
 * Opened by tapping a station in the calling points list.
 *
 * Binds to DarwinService so that:
 *   - TRUST movements flow in → headcodes are enriched in real time
 *   - Freight / ECS / light engine synthetic services are created by the ViewModel
 */
class StationBoardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStationBoardBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: TrainAdapter

    private var currentCrs = ""
    private var currentBoardType = BoardType.ALL

    // ── DarwinService binding ─────────────────────────────────────────────────
    private var darwinService: DarwinService? = null
    private val darwinConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as DarwinService.LocalBinder).getService()
            darwinService = svc
            // Hook TRUST + Darwin into this activity's ViewModel
            viewModel.attachDarwinUpdates(svc.updates, svc.connectionState)
            viewModel.attachDarwinFormations(svc.formations)
            viewModel.attachTrustMovements(svc.trustMovements, svc.trustActivations, svc.trustConnected)
            // Tell both clients which station we're looking at
            svc.setFilterCrs(currentCrs)
        }
        override fun onServiceDisconnected(name: ComponentName) { darwinService = null }
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

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchBoard(currentCrs, currentBoardType)
        }

        // Start & bind DarwinService (started once app-wide, safe to call again)
        bindService(
            Intent(this, DarwinService::class.java),
            darwinConnection,
            BIND_AUTO_CREATE
        )

        viewModel.fetchBoard(currentCrs, currentBoardType)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(darwinConnection)
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

    // ── Adapter + freight chip ────────────────────────────────────────────────
    private fun setupAdapter() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val showFreight = prefs.getBoolean("show_freight", true)

        adapter = TrainAdapter(showFreight = showFreight) { service ->
            ServiceDetailActivity.start(
                ctx         = this,
                serviceId   = service.serviceID,
                headcode    = service.trainId,
                origin      = service.origin,
                destination = service.destination,
                std         = service.std,
                etd         = service.etd,
                queryCrs    = currentCrs,
                units       = service.units,
                coachCount  = service.darwinCoachCount
            )
        }
        binding.rvTrains.layoutManager = LinearLayoutManager(this)
        binding.rvTrains.adapter = adapter

        // Freight chip — shared pref key matches MainActivity ("show_freight" in "app_prefs")
        binding.chipShowFreight.isChecked = showFreight
        binding.chipShowFreight.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("show_freight", checked) }
            adapter.setShowFreight(checked)
        }
    }

    // ── Headcode search ───────────────────────────────────────────────────────
    private fun setupHeadcodeSearch() {
        // Apply filter on keyboard "Search" action
        binding.etHeadcode.setOnEditorActionListener { _, _, event ->
            if (event?.keyCode == KeyEvent.KEYCODE_ENTER || event == null) {
                applyHeadcodeFilter(); true
            } else false
        }

        // Also apply on focus-lost so swipe-to-refresh doesn't lose it
        binding.etHeadcode.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyHeadcodeFilter()
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
                                adapter.submitAll(state.board.services, state.board.stationName)
                            }
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

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
