package com.traintracker

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetches and caches data from Network Rail's Knowledge Base (KB) API,
 * proxied through the TrainTracker server.
 *
 * The KB exposes three datasets used by this app:
 *
 *   • **Incidents** — Live disruption notices (e.g. "Signalling fault between
 *     X and Y").  Displayed as a banner on the station board when relevant.
 *
 *   • **NSI** (Network Service Information) — Per-operator service status,
 *     broadly equivalent to the coloured dots on National Rail Enquiries.
 *     Used to show a status badge next to each TOC's results.
 *
 *   • **TOC details** (Train Operating Company) — Branding metadata for each
 *     operator: full name, brand colour, and logo resource.  Keyed by the
 *     two-letter ATOC code (e.g. "VT" = Avanti West Coast).
 *
 * All three datasets are cached with a 5-minute TTL so that navigating between
 * screens doesn't trigger redundant network calls.
 */
class KbRepository(
    private val server: ServerApiClient,
    private val scope: CoroutineScope
) {
    // 5-minute TTL — KB data changes infrequently; no need to hammer the proxy.
    private val ttlMs = 5 * 60 * 1_000L

    private val _incidents = MutableStateFlow<List<KbIncident>>(emptyList())
    /** Live disruption notices from the KB. Empty until [fetchIncidents] succeeds. */
    val incidents: StateFlow<List<KbIncident>> = _incidents.asStateFlow()

    private val _nsi = MutableStateFlow<List<KbNsiEntry>>(emptyList())
    /** Per-operator service status entries.  Empty until [fetchNsi] succeeds. */
    val nsi: StateFlow<List<KbNsiEntry>> = _nsi.asStateFlow()

    private val _tocDetails = MutableStateFlow<Map<String, KbTocEntry>>(emptyMap())
    /**
     * TOC metadata keyed by uppercase ATOC code (e.g. "VT", "GW").
     * Empty until [fetchTocDetails] succeeds; populated once and never re-fetched
     * (operator branding does not change at runtime).
     */
    val tocDetails: StateFlow<Map<String, KbTocEntry>> = _tocDetails.asStateFlow()

    // Timestamps used for TTL enforcement.
    private var incidentsFetchedAt = 0L
    private var nsiFetchedAt       = 0L

    /**
     * Fetches live incidents from the KB proxy, unless the cached copy is
     * less than 5 minutes old.  Failures are silently swallowed — stale or
     * absent incident banners are a graceful degradation, not an error.
     */
    fun fetchIncidents() {
        if (System.currentTimeMillis() - incidentsFetchedAt < ttlMs) return
        scope.launch {
            try {
                _incidents.value = withContext(Dispatchers.IO) { server.getKbIncidents() }
                incidentsFetchedAt = System.currentTimeMillis()
            } catch (_: Exception) { /* non-critical — board still shows without banner */ }
        }
    }

    /**
     * Fetches NSI operator status entries, with the same 5-minute TTL and
     * silent-failure policy as [fetchIncidents].
     */
    fun fetchNsi() {
        if (System.currentTimeMillis() - nsiFetchedAt < ttlMs) return
        scope.launch {
            try {
                _nsi.value = withContext(Dispatchers.IO) { server.getKbNsi() }
                nsiFetchedAt = System.currentTimeMillis()
            } catch (_: Exception) { /* non-critical */ }
        }
    }

    /**
     * Loads TOC branding details once per app session.  Subsequent calls are
     * no-ops if the map is already populated.
     */
    fun fetchTocDetails() {
        if (_tocDetails.value.isNotEmpty()) return
        scope.launch(Dispatchers.IO) {
            val list = server.getKbToc()
            if (list.isNotEmpty()) {
                _tocDetails.value = list.associateBy { it.code.uppercase() }
                Log.d("KbRepository", "TOC details loaded: ${list.size} entries")
            }
        }
    }

    /**
     * Returns the NSI status entry for [operatorCode] (a two-letter ATOC code
     * such as "GW" or "VT"), or null if no entry exists or NSI hasn't loaded yet.
     */
    fun nsiForOperator(operatorCode: String): KbNsiEntry? {
        if (operatorCode.isEmpty()) return null
        return _nsi.value.firstOrNull { it.tocCode.equals(operatorCode, ignoreCase = true) }
    }
}
