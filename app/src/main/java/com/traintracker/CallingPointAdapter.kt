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
    val hasPassed: Boolean = false,
    val isCurrent: Boolean = false,
    val splitJoinNote: String? = null,
    val isTrainHereDivider: Boolean = false,
    val dividerDelayMins: Int = 0
)

class CallingPointAdapter(
    private val highlightCrs: String = "",
    var showPassing: Boolean = false,
    var showDetailed: Boolean = false,
    private val onStationClick: ((CallingPoint) -> Unit)? = null,
    private val splitTiploc: String = "",
    private val splitToHeadcode: String = "",
    private val splitToDestName: String = "",
    private val splitToUid: String = "",
    private val onSplitClick: ((headcode: String, uid: String) -> Unit)? = null,
    private val couplingTiplocCrs: String = "",
    private val coupledFromHc: String = ""
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

    inner class StopViewHolder(private val b: ItemCallingPointBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(row: CallingPointRow) {
            val pt         = row.point
            val isDetailed = row.isDetailed

            // Coach count / split-join note — shown in all modes
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

            // Branch split indicator — shown in all modes
            val isSplitStation = splitTiploc.isNotEmpty() && pt.crs == splitTiploc
            if (isSplitStation) {
                b.layoutSplitBranch.visibility = View.VISIBLE
                val destPart = if (splitToDestName.isNotEmpty()) " \u2192 $splitToDestName" else ""
                b.tvSplitNote.text = if (splitToHeadcode.isNotEmpty()) "$splitToHeadcode$destPart" else "splits here"
                if (onSplitClick != null && splitToHeadcode.isNotEmpty()) {
                    b.layoutSplitBranch.setOnClickListener { onSplitClick.invoke(splitToHeadcode, splitToUid) }
                    b.layoutSplitBranch.isClickable = true
                } else {
                    b.layoutSplitBranch.setOnClickListener(null)
                    b.layoutSplitBranch.isClickable = false
                }
            } else {
                b.layoutSplitBranch.visibility = View.GONE
            }

            // Station name
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

            val isHighlighted = highlightCrs.isNotEmpty() && (pt.crs == highlightCrs || row.isCurrent)
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

            if (onStationClick != null && pt.crs.isNotEmpty()) {
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
                    val typedArray = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                    colourOnSurface = typedArray.getColor(0, 0xFF000000.toInt())
                    typedArray.recycle()
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

    fun submitFiltered(
        points: List<CallingPoint>,
        passedCrs: Set<String> = emptySet(),
        currentCrs: String = "",
        delayMins: Int = 0
    ) {
        val nonPassengerKeywords = setOf("junction", "loop", "yard", "siding", "sidings", "depot", "headquarters", "reception", "neck", "jn", "sdg")
        val filtered = if (showPassing) points else points.filter { pt ->
            !pt.isPassing && (
                    pt.crs.isNotEmpty() ||
                            (pt.locationName.isNotEmpty() &&
                                    nonPassengerKeywords.none { kw -> pt.locationName.lowercase().contains(kw) } &&
                                    !pt.locationName.matches(Regex("[A-Z0-9]{4,8}")))
                    )
        }

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
            if (i == currentIdx && currentIdx > 0 && passedCrs.isNotEmpty()) {
                rows.add(
                    CallingPointRow(
                        point              = pt,
                        isFirst            = false,
                        isLast             = false,
                        isDetailed         = showDetailed,
                        isTrainHereDivider = true,
                        dividerDelayMins   = delayMins
                    )
                )
            }

            val prevLength = (i - 1 downTo 0)
                .map { filtered[it].length }
                .firstOrNull { (it ?: 0) > 0 }
            val thisLength = pt.length?.takeIf { it > 0 }
            val note: String? = when {
                splitTiploc.isNotEmpty() && pt.crs == splitTiploc -> null
                couplingTiplocCrs.isNotEmpty() && pt.crs == couplingTiplocCrs ->
                    if (coupledFromHc.isNotEmpty()) "\uD83D\uDD17 $coupledFromHc joins here" else "\uD83D\uDD17 joins here"
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
