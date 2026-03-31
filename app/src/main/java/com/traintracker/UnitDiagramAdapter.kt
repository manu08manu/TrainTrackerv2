package com.traintracker

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Multi-viewtype adapter for the RTT-style unit diagram.
 *
 * Item types:
 *  - SERVICE_HEADER  — coloured band showing headcode, route, formation, status
 *  - CALLING_POINT   — single stop row with timeline spine, times, delay
 */
class UnitDiagramAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class DiagramItem {
        data class ServiceHeader(
            val service: TrainService,
            val isFirst: Boolean,
            val gapLabel: String       // e.g. "Stabled 42 min" or "" if first
        ) : DiagramItem()

        data class CallingPoint(
            val cp: com.traintracker.CallingPoint,
            val isOrigin: Boolean,
            val isDestination: Boolean,
            val serviceIsCancelled: Boolean
        ) : DiagramItem()
    }

    private val items = mutableListOf<DiagramItem>()

    fun submitItems(newItems: List<DiagramItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is DiagramItem.ServiceHeader -> VIEW_SERVICE_HEADER
        is DiagramItem.CallingPoint  -> VIEW_CALLING_POINT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_SERVICE_HEADER -> ServiceHeaderVH(
                inflater.inflate(R.layout.item_diagram_service_header, parent, false)
            )
            else -> CallingPointVH(
                inflater.inflate(R.layout.item_diagram_calling_point, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DiagramItem.ServiceHeader -> (holder as ServiceHeaderVH).bind(item)
            is DiagramItem.CallingPoint  -> (holder as CallingPointVH).bind(item)
        }
    }

    // ── Service Header ViewHolder ─────────────────────────────────────────────

    inner class ServiceHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val gap       = view.findViewById<View>(R.id.interServiceGap)
        private val gapLabel  = view.findViewById<TextView>(R.id.tvGapLabel)
        private val statusBar = view.findViewById<View>(R.id.statusBar)
        private val tvHeadcode   = view.findViewById<TextView>(R.id.tvHeadcode)
        private val tvRoute      = view.findViewById<TextView>(R.id.tvRoute)
        private val tvFormation  = view.findViewById<TextView>(R.id.tvFormation)
        private val tvStatus     = view.findViewById<TextView>(R.id.tvStatus)
        private val splitBar     = view.findViewById<View>(R.id.splitBar)
        private val tvSplitInfo  = view.findViewById<TextView>(R.id.tvSplitInfo)

        fun bind(item: DiagramItem.ServiceHeader) {
            val s   = item.service
            val ctx = itemView.context

            // ── Inter-service gap label ────────────────────────────────────
            if (item.isFirst || item.gapLabel.isEmpty()) {
                gap.visibility = View.GONE
            } else {
                gap.visibility = View.VISIBLE
                gapLabel.text  = item.gapLabel
            }

            // ── Status bar colour ──────────────────────────────────────────
            val barColor = when {
                s.isCancelled -> ContextCompat.getColor(ctx, R.color.status_cancelled)
                s.isDelayed   -> ContextCompat.getColor(ctx, R.color.status_delayed)
                else          -> ContextCompat.getColor(ctx, R.color.status_ontime)
            }
            statusBar.setBackgroundColor(barColor)

            // ── Headcode ───────────────────────────────────────────────────
            tvHeadcode.text = s.trainId

            // ── Route ──────────────────────────────────────────────────────
            tvRoute.text = "${s.origin} → ${s.destination}"

            // ── Formation ─────────────────────────────────────────────────
            val unitStr = s.units.joinToString(" + ")
            if (unitStr.isNotEmpty()) {
                tvFormation.text       = unitStr
                tvFormation.visibility = View.VISIBLE
            } else {
                tvFormation.visibility = View.GONE
            }

            // ── Status chip ────────────────────────────────────────────────
            when {
                s.isCancelled -> {
                    tvStatus.text = "Cancelled"
                    tvStatus.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_cancelled))
                }
                s.isDelayed && s.delayMinutes > 0 -> {
                    tvStatus.text = "+${s.delayMinutes}m"
                    tvStatus.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_delayed))
                }
                else -> {
                    tvStatus.text = "On time"
                    tvStatus.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_ontime))
                }
            }

            // ── Split annotation ───────────────────────────────────────────
            if (s.splitToHeadcode.isNotEmpty()) {
                val at = s.splitTiplocName.ifEmpty { s.splitTiploc }.ifEmpty { "?" }
                tvSplitInfo.text  = "✂  Splits at $at  →  ${s.splitToHeadcode}"
                splitBar.visibility = View.VISIBLE
            } else {
                splitBar.visibility = View.GONE
            }
        }
    }

    // ── Calling Point ViewHolder ──────────────────────────────────────────────

    inner class CallingPointVH(view: View) : RecyclerView.ViewHolder(view) {
        private val lineTop      = view.findViewById<View>(R.id.lineTop)
        private val lineBottom   = view.findViewById<View>(R.id.lineBottom)
        private val stopNode     = view.findViewById<View>(R.id.stopNode)
        private val tvCrs        = view.findViewById<TextView>(R.id.tvCrs)
        private val tvName       = view.findViewById<TextView>(R.id.tvStationName)
        private val tvPlatform   = view.findViewById<TextView>(R.id.tvPlatform)
        private val tvSched      = view.findViewById<TextView>(R.id.tvSched)
        private val tvActual     = view.findViewById<TextView>(R.id.tvActual)
        private val tvDelay      = view.findViewById<TextView>(R.id.tvDelay)

        fun bind(item: DiagramItem.CallingPoint) {
            val cp  = item.cp
            val ctx = itemView.context

            // ── Timeline spine visibility ──────────────────────────────────
            lineTop.visibility    = if (item.isOrigin)      View.INVISIBLE else View.VISIBLE
            lineBottom.visibility = if (item.isDestination) View.INVISIBLE else View.VISIBLE

            // ── Node colour ────────────────────────────────────────────────
            val nodeColor = when {
                item.serviceIsCancelled || cp.isCancelled ->
                    ContextCompat.getColor(ctx, R.color.status_cancelled)
                cp.isDelayed ->
                    ContextCompat.getColor(ctx, R.color.status_delayed)
                item.isOrigin || item.isDestination ->
                    ContextCompat.getColor(ctx, R.color.primary)
                else ->
                    ContextCompat.getColor(ctx, R.color.status_ontime)
            }
            stopNode.background.setTint(nodeColor)

            // ── Text alpha — dimmed for passing/cancelled ──────────────────
            val alpha = when {
                cp.isPassing -> 0.45f
                item.serviceIsCancelled || cp.isCancelled -> 0.55f
                else -> 1.0f
            }
            tvCrs.alpha      = alpha
            tvName.alpha     = alpha
            tvPlatform.alpha = alpha
            tvSched.alpha    = alpha

            // ── CRS + name ─────────────────────────────────────────────────
            tvCrs.text  = cp.crs.ifEmpty { "—" }
            tvName.text = cp.locationName

            // Bold origin and destination
            val typefaceStyle = if (item.isOrigin || item.isDestination)
                android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            tvCrs.setTypeface(null, typefaceStyle)
            tvName.setTypeface(null, typefaceStyle)

            // ── Platform ───────────────────────────────────────────────────
            tvPlatform.text = cp.platform.ifEmpty { "" }

            // ── Scheduled time ─────────────────────────────────────────────
            tvSched.text = formatTimeFromIso(cp.st).ifEmpty { cp.st.take(5) }

            // ── Actual time ────────────────────────────────────────────────
            val actualRaw = if (cp.at.isNotEmpty() && cp.at != "On time") cp.at
                            else if (cp.et.isNotEmpty() && cp.et != "On time") cp.et
                            else ""
            val actualFormatted = formatTimeFromIso(actualRaw).ifEmpty { actualRaw.take(5) }

            if (actualFormatted.isNotEmpty()) {
                tvActual.text = actualFormatted
                tvActual.setTextColor(
                    if (cp.isDelayed) ContextCompat.getColor(ctx, R.color.status_delayed)
                    else              ContextCompat.getColor(ctx, R.color.status_ontime)
                )
            } else {
                tvActual.text = ""
            }
            tvActual.alpha = alpha

            // ── Delay pill ─────────────────────────────────────────────────
            if (cp.delayMinutes > 0 && !item.serviceIsCancelled) {
                tvDelay.text       = "+${cp.delayMinutes}"
                tvDelay.visibility = View.VISIBLE
                tvDelay.setTextColor(ContextCompat.getColor(ctx, R.color.status_delayed))
            } else if (cp.isCancelled) {
                tvDelay.text       = "Cxl"
                tvDelay.visibility = View.VISIBLE
                tvDelay.setTextColor(ContextCompat.getColor(ctx, R.color.status_cancelled))
            } else {
                tvDelay.visibility = View.INVISIBLE
            }
        }
    }

    companion object {
        private const val VIEW_SERVICE_HEADER = 0
        private const val VIEW_CALLING_POINT  = 1
    }
}
