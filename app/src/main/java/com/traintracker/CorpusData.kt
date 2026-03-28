package com.traintracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * CORPUS location reference data.
 *
 * CORPUS maps between TIPLOC, STANOX, NLC, and CRS codes. It is the definitive
 * source for TIPLOC->CRS translation used by Darwin Push Port and TRUST messages.
 *
 * Loading strategy:
 *   1. Load CORPUSExtract JSON from assets (corpus.json) ~3,700 TIPLOC->CRS mappings
 *   2. Layer the built-in table on top to fill gaps (e.g. Clapham Junction variants
 *      that have no 3ALPHA in the official CORPUS JSON)
 *
 * To update: replace corpus.json in app/src/main/assets/ and rebuild.
 */
object CorpusData {

    private const val TAG = "CorpusData"
    private const val ASSET_NAME = "corpus.json"

    @Volatile private var tiplocToCrs: Map<String, String> = emptyMap()
    @Volatile private var tiplocToName: Map<String, String> = emptyMap()
    @Volatile private var crsToTiplocs: Map<String, List<String>> = emptyMap()  // reverse: CRS → [TIPLOCs]
    @Volatile private var loaded = false
    val isReady: Boolean get() = loaded

    // --- Public API -------------------------------------------------------

    fun crsFromTiploc(tiploc: String): String? = tiplocToCrs[tiploc.uppercase()]

    /**
     * Returns all TIPLOCs that map to [crs] — used to widen DB queries so services
     * whose TIPLOCs are unresolved (stored as crs="") are still found.
     */
    fun tiplocsByCrs(crs: String): List<String> = crsToTiplocs[crs.uppercase()] ?: emptyList()

    /**
     * Returns a human-readable name for a TIPLOC that has no CRS code —
     * e.g. "Wimbledon Park C.S.D." instead of "WDONCSD".
     * Sourced from NLCDESC in the CORPUS JSON.
     */
    fun nameFromTiploc(tiploc: String): String? = tiplocToName[tiploc.uppercase()]

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        try {
            val json = context.assets.open(ASSET_NAME).bufferedReader().readText()
            loadFromJson(json)
            Log.d(TAG, "Loaded CORPUS from asset: ${tiplocToCrs.size} TIPLOC entries")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load CORPUS asset: ${e.message} — using built-in table")
            tiplocToCrs = BUILT_IN_TIPLOC
            val reverse = HashMap<String, MutableList<String>>(500)
            for ((t, c) in BUILT_IN_TIPLOC) reverse.getOrPut(c) { mutableListOf() }.add(t)
            crsToTiplocs = reverse
            Log.d(TAG, "Using built-in CORPUS (${BUILT_IN_TIPLOC.size} entries)")
        }
        loaded = true
    }

    // --- JSON loader ------------------------------------------------------

    private fun loadFromJson(json: String) {
        val tiploc = HashMap<String, String>(6000)

        val name   = HashMap<String, String>(6000)

        val arr = JSONObject(json).getJSONArray("TIPLOCDATA")
        for (i in 0 until arr.length()) {
            val obj        = arr.getJSONObject(i)
            val tiplocCode = obj.optString("TIPLOC").trim().uppercase()
            val crs        = obj.optString("3ALPHA").trim().uppercase()
            val desc       = obj.optString("NLCDESC").trim()
            if (tiplocCode.isNotEmpty()) {
                if (crs.length == 3) {
                    tiploc[tiplocCode] = crs
                }
                // Store NLCDESC as fallback display name for TIPLOCs with no CRS
                if (desc.isNotEmpty() && desc != " ") {
                    name[tiplocCode] = desc.split(" ").joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar { it.uppercase() }
                    }
                }
            }
        }

        // Layer built-in table on top — it covers variants (e.g. CLPHMJC, CLPHMJM,
        // CLPHMJW) that have no 3ALPHA in the official CORPUS JSON.
        tiploc.putAll(BUILT_IN_TIPLOC)

        tiplocToCrs  = tiploc
        tiplocToName = name

        // Build reverse map: CRS → list of all TIPLOCs that map to it
        val reverse = HashMap<String, MutableList<String>>(2000)
        for ((t, c) in tiploc) {
            reverse.getOrPut(c) { mutableListOf() }.add(t)
        }
        crsToTiplocs = reverse
    }

    // --- Built-in TIPLOC table -------------------------------------------
    // Only contains entries NOT present in the CORPUS JSON asset (corpus.json).
    // The CORPUS JSON covers ~3,700 entries; this table adds ~280 secondary
    // platform/junction TIPLOCs that the official CORPUS lacks CRS codes for.

    private val BUILT_IN_TIPLOC: Map<String, String> = mapOf(
        // ── London termini & variants ─────────────────────────────────────────
        "VICTRA"   to "VIC",   // Victoria — secondary TIPLOCs confirmed from railwaycodes.org.uk:
        "VICTRIC"  to "VIC",   //   Central platforms
        "VICTRIE"  to "VIC",   //   Eastern platforms
        "VAUXHLM"  to "VXH",
        "VAUXHLW"  to "VXH",   // Vauxhall Windsor Lines
        "WATRLMN"  to "WAT", "WATRLMJ"  to "WAT",
        "WATRLNJL" to "WAT",   // Waterloo Jubilee Line junction
        "PADNBRY"  to "PAD", "EUSTOM"   to "EUS",
        "KNGXMIDL" to "KGX", "STPXBS"   to "STP",
        // St Pancras station group — all TIPLOCs mapped to STP so all services appear together:
        //   STPX     = domestic (EMR, high level) — already in CORPUS
        //   STPXBOX  = low level / Thameslink platforms (CRS SPL in CORPUS)
        //   STPANCI  = international platforms — SE HS1 domestic + Eurostar (CRS SPX in CORPUS)
        //   STPADOM  = international domestic (no CRS in CORPUS)
        "STPXBOX"  to "STP",   // SPL → STP: Thameslink low level
        "STPANCI"  to "STP",   // SPX → STP: SE HS1 + Eurostar
        "STPADOM"  to "STP",   // SPX domestic → STP
        // Ashford (Kent): SE HS1 domestic trains use platforms 3-4 (ASHFKI/ASI) not the main CRS
        "ASHFKI"   to "AFK",   // Ashford Int platforms 3-4 → Ashford International
        // Glasgow Central: Argyle Line low-level platforms (GLGCLL/GCL) are same physical station
        "GLGCLL"   to "GLC",   // Glasgow Central Low Level → Glasgow Central
        // Lichfield Trent Valley: Chase Line (High Level) has a separate TIPLOC
        "LCHTTVH"  to "LTV",   // Lichfield TV High Level → Lichfield Trent Valley
        // Liverpool Lime Street: Merseyrail loop low-level platforms
        "LVRPLSL"  to "LIV",   // Liverpool Lime St Low Level → Liverpool Lime Street
        // Reading: TfW/GWR slow platforms 4A & 4B
        "RDNG4AB"  to "RDG",   // Reading platforms 4A&B → Reading
        // Tamworth: two-level station — High Level (Cross-City) has separate TIPLOC
        "TMWTHHL"  to "TAM",   // Tamworth High Level → Tamworth
        // Willesden Junction: High Level (Overground) and Low Level share same station board
        "WLSDJHL"  to "WIJ",   // Willesden Junction High Level → Willesden Junction
        "WLSDNJL"  to "WIJ",   // Willesden Junction Low Level → Willesden Junction
        "CHARROX"  to "CHX", "CHARXR"   to "CHX",
        "FENCHSST" to "FST", "CANNON"   to "CST",
        "CANNST"   to "CST", "MARGXR"   to "MYB",
        "MRDNBCRT" to "MYB", "CLPHMJC"  to "CLJ",
        "CLPHMJM"  to "CLJ", "CLPHMJW"  to "CLJ",
        "LNDNBDGE" to "LBG",   // London Bridge (built-in fallback)
        "LNDNBDG"  to "LBG",   // London Bridge (main)
        "LNDNBDC"  to "LBG",   // London Bridge Central (platforms 14-16, Thameslink)
        "LNDNBDE"  to "LBG",   // London Bridge Eastern (Southeastern platforms)
        "LNDNB9"   to "LBG",   // London Bridge platform 9
        "LNDNB10"  to "LBG",   // London Bridge platform 10
        "LNDNB11"  to "LBG",   // London Bridge platform 11
        "LNDNB12"  to "LBG",   // London Bridge platform 12
        "LNDNB13"  to "LBG",   // London Bridge platform 13
        "LNDNB14"  to "LBG",   // London Bridge platform 14
        "LNDNB15"  to "LBG",   // London Bridge platform 15
        "LNDNB16"  to "LBG",   // London Bridge platform 16
        "BLFRSGT"  to "BAL",
        "LVRPLST"  to "LST",   // Liverpool Street main — in CORPUS but add as fallback
        "LIVSTXR"  to "LST",   // Liverpool Street Elizabeth line / Crossrail
        "CRYDNRJ"  to "ECR",

        // ── London commuter ──────────────────────────────────────────────────
        "RADLET"   to "RDL", "RADLETJ"  to "RDL",
        "HRPNDNX"  to "HPD", "BRXTN"    to "BRX",
        "WMBLDN"   to "WIM", "KGSTNU"   to "KNG",
        "HMPTCT"   to "HMC", "ESHRJ"    to "ESH",
        "WNDSR"    to "WNS", "MADENHED" to "MAI",
        "SLOU"     to "SLO", "HAYES"    to "HYS",
        "UXBRDGE"  to "UXB", "HLNGDN"   to "HLN",
        "STNSFD"   to "STN", "CRWLY"    to "CRW",
        "HRSHM"    to "HRH", "BRIGHTN"  to "BTN",
        "HSTINGS"  to "HGS", "STVNG"    to "SVG",
        "EASTBN"   to "EBN", "FLKSTON"  to "FKS",
        "ASHFRD"   to "AFK", "FOLKRHS"  to "AFG",
        "DVRPRS"   to "DVP", "CTRBRYC"  to "CBW",
        "CTRBURE"  to "CBE", "RAMSGFE"  to "RAM",
        "STTNGBRN" to "STU", "MDSTEW"   to "MDE",
        "TONBDGE"  to "TBW", "SEVENOAL" to "SEV",
        "BKNGHM"   to "BKJ", "CRSYDNJ"  to "CRY",
        "MRDN"     to "MRD", "STHLNJ"   to "SHJ",
        "EPSOM"    to "EPS", "DRKNG"    to "DKG",
        "LTHRH"    to "LHD", "RDHL"     to "RDH",

        // ── South East & Eastern ─────────────────────────────────────────────
        "FRIXTN"   to "FXT", "NORWICH"  to "NRW",
        "NORWCHGR" to "NRW", "GRTSRM"   to "GYM",
        "PTRBH"    to "PBO", "HNTN"     to "HUN",
        "ELYBDGS"  to "ELY", "KNGSLNN"  to "KLN",
        "THETFRD"  to "TTF", "CLCTN"    to "CIT",
        "WSTRCF"   to "WCF", "SWNDPLN"  to "SPL",
        "STORTFD"  to "BIS", "BRNWTD"   to "BRE",
        "CHMSFD"   to "CHM", "SCHN"     to "SCE",

        // ── Midlands ────────────────────────────────────────────────────────
        "BRMNM"    to "BHM", "BRMNMS"   to "BHM",
        "CNVNTRY"  to "COV", "MNTNRJ"   to "MKC",
        "MLTNCNJ"  to "MKC", "LSTRJ"    to "LEI",
        "LCITWYGD" to "LEI", "DERBYJ"   to "DBY",
        "NTTM"     to "NOT", "NTTNGM"   to "NOT",
        "STNTNRD"  to "STA", "SHRWSBY"  to "SHR",
        "TELFDRJ"  to "TFD", "HRFRD"    to "HFD",
        "GLSTR"    to "GCR", "CHELTN"   to "CNM",
        "WORCR"    to "WOF", "KIDDRM"   to "KID",
        "BRMNMNS"  to "BHI", "BRMNMM"   to "BMO",
        "WLVRHMP"  to "WVH", "WLSLL"    to "WSL",
        "DDBURY"   to "DDY", "STRBGJ"   to "SAA",
        "TAMWRTH"  to "TAM", "LGHBRGH"  to "LBO",
        "NRWCHTC"  to "NWT", "LNCOLN"   to "LCN",
        "GRMSB"    to "GMB", "BSTNLN"   to "BSN",
        "SKGNSS"   to "SKN",

        // ── Midland Main Line ────────────────────────────────────────────────
        "BEDFM"    to "BDM", "BEDFDS"   to "BDM",
        "WLNBGH"   to "WBQ", "KTTN"     to "KTN",
        "MKTHRB"   to "MKR", "LSTRA"    to "LST",
        "WIGSTON"  to "WGS", "LUFBRO"   to "LBO",
        "NPTNJ"    to "NMP", "NPTN"     to "NMP",
        "WLSDN"    to "WLN", "HRLGN"    to "HLN",

        // ── North West ──────────────────────────────────────────────────────
        "MNCRIP"   to "MAN", "MNCOXD"   to "MAN",
        "MANCHSTR" to "MAN", "LIVRLST"  to "LIV",
        "LIVRPAL"  to "LIV", "LIVRCNTL" to "LVC",
        "BLCKPL"   to "BPN", "BLCKPLNS" to "BPS",
        "PRSTN"    to "PRE", "WGNWLGT"  to "WGN",
        "WGNSNC"   to "WGS", "BLTN"     to "BLN",
        "SLFD"     to "SFD", "STCKPRT"  to "SPT",
        "CHSTR"    to "CTR", "CRWE"     to "CRE",
        "WRNTN"    to "WAR", "RNCORN"   to "RUN",
        "MCLSFD"   to "MAC", "STKPRT"   to "SPT",
        "MNCDRFJ"  to "MCO", "OLDHM"    to "OLM",
        "BLTCHR"   to "BCH", "ACCRGTN"  to "ACC",
        "BLCKBRN"  to "BBN", "DRWN"     to "DWN",
        "LNCSSTR"  to "LAN", "HRCCSTL"  to "HEC",
        "BRRW"     to "BIF", "KENDL"    to "KND",
        "LNCSTR"   to "LCR", "PENRTH"   to "PNR",
        "CARLISL"  to "CAR",

        // ── Yorkshire & North East ───────────────────────────────────────────
        "LEEDSEEN" to "LDS", "DONCASR"  to "DON",
        "WKFLDKR"  to "WKF", "BRFD"     to "BDQ",
        "BRFDFL"   to "BDQ", "HRGT"     to "HGT",
        "SKLMRS"   to "SKI", "BRNLY"    to "BGY",
        "HDRSFD"   to "HUD", "DNCSTR"   to "DON",
        "RTHRM"    to "RHM", "BRNSLEY"  to "BNY",
        "SCRBR"    to "SCA", "SBRGH"    to "SBH",
        "DRSNTN"   to "DAR", "NWCSTLAJ" to "NCL",
        "NWCSTLAM" to "NCL", "SUNDERL"  to "SUN",
        "HTLPL"    to "HPL", "YBRJ"     to "YRK",
        "SLTBRN"   to "SBN", "BVRL"     to "BEV",
        "HLLPRVT"  to "HUP", "GRMSY"    to "GMY",
        "SCRPSB"   to "SBR",

        // ── Scotland ────────────────────────────────────────────────────────
        "GLGCGLS"  to "GLC", "GLGQST"   to "GLQ",
        "GLGCAL"   to "GAL", "GLGCBCH"  to "GBL",
        "EDIMBRO"  to "EDB", "EDNBGHP"  to "EDB",
        "HYRKT"    to "HYM", "MRYHLL"   to "MYH",
        "PRTGLS"   to "PTG", "RNFREW"   to "RFW",
        "PSLEY"    to "PSL", "GRNGM"    to "GRG",
        "LNRK"     to "LNK", "MTHWL"    to "MTH",
        "ABDNJ"    to "ABD", "ABDN"     to "ABD",
        "DNDE"     to "DEE", "STRLNG"   to "STG",
        "FLKRK"    to "FKK", "INVNSS"   to "INV",
        "KRKCDY"   to "KDY", "CRFRRSH"  to "CUF",
        "HNSTMNS"  to "HYS", "AVMRE"    to "AVM",
        "FORFR"    to "FOR", "MNTRS"    to "MTS",
        "AYRJ"     to "AYR", "DUMFRS"   to "DMF",

        // ── Wales ────────────────────────────────────────────────────────────
        "CRDFQST"  to "CDF", "SWNSEA"   to "SWA",
        "NWPRTM"   to "NWP", "BRYSTM"   to "NWP",
        "PWLLHLI"  to "PWL", "BNGR"     to "BNG",
        "MLDTNJ"   to "MAC", "WRMSHD"   to "WRX",
        "WRMSD"    to "WRX", "ABRSTWF"  to "AHV",
        "CARDGNRJ" to "CDQ", "MTHRTYDL" to "MTD",
        "RHYMNJ"   to "RHY",

        // ── South West & West of England ─────────────────────────────────────
        "BRSTLPWY" to "BPW", "BRSTLFSJ" to "BRI",
        "BTHSPA"   to "BTH", "WSTRSP"   to "WSP",
        "TROWBDG"  to "TRO", "SVRNTNL"  to "SVB",
        "RDNGTN"   to "RDG", "RDNGSTNE" to "RDG",
        "SWNDN"    to "SWI", "OXFDRDG"  to "OXF",
        "BNBRY"    to "BAN", "LMNGTN"   to "LMS",
        "STRTFDU"  to "SAV", "HRMRTH"   to "HMM",
        "EXETCEN"  to "EXC", "EXETST"   to "EXD",
        "EXETQY"   to "EXD", "EXMNSTR"  to "EXM",
        "DAWLSH"   to "DWL", "TGNMTH"   to "TGM",
        "PLYMTHS"  to "PLY", "BMTH"     to "BMH",
        "WBRNMTH"  to "WBN", "SWANAGE"  to "SWG",
        "SOTONAE"  to "SOA", "STNC"     to "SOU",
        "SOTONEA"  to "SOA", "PORSMTH"  to "PMS",
        "PORSMTHH" to "PMH", "HAVNT"    to "HAV",
        "CHCSTR"   to "CCH", "BOGNRGJ"  to "BOG",
        "WTHMTN"   to "WOF", "YRKSTR"   to "YAE",
        "WKMTHS"   to "WCS", "WNDMRE"   to "WDS",
        "BRKNHD"   to "BKQ", "SLNGR"    to "SLD",


        // ── East Midlands / East ─────────────────────────────────────────────
        "PTRBRS"   to "PBO", "PTRBHES"  to "PBO",
        "SNGHM"    to "SNO", "LGHTN"    to "LGT",
        "CRWLYCJ"  to "CWC", "BDRD"     to "BDF",
        "BDFD"     to "BDF", "LTNI"     to "LUT",
        "DUNSTBL"  to "DUS", "WTFD"     to "WFJ",
        "HMLHMPST" to "HML", "STALBNS"  to "SAA",
        "WLNGRDB"  to "WGC", "HTFRD"    to "HFD",

        // ── Airport stations ─────────────────────────────────────────────────
        "GATWKAIR" to "GTW", "HTHRWT1"  to "HAP",
        "HTHRWT2"  to "HXX", "STNDAIR"  to "SSD",
        "MNCHRAIR" to "MIA", "BRMAAIR"  to "BHI",
        "LTNARIP"  to "LTN", "ABLRDEEN" to "ABD",
        "EDNBURGR" to "EDB"
    )
}