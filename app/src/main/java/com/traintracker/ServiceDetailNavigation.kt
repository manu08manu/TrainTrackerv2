package com.traintracker

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Typed argument object for launching [ServiceDetailActivity].
 *
 * ── Why this exists ───────────────────────────────────────────────────────────
 *
 * Android Activities communicate via [Intent] extras — a stringly-typed key/value
 * map.  The original code scattered ~20 string key constants across a companion
 * object and had a single `start()` function with 30 parameters, making it easy
 * to accidentally pass arguments in the wrong order or forget an optional one.
 *
 * This class replaces that pattern with a proper data class:
 *   • All parameters are named and have sensible defaults.
 *   • Serialisation to/from Intent is in one place, not two.
 *   • The caller's code is readable without counting positional arguments.
 *
 * ── Usage ─────────────────────────────────────────────────────────────────────
 *
 *   // Launch the detail screen from a board item tap:
 *   ServiceDetailArgs(
 *       serviceId   = service.serviceID,
 *       headcode    = service.trainId,
 *       origin      = service.origin,
 *       destination = service.destination,
 *       std         = service.std,
 *       queryCrs    = currentCrs
 *   ).launch(context)
 *
 *   // Read args back inside ServiceDetailActivity.onCreate():
 *   val args = ServiceDetailArgs.from(intent)
 *
 * ── Railway term glossary (for fields that may be unfamiliar) ─────────────────
 *
 *   serviceId     — CIF UID or Darwin RID uniquely identifying this service run.
 *   headcode      — 4-character train reporting number, e.g. "1A34".
 *   queryCrs      — CRS of the station the user was viewing when they tapped the
 *                   service.  Used to split calling points into "previous" and
 *                   "subsequent" relative to that station.
 *   destCrs       — CRS of the service's final destination.  Used to anchor the
 *                   HSP (punctuality) route lookup.
 *   splitTiploc   — TIPLOC where the train splits into two portions.  The detail
 *                   screen uses this to show a "Divides at X" notice.
 *   splitToUid    — CIF UID of the detached portion after the split.
 *   couplingTiploc— TIPLOC where the train joins another service.
 *   coupledFromUid— CIF UID of the service that joins this one.
 *   couplingAssocType — "JOIN" or "DIVIDE" as reported by the server association API.
 */
data class ServiceDetailArgs(
    val serviceId:              String,
    val headcode:               String,
    val origin:                 String,
    val destination:            String,
    val std:                    String,
    val etd:                    String              = "",
    val queryCrs:               String,
    val destCrs:                String              = "",
    val units:                  List<String>        = emptyList(),
    val coachCount:             Int                 = 0,
    val previousCallingPoints:  List<CallingPoint>  = emptyList(),
    val subsequentCallingPoints: List<CallingPoint> = emptyList(),
    val isPassingService:       Boolean             = false,
    val platform:               String              = "",
    val isCancelled:            Boolean             = false,
    val cancelReason:           String              = "",
    val splitTiploc:            String              = "",
    val splitToHeadcode:        String              = "",
    val splitToDestName:        String              = "",
    val splitToUid:             String              = "",
    val couplingTiploc:         String              = "",
    val coupledFromHeadcode:    String              = "",
    val coupledFromUid:         String              = "",
    val couplingAssocType:      String              = ""
) {
    /** Starts [ServiceDetailActivity] with these args. */
    fun launch(ctx: Context) = ctx.startActivity(toIntent(ctx))

    /** Serialises this object into an [Intent] ready to pass to [ServiceDetailActivity]. */
    fun toIntent(ctx: Context): Intent = Intent(ctx, ServiceDetailActivity::class.java).apply {
        putExtra(Keys.SERVICE_ID,        serviceId)
        putExtra(Keys.HEADCODE,          headcode)
        putExtra(Keys.ORIGIN,            origin)
        putExtra(Keys.DESTINATION,       destination)
        putExtra(Keys.DEST_CRS,          destCrs)
        putExtra(Keys.STD,               std)
        putExtra(Keys.ETD,               etd)
        putExtra(Keys.QUERY_CRS,         queryCrs)
        putStringArrayListExtra(Keys.UNITS, ArrayList(units))
        putExtra(Keys.COACHES,           coachCount)
        putParcelableArrayListExtra(Keys.PREV_CPS,  ArrayList(previousCallingPoints))
        putParcelableArrayListExtra(Keys.SUBS_CPS,  ArrayList(subsequentCallingPoints))
        putExtra(Keys.IS_CANCELLED,      isCancelled)
        putExtra(Keys.CANCEL_REASON,     cancelReason)
        putExtra(Keys.IS_PASSING,        isPassingService)
        putExtra(Keys.PLATFORM,          platform)
        putExtra(Keys.SPLIT_TIPLOC,      splitTiploc)
        putExtra(Keys.SPLIT_HEADCODE,    splitToHeadcode)
        putExtra(Keys.SPLIT_DEST_NAME,   splitToDestName)
        putExtra(Keys.SPLIT_TO_UID,      splitToUid)
        putExtra(Keys.COUPLING_TIPLOC,   couplingTiploc)
        putExtra(Keys.COUPLED_FROM_HC,   coupledFromHeadcode)
        putExtra(Keys.COUPLED_FROM_UID,  coupledFromUid)
        putExtra(Keys.COUPLING_ASSOC,    couplingAssocType)
    }

    companion object {
        /**
         * Deserialises a [ServiceDetailArgs] from the Intent that started
         * [ServiceDetailActivity].  Call this in `onCreate()`:
         *
         *     val args = ServiceDetailArgs.from(intent)
         *
         * Returns an object with empty/default values for any extras that are absent.
         */
        fun from(intent: Intent): ServiceDetailArgs {
            fun str(key: String)  = intent.getStringExtra(key) ?: ""
            fun bool(key: String) = intent.getBooleanExtra(key, false)
            fun int_(key: String) = intent.getIntExtra(key, 0)

            // getParcelableArrayListExtra API changed in API 33 (TIRAMISU).
            @Suppress("DEPRECATION")
            fun callingPoints(key: String): List<CallingPoint> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableArrayListExtra(key, CallingPoint::class.java) ?: emptyList()
                else
                    intent.getParcelableArrayListExtra<CallingPoint>(key) ?: emptyList()

            return ServiceDetailArgs(
                serviceId               = str(Keys.SERVICE_ID),
                headcode                = str(Keys.HEADCODE),
                origin                  = str(Keys.ORIGIN),
                destination             = str(Keys.DESTINATION),
                std                     = str(Keys.STD),
                etd                     = str(Keys.ETD),
                queryCrs                = str(Keys.QUERY_CRS),
                destCrs                 = str(Keys.DEST_CRS),
                units                   = intent.getStringArrayListExtra(Keys.UNITS) ?: emptyList(),
                coachCount              = int_(Keys.COACHES),
                previousCallingPoints   = callingPoints(Keys.PREV_CPS),
                subsequentCallingPoints = callingPoints(Keys.SUBS_CPS),
                isCancelled             = bool(Keys.IS_CANCELLED),
                cancelReason            = str(Keys.CANCEL_REASON),
                isPassingService        = bool(Keys.IS_PASSING),
                platform                = str(Keys.PLATFORM),
                splitTiploc             = str(Keys.SPLIT_TIPLOC),
                splitToHeadcode         = str(Keys.SPLIT_HEADCODE),
                splitToDestName         = str(Keys.SPLIT_DEST_NAME),
                splitToUid              = str(Keys.SPLIT_TO_UID),
                couplingTiploc          = str(Keys.COUPLING_TIPLOC),
                coupledFromHeadcode     = str(Keys.COUPLED_FROM_HC),
                coupledFromUid          = str(Keys.COUPLED_FROM_UID),
                couplingAssocType       = str(Keys.COUPLING_ASSOC)
            )
        }
    }

    /**
     * Intent extra key strings — kept private here so they can only be
     * accessed through [toIntent] and [from], preventing accidental direct
     * use of raw string literals elsewhere in the codebase.
     */
    private object Keys {
        const val SERVICE_ID       = "service_id"
        const val HEADCODE         = "headcode"
        const val ORIGIN           = "origin"
        const val DESTINATION      = "destination"
        const val DEST_CRS         = "dest_crs"
        const val STD              = "std"
        const val ETD              = "etd"
        const val QUERY_CRS        = "query_crs"
        const val UNITS            = "units"
        const val COACHES          = "coaches"
        const val PREV_CPS         = "prev_calling_points"
        const val SUBS_CPS         = "subs_calling_points"
        const val IS_CANCELLED     = "is_cancelled"
        const val CANCEL_REASON    = "cancel_reason"
        const val IS_PASSING       = "is_passing"
        const val PLATFORM         = "platform"
        const val SPLIT_TIPLOC     = "split_tiploc_crs"
        const val SPLIT_HEADCODE   = "split_headcode"
        const val SPLIT_DEST_NAME  = "split_dest_name"
        const val SPLIT_TO_UID     = "split_to_uid"
        const val COUPLING_TIPLOC  = "coupling_tiploc"
        const val COUPLED_FROM_HC  = "coupled_from_headcode"
        const val COUPLED_FROM_UID = "coupled_from_uid"
        const val COUPLING_ASSOC   = "coupling_assoc_type"
    }
}
