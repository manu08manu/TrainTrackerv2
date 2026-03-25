package com.traintracker

import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * KnowledgebaseService — National Rail Knowledgebase Feeds (3 APIs)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Provider: Rail Data Marketplace (Rail Delivery Group)
 * Authentication: Either OAuth2 client_credentials (Consumer Key + Secret)
 *                 or simple x-apikey header, depending on the product.
 * Base URL: https://api1.raildata.org.uk
 *
 * If an API uses simple key-only auth, omit secret/tokenUrl and
 * the code uses x-apikey header automatically.
 */
class KnowledgebaseService {

    companion object {
        private const val TAG = "KnowledgebaseService"
        @Volatile private var stationCache: Map<String, KbStation> = emptyMap()
        private val stationLoadLock = Any()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // OAuth2 bearer token cache — ConcurrentHashMap for safe multi-coroutine access
    // cacheKey = "$key:$secret:$tokenUrl" → (bearerToken, expiryMs)
    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // ─── Incidents (XML) ──────────────────────────────────────────────────

    fun getIncidents(tocCode: String? = null): List<KbIncident> {
        return try {
            val xml = get(url = Constants.KB_INCIDENTS_URL, key = Constants.KB_INCIDENTS_KEY)
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
            val xml = get(url = Constants.KB_NSI_URL, key = Constants.KB_NSI_KEY)
            parseNsiXml(xml)
        } catch (e: Exception) {
            Log.w(TAG, "getNsi failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Stations (XML, per-TOC, cached) ──────────────────────────────────

    fun getStation(crs: String): KbStation? {
        ensureStationsLoaded()
        return stationCache[crs.uppercase()]
    }

    fun preloadStations() = ensureStationsLoaded()

    private fun ensureStationsLoaded() {
        if (stationCache.isNotEmpty()) return
        synchronized(stationLoadLock) {
            if (stationCache.isNotEmpty()) return
            val tocs = listOf(
                "AW","CC","CH","EM","ES","GN","GR","GW","GX",
                "HX","IL","LE","LM","LO","ME","NT","SE","SN","SR","SW","TL","TP","VT","XC","XR"
            )
            val map = HashMap<String, KbStation>(2600)
            for (toc in tocs) {
                try {
                    val url = Constants.KB_STATIONS_URL.replace("{TOC}", toc)
                    val xml = get(url = url, key = Constants.KB_STATIONS_KEY)
                    val parsed = parseStationsXml(xml)
                    map.putAll(parsed)
                    Log.d(TAG, "KB stations: loaded ${parsed.size} for $toc")
                } catch (e: Exception) {
                    Log.w(TAG, "KB stations: failed for $toc — ${e.message}")
                }
            }
            stationCache = map
            Log.d(TAG, "KB stations: total loaded ${map.size}")
        }
    }

    // ─── TOC Data (XML) ───────────────────────────────────────────────────

    fun getToc(): List<KbTocEntry> {
        if (Constants.KB_TOC_KEY.isEmpty()) {
            Log.d(TAG, "KB_TOC_KEY not set — TOC feed disabled")
            return emptyList()
        }
        return try {
            val xml = get(url = Constants.KB_TOC_URL, key = Constants.KB_TOC_KEY)
            parseTocXml(xml)
        } catch (e: Exception) {
            Log.w(TAG, "getToc failed: ${e.message}")
            emptyList()
        }
    }

    // ─── OAuth2 bearer token ──────────────────────────────────────────────

    private fun getBearerToken(key: String, secret: String, tokenUrl: String): String {
        val cacheKey = "$key:$secret:$tokenUrl"
        tokenCache[cacheKey]?.let { (token, expiry) ->
            if (System.currentTimeMillis() < expiry) return token
        }
        val requestBody = okhttp3.FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", key)
            .add("client_secret", secret)
            .build()
        val request = okhttp3.Request.Builder()
            .url(tokenUrl)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Token fetch failed: HTTP ${response.code} from $tokenUrl")
        }
        val body = response.body?.string() ?: throw Exception("Empty token response from $tokenUrl")
        val json = JSONObject(body)
        val token = json.getString("access_token")
        val expiresIn = json.optLong("expires_in", 3600L)
        val expiryMs = System.currentTimeMillis() + (expiresIn - 60) * 1000
        tokenCache[cacheKey] = Pair(token, expiryMs)
        return token
    }

    // ─── HTTP ─────────────────────────────────────────────────────────────

    private fun get(url: String, key: String, secret: String = "", tokenUrl: String = ""): String {
        val requestBuilder = okhttp3.Request.Builder().url(url)
        if (secret.isNotEmpty()) {
            val token = getBearerToken(key, secret, tokenUrl)
            requestBuilder.addHeader("Authorization", "Bearer $token")
        } else {
            requestBuilder.addHeader("x-apikey", key)
        }
        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} from $url")
        return response.body?.string() ?: throw Exception("Empty response from $url")
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
                            val isCleared = endTime.isNotEmpty() && try {
                                java.time.OffsetDateTime.parse(endTime)
                                    .isBefore(java.time.OffsetDateTime.now())
                            } catch (_: Exception) { false }
                            if (!isCleared) {
                                result.add(KbIncident(id, summary, description,
                                    isPlanned, startTime, endTime, operators.toList()))
                            }
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

            var tocCode = ""; var tocName = ""; var statusText = ""; var statusImage = ""
            var insideToc = false
            var currentTag = ""
            var statusDescription = ""; var customUrl = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        if (currentTag == "TOC") {
                            insideToc = true
                            tocCode = ""; tocName = ""; statusText = ""; statusImage = ""
                            statusDescription = ""; customUrl = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (!insideToc) { eventType = parser.next(); continue }
                        val text = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "TocCode"           -> tocCode = text
                            "TocName"           -> tocName = text
                            "Status"            -> statusText = text
                            "StatusImage"       -> statusImage = text
                            "StatusDescription" -> statusDescription = text
                            "CustomURL"         -> if (customUrl.isEmpty()) customUrl = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "TOC" && insideToc) {
                            val level = when {
                                statusText.equals("Good service", ignoreCase = true) -> "1"
                                statusImage.contains("tick", ignoreCase = true)      -> "1"
                                statusText.contains("minor", ignoreCase = true)      -> "2"
                                statusImage.contains("minor", ignoreCase = true)     -> "2"
                                statusText.contains("severe", ignoreCase = true)     -> "4"
                                statusImage.contains("severe", ignoreCase = true)    -> "4"
                                statusText.contains("major", ignoreCase = true) ||
                                        statusText.equals("Custom", ignoreCase = true) ||
                                        statusImage.contains("disruption", ignoreCase = true) -> "3"
                                else -> "1"
                            }
                            val code = tocCode.ifEmpty { TocData.codeFromName(tocName) }
                            if (code.isNotEmpty()) {
                                result.add(KbNsiEntry(
                                    tocCode           = code,
                                    tocName           = tocName,
                                    status            = level,
                                    statusDescription = statusDescription,
                                    customUrl         = customUrl
                                ))
                            }
                            insideToc = false
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

    // ─── XML parser: Stations ─────────────────────────────────────────────

    private fun parseStationsXml(xml: String): Map<String, KbStation> {
        val map = HashMap<String, KbStation>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inStation = false
            var crs = ""; var name = ""; var telephone = ""
            var staffingLevel = ""
            var cctv = ""
            var ticketOfficeAvail = ""
            var ticketMachineAvail = ""
            var waitingRoom = ""
            var toilets = ""
            var wifi = ""
            var stepFreeAccess = ""
            var assistanceAvail = ""
            var taxiAvail = ""
            var carParkSpaces = ""
            val addressLines = mutableListOf<String>()
            var postCode = ""

            val tagStack = ArrayDeque<String>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name ?: ""
                        tagStack.addLast(tag)
                        if (tag == "Station") {
                            inStation = true
                            crs = ""; name = ""; telephone = ""
                            staffingLevel = ""; cctv = ""
                            ticketOfficeAvail = ""; ticketMachineAvail = ""
                            waitingRoom = ""; toilets = ""; wifi = ""
                            stepFreeAccess = ""; assistanceAvail = ""
                            taxiAvail = ""; carParkSpaces = ""
                            addressLines.clear(); postCode = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (!inStation) { eventType = parser.next(); continue }
                        val text = parser.text?.trim() ?: ""
                        if (text.isEmpty()) { eventType = parser.next(); continue }
                        val tag = tagStack.lastOrNull() ?: ""
                        val parent = tagStack.dropLast(1).lastOrNull() ?: ""
                        val grandparent = tagStack.dropLast(2).lastOrNull() ?: ""
                        val great = tagStack.dropLast(3).lastOrNull() ?: ""
                        when {
                            tag == "CrsCode" && parent == "Station"        -> crs = text.uppercase()
                            tag == "Name" && parent == "Station"           -> name = text
                            tag == "Line"                                   -> addressLines.add(text)
                            tag == "PostCode"                               -> postCode = text
                            tag == "PrimaryTelephoneNumber" && great == "Station" ->
                                if (telephone.isEmpty()) telephone = text
                            tag == "StaffingLevel"                         -> staffingLevel = text
                            tag == "Available" && parent == "ClosedCircuitTelevision" ->
                                cctv = if (text == "true") "Yes" else ""
                            tag == "Available" && parent == "TicketOffice" ->
                                ticketOfficeAvail = if (text == "true") "Open" else "Closed"
                            tag == "Available" && parent == "TicketMachine" && grandparent == "Fares" ->
                                ticketMachineAvail = if (text == "true") "Available" else ""
                            tag == "Available" && parent == "WaitingRoom"  ->
                                waitingRoom = if (text == "true") "Yes" else ""
                            tag == "Available" && parent == "Toilets"      ->
                                toilets = if (text == "true") "Yes" else ""
                            tag == "Available" && parent == "WiFi"         ->
                                wifi = if (text == "true") "Yes" else ""
                            tag == "Coverage"                              -> stepFreeAccess = when (text) {
                                "wholeStation"   -> "Whole station"
                                "partialStation" -> "Partial"
                                "none"           -> ""
                                else             -> text
                            }
                            tag == "Available" && parent == "Accessibility" ->
                                assistanceAvail = if (text == "true") "Available" else ""
                            tag == "Available" && parent == "AccessibleTaxis" ->
                                taxiAvail = if (text == "true") "Available" else ""
                            tag == "NumberAccessibleSpaces"                ->
                                if (text.toIntOrNull() ?: 0 > 0) carParkSpaces = "$text accessible spaces"
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name ?: ""
                        if (tag == "Station" && inStation) {
                            if (crs.length == 3) {
                                val address = (addressLines + listOf(postCode))
                                    .filter { it.isNotEmpty() }.joinToString(", ")
                                map[crs] = KbStation(
                                    crs               = crs,
                                    name              = name,
                                    address           = address,
                                    telephone         = telephone,
                                    staffingNote      = staffingLevel,
                                    ticketOfficeHours = ticketOfficeAvail,
                                    sstmAvailability  = ticketMachineAvail,
                                    stepFreeAccess    = stepFreeAccess,
                                    assistanceAvail   = assistanceAvail,
                                    wifi              = wifi,
                                    toilets           = toilets,
                                    waitingRoom       = waitingRoom,
                                    cctv              = cctv,
                                    taxi              = taxiAvail,
                                    busInterchange    = "",
                                    carParking        = carParkSpaces
                                )
                            }
                            inStation = false
                        }
                        tagStack.removeLastOrNull()
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseStationsXml error: ${e.message}")
        }
        return map
    }

    // ─── XML parser: TOC ──────────────────────────────────────────────────

    private fun parseTocXml(xml: String): List<KbTocEntry> {
        val result = mutableListOf<KbTocEntry>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var code = ""; var name = ""; var website = ""
            var customerServicePhone = ""; var assistedTravelPhone = ""
            var assistedTravelUrl = ""; var lostPropertyUrl = ""
            var insideToc = false; var currentTag = ""
            var insideCustomerService = false; var insideAssistedTravel = false
            var insideLostProperty = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        when (currentTag) {
                            "Toc"             -> { insideToc = true; code = ""; name = ""; website = ""
                                customerServicePhone = ""; assistedTravelPhone = ""
                                assistedTravelUrl = ""; lostPropertyUrl = "" }
                            "CustomerService" -> insideCustomerService = true
                            "AssistedTravel"  -> insideAssistedTravel = true
                            "LostProperty"    -> insideLostProperty = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (!insideToc) { eventType = parser.next(); continue }
                        val text = parser.text?.trim() ?: ""
                        if (text.isEmpty()) { eventType = parser.next(); continue }
                        when {
                            currentTag == "AtocCode"                                        -> code = text
                            currentTag == "Name"                                            -> name = text
                            currentTag == "CompanyWebsite"                                  -> website = text
                            currentTag == "PrimaryTelephoneNumber" && insideCustomerService -> customerServicePhone = text
                            currentTag == "PrimaryTelephoneNumber" && insideAssistedTravel  -> assistedTravelPhone = text
                            currentTag == "Url" && insideAssistedTravel                     -> assistedTravelUrl = text
                            currentTag == "Url" && insideLostProperty                       -> lostPropertyUrl = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "CustomerService" -> insideCustomerService = false
                            "AssistedTravel"  -> insideAssistedTravel = false
                            "LostProperty"    -> insideLostProperty = false
                            "Toc" -> {
                                if (insideToc && code.isNotEmpty()) {
                                    result.add(KbTocEntry(
                                        code                 = code,
                                        name                 = name,
                                        website              = website,
                                        customerServicePhone = customerServicePhone,
                                        assistedTravelPhone  = assistedTravelPhone,
                                        assistedTravelUrl    = assistedTravelUrl,
                                        lostPropertyUrl      = lostPropertyUrl
                                    ))
                                }
                                insideToc = false
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseTocXml error: ${e.message}")
        }
        return result
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
    val tocCode: String,
    val tocName: String,
    val status: String,
    val statusDescription: String = "",
    val customUrl: String = ""
) {
    val statusLevel: Int get() = status.toIntOrNull() ?: 1
    val isGood:   Boolean get() = statusLevel == 1
    val isMinor:  Boolean get() = statusLevel == 2
    val isMajor:  Boolean get() = statusLevel == 3
    val isSevere: Boolean get() = statusLevel == 4
    val statusLabel: String get() = when (statusLevel) {
        1 -> "Good service"
        2 -> "Minor delays"
        3 -> "Major delays"
        4 -> "Severe disruption"
        else -> "Unknown"
    }
}

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

data class KbTocEntry(
    val code:                 String,
    val name:                 String,
    val website:              String = "",
    val customerServicePhone: String = "",
    val assistedTravelPhone:  String = "",
    val assistedTravelUrl:    String = "",
    val lostPropertyUrl:      String = ""
)