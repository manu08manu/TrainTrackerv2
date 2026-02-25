package com.traintracker

/**
 * Maps TOPS unit/vehicle numbers to class names and traction type.
 *
 * Darwin formation messages provide raw unit numbers like "387001", "800321", "43012".
 * This object converts those into human-readable descriptions similar to Know Your Train.
 *
 * TOPS numbering rules:
 *  - 5-digit: first 2 digits = class  (e.g. 43012 → Class 43)
 *  - 6-digit: first 3 digits = class  (e.g. 387001 → Class 387, 800321 → Class 800)
 *  - Class 1–99   = locomotives
 *  - Class 100–299 = DMUs
 *  - Class 300–799 = EMUs
 *  - Class 800+   = bi-mode / new generation
 */
object RollingStockData {

    data class ClassInfo(
        val name: String,        // e.g. "Class 387 Electrostar"
        val traction: String,    // "EMU", "DMU", "DEMU", "HST", "Loco", "Bi-mode"
        val operator: String = ""
    )

    /** Derive the class number from a raw TOPS unit number string. */
    fun classFromUnit(unit: String): Int? {
        val digits = unit.filter { it.isDigit() }
        return when (digits.length) {
            5    -> digits.take(2).toIntOrNull()
            6    -> digits.take(3).toIntOrNull()
            else -> null
        }
    }

    /** Return a [ClassInfo] for a given unit number, or null if unknown. */
    fun infoFromUnit(unit: String): ClassInfo? {
        val cls = classFromUnit(unit) ?: return null
        return classInfoMap[cls]
    }

    /**
     * Given a list of unit numbers from a Darwin formation, return a compact
     * description string like "Class 387 Electrostar · EMU · 387001 + 387002"
     */
    fun describeFormation(units: List<String>, coachCount: Int = 0): String {
        if (units.isEmpty()) return ""

        // Get class info from the first unit (they should all be the same class in most cases)
        val info = infoFromUnit(units.first())
        val className = info?.name ?: units.first().let {
            val cls = classFromUnit(it)
            if (cls != null) "Class $cls" else ""
        }
        val traction = info?.traction ?: ""

        val unitStr  = units.joinToString(" + ")
        val coachStr = if (coachCount > 0) " · ${coachCount}c" else ""

        return buildString {
            if (className.isNotEmpty()) append(className)
            if (traction.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(traction)
            }
            if (isNotEmpty() && unitStr.isNotEmpty()) append(" · ")
            append(unitStr)
            append(coachStr)
        }
    }

    /**
     * Short class label for the board card — e.g. "387 Electrostar" or "800 Azuma"
     * Returns "" if unknown.
     */
    fun shortLabel(units: List<String>): String {
        if (units.isEmpty()) return ""
        val info = infoFromUnit(units.first()) ?: run {
            val cls = classFromUnit(units.first())
            return if (cls != null) "Class $cls" else ""
        }
        // Strip "Class NNN " prefix for brevity
        val shortName = info.name.removePrefix("Class ").let { n ->
            // "387 Electrostar" → already short enough
            n
        }
        return shortName
    }

    // ─── Class database ───────────────────────────────────────────────────────
    // Covers the most common classes on the Great British network.
    // Traction: EMU = electric multiple unit, DMU = diesel, DEMU = diesel-electric,
    //           HST = High Speed Train (IC125), Loco = locomotive, Bi-mode = dual mode

    private val classInfoMap: Map<Int, ClassInfo> = mapOf(

        // ── Locomotives ───────────────────────────────────────────────────────
        37  to ClassInfo("Class 37",                      "Loco"),
        43  to ClassInfo("Class 43 (IC125/HST)",          "HST"),
        47  to ClassInfo("Class 47",                      "Loco"),
        57  to ClassInfo("Class 57",                      "Loco"),
        66  to ClassInfo("Class 66",                      "Loco"),
        67  to ClassInfo("Class 67",                      "Loco"),
        68  to ClassInfo("Class 68",                      "Loco"),
        70  to ClassInfo("Class 70",                      "Loco"),
        73  to ClassInfo("Class 73",                      "Loco"),
        86  to ClassInfo("Class 86",                      "Loco"),
        87  to ClassInfo("Class 87",                      "Loco"),
        88  to ClassInfo("Class 88",                      "Loco"),
        90  to ClassInfo("Class 90",                      "Loco"),
        91  to ClassInfo("Class 91",                      "Loco"),
        92  to ClassInfo("Class 92",                      "Loco"),

        // ── DMUs ──────────────────────────────────────────────────────────────
        142 to ClassInfo("Class 142 Pacer",               "DMU"),
        143 to ClassInfo("Class 143 Pacer",               "DMU"),
        144 to ClassInfo("Class 144 Pacer",               "DMU"),
        150 to ClassInfo("Class 150 Sprinter",            "DMU"),
        153 to ClassInfo("Class 153 Super Sprinter",      "DMU"),
        155 to ClassInfo("Class 155 Super Sprinter",      "DMU"),
        156 to ClassInfo("Class 156 Super Sprinter",      "DMU"),
        158 to ClassInfo("Class 158 Express Sprinter",    "DMU"),
        159 to ClassInfo("Class 159 South Western Turbo", "DMU"),
        165 to ClassInfo("Class 165 Turbo",               "DMU"),
        166 to ClassInfo("Class 166 Turbo Express",       "DMU"),
        168 to ClassInfo("Class 168 Clubman",             "DMU"),
        170 to ClassInfo("Class 170 Turbostar",           "DMU"),
        171 to ClassInfo("Class 171 Turbostar",           "DMU"),
        172 to ClassInfo("Class 172 Turbostar",           "DMU"),
        175 to ClassInfo("Class 175 Coradia",             "DMU"),
        185 to ClassInfo("Class 185 Pennine",             "DMU"),
        195 to ClassInfo("Class 195 Civity",              "DMU"),
        196 to ClassInfo("Class 196 Civity",              "DMU"),
        197 to ClassInfo("Class 197 Civity",              "DMU"),
        220 to ClassInfo("Class 220 Voyager",             "DEMU"),
        221 to ClassInfo("Class 221 Super Voyager",       "DEMU"),
        222 to ClassInfo("Class 222 Meridian/Pioneer",    "DEMU"),
        231 to ClassInfo("Class 231",                     "DMU"),

        // ── EMUs ─────────────────────────────────────────────────────────────
        313 to ClassInfo("Class 313",                     "EMU"),
        314 to ClassInfo("Class 314",                     "EMU"),
        315 to ClassInfo("Class 315",                     "EMU"),
        317 to ClassInfo("Class 317",                     "EMU"),
        318 to ClassInfo("Class 318",                     "EMU"),
        319 to ClassInfo("Class 319",                     "EMU"),
        320 to ClassInfo("Class 320",                     "EMU"),
        321 to ClassInfo("Class 321 Networker",           "EMU"),
        323 to ClassInfo("Class 323",                     "EMU"),
        325 to ClassInfo("Class 325 Royal Mail",          "EMU"),
        331 to ClassInfo("Class 331 Civity",              "EMU"),
        332 to ClassInfo("Class 332 Heathrow Express",    "EMU"),
        333 to ClassInfo("Class 333",                     "EMU"),
        334 to ClassInfo("Class 334 Juniper",             "EMU"),
        345 to ClassInfo("Class 345 Aventra",             "EMU"),
        350 to ClassInfo("Class 350 Desiro",              "EMU"),
        357 to ClassInfo("Class 357 Electrostar",         "EMU"),
        360 to ClassInfo("Class 360 Desiro",              "EMU"),
        375 to ClassInfo("Class 375 Electrostar",         "EMU"),
        376 to ClassInfo("Class 376 Electrostar",         "EMU"),
        377 to ClassInfo("Class 377 Electrostar",         "EMU"),
        378 to ClassInfo("Class 378 Capitalstar",         "EMU"),
        379 to ClassInfo("Class 379 Electrostar",         "EMU"),
        380 to ClassInfo("Class 380 Desiro",              "EMU"),
        385 to ClassInfo("Class 385 AT200",               "EMU"),
        387 to ClassInfo("Class 387 Electrostar",         "EMU"),
        390 to ClassInfo("Class 390 Pendolino",           "EMU"),
        395 to ClassInfo("Class 395 Javelin",             "EMU"),
        397 to ClassInfo("Class 397 Nova 2",              "EMU"),
        399 to ClassInfo("Class 399 Citylink",            "EMU"),
        442 to ClassInfo("Class 442 Wessex Electric",     "EMU"),
        444 to ClassInfo("Class 444 Desiro",              "EMU"),
        450 to ClassInfo("Class 450 Desiro",              "EMU"),
        455 to ClassInfo("Class 455",                     "EMU"),
        456 to ClassInfo("Class 456",                     "EMU"),
        458 to ClassInfo("Class 458 Juniper",             "EMU"),
        460 to ClassInfo("Class 460 Juniper",             "EMU"),
        465 to ClassInfo("Class 465 Networker",           "EMU"),
        466 to ClassInfo("Class 466 Networker",           "EMU"),
        483 to ClassInfo("Class 483",                     "EMU"),
        507 to ClassInfo("Class 507",                     "EMU"),
        508 to ClassInfo("Class 508",                     "EMU"),
        700 to ClassInfo("Class 700 Desiro City",         "EMU"),
        707 to ClassInfo("Class 707 Desiro City",         "EMU"),
        710 to ClassInfo("Class 710 Aventra",             "EMU"),
        717 to ClassInfo("Class 717 Desiro City",         "EMU"),
        720 to ClassInfo("Class 720 Aventra",             "EMU"),
        730 to ClassInfo("Class 730",                     "EMU"),
        745 to ClassInfo("Class 745 Flirt",               "EMU"),
        755 to ClassInfo("Class 755 Flirt",               "DEMU"),
        766 to ClassInfo("Class 766",                     "EMU"),

        // ── Bi-mode / New generation ──────────────────────────────────────────
        800 to ClassInfo("Class 800 Azuma/AT300",         "Bi-mode"),
        801 to ClassInfo("Class 801 Azuma",               "EMU"),
        802 to ClassInfo("Class 802 Azuma/Nova 1",        "Bi-mode"),
        803 to ClassInfo("Class 803 Lumo",                "EMU"),
        805 to ClassInfo("Class 805",                     "Bi-mode"),
        806 to ClassInfo("Class 806",                     "EMU"),
        807 to ClassInfo("Class 807",                     "EMU"),
        810 to ClassInfo("Class 810",                     "Bi-mode"),
    )
}
