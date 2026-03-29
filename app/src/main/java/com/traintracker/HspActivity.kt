package com.traintracker

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.traintracker.databinding.ActivityHspBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// ─── ViewModel ────────────────────────────────────────────────────────────────

sealed class HspState {
    object Idle    : HspState()
    object Loading : HspState()
    data class Progress(val pct: Int, val results: List<HspServiceRow>) : HspState()
    data class Pending(val message: String) : HspState()
    data class Success(val results: List<HspServiceRow>) : HspState()
    data class Detail(val rows: List<HspServiceRow>, val unitSummary: String) : HspState()
    data class Error(val message: String) : HspState()
}

data class HspServiceRow(
    val rid:           String,
    val scheduledDep:  String,
    val scheduledArr:  String,
    val originName:    String,
    val destName:      String,
    val tocName:       String,
    val punctualityPct: Int,   // -1 = no data
    val total:         Int
)

class HspViewModel : ViewModel() {

    private val server = ServerApiClient()

    private val _state = MutableStateFlow<HspState>(HspState.Idle)
    val state: StateFlow<HspState> = _state.asStateFlow()

    private val _detailState = MutableStateFlow<HspState>(HspState.Idle)
    val detailState: StateFlow<HspState> = _detailState.asStateFlow()

    // Remember last search so retry works
    private var lastFromCrs = ""
    private var lastToCrs   = ""
    private var lastDate    = ""

    fun search(fromCrs: String, toCrs: String, date: String) {
        if (fromCrs.isBlank() || toCrs.isBlank() || date.isBlank()) return
        lastFromCrs = fromCrs; lastToCrs = toCrs; lastDate = date
        _state.value = HspState.Loading
        viewModelScope.launch {
            try {
                server.streamHspMetrics(fromCrs = fromCrs, toCrs = toCrs, fromDate = date)
                    .collect { event ->
                        // Timeout sentinel — server is still caching chunks, retry will be faster
                        if (event.timedOut) {
                            _state.value = HspState.Error(
                                "Search timed out. The server is still building the results — " +
                                        "please try again in a moment."
                            )
                            return@collect
                        }

                        val rows = event.services.map { s ->
                            val originCrs  = CorpusData.crsFromTiploc(s.originTiploc) ?: s.originTiploc
                            val destCrs    = CorpusData.crsFromTiploc(s.destTiploc)   ?: s.destTiploc
                            val originName = StationData.findByCrs(originCrs)?.name
                                ?: CorpusData.nameFromTiploc(s.originTiploc) ?: originCrs
                            val destName   = StationData.findByCrs(destCrs)?.name
                                ?: CorpusData.nameFromTiploc(s.destTiploc) ?: destCrs
                            val tocName    = TocData.get(s.tocCode)?.name ?: s.tocCode
                            HspServiceRow(
                                rid            = s.rid,
                                scheduledDep   = s.scheduledDep,
                                scheduledArr   = s.scheduledArr,
                                originName     = originName,
                                destName       = destName,
                                tocName        = tocName,
                                punctualityPct = s.punctualityPct,
                                total          = s.total
                            )
                        }.sortedBy { it.scheduledDep.ifEmpty { it.scheduledArr } }

                        if (event.progress == 100) {
                            if (rows.isEmpty()) {
                                _state.value = HspState.Error("No services found between $fromCrs and $toCrs on $date")
                            } else {
                                _state.value = HspState.Success(rows)
                            }
                        } else {
                            _state.value = HspState.Progress(event.progress, rows)
                        }
                    }
            } catch (e: Exception) {
                _state.value = HspState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retry() = search(lastFromCrs, lastToCrs, lastDate)

    fun loadDetails(rid: String, scheduledDep: String = "") {
        _detailState.value = HspState.Loading
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { server.getHspDetails(rid, scheduledDep) }
                if (result == null) {
                    _detailState.value = HspState.Error("Could not load service details")
                    return@launch
                }
                // Build unit summary string for the header row
                val unitSummary = buildString {
                    if (result.units.isNotEmpty()) {
                        append(result.units.joinToString(" + "))
                        if (result.unitCount > 1) append(" (${result.unitCount} units)")
                    } else if (result.unit.isNotEmpty()) {
                        append(result.unit)
                    }
                }
                val rows = result.locations.map { loc ->
                    val crs  = CorpusData.crsFromTiploc(loc.tiploc) ?: loc.tiploc
                    val name = StationData.findByCrs(crs)?.name
                        ?: CorpusData.nameFromTiploc(loc.tiploc) ?: loc.tiploc
                    val sched  = loc.scheduledDep.ifEmpty { loc.scheduledArr }
                    val actual = loc.actualDep.ifEmpty { loc.actualArr }
                    val status = when {
                        loc.cancelReason.isNotBlank() -> "Cancelled"
                        actual.isNotEmpty() && sched.isNotEmpty() -> {
                            val delayMins = computeDelay(sched, actual)
                            when {
                                delayMins <= 0  -> "On time ($actual)"
                                else            -> "$actual (+${delayMins}m)"
                            }
                        }
                        actual.isNotEmpty() -> actual
                        else -> "—"
                    }
                    HspServiceRow(
                        rid            = loc.tiploc,
                        scheduledDep   = sched,
                        scheduledArr   = actual,
                        originName     = name,
                        destName       = status,
                        tocName        = if (loc.cancelReason.isNotBlank()) loc.cancelReason else "",
                        punctualityPct = -1,
                        total          = 0
                    )
                }
                _detailState.value = HspState.Detail(rows, unitSummary)
            } catch (e: Exception) {
                _detailState.value = HspState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearDetail() { _detailState.value = HspState.Idle }

    private fun computeDelay(scheduled: String, actual: String): Int {
        return try {
            val (sh, sm) = scheduled.split(":").map { it.toInt() }
            val (ah, am) = actual.split(":").map { it.toInt() }
            (ah * 60 + am) - (sh * 60 + sm)
        } catch (_: Exception) { 0 }
    }
}

// ─── Activity ─────────────────────────────────────────────────────────────────

class HspActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHspBinding
    private val viewModel: HspViewModel by viewModels()

    private var fromCrs  = ""
    private var toCrs    = ""
    private var selectedDate = ""

    private lateinit var resultsAdapter: HspResultsAdapter

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HspActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHspBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Historical Performance"

        setupStationPickers()
        setupDatePicker()
        setupResults()
        observeState()

        // Default date to yesterday
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        selectedDate = "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        binding.btnDate.text = formatHistoricDate(selectedDate)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─── Station pickers ──────────────────────────────────────────────────────

    private fun setupStationPickers() {
        binding.btnFromStation.setOnClickListener { showStationPicker(isFrom = true) }
        binding.btnToStation.setOnClickListener   { showStationPicker(isFrom = false) }
        binding.btnSwapStations.setOnClickListener {
            val tmpCrs  = fromCrs;  fromCrs  = toCrs;  toCrs  = tmpCrs
            val tmpText = binding.btnFromStation.text
            binding.btnFromStation.text = binding.btnToStation.text
            binding.btnToStation.text   = tmpText
        }
        binding.btnSearch.setOnClickListener { doSearch() }
    }

    private fun showStationPicker(isFrom: Boolean) {
        val input = android.widget.AutoCompleteTextView(this).apply {
            hint = "e.g. Manchester, MAN"
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
        var selectedName = ""
        input.setOnItemClickListener { _, view, _, _ ->
            val text = (view as? TextView)?.text?.toString() ?: return@setOnItemClickListener
            selectedCrs  = Regex("\\(([A-Z]{3})\\)$").find(text)?.groupValues?.get(1) ?: ""
            selectedName = text
            input.setText(text)
            input.dismissDropDown()
        }
        AlertDialog.Builder(this)
            .setTitle(if (isFrom) "From station" else "To station")
            .setView(container)
            .setPositiveButton("Select") { _, _ ->
                val crs = selectedCrs.ifEmpty {
                    Regex("\\(([A-Z]{3})\\)$").find(input.text.toString())?.groupValues?.get(1) ?: ""
                }
                val name = selectedName.ifEmpty { input.text.toString() }
                if (crs.isEmpty()) { toast("Please select a station from the list"); return@setPositiveButton }
                if (isFrom) {
                    fromCrs = crs
                    binding.btnFromStation.text = name
                } else {
                    toCrs = crs
                    binding.btnToStation.text = name
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Date picker ──────────────────────────────────────────────────────────

    private fun setupDatePicker() {
        binding.btnDate.setOnClickListener {
            val parts = selectedDate.split("-").map { it.toInt() }
            val dpd = DatePickerDialog(
                this,
                { _, year, month, day ->
                    selectedDate = "%04d-%02d-%02d".format(year, month + 1, day)
                    binding.btnDate.text = formatHistoricDate(selectedDate)
                },
                parts[0], parts[1] - 1, parts[2]
            )
            dpd.datePicker.maxDate = System.currentTimeMillis() - 86_400_000L
            val minCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -730) }
            dpd.datePicker.minDate = minCal.timeInMillis
            dpd.show()
        }
    }

    // ─── Results list ─────────────────────────────────────────────────────────

    private fun setupResults() {
        resultsAdapter = HspResultsAdapter { row ->
            viewModel.loadDetails(row.rid, row.scheduledDep)
        }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvResults.adapter = resultsAdapter
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    private fun doSearch() {
        if (fromCrs.isEmpty()) { toast("Please select a From station"); return }
        if (toCrs.isEmpty())   { toast("Please select a To station"); return }
        if (fromCrs == toCrs)  { toast("From and To stations must be different"); return }
        viewModel.search(fromCrs, toCrs, selectedDate)
    }

    // ─── Observers ────────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        when (state) {
                            is HspState.Idle -> {
                                binding.progressBar.visibility = View.GONE
                                binding.tvStatus.visibility    = View.GONE
                                binding.tvSummary.visibility   = View.GONE
                            }
                            is HspState.Loading -> {
                                binding.progressBar.visibility = View.VISIBLE
                                binding.tvStatus.visibility    = View.VISIBLE
                                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                                binding.tvStatus.text          = "Searching… first search may take up to a minute"
                                binding.tvSummary.visibility   = View.GONE
                                resultsAdapter.submitList(emptyList())
                            }
                            is HspState.Progress -> {
                                binding.progressBar.visibility = View.VISIBLE
                                binding.tvStatus.visibility    = View.VISIBLE
                                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                                binding.tvStatus.text          = "${state.pct}% — ${state.results.size} services so far…"
                                binding.tvSummary.visibility   = View.GONE
                                resultsAdapter.submitList(state.results)
                            }
                            is HspState.Pending -> {
                                binding.progressBar.visibility = View.GONE
                                binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                                binding.tvStatus.text          = "⏳ ${state.message}"
                                binding.tvStatus.visibility    = View.VISIBLE
                            }
                            is HspState.Success -> {
                                binding.progressBar.visibility = View.GONE
                                binding.tvStatus.visibility    = View.GONE
                                binding.tvSummary.text         = "${state.results.size} services · ${formatHistoricDate(selectedDate)}"
                                binding.tvSummary.visibility   = View.VISIBLE
                                resultsAdapter.submitList(state.results)
                            }
                            is HspState.Detail -> {}
                            is HspState.Error -> {
                                binding.progressBar.visibility = View.GONE
                                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                                binding.tvStatus.text          = "⚠ ${state.message}"
                                binding.tvStatus.visibility    = View.VISIBLE
                                resultsAdapter.submitList(emptyList())
                            }
                        }
                    }
                }
                launch {
                    viewModel.detailState.collect { state ->
                        when (state) {
                            is HspState.Idle     -> {}
                            is HspState.Loading  -> showDetailDialog(null, "")
                            is HspState.Progress -> {}
                            is HspState.Pending  -> {}
                            is HspState.Success  -> showDetailDialog(state.results, "")
                            is HspState.Detail   -> showDetailDialog(state.rows, state.unitSummary)
                            is HspState.Error    -> {
                                toast(state.message)
                                viewModel.clearDetail()
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Detail dialog ────────────────────────────────────────────────────────

    private var detailDialog: AlertDialog? = null

    private fun showDetailDialog(rows: List<HspServiceRow>?, unitSummary: String) {
        detailDialog?.dismiss()

        if (rows == null) {
            detailDialog = AlertDialog.Builder(this)
                .setTitle("Loading calling points…")
                .setMessage("Please wait…")
                .setNegativeButton("Cancel") { _, _ -> viewModel.clearDetail() }
                .create()
                .also { it.show() }
            return
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        // Unit header — only shown when we have allocation data
        if (unitSummary.isNotEmpty()) {
            val unitHeader = TextView(this).apply {
                text = "🚆 $unitSummary"
                setPadding(48, 24, 48, 16)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setBackgroundColor(0xFFF3F3F3.toInt())
            }
            container.addView(unitHeader)
        }

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HspActivity)
            addItemDecoration(DividerItemDecoration(this@HspActivity, DividerItemDecoration.VERTICAL))
            adapter = HspDetailAdapter(rows)
            setPadding(0, 8, 0, 8)
        }
        container.addView(rv)

        detailDialog = AlertDialog.Builder(this)
            .setTitle("Calling points")
            .setView(container)
            .setPositiveButton("Close") { _, _ -> viewModel.clearDetail() }
            .setOnDismissListener { viewModel.clearDetail() }
            .create()
            .also { it.show() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ─── Results adapter ──────────────────────────────────────────────────────────

class HspResultsAdapter(
    private val onTap: (HspServiceRow) -> Unit
) : RecyclerView.Adapter<HspResultsAdapter.VH>() {

    private val items = mutableListOf<HspServiceRow>()

    fun submitList(list: List<HspServiceRow>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.two_line_list_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val time = row.scheduledDep.ifEmpty { row.scheduledArr }
        val pct  = if (row.punctualityPct >= 0) "${row.punctualityPct}% on time" else "No data"
        holder.text1.text = "$time  ${row.originName} → ${row.destName}"
        holder.text2.text = "${row.tocName}  ·  $pct  (${row.total} runs)"
        holder.itemView.setOnClickListener { onTap(row) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text1: TextView = v.findViewById(android.R.id.text1)
        val text2: TextView = v.findViewById(android.R.id.text2)
    }
}

// ─── Detail (calling points) adapter ─────────────────────────────────────────

class HspDetailAdapter(
    private val items: List<HspServiceRow>
) : RecyclerView.Adapter<HspDetailAdapter.VH>() {

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.two_line_list_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.text1.text = "${row.scheduledDep.padEnd(5)}  ${row.originName}"
        holder.text2.text = row.destName
        val colour = when {
            row.destName.contains("+")         -> 0xFFE65100.toInt()
            row.destName == "Cancelled"        -> 0xFFB71C1C.toInt()
            row.destName.startsWith("On time") -> 0xFF2E7D32.toInt()
            else -> holder.text2.currentTextColor
        }
        holder.text2.setTextColor(colour)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text1: TextView = v.findViewById(android.R.id.text1)
        val text2: TextView = v.findViewById(android.R.id.text2)
    }
}