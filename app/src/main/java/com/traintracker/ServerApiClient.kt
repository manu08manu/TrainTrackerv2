package com.traintracker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

    private val _movements   = MutableSharedFlow<TrustMovement>(extraBufferCapacity = 256)
    val movements: SharedFlow<TrustMovement> = _movements

    private val _activations = MutableSharedFlow<TrustActivation>(extraBufferCapacity = 64)
    val activations: SharedFlow<TrustActivation> = _activations

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

    suspend fun getLastKnownLocation(headcode: String): TrainLocationResult? =
        withContext(Dispatchers.IO) {
            try {
                val json = get("/api/trust/$headcode") ?: return@withContext null
                TrainLocationResult(
                    headcode     = json.optString("headcode"),
                    stationName  = json.optString("stationName"),
                    crs          = json.optString("crs").ifEmpty { null },
                    actualTime   = json.optString("actualTime"),
                    eventType    = json.optString("eventType"),
                    delayMinutes = json.optInt("delayMinutes", 0)
                )
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

    /**
     * Fetches consist/allocation for [headcode] on [date] (ISO yyyy-MM-dd).
     *
     * The endpoint only accepts a 4-char headcode. When multiple services share
     * the same headcode the server returns a JSON array. [uid] (the CIF UID,
     * e.g. "C41070") is used to find the correct entry by matching array elements
     * whose coreId starts with headcode+uid — the coreId format is headcode+uid+suffix
     * so startsWith is the correct comparison. Falls back to null if no match.
     *
     * Returns null if the server is unreachable, returns no data, or parsing fails.
     */
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
                            // No uid to disambiguate — only safe if there's exactly one result.
                            if (arr.length() > 1) {
                                Log.w(TAG, "getAllocation: ${arr.length()} results for $headcode but no uid to disambiguate — skipping")
                                return@withContext null
                            }
                            arr.getJSONObject(0)
                        } else {
                            // coreId format is headcode+uid+suffix (e.g. "1S16C4107012" for headcode="1S16", uid="C41070").
                            // Use startsWith rather than == because the suffix length varies.
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

    suspend fun getStatus(): ServerStatus? =
        withContext(Dispatchers.IO) {
            try {
                val json = get("/api/status") ?: return@withContext null
                ServerStatus(
                    cifLastDownload     = json.optString("cifLastDownload"),
                    trustConnected      = json.optBoolean("trustConnected", false),
                    trainLocationsCount = json.optInt("trainLocationsCount", 0)
                )
            } catch (e: Exception) { null }
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
                platform      = m.optString("platform").let { if (it == "null") "" else it },
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
            result.add(ServerService(
                uid           = s.optString("uid"),
                headcode      = s.optString("headcode"),
                atocCode      = s.optString("atocCode"),
                scheduledTime = s.optString("scheduledTime"),
                platform      = s.optString("platform").let { if (it == "null" || it.isEmpty()) null else it },
                isPass        = s.optBoolean("isPass", false),
                originCrs     = s.optString("originCrs").let { if (it == "null" || it.isEmpty()) null else it },
                destCrs       = s.optString("destCrs").let { if (it == "null" || it.isEmpty()) null else it },
                originTiploc  = s.optString("originTiploc"),
                destTiploc    = s.optString("destTiploc"),
                actualTime    = s.optString("actualTime").let { if (it == "null") "" else it },
                isCancelled   = s.optBoolean("isCancelled", false)
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
            val crs    = cp.optString("crs").let { if (it == "null" || it.isEmpty()) null else it }
            result.add(CallingPoint(
                locationName = crs?.let { StationData.findByCrs(it)?.name }
                    ?: CorpusData.nameFromTiploc(tiploc) ?: tiploc,
                crs          = crs ?: "",
                st           = cp.optString("scheduledTime"),
                et           = cp.optString("actualTime").let { if (it == "null" || it.isEmpty()) "" else it },
                at           = cp.optString("actualTime").let { if (it == "null" || it.isEmpty()) "" else it },
                isCancelled  = cp.optBoolean("isCancelled", false),
                length       = null,
                platform     = cp.optString("platform").let { if (it == "null" || it.isEmpty()) "" else it },
                isPassing    = cp.optBoolean("isPass", false)
            ))
        }
        return result
    }

    private fun get(path: String): JSONObject? {
        val response = http.newCall(Request.Builder().url("$baseUrl$path").build()).execute()
        if (!response.isSuccessful) return null
        return response.body?.string()?.let { JSONObject(it) }
    }

    /** Like [get] but returns the raw response body string, allowing callers to decide
     *  whether the root is a JSON object or array before parsing. */
    private fun getRaw(path: String): String? {
        val response = http.newCall(Request.Builder().url("$baseUrl$path").build()).execute()
        if (!response.isSuccessful) return null
        return response.body?.string()?.trim()
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
    val isCancelled: Boolean = false
)

data class CallingPointsResult(
    val uid: String,
    val previous: List<CallingPoint>,
    val subsequent: List<CallingPoint>
)

data class ServerStatus(
    val cifLastDownload: String?,
    val trustConnected: Boolean,
    val trainLocationsCount: Int
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