package com.traintracker

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * NWR Train Movements (TRUST) client.
 *
 * Consumes the TRAIN_MVT_ALL_TOC Kafka topic using the native Kafka client
 * over SASL_SSL/PLAIN, matching Network Rail's Confluent Cloud configuration.
 *
 * Headcode extraction:
 *   The TRUST 10-character train_id encodes the headcode at characters 2-5 (0-based).
 *   Format: SS HHHH CC DD
 *     SS   = 2-digit origin STANOX area prefix
 *     HHHH = 4-character headcode (e.g. "1A34")
 *     CC   = call code
 *     DD   = day of month
 *   This is present in every message type (0001/0002/0003/0005).
 *
 * Required in Constants.kt:
 *   TRUST_BOOTSTRAP  - e.g. "pkc-z3p1v0.europe-west2.gcp.confluent.cloud:9092"
 *   TRUST_USERNAME   - Consumer username from NWR RDM subscription
 *   TRUST_PASSWORD   - Consumer password from NWR RDM subscription
 */
class TrustClient {

    companion object {
        private const val TAG   = "TrustClient"
        private const val TOPIC = "TRAIN_MVT_ALL_TOC"
        private val POLL_TIMEOUT = Duration.ofSeconds(5)

        /**
         * Extract the 4-character headcode from a TRUST 10-char train_id.
         * Returns "" if blank, too short, or anonymised (no letter in position 3).
         * Freight headcodes beginning with 6/7 may be anonymised by NR —
         * these still get returned as-is since they are valid for board display.
         */
        fun headcodeFromTrainId(trainId: String): String {
            if (trainId.length < 6) return ""
            val hc = trainId.substring(2, 6)
            if (hc.length != 4) return ""
            if (!hc[0].isDigit()) return ""
            if (!hc[1].isLetter()) return ""
            return hc
        }
    }

    private val _movements   = MutableSharedFlow<TrustMovement>(extraBufferCapacity = 256)
    val movements: SharedFlow<TrustMovement> = _movements

    private val _activations = MutableSharedFlow<TrustActivation>(extraBufferCapacity = 256)
    val activations: SharedFlow<TrustActivation> = _activations

    private val _connected = MutableSharedFlow<Boolean>(replay = 1)
    val connected: SharedFlow<Boolean> = _connected

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile private var filterCrs: String = ""
    @Volatile private var running = false

    private val groupId = "traintracker-${UUID.randomUUID()}"

    // ---- Public API --------------------------------------------------------

    fun setFilterCrs(crs: String) { filterCrs = crs.uppercase() }

    fun connect() {
        val bootstrap = try { Constants.TRUST_BOOTSTRAP } catch (_: Exception) { "" }
        if (bootstrap.isEmpty()) {
            Log.d(TAG, "TRUST_BOOTSTRAP not set — TRUST disabled")
            return
        }
        val username = try { Constants.TRUST_USERNAME } catch (_: Exception) { "" }
        if (username.isEmpty()) {
            Log.d(TAG, "TRUST_USERNAME not set — TRUST disabled")
            return
        }
        if (running) return
        running = true
        job = scope.launch { consumeLoop() }
    }

    fun disconnect() {
        running = false
        job?.cancel()
        _connected.tryEmit(false)
    }

    // ---- Consumer loop -----------------------------------------------------

    private suspend fun consumeLoop() {
        while (running) {
            try {
                withContext(Dispatchers.IO) {
                    buildConsumer().use { consumer ->
                        consumer.subscribe(listOf(TOPIC))
                        _connected.tryEmit(true)
                        Log.d(TAG, "TRUST subscribed to $TOPIC (group=$groupId)")
                        while (running) {
                            val records = consumer.poll(POLL_TIMEOUT)
                            if (!records.isEmpty) {
                                Log.d(TAG, "TRUST: ${records.count()} record(s)")
                            }
                            for (record in records) {
                                record.value()?.let { parseRecord(it) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TRUST error: ${e.message}")
                _connected.tryEmit(false)
                if (running) delay(10_000)
            }
        }
        _connected.tryEmit(false)
    }

    // ---- Kafka setup -------------------------------------------------------

    private fun buildConsumer(): KafkaConsumer<String, String> {
        val jaasConfig = "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                "username=\"${Constants.TRUST_USERNAME}\" password=\"${Constants.TRUST_PASSWORD}\";"
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        Constants.TRUST_BOOTSTRAP)
            put(ConsumerConfig.GROUP_ID_CONFIG,                 groupId)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "latest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       "true")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,       "30000")
            put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,       "45000")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,         "500")
            put("security.protocol", "SASL_SSL")
            put("sasl.mechanism",    "PLAIN")
            put("sasl.jaas.config",  jaasConfig)
        }
        return KafkaConsumer(props)
    }

    // ---- Message parsing ---------------------------------------------------

    private fun parseRecord(valueStr: String) {
        try {
            val messages: JSONArray = when {
                valueStr.trimStart().startsWith('[') -> JSONArray(valueStr)
                else -> JSONArray().put(JSONObject(valueStr))
            }
            for (i in 0 until messages.length()) {
                val msg    = messages.getJSONObject(i)
                val header = msg.optJSONObject("header") ?: continue
                val body   = msg.optJSONObject("body")   ?: continue
                val msgType = header.optString("msg_type", "")
                val trainId = body.optString("train_id", "")
                val headcode = headcodeFromTrainId(trainId)
                when (msgType) {
                    "0001" -> parseActivation(header, body, trainId, headcode)
                    "0002" -> parseCancellation(body, trainId, headcode)
                    "0003" -> parseMovement(body, trainId, headcode)
                    "0005" -> parseReinstatement(body, trainId, headcode)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TRUST parse error: ${e.message}")
        }
    }

    private fun parseActivation(header: JSONObject, body: JSONObject,
                                trainId: String, headcode: String) {
        if (headcode.isEmpty()) return
        val originDepMs  = body.optString("origin_dep_timestamp", "").toLongOrNull()
        val originStanox = body.optString("sched_origin_stanox", "")
        val trainUid     = header.optString("train_uid", "")
        val schedType    = body.optString("schedule_type", "")
        val originDep    = if (originDepMs != null && originDepMs > 0)
            formatTrustTime(originDepMs.toString()) else ""
        _activations.tryEmit(TrustActivation(
            trainId      = trainId,
            headcode     = headcode,
            trainUid     = trainUid,
            originStanox = originStanox,
            originDep    = originDep,
            schedType    = schedType
        ))
    }

    private fun parseMovement(body: JSONObject, trainId: String, headcode: String) {
        val stanox = body.optString("loc_stanox", "")
        if (stanox.isBlank()) return
        val crs = CorpusData.crsFromStanox(stanox)
        if (filterCrs.isNotEmpty() && (crs == null || crs != filterCrs)) return
        val eventType = body.optString("event_type", "")
        _movements.tryEmit(TrustMovement(
            type          = if (eventType == "DEPARTURE") "DEPARTURE" else "ARRIVAL",
            trainUid      = "",
            trainId       = trainId,
            headcode      = headcode,
            stanox        = stanox,
            crs           = crs ?: stanox,
            scheduledTime = formatTrustTime(body.optString("gbtt_timestamp", "")),
            actualTime    = formatTrustTime(body.optString("actual_timestamp", "")),
            platform      = body.optString("platform", "").trim(),
            isCancelled   = false
        ))
    }

    private fun parseCancellation(body: JSONObject, trainId: String, headcode: String) {
        val stanox = body.optString("loc_stanox", "")
        if (stanox.isBlank()) return
        val crs = CorpusData.crsFromStanox(stanox)
        if (filterCrs.isNotEmpty() && (crs == null || crs != filterCrs)) return
        _movements.tryEmit(TrustMovement(
            type          = "CANCELLATION",
            trainUid      = "",
            trainId       = trainId,
            headcode      = headcode,
            stanox        = stanox,
            crs           = crs ?: stanox,
            scheduledTime = formatTrustTime(body.optString("dep_timestamp", "")),
            actualTime    = "",
            platform      = "",
            isCancelled   = true
        ))
    }

    private fun parseReinstatement(body: JSONObject, trainId: String, headcode: String) {
        val stanox = body.optString("reinstatement_stanox", "")
        if (stanox.isBlank()) return
        val crs = CorpusData.crsFromStanox(stanox)
        if (filterCrs.isNotEmpty() && (crs == null || crs != filterCrs)) return
        _movements.tryEmit(TrustMovement(
            type          = "REINSTATEMENT",
            trainUid      = "",
            trainId       = trainId,
            headcode      = headcode,
            stanox        = stanox,
            crs           = crs ?: stanox,
            scheduledTime = formatTrustTime(body.optString("dep_timestamp", "")),
            actualTime    = "",
            platform      = "",
            isCancelled   = false
        ))
    }

    private fun formatTrustTime(epochMs: String): String {
        val ms = epochMs.toLongOrNull() ?: return ""
        if (ms == 0L) return ""
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }
}

// ---- Data models -----------------------------------------------------------

data class TrustMovement(
    val type: String,
    val trainUid: String,
    val trainId: String,
    val headcode: String,       // 4-char headcode extracted from trainId
    val stanox: String,
    val crs: String,
    val scheduledTime: String,
    val actualTime: String,
    val platform: String,
    val isCancelled: Boolean
)

data class TrustActivation(
    val trainId: String,
    val headcode: String,       // e.g. "1A34"
    val trainUid: String,
    val originStanox: String,
    val originDep: String,      // HH:MM departure from origin
    val schedType: String       // P/O/N/C
)
