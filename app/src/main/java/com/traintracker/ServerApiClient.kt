package com.traintracker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.traintracker.safeString
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ServerApiClient {

    companion object {
        private const val TAG = "ServerApiClient"
        private const val TRUST_POLL_INTERVAL_MS = 30_000L
    }

    val isEnabled: Boolean
        get() = try { Constants.SERVER_BASE_URL.isNotBlank() } catch (_: Exception) { false }

    private val baseUrl: String
        get() = try { Constants.SERVER_BASE_URL.trimEnd('/') } catch (_: Exception) { "" }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 180s to cover worst-case uncached full-day HSP queries (4 chunks × ~40s each)
    private val sseHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // ── HSP progress event ─────────────────────────────────────────────────────
    data class HspProgressEvent(
        val progress: Int,
        val services: List<HspServiceMetrics>,
        val done: Boolean = false,
        val timedOut: Boolean = false   // true = caller should show retry message
    )

    /**
     * Fetches HSP metrics and emits a single [HspProgressEvent] with the full result.
     * Runs on Dispatchers.IO via flowOn — safe to collect on any coroutine.
     *
     * On timeout emits a sentinel event with [HspProgressEvent.timedOut] = true so the
     * UI can show "Search timed out — please try again" rather than a generic error.
     * Results are cached server-side so a retry will be fast.
     */
    fun streamHspMetrics(
        fromCrs:  String,
        toCrs:    String,
        fromDate: String,
        fromTime: String = "0000",
        toTime:   String = "2359"
    ): Flow<HspProgressEvent> = flow {
        val url = "$baseUrl/api/hsp/metrics/stream" +
                "?from=${fromCrs.uppercase()}&to=${toCrs.uppercase()}" +
                "&date=$fromDate&from_time=$fromTime&to_time=$toTime"

        val request = Request.Builder().url(url).build()
        try {
            val response = sseHttp.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "streamHspMetrics failed: HTTP ${response.code}")
                return@flow
            }
            val body = response.body.string()
            if (body.isEmpty()) {
                Log.w(TAG, "streamHspMetrics: empty body")
                response.close()
                return@flow
            }
            response.close()
            val json = JSONObject(body)
            val svcsArr = json.optJSONArray("services") ?: JSONArray()
            val services = (0 until svcsArr.length()).mapNotNull { i ->
                val s = svcsArr.optJSONObject(i) ?: return@mapNotNull null
                HspServiceMetrics(
                    rid             = s.optString("rid"),
                    originTiploc    = s.optString("originTiploc"),
                    destTiploc      = s.optString("destTiploc"),
                    scheduledDep    = s.optString("scheduledDep"),
                    scheduledArr    = s.optString("scheduledArr"),
                    tocCode         = s.optString("tocCode"),
                    matchedServices = s.optInt("matchedServices"),
                    onTime          = s.optInt("onTime"),
                    total           = s.optInt("total"),
                    punctualityPct  = s.optInt("punctualityPct", -1)
                )
            }
            emit(HspProgressEvent(100, services, done = true))
        } catch (e: SocketTimeoutException) {
            // First load of an uncached route can exceed even 180s on a slow HSP API day.
            // Emit a timeout sentinel — the UI should prompt the user to retry.
            // The server caches chunks as they complete, so a retry will be faster.
            Log.w(TAG, "streamHspMetrics timed out for $fromCrs→$toCrs $fromDate — emitting retry sentinel")
            emit(HspProgressEvent(0, emptyList(), done = false, timedOut = true))
        } catch (e: Exception) {
            Log.w(TAG, "streamHspMetrics error: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }.flowOn(Dispatchers.IO)

    private val _movements   = MutableSharedFlow<TrustMovement>(extraBufferCapacity = 256)
    val movements: SharedFlow<TrustMovement> = _movements

    private val _connected = MutableSharedFlow<Boolean>(replay = 1)
    val connected: SharedFlow<Boolean> = _connected

    suspend fun pollTrustForHeadcode(headcode: String) {
        if (!isEnabled || headcode.isEmpty()) return
        while (true) {
            try {
                fetchMovements(headcode).forEach { _movements.tryEmit(it) }
                _connected.tryEmit(true)
            } catch (e: Exception) {
                Log.w(TAG, "TRUST poll error: ${e.message}")
                _connected.tryEmit(false)
            }
            delay(TRUST_POLL_INTERVAL_MS)
        }
    }

    suspend fun resolveHeadcode(headcode: String): String =
        withContext(Dispatchers.IO) {
            try {
                get("/api/trust/resolve/$headcode")?.optString("resolved")?.ifEmpty { headcode } ?: headcode
            } catch (e: Exception) { headcode }
        }

    /**
     * Finds which station a headcode is currently at (or last seen at).
     * Returns a CRS code, or null if the headcode can't be located.
     */
    suspend fun findHeadcodeStation(headcode: String): String? =
        withContext(Dispatchers.IO) {
            try {
                get("/api/trust/locate/$headcode")?.safeString("crs")?.ifEmpty { null }
            } catch (e: Exception) { null }
        }

    suspend fun getDepartures(crs: String, windowMinutes: Int = 120, offset: Int = 0): List<ServerService> =
        withContext(Dispatchers.IO) {
            try {
                parseServices(get("/api/departures?crs=$crs&window=$windowMinutes&offset=$offset")?.optJSONArray("services"))
            } catch (e: Exception) {
                Log.w(TAG, "getDepartures error: ${e.message}"); emptyList()
            }
        }

    suspend fun getArrivals(crs: String, windowMinutes: Int = 120, offset: Int = 0): List<ServerService> =
        withContext(Dispatchers.IO) {
            try {
                parseServices(get("/api/arrivals?crs=$crs&window=$windowMinutes&offset=$offset")?.optJSONArray("services"))
            } catch (e: Exception) {
                Log.w(TAG, "getArrivals error: ${e.message}"); emptyList()
            }
        }

    suspend fun getAllServices(crs: String, windowMinutes: Int = 120, offset: Int = 0): List<ServerService> =
        withContext(Dispatchers.IO) {
            try {
                parseServices(get("/api/allservices?crs=$crs&window=$windowMinutes&offset=$offset")?.optJSONArray("services"))
            } catch (e: Exception) {
                Log.w(TAG, "getAllServices error: ${e.message}"); emptyList()
            }
        }


    /**
     * Fetches all services for a given unit number today.
     * Returns null if the server returns 404 (unit not found).
     */
    suspend fun getUnitBoard(unit: String): List<ServerService>? =
        withContext(Dispatchers.IO) {
            try {
                val response = http.newCall(
                    Request.Builder().url("$baseUrl/api/unit/${unit.uppercase().trim()}").build()
                ).execute()
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> { Log.w(TAG, "getUnitBoard HTTP ${response.code}"); emptyList() }
                    else -> parseServices(JSONObject(response.body.string()).optJSONArray("services"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "getUnitBoard error: ${e.message}"); emptyList()
            }
        }

    suspend fun getHeadcodeBoard(headcode: String): List<ServerService>? =
        withContext(Dispatchers.IO) {
            try {
                val response = http.newCall(
                    Request.Builder().url("$baseUrl/api/headcode/${headcode.uppercase().trim()}").build()
                ).execute()
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> { Log.w(TAG, "getHeadcodeBoard HTTP ${response.code}"); emptyList() }
                    else -> parseServices(JSONObject(response.body.string()).optJSONArray("services"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "getHeadcodeBoard error: ${e.message}"); emptyList()
            }
        }

    suspend fun getCallingPoints(uid: String, atCrs: String): CallingPointsResult? =
        withContext(Dispatchers.IO) {
            try {
                val json = get("/api/service/$uid?crs=$atCrs") ?: return@withContext null
                CallingPointsResult(
                    uid        = json.optString("uid"),
                    previous   = parseCallingPoints(json.optJSONArray("previous")),
                    subsequent = parseCallingPoints(json.optJSONArray("subsequent"))
                )
            } catch (e: Exception) { null }
        }

    suspend fun getMovementsForHeadcode(headcode: String): List<TrustMovement> =
        withContext(Dispatchers.IO) {
            try {
                fetchMovements(headcode)
            } catch (e: Exception) {
                Log.w(TAG, "getMovements error: \${e.message}"); emptyList()
            }
        }

    suspend fun getAllocation(headcode: String, date: String, uid: String = ""): AllocationInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = "/api/allocation/$headcode?date=$date"
                Log.d(TAG, "getAllocation: fetching $baseUrl$url (uid=$uid)")

                val raw = getRaw(url)
                if (raw == null) {
                    Log.w(TAG, "getAllocation: null/unsuccessful response for $headcode")
                    return@withContext null
                }
                Log.d(TAG, "getAllocation: raw response = $raw")

                val json: JSONObject = when {
                    raw.trimStart().startsWith("[") -> {
                        val arr = JSONArray(raw)
                        Log.d(TAG, "getAllocation: array response, length=${arr.length()}")
                        if (arr.length() == 0) {
                            Log.w(TAG, "getAllocation: empty array for $headcode")
                            return@withContext null
                        }
                        if (uid.isEmpty()) {
                            if (arr.length() > 1) {
                                Log.w(TAG, "getAllocation: ${arr.length()} results for $headcode but no uid to disambiguate — skipping")
                                return@withContext null
                            }
                            arr.getJSONObject(0)
                        } else {
                            val expectedPrefix = "$headcode$uid"
                            var matched: JSONObject? = null
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                if (obj.optString("coreId").startsWith(expectedPrefix)) {
                                    matched = obj
                                    Log.d(TAG, "getAllocation: matched coreId=${obj.optString("coreId")} via prefix=$expectedPrefix")
                                    break
                                }
                            }
                            if (matched == null) {
                                Log.w(TAG, "getAllocation: no coreId match for prefix=$expectedPrefix in ${arr.length()} results — skipping")
                                return@withContext null
                            }
                            matched
                        }
                    }
                    else -> JSONObject(raw)
                }

                val info = AllocationInfo(
                    coreId      = json.optString("coreId"),
                    headcode    = json.optString("headcode"),
                    serviceDate = json.optString("serviceDate"),
                    operator    = json.optString("operator"),
                    units       = json.optJSONArray("units")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    vehicles    = json.optJSONArray("vehicles")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    unitCount   = json.optInt("unitCount", 0),
                    coachCount  = json.optInt("coachCount", 0)
                )
                Log.d(TAG, "getAllocation: parsed OK — units=${info.units}, coachCount=${info.coachCount}")
                info
            } catch (e: Exception) {
                Log.e(TAG, "getAllocation($headcode): ${e.message}", e)
                null
            }
        }

    suspend fun getHspMetrics(
        fromCrs:  String,
        toCrs:    String,
        fromDate: String,
        toDate:   String  = fromDate,
        fromTime: String  = "0000",
        toTime:   String  = "2359"
    ): HspMetricsResult? = withContext(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject().apply {
                put("from_loc",  fromCrs.uppercase())
                put("to_loc",    toCrs.uppercase())
                put("from_date", fromDate)
                put("to_date",   toDate)
                put("from_time", fromTime)
                put("to_time",   toTime)
            }
            val raw = postRaw("/api/hsp/metrics", body.toString()) ?: return@withContext null
            val json = org.json.JSONObject(raw)
            val svcs = json.optJSONArray("services") ?: return@withContext null
            val services = (0 until svcs.length()).mapNotNull { i ->
                val s = svcs.optJSONObject(i) ?: return@mapNotNull null
                HspServiceMetrics(
                    rid            = s.optString("rid"),
                    originTiploc   = s.optString("originTiploc"),
                    destTiploc     = s.optString("destTiploc"),
                    scheduledDep   = s.optString("scheduledDep"),
                    scheduledArr   = s.optString("scheduledArr"),
                    tocCode        = s.optString("tocCode"),
                    matchedServices = s.optInt("matchedServices"),
                    onTime         = s.optInt("onTime"),
                    total          = s.optInt("total"),
                    punctualityPct = s.optInt("punctualityPct", -1)
                )
            }
            HspMetricsResult(services)
        } catch (e: Exception) {
            Log.w(TAG, "getHspMetrics error: ${e.message}")
            null
        }
    }

    suspend fun getHspDetails(rid: String, scheduledDep: String = ""): HspDetailsResult? = withContext(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject().apply {
                put("rid", rid)
                if (scheduledDep.isNotEmpty()) put("scheduled_dep", scheduledDep)
            }
            val raw  = postRaw("/api/hsp/details", body.toString()) ?: return@withContext null
            val json = org.json.JSONObject(raw)
            val locs = json.optJSONArray("locations") ?: return@withContext null
            HspDetailsResult(
                rid       = json.optString("rid"),
                date      = json.optString("date"),
                tocCode   = json.optString("tocCode"),
                unit      = json.optString("unit"),
                units     = json.optJSONArray("units")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                vehicles  = json.optJSONArray("vehicles")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                unitCount = json.optInt("unitCount", 0),
                locations = (0 until locs.length()).mapNotNull { i ->
                    val l = locs.optJSONObject(i) ?: return@mapNotNull null
                    HspLocationResult(
                        tiploc       = l.optString("tiploc"),
                        scheduledDep = l.optString("scheduledDep"),
                        scheduledArr = l.optString("scheduledArr"),
                        actualDep    = l.optString("actualDep"),
                        actualArr    = l.optString("actualArr"),
                        cancelReason = l.optString("cancelReason")
                    )
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "getHspDetails error: ${e.message}")
            null
        }
    }

    private fun fetchMovements(headcode: String): List<TrustMovement> {
        val array = get("/api/trust/movements?headcode=$headcode")?.optJSONArray("movements") ?: return emptyList()
        val result = mutableListOf<TrustMovement>()
        for (i in 0 until array.length()) {
            val m = array.getJSONObject(i)
            result.add(TrustMovement(
                type          = m.optString("eventType", "DEPARTURE"),
                trainUid      = "", trainId = headcode, headcode = headcode, stanox = "",
                crs           = m.optString("crs"),
                scheduledTime = m.optString("scheduledTime"),
                actualTime    = m.optString("actualTime"),
                platform      = m.safeString("platform"),
                isCancelled   = m.optBoolean("isCancelled", false)
            ))
        }
        return result
    }

    private fun parseServices(array: JSONArray?): List<ServerService> {
        array ?: return emptyList()
        val result = mutableListOf<ServerService>()
        for (i in 0 until array.length()) {
            val s = array.getJSONObject(i)
            val units = s.optJSONArray("units")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val vehicles = s.optJSONArray("vehicles")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            result.add(ServerService(
                uid            = s.optString("uid"),
                headcode       = s.optString("headcode"),
                atocCode       = s.optString("atocCode"),
                scheduledTime  = s.optString("scheduledTime"),
                platform       = s.safeString("platform").ifEmpty { null },
                isPass         = s.optBoolean("isPass", false),
                originCrs      = s.safeString("originCrs").ifEmpty { null },
                destCrs        = s.safeString("destCrs").ifEmpty { null },
                originTiploc   = s.optString("originTiploc"),
                destTiploc     = s.optString("destTiploc"),
                actualTime     = s.safeString("actualTime"),
                isCancelled    = s.optBoolean("isCancelled", false),
                cancelReason   = s.safeString("cancelReason"),
                hasAlert       = s.optBoolean("isCancelled", false),
                units          = units,
                vehicles       = vehicles,
                unitJoinTiploc = s.safeString("unitJoinTiploc").ifEmpty { null }
            ))
        }
        return result
    }

    private fun parseCallingPoints(array: JSONArray?): List<CallingPoint> {
        array ?: return emptyList()
        val result = mutableListOf<CallingPoint>()
        for (i in 0 until array.length()) {
            val cp     = array.getJSONObject(i)
            val tiploc = cp.optString("tiploc")
            val crs    = cp.safeString("crs").ifEmpty { null }
            result.add(CallingPoint(
                locationName = crs?.let { StationData.findByCrs(it)?.name }
                    ?: CorpusData.nameFromTiploc(tiploc) ?: tiploc,
                crs          = crs ?: "",
                st           = cp.optString("scheduledTime"),
                et           = cp.safeString("actualTime"),
                at           = cp.safeString("actualTime"),
                isCancelled  = cp.optBoolean("isCancelled", false),
                length       = null,
                platform     = cp.safeString("platform"),
                isPassing    = cp.optBoolean("isPass", false)
            ))
        }
        return result
    }

    private fun get(path: String): JSONObject? {
        val response = http.newCall(Request.Builder().url("$baseUrl$path").build()).execute()
        if (!response.isSuccessful) return null
        return JSONObject(response.body.string())
    }

    private fun getRaw(path: String): String? {
        val response = http.newCall(Request.Builder().url("$baseUrl$path").build()).execute()
        if (!response.isSuccessful) return null
        return response.body.string().trim()
    }

    private fun postRaw(path: String, bodyJson: String): String? {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(bodyJson.toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "POST $path → HTTP ${response.code}")
            return null
        }
        return response.body.string().trim()
    }
}

data class TrainLocationResult(
    val headcode: String, val stationName: String, val crs: String?,
    val actualTime: String, val eventType: String, val delayMinutes: Int
)

data class ServerService(
    val uid: String, val headcode: String, val atocCode: String,
    val scheduledTime: String, val platform: String?, val isPass: Boolean,
    val originCrs: String?, val destCrs: String?,
    val originTiploc: String, val destTiploc: String,
    val actualTime: String = "",
    val isCancelled: Boolean = false,
    val cancelReason: String = "",
    val hasAlert: Boolean = false,
    val units: List<String> = emptyList(),
    val vehicles: List<String> = emptyList(),
    val unitJoinTiploc: String? = null
)

data class CallingPointsResult(
    val uid: String,
    val previous: List<CallingPoint>,
    val subsequent: List<CallingPoint>
)

data class AllocationInfo(
    val coreId: String,
    val headcode: String,
    val serviceDate: String,
    val operator: String,
    val units: List<String>,
    val vehicles: List<String>,
    val unitCount: Int,
    val coachCount: Int
)

// ─── HSP result models ────────────────────────────────────────────────────────

data class HspServiceMetrics(
    val rid:             String,
    val originTiploc:    String,
    val destTiploc:      String,
    val scheduledDep:    String,
    val scheduledArr:    String,
    val tocCode:         String,
    val matchedServices: Int,
    val onTime:          Int,
    val total:           Int,
    val punctualityPct:  Int   // -1 = no data; 0-100 = % on time
)

data class HspMetricsResult(
    val services: List<HspServiceMetrics>
)

data class HspLocationResult(
    val tiploc:       String,
    val scheduledDep: String,
    val scheduledArr: String,
    val actualDep:    String,
    val actualArr:    String,
    val cancelReason: String
)

data class HspDetailsResult(
    val rid:       String,
    val date:      String,
    val tocCode:   String,
    val unit:      String = "",
    val units:     List<String> = emptyList(),
    val vehicles:  List<String> = emptyList(),
    val unitCount: Int = 0,
    val locations: List<HspLocationResult>
)