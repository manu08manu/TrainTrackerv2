package com.traintracker

/**
 * Maps TOPS unit/vehicle numbers to class names, traction type, and known operators.
 *
 * Extended in v3 with:
 *   - Freight locomotive classes (08, 37, 47, 57, 60, 66, 68, 70, 88, 90, 92)
 *   - Rail tour traction (47, 57, 67, 91 etc.)
 *   - Bi-mode classes (800–810, 802–807)
 *   - Newer classes (196, 197, 195, 769, 777, 810, 701, 711, 717, 720, 730, 745, 755, 756, 760, 800, 807, 810)
 *   - Multiple-unit detection for long formations
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
        755 to ClassInfo("Class 755 Flirt",            "DEMU", topSpeed = 100, introduced = 2019, operator = "LE"),
        756 to ClassInfo("Class 756 Flirt",            "DEMU", topSpeed = 100, introduced = 2021, operator = "LE"),
        769 to ClassInfo("Class 769 Flex",             "DEMU", topSpeed = 100, introduced = 2019),
        777 to ClassInfo("Class 777 Nova 1",           "EMU",  topSpeed = 75,  introduced = 2022, operator = "ME"),

        // ── Bi-modes ─────────────────────────────────────────────────────────
        800 to ClassInfo("Class 800 Azuma",            "Bi-mode", topSpeed = 125, introduced = 2017, operator = "GR"),
        801 to ClassInfo("Class 801 Azuma",            "EMU",     topSpeed = 125, introduced = 2018, operator = "GR"),
        802 to ClassInfo("Class 802 Nova 1",           "Bi-mode", topSpeed = 125, introduced = 2018),
        803 to ClassInfo("Class 803 Lumo",             "EMU",     topSpeed = 125, introduced = 2021, operator = "HT"),
        805 to ClassInfo("Class 805",                  "Bi-mode", topSpeed = 125, introduced = 2023, operator = "VT"),
        806 to ClassInfo("Class 806",                  "EMU",     topSpeed = 125, introduced = 2024, operator = "VT"),
        807 to ClassInfo("Class 807 Evero",            "Bi-mode", topSpeed = 125, introduced = 2023, operator = "XC"),
        810 to ClassInfo("Class 810 Aurora",           "Bi-mode", topSpeed = 125, introduced = 2023, operator = "EM"),

        // ── HST coaching stock ────────────────────────────────────────────────
        254 to ClassInfo("HST Mk3 trailer",            "HST",  notes = "InterCity 125 trailer"),
    )

}