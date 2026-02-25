package com.traintracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traintracker.databinding.ActivityStationInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Station information screen — shows facilities, accessibility, and transport links
 * for any National Rail station, sourced from the Knowledgebase Stations API.
 *
 * Open by long-pressing a station chip/favourite, or via a dedicated info button.
 */
class StationInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStationInfoBinding
    private val kb = KnowledgebaseService()

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
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility     = View.GONE
        binding.scrollContent.visibility = View.GONE

        lifecycleScope.launch {
            val station = withContext(Dispatchers.IO) { kb.getStation(crs) }

            binding.progressBar.visibility = View.GONE

            if (station == null) {
                binding.tvError.text = getString(R.string.info_load_error)
                binding.tvError.visibility = View.VISIBLE
                return@launch
            }

            bindStation(station)
            binding.scrollContent.visibility = View.VISIBLE
        }
    }

    private fun bindStation(s: KbStation) {
        // Address
        binding.tvAddress.text = s.address.ifEmpty { getString(R.string.info_no_address) }

        // Phone
        if (s.telephone.isNotEmpty()) {
            binding.tvPhone.text = "📞 ${s.telephone}"
            binding.tvPhone.visibility = View.VISIBLE
        }

        // Staffing
        if (s.staffingNote.isNotEmpty()) {
            binding.tvStaffing.text = s.staffingNote
            binding.tvStaffing.visibility = View.VISIBLE
        }

        // Tickets
        binding.tvTicketOffice.text = if (s.ticketOfficeHours.isNotEmpty())
            "🎟 Ticket office:\n${s.ticketOfficeHours}"
        else getString(R.string.info_no_ticket_office)

        binding.tvSstm.text = if (s.sstmAvailability.isNotEmpty())
            "🖥 Self-service machines: ${s.sstmAvailability}"
        else ""

        // Accessibility
        binding.tvStepFree.text  = "♿ Step-free access: ${s.stepFreeAccess.ifEmpty { getString(R.string.info_unknown) }}"
        binding.tvAssistance.text = "🤝 Assistance: ${s.assistanceAvail.ifEmpty { getString(R.string.info_unknown) }}"

        // Facilities
        binding.tvWifi.text    = "📶 WiFi: ${s.wifi.ifEmpty { getString(R.string.info_unknown) }}"
        binding.tvToilets.text = "🚻 Toilets: ${s.toilets.ifEmpty { getString(R.string.info_unknown) }}"
        binding.tvWaiting.text = "🪑 Waiting room: ${s.waitingRoom.ifEmpty { getString(R.string.info_unknown) }}"
        binding.tvCctv.text    = "📷 CCTV: ${s.cctv.ifEmpty { getString(R.string.info_unknown) }}"

        // Getting here
        binding.tvTaxi.text    = "🚕 Taxi: ${s.taxi.ifEmpty { getString(R.string.info_unknown) }}"
        binding.tvBus.text     = "🚌 Bus interchange: ${s.busInterchange.ifEmpty { getString(R.string.info_unknown) }}"
        binding.tvParking.text = if (s.carParking.isNotEmpty()) "🅿 ${s.carParking}"
                                 else "🅿 Parking: ${getString(R.string.info_unknown)}"
    }
}
