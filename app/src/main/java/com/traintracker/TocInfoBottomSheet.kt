package com.traintracker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class TocInfoBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CODE     = "code"
        private const val ARG_NAME     = "name"
        private const val ARG_COLOR    = "color"
        private const val ARG_PHONE    = "phone"
        private const val ARG_AT_PHONE = "at_phone"
        private const val ARG_WEBSITE  = "website"
        private const val ARG_AT_URL   = "at_url"
        private const val ARG_LP_URL   = "lp_url"

        fun newInstance(service: TrainService, detail: KbTocEntry?): TocInfoBottomSheet {
            val brandColor = TocData.brandColor(service.operatorCode.ifEmpty { service.operator })
            return TocInfoBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CODE,     service.operatorCode)
                    putString(ARG_NAME,     detail?.name?.ifEmpty { service.operator } ?: service.operator)
                    putInt   (ARG_COLOR,    brandColor)
                    putString(ARG_PHONE,    detail?.customerServicePhone ?: "")
                    putString(ARG_AT_PHONE, detail?.assistedTravelPhone ?: "")
                    putString(ARG_WEBSITE,  detail?.website ?: "")
                    putString(ARG_AT_URL,   detail?.assistedTravelUrl ?: "")
                    putString(ARG_LP_URL,   detail?.lostPropertyUrl ?: "")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val args       = requireArguments()
        val brandColor = args.getInt(ARG_COLOR)
        val name       = args.getString(ARG_NAME, "")
        val phone      = args.getString(ARG_PHONE, "")
        val atPhone    = args.getString(ARG_AT_PHONE, "")
        val website    = args.getString(ARG_WEBSITE, "")
        val atUrl      = args.getString(ARG_AT_URL, "")
        val lpUrl      = args.getString(ARG_LP_URL, "")

        val onBrand = if (ColorUtils.calculateLuminance(brandColor) < 0.4)
            Color.WHITE else Color.BLACK

        val ctx  = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // ── Coloured header ───────────────────────────────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(brandColor)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        header.addView(TextView(ctx).apply {
            text      = name
            textSize  = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(onBrand)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text     = args.getString(ARG_CODE, "")
            textSize = 12f
            setTextColor(onBrand)
            alpha    = 0.7f
        })
        root.addView(header)

        // ── Contact rows ──────────────────────────────────────────────────────
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        fun addRow(emoji: String, label: String, value: String, action: (() -> Unit)? = null) {
            if (value.isBlank()) return
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(10))
                if (action != null) setOnClickListener { action() }
            }
            val strip = View(ctx).apply {
                setBackgroundColor(brandColor)
                layoutParams = LinearLayout.LayoutParams(dp(3),
                    LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) }
            }
            row.addView(strip)
            row.addView(TextView(ctx).apply {
                text     = emoji
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
            })
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { text = label; textSize = 10f; alpha = 0.6f })
            col.addView(TextView(ctx).apply {
                text     = value
                textSize = 14f
                if (action != null) setTextColor(brandColor)
            })
            row.addView(col)
            body.addView(row)
            body.addView(View(ctx).apply {
                setBackgroundColor(0x1A000000)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }

        addRow("📞", "Customer services", phone) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        }
        addRow("🌐", "Website",
            website.removePrefix("https://").removePrefix("http://")) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(if (website.startsWith("http")) website else "https://$website")))
        }
        addRow("♿", "Assisted travel", atPhone) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$atPhone")))
        }
        addRow("🔗", "Assisted travel booking",
            atUrl.removePrefix("https://").removePrefix("http://")) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(if (atUrl.startsWith("http")) atUrl else "https://$atUrl")))
        }
        addRow("🧳", "Lost property",
            lpUrl.removePrefix("https://").removePrefix("http://")) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(if (lpUrl.startsWith("http")) lpUrl else "https://$lpUrl")))
        }

        if (body.childCount == 0) {
            body.addView(TextView(ctx).apply {
                text     = "No contact details available"
                textSize = 13f
                alpha    = 0.5f
                setPadding(0, dp(16), 0, dp(16))
            })
        }

        root.addView(body)
        return root
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}