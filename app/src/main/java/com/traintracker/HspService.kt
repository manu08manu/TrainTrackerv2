package com.traintracker

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * HspService — Historical Service Performance
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * API: Historical Service Performance (HSP)
 * Provider: Network Rail (Rockshore)
 * Endpoint: https://hsp-prod.rockshore.net/api/v1
 * Auth: Basic Auth (HSP_KEY:HSP_SECRET → Base64)
 * Method: ALL calls are POST with JSON body
 *
 * Two endpoints:
 *   /serviceMetrics  — board-level query: services between two locations on a date
 *   /serviceDetails  — per-service detail: calling points with actual times
 *
 * Times in HSP are HHmm (4 digits, no colon). This class converts to HH:mm internally.
 */
class HspService {

    companion object {
        private const val TAG = "HspService"
        private const val BASE_URL = "https://api1.raildata.org.uk/1010-historical-service-performance-_hsp_v1/api/v1"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Convert HHmm → HH:mm. Returns "" if blank/null. */
        fun hhmm(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "0000" && raw.length != 4) return ""
            val s = raw.trim().padStart(4, '0')
            return if (s.length >= 4) "${s.substring(0, 2)}:${s.substring(2, 4)}" else ""
        }
    }

    // ── Data models ──────────────────────────────────────────────────────────

    /** One service returned by /serviceMetrics for a date+station query. */
    data class HspService(
        val rid: String,              // Darwin RID
        val originTiploc: String,     // origin_location
        val destTiploc: String,       // destination_location
        val scheduledDep: String,     // gbtt_ptd (HH:mm)
        val scheduledArr: String,     // gbtt_pta (HH:mm)
        val tocCode: String           // toc_code
    )

    /** Full calling-point detail for one RID from /serviceDetails. */
    data class HspServiceDetail(
        val rid: String,
        val date: String,             // date_of_service (YYYY-MM-DD)
        val tocCode: String,
        val locations: List<HspLocation>
    )

    data class HspLocation(
        val tiploc: String,           // location field
        val scheduledDep: String,     // gbtt_ptd (HH:mm)
        val scheduledArr: String,     // gbtt_pta (HH:mm)
        val actualDep: String,        // actual_td (HH:mm)
        val actualArr: String,        // actual_ta (HH:mm)
        val cancelReason: String      // late_canc_reason
    )

    // ── HTTP client ───────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKey: String? by lazy {
        val k = Constants.HSP_KEY
        if (k.isNotEmpty()) k else null
    }

    val isAvailable: Boolean get() = apiKey != null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch all services calling at [stationCrs] on [date] (YYYY-MM-DD)
     * within the time window [fromTime]..[toTime] (HHmm, e.g. "0600".."2359").
     *
     * Uses from_loc = to_loc = stationCrs so all services calling there are returned.
     * Returns a list of [HspService] objects, one per RID (one per matched day).
     */
    fun getStationBoard(
        stationCrs: String,
        date: String,               // YYYY-MM-DD
        fromTime: String = "0000",  // HHmm
        toTime: String = "2359",    // HHmm
        days: String = "WEEKDAY"    // WEEKDAY | SATURDAY | SUNDAY
    ): List<HspService> {
        val creds = apiKey ?: return emptyList()
        return try {
            val body = JSONObject().apply {
                put("from_loc", stationCrs.uppercase())
                put("to_loc", stationCrs.uppercase())
                put("from_time", fromTime)
                put("to_time", toTime)
                put("from_date", date)
                put("to_date", date)
                put("days", days)
            }
            val response = post("$BASE_URL/serviceMetrics", body.toString(), creds)
                ?: return emptyList()

            val json = JSONObject(response)
            val bodyObj = json.optJSONObject("body") ?: return emptyList()
            val services = bodyObj.optJSONArray("Services") ?: return emptyList()

            val result = mutableListOf<HspService>()
            for (i in 0 until services.length()) {
                val svc = services.optJSONObject(i) ?: continue
                val attrs = svc.optJSONObject("serviceAttributesMetrics") ?: continue
                val rids = attrs.optJSONArray("rids") ?: continue

                // rids is a list of Darwin RIDs matched for the date range.
                // Since from_date == to_date we expect exactly one RID per service.
                for (r in 0 until rids.length()) {
                    val rid = rids.optString(r).trim()
                    if (rid.isEmpty()) continue
                    result.add(
                        HspService(
                            rid           = rid,
                            originTiploc  = attrs.optString("origin_location"),
                            destTiploc    = attrs.optString("destination_location"),
                            scheduledDep  = hhmm(attrs.optString("gbtt_ptd")),
                            scheduledArr  = hhmm(attrs.optString("gbtt_pta")),
                            tocCode       = attrs.optString("toc_code")
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "getStationBoard failed for $stationCrs on $date: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch full calling-point detail (with actual times) for a single RID.
     */
    fun getServiceDetail(rid: String): HspServiceDetail? {
        val creds = apiKey ?: return null
        return try {
            val body = JSONObject().apply { put("rid", rid) }
            val response = post("$BASE_URL/serviceDetails", body.toString(), creds)
                ?: return null

            val json = JSONObject(response)
            val bodyObj = json.optJSONObject("body") ?: return null
            val detail = bodyObj.optJSONObject("serviceAttributesDetails") ?: return null
            val locArray = detail.optJSONArray("locations") ?: JSONArray()

            val locs = (0 until locArray.length()).mapNotNull { i ->
                val l = locArray.optJSONObject(i) ?: return@mapNotNull null
                HspLocation(
                    tiploc      = l.optString("location"),
                    scheduledDep = hhmm(l.optString("gbtt_ptd")),
                    scheduledArr = hhmm(l.optString("gbtt_pta")),
                    actualDep   = hhmm(l.optString("actual_td")),
                    actualArr   = hhmm(l.optString("actual_ta")),
                    cancelReason = l.optString("late_canc_reason")
                )
            }

            HspServiceDetail(
                rid     = rid,
                date    = detail.optString("date_of_service"),
                tocCode = detail.optString("toc_code"),
                locations = locs
            )
        } catch (e: Exception) {
            Log.w(TAG, "getServiceDetail failed for rid=$rid: ${e.message}")
            null
        }
    }

    /**
     * Punctuality summary for a route over the past [days] days.
     * Used for the punctuality badge on service cards (existing feature).
     */
    fun getServiceMetrics(
        fromCrs: String,
        toCrs: String,
        @Suppress("UNUSED_PARAMETER") headcode: String,
        days: Int = Constants.HSP_DAYS_LOOKBACK
    ): HspSummary? {
        val creds = apiKey ?: return null
        return try {
            val cal = java.util.Calendar.getInstance()
            val toDate = String.format(
                "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            cal.add(java.util.Calendar.DAY_OF_MONTH, -days)
            val fromDate = String.format(
                "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )

            val body = JSONObject().apply {
                put("from_loc", fromCrs.uppercase())
                put("to_loc", toCrs.uppercase())
                put("from_time", "0000")
                put("to_time", "2359")
                put("from_date", fromDate)
                put("to_date", toDate)
                put("days", "WEEKDAY")
            }

            val response = post("$BASE_URL/serviceMetrics", body.toString(), creds)
                ?: return null
            val json = JSONObject(response)
            val bodyObj = json.optJSONObject("body") ?: return null
            val services = bodyObj.optJSONArray("Services") ?: return null

            var onTime = 0; var total = 0
            for (i in 0 until services.length()) {
                val svc = services.optJSONObject(i) ?: continue
                val metrics = svc.optJSONArray("Metrics") ?: continue
                for (m in 0 until metrics.length()) {
                    val metric = metrics.optJSONObject(m) ?: continue
                    if (metric.optBoolean("global_tolerance", false)) {
                        total  += metric.optString("num_not_tolerance").toIntOrNull() ?:0
                        total  += metric.optString("num_tolerance").toIntOrNull() ?: 0
                        onTime += metric.optString("num_tolerance").toIntOrNull() ?: 0
                    }
                }
            }
            if (total == 0) return null
            HspSummary(
                headcode       = headcode,
                operatorCode   = "",
                totalRuns      = total,
                onTimeCount    = onTime,
                punctualityPct = (onTime * 100 / total)
            )
        } catch (e: Exception) {
            Log.w(TAG, "getServiceMetrics failed: ${e.message}")
            null
        }
    }

    // ── Private HTTP ──────────────────────────────────────────────────────────

    private fun post(url: String, bodyJson: String, creds: String): String? {
        val request = Request.Builder()
            .url(url)
            .addHeader("x-apikey", creds)
            .addHeader("Content-Type", "application/json")
            .addHeader("Origin", "https://raildata.org.uk")
            .addHeader("Referer", "https://raildata.org.uk/")
            .post(bodyJson.toRequestBody(JSON_TYPE))
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.string()
            else {
                Log.w(TAG, "HSP POST $url → ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HSP POST $url failed: ${e.message}")
            null
        }
    }
}