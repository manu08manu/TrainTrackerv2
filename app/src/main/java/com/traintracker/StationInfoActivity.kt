package com.traintracker
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traintracker.databinding.ActivityStationInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetTextI18n")
class StationInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStationInfoBinding
    private val server = ServerApiClient()

    companion object {
        private const val EXTRA_CRS  = "crs"
        private const val EXTRA_NAME = "name"

        fun start(ctx: Context, crs: String, name: String) {
            ctx.startActivity(Intent(ctx, StationInfoActivity::class.java).apply {
                putExtra(EXTRA_CRS,  crs)
                putExtra(EXTRA_NAME, name)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStationInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val crs  = intent.getStringExtra(EXTRA_CRS)  ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: crs

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = name
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadStation(crs)
    }

    private fun loadStation(crs: String) {
        binding.progressBar.visibility   = View.VISIBLE
        binding.tvError.visibility       = View.GONE
        binding.scrollContent.visibility = View.GONE

        lifecycleScope.launch {
            val station  = withContext(Dispatchers.IO) { server.getKbStation(crs) }
            val messages = withContext(Dispatchers.IO) { server.getKbStationMessages(crs) }

            binding.progressBar.visibility = View.GONE

            if (station == null) {
                binding.tvError.text = getString(R.string.info_load_error)
                binding.tvError.visibility = View.VISIBLE
                return@launch
            }

            bindStation(station)
            bindAlerts(messages)
            binding.scrollContent.visibility = View.VISIBLE
        }
    }

    private fun bindAlerts(messages: KbStationMessages?) {
        if (messages == null) { binding.cardAlerts.visibility = View.GONE; return }
        val sb = StringBuilder()
        messages.disruptions.forEach { d ->
            if (d.summary.isNotEmpty()) sb.appendLine("⚠ ${d.summary}")
        }
        if (messages.stationAlerts.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.append("ℹ ${messages.stationAlerts}")
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) { binding.cardAlerts.visibility = View.GONE; return }
        binding.tvAlerts.text = text
        binding.cardAlerts.visibility = View.VISIBLE
    }

    private fun bindStation(s: KbStation) {
        bindOrHide(binding.tvAddress, s.address)
        bindOrHide(binding.tvStaffing, mapStaffing(s.staffingNote))

        // ── Tickets ──────────────────────────────────────────────────────
        bindOrHide(binding.tvTicketOffice,
            if (s.ticketOfficeHours.isNotEmpty()) "🎟 Ticket office: ${s.ticketOfficeHours}"
            else getString(R.string.info_no_ticket_office))
        bindOrHide(binding.tvSstm,
            if (s.sstmAvailability.isNotEmpty()) "🖥 Self-service machines: ${s.sstmAvailability}" else "")
        bindOrHide(binding.tvTicketGates,
            if (s.ticketGates.isNotEmpty()) "🚧 Ticket gates: ${s.ticketGates}" else "")
        hideSectionIfEmpty(binding.tvHeaderTickets, binding.cardTickets,
            binding.tvTicketOffice, binding.tvSstm, binding.tvTicketGates)

        // ── Accessibility ─────────────────────────────────────────────────
        bindOrHide(binding.tvStepFree,
            if (s.stepFreeAccess.isNotEmpty()) "♿ Step-free: ${s.stepFreeAccess}" else "")
        bindOrHide(binding.tvAssistance,
            if (s.assistanceAvail.isNotEmpty()) "🤝 Assistance available: ${s.assistanceAvail}" else "")
        bindOrHide(binding.tvRamp,
            if (s.rampForTrainAccess.isNotEmpty()) "📐 Ramp for train access: ${s.rampForTrainAccess}" else "")
        bindOrHide(binding.tvWheelchairs,
            if (s.wheelchairsAvailable.isNotEmpty()) "🦽 Wheelchairs: ${s.wheelchairsAvailable}" else "")
        bindOrHide(binding.tvInductionLoop,
            if (s.inductionLoop.isNotEmpty()) "🔊 Induction loop: ${s.inductionLoop}" else "")
        bindOrHide(binding.tvAccessibleTicketMachines,
            if (s.accessibleTicketMachines.isNotEmpty()) "♿ Accessible ticket machines: ${s.accessibleTicketMachines}" else "")
        bindOrHide(binding.tvNationalKeyToilets,
            if (s.nationalKeyToilets.isNotEmpty()) "🔑 National Key toilets: ${s.nationalKeyToilets}" else "")
        bindOrHide(binding.tvImpairedMobilitySetDown,
            if (s.impairedMobilitySetDown.isNotEmpty()) "🚗 Mobility set-down: ${s.impairedMobilitySetDown}" else "")
        hideSectionIfEmpty(binding.tvHeaderAccessibility, binding.cardAccessibility,
            binding.tvStepFree, binding.tvAssistance, binding.tvRamp, binding.tvWheelchairs,
            binding.tvInductionLoop, binding.tvAccessibleTicketMachines,
            binding.tvNationalKeyToilets, binding.tvImpairedMobilitySetDown)

        // ── Facilities ────────────────────────────────────────────────────
        bindOrHide(binding.tvWifi,
            if (s.wifi.isNotEmpty()) "📶 WiFi: ${s.wifi}" else "")
        bindOrHide(binding.tvToilets,
            if (s.toilets.isNotEmpty()) "🚻 Toilets: ${s.toilets}" else "")
        bindOrHide(binding.tvWaiting,
            if (s.waitingRoom.isNotEmpty()) "🪑 Waiting room: ${s.waitingRoom}" else "")
        bindOrHide(binding.tvCctv,
            if (s.cctv.isNotEmpty()) "📷 CCTV: ${s.cctv}" else "")
        bindOrHide(binding.tvBabyChange,
            if (s.babyChange.isNotEmpty()) "🍼 Baby change: ${s.babyChange}" else "")
        bindOrHide(binding.tvLeftLuggage,
            if (s.leftLuggage.isNotEmpty()) "🧳 Left luggage: ${s.leftLuggage}" else "")
        bindOrHide(binding.tvStationBuffet,
            if (s.stationBuffet.isNotEmpty()) "☕ Food & drink: ${s.stationBuffet}" else "")
        bindOrHide(binding.tvShowers,
            if (s.showers.isNotEmpty()) "🚿 Showers: ${s.showers}" else "")
        bindOrHide(binding.tvAtmMachine,
            if (s.atmMachine.isNotEmpty()) "💳 ATM: ${s.atmMachine}" else "")
        bindOrHide(binding.tvTrolleys,
            if (s.trolleys.isNotEmpty()) "🛒 Trolleys: ${s.trolleys}" else "")
        bindOrHide(binding.tvSeatedArea,
            if (s.seatedArea.isNotEmpty()) "💺 Seated area: ${s.seatedArea}" else "")
        bindOrHide(binding.tvFirstClassLounge,
            if (s.firstClassLounge.isNotEmpty()) "🥂 First class lounge: ${s.firstClassLounge}" else "")
        bindOrHide(binding.tvCustomerHelpPoints,
            if (s.customerHelpPoints.isNotEmpty()) "ℹ Help points: ${s.customerHelpPoints}" else "")
        hideSectionIfEmpty(binding.tvHeaderFacilities, binding.cardFacilities,
            binding.tvWifi, binding.tvToilets, binding.tvWaiting, binding.tvCctv,
            binding.tvBabyChange, binding.tvLeftLuggage, binding.tvStationBuffet,
            binding.tvShowers, binding.tvAtmMachine, binding.tvTrolleys,
            binding.tvSeatedArea, binding.tvFirstClassLounge, binding.tvCustomerHelpPoints)

        // ── Getting here ──────────────────────────────────────────────────
        bindOrHide(binding.tvTaxi,
            if (s.taxi.isNotEmpty()) "🚕 Taxi: ${s.taxi}" else "")
        bindOrHide(binding.tvBus,
            if (s.busInterchange.isNotEmpty()) "🚌 Bus interchange: ${s.busInterchange}" else "")
        bindOrHide(binding.tvAirport,
            if (s.airport.isNotEmpty()) "✈ Airport links: ${s.airport}" else "")
        val parkingText = buildString {
            if (s.carParking.isNotEmpty()) append("🅿 ${s.carParking}")
            if (s.carParkName.isNotEmpty()) append(" (${s.carParkName})")
        }
        bindOrHide(binding.tvParking, parkingText)
        val cycleText = buildString {
            if (s.cycleSpaces.isNotEmpty()) append("🚲 Cycle storage: ${s.cycleSpaces} spaces")
            if (s.cycleSheltered == "Yes") append(" (sheltered)")
        }
        bindOrHide(binding.tvCycle, cycleText)
        hideSectionIfEmpty(binding.tvHeaderGettingHere, binding.cardGettingHere,
            binding.tvTaxi, binding.tvBus, binding.tvAirport, binding.tvParking, binding.tvCycle)
    }

    /** Show [tv] with [text] if non-empty, making phone numbers and URLs clickable. */
    private fun bindOrHide(tv: TextView, text: String) {
        if (text.isEmpty()) { tv.visibility = View.GONE; return }

        val spannable = SpannableString(text)

        // Match phone numbers like 0800 123 4567, 020 7295 2789, +44 ...
        val phoneRegex = Regex("""(?<!\d)(\+?[\d][\d\s\-]{6,}[\d])(?!\d)""")
        phoneRegex.findAll(text).forEach { match ->
            val digits = match.value.replace(Regex("\\s|-"), "")
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                }
            }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Match URLs
        val urlRegex = Regex("""https?://[^\s]+""")
        urlRegex.findAll(text).forEach { match ->
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(match.value)))
                }
            }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        tv.text = spannable
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.visibility = View.VISIBLE
    }

    /** Hide the section [header] and [card] if every [rows] view is GONE. */
    private fun hideSectionIfEmpty(header: View, card: View, vararg rows: View) {
        val allEmpty = rows.all { it.visibility == View.GONE }
        header.visibility = if (allEmpty) View.GONE else View.VISIBLE
        card.visibility   = if (allEmpty) View.GONE else View.VISIBLE
    }

    private fun mapStaffing(raw: String): String =
        if (raw.trim().isEmpty()) "" else "👤 Staffing: $raw"
}
