package com.traintracker

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.traintracker.databinding.ItemCallingPointBinding
import com.traintracker.databinding.ItemTrainHereDividerBinding

data class CallingPointRow(
    val point: CallingPoint,
    val isFirst: Boolean,
    val isLast: Boolean,
    val isDetailed: Boolean,
    /** Station the train has already departed from (TRUST confirmed) */
    val hasPassed: Boolean = false,
    /** The train is currently at or between this and the next stop */
    val isCurrent: Boolean = false,
    /**
     * Non-null when the train length changes at this stop — indicates a split or join.
     * e.g. "Train divides here · 6 coaches continue" or "Train joins here · 12 coaches"
     */
    val splitJoinNote: String? = null,
    /**
     * True for the synthetic divider row injected between the last passed stop
     * and the next upcoming stop. The [point] field is unused for divider rows.
     */
    val isTrainHereDivider: Boolean = false,
    /** Delay in minutes to display on the divider badge, 0 = on time / unknown */
    val dividerDelayMins: Int = 0
)

class CallingPointAdapter(
    private val highlightCrs: String = "",
    var showPassing: Boolean = false,
    var showDetailed: Boolean = false,
    private val onStationClick: ((CallingPoint) -> Unit)? = null
) : ListAdapter<CallingPointRow, RecyclerView.ViewHolder>(DIFF) {

    private var colourOnSurface = 0
    private var colourCancelled = 0
    private var colourDelayed   = 0
    private var colourOnTime    = 0
    private var colourAccent    = 0
    private var colourPrimary   = 0
    private var colourGrey      = 0
    private var colourPassed    = 0
    private var coloursReady    = false

    companion object {
        private const val TYPE_STOP    = 0
        private const val TYPE_DIVIDER = 1

        private val DIFF = object : DiffUtil.ItemCallback<CallingPointRow>() {
            override fun areItemsTheSame(a: CallingPointRow, b: CallingPointRow): Boolean {
                if (a.isTrainHereDivider && b.isTrainHereDivider) return true
                if (a.isTrainHereDivider != b.isTrainHereDivider) return false
                return a.point.crs == b.point.crs &&
                        a.point.st  == b.point.st  &&
                        a.isDetailed == b.isDetailed
            }
            override fun areContentsTheSame(a: CallingPointRow, b: CallingPointRow) = a == b
        }
    }

    // ── ViewHolder types ──────────────────────────────────────────────────────

    inner class StopViewHolder(private val b: ItemCallingPointBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(row: CallingPointRow) {
            val pt         = row.point
            val isDetailed = row.isDetailed

            // ── Station name ──────────────────────────────────────────────────
            val isTiploc = pt.crs.isEmpty() && pt.locationName.matches(Regex("[A-Z0-9]{4,7}"))
            if (isTiploc) {
                val span = SpannableString(pt.locationName)
                span.setSpan(StyleSpan(Typeface.ITALIC), 0, span.length, 0)
                span.setSpan(ForegroundColorSpan(colourGrey), 0, span.length, 0)
                b.tvStation.text = span
            } else {
                b.tvStation.text = pt.locationName
            }

            b.tvCrs.text = pt.crs
            b.tvCrs.visibility = View.VISIBLE

            val stopCancelled = pt.isCancelled
                    || pt.et.equals("Cancelled", ignoreCase = true)
                    || pt.at.equals("Cancelled", ignoreCase = true)

            val timeColour = when {
                stopCancelled                          -> colourCancelled
                row.hasPassed && pt.delayMinutes > 0  -> colourDelayed
                row.hasPassed                         -> colourOnTime
                pt.isDelayed                          -> colourDelayed
                else                                  -> colourOnTime
            }

            if (pt.st.isNotEmpty() && pt.st != "—") {
                b.tvScheduled.text = formatTimeFromIso(pt.st)
                b.tvScheduled.visibility = View.VISIBLE
                val shouldStrike = (pt.isDelayed || stopCancelled) && !row.hasPassed
                if (shouldStrike) {
                    b.tvScheduled.paintFlags = b.tvScheduled.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    b.tvScheduled.alpha = 0.5f
                } else {
                    b.tvScheduled.paintFlags = b.tvScheduled.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    b.tvScheduled.alpha = if (row.hasPassed) 0.5f else 1f
                }
            } else {
                b.tvScheduled.visibility = View.GONE
            }

            val actualText = when {
                stopCancelled                                              -> "Canc"
                row.hasPassed && pt.at.isNotEmpty() && pt.at != "On time" -> "✓ ${formatTimeFromIso(pt.at)}"
                row.hasPassed                                              -> "✓"
                pt.at.isNotEmpty() && pt.at != "On time"                  -> formatTimeFromIso(pt.at)
                pt.et.isEmpty() || pt.et == "On time"                     -> "On time"
                else                                                       -> "~${formatTimeFromIso(pt.et)}"
            }
            b.tvActual.text = actualText
            b.tvActual.setTextColor(timeColour)
            b.tvActual.visibility = View.VISIBLE

            if (isDetailed) {
                if (pt.delayMinutes > 0 && !stopCancelled && !row.hasPassed) {
                    b.tvDelayMin.text = b.root.context.getString(R.string.delay_mins, pt.delayMinutes)
                    b.tvDelayMin.visibility = View.VISIBLE
                } else {
                    b.tvDelayMin.visibility = View.GONE
                }
                val note = row.splitJoinNote
                when {
                    note != null -> {
                        b.tvLength.text = note
                        b.tvLength.visibility = View.VISIBLE
                    }
                    pt.length != null && pt.length > 0 -> {
                        b.tvLength.text = b.root.context.getString(R.string.coach_count_label, pt.length)
                        b.tvLength.visibility = View.VISIBLE
                    }
                    else -> b.tvLength.visibility = View.GONE
                }
                if (pt.platform.isNotEmpty()) {
                    b.tvPlatform.text = b.root.context.getString(R.string.platform_label, pt.platform)
                    b.tvPlatform.visibility = View.VISIBLE
                } else {
                    b.tvPlatform.visibility = View.GONE
                }
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
                b.tvArrDep.visibility = View.GONE
                if (pt.delayMinutes > 0 && !stopCancelled && !row.hasPassed) {
                    b.tvDelayMin.text = b.root.context.getString(R.string.delay_mins, pt.delayMinutes)
                    b.tvDelayMin.visibility = View.VISIBLE
                } else {
                    b.tvDelayMin.visibility = View.GONE
                }
                if (pt.platform.isNotEmpty()) {
                    b.tvPlatform.text = b.root.context.getString(R.string.platform_label, pt.platform)
                    b.tvPlatform.visibility = View.VISIBLE
                } else {
                    b.tvPlatform.visibility = View.GONE
                }
                if (row.splitJoinNote == null) b.tvLength.visibility = View.GONE
            }

            when {
                pt.isPassing  -> {
                    b.tvPassingTag.text = b.root.context.getString(R.string.status_passing)
                    b.tvPassingTag.visibility = View.VISIBLE
                }
                stopCancelled -> {
                    b.tvPassingTag.text = b.root.context.getString(R.string.status_not_calling)
                    b.tvPassingTag.visibility = View.VISIBLE
                }
                else -> b.tvPassingTag.visibility = View.GONE
            }

            b.lineTop.visibility    = if (row.isFirst) View.INVISIBLE else View.VISIBLE
            b.lineBottom.visibility = if (row.isLast)  View.INVISIBLE else View.VISIBLE

            val isHighlighted = pt.crs == highlightCrs || row.isCurrent
            val rowAlpha = when {
                row.hasPassed -> 0.55f
                pt.isPassing  -> 0.5f
                stopCancelled -> 0.5f
                else          -> 1f
            }
            b.tvStation.alpha   = rowAlpha
            b.tvCrs.alpha       = rowAlpha
            b.tvScheduled.alpha = if (row.hasPassed) 0.4f else b.tvScheduled.alpha

            val dotColour = when {
                row.isCurrent  -> colourAccent
                isHighlighted  -> colourAccent
                row.hasPassed  -> colourPassed
                pt.isPassing   -> colourGrey
                else           -> colourPrimary
            }
            b.dot.setColorFilter(dotColour)

            val nameColour = when {
                stopCancelled                  -> colourCancelled
                row.isCurrent || isHighlighted -> colourAccent
                row.hasPassed                  -> colourGrey
                else                           -> colourOnSurface
            }
            b.tvStation.setTextColor(nameColour)
            if (stopCancelled && !row.hasPassed) {
                b.tvStation.paintFlags = b.tvStation.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                b.tvStation.paintFlags = b.tvStation.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            if (!pt.isPassing && onStationClick != null) {
                b.root.setOnClickListener { onStationClick.invoke(pt) }
                b.root.isClickable = true
            } else {
                b.root.setOnClickListener(null)
                b.root.isClickable = false
            }
        }
    }

    inner class DividerViewHolder(private val b: ItemTrainHereDividerBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(row: CallingPointRow) {
            if (row.dividerDelayMins > 0) {
                b.tvHerDelay.text = b.root.context.getString(R.string.divider_delay, row.dividerDelayMins)
                b.tvHerDelay.visibility = View.VISIBLE
            } else {
                b.tvHerDelay.visibility = View.GONE
            }
        }
    }

    // ── Adapter overrides ─────────────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).isTrainHereDivider) TYPE_DIVIDER else TYPE_STOP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_DIVIDER -> {
                DividerViewHolder(
                    ItemTrainHereDividerBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false
                    )
                )
            }
            else -> {
                val v = StopViewHolder(
                    ItemCallingPointBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false
                    )
                )
                if (!coloursReady) {
                    val ctx = parent.context
                    colourOnSurface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
                    colourCancelled = ContextCompat.getColor(ctx, R.color.status_cancelled)
                    colourDelayed   = ContextCompat.getColor(ctx, R.color.status_delayed)
                    colourOnTime    = ContextCompat.getColor(ctx, R.color.status_ontime)
                    colourAccent    = ContextCompat.getColor(ctx, R.color.accent)
                    colourPrimary   = ContextCompat.getColor(ctx, R.color.primary)
                    colourGrey      = ContextCompat.getColor(ctx, android.R.color.darker_gray)
                    colourPassed    = colourOnTime
                    coloursReady    = true
                }
                v
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is DividerViewHolder -> holder.bind(item)
            is StopViewHolder    -> holder.bind(item)
        }
    }

    // ── submitFiltered ────────────────────────────────────────────────────────

    /**
     * Builds the row list from calling points.
     * - Marks stops as passed based on [passedCrs] (set of CRS codes with confirmed departures)
     * - Injects a TRAIN_HERE divider row immediately before the first un-departed stop
     * - The divider carries the current [delayMins] for display
     */
    fun submitFiltered(
        points: List<CallingPoint>,
        passedCrs: Set<String> = emptySet(),
        currentCrs: String = "",
        delayMins: Int = 0
    ) {
        val filtered = if (showPassing) points else points.filter { !it.isPassing }

        // currentIdx = first stop the train has NOT yet departed from
        val currentIdx = when {
            currentCrs.isNotEmpty() -> filtered.indexOfFirst { it.crs == currentCrs }
            passedCrs.isNotEmpty()  -> {
                val lastPassedIdx = filtered.indexOfLast { it.crs in passedCrs }
                if (lastPassedIdx >= 0 && lastPassedIdx + 1 < filtered.size) lastPassedIdx + 1 else -1
            }
            else -> -1
        }

        val rows = mutableListOf<CallingPointRow>()

        filtered.forEachIndexed { i, pt ->
            // Inject the train-here divider immediately before the current stop,
            // but only if there are passed stops above it (so divider isn't at top)
            if (i == currentIdx && currentIdx > 0 && passedCrs.isNotEmpty()) {
                rows.add(
                    CallingPointRow(
                        point              = pt,   // unused by DividerViewHolder
                        isFirst            = false,
                        isLast             = false,
                        isDetailed         = showDetailed,
                        isTrainHereDivider = true,
                        dividerDelayMins   = delayMins
                    )
                )
            }

            // Split/join detection
            val prevLength = (i - 1 downTo 0)
                .map { filtered[it].length }
                .firstOrNull { (it ?: 0) > 0 }
            val thisLength = pt.length?.takeIf { it > 0 }
            val note: String? = when {
                prevLength != null && thisLength != null && prevLength > thisLength ->
                    "⚡ Train divides · $thisLength coaches continue"
                prevLength != null && thisLength != null && prevLength < thisLength ->
                    "⚡ Train joins · $thisLength coaches from here"
                else -> null
            }

            rows.add(
                CallingPointRow(
                    point         = pt,
                    isFirst       = i == 0,
                    isLast        = i == filtered.lastIndex,
                    isDetailed    = showDetailed,
                    hasPassed     = pt.crs in passedCrs,
                    isCurrent     = i == currentIdx,
                    splitJoinNote = note
                )
            )
        }

        submitList(rows)
    }
}