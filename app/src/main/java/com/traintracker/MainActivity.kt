package com.traintracker

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.traintracker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var favManager: FavouritesManager
    private lateinit var adapter: TrainAdapter

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }

    private var currentBoardType = BoardType.DEPARTURES
    private var currentCrs       = ""

    // ─── Permission launchers ──────────────────────────────────────────────────
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            findNearestStation()
        }
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        observeUnitBoard()
        observeFilterState()
        observeDarwinState()
        observeIncidents()
        observeNsi()
        observeTick()
        observeRecentStations()
        observeHistoricDate()

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

    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────
    private fun setupAdapter() {
        adapter = TrainAdapter(
            onServiceClick = { service ->
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
                    coachCount  = service.darwinCoachCount,
                    isPassingService = service.isServicePassing,
                    platform    = service.platform,
                    isCancelled = service.isCancelled,
                    cancelReason = service.cancelReason
                )
            }
        )
        binding.rvTrains.layoutManager = LinearLayoutManager(this)
        binding.rvTrains.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    // ─── Tabs ─────────────────────────────────────────────────────────────────
    private fun setupTabs() {
        BoardType.entries.forEach { type ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(type.label))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentBoardType = BoardType.entries[tab.position]
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
        binding.etCrs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
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

        binding.btnHistory.setOnClickListener {
            HspActivity.start(this)
        }
    }

    // ─── Filter chips ─────────────────────────────────────────────────────────
    private fun setupFilterChips() {
        binding.chipCallingAt.setOnClickListener { showCallingAtDialog() }
        binding.chipCallingAt.setOnCloseIconClickListener { viewModel.clearCallingAtFilter() }

        binding.chipOperator.setOnClickListener { showOperatorDialog() }
        binding.chipOperator.setOnCloseIconClickListener { viewModel.clearOperatorFilter() }

        // Time navigation — ±30 min steps; long-press on label opens date picker
        binding.btnTimePrev.setOnClickListener { viewModel.setTimeOffset(viewModel.timeOffset.value - 30) }
        binding.btnTimeNext.setOnClickListener { viewModel.setTimeOffset(viewModel.timeOffset.value + 30) }
        binding.tvTimeOffset.setOnClickListener  {
            // Short tap: if on a historic date, go back to live; if live, reset offset to 0
            if (viewModel.historicDate.value != null) {
                viewModel.setHistoricDate(null)
            } else {
                viewModel.setTimeOffset(0)
            }
        }
        binding.tvTimeOffset.setOnLongClickListener {
            showDatePickerDialog()
            true
        }
    }

    // ─── Date picker ──────────────────────────────────────────────────────────
    private fun showDatePickerDialog() {
        val cal = Calendar.getInstance()
        // Start the picker on today and restrict to past dates only
        val dpd = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val today = Calendar.getInstance()
                val selected = Calendar.getInstance().apply { set(year, month, dayOfMonth) }

                // If today is selected, go back to live mode
                if (selected.get(Calendar.YEAR)         == today.get(Calendar.YEAR) &&
                    selected.get(Calendar.DAY_OF_YEAR)  == today.get(Calendar.DAY_OF_YEAR)) {
                    viewModel.setHistoricDate(null)
                } else {
                    val dateStr = "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
                    viewModel.setHistoricDate(dateStr)
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        // Restrict to past dates only (today inclusive)
        dpd.datePicker.maxDate = System.currentTimeMillis()
        // Restrict to roughly 2 years back (HSP data limit)
        val minCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -730) }
        dpd.datePicker.minDate = minCal.timeInMillis
        dpd.setTitle("Select date (long-press time label = date picker)")
        dpd.show()
    }

    // ─── Observer: historic date label ───────────────────────────────────────
    private fun observeHistoricDate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.historicDate.collect { date ->
                    if (date == null) {
                        // Live mode — tvTimeOffset driven by observeFilterState
                        binding.btnTimePrev.isEnabled = true
                        binding.btnTimeNext.isEnabled = true
                    } else {
                        // Historic mode — show date in the time label
                        binding.tvTimeOffset.text = formatHistoricDate(date)
                        // Disable forward/back arrows in historic mode (use date picker instead)
                        binding.btnTimePrev.isEnabled = false
                        binding.btnTimeNext.isEnabled = false
                    }
                }
            }
        }
    }

    // ─── Calling-at dialog ────────────────────────────────────────────────────
    private fun showCallingAtDialog() {
        val input = AutoCompleteTextView(this).apply {
            hint = getString(R.string.search_hint)
            threshold = 1
        }
        val autoAdapter = StationAutoCompleteAdapter()
        input.setAdapter(autoAdapter)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
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
        if (text.matches(Regex("[0-9][A-Z0-9]{3}", RegexOption.IGNORE_CASE))) {
            searchByHeadcode(text.uppercase())
            return
        }
        // Unit number: all digits (e.g. 375918) OR 2 letters + digits (e.g. HA14, NL13)
        // but NOT a headcode (1 digit + 3 alphanumeric)
        if (text.matches(Regex("[0-9]{4,}", RegexOption.IGNORE_CASE)) ||
            text.matches(Regex("[A-Z]{2}[0-9]+", RegexOption.IGNORE_CASE))) {
            searchByUnit(text.uppercase())
            return
        }
        if (text.length <= 5) {
            StationData.findByCrs(text.take(3))?.let { selectStation(it); return }
        }
        val crsMatch = Regex("\\(([A-Z]{3})\\)$").find(text)
        if (crsMatch != null) { currentCrs = crsMatch.groupValues[1]; saveAndSearch(); return }
        val results = StationData.search(text)
        if (results.isNotEmpty()) { selectStation(results[0]); return }
        currentCrs = text.uppercase().take(3)
        saveAndSearch()
    }

    private fun searchByHeadcode(headcode: String) {
        if (currentCrs.isNotEmpty()) {
            // Station already selected — filter the current board directly
            viewModel.setHeadcodeFilter(headcode)
            binding.chipCallingAt.text = getString(R.string.filter_headcode, headcode)
            binding.chipCallingAt.isChecked = true
            return
        }
        // No station selected — try to locate the train globally via the server
        binding.progressBar.visibility = View.VISIBLE
        viewModel.locateHeadcodeGlobally(
            headcode = headcode,
            onFound = { crs ->
                binding.progressBar.visibility = View.GONE
                val station = StationData.findByCrs(crs)
                if (station != null) {
                    selectStation(station)
                } else {
                    currentCrs = crs
                    binding.etCrs.setText(crs)
                    updateFavouriteButton()
                    saveAndSearch()
                }
                viewModel.setHeadcodeFilter(headcode)
                binding.chipCallingAt.text = getString(R.string.filter_headcode, headcode)
                binding.chipCallingAt.isChecked = true
            },
            onNotFound = {
                binding.progressBar.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setTitle("Headcode not found")
                    .setMessage("$headcode could not be located. It may not be running yet, or the server may not have data for it.\n\nTry selecting a station first to search there.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        )
    }


    private fun searchByUnit(unit: String) {
        currentCrs = ""
        binding.etCrs.setText(unit.uppercase())
        binding.progressBar.visibility = View.VISIBLE
        viewModel.clearHeadcodeBoard()
        viewModel.fetchUnitBoard(
            unit = unit,
            onNotFound = {
                binding.progressBar.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setTitle("Unit not found")
                    .setMessage("$unit wasn't found in today's allocations.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        )
        binding.chipCallingAt.text = "Unit $unit"
        binding.chipCallingAt.isChecked = true
    }

    private fun selectStation(station: Station) {
        currentCrs = station.crs
        binding.etCrs.setText(getString(R.string.station_display, station.name, station.crs))
        saveAndSearch()
    }

    private fun saveAndSearch() {
        viewModel.clearHeadcodeBoard()
        binding.chipCallingAt.text = getString(R.string.filter_calling_at)
        binding.chipCallingAt.isChecked = false
        binding.chipCallingAt.isCloseIconVisible = false
        prefs.edit { putString("last_crs", currentCrs) }
        updateFavouriteButton()
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
                            binding.emptyState.visibility  = View.GONE
                            binding.tvHeader.visibility    = View.GONE
                        }
                        is UiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.tvError.visibility     = View.GONE
                            binding.emptyState.visibility  = View.GONE
                            binding.tvHeader.visibility    = View.GONE
                            adapter.submitList(emptyList())
                        }
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.visibility     = View.GONE
                            binding.tvHeader.visibility    = View.VISIBLE
                            // generatedAt holds the date string for historic boards
                            val genTime = state.board.generatedAt.take(16).replace('T', ' ')
                            binding.tvHeader.text = getString(
                                R.string.board_header,
                                state.board.boardType.label,
                                state.board.stationName,
                                genTime
                            )
                            if (state.board.services.isEmpty()) {
                                binding.emptyState.visibility = View.VISIBLE
                                binding.tvEmptySubtitle.text = when {
                                    viewModel.historicDate.value != null ->
                                        "No services were recorded for this station on that date."
                                    viewModel.filterCallingAt.value.isNotEmpty() ||
                                            viewModel.filterOperator.value.isNotEmpty() ->
                                        "No services match your current filters.\nTry clearing a filter to see more results."
                                    else ->
                                        "There are no trains scheduled in this window.\nTry adjusting the time or checking for disruptions."
                                }
                                adapter.submitList(emptyList())
                            } else {
                                binding.emptyState.visibility = View.GONE
                                adapter.submitAll(state.board.services, state.board.stationName)
                            }
                            showNrccMessages(state.board.nrccMessages)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.emptyState.visibility  = View.GONE
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


    private fun observeUnitBoard() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.unitBoard.collect { state ->
                    if (state == null) return@collect
                    binding.swipeRefresh.isRefreshing = false
                    when (state) {
                        is UiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.tvError.visibility     = View.GONE
                            binding.emptyState.visibility  = View.GONE
                            binding.tvHeader.visibility    = View.GONE
                            adapter.submitList(emptyList())
                        }
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.visibility     = View.GONE
                            binding.tvHeader.visibility    = View.VISIBLE
                            binding.tvHeader.text = "Unit ${state.board.stationName}"
                            if (state.board.services.isEmpty()) {
                                binding.emptyState.visibility = View.VISIBLE
                                binding.tvEmptySubtitle.text = "No services found for this unit today."
                                adapter.submitList(emptyList())
                            } else {
                                binding.emptyState.visibility = View.GONE
                                adapter.submitAll(state.board.services, state.board.stationName)
                            }
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.tvError.text = state.message
                            binding.tvError.visibility = View.VISIBLE
                        }
                        else -> {}
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
                        // Only update the label when not in historic mode
                        if (viewModel.historicDate.value == null) {
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
    }

    private fun observeDarwinState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trustConnected.collect { connected ->
                    if (connected) {
                        binding.tvLiveIndicator.visibility = View.VISIBLE
                        binding.tvLiveIndicator.text = getString(R.string.live_indicator)
                        binding.tvLiveIndicator.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_ontime))
                    } else {
                        binding.tvLiveIndicator.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ─── Incidents banner ─────────────────────────────────────────────────────
    private var currentNrccMessages: List<String> = emptyList()

    private fun showNrccMessages(messages: List<String>) {
        currentNrccMessages = messages
        refreshBanner()
    }

    private fun refreshBanner() {
        val nrcc = currentNrccMessages
        val boardOps = (viewModel.uiState.value as? UiState.Success)
            ?.board?.services?.map { it.operatorCode }?.toSet() ?: emptySet()
        val allIncidents = viewModel.incidents.value
        val kbIncidents = if (boardOps.isEmpty()) allIncidents
        else allIncidents.filter { inc -> inc.operators.isEmpty() || inc.operators.any { it in boardOps } }

        val allMessages: List<String> = nrcc + kbIncidents.map { it.summary }
        if (allMessages.isEmpty()) {
            binding.bannerIncidents.visibility = View.GONE
            return
        }
        binding.bannerIncidents.visibility = View.VISIBLE
        binding.tvBannerSummary.text = allMessages.first()
        val extra = allMessages.size - 1
        if (extra > 0) {
            binding.tvBannerMore.text = getString(R.string.banner_more_incidents, extra)
            binding.tvBannerMore.visibility = View.VISIBLE
        } else {
            binding.tvBannerMore.visibility = View.GONE
        }
        binding.bannerIncidents.setOnClickListener {
            val nrccAsIncidents = nrcc.map { msg ->
                KbIncident(id = "nrcc", summary = msg, description = "", startTime = "",
                    endTime = "", operators = emptyList(), isPlanned = false)
            }
            showIncidentsDialog(nrccAsIncidents + kbIncidents)
        }
    }

    private fun observeIncidents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.incidents.collect { refreshBanner() }
            }
        }
    }

    // ─── NSI status dots ──────────────────────────────────────────────────────
    private fun observeNsi() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nsi.collect {
                    adapter.nsiLookup      = { code -> viewModel.nsiForOperator(code) }
                    adapter.tocDetailLookup = { code -> viewModel.tocDetails.value[code.uppercase()] }
                }
            }
        }
    }

    // ─── Tick → redraw countdowns ─────────────────────────────────────────────
    private fun observeTick() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tick.collect {
                    adapter.notifyTick()
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
            binding.tvRecentsLabel.visibility   = View.GONE
            binding.chipGroupRecents.visibility = View.GONE
            return
        }
        binding.tvRecentsLabel.visibility   = View.VISIBLE
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