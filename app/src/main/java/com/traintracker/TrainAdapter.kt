package com.traintracker

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.traintracker.databinding.ItemTrainBinding

class TrainAdapter(
    private var showFreight: Boolean = true,
    private val onServiceClick: (TrainService) -> Unit
) : ListAdapter<TrainService, TrainAdapter.ViewHolder>(DIFF) {

    private var allServices: List<TrainService> = emptyList()
    private var stationName: String = ""

    fun submitAll(list: List<TrainService>, station: String = stationName) {
        allServices = list
        stationName = station
        super.submitList(filtered())
    }

    override fun submitList(list: List<TrainService>?) = submitAll(list ?: emptyList())

    fun setShowFreight(show: Boolean) {
        showFreight = show
        super.submitList(filtered())
    }

    private fun filtered() = if (showFreight) allServices else allServices.filter { it.isPassenger }

    private var colourCancelled = 0
    private var colourDelayed   = 0
    private var colourOnTime    = 0
    private var coloursReady    = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (!coloursReady) {
            val ctx = parent.context
            colourCancelled = ContextCompat.getColor(ctx, R.color.status_cancelled)
            colourDelayed   = ContextCompat.getColor(ctx, R.color.status_delayed)
            colourOnTime    = ContextCompat.getColor(ctx, R.color.status_ontime)
            coloursReady    = true
        }
        return ViewHolder(ItemTrainBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val b: ItemTrainBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(s: TrainService) {
            val ctx = b.root.context

            // ── Time ─────────────────────────────────────────────────────────
            b.tvTime.text = s.scheduledTime

            // ── Headcode — always show; "—" if unavailable ────────────────────
            val hc = s.trainId.ifEmpty { "—" }
            b.tvHeadcode.text = hc

            // ── Schedule tag (VAR/CAN/BUS) — hidden when WTT ─────────────────
            val tag = s.scheduleTag
            if (tag != ScheduleTag.WTT) {
                b.tvScheduleTag.text = tag.label
                b.tvScheduleTag.background.setTint(when (tag) {
                    ScheduleTag.CAN   -> colourCancelled
                    ScheduleTag.VAR   -> colourDelayed
                    ScheduleTag.BUS   -> 0xFF1565C0.toInt()
                    ScheduleTag.FERRY -> 0xFF00838F.toInt()
                    else              -> colourOnTime
                })
                b.tvScheduleTag.visibility = View.VISIBLE
            } else {
                b.tvScheduleTag.visibility = View.GONE
            }

            // ── Service type icon ─────────────────────────────────────────────
            val icon = s.serviceTypeIcon
            b.tvServiceType.text       = icon
            b.tvServiceType.visibility = if (icon.isNotEmpty()) View.VISIBLE else View.GONE

            // ── Main / sub labels ─────────────────────────────────────────────
            if (!s.isPassenger && s.specialServiceType != "bus" && s.specialServiceType != "ferry") {
                // Non-passenger service: use headcode as main label, type as sub
                b.tvMainLabel.text      = buildString {
                    val typeLabel = when (s.specialServiceType) {
                        "freight"     -> "Freight"
                        "ecs"         -> "ECS Movement"
                        "railtour"    -> "Railtour"
                        "lightengine" -> "Light Engine"
                        else          -> "Special Working"
                    }
                    append(typeLabel)
                }
                b.tvSubLabel.text       = s.serviceTypeLabel.ifEmpty { "Not in normal passenger service" }
                b.tvSubLabel.visibility = View.VISIBLE
            } else {
                b.tvMainLabel.text = if (s.boardType == BoardType.ARRIVALS) s.origin else s.destination
                val sub = when (s.boardType) {
                    BoardType.ALL      -> "From ${s.origin}"
                    BoardType.ARRIVALS -> "To ${s.destination}"
                    else               -> ""
                }
                b.tvSubLabel.text       = sub
                b.tvSubLabel.visibility = if (sub.isNotEmpty()) View.VISIBLE else View.GONE
            }

            // ── Journey time ──────────────────────────────────────────────────
            val jt = if (s.journeyMinutes > 0) formatDuration(s.journeyMinutes) else ""
            b.tvJourneyTime.text       = jt
            b.tvJourneyTime.visibility = if (jt.isNotEmpty()) View.VISIBLE else View.GONE

            // ── Unit / RSID / power type / platform ───────────────────────────
            b.tvPlatform.text = buildString {
                if (s.platform.isNotEmpty()) append("Plat ${s.platform}")

                // Rolling stock line — priority:
                //   1. rollingStockDesc (Darwin units decoded via RollingStockData)
                //   2. units list (raw unit numbers from Darwin)
                //   3. rsid (OpenLDBWS retail service ID)
                val stockStr = when {
                    s.rollingStockDesc.isNotEmpty() -> s.rollingStockDesc
                    s.units.isNotEmpty() -> {
                        val label = RollingStockData.shortLabel(s.units)
                        val nums  = s.units.joinToString(" + ")
                        val coach = if (s.darwinCoachCount > 0) " · ${s.darwinCoachCount}c" else ""
                        if (label.isNotEmpty()) "$label · $nums$coach" else "$nums$coach"
                    }
                    s.rsid.isNotEmpty() -> s.rsid
                    else -> ""
                }
                if (stockStr.isNotEmpty()) {
                    if (s.platform.isNotEmpty()) append("\n")
                    append(stockStr)
                }
            }

            // ── Approach state ────────────────────────────────────────────────
            val approach = s.approachState
            b.tvApproachState.text       = approach
            b.tvApproachState.visibility = if (approach.isNotEmpty()) View.VISIBLE else View.GONE

            // ── TOC logo / badge ──────────────────────────────────────────────
            val logoName = TocData.logoDrawableName(s.operatorCode)
                .ifEmpty { TocData.logoDrawableName(s.operator) }
            val resId = if (logoName.isNotEmpty())
                ctx.resources.getIdentifier(logoName, "drawable", ctx.packageName) else 0

            if (resId != 0) {
                b.imgTocLogo.setImageResource(resId)
                b.imgTocLogo.visibility = View.VISIBLE
                b.tvTocBadge.visibility = View.GONE
            } else {
                b.imgTocLogo.visibility = View.GONE
                val label = s.operatorCode.ifEmpty { s.operator.take(3).uppercase() }
                if (label.isNotEmpty()) {
                    b.tvTocBadge.text = label
                    b.tvTocBadge.background.setTint(TocData.brandColor(s.operatorCode.ifEmpty { s.operator }))
                    b.tvTocBadge.visibility = View.VISIBLE
                } else {
                    b.tvTocBadge.visibility = View.GONE
                }
            }

            // ── Status ────────────────────────────────────────────────────────
            val statusColour = when {
                s.isCancelled -> colourCancelled
                s.isDelayed   -> colourDelayed
                else          -> colourOnTime
            }
            b.tvStatus.text = s.statusDisplay
            b.tvStatus.setTextColor(statusColour)

            // Left bar colour: TOC brand when on time, status colour otherwise
            val tocColour = TocData.brandColor(s.operatorCode.ifEmpty { s.operator })
            val defaultBarColour = 0xFF1A237E.toInt() // primary
            val barColour = when {
                s.isCancelled -> colourCancelled
                s.isDelayed   -> colourDelayed
                tocColour != 0xFF555555.toInt() -> tocColour
                else          -> defaultBarColour
            }
            b.statusBar.setBackgroundColor(barColour)

            // ── Delay pill ────────────────────────────────────────────────────
            if (s.delayMinutes > 0 && !s.isCancelled) {
                b.tvDelay.text = "+${s.delayMinutes}m"
                b.tvDelay.visibility = View.VISIBLE
            } else {
                b.tvDelay.visibility = View.GONE
            }

            // ── Countdown ─────────────────────────────────────────────────────
            val countdown = s.countdownLabel
            b.tvCountdown.text       = countdown
            b.tvCountdown.visibility = if (countdown.isNotEmpty()) View.VISIBLE else View.GONE

            // ── Alert ─────────────────────────────────────────────────────────
            b.tvAlertIcon.visibility = if (s.hasAlert) View.VISIBLE else View.GONE

            // ── Alpha + click ─────────────────────────────────────────────────
            b.root.alpha = if (s.isCancelled || s.hasDeparted) 0.5f else 1f
            b.root.setOnClickListener { onServiceClick(s) }
            b.root.setOnLongClickListener {
                val shareText = s.shareText(stationName.ifEmpty { "Train Tracker" })
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }, "Share service"
                ))
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrainService>() {
            override fun areItemsTheSame(a: TrainService, b: TrainService) =
                a.serviceID == b.serviceID && a.std == b.std
            override fun areContentsTheSame(a: TrainService, b: TrainService) = a == b
        }
    }
}
