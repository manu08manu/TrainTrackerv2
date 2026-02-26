package com.traintracker

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.Socket
import java.util.zip.GZIPInputStream

/**
 * Darwin Push Port client over STOMP/TCP.
 *
 * Connects to the Darwin messaging broker on port 61613 and subscribes to the
 * live pushport-v16 topic. Message bodies are gzip-compressed XML which is
 * decompressed before parsing.
 *
 * Connection lifecycle:
 *   connect()    → opens TCP socket, sends STOMP CONNECT + SUBSCRIBE, reads loop
 *   disconnect() → cancels the read loop and closes the socket
 *
 * Automatically retries on failure every 5 seconds.
 */
class DarwinClient {

    companion object {
        private const val TAG   = "DarwinClient"
        private const val HOST  = "darwin-dist-44ae45.nationalrail.co.uk"
        private const val PORT  = 61613
        private const val TOPIC = "/topic/darwin.pushport-v16"
        // TIPLOC→CRS mapping is now handled by CorpusData (replaces old hardcoded table)
    }

    // --- Public flows -----------------------------------------------------

    private val _updates = MutableSharedFlow<DarwinUpdate>(extraBufferCapacity = 64)
    val updates: SharedFlow<DarwinUpdate> = _updates

    private val _formations = MutableSharedFlow<DarwinFormation>(extraBufferCapacity = 32)
    val formations: SharedFlow<DarwinFormation> = _formations

    private val _connectionState = MutableSharedFlow<DarwinConnectionState>(
        replay = 1,
        extraBufferCapacity = 4
    )
    val connectionState: SharedFlow<DarwinConnectionState> = _connectionState

    // --- Internal state ---------------------------------------------------

    @Volatile private var filterCrs: String = ""
    @Volatile private var running = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null

    // --- Public API -------------------------------------------------------

    fun setFilterCrs(crs: String) {
        filterCrs = crs.uppercase()
    }

    fun connect() {
        if (running) return
        running = true
        connectionJob = scope.launch { connectionLoop() }
    }

    fun disconnect() {
        running = false
        connectionJob?.cancel()
        _connectionState.tryEmit(DarwinConnectionState.Disconnected)
        Log.d(TAG, "Disconnected")
    }

    // --- Connection loop --------------------------------------------------

    private suspend fun connectionLoop() {
        while (running) {
            try {
                _connectionState.tryEmit(DarwinConnectionState.Connecting)
                Log.d(TAG, "Connecting to $HOST:$PORT")

                Socket(HOST, PORT).use { socket ->
                    socket.soTimeout = 70_000  // 70s — covers the 60s heartbeat interval
                    val output = socket.getOutputStream()
                    val input  = BufferedInputStream(socket.getInputStream())

                    // 1. Send STOMP CONNECT
                    output.write(stompConnect().toByteArray(Charsets.UTF_8))
                    output.flush()

                    // 2. Expect CONNECTED
                    val (connCommand, _, _) = readFrame(input)
                    if (connCommand != "CONNECTED") {
                        Log.e(TAG, "Expected CONNECTED, got: $connCommand")
                        _connectionState.tryEmit(DarwinConnectionState.Error("STOMP handshake failed"))
                        delay(5_000)
                        return@use
                    }
                    Log.d(TAG, "STOMP CONNECTED — subscribing to $TOPIC")
                    _connectionState.tryEmit(DarwinConnectionState.Connected)

                    // 3. Send SUBSCRIBE
                    output.write(stompSubscribe().toByteArray(Charsets.UTF_8))
                    output.flush()

                    // 4. Read MESSAGE frames until disconnected
                    while (running) {
                        val (command, headers, body) = readFrame(input)
                        when (command) {
                            ""          -> { /* heartbeat newline — ignore */ }
                            "MESSAGE"   -> handleMessage(body)
                            "ERROR"     -> {
                                val msg = headers["message"] ?: "STOMP ERROR"
                                Log.e(TAG, "STOMP ERROR: $msg")
                                _connectionState.tryEmit(DarwinConnectionState.Error(msg))
                            }
                            else        -> Log.d(TAG, "Unhandled STOMP command: $command")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e  // let the coroutine cancel cleanly
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                _connectionState.tryEmit(DarwinConnectionState.Error(e.message ?: "Connection failed"))
                if (running) delay(5_000)  // back-off before retry
            }
        }
    }

    // --- STOMP framing ----------------------------------------------------

    private fun stompConnect(): String = buildString {
        append("CONNECT\n")
        append("accept-version:1.1,1.2\n")
        append("login:${Constants.DARWIN_USERNAME}\n")
        append("passcode:${Constants.DARWIN_PASSWORD}\n")
        append("heart-beat:10000,60000\n")
        append("\n")
        append("\u0000")
    }

    private fun stompSubscribe(): String = buildString {
        append("SUBSCRIBE\n")
        append("id:sub-1\n")
        append("destination:$TOPIC\n")
        append("ack:auto\n")
        append("\n")
        append("\u0000")
    }

    // --- Frame reader -----------------------------------------------------

    /**
     * Reads one complete STOMP frame from [input].
     *
     * Returns a Triple of (command, headers, bodyBytes).
     * Heartbeat frames (bare newlines) return ("", emptyMap(), emptyBytes).
     *
     * Darwin messages are gzip-compressed, so body bytes are raw — call
     * [decompressBody] before treating them as text.
     */
    private fun readFrame(input: BufferedInputStream): Triple<String, Map<String, String>, ByteArray> {
        // Read command line
        val command = readLine(input).trim()
        if (command.isEmpty()) {
            // Heartbeat — consume any trailing null byte and return
            return Triple("", emptyMap(), ByteArray(0))
        }

        // Read headers until blank line
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon)] = line.substring(colon + 1)
        }

        // Read body — use content-length if provided, otherwise read to null byte
        val contentLength = headers["content-length"]?.toIntOrNull()
        val body: ByteArray

        if (contentLength != null && contentLength > 0) {
            body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                if (read == -1) throw IOException("Stream closed reading body")
                offset += read
            }
            input.read() // consume trailing null byte
        } else {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) throw IOException("Stream closed reading body")
                if (b == 0) break
                buf.write(b)
            }
            body = buf.toByteArray()
        }

        return Triple(command, headers, body)
    }

    /** Reads one `\n`-terminated line from the stream (strips `\r` if present). */
    private fun readLine(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("Stream closed reading line")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    // --- Message handling -------------------------------------------------

    private fun handleMessage(body: ByteArray) {
        if (body.isEmpty()) return
        try {
            // Darwin pushport-v16 bodies are gzip compressed
            val xml = decompressBody(body)
            parseDarwinXml(xml)
            // Also parse formation data if present
            if (xml.contains("<Formation", ignoreCase = true)) {
                parseFormation(xml)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle Darwin message: ${e.message}")
        }
    }

    /**
     * Decompresses a gzip body. Falls back to plain UTF-8 text if the bytes
     * are not gzip (first two bytes are not the gzip magic number 0x1F 0x8B).
     */
    private fun decompressBody(body: ByteArray): String {
        val isGzip = body.size >= 2 && body[0] == 0x1F.toByte() && body[1] == 0x8B.toByte()
        return if (isGzip) {
            GZIPInputStream(body.inputStream()).bufferedReader(Charsets.UTF_8).readText()
        } else {
            body.toString(Charsets.UTF_8)
        }
    }

    // --- Darwin XML parsing -----------------------------------------------

    private fun parseDarwinXml(xml: String) {
        if (xml.isBlank()) return
        val crs = filterCrs
        if (crs.isEmpty()) return

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var inTs = false
            var trainUid = ""
            var trainRid = ""
            var headcode = ""

            var inLocation = false
            var locationTiploc = ""
            var locationPtd = ""
            var locationPta = ""
            var locationEtd = ""
            var locationEta = ""
            var locationAtd = ""
            var locationPlatform = ""
            var locationCancelled = false
            var locationCancelReason: String? = null
            var locationDelayReason: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = (parser.name ?: "").substringAfterLast(':').lowercase()

                when (eventType) {
                    XmlPullParser.START_TAG -> when {
                        tag == "ts" -> {
                            inTs = true
                            trainUid = parser.getAttributeValue(null, "uid") ?: ""
                            trainRid = parser.getAttributeValue(null, "rid") ?: ""
                            headcode = ""
                        }
                        tag == "trainid" && inTs -> {
                            headcode = readXmlText(parser)
                        }
                        tag == "location" && inTs -> {
                            inLocation     = true
                            locationTiploc = parser.getAttributeValue(null, "tpl") ?: ""
                            locationPtd    = parser.getAttributeValue(null, "ptd") ?: ""
                            locationPta    = parser.getAttributeValue(null, "pta") ?: ""
                            locationEtd    = ""
                            locationEta    = ""
                            locationAtd    = ""
                            locationPlatform  = ""
                            locationCancelled = false
                            locationCancelReason = null
                            locationDelayReason  = null
                        }
                        tag == "dep" && inLocation -> {
                            locationEtd       = parser.getAttributeValue(null, "et") ?: ""
                            locationAtd       = parser.getAttributeValue(null, "at") ?: ""
                            locationCancelled = parser.getAttributeValue(null, "isCancelled") == "true"
                        }
                        tag == "arr" && inLocation -> {
                            locationEta = parser.getAttributeValue(null, "et") ?: ""
                        }
                        tag == "plat" && inLocation -> {
                            locationPlatform = readXmlText(parser)
                        }
                        tag == "cancreason" && inLocation -> {
                            locationCancelReason = readXmlText(parser).ifEmpty { null }
                        }
                        tag == "delayreason" && inLocation -> {
                            locationDelayReason = readXmlText(parser).ifEmpty { null }
                        }
                    }

                    XmlPullParser.END_TAG -> when {
                        tag == "location" && inLocation -> {
                            if (tiplocMatchesCrs(locationTiploc, crs)) {
                                _updates.tryEmit(
                                    DarwinUpdate(
                                        trainRid           = trainRid,
                                        trainUid           = trainUid,
                                        headcode           = headcode,
                                        stationCrs         = crs,
                                        stationTiploc      = locationTiploc,
                                        scheduledDeparture = locationPtd,
                                        scheduledArrival   = locationPta,
                                        estimatedDeparture = locationEtd.ifEmpty { null },
                                        estimatedArrival   = locationEta.ifEmpty { null },
                                        actualDeparture    = locationAtd.ifEmpty { null },
                                        platform           = locationPlatform.ifEmpty { null },
                                        isCancelled        = locationCancelled,
                                        cancelReason       = locationCancelReason,
                                        delayReason        = locationDelayReason
                                    )
                                )
                            }
                            inLocation = false
                        }
                        tag == "ts" -> inTs = false
                    }
                }

                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Darwin XML: ${e.message}")
        }
    }

    /**
     * Parses Darwin Formation messages.
     *
     * Formation XML structure (pushport-v16):
     * <Formation rid="..." uid="..." trainId="...">
     *   <Coaches>
     *     <Coach coachNumber="A" coachClass="First">
     *       <ToiletType>Standard</ToiletType>
     *       <vehicle>321001</vehicle>   ← unit number
     *     </Coach>
     *   </Coaches>
     * </Formation>
     */
    private fun parseFormation(xml: String) {
        if (xml.isBlank()) return
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(xml))

            var rid = ""; var uid = ""; var trainId = ""
            val units = mutableSetOf<String>()
            var coachCount = 0
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = (parser.name ?: "").substringAfterLast(':').lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = tag
                        when (tag) {
                            "formation" -> {
                                rid     = parser.getAttributeValue(null, "rid") ?: ""
                                uid     = parser.getAttributeValue(null, "uid") ?: ""
                                trainId = parser.getAttributeValue(null, "trainId") ?: ""
                                units.clear(); coachCount = 0
                            }
                            "coach" -> coachCount++
                        }
                    }
                    XmlPullParser.TEXT -> {
                        // <vehicle> contains the actual unit/stock number
                        if (currentTag == "vehicle") {
                            val v = parser.text?.trim() ?: ""
                            if (v.isNotEmpty()) units.add(v)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag == "formation" && rid.isNotEmpty()) {
                            _formations.tryEmit(DarwinFormation(
                                rid        = rid,
                                uid        = uid,
                                trainId    = trainId,
                                units      = units.toList(),
                                coachCount = coachCount
                            ))
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Formation parse error: ${e.message}")
        }
    }

    private fun tiplocMatchesCrs(tiploc: String, crs: String): Boolean {
        if (tiploc.isEmpty() || crs.isEmpty()) return false
        // 1. Station asset TIPLOC (most precise)
        val station = StationData.findByCrs(crs)
        if (station?.tiploc != null) return tiploc == station.tiploc
        // 2. CorpusData (full TIPLOC→CRS map, updated from RDM subscription)
        val corpusCrs = CorpusData.crsFromTiploc(tiploc)
        if (corpusCrs != null) return corpusCrs == crs
        // 3. Fallback prefix match
        return tiploc.startsWith(crs, ignoreCase = true)
    }

    private fun readXmlText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result
    }
}

// --- Data models ----------------------------------------------------------

data class DarwinUpdate(
    val trainRid: String,
    val trainUid: String,
    val headcode: String,
    val stationCrs: String,
    val stationTiploc: String,
    val scheduledDeparture: String,
    val scheduledArrival: String,
    val estimatedDeparture: String?,
    val estimatedArrival: String?,
    val actualDeparture: String?,
    val platform: String?,
    val isCancelled: Boolean,
    val cancelReason: String?,
    val delayReason: String?
)

sealed class DarwinConnectionState {
    object Connecting    : DarwinConnectionState()
    object Connected     : DarwinConnectionState()
    object Disconnected  : DarwinConnectionState()
    data class Error(val message: String) : DarwinConnectionState()
}

data class DarwinFormation(
    val rid: String,
    val uid: String,
    val trainId: String,        // headcode e.g. "1A34"
    val units: List<String>,    // actual vehicle/unit numbers e.g. ["321001", "321002"]
    val coachCount: Int
)