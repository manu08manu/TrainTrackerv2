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
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AllocationClient — NWR Passenger Train Allocation and Consist
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * API: NWR Passenger Train Allocation and Consist
 * Provider: Network Rail via Rail Data Marketplace
 * Type: Apache Kafka (Confluent Cloud)
 * Topic: prod-1033-Passenger-Train-Allocation-and-Consist-1_0
 * Bootstrap: pkc-z3p1v0.europe-west2.gcp.confluent.cloud:9092
 * Security: SASL_SSL/PLAIN
 *
 * ───────────────────────────────────────────────────────────────────────────
 * DATA SOURCE
 * ───────────────────────────────────────────────────────────────────────────
 * Derived from WebGemini — the rail industry system that manages rolling stock
 * allocation. Updated by train operators who are responsible for its accuracy.
 * Data may not reflect reality during disruption, and may be updated after
 * a train has run.
 *
 * Does NOT cover: London Overground, freight operators.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * MESSAGE FORMAT
 * ───────────────────────────────────────────────────────────────────────────
 * Each Kafka record value is a JSON object with a "body" or "xml" field
 * containing Darwin-style XML. The relevant element is <PTSAC> (Passenger
 * Train Schedule and Consist):
 *
 *   <PTSAC>
 *     <Journey rId="..." uid="..." sdd="YYYY-MM-DD" trainId="1A34">
 *       <Unit>
 *         <Vehicle>387101</Vehicle>
 *         <Vehicle>387102</Vehicle>
 *       </Unit>
 *       <Unit>
 *         <Vehicle>387215</Vehicle>
 *       </Unit>
 *     </Journey>
 *   </PTSAC>
 *
 * - rId    : Darwin RID (unique run identifier, matches Darwin Push Port)
 * - uid    : Schedule UID (matches TRUST)
 * - trainId: 4-char headcode e.g. "1C54"
 * - Each <Unit> is a separate physical unit in the consist
 * - Each <Vehicle> within a Unit is an individual vehicle number
 *
 * ───────────────────────────────────────────────────────────────────────────
 * REQUIRED CONSTANTS (Constants.kt)
 * ───────────────────────────────────────────────────────────────────────────
 * const val ALLOCATION_USERNAME = "..."  // From RDM subscription
 * const val ALLOCATION_PASSWORD = "..."  // From RDM subscription
 */
class AllocationClient {

    companion object {
        private const val TAG   = "AllocationClient"
        private const val TOPIC = "prod-1033-Passenger-Train-Allocation-and-Consist-1_0"
        private val POLL_TIMEOUT = Duration.ofSeconds(5)
    }

    /** Emitted whenever a consist is received for a service. */
    private val _allocations = MutableSharedFlow<DarwinFormation>(extraBufferCapacity = 64)
    val allocations: SharedFlow<DarwinFormation> = _allocations

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile private var running = false

    private val groupId = "traintracker-alloc-${UUID.randomUUID()}"

    // ---- Public API --------------------------------------------------------

    fun connect() {
        val username = try { Constants.ALLOCATION_USERNAME } catch (_: Exception) { "" }
        if (username.isEmpty()) {
            Log.d(TAG, "ALLOCATION_USERNAME not set — allocation feed disabled")
            return
        }
        if (running) return
        running = true
        job = scope.launch { consumeLoop() }
    }

    fun disconnect() {
        running = false
        job?.cancel()
    }

    // ---- Consumer loop -----------------------------------------------------

    private suspend fun consumeLoop() {
        while (running) {
            try {
                withContext(Dispatchers.IO) {
                    buildConsumer().use { consumer ->
                        consumer.subscribe(listOf(TOPIC))
                        Log.d(TAG, "Allocation subscribed to $TOPIC (group=$groupId)")
                        while (running) {
                            val records = consumer.poll(POLL_TIMEOUT)
                            for (record in records) {
                                record.value()?.let { parseRecord(it) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val cause = generateSequence<Throwable>(e) { it.cause }.last()
                Log.w(TAG, "Allocation error: ${e.message} | root: ${cause.javaClass.simpleName}: ${cause.message}")
                if (running) delay(10_000)
            }
        }
    }

    // ---- Kafka setup -------------------------------------------------------

    private fun buildConsumer(): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        "pkc-z3p1v0.europe-west2.gcp.confluent.cloud:9092")
            put(ConsumerConfig.GROUP_ID_CONFIG,                 groupId)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "latest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       "true")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,       "30000")
            put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,       "45000")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,         "200")
            put("security.protocol",                            "SASL_SSL")
            put("sasl.mechanism",                               "PLAIN")
            put("sasl.client.callback.handler.class",           AllocationSaslCallbackHandler::class.java.name)
            put("metric.reporters",                             "")
            put("auto.include.jmx.reporter",                    "false")
        }
        return KafkaConsumer(props)
    }

    // ---- Message parsing ---------------------------------------------------

    private fun parseRecord(value: String) {
        try {
            val xml = extractXml(value) ?: return
            if (xml.contains("Journey", ignoreCase = true)) {
                parseAllocationXml(xml)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Allocation parse error: ${e.message}")
        }
    }

    private fun extractXml(value: String): String? {
        val trimmed = value.trimStart()
        if (trimmed.startsWith('<')) return trimmed
        return try {
            val json = JSONObject(value)
            json.optString("body").takeIf { it.isNotEmpty() }
                ?: json.optString("xml").takeIf { it.isNotEmpty() }
                ?: json.optString("payload").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /**
     * Parses PTSAC XML into [DarwinFormation] events.
     *
     * Structure (after stripping namespace prefix):
     *   <Journey rId="..." uid="..." sdd="..." trainId="1C54">
     *     <Unit>
     *       <Vehicle>387101</Vehicle>
     *       <Vehicle>387102</Vehicle>
     *     </Unit>
     *     <Unit>
     *       <Vehicle>387215</Vehicle>
     *     </Unit>
     *   </Journey>
     *
     * We flatten all vehicles across all units into a single list, in order,
     * and count the units (not vehicles) as a proxy for physical consist size
     * where coach count isn't directly available.
     */
    private fun parseAllocationXml(xml: String) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var rid = ""; var uid = ""; var trainId = ""
            val vehicles = mutableListOf<String>()
            var unitCount = 0
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = (parser.name ?: "").substringAfterLast(':').lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = tag
                        when (tag) {
                            "journey" -> {
                                rid     = parser.getAttributeValue(null, "rId") ?: ""
                                uid     = parser.getAttributeValue(null, "uid") ?: ""
                                trainId = parser.getAttributeValue(null, "trainId") ?: ""
                                vehicles.clear()
                                unitCount = 0
                            }
                            "unit" -> unitCount++
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (currentTag == "vehicle") {
                            val v = parser.text?.trim() ?: ""
                            if (v.isNotEmpty()) vehicles.add(v)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag == "journey" && (rid.isNotEmpty() || uid.isNotEmpty())) {
                            // Use vehicle count as coach count — each vehicle is one coach/unit
                            _allocations.tryEmit(
                                DarwinFormation(
                                    rid        = rid,
                                    uid        = uid,
                                    trainId    = trainId,
                                    units      = vehicles.toList(),
                                    coachCount = vehicles.size
                                )
                            )
                            Log.d(TAG, "Allocation: $trainId rid=$rid units=${vehicles.size} vehicles=$vehicles")
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Allocation XML parse error: ${e.message}")
        }
    }
}
