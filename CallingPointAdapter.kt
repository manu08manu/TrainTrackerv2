package com.traintracker

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.traintracker.databinding.ItemCallingPointBinding

data class CallingPointRow(
    val point: CallingPoint,
    val isFirst: Boolean,
    val isLast: Boolean,
    val isDetailed: Boolean
)

class CallingPointAdapter(
    private val highlightCrs: String = "",
    var showPassing: Boolean = false,
    var showDetailed: Boolean = false,
    private val onStationClick: ((CallingPoint) -> Unit)? = null
) : ListAdapter<CallingPointRow, CallingPointAdapter.ViewHolder>(DIFF) {

    private var colourOnSurface = 0
    private var colourCancelled = 0
    private var colourDelayed   = 0
    private var colourOnTime    = 0
    private var colourAccent    = 0
    private var colourPrimary   = 0
    private var colourGrey      = 0
    private var coloursReady    = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = ViewHolder(ItemCallingPointBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        if (!coloursReady) {
            val ctx = parent.context
            colourOnSurface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
            colourCancelled = ContextCompat.getColor(ctx, R.color.status_cancelled)
            colourDelayed   = ContextCompat.getColor(ctx, R.color.status_delayed)
            colourOnTime    = ContextCompat.getColor(ctx, R.color.status_ontime)
            colourAccent    = ContextCompat.getColor(ctx, R.color.accent)
            colourPrimary   = ContextCompat.getColor(ctx, R.color.primary)
            colourGrey      = ContextCompat.getColor(ctx, android.R.color.darker_gray)
            coloursReady    = true
        }
        return v
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    fun submitFiltered(points: List<CallingPoint>) {
        val filtered = if (showPassing) points else points.filter { !it.isPassing }
        val rows = filtered.mapIndexed { i, pt ->
            CallingPointRow(
                point      = pt,
                isFirst    = i == 0,
                isLast     = i == filtered.lastIndex,
                isDetailed = showDetailed
            )
        }
        submitList(rows)
    }

    inner class ViewHolder(private val b: ItemCallingPointBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(row: CallingPointRow) {
            val pt         = row.point
            val isDetailed = row.isDetailed

            // ── Station name ──────────────────────────────────────────────────
            b.tvStation.text = pt.locationName

            // ── CRS — ALWAYS visible ──────────────────────────────────────────
            b.tvCrs.text = pt.crs
            b.tvCrs.visibility = View.VISIBLE

            val stopCancelled = pt.isCancelled
                    || pt.et.equals("Cancelled", ignoreCase = true)
                    || pt.at.equals("Cancelled", ignoreCase = true)

            val timeColour = when {
                stopCancelled -> colourCancelled
                pt.isDelayed  -> colourDelayed
                else          -> colourOnTime
            }

            // ── Scheduled time — ALWAYS visible, struck-through if late ───────
            if (pt.st.isNotEmpty() && pt.st != "—") {
                b.tvScheduled.text = pt.st
                b.tvScheduled.visibility = View.VISIBLE
                if (pt.isDelayed && !stopCancelled) {
                    b.tvScheduled.paintFlags = b.tvScheduled.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    b.tvScheduled.alpha = 0.5f
                } else {
                    b.tvScheduled.paintFlags = b.tvScheduled.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    b.tvScheduled.alpha = 1f
                }
            } else {
                b.tvScheduled.visibility = View.GONE
            }

            // ── Actual / estimated time — ALWAYS visible ──────────────────────
            val actualText = when {
                stopCancelled                             -> "Canc"
                pt.at.isNotEmpty() && pt.at != "On time" -> pt.at
                pt.et.isEmpty() || pt.et == "On time"    -> "On time"
                else                                     -> pt.et
            }
            b.tvActual.text = actualText
            b.tvActual.setTextColor(timeColour)
            b.tvActual.visibility = View.VISIBLE

            // ── Detailed-only extras ──────────────────────────────────────────
            if (isDetailed) {
                // Delay amount badge
                if (pt.delayMinutes > 0 && !stopCancelled) {
                    b.tvDelayMin.text = "+${pt.delayMinutes}m late"
                    b.tvDelayMin.visibility = View.VISIBLE
                } else {
                    b.tvDelayMin.visibility = View.GONE
                }

                // Coach count
                if (pt.length != null && pt.length > 0) {
                    b.tvLength.text = "${pt.length} coaches"
                    b.tvLength.visibility = View.VISIBLE
                } else {
                    b.tvLength.visibility = View.GONE
                }

                // Platform
                if (pt.platform.isNotEmpty()) {
                    b.tvPlatform.text = "Plat ${pt.platform}"
                    b.tvPlatform.visibility = View.VISIBLE
                } else {
                    b.tvPlatform.visibility = View.GONE
                }

                // Arr/dep label for terminus stops
                val arrDepLabel = when {
                    row.isFirst -> "departs"
                    row.isLast  -> "arrives"
                    else        -> ""
                }
                if (arrDepLabel.isNotEmpty()) {
                    b.tvArrDep.text = arrDepLabel
                    b.tvArrDep.visibility = View.VISIBLE
                } else {
                    b.tvArrDep.visibility = View.GONE
                }
            } else {
                b.tvArrDep.visibility   = View.GONE
                b.tvDelayMin.visibility = View.GONE
                b.tvLength.visibility   = View.GONE
                b.tvPlatform.visibility = View.GONE
            }

            // ── Passing station ───────────────────────────────────────────────
            val alpha = if (pt.isPassing) 0.5f else 1f
            b.tvStation.alpha  = alpha
            b.tvCrs.alpha      = alpha
            b.tvScheduled.alpha = if (pt.isPassing) 0.5f else b.tvScheduled.alpha
            b.tvPassingTag.visibility = if (pt.isPassing) View.VISIBLE else View.GONE

            // ── Timeline ──────────────────────────────────────────────────────
            b.lineTop.visibility    = if (row.isFirst) View.INVISIBLE else View.VISIBLE
            b.lineBottom.visibility = if (row.isLast)  View.INVISIBLE else View.VISIBLE

            // ── Highlight current query station ───────────────────────────────
            val isHighlighted = pt.crs == highlightCrs
            b.dot.setColorFilter(when {
                isHighlighted -> colourAccent
                pt.isPassing  -> colourGrey
                else          -> colourPrimary
            })
            b.tvStation.setTextColor(if (isHighlighted) colourAccent else colourOnSurface)

            // ── Click handler ─────────────────────────────────────────────────
            if (!pt.isPassing && onStationClick != null) {
                b.root.setOnClickListener { onStationClick.invoke(pt) }
                b.root.isClickable = true
            } else {
                b.root.setOnClickListener(null)
                b.root.isClickable = false
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CallingPointRow>() {
            override fun areItemsTheSame(a: CallingPointRow, b: CallingPointRow) =
                a.point.crs == b.point.crs &&
                a.point.st  == b.point.st  &&
                a.isDetailed == b.isDetailed

            override fun areContentsTheSame(a: CallingPointRow, b: CallingPointRow) = a == b
        }
    }
}
