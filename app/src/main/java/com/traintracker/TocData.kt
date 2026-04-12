package com.traintracker

import androidx.core.graphics.toColorInt

object TocData {

    // Keys are the 2-letter ATOC/operator codes returned by OpenLDBWS
    // logoDrawableName matches the filename in res/drawable (e.g. "logo_se")
    data class TocEntry(
        val code: String,
        val name: String,
        val brandColorHex: String,
        val logoDrawableName: String = ""
    )

    private val ENTRIES = listOf(
        TocEntry("AW",  "Transport for Wales",   "#CC0033", "logo_aw"),
        TocEntry("CC",  "c2c",                   "#B20039", "logo_cc"),
        TocEntry("CH",  "Chiltern Railways",      "#00447C", "logo_ch"),
        TocEntry("CS",  "Caledonian Sleeper",     "#1C4EA0", "logo_cs"),
        TocEntry("EM",  "East Midlands Railway",  "#732982", "logo_em"),
        TocEntry("ES",  "Eurostar",               "#0F6CFD", "logo_es"),
        TocEntry("GC",  "Grand Central",          "#F58220", "logo_gc"),
        TocEntry("GN",  "Great Northern",         "#003DA5", "logo_gn"),
        TocEntry("GR",  "LNER",                   "#E21836", "logo_gr"),
        TocEntry("GW",  "Great Western Railway",  "#007A53", "logo_gw"),
        TocEntry("GX",  "Gatwick Express",        "#E21836", "logo_gx"),
        TocEntry("HT",  "Hull Trains",            "#EB2226", "logo_ht"),
        TocEntry("HX",  "Heathrow Express",       "#532B6F", "logo_hx"),
        TocEntry("IL",  "Island Line",            "#006CB7", "logo_il"),
        TocEntry("LE",  "Greater Anglia",         "#D10A11", "logo_le"),
        TocEntry("LM",  "West Midlands Trains",   "#FF6600", "logo_lm"),
        TocEntry("LD",  "Lumo",                   "#1568BF", "logo_ld"),
        TocEntry("LF",  "Lumo",                   "#1568BF", "logo_ld"),
        TocEntry("LO",  "London Overground",      "#EE7C0E", "logo_lo"),
        TocEntry("ME",  "Merseyrail",             "#FFE600", "logo_me"),
        TocEntry("NT",  "Northern Trains",        "#A71F25", "logo_nt"),
        TocEntry("SE",  "Southeastern",           "#003CA6", "logo_se"),
        TocEntry("SN",  "Southern",               "#00A650", "logo_sn"),
        TocEntry("SR",  "ScotRail",               "#1C4EA0", "logo_sr"),
        TocEntry("SW",  "South Western Railway",  "#006B75", "logo_sw"),
        TocEntry("TL",  "Thameslink",             "#E21836", "logo_tl"),
        TocEntry("TP",  "TransPennine Express",   "#005BAA", "logo_tp"),
        TocEntry("VT",  "Avanti West Coast",      "#004B87", "logo_vt"),
        TocEntry("XC",  "CrossCountry",           "#660F21", "logo_xc"),
        TocEntry("XR",  "Elizabeth line",         "#6950A1", "logo_xr"),
        // ── Freight operators ─────────────────────────────────────────────────
        TocEntry("DB",  "DB Cargo UK",            "#CC0000"),
        TocEntry("DBS", "DB Schenker",            "#CC0000"),
        TocEntry("FL",  "Freightliner",           "#00843D"),
        TocEntry("GB",  "GB Railfreight",         "#F6A623"),
        TocEntry("GBR", "GB Railfreight",         "#F6A623"),
        TocEntry("HB",  "Harry Needle Railroad",  "#003087"),
        TocEntry("DC",  "Direct Rail Services",   "#0047AB"),
        TocEntry("DRS", "Direct Rail Services",   "#0047AB"),
        TocEntry("AZ",  "Colas Rail",             "#E67817"),
        TocEntry("CR",  "Colas Rail",             "#E67817"),
        TocEntry("WR",  "West Coast Railways",    "#1B3A6B"),
        TocEntry("WCR", "West Coast Railways",    "#1B3A6B"),
        TocEntry("EW",  "EWS/DB Cargo",           "#CC0000"),
        TocEntry("RM",  "Royal Mail",             "#FF0000"),
        TocEntry("LNW", "LNWR Heritage",          "#8B0000"),
    )

    // Build lookup maps
    private val BY_CODE: Map<String, TocEntry> = ENTRIES.associateBy { it.code.uppercase() }

    // Also accept common aliases / full names
    private val ALIASES = mapOf(
        "EMR"  to "EM",
        "GER"  to "LE",
        "GA"   to "LE",
        "GWR"  to "GW",
        "LNER" to "GR",
        "SWR"  to "SW",
        "TFW"  to "AW",
        "WMR"  to "LM",
        "NTR"  to "NT",
        "CHR"  to "CH",
        "C2C"  to "CC",
        "MRS"  to "ME",
        "HLR"  to "HT",
        "AVNT" to "VT",
        "GTR"  to "TL",   // Govia Thameslink Railway — use TL logo as closest
    )

    fun get(operatorCode: String): TocEntry? {
        val key = operatorCode.trim().uppercase()
        return BY_CODE[key] ?: BY_CODE[ALIASES[key] ?: ""]
    }

    /** Returns the drawable resource name for use with logoDrawableRes() */
    fun logoDrawableName(operatorCode: String): String =
        get(operatorCode)?.logoDrawableName ?: ""

    /** Resolves a drawable name to a resource ID without using the deprecated getIdentifier(). */
    fun logoDrawableRes(name: String, ctx: android.content.Context): Int {
        if (name.isEmpty()) return 0
        @Suppress("DiscouragedApi")
        return ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
    }

    fun brandColor(operatorCode: String): Int {
        val hex = get(operatorCode)?.brandColorHex ?: "#555555"
        return try { hex.toColorInt() } catch (_: Exception) { 0xFF555555.toInt() }
    }

}