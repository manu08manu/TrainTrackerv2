package com.traintracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * CORPUS location reference data.
 *
 * CORPUS maps between TIPLOC, STANOX, NLC, and CRS codes. It is the definitive
 * source for TIPLOC→CRS translation used by Darwin Push Port and TRUST messages.
 *
 * Loading strategy:
 *   1. If DATA_API_BASE_URL is set → fetch pre-processed data from the backend API
 *   2. Else if CORPUS_DOWNLOAD_URL is set and a cached copy exists (<7 days old) → use cache
 *   3. Else if CORPUS_DOWNLOAD_URL is set and no valid cache → download and cache
 *   4. Fallback: comprehensive built-in table covering all major stations
 */
object CorpusData {

    private const val TAG = "CorpusData"
    private const val CACHE_FILE = "corpus_cache.csv"
    private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

    @Volatile private var tiplocToCrs: Map<String, String>? = null
    @Volatile private var stanoxToCrs: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    // --- CRS alias map ----------------------------------------------------
    // Some stations have multiple CRS codes in CORPUS depending on which
    // platform or operator is used. This map normalises alternative CRS
    // codes to the canonical code used in uk_stations.json.
    // Add new entries here when additional aliases are discovered.
    private val CRS_ALIASES: Map<String, String> = mapOf(
        "SPL" to "STP",   // St Pancras International (Thameslink low-level)
        "ABX" to "ABW",   // Abbey Wood (Elizabeth Line)
        "STP" to "STP",   // canonical — included so reverse lookups work
        "ABW" to "ABW",
    )

    /** Resolve an alternative CRS to its canonical form, or return as-is. */
    fun canonicalCrs(crs: String): String = CRS_ALIASES[crs.uppercase()] ?: crs.uppercase()

    /** All CRS codes (including aliases) that refer to the given canonical CRS. */
    fun aliasesFor(canonicalCrs: String): Set<String> {
        val target = canonicalCrs.uppercase()
        return CRS_ALIASES.entries
            .filter { it.value == target }
            .map { it.key }
            .toSet()
    }

    // --- Public API -------------------------------------------------------

    fun crsFromTiploc(tiploc: String): String? {
        val raw = (tiplocToCrs ?: BUILT_IN_TIPLOC)[tiploc.uppercase()] ?: return null
        return canonicalCrs(raw)
    }

    fun crsFromStanox(stanox: String): String? {
        val raw = stanoxToCrs[stanox] ?: return null
        return canonicalCrs(raw)
    }

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext

        // Strategy 1: fetch from TrainTracker Data API (pre-processed by backend)
        val apiBase = Constants.DATA_API_BASE_URL
        if (apiBase.isNotEmpty()) {
            try {
                if (loadFromApi(apiBase)) {
                    Log.d(TAG, "Loaded CORPUS from Data API (${(tiplocToCrs ?: BUILT_IN_TIPLOC).size} entries)")
                    loaded = true
                    return@withContext
                }
            } catch (e: Exception) {
                Log.w(TAG, "Data API CORPUS load failed, falling back: ${e.message}")
            }
        }

        // Strategy 2: download raw CORPUS CSV directly (legacy path)
        val url = Constants.CORPUS_DOWNLOAD_URL
        if (url.isNotEmpty()) {
            try {
                val cacheFile = File(context.cacheDir, CACHE_FILE)
                if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_MAX_AGE_MS) {
                    loadFromCsv(cacheFile.readText())
                    Log.d(TAG, "Loaded CORPUS from cache (${(tiplocToCrs ?: BUILT_IN_TIPLOC).size} entries)")
                } else {
                    val csv = downloadCorpus(url)
                    if (csv.isNotEmpty()) {
                        cacheFile.writeText(csv)
                        loadFromCsv(csv)
                        Log.d(TAG, "Downloaded and cached CORPUS (${(tiplocToCrs ?: BUILT_IN_TIPLOC).size} entries)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CORPUS download failed, using built-in: ${e.message}")
            }
        } else {
            Log.d(TAG, "Using built-in CORPUS (${BUILT_IN_TIPLOC.size} entries). Set DATA_API_BASE_URL for full data.")
        }
        loaded = true
    }

    // --- API loader -------------------------------------------------------

    /**
     * Fetch all CORPUS entries from the backend Data API in a single bulk request.
     * The /api/v1/corpus/all endpoint returns every TIPLOC→CRS mapping as JSON.
     */
    private fun loadFromApi(apiBase: String): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val url = "${apiBase.trimEnd('/')}/api/v1/corpus/all"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return false
        val json = response.body?.string() ?: return false

        val tiploc = HashMap<String, String>(4096)
        val stanox = HashMap<String, String>(4096)
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val tiplocCode = obj.optString("tiploc", "").uppercase()
            val crs = obj.optString("crs", "").uppercase()
            val stanoxCode = obj.optString("stanox", "")
            if (crs.length == 3 && tiplocCode.isNotEmpty()) {
                tiploc[tiplocCode] = crs
                if (stanoxCode.isNotEmpty()) stanox[stanoxCode] = crs
            }
        }
        if (tiploc.isEmpty()) return false
        tiplocToCrs = tiploc
        stanoxToCrs = stanox
        return true
    }

    // --- CSV parser -------------------------------------------------------

    /**
     * Parses CORPUS CSV format (columns: TIPLOC, ABBREVIATION, STARTDATE, ENDDATE,
     * STANOX, UIC, CRS, DESCRIPTION, 16CHA, NLC).
     */
    private fun loadFromCsv(csv: String) {
        val tiploc = HashMap<String, String>(4096)
        val stanox  = HashMap<String, String>(4096)

        csv.lineSequence().drop(1).forEach { line ->
            val cols = line.split(',')
            if (cols.size < 7) return@forEach
            val tiplocCode = cols[0].trim().uppercase()
            val crs        = cols[6].trim().uppercase()
            val stanoxCode = cols[4].trim()
            if (crs.length == 3) {
                if (tiplocCode.isNotEmpty()) tiploc[tiplocCode] = crs
                if (stanoxCode.isNotEmpty()) stanox[stanoxCode] = crs
            }
        }

        if (tiploc.isNotEmpty()) {
            tiplocToCrs = tiploc
            stanoxToCrs = stanox
        }
    }

    // --- Download ---------------------------------------------------------

    private fun downloadCorpus(url: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            
            .build()
        val response = client.newCall(request).execute()
        val body     = response.body ?: return ""

        // CORPUS may be delivered as a ZIP
        return if (response.header("Content-Type")?.contains("zip") == true
            || url.endsWith(".zip")) {
            ZipInputStream(body.byteStream()).use { zip ->
                zip.nextEntry ?: return ""
                zip.bufferedReader().readText()
            }
        } else {
            body.string()
        }
    }

    // --- Built-in TIPLOC table -------------------------------------------
    // Sourced from publicly available CORPUS snapshots.
    // Covers all major GB stations. Update via CORPUS_DOWNLOAD_URL for complete data.

    private val BUILT_IN_TIPLOC: Map<String, String> = mapOf(
        // ── London termini ───────────────────────────────────────────────────
        "VICTRIA"  to "VIC", "VAUXHLM"  to "VXH",
        "WATRLMN"  to "WAT", "WATRLMJ"  to "WAT",
        "PADTON"   to "PAD", "PADNBRY"  to "PAD",
        "EUSTON"   to "EUS", "EUSTOM"   to "EUS",
        "LIVST"    to "LST", "LIVSTLL"  to "LST",
        "KNGX"     to "KGX", "KNGXMIDL" to "KGX",
        "STPX"     to "STP", "STPXBS"   to "STP",
        "CHARROX"  to "CHX", "CHARXR"   to "CHX",
        "FENCHSST" to "FST",
        "CANNON"   to "CST", "CANNST"   to "CST",
        "MARGXR"   to "MYB", "MRDNBCRT" to "MYB",
        "CLPHMJC"  to "CLJ", "CLPHMJM"  to "CLJ", "CLPHMJW" to "CLJ",
        "LNDNBDG"  to "LBG", "LNDNBDGE" to "LBG",
        "BLFRSGT"  to "BAL", "CRYDNRJ"  to "ECR",

        // ── London commuter ──────────────────────────────────────────────────
        "WATFDJ"   to "WFJ", "STALBCY"  to "SAC",
        "HRPNDNX"  to "HPD", "BRXTN"    to "BRX",
        "SURBITN"  to "SUR", "WMBLDN"   to "WIM",
        "RICHMND"  to "RMD", "TWCKNHM"  to "TWI",
        "KGSTNU"   to "KNG", "HMPTNCT"  to "HMC",
        "ESHRJ"    to "ESH", "GUILDFD"  to "GLD",
        "WOKNGHM"  to "WOK", "BSNGSTK"  to "BSK",
        "WNDSR"    to "WNS", "MADENHED" to "MAI",
        "SLOU"     to "SLO", "HAYES"    to "HYS",
        "UXBRDGE"  to "UXB", "HLNGDN"   to "HLN",
        "STNSFD"   to "STN", "CRWLY"    to "CRW",
        "GTWK"     to "GTW", "HRSHAM"   to "HRH",
        "BRIGHTN"  to "BTN", "HSTINGS"  to "HGS",
        "STVNG"    to "SVG", "EASTBN"   to "EBN",
        "LEWES"    to "LWS", "FLKSTON"  to "FKS",
        "ASHFRD"   to "AFK", "FOLKRHS"  to "AFG",
        "DVRPRS"   to "DVP", "CTRBRYC"  to "CBW",
        "CTRBURE"  to "CBE", "MARGATE"  to "MAR",
        "RAMSGTE"  to "RAM", "STTNGBRN" to "STU",
        "MDSTNEW"  to "MDE", "TONBDGE"  to "TBW",
        "SEVENOAL" to "SEV", "BKNGHMJ"  to "BKJ",
        "OXTED"    to "OXT", "CRSYDNJ"  to "CRY",
        "MRDN"     to "MRD", "STHLDNJ"  to "SHJ",
        "EPSOM"    to "EPS", "DRKNG"    to "DKG",
        "LTHRHD"   to "LHD", "REIGATE"  to "REI",
        "RDHL"     to "RDH", "OXSHOTT"  to "OXS",

        // ── South East & Eastern ─────────────────────────────────────────────
        "CLCHSTR"  to "COL", "IPSWICH"  to "IPS",
        "FRIXTN"   to "FXT", "NORWICH"  to "NRW",
        "NORWCHGR" to "NRW", "GRTSRM"   to "GYM",
        "CAMBDGE"  to "CBG", "PTRBRH"   to "PBO",
        "HNTN"     to "HUN", "ELYBDGS"  to "ELY",
        "KNGSLNN"  to "KLN", "THETFRD"  to "TTF",
        "HARWICH"  to "HWC", "CLCTN"    to "CIT",
        "WSTRCLF"  to "WCF", "SWNDPLN"  to "STP",
        "STORTFD"  to "BIS", "BRNTWD"   to "BRE",
        "CHMSFD"   to "CHM", "SCHN"     to "SCE",

        // ── Midlands ────────────────────────────────────────────────────────
        "BRMNGM"   to "BHM", "BRMNGMS"  to "BHM",
        "CNVNTRY"  to "COV", "COVNTRY"  to "COV",
        "RUGBY"    to "RUG", "MNTNRJ"   to "MKC",
        "MLTNCNJ"  to "MKC", "LSTRJ"    to "LEI",
        "LCITWYGD" to "LEI", "DERBYJ"   to "DBY",
        "DRBY"     to "DBY", "NTTM"     to "NOT",
        "NTTNGM"   to "NOT", "STNTNRD"  to "STA",
        "SHRWSBY"  to "SHR", "TELFDRJ"  to "TFD",
        "HRFRD"    to "HFD", "GLSTR"    to "GCR",
        "CHELTNH"  to "CNM", "WORCR"    to "WOF",
        "KIDDRM"   to "KID", "BRMNGMNS" to "BHI",
        "BRMNGMM"  to "BMO", "WLVRHMP"  to "WVH",
        "WLSLL"    to "WSL", "DDBRY"    to "DDY",
        "STRBGJ"   to "SAA", "TAMWRTH"  to "TAM",
        "LGHBRGH"  to "LBO", "NRWCHTC"  to "NWT",
        "LNCOLN"   to "LCN", "GRMSB"    to "GMB",
        "BSTNLN"   to "BSN", "SKGNSS"   to "SKN",

        // ── North West ──────────────────────────────────────────────────────
        "MNCRIAP"  to "MAN", "MNCRPIC"  to "MAN",
        "MNCROXD"  to "MAN", "MANCHSTR" to "MAN",
        "LIVRLST"  to "LIV", "LIVRPAL"  to "LIV",
        "LIVRCNTL" to "LVC", "BLCKPL"   to "BPN",
        "BLCKPLNS" to "BPS", "PRSTON"   to "PRE",
        "WGNWLGT"  to "WGN", "WGNSTNC"  to "WGS",
        "BLTN"     to "BLN", "RCHDALE"  to "RCD",
        "SLFD"     to "SFD", "STCKPRT"  to "SPT",
        "CHSTR"    to "CTR", "CRWE"     to "CRE",
        "WRNTN"    to "WAR", "RNCORN"   to "RUN",
        "MCLSFLD"  to "MAC", "STKPRT"   to "SPT",
        "MNCRDFRJ" to "MCO", "OLDHM"    to "OLM",
        "BLTCHR"   to "BCH", "ACCRGTN"  to "ACC",
        "BLCKBRN"  to "BBN", "DRWN"     to "DWN",
        "LNCSSTR"  to "LAN", "HRCCSTL"  to "HEC",
        "BRRW"     to "BIF", "KENDL"    to "KND",
        "LNCSTNR"  to "LCR", "PENRTH"   to "PNR",
        "CARLILE"  to "CAR", "CARLISL"  to "CAR",

        // ── Yorkshire & North East ───────────────────────────────────────────
        "LEEDSCEN" to "LDS", "LEEDS"    to "LDS",
        "SHEFFLD"  to "SHF", "DONCASTR" to "DON",
        "WKFLDKR"  to "WKF", "WKFLDWG"  to "WKK",
        "BRDFD"    to "BDQ", "BRDFDFL"  to "BDQ",
        "HRGT"     to "HGT", "SKLMRS"   to "SKI",
        "BRNGLY"   to "BGY", "HDRSFLD"  to "HUD",
        "DNCSTR"   to "DON", "RTHRM"    to "RHM",
        "BRNSLEY"  to "BNY", "YORK"     to "YRK",
        "SCRBR"    to "SCA", "SBRGH"    to "SBH",
        "DRSNGTN"  to "DAR", "NWCSTLE"  to "NCL",
        "NWCSTLAJ" to "NCL", "NWCSTLAM" to "NCL",
        "SUNDERL"  to "SUN", "DRHM"     to "DHM",
        "HTLPL"    to "HPL", "MDLSBRO"  to "MBR",
        "YBRGJ"    to "YRK", "SLTBRN"   to "SBN",
        "BVRL"     to "BEV", "HLLPRVT"  to "HUP",
        "HULL"     to "HUL", "GRMSHY"   to "GMY",
        "CLTHRPS"  to "CLT", "SCRPSBR"  to "SBR",

        // ── Scotland ────────────────────────────────────────────────────────
        "GLGC"     to "GLC", "GLGCGLS"  to "GLC",
        "GLGQST"   to "GLQ", "GLGCAL"   to "GAL",
        "GLGCBCH"  to "GBL", "EDIMBRO"  to "EDB",
        "EDNGBHP"  to "EDB", "HYRKT"    to "HYM",
        "MRYHLL"   to "MYH", "PRTGLS"   to "PTG",
        "RNFREW"   to "RFW", "PSLEY"    to "PSL",
        "GRNGM"    to "GRG", "LNRK"     to "LNK",
        "MTHWL"    to "MTH", "ABDNJ"    to "ABD",
        "ABDN"     to "ABD", "DNDE"     to "DEE",
        "STRLNG"   to "STG", "FALKRK"   to "FKK",
        "PERTH"    to "PTH", "INVNSS"   to "INV",
        "KRKCLDY"  to "KDY", "CRFRRSH"  to "CUF",
        "HNSTMNS"  to "HYS", "AVMRE"    to "AVM",
        "FORFR"    to "FOR", "MNTRS"    to "MTS",
        "AYRJ"     to "AYR", "KILMRNK"  to "KMK",
        "TROON"    to "TRN", "DUMFRS"   to "DMF",

        // ── Wales ────────────────────────────────────────────────────────────
        "CRDFCEN"  to "CDF", "CRDFQST"  to "CDF",
        "SWANSEA"  to "SWA", "SWNSEA"   to "SWA",
        "NWPRTJM"  to "NWP", "BRYSTHM"  to "NWP",
        "PWLLHLI"  to "PWL", "BNGR"     to "BNG",
        "MLDTNJ"   to "MAC", "WRMSHD"   to "WRX",
        "WRMSD"    to "WRX", "ABRSTWYF" to "AHV",
        "CARDGNRJ" to "CDQ", "MTHRTYDL" to "MTD",
        "RHYMNJ"   to "RHY",

        // ── South West & West of England ─────────────────────────────────────
        "BRSTLTM"  to "BRI", "BRSTLPWY" to "BPW",
        "BRSTLFSJ" to "BRI", "BTHSPA"   to "BTH",
        "WSTRYSP"  to "WSP", "TROWBDG"  to "TRO",
        "CHIPNHM"  to "CPM", "SVRNTNL"  to "SVB",
        "RDNGSTN"  to "RDG", "RDNGSTNE" to "RDG",
        "SWNDN"    to "SWI", "OXFD"     to "OXF",
        "OXFDRDG"  to "OXF", "BNBRY"    to "BAN",
        "LMNGTN"   to "LMS", "STRTFDU"  to "SAV",
        "HRMRSMTH" to "HMM", "EXETCEN"  to "EXC",
        "EXETST"   to "EXD", "EXETQY"   to "EXD",
        "EXMNSTR"  to "EXM", "DAWLSH"   to "DWL",
        "TGNMTH"   to "TGM", "PAIGNTN"  to "PGN",
        "TORQUAY"  to "TQY", "NWTN ABT" to "NTA",
        "PLYMTH"   to "PLY", "PLYMTHS"  to "PLY",
        "TRURO"    to "TRU", "PENZNCE"  to "PNZ",
        "STIVES"   to "SIS", "SLSBRY"   to "SAL",
        "BMTH"     to "BMH", "POOLE"    to "POO",
        "WBRNMTH"  to "WBN", "SWANAGE"  to "SWG",
        "SOTON"    to "SOU", "SOTONAE"  to "SOA",
        "STNC"     to "SOU", "SOTONEA"  to "SOA",
        "PORSMTH"  to "PMS", "PORSMTHH" to "PMH",
        "HAVNT"    to "HAV", "CHCSTR"   to "CCH",
        "BOGNRGJ"  to "BOG", "WTHMPTN"  to "WOF",
        "YRKMSTR"  to "YAE", "WKMTHS"   to "WCS",
        "WNDMRE"   to "WDS", "BRKNHD"   to "BKQ",
        "SLNGER"   to "SLD", "ANGMRNG"  to "ANG",

        // ── East Midlands / East ─────────────────────────────────────────────
        "PTRBRHS"  to "PBO", "PTRBRHES" to "PBO",
        "SNGHM"    to "SNO", "LGHTN"    to "LGT",
        "CRWLYCJ"  to "CWC", "BDFRD"    to "BDF",
        "BDFD"     to "BDF", "LTNI"     to "LUT",
        "DUNSTBL"  to "DUS", "HRPNDN"   to "HPD",
        "WTFD"     to "WFJ", "HMLHMPST" to "HML",
        "STALBNS"  to "SAA", "WLNGRDB"  to "WGC",
        "HTFRD"    to "HFD", "BSNGSTK"  to "BSK",

        // ── Airport stations ─────────────────────────────────────────────────
        "GATWKAIR" to "GTW", "HTHRWT1"  to "HAP",
        "HTHRWT2"  to "HXX", "STNDAIR"  to "SSD",
        "MNCHSAIR" to "MIA", "BRMAIR"   to "BHI",
        "LTNAIRP"  to "LTN", "ABLRDEEN" to "ABD",
        "EDNBURGR" to "EDB"
    )
}
