package com.traintracker

/**
 * Maps TOPS unit/vehicle numbers to class names, traction type, and known operators.
 */
object RollingStockData {

    data class ClassInfo(
        val name: String,
        val traction: String,       // EMU, DMU, DEMU, HST, Loco, Bi-mode, Diesel loco, Electric loco
        val operator: String = "",
        val topSpeed: Int = 0,      // mph
        val introduced: Int = 0,    // year
        val notes: String = ""
    )

    fun classFromUnit(unit: String): Int? {
        val digits = unit.filter { it.isDigit() }
        return when (digits.length) {
            5    -> digits.take(2).toIntOrNull()
            6    -> digits.take(3).toIntOrNull()
            else -> null
        }
    }

    fun infoFromUnit(unit: String): ClassInfo? {
        val digits = unit.filter { it.isDigit() }
        if (digits.length == 6) {
            val subclass = digits.take(4).toIntOrNull()
            if (subclass != null && subclassInfoMap.containsKey(subclass)) return subclassInfoMap[subclass]
        }
        val cls = classFromUnit(unit) ?: return null
        return classInfoMap[cls]
    }

    fun describeFormation(units: List<String>, coachCount: Int = 0): String {
        if (units.isEmpty()) return ""
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
            if (traction.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(traction) }
            if (isNotEmpty() && unitStr.isNotEmpty()) append(" · ")
            append(unitStr)
            append(coachStr)
        }
    }

    fun toUnitAllocation(units: List<String>, coachCount: Int = 0): UnitAllocation? {
        if (units.isEmpty()) return null
        val info    = infoFromUnit(units.first())
        val classes = units.mapNotNull { classFromUnit(it) }.distinct()
        val className = info?.name ?: classes.firstOrNull()?.let { "Class $it" } ?: ""
        val traction  = info?.traction ?: ""
        return UnitAllocation(
            units         = units,
            classNumbers  = units.mapNotNull { classFromUnit(it) },
            className     = className,
            tractionType  = traction,
            coachCount    = coachCount,
            multipleUnits = units.size > 1
        )
    }

    // ─── Full class database ─────────────────────────────────────────────────

    private val classInfoMap: Map<Int, ClassInfo> = mapOf(

        // ── Locomotives ───────────────────────────────────────────────────────
        8   to ClassInfo("Class 08 Shunter",         "Diesel loco", topSpeed = 15,  introduced = 1952),
        20  to ClassInfo("Class 20",                  "Diesel loco", topSpeed = 75,  introduced = 1957),
        31  to ClassInfo("Class 31",                  "Diesel loco", topSpeed = 90,  introduced = 1958),
        37  to ClassInfo("Class 37",                  "Diesel loco", topSpeed = 80,  introduced = 1960, notes = "Popular railtour & freight loco"),
        40  to ClassInfo("Class 40",                  "Diesel loco", topSpeed = 90,  introduced = 1958),
        43  to ClassInfo("Class 43 HST Power Car",    "HST",        topSpeed = 125, introduced = 1975, notes = "HST power car — pairs with Mk3 coaches"),
        44  to ClassInfo("Class 44",                  "Diesel loco", topSpeed = 90,  introduced = 1959),
        45  to ClassInfo("Class 45",                  "Diesel loco", topSpeed = 90,  introduced = 1960),
        46  to ClassInfo("Class 46",                  "Diesel loco", topSpeed = 90,  introduced = 1961),
        47  to ClassInfo("Class 47",                  "Diesel loco", topSpeed = 95,  introduced = 1962, notes = "Common railtour & charter loco"),
        50  to ClassInfo("Class 50",                  "Diesel loco", topSpeed = 100, introduced = 1967),
        55  to ClassInfo("Class 55 Deltic",           "Diesel loco", topSpeed = 100, introduced = 1961),
        56  to ClassInfo("Class 56",                  "Diesel loco", topSpeed = 80,  introduced = 1976),
        57  to ClassInfo("Class 57",                  "Diesel loco", topSpeed = 95,  introduced = 1998, notes = "Common charter/railtour loco"),
        58  to ClassInfo("Class 58",                  "Diesel loco", topSpeed = 80,  introduced = 1983),
        59  to ClassInfo("Class 59",                  "Diesel loco", topSpeed = 60,  introduced = 1985, notes = "Aggregate freight"),
        60  to ClassInfo("Class 60",                  "Diesel loco", topSpeed = 62,  introduced = 1989),
        66  to ClassInfo("Class 66",                  "Diesel loco", topSpeed = 75,  introduced = 1998, notes = "Most common freight loco"),
        67  to ClassInfo("Class 67",                  "Diesel loco", topSpeed = 125, introduced = 1999, notes = "Charter/Royal Train loco"),
        68  to ClassInfo("Class 68",                  "Diesel loco", topSpeed = 100, introduced = 2013),
        69  to ClassInfo("Class 69",                  "Diesel loco", topSpeed = 75,  introduced = 2022),
        70  to ClassInfo("Class 70",                  "Diesel loco", topSpeed = 75,  introduced = 2008),
        73  to ClassInfo("Class 73",                  "Electro-Diesel loco", topSpeed = 90, introduced = 1962),
        86  to ClassInfo("Class 86",                  "Electric loco", topSpeed = 110, introduced = 1965),
        87  to ClassInfo("Class 87",                  "Electric loco", topSpeed = 110, introduced = 1973),
        88  to ClassInfo("Class 88",                  "Bi-mode loco", topSpeed = 100, introduced = 2016),
        90  to ClassInfo("Class 90",                  "Electric loco", topSpeed = 110, introduced = 1987),
        91  to ClassInfo("Class 91 Intercity 225",    "Electric loco", topSpeed = 125, introduced = 1988, notes = "LNER East Coast Main Line"),
        92  to ClassInfo("Class 92",                  "Electric loco", topSpeed = 87,  introduced = 1993, notes = "Channel Tunnel freight"),

        // ── DMUs ─────────────────────────────────────────────────────────────
        101 to ClassInfo("Class 101 Calder Valley",   "DMU",  topSpeed = 70,  introduced = 1956),
        108 to ClassInfo("Class 108",                  "DMU",  topSpeed = 70,  introduced = 1958),
        142 to ClassInfo("Class 142 Pacer",            "DMU",  topSpeed = 75,  introduced = 1985),
        143 to ClassInfo("Class 143 Pacer",            "DMU",  topSpeed = 75,  introduced = 1985),
        144 to ClassInfo("Class 144 Pacer",            "DMU",  topSpeed = 75,  introduced = 1986),
        150 to ClassInfo("Class 150 Sprinter",         "DMU",  topSpeed = 75,  introduced = 1985),
        153 to ClassInfo("Class 153 Super Sprinter",   "DMU",  topSpeed = 75,  introduced = 1987),
        155 to ClassInfo("Class 155 Super Sprinter",   "DMU",  topSpeed = 75,  introduced = 1988),
        156 to ClassInfo("Class 156 Super Sprinter",   "DMU",  topSpeed = 75,  introduced = 1987),
        158 to ClassInfo("Class 158 Express Sprinter", "DMU",  topSpeed = 90,  introduced = 1990),
        159 to ClassInfo("Class 159 South Western Turbo", "DMU", topSpeed = 90, introduced = 1992, operator = "SWR"),
        165 to ClassInfo("Class 165 Networker Turbo",  "DMU",  topSpeed = 90,  introduced = 1992),
        166 to ClassInfo("Class 166 Networker Express","DMU",  topSpeed = 90,  introduced = 1993),
        170 to ClassInfo("Class 170 Turbostar",        "DMU",  topSpeed = 100, introduced = 1999),
        171 to ClassInfo("Class 171 Turbostar",        "DMU",  topSpeed = 100, introduced = 2003, operator = "SE"),
        175 to ClassInfo("Class 175 Coradia",          "DMU",  topSpeed = 100, introduced = 1999),
        180 to ClassInfo("Class 180 Adelante",         "DMU",  topSpeed = 125, introduced = 2001),
        185 to ClassInfo("Class 185 Desiro",           "DMU",  topSpeed = 100, introduced = 2005, operator = "TP"),
        195 to ClassInfo("Class 195 Civity",           "DMU",  topSpeed = 100, introduced = 2019, operator = "NT"),
        196 to ClassInfo("Class 196 Civity",           "DMU",  topSpeed = 100, introduced = 2021, operator = "WMR"),
        197 to ClassInfo("Class 197 Civity",           "DMU",  topSpeed = 100, introduced = 2022, operator = "TFW"),
        220 to ClassInfo("Class 220 Voyager",          "DEMU", topSpeed = 125, introduced = 2000, operator = "XC"),
        221 to ClassInfo("Class 221 Super Voyager",    "DEMU", topSpeed = 125, introduced = 2001),
        222 to ClassInfo("Class 222 Meridian",         "DEMU", topSpeed = 125, introduced = 2004),
        230 to ClassInfo("Class 230 D-Train",          "DEMU", topSpeed = 70,  introduced = 2018),

        // ── EMUs ─────────────────────────────────────────────────────────────
        313 to ClassInfo("Class 313",                  "EMU",  topSpeed = 75,  introduced = 1976),
        314 to ClassInfo("Class 314",                  "EMU",  topSpeed = 75,  introduced = 1979),
        315 to ClassInfo("Class 315",                  "EMU",  topSpeed = 75,  introduced = 1980),
        317 to ClassInfo("Class 317",                  "EMU",  topSpeed = 100, introduced = 1981),
        318 to ClassInfo("Class 318",                  "EMU",  topSpeed = 90,  introduced = 1985),
        319 to ClassInfo("Class 319",                  "EMU",  topSpeed = 100, introduced = 1987),
        320 to ClassInfo("Class 320",                  "EMU",  topSpeed = 75,  introduced = 1990),
        321 to ClassInfo("Class 321 Networker Express","EMU",  topSpeed = 100, introduced = 1988),
        322 to ClassInfo("Class 322",                  "EMU",  topSpeed = 100, introduced = 1990),
        323 to ClassInfo("Class 323",                  "EMU",  topSpeed = 90,  introduced = 1992),
        325 to ClassInfo("Class 325 Post Office",      "EMU",  topSpeed = 100, introduced = 1995, notes = "Royal Mail postal units"),
        331 to ClassInfo("Class 331 Civity",           "EMU",  topSpeed = 100, introduced = 2019, operator = "NT"),
        332 to ClassInfo("Class 332 Heathrow Express", "EMU",  topSpeed = 100, introduced = 1997, operator = "HX"),
        333 to ClassInfo("Class 333",                  "EMU",  topSpeed = 100, introduced = 2000),
        334 to ClassInfo("Class 334 Juniper",          "EMU",  topSpeed = 90,  introduced = 2000, operator = "SR"),
        345 to ClassInfo("Class 345 Aventra",          "EMU",  topSpeed = 90,  introduced = 2017, operator = "XR"),
        350 to ClassInfo("Class 350 Desiro",           "EMU",  topSpeed = 110, introduced = 2004),
        357 to ClassInfo("Class 357 Electrostar",      "EMU",  topSpeed = 100, introduced = 1999, operator = "CC"),
        360 to ClassInfo("Class 360 Desiro",           "EMU",  topSpeed = 100, introduced = 2002),
        365 to ClassInfo("Class 365 Networker Express","EMU",  topSpeed = 100, introduced = 1994),
        370 to ClassInfo("Class 370 Advanced Passenger Train","EMU", topSpeed = 150, introduced = 1978),
        373 to ClassInfo("Class 373 Eurostar",         "EMU",  topSpeed = 186, introduced = 1993, operator = "ES"),
        374 to ClassInfo("Class 374 Eurostar e320",    "EMU",  topSpeed = 200, introduced = 2015, operator = "ES"),
        375 to ClassInfo("Class 375 Electrostar",      "EMU",  topSpeed = 100, introduced = 1999, operator = "SE"),
        376 to ClassInfo("Class 376 Electrostar",      "EMU",  topSpeed = 75,  introduced = 2004, operator = "SE"),
        377 to ClassInfo("Class 377 Electrostar",      "EMU",  topSpeed = 100, introduced = 2002, operator = "SN"),
        378 to ClassInfo("Class 378 Capitalstar",      "EMU",  topSpeed = 75,  introduced = 2009, operator = "LO"),
        379 to ClassInfo("Class 379 Electrostar",      "EMU",  topSpeed = 100, introduced = 2011),
        380 to ClassInfo("Class 380 Desiro",           "EMU",  topSpeed = 100, introduced = 2010, operator = "SR"),
        381 to ClassInfo("Class 381",                  "EMU",  topSpeed = 100, introduced = 1978),
        385 to ClassInfo("Class 385 Aventra",          "EMU",  topSpeed = 100, introduced = 2017, operator = "SR"),
        387 to ClassInfo("Class 387 Electrostar",      "EMU",  topSpeed = 110, introduced = 2015),
        390 to ClassInfo("Class 390 Pendolino",        "EMU",  topSpeed = 125, introduced = 2001, operator = "VT"),
        395 to ClassInfo("Class 395 Javelin",          "EMU",  topSpeed = 140, introduced = 2009, operator = "SE"),
        397 to ClassInfo("Class 397 Nova 2",           "EMU",  topSpeed = 125, introduced = 2019, operator = "TP"),
        399 to ClassInfo("Class 399 Citylink",         "EMU",  topSpeed = 56,  introduced = 2015),
        444 to ClassInfo("Class 444 Desiro",           "EMU",  topSpeed = 100, introduced = 2003, operator = "SW"),
        450 to ClassInfo("Class 450 Desiro",           "EMU",  topSpeed = 100, introduced = 2002, operator = "SW"),
        455 to ClassInfo("Class 455",                  "EMU",  topSpeed = 75,  introduced = 1982),
        456 to ClassInfo("Class 456",                  "EMU",  topSpeed = 75,  introduced = 1990),
        458 to ClassInfo("Class 458 Juniper",          "EMU",  topSpeed = 75,  introduced = 1998, operator = "SW"),
        460 to ClassInfo("Class 460 Juniper",          "EMU",  topSpeed = 100, introduced = 2001),
        465 to ClassInfo("Class 465 Networker",        "EMU",  topSpeed = 75,  introduced = 1991, operator = "SE"),
        466 to ClassInfo("Class 466 Networker",        "EMU",  topSpeed = 75,  introduced = 1993, operator = "SE"),
        483 to ClassInfo("Class 483",                  "EMU",  topSpeed = 45,  introduced = 1938, operator = "IL"),
        507 to ClassInfo("Class 507",                  "EMU",  topSpeed = 75,  introduced = 1978, operator = "ME"),
        508 to ClassInfo("Class 508",                  "EMU",  topSpeed = 75,  introduced = 1979, operator = "ME"),
        700 to ClassInfo("Class 700 Desiro City",      "EMU",  topSpeed = 100, introduced = 2016, operator = "TL"),
        701 to ClassInfo("Class 701 Arterio",          "EMU",  topSpeed = 100, introduced = 2020, operator = "SW"),
        707 to ClassInfo("Class 707 Desiro City",      "EMU",  topSpeed = 100, introduced = 2017, operator = "SW"),
        710 to ClassInfo("Class 710 Aventra",          "EMU",  topSpeed = 75,  introduced = 2018, operator = "LO"),
        711 to ClassInfo("Class 711",                  "EMU",  topSpeed = 100, introduced = 2021),
        717 to ClassInfo("Class 717 Desiro City",      "EMU",  topSpeed = 100, introduced = 2018, operator = "GN"),
        720 to ClassInfo("Class 720 Aventra",          "EMU",  topSpeed = 100, introduced = 2020, operator = "LE"),
        730 to ClassInfo("Class 730 Aventra",          "EMU",  topSpeed = 110, introduced = 2021, operator = "WMR"),
        745 to ClassInfo("Class 745 Flirt",            "EMU",  topSpeed = 100, introduced = 2019, operator = "LE"),
        755 to ClassInfo("Class 755 Flirt",            "Bi-mode", topSpeed = 100, introduced = 2019, operator = "LE"),
        756 to ClassInfo("Class 756 Flirt",            "DEMU", topSpeed = 75, introduced = 2021, operator = "LE"),
        769 to ClassInfo("Class 769 Flex",             "DEMU", topSpeed = 100, introduced = 2019),
        777 to ClassInfo("Class 777 Nova 1",           "EMU",  topSpeed = 75,  introduced = 2022, operator = "ME"),

        // ── Bi-modes (class-level fallback) ──────────────────────────────────
        800 to ClassInfo("Class 800 IET/Azuma",        "Bi-mode", topSpeed = 125, introduced = 2017),
        801 to ClassInfo("Class 801 Azuma",            "EMU",     topSpeed = 125, introduced = 2019, operator = "GR"),
        802 to ClassInfo("Class 802 IET/Nova 1/Paragon",       "Bi-mode", topSpeed = 125, introduced = 2018),
        803 to ClassInfo("Class 803 Lumo",             "EMU",     topSpeed = 125, introduced = 2021, operator = "LD"),
        805 to ClassInfo("Class 805 Evero",            "Bi-mode", topSpeed = 125, introduced = 2024, operator = "VT"),
        807 to ClassInfo("Class 807 Evero",            "Bi-mode", topSpeed = 125, introduced = 2024, operator = "VT"),
        810 to ClassInfo("Class 810 Aurora",           "Bi-mode", topSpeed = 125, introduced = 2026, operator = "EM"),

        // ── HST coaching stock ────────────────────────────────────────────────
        254 to ClassInfo("HST Mk3 trailer",            "HST",  notes = "InterCity 125 trailer"),
    )

    private val subclassInfoMap: Map<Int, ClassInfo> = mapOf(
        // ── Class 150 ────────────────────────────────────────────────────────
        1500 to ClassInfo("Class 150/0 Sprinter",        "DMU",     topSpeed = 75,  introduced = 1984, notes = "2-car prototype"),
        1501 to ClassInfo("Class 150/1 Sprinter",        "DMU",     topSpeed = 75,  introduced = 1985, notes = "2-car"),
        1502 to ClassInfo("Class 150/2 Sprinter",        "DMU",     topSpeed = 75,  introduced = 1986, notes = "2-car"),
        // ── Class 158 ────────────────────────────────────────────────────────
        1587 to ClassInfo("Class 158/7 Express Sprinter","DMU",     topSpeed = 90,  introduced = 1990, notes = "2-car"),
        1588 to ClassInfo("Class 158/8 Express Sprinter","DMU",     topSpeed = 90,  introduced = 1990, notes = "3-car"),
        1589 to ClassInfo("Class 158/9 Express Sprinter","DMU",     topSpeed = 90,  introduced = 1990, operator = "SW", notes = "2-car"),
        // ── Class 159 ────────────────────────────────────────────────────────
        1590 to ClassInfo("Class 159/0",                 "DMU",     topSpeed = 90,  introduced = 1992, operator = "SW", notes = "3-car"),
        1591 to ClassInfo("Class 159/1",                 "DMU",     topSpeed = 90,  introduced = 2006, operator = "SW", notes = "3-car"),
        // ── Class 165 ────────────────────────────────────────────────────────
        1650 to ClassInfo("Class 165/0 Networker Turbo", "DMU",     topSpeed = 90,  introduced = 1992, notes = "2-car"),
        1651 to ClassInfo("Class 165/1 Networker Turbo", "DMU",     topSpeed = 90,  introduced = 1992, notes = "3-car"),
        // ── Class 166 ────────────────────────────────────────────────────────
        1662 to ClassInfo("Class 166/2 Networker Express","DMU",    topSpeed = 90,  introduced = 1993, notes = "3-car"),
        // ── Class 170 ────────────────────────────────────────────────────────
        1701 to ClassInfo("Class 170/1 Turbostar",       "DMU",     topSpeed = 100, introduced = 1999, notes = "2-car"),
        1702 to ClassInfo("Class 170/2 Turbostar",       "DMU",     topSpeed = 100, introduced = 2000, notes = "2-car"),
        1703 to ClassInfo("Class 170/3 Turbostar",       "DMU",     topSpeed = 100, introduced = 2000, notes = "3-car"),
        1704 to ClassInfo("Class 170/4 Turbostar",       "DMU",     topSpeed = 100, introduced = 2000, notes = "2 or 3-car"),
        1705 to ClassInfo("Class 170/5 Turbostar",       "DMU",     topSpeed = 100, introduced = 2001, notes = "2-car"),
        1706 to ClassInfo("Class 170/6 Turbostar",       "DMU",     topSpeed = 100, introduced = 2004, notes = "3-car"),
        1707 to ClassInfo("Class 170/7 Turbostar",       "DMU",     topSpeed = 100, introduced = 2004, notes = "3-car"),
        // ── Class 171 ────────────────────────────────────────────────────────
        1717 to ClassInfo("Class 171/7 Turbostar",       "DMU",     topSpeed = 100, introduced = 2003, operator = "SE", notes = "2-car"),
        1718 to ClassInfo("Class 171/8 Turbostar",       "DMU",     topSpeed = 100, introduced = 2004, operator = "SE", notes = "4-car"),
        // ── Class 175 ────────────────────────────────────────────────────────
        1750 to ClassInfo("Class 175/0 Coradia",         "DMU",     topSpeed = 100, introduced = 1999, operator = "AW", notes = "2-car"),
        1751 to ClassInfo("Class 175/1 Coradia",         "DMU",     topSpeed = 100, introduced = 2000, operator = "AW", notes = "3-car"),
        // ── Class 185 ────────────────────────────────────────────────────────
        1851 to ClassInfo("Class 185/1 Desiro",          "DMU",     topSpeed = 100, introduced = 2005, operator = "TP", notes = "3-car"),
        // ── Class 195 ────────────────────────────────────────────────────────
        1950 to ClassInfo("Class 195/0 Civity",          "DMU",     topSpeed = 100, introduced = 2019, operator = "NT", notes = "2-car"),
        1951 to ClassInfo("Class 195/1 Civity",          "DMU",     topSpeed = 100, introduced = 2019, operator = "NT", notes = "3-car"),
        // ── Class 196 ────────────────────────────────────────────────────────
        1960 to ClassInfo("Class 196/0 Civity",          "DMU",     topSpeed = 100, introduced = 2021, operator = "WMR", notes = "2-car"),
        1961 to ClassInfo("Class 196/1 Civity",          "DMU",     topSpeed = 100, introduced = 2021, operator = "WMR", notes = "4-car"),
        // ── Class 197 ────────────────────────────────────────────────────────
        1970 to ClassInfo("Class 197/0 Civity",          "DMU",     topSpeed = 100, introduced = 2022, operator = "TFW", notes = "2-car"),
        1971 to ClassInfo("Class 197/1 Civity",          "DMU",     topSpeed = 100, introduced = 2022, operator = "TFW", notes = "3-car"),
        // ── Class 220 ────────────────────────────────────────────────────────
        2200 to ClassInfo("Class 220/0 Voyager",         "DEMU",    topSpeed = 125, introduced = 2000, operator = "XC", notes = "4-car"),
        // ── Class 221 ────────────────────────────────────────────────────────
        2211 to ClassInfo("Class 221/1 Super Voyager",   "DEMU",    topSpeed = 125, introduced = 2001, operator = "VT", notes = "5-car"),
        2212 to ClassInfo("Class 221/2 Super Voyager",   "DEMU",    topSpeed = 125, introduced = 2002, operator = "XC", notes = "5-car"),
        // ── Class 222 ────────────────────────────────────────────────────────
        2220 to ClassInfo("Class 222/0",                 "DEMU",    topSpeed = 125, introduced = 2004, notes = "5 or 7-car; EMR (Meridian) and Lumo"),
        2221 to ClassInfo("Class 222/1 Meridian",        "DEMU",    topSpeed = 125, introduced = 2004, operator = "EM", notes = "4-car; EMR only"),
        2226 to ClassInfo("Class 222/6",                 "DEMU",    topSpeed = 125, introduced = 2021, operator = "LD", notes = "6-car; Lumo only"),
        // ── Class 313 ────────────────────────────────────────────────────────
        3130 to ClassInfo("Class 313/0",                 "EMU",     topSpeed = 75,  introduced = 1976, notes = "3-car"),
        3131 to ClassInfo("Class 313/1",                 "EMU",     topSpeed = 75,  introduced = 1976, notes = "3-car"),
        3132 to ClassInfo("Class 313/2",                 "EMU",     topSpeed = 75,  introduced = 1976, notes = "3-car"),
        // ── Class 317 ────────────────────────────────────────────────────────
        3171 to ClassInfo("Class 317/1",                 "EMU",     topSpeed = 100, introduced = 1981, notes = "4-car"),
        3172 to ClassInfo("Class 317/2",                 "EMU",     topSpeed = 100, introduced = 1985, notes = "4-car"),
        3173 to ClassInfo("Class 317/3",                 "EMU",     topSpeed = 100, introduced = 1985, notes = "4-car"),
        3174 to ClassInfo("Class 317/4",                 "EMU",     topSpeed = 100, introduced = 1987, notes = "4-car"),
        3175 to ClassInfo("Class 317/5",                 "EMU",     topSpeed = 100, introduced = 2004, notes = "4-car"),
        3176 to ClassInfo("Class 317/6",                 "EMU",     topSpeed = 100, introduced = 2004, notes = "4-car"),
        3177 to ClassInfo("Class 317/7",                 "EMU",     topSpeed = 100, introduced = 2004, notes = "4-car"),
        3178 to ClassInfo("Class 317/8",                 "EMU",     topSpeed = 100, introduced = 2004, notes = "4-car"),
        // ── Class 319 ────────────────────────────────────────────────────────
        3190 to ClassInfo("Class 319/0",                 "EMU",     topSpeed = 100, introduced = 1987, notes = "4-car"),
        3192 to ClassInfo("Class 319/2",                 "EMU",     topSpeed = 100, introduced = 1990, notes = "4-car"),
        3193 to ClassInfo("Class 319/3",                 "EMU",     topSpeed = 100, introduced = 1987, notes = "4-car"),
        3194 to ClassInfo("Class 319/4",                 "EMU",     topSpeed = 100, introduced = 1987, notes = "4-car"),
        // ── Class 331 ────────────────────────────────────────────────────────
        3310 to ClassInfo("Class 331/0 Civity",          "EMU",     topSpeed = 100, introduced = 2019, operator = "NT", notes = "3-car"),
        3311 to ClassInfo("Class 331/1 Civity",          "EMU",     topSpeed = 100, introduced = 2019, operator = "NT", notes = "5-car"),
        // ── Class 345 ────────────────────────────────────────────────────────
        3450 to ClassInfo("Class 345/0 Aventra",         "EMU",     topSpeed = 90,  introduced = 2017, operator = "XR", notes = "7-car"),
        3451 to ClassInfo("Class 345/1 Aventra",         "EMU",     topSpeed = 90,  introduced = 2017, operator = "XR", notes = "9-car"),
        // ── Class 350 ────────────────────────────────────────────────────────
        3501 to ClassInfo("Class 350/1 Desiro",          "EMU",     topSpeed = 110, introduced = 2004, operator = "LM", notes = "4-car"),
        3502 to ClassInfo("Class 350/2 Desiro",          "EMU",     topSpeed = 110, introduced = 2008, operator = "LM", notes = "4-car"),
        3503 to ClassInfo("Class 350/3 Desiro",          "EMU",     topSpeed = 110, introduced = 2013, operator = "TP", notes = "4-car"),
        3504 to ClassInfo("Class 350/4 Desiro",          "EMU",     topSpeed = 110, introduced = 2014, operator = "LM", notes = "4-car"),
        // ── Class 357 ────────────────────────────────────────────────────────
        3570 to ClassInfo("Class 357/0 Electrostar",     "EMU",     topSpeed = 100, introduced = 1999, operator = "CC", notes = "4-car"),
        3572 to ClassInfo("Class 357/2 Electrostar",     "EMU",     topSpeed = 100, introduced = 2001, operator = "CC", notes = "4-car"),
        3573 to ClassInfo("Class 357/3 Electrostar",     "EMU",     topSpeed = 100, introduced = 2001, operator = "CC", notes = "4-car"),
        3574 to ClassInfo("Class 357/4 Electrostar",     "EMU",     topSpeed = 100, introduced = 2010, operator = "CC", notes = "4-car"),
        // ── Class 360 ────────────────────────────────────────────────────────
        3601 to ClassInfo("Class 360/1 Desiro",          "EMU",     topSpeed = 100, introduced = 2002, operator = "HX", notes = "5-car"),
        3602 to ClassInfo("Class 360/2 Desiro",          "EMU",     topSpeed = 100, introduced = 2004, notes = "4-car"),
        3605 to ClassInfo("Class 360/5 Desiro",          "EMU",     topSpeed = 100, introduced = 2011, notes = "5-car"),
        // ── Class 375 ────────────────────────────────────────────────────────
        3753 to ClassInfo("Class 375/3 Electrostar",     "EMU",     topSpeed = 100, introduced = 1999, operator = "SE", notes = "3-car"),
        3756 to ClassInfo("Class 375/6 Electrostar",     "EMU",     topSpeed = 100, introduced = 2001, operator = "SE", notes = "3-car"),
        3757 to ClassInfo("Class 375/7 Electrostar",     "EMU",     topSpeed = 100, introduced = 2001, operator = "SE", notes = "4-car"),
        3758 to ClassInfo("Class 375/8 Electrostar",     "EMU",     topSpeed = 100, introduced = 2003, operator = "SE", notes = "4-car"),
        3759 to ClassInfo("Class 375/9 Electrostar",     "EMU",     topSpeed = 100, introduced = 2004, operator = "SE", notes = "4-car"),
        // ── Class 376 ────────────────────────────────────────────────────────
        3760 to ClassInfo("Class 376/0 Electrostar",     "EMU",     topSpeed = 75,  introduced = 2004, operator = "SE", notes = "5-car"),
        // ── Class 377 ────────────────────────────────────────────────────────
        3771 to ClassInfo("Class 377/1 Electrostar",     "EMU",     topSpeed = 100, introduced = 2002, operator = "SN", notes = "4-car"),
        3772 to ClassInfo("Class 377/2 Electrostar",     "EMU",     topSpeed = 100, introduced = 2003, operator = "SN", notes = "3-car"),
        3773 to ClassInfo("Class 377/3 Electrostar",     "EMU",     topSpeed = 100, introduced = 2003, operator = "SN", notes = "3-car"),
        3774 to ClassInfo("Class 377/4 Electrostar",     "EMU",     topSpeed = 100, introduced = 2004, operator = "SN", notes = "4-car"),
        3775 to ClassInfo("Class 377/5 Electrostar",     "EMU",     topSpeed = 100, introduced = 2012, operator = "SN", notes = "5-car"),
        3776 to ClassInfo("Class 377/6 Electrostar",     "EMU",     topSpeed = 100, introduced = 2014, operator = "SN", notes = "5-car"),
        // ── Class 378 ────────────────────────────────────────────────────────
        3781 to ClassInfo("Class 378/1 Capitalstar",     "EMU",     topSpeed = 75,  introduced = 2009, operator = "LO", notes = "5-car"),
        3782 to ClassInfo("Class 378/2 Capitalstar",     "EMU",     topSpeed = 75,  introduced = 2010, operator = "LO", notes = "5-car"),
        // ── Class 380 ────────────────────────────────────────────────────────
        3800 to ClassInfo("Class 380/0 Desiro",          "EMU",     topSpeed = 100, introduced = 2010, operator = "SR", notes = "3-car"),
        3801 to ClassInfo("Class 380/1 Desiro",          "EMU",     topSpeed = 100, introduced = 2010, operator = "SR", notes = "4-car"),
        // ── Class 385 ────────────────────────────────────────────────────────
        3850 to ClassInfo("Class 385/0 Aventra",         "EMU",     topSpeed = 100, introduced = 2017, operator = "SR", notes = "3-car"),
        3851 to ClassInfo("Class 385/1 Aventra",         "EMU",     topSpeed = 100, introduced = 2018, operator = "SR", notes = "4-car"),
        // ── Class 387 ────────────────────────────────────────────────────────
        3871 to ClassInfo("Class 387/1 Electrostar",     "EMU",     topSpeed = 110, introduced = 2015, notes = "4-car"),
        3872 to ClassInfo("Class 387/2 Electrostar",     "EMU",     topSpeed = 110, introduced = 2016, operator = "GX", notes = "4-car; Gatwick Express"),
        3873 to ClassInfo("Class 387/3 Electrostar",     "EMU",     topSpeed = 110, introduced = 2019, operator = "HX", notes = "4-car; Heathrow Express"),
        // ── Class 390 ────────────────────────────────────────────────────────
        3900 to ClassInfo("Class 390/0 Pendolino",       "EMU",     topSpeed = 125, introduced = 2001, operator = "VT", notes = "9-car"),
        3901 to ClassInfo("Class 390/1 Pendolino",       "EMU",     topSpeed = 125, introduced = 2012, operator = "VT", notes = "11-car"),
        // ── Class 444 ────────────────────────────────────────────────────────
        4440 to ClassInfo("Class 444/0 Desiro",          "EMU",     topSpeed = 100, introduced = 2003, operator = "SW", notes = "5-car"),
        // ── Class 450 ────────────────────────────────────────────────────────
        4500 to ClassInfo("Class 450/0 Desiro",          "EMU",     topSpeed = 100, introduced = 2002, operator = "SW", notes = "4-car"),
        4501 to ClassInfo("Class 450/1 Desiro",          "EMU",     topSpeed = 100, introduced = 2006, operator = "SW", notes = "4-car"),
        4504 to ClassInfo("Class 450/4 Desiro",          "EMU",     topSpeed = 100, introduced = 2006, operator = "SW", notes = "4-car"),
        4505 to ClassInfo("Class 450/5 Desiro",          "EMU",     topSpeed = 100, introduced = 2006, operator = "SW", notes = "4-car"),
        // ── Class 455 ────────────────────────────────────────────────────────
        4557 to ClassInfo("Class 455/7",                 "EMU",     topSpeed = 75,  introduced = 1984, operator = "SW", notes = "4-car"),
        4558 to ClassInfo("Class 455/8",                 "EMU",     topSpeed = 75,  introduced = 1982, operator = "SW", notes = "4-car"),
        4559 to ClassInfo("Class 455/9",                 "EMU",     topSpeed = 75,  introduced = 1985, operator = "SW", notes = "4-car"),
        // ── Class 458 ────────────────────────────────────────────────────────
        4580 to ClassInfo("Class 458/0 Juniper",         "EMU",     topSpeed = 75,  introduced = 1998, operator = "SW", notes = "4-car"),
        4585 to ClassInfo("Class 458/5 Juniper",         "EMU",     topSpeed = 75,  introduced = 2015, operator = "SW", notes = "5-car; rebuilt with extra vehicle"),
        // ── Class 465 ────────────────────────────────────────────────────────
        4650 to ClassInfo("Class 465/0 Networker",       "EMU",     topSpeed = 75,  introduced = 1991, operator = "SE", notes = "4-car"),
        4651 to ClassInfo("Class 465/1 Networker",       "EMU",     topSpeed = 75,  introduced = 1992, operator = "SE", notes = "4-car"),
        4652 to ClassInfo("Class 465/2 Networker",       "EMU",     topSpeed = 75,  introduced = 1993, operator = "SE", notes = "4-car"),
        4659 to ClassInfo("Class 465/9 Networker",       "EMU",     topSpeed = 75,  introduced = 1993, operator = "SE", notes = "4-car"),
        // ── Class 700 ────────────────────────────────────────────────────────
        7000 to ClassInfo("Class 700/0 Desiro City",     "EMU",     topSpeed = 100, introduced = 2016, operator = "TL", notes = "8-car"),
        7001 to ClassInfo("Class 700/1 Desiro City",     "EMU",     topSpeed = 100, introduced = 2016, operator = "TL", notes = "12-car"),
        // ── Class 701 ────────────────────────────────────────────────────────
        7010 to ClassInfo("Class 701/0 Arterio",         "EMU",     topSpeed = 100, introduced = 2020, operator = "SW", notes = "5-car"),
        7015 to ClassInfo("Class 701/5 Arterio",         "EMU",     topSpeed = 100, introduced = 2020, operator = "SW", notes = "10-car"),
        // ── Class 707 ────────────────────────────────────────────────────────
        7070 to ClassInfo("Class 707/0 Desiro City",     "EMU",     topSpeed = 100, introduced = 2017, operator = "SE", notes = "5-car"),
        // ── Class 710 ────────────────────────────────────────────────────────
        7101 to ClassInfo("Class 710/1 Aventra",         "EMU",     topSpeed = 75,  introduced = 2018, operator = "LO", notes = "4-car; AC overhead"),
        7102 to ClassInfo("Class 710/2 Aventra",         "EMU",     topSpeed = 75,  introduced = 2018, operator = "LO", notes = "4-car; DC third rail"),
        7103 to ClassInfo("Class 710/3 Aventra",         "EMU",     topSpeed = 75,  introduced = 2020, operator = "LO", notes = "5-car; AC overhead"),
        7104 to ClassInfo("Class 710/4 Aventra",         "EMU",     topSpeed = 75,  introduced = 2020, operator = "LO", notes = "5-car; DC third rail"),
        // ── Class 717 ────────────────────────────────────────────────────────
        7170 to ClassInfo("Class 717/0 Desiro City",     "EMU",     topSpeed = 100, introduced = 2018, operator = "GN", notes = "6-car"),
        // ── Class 720 ────────────────────────────────────────────────────────
        7201 to ClassInfo("Class 720/1 Aventra",         "EMU",     topSpeed = 100, introduced = 2020, operator = "LE", notes = "5-car"),
        7205 to ClassInfo("Class 720/5 Aventra",         "EMU",     topSpeed = 100, introduced = 2021, operator = "LE", notes = "10-car"),
        // ── Class 730 ────────────────────────────────────────────────────────
        7300 to ClassInfo("Class 730/0 Aventra",         "EMU",     topSpeed = 110, introduced = 2021, operator = "WMR", notes = "3-car"),
        7301 to ClassInfo("Class 730/1 Aventra",         "EMU",     topSpeed = 110, introduced = 2022, operator = "WMR", notes = "5-car"),
        // ── Class 745 ────────────────────────────────────────────────────────
        7450 to ClassInfo("Class 745/0 Flirt",           "EMU",     topSpeed = 100, introduced = 2019, operator = "LE", notes = "12-car; intercity"),
        7451 to ClassInfo("Class 745/1 Flirt",           "EMU",     topSpeed = 100, introduced = 2020, operator = "LE", notes = "5-car; Stansted Express"),
        // ── Class 755 ────────────────────────────────────────────────────────
        7553 to ClassInfo("Class 755/3 Flirt",           "Bi-mode", topSpeed = 100, introduced = 2019, operator = "LE", notes = "3-car"),
        7554 to ClassInfo("Class 755/4 Flirt",           "Bi-mode", topSpeed = 100, introduced = 2019, operator = "LE", notes = "4-car"),
        // ── Class 756 ────────────────────────────────────────────────────────
        7560 to ClassInfo("Class 756/0 Flirt",           "DEMU",    topSpeed = 75,  introduced = 2021, operator = "LE", notes = "3-car"),
        7561 to ClassInfo("Class 756/1 Flirt",           "DEMU",    topSpeed = 75,  introduced = 2021, operator = "LE", notes = "4-car"),
        // ── Class 769 ────────────────────────────────────────────────────────
        7694 to ClassInfo("Class 769/4 Flex",            "DEMU",    topSpeed = 100, introduced = 2019, operator = "NT", notes = "Northern only subclass in service"),
        // ── Class 777 ────────────────────────────────────────────────────────
        7770 to ClassInfo("Class 777/0 Nova 1",          "EMU",     topSpeed = 75,  introduced = 2022, operator = "ME"),
        7771 to ClassInfo("Class 777/1 Nova 1",          "EMU",     topSpeed = 75,  introduced = 2023, operator = "ME"),
        // ── Class 800 ────────────────────────────────────────────────────────
        8000 to ClassInfo("Class 800/0 IET",             "Bi-mode", topSpeed = 125, introduced = 2017, operator = "GW"),
        8001 to ClassInfo("Class 800/1 Azuma",           "Bi-mode", topSpeed = 125, introduced = 2019, operator = "GR"),
        8002 to ClassInfo("Class 800/2 Azuma",           "Bi-mode", topSpeed = 125, introduced = 2019, operator = "GR"),
        8003 to ClassInfo("Class 800/3 IET",             "Bi-mode", topSpeed = 125, introduced = 2018, operator = "GW"),
        // ── Class 801 ────────────────────────────────────────────────────────
        8010 to ClassInfo("Class 801/0 Azuma",           "EMU",     topSpeed = 125, introduced = 2019, operator = "GR"),
        8011 to ClassInfo("Class 801/1 Azuma",           "EMU",     topSpeed = 125, introduced = 2019, operator = "GR"),
        8012 to ClassInfo("Class 801/2 Paragon",         "EMU",     topSpeed = 125, introduced = 2019, operator = "HT"),
        8013 to ClassInfo("Class 801/3 Lumo",            "EMU",     topSpeed = 125, introduced = 2021, operator = "LD"),
        // ── Class 802 ────────────────────────────────────────────────────────
        8020 to ClassInfo("Class 802/0 IET",             "Bi-mode", topSpeed = 125, introduced = 2018, operator = "GW"),
        8021 to ClassInfo("Class 802/1 IET",             "Bi-mode", topSpeed = 125, introduced = 2018, operator = "GW"),
        8022 to ClassInfo("Class 802/2 Nova 1",          "Bi-mode", topSpeed = 125, introduced = 2019, operator = "TP"),
        8023 to ClassInfo("Class 802/3 Paragon",         "Bi-mode", topSpeed = 125, introduced = 2020, operator = "HT"),
        8024 to ClassInfo("Class 802/4 IET",             "Bi-mode", topSpeed = 125, introduced = 2022, operator = "GW"),
        // ── Class 803 ────────────────────────────────────────────────────────
        8030 to ClassInfo("Class 803/0 Lumo",            "EMU",     topSpeed = 125, introduced = 2021, operator = "LD"),
        // ── Class 805 ────────────────────────────────────────────────────────
        8050 to ClassInfo("Class 805/0 Evero",           "Bi-mode", topSpeed = 125, introduced = 2024, operator = "VT"),
        // ── Class 807 ────────────────────────────────────────────────────────
        8070 to ClassInfo("Class 807/0 Evero",           "EMU",     topSpeed = 125, introduced = 2024, operator = "VT"),
        // ── Class 810 ────────────────────────────────────────────────────────
        8100 to ClassInfo("Class 810/0 Aurora",          "Bi-mode", topSpeed = 125, introduced = 2026, operator = "EM"),
    )

}