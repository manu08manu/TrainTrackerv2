package com.traintracker

fun formatHistoricDate(date: String): String {
    return try {
        val parts = date.split("-")
        val cal = java.util.Calendar.getInstance().apply {
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        }
        val dow   = arrayOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        val month = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")[parts[1].toInt() - 1]
        "$dow ${parts[2]} $month ${parts[0]}"
    } catch (_: Exception) { date }
}

