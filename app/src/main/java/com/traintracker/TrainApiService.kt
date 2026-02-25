package com.traintracker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class TrainApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val dbFactory = DocumentBuilderFactory.newInstance()
        .also { it.isNamespaceAware = true }

    private val endpoint = "https://lite.realtime.nationalrail.co.uk/OpenLDBWS/ldb12.asmx"
    private val token    = Constants.LDB_TOKEN
    private val xmlMedia = "text/xml; charset=utf-8".toMediaType()

    private val soapHeader = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:typ="http://thalesgroup.com/RTTI/2013-11-28/Token/types"
               xmlns:ldb="http://thalesgroup.com/RTTI/2021-11-01/ldb/">
    <soap:Header><typ:AccessToken><typ:TokenValue>$token</typ:TokenValue></typ:AccessToken></soap:Header>
    <soap:Body>"""
    private val soapFooter = "\n    </soap:Body>\n</soap:Envelope>"

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Fetch a departure/arrival/all board.
     *
     * @param crs         Station CRS code (e.g. "EUS")
     * @param boardType   DEPARTURES / ARRIVALS / ALL
     * @param numRows     Max services to return (up to 150)
     * @param filterCrs   Optional — only show trains calling at this CRS
     * @param timeOffset  Minutes offset from now (-120 to +1439). 0 = now.
     */
    fun getBoard(
        crs: String,
        boardType: BoardType,
        numRows: Int = 20,
        filterCrs: String? = null,
        timeOffset: Int = 0
    ): BoardResult {
        val op = when (boardType) {
            BoardType.DEPARTURES -> "GetDepartureBoardRequest"
            BoardType.ARRIVALS   -> "GetArrivalBoardRequest"
            BoardType.ALL        -> "GetArrivalDepartureBoardRequest"
        }
        val filterEl = if (!filterCrs.isNullOrBlank())
            "<ldb:filterCrs>${filterCrs.uppercase()}</ldb:filterCrs><ldb:filterType>to</ldb:filterType>"
        else ""
        val offsetEl = if (timeOffset != 0) "<ldb:timeOffset>$timeOffset</ldb:timeOffset>" else ""
        val body = "\n        <ldb:$op>" +
            "<ldb:numRows>$numRows</ldb:numRows>" +
            "<ldb:crs>${crs.uppercase()}</ldb:crs>" +
            filterEl + offsetEl +
            "</ldb:$op>"
        val result = parseBoardResponse(post(soapHeader + body + soapFooter, boardType.soapAction), crs, boardType)
        return result.copy(filterCallingAt = filterCrs?.uppercase() ?: "", timeOffset = timeOffset)
    }

    fun getServiceDetails(serviceId: String): ServiceDetails {
        val body = "\n        <ldb:GetServiceDetailsRequest><ldb:serviceID>$serviceId</ldb:serviceID></ldb:GetServiceDetailsRequest>"
        return parseServiceDetails(post(soapHeader + body + soapFooter, "GetServiceDetails"))
    }

    // ─── HTTP ──────────────────────────────────────────────────────────────────

    private fun post(soap: String, action: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "text/xml; charset=utf-8")
            .addHeader("SOAPAction", "http://thalesgroup.com/RTTI/2012-01-13/ldb/$action")
            .post(soap.toRequestBody(xmlMedia))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception(extractFault(body) ?: "Server error: ${response.code}")
        return body
    }

    // ─── XML parsers ───────────────────────────────────────────────────────────

    private fun parseXml(xml: String) =
        dbFactory.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
            .also { it.documentElement.normalize() }

    private fun firstTag(doc: org.w3c.dom.Document, name: String): String {
        val nodes = doc.getElementsByTagNameNS("*", name)
        return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
    }

    private fun Element.child(localName: String): String {
        val nl = getElementsByTagNameNS("*", localName)
        return if (nl.length > 0) nl.item(0).textContent.trim() else ""
    }

    private fun parseBoardResponse(xml: String, crs: String, boardType: BoardType): BoardResult {
        val doc          = parseXml(xml)
        val stationName  = firstTag(doc, "locationName")
        val generatedAt  = firstTag(doc, "generatedAt")
        val serviceNodes = doc.getElementsByTagNameNS("*", "service")
        val services     = ArrayList<TrainService>(serviceNodes.length)
        for (i in 0 until serviceNodes.length) {
            services.add(parseServiceItem(serviceNodes.item(i) as Element, boardType))
        }
        return BoardResult(stationName, crs.uppercase(), services, generatedAt, boardType)
    }

    private fun parseServiceItem(node: Element, boardType: BoardType): TrainService {
        var std = ""; var etd = ""; var sta = ""; var eta = ""
        var platform = ""; var operator = ""; var operatorCode = ""
        var serviceID = ""; var trainId = ""; var rsid = ""; var serviceType = "train"
        var isCancelled = false; var isPassenger = true
        var cancelReason = ""; var delayReason = ""
        val destinations = mutableListOf<String>()
        val origins      = mutableListOf<String>()

        val children = node.childNodes
        for (j in 0 until children.length) {
            val child = children.item(j) as? Element ?: continue
            when (child.localName) {
                "std"                -> std          = child.textContent.trim()
                "etd"                -> etd          = child.textContent.trim()
                "sta"                -> sta          = child.textContent.trim()
                "eta"                -> eta          = child.textContent.trim()
                "platform"           -> platform     = child.textContent.trim()
                "operator"           -> operator     = child.textContent.trim()
                "operatorCode"       -> operatorCode = child.textContent.trim()
                "serviceID"          -> serviceID    = child.textContent.trim()
                "trainid"            -> trainId      = child.textContent.trim()
                "rsid"               -> rsid          = child.textContent.trim()
                "isCancelled"        -> isCancelled  = child.textContent.trim() == "true"
                "cancelReason"       -> cancelReason = child.textContent.trim()
                "delayReason"        -> delayReason  = child.textContent.trim()
                "serviceType"        -> { serviceType = child.textContent.trim().lowercase(); isPassenger = serviceType != "freight" }
                "isPassengerService" -> isPassenger  = child.textContent.trim() == "true"
                "destination"        -> child.getElementsByTagNameNS("*", "locationName")
                    .let { nl -> (0 until nl.length).forEach { destinations.add(nl.item(it).textContent.trim()) } }
                "origin"             -> child.getElementsByTagNameNS("*", "locationName")
                    .let { nl -> (0 until nl.length).forEach { origins.add(nl.item(it).textContent.trim()) } }
            }
        }
        return TrainService(
            std = std, etd = etd, sta = sta, eta = eta,
            destination = destinations.joinToString(" & "),
            origin      = origins.joinToString(" & "),
            platform = platform, operator = operator, operatorCode = operatorCode,
            isCancelled = isCancelled, serviceID = serviceID,
            trainId = trainId, boardType = boardType,
            serviceType = serviceType, isPassenger = isPassenger,
            rsid = rsid,
            hasAlert = cancelReason.isNotEmpty() || delayReason.isNotEmpty()
        )
    }

    private fun parseServiceDetails(xml: String): ServiceDetails {
        val doc = parseXml(xml)

        val prev = mutableListOf<CallingPoint>()
        val subs = mutableListOf<CallingPoint>()

        fun parsePointList(listName: String, out: MutableList<CallingPoint>) {
            val lists = doc.getElementsByTagNameNS("*", listName)
            for (i in 0 until lists.length) {
                val pts = (lists.item(i) as Element).getElementsByTagNameNS("*", "callingPoint")
                for (j in 0 until pts.length) {
                    val pt = pts.item(j) as Element
                    out.add(CallingPoint(
                        locationName = pt.child("locationName"),
                        crs          = pt.child("crs"),
                        st           = pt.child("st"),
                        et           = pt.child("et"),
                        at           = pt.child("at"),
                        isCancelled  = pt.child("isCancelled") == "true",
                        length       = pt.child("length").toIntOrNull(),
                        platform     = pt.child("platform"),
                        isPassing    = pt.child("isPass") == "true"
                    ))
                }
            }
        }

        parsePointList("previousCallingPoints", prev)
        parsePointList("subsequentCallingPoints", subs)

        val formationEl = doc.getElementsByTagNameNS("*", "formation")
        val coachCount  = if (formationEl.length > 0)
            (formationEl.item(0) as Element).getElementsByTagNameNS("*", "coach").let { if (it.length > 0) it.length else null }
        else null

        fun stationNameFrom(tag: String): String {
            val nl = doc.getElementsByTagNameNS("*", tag)
            return if (nl.length > 0) (nl.item(0) as Element).child("locationName") else ""
        }

        val alerts = doc.getElementsByTagNameNS("*", "adhocAlerts").let { nl ->
            (0 until nl.length).joinToString("\n") { nl.item(it).textContent.trim() }
        }

        return ServiceDetails(
            generatedAt             = firstTag(doc, "generatedAt"),
            serviceType             = firstTag(doc, "serviceType"),
            trainId                 = firstTag(doc, "trainid"),
            rsid                    = firstTag(doc, "rsid"),
            operator                = firstTag(doc, "operator"),
            operatorCode            = firstTag(doc, "operatorCode"),
            isCancelled             = firstTag(doc, "isCancelled") == "true",
            platform                = firstTag(doc, "platform"),
            origin                  = stationNameFrom("origin"),
            destination             = stationNameFrom("destination"),
            previousCallingPoints   = prev,
            subsequentCallingPoints = subs,
            coachCount              = coachCount,
            formation               = coachCount?.let { "$it coaches" } ?: "",
            cancelReason            = firstTag(doc, "cancelReason"),
            delayReason             = firstTag(doc, "delayReason"),
            adhocAlerts             = alerts
        )
    }

    private fun extractFault(xml: String): String? = try {
        parseXml(xml).getElementsByTagNameNS("*", "faultstring")
            .let { if (it.length > 0) it.item(0).textContent else null }
    } catch (_: Exception) { null }
}
