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

    /** Freight operator descriptions by headcode first digit. */
    fun freightDescriptor(headcode: String): String {
        if (headcode.isEmpty()) return "Freight"
        return when (headcode[0]) {
            '6'  -> "Class 6 Freight"
            '7'  -> "Class 7 Freight"
            else -> "Freight"
        }
    }

}