package com.traintracker

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Client for the National Rail Knowledgebase feeds on api1.raildata.org.uk.
 *
 * Three separate subscriptions, each with their own Consumer key (x-apikey header):
 *   • NSI       — XML feed — KB_NSI_KEY / KB_NSI_URL
 *   • Incidents — XML feed — KB_INCIDENTS_KEY / KB_INCIDENTS_URL
 *   • Stations  — JSON     — KB_STATIONS_KEY / KB_STATIONS_URL (full list, cached)
 *
 * Set the three Consumer keys in Constants.kt.
 */
class KnowledgebaseService {

    companion object {
        private const val TAG = "KnowledgebaseService"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Station data is the full list in one call — cache it for the session
    @Volatile private var stationCache: Map<String, KbStation> = emptyMap()

    // ─── Incidents (XML) ──────────────────────────────────────────────────

    fun getIncidents(tocCode: String? = null): List<KbIncident> {
        return try {
            val xml = get(Constants.KB_INCIDENTS_URL, Constants.KB_INCIDENTS_KEY)
            val all = parseIncidentsXml(xml)
            if (tocCode.isNullOrBlank()) all
            else all.filter { it.operators.contains(tocCode.uppercase()) }
        } catch (e: Exception) {
            Log.w(TAG, "getIncidents failed: ${e.message}")
            emptyList()
        }
    }

    // ─── NSI (XML) ────────────────────────────────────────────────────────

    fun getNsi(): List<KbNsiEntry> {
        return try {
            val xml = get(Constants.KB_NSI_URL, Constants.KB_NSI_KEY)
            parseNsiXml(xml)
        } catch (e: Exception) {
            Log.w(TAG, "getNsi failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Stations (JSON) — prefers Data API, falls back to KB bulk load ──

    fun getStation(crs: String): KbStation? {
        // Strategy 1: query the TrainTracker Data API per station
        val apiBase = Constants.DATA_API_BASE_URL
        if (apiBase.isNotEmpty()) {
            try {
                val json = getUrl("${apiBase.trimEnd('/')}/api/v1/stations/${crs.uppercase()}")
                return parseApiStation(json)
            } catch (e: Exception) {
                Log.w(TAG, "Data API station lookup failed for $crs, falling back: ${e.message}")
            }
        }
        // Strategy 2: legacy bulk load from KB
        ensureStationsLoaded()
        return stationCache[crs.uppercase()]
    }

    fun preloadStations() {
        // With the Data API, we fetch per-station on demand, so preloading is a no-op.
        if (Constants.DATA_API_BASE_URL.isNotEmpty()) return
        ensureStationsLoaded()
    }

    private fun ensureStationsLoaded() {
        if (stationCache.isNotEmpty()) return
        try {
            val json = get(Constants.KB_STATIONS_URL, Constants.KB_STATIONS_KEY)
            stationCache = parseStationsJson(json)
            Log.d(TAG, "Loaded ${stationCache.size} stations from KB")
        } catch (e: Exception) {
            Log.w(TAG, "Stations load failed: ${e.message}")
        }
    }

    // ─── HTTP ─────────────────────────────────────────────────────────────

    private fun get(url: String, apiKey: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("x-apikey", apiKey)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} from $url")
        return response.body?.string() ?: throw Exception("Empty response from $url")
    }

    /** Simple GET without auth headers — used for the TrainTracker Data API. */
    private fun getUrl(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} from $url")
        return response.body?.string() ?: throw Exception("Empty response from $url")
    }

    // ─── Data API JSON parser ─────────────────────────────────────────────

    /** Parse a single station JSON object returned by the TrainTracker Data API. */
    private fun parseApiStation(json: String): KbStation? {
        val s = JSONObject(json)
        val crs = s.optString("crs", "")
        if (crs.length != 3) return null
        return KbStation(
            crs               = crs,
            name              = s.optString("name", ""),
            address           = s.optString("address", ""),
            telephone         = s.optString("telephone", ""),
            staffingNote      = s.optString("staffing_note", ""),
            ticketOfficeHours = s.optString("ticket_office_hours", ""),
            sstmAvailability  = s.optString("sstm_availability", ""),
            stepFreeAccess    = s.optString("step_free_access", ""),
            assistanceAvail   = s.optString("assistance_avail", ""),
            wifi              = s.optString("wifi", ""),
            toilets           = s.optString("toilets", ""),
            waitingRoom       = s.optString("waiting_room", ""),
            cctv              = s.optString("cctv", ""),
            taxi              = s.optString("taxi", ""),
            busInterchange    = s.optString("bus_interchange", ""),
            carParking        = s.optString("car_parking", "")
        )
    }

    // ─── XML parser: Incidents ────────────────────────────────────────────

    private fun parseIncidentsXml(xml: String): List<KbIncident> {
        val result = mutableListOf<KbIncident>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var id = ""; var summary = ""; var description = ""
            var isPlanned = false; var startTime = ""; var endTime = ""
            val operators = mutableListOf<String>()
            var insideIncident = false
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        if (currentTag == "Incident") {
                            insideIncident = true
                            id = ""; summary = ""; description = ""
                            isPlanned = false; startTime = ""; endTime = ""
                            operators.clear()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (!insideIncident) { eventType = parser.next(); continue }
                        val text = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "IncidentNumber" -> id = text
                            "Summary"        -> summary = text
                            "Description"    -> description = android.text.Html.fromHtml(
                                text, android.text.Html.FROM_HTML_MODE_COMPACT
                            ).toString().trim()
                            "IsPlanned"      -> isPlanned = text.equals("true", ignoreCase = true)
                            "StartTime"      -> startTime = text
                            "ClearanceTime"  -> endTime = text
                            "Code"           -> if (text.length in 2..4) operators.add(text.uppercase())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "Incident" && insideIncident) {
                            result.add(KbIncident(id, summary, description,
                                isPlanned, startTime, endTime, operators.toList()))
                            insideIncident = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseIncidentsXml error: ${e.message}")
        }
        return result
    }

    // ─── XML parser: NSI ─────────────────────────────────────────────────

    private fun parseNsiXml(xml: String): List<KbNsiEntry> {
        val result = mutableListOf<KbNsiEntry>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var tocName = ""; var status = ""; var statusImage = "1"
            var insideIndicator = false
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        if (currentTag == "ServiceIndicator") {
                            insideIndicator = true
                            tocName = ""; status = ""; statusImage = "1"
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (!insideIndicator) { eventType = parser.next(); continue }
                        val text = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "TOCName"     -> tocName = text
                            "Status"      -> status = text
                            "StatusImage" -> statusImage = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "ServiceIndicator" && insideIndicator) {
                            result.add(KbNsiEntry(
                                description = if (tocName.isNotEmpty()) tocName else status,
                                status      = statusImage
                            ))
                            insideIndicator = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseNsiXml error: ${e.message}")
        }
        return result
    }

    // ─── JSON parser: Stations ────────────────────────────────────────────

    private fun parseStationsJson(json: String): Map<String, KbStation> {
        val map = HashMap<String, KbStation>(2600)
        try {
            val array: JSONArray = when {
                json.trimStart().startsWith('[') -> JSONArray(json)
                else -> {
                    val root = JSONObject(json)
                    root.optJSONArray("stations")
                        ?: root.optJSONArray("StationList")
                        ?: root.optJSONArray("Station")
                        ?: return map
                }
            }
            for (i in 0 until array.length()) {
                val station = parseStationObject(array.getJSONObject(i)) ?: continue
                if (station.crs.length == 3) map[station.crs] = station
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseStationsJson error: ${e.message}")
        }
        return map
    }

    private fun parseStationObject(s: JSONObject): KbStation? {
        fun str(vararg keys: String): String {
            for (k in keys) { val v = s.optString(k, "").trim(); if (v.isNotEmpty()) return v }
            return ""
        }
        fun obj(vararg keys: String): JSONObject? {
            for (k in keys) { val v = s.optJSONObject(k); if (v != null) return v }
            return null
        }
        fun avail(vararg keys: String): String {
            val o = obj(*keys) ?: return ""
            return o.optString("Availability", o.optString("availability", "")).trim()
        }

        val crs = str("CrsCode", "crsCode", "crs")
        if (crs.length != 3) return null

        val toHours = buildString {
            val to = obj("TicketOfficeAvailability", "ticketOfficeAvailability")
            if (to != null) {
                listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday").forEach { day ->
                    val h = to.optString(day, to.optString(day.lowercase(), "")).trim()
                    if (h.isNotEmpty()) append("$day: $h\n")
                }
            }
        }.trim()

        val parking = buildString {
            val cp = obj("CarPark", "carPark")
            if (cp != null) {
                val a = cp.optString("Availability", "").trim()
                if (a.isNotEmpty()) append("$a\n")
                val n = cp.optString("Notes", "").trim()
                if (n.isNotEmpty()) append(n)
            }
        }.trim()

        return KbStation(
            crs               = crs,
            name              = str("Name", "name"),
            address           = listOf(
                str("Address1","address1"), str("Address2","address2"),
                str("Town","town"), str("County","county"), str("Postcode","postcode")
            ).filter { it.isNotEmpty() }.joinToString(", "),
            telephone         = str("Telephone","telephone"),
            staffingNote      = str("Staffing","staffing").ifEmpty {
                obj("Staffing","staffing")?.optString("Note","") ?: ""
            },
            ticketOfficeHours = toHours,
            sstmAvailability  = avail("SelfServiceTicketMachines","selfServiceTicketMachines"),
            stepFreeAccess    = avail("StepFreeAccess","stepFreeAccess"),
            assistanceAvail   = avail("AssistanceAvailability","assistanceAvailability"),
            wifi              = avail("WiFi","wifi"),
            toilets           = avail("Toilets","toilets"),
            waitingRoom       = avail("WaitingRoom","waitingRoom"),
            cctv              = avail("CCTV","cctv"),
            taxi              = avail("Taxi","taxi"),
            busInterchange    = avail("BusInterchange","busInterchange"),
            carParking        = parking
        )
    }
}

// ─── Data models ──────────────────────────────────────────────────────────────

data class KbIncident(
    val id: String,
    val summary: String,
    val description: String,
    val isPlanned: Boolean,
    val startTime: String,
    val endTime: String,
    val operators: List<String>
)

data class KbNsiEntry(
    val description: String,
    val status: String    // "1"=good service, "2"=minor, "3"=major, "4"=severe
)

data class KbStation(
    val crs: String,
    val name: String,
    val address: String,
    val telephone: String,
    val staffingNote: String,
    val ticketOfficeHours: String,
    val sstmAvailability: String,
    val stepFreeAccess: String,
    val assistanceAvail: String,
    val wifi: String,
    val toilets: String,
    val waitingRoom: String,
    val cctv: String,
    val taxi: String,
    val busInterchange: String,
    val carParking: String
)
