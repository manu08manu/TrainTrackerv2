package com.traintracker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.traintracker.databinding.ItemTrainBinding

class TrainAdapter(
    private val onServiceClick: (TrainService) -> Unit
) : ListAdapter<TrainService, TrainAdapter.ViewHolder>(DIFF) {

    private var allServices: List<TrainService> = emptyList()
    private var stationName: String = ""

    /** Called with an operator code, returns NSI status or null if good/unknown. */
    var nsiLookup: ((String) -> KbNsiEntry?)? = null
        set(value) { field = value; notifyItemRangeChanged(0, itemCount) }

    var tocDetailLookup: ((String) -> KbTocEntry?)? = null

    fun submitAll(list: List<TrainService>, station: String = stationName) {
        allServices = list
        stationName = station
        super.submitList(allServices)
    }

    override fun submitList(list: List<TrainService>?) = submitAll(list ?: emptyList())

    private var colourCancelled  = 0
    private var colourDelayed    = 0
    private var colourOnTime     = 0
    private var colourNsiMinor   = 0xFF_FFB300.toInt()  // amber  — minor delays
    private var colourNsiMajor   = 0xFF_E65100.toInt()  // dark orange — major delays
    private var coloursReady     = false

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

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(getItem(position))
        } else {
            // Lightweight tick refresh — only update time-sensitive fields
            if (payloads.contains(PAYLOAD_TICK)) holder.bindTick(getItem(position))
        }
    }

    inner class ViewHolder(private val b: ItemTrainBinding) :
        RecyclerView.ViewHolder(b.root) {

        private val ctx get() = b.root.context

        fun bind(s: TrainService) {

            // ── Scheduled time ────────────────────────────────────────────────
            b.tvTime.text = formatTimeFromIso(s.scheduledTime)

            // ── Headcode ── always shown; staffboards always return 4-char codes
            b.tvHeadcode.text = s.trainId.ifEmpty { "—" }

            // ── Schedule tag ──────────────────────────────────────────────────
            when (s.scheduleTag) {
                ScheduleTag.WTT -> b.tvScheduleTag.visibility = View.GONE
                ScheduleTag.CAN, ScheduleTag.VAR, ScheduleTag.BUS, ScheduleTag.FERRY -> {
                    b.tvScheduleTag.text = s.scheduleTag.label
                    b.tvScheduleTag.background.setTint(when (s.scheduleTag) {
                        ScheduleTag.CAN   -> colourCancelled
                        ScheduleTag.VAR   -> colourDelayed
                        ScheduleTag.BUS   -> 0xFF1565C0.toInt()
                        ScheduleTag.FERRY -> 0xFF00838F.toInt()
                        ScheduleTag.WTT   -> colourOnTime
                    })
                    b.tvScheduleTag.visibility = View.VISIBLE
                }
            }

            // ── Category icon + card tint for non-passenger ──────────────────
            val icon = if (s.isServicePassing) "⏩" else s.serviceTypeIcon
            b.tvServiceType.text       = icon
            b.tvServiceType.visibility = if (icon.isNotEmpty()) View.VISIBLE else View.GONE

            // Tint card background: passing-through services get a grey tint; non-passenger get category tint
            val cardTint = when {
                s.isServicePassing               -> 0x1A808080  // subtle grey — passing through
                s.category == ServiceCategory.FREIGHT      -> 0x0D8B4513
                s.category == ServiceCategory.ECS          -> 0x0D1565C0
                s.category == ServiceCategory.LIGHT_ENGINE -> 0x0D6A1FA2
                s.category == ServiceCategory.RAILTOUR     -> 0x0DC62828
                s.category == ServiceCategory.SPECIAL      -> 0x0D2E7D32
                else                                       -> 0x00000000
            }
            (b.root as? com.google.android.material.card.MaterialCardView)
                ?.setCardBackgroundColor(cardTint)

            // ── Main label (destination or service type) ──────────────────────
            when (s.category) {
                ServiceCategory.PASSENGER -> {
                    b.tvMainLabel.text = if (s.boardType == BoardType.ARRIVALS) s.origin else s.destination
                    val sub = when (s.boardType) {
                        BoardType.ALL      -> "From ${s.origin}"
                        BoardType.ARRIVALS -> "To ${s.destination}"
                        else               -> ""
                    }
                    b.tvSubLabel.text       = sub
                    b.tvSubLabel.visibility = if (sub.isNotEmpty()) View.VISIBLE else View.GONE
                }
                ServiceCategory.RAILTOUR -> {
                    b.tvMainLabel.text = s.tourName.ifEmpty { s.destination.ifEmpty { "Railtour" } }
                    b.tvSubLabel.text       = b.root.context.getString(R.string.railtour_label, s.trainId)
                    b.tvSubLabel.visibility = View.VISIBLE
                }
                ServiceCategory.FREIGHT -> {
                    b.tvMainLabel.text = RailTourData.freightDescriptor(s.trainId)
                    val dest = s.destination.ifEmpty { s.trainId }
                    b.tvSubLabel.text       = if (dest.isNotEmpty() && dest != s.trainId) "To $dest" else s.serviceTypeLabel
                    b.tvSubLabel.visibility = View.VISIBLE
                }
                else -> {
                    b.tvMainLabel.text = buildString {
                        append(when (s.category) {
                            ServiceCategory.ECS          -> "Empty Stock"
                            ServiceCategory.LIGHT_ENGINE -> "Light Engine"
                            ServiceCategory.BUS          -> "Bus Replacement"
                            ServiceCategory.FERRY        -> "Ferry"
                            else                         -> "Special Working"
                        })
                    }
                    b.tvSubLabel.text       = s.serviceTypeLabel.ifEmpty { "Not in passenger service" }
                    b.tvSubLabel.visibility = View.VISIBLE
                }
            }

            // ── Journey time ──────────────────────────────────────────────────
            val jt = if (s.journeyMinutes > 0) formatDuration(s.journeyMinutes) else ""
            b.tvJourneyTime.text       = jt
            b.tvJourneyTime.visibility = if (jt.isNotEmpty()) View.VISIBLE else View.GONE

            // ── Platform + unit allocation ────────────────────────────────────
            b.tvPlatform.text = if (s.platform.isNotEmpty()) "Plat ${s.platform}" else ""

            // ── Approach state ────────────────────────────────────────────────
            val approach = s.approachState
            b.tvApproachState.text       = approach
            b.tvApproachState.visibility = if (approach.isNotEmpty()) View.VISIBLE else View.GONE

            // ── Punctuality badge ─────────────────────────────────────────────
            if (s.punctualityPercent >= 0) {
                b.tvPunctuality.text = b.root.context.getString(R.string.punctuality_pct_label, s.punctualityPercent)
                val pctColor = when {
                    s.punctualityPercent >= 90 -> colourOnTime
                    s.punctualityPercent >= 75 -> colourDelayed
                    else                       -> colourCancelled
                }
                b.tvPunctuality.setTextColor(pctColor)
                b.tvPunctuality.visibility = View.VISIBLE
            } else {
                b.tvPunctuality.visibility = View.GONE
            }

            // ── TOC logo / badge ──────────────────────────────────────────────
            val logoName = TocData.logoDrawableName(s.operatorCode)
                .ifEmpty { TocData.logoDrawableName(s.operator) }
            val resId = if (logoName.isNotEmpty())
                TocData.logoDrawableRes(logoName, ctx) else 0

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

            // ── NSI status dot ────────────────────────────────────────────────
            val nsi = nsiLookup?.invoke(s.operatorCode)
            if (nsi != null && !nsi.isGood) {
                val dotColour = when {
                    nsi.isSevere -> colourCancelled
                    nsi.isMajor  -> colourNsiMajor
                    else         -> colourNsiMinor  // minor
                }
                b.nsiDot.setColorFilter(dotColour)
                b.nsiDot.visibility = View.VISIBLE
                b.nsiDot.contentDescription = nsi.statusLabel
            } else {
                b.nsiDot.visibility = View.GONE
            }

            // ── Status ────────────────────────────────────────────────────────
            val statusColour = when {
                s.isCancelled -> colourCancelled
                s.isDelayed   -> colourDelayed
                else          -> colourOnTime
            }
            b.tvStatus.text = s.statusDisplay
            b.tvStatus.setTextColor(statusColour)

            // Left bar: TOC brand colour for on-time; status colour otherwise
            val tocColour       = TocData.brandColor(s.operatorCode.ifEmpty { s.operator })
            val defaultBarColor = 0xFF1A237E.toInt()
            // Non-passenger services get a different bar colour scheme
            val barColour = when {
                s.isCancelled                          -> colourCancelled
                s.isDelayed                            -> colourDelayed
                s.category == ServiceCategory.FREIGHT  -> 0xFF4E342E.toInt()  // brown for freight
                s.category == ServiceCategory.RAILTOUR -> 0xFF1A237E.toInt()  // indigo for railtours
                s.category == ServiceCategory.ECS      -> 0xFF37474F.toInt()  // blue-grey for ECS
                s.category == ServiceCategory.LIGHT_ENGINE -> 0xFF424242.toInt()
                tocColour != 0xFF555555.toInt()         -> tocColour
                else                                    -> defaultBarColor
            }
            b.statusBar.setBackgroundColor(barColour)

            // ── Delay pill ────────────────────────────────────────────────────
            // For departed trains, use actual vs scheduled delay (not stale etd estimate)
            val displayDelay = when {
                s.isCancelled   -> 0
                s.hasDeparted   -> minuteDelay(s.scheduledTime, s.actualTime)
                else            -> s.delayMinutes
            }
            if (displayDelay > 0) {
                b.tvDelay.text = b.root.context.getString(R.string.delay_mins_short, displayDelay)
                b.tvDelay.visibility = View.GONE
            } else {
                b.tvDelay.visibility = View.GONE
            }

            // ── Countdown ─────────────────────────────────────────────────────
            val countdown = s.countdownLabel
            b.tvCountdown.text       = countdown
            b.tvCountdown.visibility = if (countdown.isNotEmpty()) View.VISIBLE else View.GONE

            // ── Alert ─────────────────────────────────────────────────────────
            b.tvAlertIcon.visibility = if (s.hasAlert) View.VISIBLE else View.GONE
            b.tvAlertIcon.setOnClickListener {
                val nsi = nsiLookup?.invoke(s.operatorCode)
                val msg = buildString {
                    if (s.isCancelled) {
                        val reason = if (s.cancelReason.isNotEmpty()) ReasonCodes.resolveCancel(s.cancelReason) else ""
                        if (reason.isNotEmpty()) appendLine("Cancelled: $reason")
                        else appendLine("This service is cancelled.")
                    }
                    else if (s.isDelayed) appendLine("Running late: ${s.etd}")
                    if (nsi != null && !nsi.isGood) {
                        if (isNotEmpty()) appendLine()
                        append("${nsi.tocName}: ${nsi.statusLabel}")
                    }
                }.trim()
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Service Alert")
                    .setMessage(msg.ifEmpty { "Alert details unavailable." })
                    .setPositiveButton("OK", null)
                    .show()
            }

            // ── Alpha (dim departed/cancelled/non-stopping) ─────────────────
            b.root.alpha = when {
                s.isCancelled || s.hasDeparted -> 0.5f
                s.isServicePassing              -> 0.7f   // passenger passing through — dimmed
                s.isFreight                     -> 0.75f  // freight/non-stopping — slightly dimmed
                else                            -> 1f
            }

            // ── Unit formation + split/coupling info ─────────────────────────────
            if (s.units.isNotEmpty()) {
                b.tvUnitFormation.text = s.units.joinToString(" + ")
                b.tvUnitFormation.visibility = View.VISIBLE
            } else {
                b.tvUnitFormation.visibility = View.GONE
            }

            // Split info
            val splitLine = if (s.splitToHeadcode.isNotEmpty()) {
                val at = s.splitTiplocName.ifEmpty { s.splitTiploc }.ifEmpty { "?" }
                "✂ Splits at $at → ${s.splitToHeadcode}"
            } else ""
            b.tvSplitInfo.text       = splitLine
            b.tvSplitInfo.visibility = if (splitLine.isNotEmpty()) View.VISIBLE else View.GONE

            // Forms next service
            val formsLine = if (s.formsHeadcode.isNotEmpty()) "→ Forms ${s.formsHeadcode}" else ""
            b.tvFormsNext.text       = formsLine
            b.tvFormsNext.visibility = if (formsLine.isNotEmpty()) View.VISIBLE else View.GONE
            b.tvFormsNext.setOnClickListener {
                if (s.formsUid.isNotEmpty()) {
                    val intent = android.content.Intent(ctx, ServiceDetailActivity::class.java).apply {
                        putExtra("serviceId", s.formsUid)
                        putExtra("headcode", s.formsHeadcode)
                    }
                    ctx.startActivity(intent)
                }
            }
            b.tvFormsNext.setOnLongClickListener {
                if (s.formsHeadcode.isNotEmpty()) {
                    val intent = android.content.Intent(ctx, StationBoardActivity::class.java).apply {
                        putExtra("headcode", s.formsHeadcode)
                    }
                    ctx.startActivity(intent)
                }
                true
            }

            // ── Click / long-press ────────────────────────────────────────────────
            b.root.isClickable = true
            b.root.isFocusable = false
            b.root.setOnClickListener { onServiceClick(s) }
            b.root.setOnLongClickListener {
                val detail = tocDetailLookup?.invoke(s.operatorCode.ifEmpty { s.operator })
                val nsiEntry = nsiLookup?.invoke(s.operatorCode.ifEmpty { s.operator })
                val fm = (ctx as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
                if (fm != null) {
                    TocInfoBottomSheet.newInstance(s, detail, nsiEntry).show(fm, "toc_info")
                }
                true
            }
        }

        /** Lightweight refresh — only updates countdown and status text without rebinding the whole item. */
        fun bindTick(s: TrainService) {
            val countdown = s.countdownLabel
            b.tvCountdown.text       = countdown
            b.tvCountdown.visibility = if (countdown.isNotEmpty()) View.VISIBLE else View.GONE
            val statusColour = when {
                s.isCancelled -> colourCancelled
                s.isDelayed   -> colourDelayed
                else          -> colourOnTime
            }
            b.tvStatus.text = s.statusDisplay
            b.tvStatus.setTextColor(statusColour)
        }
    }

    /** Notify only countdown/status fields changed — avoids full rebind and logo flicker. */
    fun notifyTick() {
        for (i in 0 until itemCount) notifyItemChanged(i, PAYLOAD_TICK)
    }

    companion object {
        const val PAYLOAD_TICK = "tick"
        private val DIFF = object : DiffUtil.ItemCallback<TrainService>() {
            override fun areItemsTheSame(a: TrainService, b: TrainService) =
                a.serviceID == b.serviceID && a.std == b.std
            override fun areContentsTheSame(a: TrainService, b: TrainService) = a == b
        }
    }
}