package com.traintracker

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.traintracker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var favManager: FavouritesManager
    private lateinit var adapter: TrainAdapter

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }

    private var currentBoardType = BoardType.DEPARTURES
    private var currentCrs       = ""

    // ─── Darwin service connection ─────────────────────────────────────────────
    private var darwinService: DarwinService? = null
    private val darwinConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as DarwinService.LocalBinder).getService()
            darwinService = svc
            viewModel.attachDarwinUpdates(svc.updates, svc.connectionState)
            viewModel.attachDarwinFormations(svc.formations)
            viewModel.attachTrustMovements(svc.trustMovements, svc.trustActivations, svc.trustConnected)
            if (currentCrs.isNotEmpty()) svc.setFilterCrs(currentCrs)
        }
        override fun onServiceDisconnected(name: ComponentName) { darwinService = null }
    }

    // ─── Permission launchers ──────────────────────────────────────────────────
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            findNearestStation()
        }
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — service already running, just won't show notification */ }

    // ─── onCreate ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved theme before inflating
        val dark = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        favManager = FavouritesManager(this)

        setupAdapter()
        setupTabs()
        setupSearch()
        setupButtons()
        setupFilterChips()
        setupFavouriteChips()
        observeUiState()
        observeFilterState()
        observeDarwinState()
        observeIncidents()
        observeTick()
        observeRecentStations()

        // Restore last searched station
        val lastCrs = prefs.getString("last_crs", "") ?: ""
        if (lastCrs.isNotEmpty()) {
            currentCrs = lastCrs
            binding.etCrs.setText(
                StationData.findByCrs(lastCrs)
                    ?.let { getString(R.string.station_display, it.name, it.crs) }
                    ?: lastCrs
            )
            updateFavouriteButton()
            search()
        }

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start and bind to DarwinService — moved to onStart/onStop so the
        // service stops when the app is backgrounded, not just when destroyed.
    }

    override fun onStart() {
        super.onStart()
        DarwinService.start(this)
        bindService(
            Intent(this, DarwinService::class.java),
            darwinConnection,
            BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        unbindService(darwinConnection)
        darwinService = null
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────
    private fun setupAdapter() {
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
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    // ─── Tabs ─────────────────────────────────────────────────────────────────
    private fun setupTabs() {
        BoardType.values().forEach { type ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(type.label))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentBoardType = BoardType.values()[tab.position]
                if (currentCrs.isNotEmpty()) search()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ─── Search bar ───────────────────────────────────────────────────────────
    private fun setupSearch() {
        val autoAdapter = StationAutoCompleteAdapter()
        binding.etCrs.setAdapter(autoAdapter)
        binding.etCrs.threshold = 1
        binding.etCrs.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                autoAdapter.updateItems(StationData.search(s?.toString() ?: return))
            }
        })
        binding.etCrs.setOnItemClickListener { _, view, _, _ ->
            val text = (view as? TextView)?.text?.toString() ?: return@setOnItemClickListener
            val crs  = Regex("\\(([A-Z]{3})\\)$").find(text)?.groupValues?.get(1) ?: return@setOnItemClickListener
            StationData.findByCrs(crs)?.let { selectStation(it) }
        }
        binding.etCrs.setOnEditorActionListener { _, _, event ->
            if (event?.keyCode == KeyEvent.KEYCODE_ENTER || event == null) {
                performSearch(); true
            } else false
        }
        binding.btnSearch.setOnClickListener { performSearch() }
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnGps.setOnClickListener { requestLocationPermission() }

        binding.btnFavourite.setOnClickListener {
            if (currentCrs.isEmpty()) return@setOnClickListener
            val station = StationData.findByCrs(currentCrs)
            if (favManager.isFavourite(currentCrs)) {
                favManager.remove(currentCrs)
            } else {
                favManager.add(currentCrs, station?.name ?: currentCrs)
            }
            setupFavouriteChips()
            updateFavouriteButton()
        }

        // Station info button — long-press on star or dedicated info icon
        binding.btnStationInfo.setOnClickListener {
            if (currentCrs.isEmpty()) { toast("Search for a station first"); return@setOnClickListener }
            val name = StationData.findByCrs(currentCrs)?.name ?: currentCrs
            StationInfoActivity.start(this, currentCrs, name)
        }

        binding.btnDarkMode.setOnClickListener {
            val newDark = !prefs.getBoolean("dark_mode", false)
            prefs.edit { putBoolean("dark_mode", newDark) }
            AppCompatDelegate.setDefaultNightMode(
                if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Not in normal passenger service chip
        binding.chipShowFreight.isChecked = prefs.getBoolean("show_freight", true)
        binding.chipShowFreight.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("show_freight", checked) }
            adapter.setShowFreight(checked)
        }
    }

    // ─── Filter chips ─────────────────────────────────────────────────────────
    private fun setupFilterChips() {
        // Calling at
        binding.chipCallingAt.setOnClickListener { showCallingAtDialog() }
        binding.chipCallingAt.setOnCloseIconClickListener { viewModel.clearCallingAtFilter() }

        // Operator
        binding.chipOperator.setOnClickListener { showOperatorDialog() }
        binding.chipOperator.setOnCloseIconClickListener { viewModel.clearOperatorFilter() }

        // Time navigation — ±30 min steps
        binding.btnTimePrev.setOnClickListener { viewModel.setTimeOffset(viewModel.timeOffset.value - 30) }
        binding.btnTimeNext.setOnClickListener { viewModel.setTimeOffset(viewModel.timeOffset.value + 30) }
        binding.tvTimeOffset.setOnClickListener  { viewModel.setTimeOffset(0) }
    }

    // ─── Calling-at dialog ────────────────────────────────────────────────────
    private fun showCallingAtDialog() {
        val input = android.widget.AutoCompleteTextView(this).apply {
            hint = getString(R.string.search_hint)
            threshold = 1
        }
        val autoAdapter = StationAutoCompleteAdapter()
        input.setAdapter(autoAdapter)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                autoAdapter.updateItems(StationData.search(s?.toString() ?: return))
            }
        })
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, 8, padding, 0)
            addView(input)
        }
        var selectedCrs = ""
        input.setOnItemClickListener { _, view, _, _ ->
            val text = (view as? TextView)?.text?.toString() ?: return@setOnItemClickListener
            selectedCrs = Regex("\\(([A-Z]{3})\\)$").find(text)?.groupValues?.get(1) ?: ""
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_calling_at_title))
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val crs = selectedCrs.ifEmpty {
                    Regex("\\(([A-Z]{3})\\)$").find(input.text.toString())?.groupValues?.get(1) ?: ""
                }
                if (crs.isNotEmpty()) viewModel.setCallingAtFilter(crs)
            }
            .setNeutralButton(getString(R.string.dialog_clear)) { _, _ -> viewModel.clearCallingAtFilter() }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ─── Operator dialog ──────────────────────────────────────────────────────
    private fun showOperatorDialog() {
        val operators = viewModel.availableOperators.value
        if (operators.isEmpty()) { toast("Load a board first"); return }
        val items = arrayOf(getString(R.string.dialog_clear)) + operators.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_operator_title))
            .setItems(items) { _, which ->
                if (which == 0) viewModel.clearOperatorFilter()
                else viewModel.setOperatorFilter(operators[which - 1])
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ─── Incidents dialog ─────────────────────────────────────────────────────
    private fun showIncidentsDialog(incidents: List<KbIncident>) {
        if (incidents.isEmpty()) return
        val msg = incidents.joinToString("\n\n") { inc ->
            buildString {
                append(if (inc.isPlanned) "🔧 " else "⚠ ")
                append(inc.summary)
                if (inc.description.isNotEmpty() && inc.description != inc.summary) {
                    append("\n")
                    append(inc.description.take(300))
                    if (inc.description.length > 300) append("…")
                }
                if (inc.endTime.isNotEmpty()) append("\nExpected clear: ${inc.endTime.take(16).replace('T',' ')}")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Service Disruptions")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ─── Search logic ─────────────────────────────────────────────────────────
    private fun performSearch() {
        hideKeyboard()
        val text = binding.etCrs.text.toString().trim()
        if (text.isEmpty()) return
        // Detect headcode search: 4-char alphanumeric starting with a digit e.g. "1Z86", "6M50"
        if (text.matches(Regex("[0-9][A-Z0-9]{3}", RegexOption.IGNORE_CASE))) {
            searchByHeadcode(text.uppercase())
            return
        }
        // Try direct CRS
        if (text.length <= 5) {
            StationData.findByCrs(text.take(3))?.let { selectStation(it); return }
        }
        // Try CRS in autocomplete format "Name (CRS)"
        val crsMatch = Regex("\\(([A-Z]{3})\\)$").find(text)
        if (crsMatch != null) { currentCrs = crsMatch.groupValues[1]; saveAndSearch(); return }
        // Try name search
        val results = StationData.search(text)
        if (results.isNotEmpty()) { selectStation(results[0]); return }
        // Fallback — treat first 3 chars as CRS
        currentCrs = text.uppercase().take(3)
        saveAndSearch()
    }

    private fun searchByHeadcode(headcode: String) {
        if (currentCrs.isEmpty()) {
            // No station yet — ask user to pick one first
            android.app.AlertDialog.Builder(this)
                .setTitle("Search by headcode")
                .setMessage("Enter a station to search $headcode at:")
                .setPositiveButton("OK") { _, _ ->
                    toast("Enter a station in the search box, then type $headcode again")
                }
                .show()
            return
        }
        viewModel.setHeadcodeFilter(headcode)
        // Show headcode as a dismissible chip so user knows filter is active
        binding.chipCallingAt.text = "Headcode: $headcode"
        binding.chipCallingAt.isChecked = true
    }

    private fun clearHeadcodeFilter() {
        viewModel.clearHeadcodeFilter()
        binding.chipCallingAt.text = getString(R.string.filter_calling_at)
        binding.chipCallingAt.isChecked = false
    }

    private fun selectStation(station: Station) {
        currentCrs = station.crs
        binding.etCrs.setText(getString(R.string.station_display, station.name, station.crs))
        saveAndSearch()
    }

    private fun saveAndSearch() {
        prefs.edit { putString("last_crs", currentCrs) }
        darwinService?.setFilterCrs(currentCrs)
        updateFavouriteButton()
        // Record in recents
        val name = StationData.findByCrs(currentCrs)?.name ?: currentCrs
        viewModel.recordRecentStation(currentCrs, name)
        search()
    }

    private fun search() { viewModel.fetchBoard(currentCrs, currentBoardType) }

    // ─── GPS ──────────────────────────────────────────────────────────────────
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            findNearestStation()
        } else {
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun findNearestStation() {
        try {
            val lm  = getSystemService(LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (loc != null) {
                val nearest = StationData.findNearest(loc.latitude, loc.longitude)
                if (nearest != null) { selectStation(nearest); toast(getString(R.string.nearest_station, nearest.name)) }
                else toast(getString(R.string.no_station_found))
            } else toast(getString(R.string.location_unavailable))
        } catch (_: SecurityException) {
            toast(getString(R.string.location_not_granted))
        }
    }

    // ─── Favourites ───────────────────────────────────────────────────────────
    private fun setupFavouriteChips() {
        binding.chipGroupFavourites.removeAllViews()
        val favs    = favManager.getAll()
        val visible = favs.isNotEmpty()
        binding.tvFavLabel.visibility          = if (visible) View.VISIBLE else View.GONE
        binding.chipGroupFavourites.visibility = if (visible) View.VISIBLE else View.GONE
        favs.forEach { (crs, name) ->
            val chip = Chip(this).apply {
                text = if (name.isNotEmpty() && name != crs) "$name ($crs)" else crs
                isCloseIconVisible = true
                setOnClickListener {
                    currentCrs = crs
                    binding.etCrs.setText(
                        StationData.findByCrs(crs)?.let { getString(R.string.station_display, it.name, it.crs) } ?: crs
                    )
                    updateFavouriteButton()
                    saveAndSearch()
                }
                setOnCloseIconClickListener {
                    favManager.remove(crs); setupFavouriteChips(); updateFavouriteButton()
                }
            }
            binding.chipGroupFavourites.addView(chip)
        }
    }

    private fun updateFavouriteButton() {
        binding.btnFavourite.setImageResource(
            if (currentCrs.isNotEmpty() && favManager.isFavourite(currentCrs))
                R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    // ─── Observers ────────────────────────────────────────────────────────────
    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    when (state) {
                        is UiState.Idle -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.visibility     = View.GONE
                            binding.tvHeader.visibility    = View.GONE
                        }
                        is UiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.tvError.visibility     = View.GONE
                            binding.tvHeader.visibility    = View.GONE
                            adapter.submitList(emptyList())
                        }
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.visibility     = View.GONE
                            binding.tvHeader.visibility    = View.VISIBLE
                            val genTime = state.board.generatedAt.take(16).replace('T', ' ')
                            binding.tvHeader.text = getString(
                                R.string.board_header,
                                state.board.boardType.label,
                                state.board.stationName,
                                genTime
                            )
                            if (state.board.services.isEmpty()) {
                                binding.tvError.text = getString(R.string.no_services_found)
                                binding.tvError.visibility = View.VISIBLE
                            } else {
                                adapter.submitAll(state.board.services, state.board.stationName)
                            }
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.text = getString(R.string.error_prefix, state.message)
                            binding.tvError.visibility  = View.VISIBLE
                            binding.tvHeader.visibility = View.GONE
                            adapter.submitList(emptyList())
                        }
                    }
                }
            }
        }
    }

    private fun observeFilterState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filterCallingAt.collect { crs ->
                        if (crs.isEmpty()) {
                            binding.chipCallingAt.text = getString(R.string.filter_calling_at)
                            binding.chipCallingAt.isCloseIconVisible = false
                        } else {
                            val name = StationData.findByCrs(crs)?.name ?: crs
                            binding.chipCallingAt.text = getString(R.string.filter_calling_at_active, name)
                            binding.chipCallingAt.isCloseIconVisible = true
                        }
                    }
                }
                launch {
                    viewModel.filterOperator.collect { op ->
                        if (op.isEmpty()) {
                            binding.chipOperator.text = getString(R.string.filter_operator)
                            binding.chipOperator.isCloseIconVisible = false
                        } else {
                            binding.chipOperator.text = getString(R.string.filter_operator_active, op)
                            binding.chipOperator.isCloseIconVisible = true
                        }
                    }
                }
                launch {
                    viewModel.timeOffset.collect { offset ->
                        binding.tvTimeOffset.text = when {
                            offset == 0 -> getString(R.string.time_now)
                            offset > 0  -> getString(R.string.time_offset_plus, offset)
                            else        -> getString(R.string.time_offset_minus, -offset)
                        }
                    }
                }
            }
        }
    }

    private fun observeDarwinState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.darwinConnectionState.collect { state ->
                    when (state) {
                        is DarwinConnectionState.Connected -> {
                            binding.tvLiveIndicator.visibility = View.VISIBLE
                            binding.tvLiveIndicator.text = getString(R.string.live_indicator)
                            binding.tvLiveIndicator.setTextColor(getColor(android.R.color.holo_green_dark))
                        }
                        is DarwinConnectionState.Connecting -> {
                            binding.tvLiveIndicator.visibility = View.VISIBLE
                            binding.tvLiveIndicator.text = getString(R.string.connecting_indicator)
                            binding.tvLiveIndicator.setTextColor(getColor(android.R.color.holo_orange_dark))
                        }
                        is DarwinConnectionState.Error -> {
                            binding.tvLiveIndicator.visibility = View.VISIBLE
                            binding.tvLiveIndicator.text = getString(R.string.feed_error_indicator)
                            binding.tvLiveIndicator.setTextColor(getColor(android.R.color.holo_red_dark))
                        }
                        is DarwinConnectionState.Disconnected ->
                            binding.tvLiveIndicator.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ─── Incidents banner ─────────────────────────────────────────────────────
    private fun observeIncidents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.incidents.collect { all ->
                    // Filter to incidents relevant to what's shown, or show all if station unknown
                    val relevant = if (all.isEmpty()) emptyList()
                    else {
                        // Try to match against current board's operators
                        val boardOps = (viewModel.uiState.value as? UiState.Success)
                            ?.board?.services?.map { it.operatorCode }?.toSet() ?: emptySet()
                        if (boardOps.isEmpty()) all
                        else all.filter { inc ->
                            inc.operators.isEmpty() || inc.operators.any { it in boardOps }
                        }
                    }

                    if (relevant.isEmpty()) {
                        binding.bannerIncidents.visibility = View.GONE
                    } else {
                        binding.bannerIncidents.visibility = View.VISIBLE
                        binding.tvBannerSummary.text = relevant.first().summary
                        if (relevant.size > 1) {
                            binding.tvBannerMore.text = getString(R.string.banner_more_incidents, relevant.size - 1)
                            binding.tvBannerMore.visibility = View.VISIBLE
                        } else {
                            binding.tvBannerMore.visibility = View.GONE
                        }
                        binding.bannerIncidents.setOnClickListener { showIncidentsDialog(relevant) }
                    }
                }
            }
        }
    }

    // ─── Tick → redraw countdowns ─────────────────────────────────────────────
    private fun observeTick() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tick.collect {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
        }
    }

    // ─── Recent stations observer ─────────────────────────────────────────────
    private fun observeRecentStations() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentStations.collect { recents ->
                    buildRecentChips(recents)
                }
            }
        }
    }

    private fun buildRecentChips(recents: List<RecentStation>) {
        binding.chipGroupRecents.removeAllViews()
        if (recents.isEmpty()) {
            binding.tvRecentsLabel.visibility  = View.GONE
            binding.chipGroupRecents.visibility = View.GONE
            return
        }
        binding.tvRecentsLabel.visibility  = View.VISIBLE
        binding.chipGroupRecents.visibility = View.VISIBLE
        recents.forEach { recent ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = if (recent.name.isNotEmpty() && recent.name != recent.crs)
                    "${recent.name} (${recent.crs})" else recent.crs
                isCloseIconVisible = true
                setOnClickListener {
                    currentCrs = recent.crs
                    binding.etCrs.setText(
                        StationData.findByCrs(recent.crs)
                            ?.let { getString(R.string.station_display, it.name, it.crs) }
                            ?: recent.crs
                    )
                    updateFavouriteButton()
                    saveAndSearch()
                }
                setOnCloseIconClickListener {
                    viewModel.clearRecentStation(recent.crs)
                }
            }
            binding.chipGroupRecents.addView(chip)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ─── Station autocomplete adapter ─────────────────────────────────────────────
class StationAutoCompleteAdapter : BaseAdapter(), Filterable {
    private var items: List<Station> = emptyList()

    fun updateItems(new: List<Station>) {
        items = new
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
        val tv = (convertView as? TextView) ?: TextView(parent.context).apply {
            setPadding(32, 24, 32, 24)
        }
        val s = items[pos]
        tv.text = "${s.name} (${s.crs})"
        return tv
    }

    override fun getFilter() = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults()
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {}
    }
}
