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
 * ═══════════════════════════════════════════════════════════════════════════
 * TrustClient — NWR Train Movements (TRUST)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * API: NWR Train Movements
 * Provider: Network Rail (via Rail Data Marketplace)
 * Type: Apache Kafka (Confluent Cloud)
 * Topic: TRAIN_MVT_ALL_TOC
 * Bootstrap Server: pkc-z3p1v0.europe-west2.gcp.confluent.cloud:9092
 * Security: SASL_SSL/PLAIN authentication
 * Region: GCP (europe-west2)
 * Authentication: Kafka username/password (Constants.TRUST_USERNAME, Constants.TRUST_PASSWORD)
 * Update Speed: Real-time streaming
 *
 * ───────────────────────────────────────────────────────────────────────────
 * MESSAGE TYPES (RTTI)
 * ───────────────────────────────────────────────────────────────────────────
 * • Type 0001: Activation    - Train service activation/creation
 * • Type 0002: Movement      - Train movement records with location updates
 * • Type 0003: Cancellation  - Service cancellation notice
 * • Type 0005: Reinstatement - Cancelled service reinstatement
 *
 * All message types contain the 10-character train_id with embedded headcode
 *
 * ───────────────────────────────────────────────────────────────────────────
 * HEADCODE EXTRACTION
 * ───────────────────────────────────────────────────────────────────────────
 * The TRUST 10-character train_id encodes the headcode at positions 2-5 (0-based):
 *
 *   Format: SS HHHH CC DD
 *   └─┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
 *     │       │       │       │
 *     │       │       │       └─ DD = Day of month (2 digits)
 *     │       │       └─ CC = Call code (2 digits)
 *     │       └─ HHHH = 4-character headcode (the actual train identifier)
 *     └─ SS = STANOX origin area prefix (2 digits)
 *
 * Example: "23 1A34 00 02" → Headcode = "1A34"
 * Example: "67 6M90 01 15" → Headcode = "6M90" (freight)
 * Example: "01 5Z12 00 28" → Headcode = "5Z12" (rail tour)
 *
 * ───────────────────────────────────────────────────────────────────────────
 * HEADCODE FORMATS
 * ───────────────────────────────────────────────────────────────────────────
 * Passenger trains:  1Axx-9Dxx (standard passenger services)
 * Freight trains:    6xxx, 7xxx
 * ECS (empties):     5xxx
 * Light engines:     0xxx
 * Rail tours:        1Zxx, 0Zxx, 5Zxx, etc.
 *
 * Note: Freight headcodes beginning with 6/7 may be ANONYMISED by Network Rail
 * (replaced with 0000 or other placeholder), but this is handled by the app
 *
 * ───────────────────────────────────────────────────────────────────────────
 * MESSAGE FORMAT
 * ───────────────────────────────────────────────────────────────────────────
 * Format: JSON
 * Serialization: String key/value in Kafka
 * No compression at message level (Kafka handles compression)
 *
 * ───────────────────────────────────────────────────────────────────────────
 * CONNECTION LIFECYCLE
 * ───────────────────────────────────────────────────────────────────────────
 * 1. connect()    → Creates Kafka consumer → Sets up SASL_SSL config → Subscribes
 * 2. Running      → Polls TRAIN_MVT_ALL_TOC topic → Emits TrustMovement/Activation
 * 3. disconnect() → Closes consumer → Cancels read loop → Emits disconnected state
 * 4. Auto-retry   → Reconnects every 5 seconds on failure
 *
 * ───────────────────────────────────────────────────────────────────────────
 * CLIENT-SIDE FILTERING
 * ───────────────────────────────────────────────────────────────────────────
 * • setFilterCrs(crs) : Filters movements to a specific station (CRS code)
 * • Filtering: Done client-side before emitting events
 * • Use case: When app is focused on a single station, reduce noise
 *
 * ───────────────────────────────────────────────────────────────────────────
 * IMPLEMENTATION DETAILS
 * ───────────────────────────────────────────────────────────────────────────
 * • Kafka Client: Apache Kafka native client (org.apache.kafka:kafka-clients)
 * • Consumer: String deserializer (both keys and values)
 * • Poll Timeout: 5 seconds per poll cycle
 * • Consumer Group: Dynamically generated per session (traintracker-{UUID})
 * • Concurrency: Kotlin coroutines with Dispatchers.IO
 * • Buffer Capacity: 256 items for movements, 256 for activations
 * • State: Volatile fields + atomic operations for thread safety
 *
 * ───────────────────────────────────────────────────────────────────────────
 * REQUIRED CONSTANTS (Constants.kt)
 * ───────────────────────────────────────────────────────────────────────────
 * const val TRUST_BOOTSTRAP = "pkc-z3p1v0.europe-west2.gcp.confluent.cloud:9092"
 * const val TRUST_USERNAME  = "..."  // From NWR RDM subscription
 * const val TRUST_PASSWORD  = "..."  // From NWR RDM subscription
 *
 * ───────────────────────────────────────────────────────────────────────────
 * KAFKA CONFIGURATION (SASL_SSL/PLAIN)
 * ───────────────────────────────────────────────────────────────────────────
 * • Security Protocol: SASL_SSL
 * • SASL Mechanism: PLAIN
 * • SASL Config: org.apache.kafka.common.security.plain.PlainLoginModule
 * • Bootstrap Servers: Confluent Cloud GCP (europe-west2)
 * • Encryption: SSL/TLS
 *
 * ───────────────────────────────────────────────────────────────────────────
 * HEADCODE VALIDATION LOGIC
 * ───────────────────────────────────────────────────────────────────────────
 * Extracted headcode must:
 * • Be exactly 4 characters
 * • Start with a digit (position 0)
 * • Have a letter at position 1 (e.g., A-Z)
 * • Pattern: D + L + (D|L) + (D|L)  where D=digit, L=letter
 *
 * Returns empty string if:
 * • train_id is too short (< 6 characters)
 * • Positions 2-5 don't form valid headcode
 * • Anonymised (6/7 prefix may be hidden, but still returned as-is)
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
                val cause = generateSequence<Throwable>(e) { it.cause }.last()
                Log.w(TAG, "TRUST error: ${e.message} | root: ${cause.javaClass.simpleName}: ${cause.message}")
                _connected.tryEmit(false)
                if (running) delay(10_000)
            }
        }
        _connected.tryEmit(false)
    }

    // ---- Kafka setup -------------------------------------------------------

    private fun buildConsumer(): KafkaConsumer<String, String> {
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
            put("security.protocol",                        "SASL_SSL")
            put("sasl.mechanism",                           "PLAIN")
            // Use a custom callback handler instead of sasl.jaas.config —
            // javax.security.auth.login.Configuration is not available on Android.
            put("sasl.client.callback.handler.class",       TrustSaslCallbackHandler::class.java.name)
            put("metric.reporters",                         "")
            put("auto.include.jmx.reporter",                "false")
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
                    "0002" -> parseMovement(body, trainId, headcode)
                    "0003" -> parseCancellation(body, trainId, headcode)
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
        val eventType    = body.optString("event_type", "")
        val movementType = body.optString("movement_type", "").uppercase()
        // TRUST fires PASSING events for non-stopping pass-throughs
        val isPassing = movementType.contains("PASSING")
        val type = when {
            isPassing              -> "PASSING"
            eventType == "DEPARTURE" -> "DEPARTURE"
            else                   -> "ARRIVAL"
        }
        _movements.tryEmit(TrustMovement(
            type          = type,
            trainUid      = "",
            trainId       = trainId,
            headcode      = headcode,
            stanox        = stanox,
            crs           = crs ?: stanox,
            scheduledTime = formatTrustTime(body.optString("gbtt_timestamp", "")),
            actualTime    = formatTrustTime(body.optString("actual_timestamp", "")),
            platform      = body.optString("platform", "").trim(),
            isCancelled   = false,
            isPassing     = isPassing
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
    val isCancelled: Boolean,
    val isPassing: Boolean = false
)

data class TrustActivation(
    val trainId: String,
    val headcode: String,       // e.g. "1A34"
    val trainUid: String,
    val originStanox: String,
    val originDep: String,      // HH:MM departure from origin
    val schedType: String       // P/O/N/C
)