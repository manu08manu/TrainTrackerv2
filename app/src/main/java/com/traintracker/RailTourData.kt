package com.traintracker

/**
 * Known rail tour and charter service identification.
 *
 * Maps headcode prefixes, RSID patterns, and known operator codes to tour names.
 * This is supplementary to the headcode-based detection; it gives the tour a
 * human-readable name where possible.
 *
 * Sources: enthusiast records, VSOE/charter operator published rosters.
 * Note: headcodes rotate; this is best-effort identification only.
 */
object RailTourData {

    data class TourOrganiser(
        val code: String,           // ATOC/operator code
        val name: String,
        val brandColor: String = "#333333"
    )

    /**
     * Known tour operators by operator code.
     * Some charters run under the TOC's own code; others under specialist codes.
     */
    val TOUR_OPERATORS = mapOf(
        "LZ"  to TourOrganiser("LZ",  "Locomotive Services Ltd",      "#8B0000"),
        "WC"  to TourOrganiser("WC",  "West Coast Railways",          "#1B3A6B"),
        "BL"  to TourOrganiser("BL",  "Belmond VSOE",                 "#000080"),
        "RT"  to TourOrganiser("RT",  "Rail Travel",                  "#2E4A1E"),
        "OS"  to TourOrganiser("OS",  "Statesman Rail",               "#4A2C6E"),
        "PA"  to TourOrganiser("PA",  "Past Time Rail",               "#5C3317"),
        "RF"  to TourOrganiser("RF",  "Railtour Federation",          "#1A3C5E"),
        "GR"  to TourOrganiser("GR",  "LNER (charter)",               "#E21836"),
    )

    /**
     * Railtour headcode patterns (first 2 chars of 4-char headcode).
     * These are the Working Timetable headcode prefixes commonly used for railtours
     * and charter trains in the UK.
     */
    val RAILTOUR_HEADCODE_PREFIXES = setOf(
        "1Z", "0Z", "5Z",  // Most common railtour / charter prefixes
        "0X", "5X",        // Excursion / special variants
        "2Z", "3Z", "9Z"   // Less common but used for genuine railtours
        // Excluded: 1X (Thameslink), 9O/9I (Eurostar), 9A-9H (Thameslink),
        //           9J-9M (London Overground), 9R/9S (Elizabeth line),
        //           9X/9V (other scheduled services)
    )

    /**
     * Returns a display name for a known railtour based on headcode and operator.
     * Returns empty string if no specific tour name is known.
     */
    fun tourNameFor(headcode: String, rsid: String, operatorCode: String): String {
        if (headcode.length < 2) return ""

        // Check if the RSID indicates a specific charter series
        val rsidUpper = rsid.uppercase()
        return when {
            rsidUpper.contains("VSOE") || rsidUpper.contains("ORIENT") -> "Venice Simplon-Orient-Express"
            rsidUpper.contains("CALEDONIAN") -> "Caledonian Sleeper Charter"
            rsidUpper.contains("ROYAL") -> "Royal Train"
            operatorCode == "WC" -> "West Coast Railways Charter"
            operatorCode == "BL" -> "Belmond Charter"
            else -> ""
        }
    }

    /** Is this headcode a known railtour/charter prefix? */
    fun isRailtourPrefix(headcode: String): Boolean {
        if (headcode.length < 2) return false
        return headcode.take(2).uppercase() in RAILTOUR_HEADCODE_PREFIXES
    }

    /** Freight operator descriptions by headcode first digit. */
    fun freightDescriptor(headcode: String): String {
        if (headcode.isEmpty()) return "Freight"
        return when (headcode[0]) {
            '6'  -> "Class 6 Freight"
            '7'  -> "Class 7 Freight"
            else -> "Freight"
        }
    }

    /** All non-passenger service types in priority display order. */
    val NON_PASSENGER_ICONS = mapOf(
        ServiceCategory.FREIGHT      to "🚛",
        ServiceCategory.RAILTOUR     to "🚂",
        ServiceCategory.ECS          to "🚃",
        ServiceCategory.LIGHT_ENGINE to "🔧",
        ServiceCategory.BUS          to "🚌",
        ServiceCategory.FERRY        to "⛴️",
        ServiceCategory.SPECIAL      to "⚡"
    )
}
