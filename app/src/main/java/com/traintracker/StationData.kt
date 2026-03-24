package com.traintracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class Station(
    val crs: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    internal val nameLower: String,
    internal val nameNoLondon: String,
    internal val initials: String,
    internal val nameWords: List<String>,
    // TIPLOC code used by Darwin Push Port — null if not present in station JSON asset
    val tiploc: String? = null
)

object StationData {

    @Volatile private var _all: List<Station> = emptyList()
    private var _byCrs: HashMap<String, Station> = HashMap()

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (_all.isNotEmpty()) return@withContext
        val json = context.assets.open("uk_stations.json").bufferedReader().readText()
        val arr = JSONArray(json)
        val list = ArrayList<Station>(arr.length())
        val map  = HashMap<String, Station>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val obj  = arr.getJSONObject(i)
            val name = obj.getString("stationName")
            val nl   = name.lowercase()
            val words = nl.split(' ', '-').filter { it.isNotEmpty() }
            val s = Station(
                crs          = obj.getString("crsCode"),
                name         = name,
                lat          = obj.getDouble("lat"),
                lon          = obj.getDouble("long"),
                nameLower    = nl,
                nameNoLondon = if (nl.startsWith("london ")) nl.removePrefix("london ") else nl,
                initials     = words.joinToString("") { it.take(1) },
                nameWords    = words,
                tiploc       = if (obj.has("tiplocCode")) obj.getString("tiplocCode") else null
            )
            list.add(s)
            map[s.crs.uppercase()] = s
        }
        _all  = list
        _byCrs = map
    }


    fun search(query: String): List<Station> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return _all.filter { s ->
            s.crs.lowercase().startsWith(q) ||
            s.nameLower.startsWith(q) ||
            s.nameNoLondon.startsWith(q) ||
            (q.length >= 2 && s.nameWords.any { it.startsWith(q) }) ||
            s.initials.startsWith(q)
        }.sortedWith(compareBy(
            { if (it.crs.lowercase() == q) 0 else 1 },
            { if (it.crs.lowercase().startsWith(q)) 0 else 1 },
            { if (it.nameLower.startsWith(q) || it.nameNoLondon.startsWith(q)) 0 else 1 },
            { if (it.nameWords.firstOrNull()?.startsWith(q) == true) 0 else 1 },
            { it.name }
        )).take(10)
    }

    fun findByCrs(crs: String): Station? = _byCrs[crs.uppercase()]

    fun findNearest(lat: Double, lon: Double): Station? =
        _all.minByOrNull { s ->
            val dLat = s.lat - lat
            val dLon = s.lon - lon
            dLat * dLat + dLon * dLon
        }
}
