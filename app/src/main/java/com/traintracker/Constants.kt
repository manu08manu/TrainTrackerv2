package com.traintracker

/**
 * App-wide constants and credentials.
 *
 * ── How to obtain credentials ──────────────────────────────────────────────
 *
 * OpenLDBWS (departure boards):
 *   Register at: https://realtime.nationalrail.co.uk/OpenLDBWSRegistration/
 *   → Set LDB_TOKEN below
 *
 * Darwin Push Port (real-time updates via STOMP):
 *   Credentials from your Darwin Push Port subscription email.
 *   → Set DARWIN_USERNAME and DARWIN_PASSWORD below
 *
 * Knowledgebase API (incidents, stations):
 *   From your Rail Data Marketplace or NRDP dashboard.
 *   → Set KB_API_KEY below
 *   → Endpoint varies: use the URL shown in your RDM subscription detail,
 *     or the NRDP default (https://api.nationalrail.co.uk) if you have NRDP access.
 *
 * CORPUS (TIPLOC→CRS lookup):
 *   Available from RDM as a file. Set CORPUS_DOWNLOAD_URL to the endpoint
 *   shown in your RDM subscription, or leave blank to use bundled snapshot.
 *
 * NWR Train Movements (TRUST via Kafka):
 *   RDM provides this via Apache Kafka — not directly consumable on Android.
 *   Set TRUST_WS_URL to a backend WebSocket relay you run separately.
 *   See: https://wiki.openraildata.com/index.php/Train_Movements
 */
object Constants {

    // ── OpenLDBWS ──────────────────────────────────────────────────────────
    const val LDB_TOKEN = ""

    // ── Darwin Push Port (STOMP/TCP) ───────────────────────────────────────
    const val DARWIN_USERNAME = ""
    const val DARWIN_PASSWORD = ""

    // ── Knowledgebase API ──────────────────────────────────────────────────
    // Each subscription has its own Consumer key — find it on raildata.org.uk
    // under your subscription → "API access credentials" → Consumer key.
    // Auth header: x-apikey

    // Knowledgebase National Service Indicator (XML)
    const val KB_NSI_KEY = ""
    const val KB_NSI_URL = ""

    // Knowledgebase Incidents (XML)
    const val KB_INCIDENTS_KEY = ""
    const val KB_INCIDENTS_URL = ""

    // NationalRail Knowledgebase Stations (JSON) — returns all stations
    const val KB_STATIONS_KEY = ""
    const val KB_STATIONS_URL = ""

    // ── CORPUS (TIPLOC/STANOX → CRS) ──────────────────────────────────────
    // Optional: URL to download fresh CORPUS from your RDM subscription.
    // Leave empty to use the bundled snapshot only.
    const val CORPUS_DOWNLOAD_URL = ""

    // ── NWR Train Movements / TRUST (Kafka relay) ─────────────────────────
    // TRUST arrives via Apache Kafka on RDM. Point this at a backend WebSocket
    // relay that consumes from Kafka and forwards TRUST JSON to the app.
    // Leave empty to disable TRUST (Darwin real-time will still work).
    const val TRUST_BOOTSTRAP = ""
    const val TRUST_USERNAME = ""
    const val TRUST_PASSWORD = ""
}
