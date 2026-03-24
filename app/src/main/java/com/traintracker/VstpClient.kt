package com.traintracker

import android.util.Log
import kotlinx.coroutines.CancellationException
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
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * VstpClient — NWR Very Short Term Plan (VSTP) schedule amendments
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * API: NWR VSTP (Very Short Term Plan)
 * Provider: Network Rail (via Rail Data Marketplace)
 * Type: Apache Kafka (Confluent Cloud)
 * Topic: VSTP_ALL
 * Bootstrap: same as TRUST (Constants.TRUST_BOOTSTRAP)
 * Security: SASL_SSL/PLAIN
 * Authentication: Constants.VSTP_USERNAME / Constants.VSTP_PASSWORD
 *
 * ───────────────────────────────────────────────────────────────────────────
 * PURPOSE
 * ───────────────────────────────────────────────────────────────────────────
 * VSTP carries intra-day schedule additions and amendments — services that
 * were not in the overnight CIF download. These are typically:
 *   • Diverted services taking non-standard routes
 *   • Short-notice extra workings (e.g. engineering relief trains)
 *   • Same-day reschedules (departure time changes, intermediate stop changes)
 *   • Cancellations of scheduled services (STP indicator C)
 *
 * VstpClient parses incoming JSON messages and calls CifRepository.applyVstp()
 * to insert or update the local CIF SQLite DB without a full re-parse.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * MESSAGE FORMAT
 * ───────────────────────────────────────────────────────────────────────────
 * VSTP messages are JSON, same CIF JSON schema as the overnight full extract:
 *   { "VSTPCIFMsgV1": { "schedule": { "JsonScheduleV1": { ... } } } }
 *
 * The JsonScheduleV1 object is identical to the full CIF feed format.
 * transaction_type: "Create" | "Delete"
 * CIF_stp_indicator: "N" (new) | "O" (overlay) | "C" (cancellation)
 *
 * ───────────────────────────────────────────────────────────────────────────
 * REQUIRED CONSTANTS (Constants.kt)
 * ───────────────────────────────────────────────────────────────────────────
 * const val VSTP_USERNAME = "..."   // From NWR RDM VSTP subscription
 * const val VSTP_PASSWORD = "..."   // From NWR RDM VSTP subscription
 * (Bootstrap server shared with TRUST: Constants.TRUST_BOOTSTRAP)
 */
class VstpClient {

    companion object {
        private const val TAG   = "VstpClient"
        private const val TOPIC = "VSTP_ALL"
        private val POLL_TIMEOUT = Duration.ofSeconds(5)
    }

    private val _connected = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 2)
    val connected: SharedFlow<Boolean> = _connected

    @Volatile private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private val groupId = "traintracker-vstp-${UUID.randomUUID()}"

    fun connect() {
        val username = try { Constants.VSTP_USERNAME } catch (_: Exception) { "" }
        if (username.isEmpty()) {
            Log.d(TAG, "VSTP_USERNAME not set — VSTP feed disabled")
            return
        }
        if (running) return
        running = true
        connectionJob = scope.launch { consumeLoop() }
    }

    fun disconnect() {
        running = false
        connectionJob?.cancel()
        _connected.tryEmit(false)
        Log.d(TAG, "Disconnected")
    }

    private suspend fun consumeLoop() {
        while (running) {
            try {
                withContext(Dispatchers.IO) {
                    buildConsumer().use { consumer ->
                        consumer.subscribe(listOf(TOPIC))
                        _connected.tryEmit(true)
                        Log.d(TAG, "VSTP Kafka subscribed to $TOPIC (group=$groupId)")
                        while (running) {
                            val records = consumer.poll(POLL_TIMEOUT)
                            for (record in records) {
                                record.value()?.let { handleMessage(it) }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VSTP Kafka error: ${e.message}")
                _connected.tryEmit(false)
                if (running) delay(5_000)
            }
        }
    }

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
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,         "200")
            put("security.protocol",                        "SASL_SSL")
            put("sasl.mechanism",                           "PLAIN")
            put("sasl.client.callback.handler.class",       VstpSaslCallbackHandler::class.java.name)
            put("metric.reporters",                         "")
            put("auto.include.jmx.reporter",                "false")
        }
        return KafkaConsumer(props)
    }

    private fun handleMessage(json: String) {
        if (json.isEmpty()) return
        try {
            // VSTP envelope: { "VSTPCIFMsgV1": { "schedule": { "JsonScheduleV1": { ... } } } }
            // Unwrap to get the JsonScheduleV1 line that CifRepository already knows how to parse.
            // The inner schedule object is re-wrapped as { "JsonScheduleV1": ... } to match the
            // overnight CIF format so applyVstp() can reuse the same parsing logic.
            val scheduleStart = json.indexOf("\"JsonScheduleV1\"")
            if (scheduleStart < 0) return

            // Find the opening brace of the JsonScheduleV1 value object
            val valueStart = json.indexOf('{', scheduleStart + "\"JsonScheduleV1\"".length)
            if (valueStart < 0) return

            // Walk forward to find the matching closing brace
            var depth = 0
            var i = valueStart
            while (i < json.length) {
                when (json[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) break }
                }
                i++
            }
            if (depth != 0) return

            // Re-wrap as a top-level JsonScheduleV1 line matching the overnight CIF format
            val scheduleJson = "{\"JsonScheduleV1\":" + json.substring(valueStart, i + 1) + "}"

            CifRepository.applyVstp(scheduleJson)

        } catch (e: Exception) {
            Log.w(TAG, "VSTP message handling error: ${e.message}")
        }
    }
}
