package com.traintracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object CifRepository {

    private const val TAG = "CifRepository"

    // ── Status flow ───────────────────────────────────────────────────────────

    sealed class Status {
        object Idle       : Status()
        object Checking   : Status()
        data class Downloading(
            val bytesRead:  Long,
            val totalBytes: Long,
            val startMs:    Long
        ) : Status()
        data class Parsing(val scheduleCount: Int) : Status()
        object Ready      : Status()
        data class Error(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    val isReady: Boolean get() = _status.value is Status.Ready

    private val _vstpAmendedUid = MutableStateFlow("")
    val vstpAmendedUid: StateFlow<String> = _vstpAmendedUid.asStateFlow()

    @Volatile private var db: CifDb? = null
    private lateinit var appContext: Context
    private val isDownloading = java.util.concurrent.atomic.AtomicBoolean(false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()
            chain.proceed(request)
        }
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when SERVER_BASE_URL is set — server handles CIF, skip local download.
     */
    fun skipToReady() {
        _status.value = Status.Ready
    }

    /**
     * Initialise for local CIF mode (no server configured).
     * Downloads and parses the CIF file if the local cache is stale.
     */
    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            _status.value = Status.Checking
            try {
                val database = CifDb(context)
                db = database
                val today = todayString()
                if (database.getCachedDate() == today) {
                    Log.d(TAG, "CIF cache current ($today) — ready")
                    _status.value = Status.Ready
                    return@withContext
                }
                Log.d(TAG, "CIF cache stale — downloading")
                downloadAndIndex(database)
            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}", e)
                _status.value = Status.Error(e.message ?: "Init failed")
            }
        }
    }

    suspend fun forceRefresh(context: Context) {
        withContext(Dispatchers.IO) {
            val database = db ?: CifDb(context).also { db = it }
            downloadAndIndex(database)
        }
    }

    fun getScheduledAt(crs: String): List<CifMovement> {
        if (!isReady) return emptyList()
        val c = crs.uppercase()
        return db?.queryByCrs(c, CorpusData.tiplocsByCrs(c)) ?: emptyList()
    }

    fun getArrivalsAt(crs: String): List<CifMovement> {
        if (!isReady) return emptyList()
        val c = crs.uppercase()
        return db?.queryArrivalsByCrs(c, CorpusData.tiplocsByCrs(c)) ?: emptyList()
    }

    fun getCallingPointsForService(uid: String, atCrs: String): Pair<List<CallingPoint>, List<CallingPoint>>? {
        if (!isReady) return null
        return db?.queryCallingPoints(uid, atCrs.uppercase())
    }

    fun findByHeadcode(headcode: String, atCrs: String): CifMovement? {
        if (!isReady) return null
        return db?.queryByHeadcodeAtCrs(headcode.uppercase(), atCrs.uppercase())
    }

    fun applyVstp(line: String) {
        val database = db ?: return
        try {
            val today = todayString()
            val todayDow = todayDayOfWeek()

            if (line.contains("\"transaction_type\":\"Delete\"")) {
                val uid = strVal(line, "CIF_train_uid")
                if (uid.isNotEmpty()) {
                    database.deleteByUid(uid, today)
                    _vstpAmendedUid.value = uid
                }
                return
            }
            if (line.contains("\"CIF_stp_indicator\":\"C\"")) {
                val uid = strVal(line, "CIF_train_uid")
                if (uid.isNotEmpty()) {
                    database.deleteByUid(uid, today)
                    _vstpAmendedUid.value = uid
                }
                return
            }
            if (line.contains("\"train_status\":\"B\"") || line.contains("\"train_status\":\"S\"")) return

            val startDate = strVal(line, "schedule_start_date")
            val endDate   = strVal(line, "schedule_end_date")
            if (startDate.isEmpty() || endDate.isEmpty()) return
            if (today < startDate || today > endDate) return

            val daysRuns = strVal(line, "schedule_days_runs")
            if (daysRuns.length < 7 || daysRuns[todayDow] != '1') return

            val trainStatus = strVal(line, "train_status")
            val uid         = strVal(line, "CIF_train_uid")
            val atocCode    = strVal(line, "atoc_code").uppercase()
            val headcode    = strVal(line, "signalling_id").trim()
            if (uid.isEmpty() || headcode.isEmpty()) return

            database.deleteByUid(uid, today)

            val locArrayStart = line.indexOf("\"schedule_location\":[")
            if (locArrayStart < 0) return
            val arrayOpen  = locArrayStart + "\"schedule_location\":[".length - 1
            val arrayClose = line.indexOf(']', arrayOpen)
            if (arrayClose < 0) return
            val locArray   = line.substring(arrayOpen + 1, arrayClose)
            val locObjects = locArray.split("},{")
            if (locObjects.size < 2) return

            val originTiploc = strVal(locObjects.first(), "tiploc_code")
            val destTiploc   = strVal(locObjects.last(),  "tiploc_code")
            val originCrs    = CorpusData.crsFromTiploc(originTiploc) ?: ""
            val destCrs      = CorpusData.crsFromTiploc(destTiploc)   ?: ""

            database.beginBatch()
            var seq = 0
            for (loc in locObjects) {
                val tiploc = strVal(loc, "tiploc_code")
                if (tiploc.isEmpty()) continue
                val crs = CorpusData.crsFromTiploc(tiploc)
                    ?: database.queryTiplocCrs(tiploc)?.takeIf { it.length == 3 }
                    ?: ""
                val locType  = strVal(loc, "location_type")
                val pass     = strVal(loc, "pass")
                val isPass   = pass.isNotEmpty()
                val platform = strVal(loc, "platform").trim()
                val wTime: String; val pTime: String; val pointType: String
                when (locType) {
                    "LO" -> { wTime = formatJsonTime(strVal(loc, "departure")); pTime = formatJsonTime(strVal(loc, "public_departure")); pointType = "LO" }
                    "LT" -> { wTime = formatJsonTime(strVal(loc, "arrival"));   pTime = formatJsonTime(strVal(loc, "public_arrival"));   pointType = "LT" }
                    else -> {
                        if (isPass) {
                            wTime = formatJsonTime(pass); pTime = ""; pointType = "PP"
                        } else {
                            val wDep = formatJsonTime(strVal(loc, "departure"))
                            val wArr = formatJsonTime(strVal(loc, "arrival"))
                            wTime = wDep.ifEmpty { wArr }
                            pTime = formatJsonTime(strVal(loc, "public_departure")).ifEmpty { formatJsonTime(strVal(loc, "public_arrival")) }
                            pointType = "LI"
                        }
                    }
                }
                if (wTime.isEmpty()) continue
                val locationName = if (crs.length == 3) StationData.findByCrs(crs)?.name ?: crs
                else CorpusData.nameFromTiploc(tiploc) ?: database.queryTiplocName(tiploc) ?: tiploc
                database.insert(crs, tiploc, uid, headcode, trainStatus, today, wTime, pTime, isPass, pointType, originTiploc, originCrs, destTiploc, destCrs, atocCode)
                database.insertCallingPoint(uid, seq++, crs, locationName, wTime, pTime, isPass, pointType, platform)
            }
            database.commitBatch()
            _vstpAmendedUid.value = uid
        } catch (e: Exception) {
            Log.w(TAG, "VSTP apply error: ${e.message}")
        }
    }

    fun nameFromTiploc(tiploc: String): String {
        if (!isReady) return tiploc
        return db?.queryTiplocName(tiploc.uppercase()) ?: tiploc
    }

    fun crsFromTiploc(tiploc: String): String? {
        if (!isReady) return null
        return db?.queryTiplocCrs(tiploc.uppercase())?.takeIf { it.length == 3 }
    }

    // ── Download (local mode only) ────────────────────────────────────────────

    private suspend fun downloadAndIndex(database: CifDb) {
        if (!isDownloading.compareAndSet(false, true)) return
        try { downloadAndIndexInternal(database) } finally { isDownloading.set(false) }
    }

    private suspend fun downloadAndIndexInternal(database: CifDb) {
        val url = try { Constants.SCHEDULE_URL } catch (_: Exception) { "" }
        if (url.isEmpty()) { _status.value = Status.Error("SCHEDULE_URL not configured"); return }
        val username = try { Constants.SCHEDULE_USERNAME } catch (_: Exception) { "" }
        val password = try { Constants.SCHEDULE_PASSWORD } catch (_: Exception) { "" }
        val request = Request.Builder().url(url).apply {
            if (username.isNotEmpty()) addHeader("Authorization", okhttp3.Credentials.basic(username, password))
        }.build()

        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                if (attempt > 1) kotlinx.coroutines.delay(10_000)
                val downloadStartMs = System.currentTimeMillis()
                _status.value = Status.Downloading(0L, -1L, downloadStartMs)
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
                    val body = response.body ?: throw Exception("Empty response body")
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val rawStream = body.byteStream()
                    val progressStream = object : java.io.InputStream() {
                        private var totalRead = 0L; private var lastEmitMs = 0L
                        override fun read(): Int { val b = rawStream.read(); if (b >= 0) tick(1); return b }
                        override fun read(buf: ByteArray, off: Int, len: Int): Int { val n = rawStream.read(buf, off, len); if (n > 0) tick(n.toLong()); return n }
                        private fun tick(n: Long) { totalRead += n; val now = System.currentTimeMillis(); if (now - lastEmitMs > 500) { _status.value = Status.Downloading(totalRead, contentLength, downloadStartMs); lastEmitMs = now } }
                        override fun close() = rawStream.close()
                    }
                    val reader = BufferedReader(InputStreamReader(GZIPInputStream(progressStream.buffered(131_072)), "UTF-8"), 131_072)
                    parseCifStream(reader, database)
                }
                return
            } catch (e: Exception) { lastException = e; Log.w(TAG, "Attempt $attempt failed: ${e.message}") }
        }
        _status.value = Status.Error("Download failed: ${lastException?.message}")
    }

    private fun parseCifStream(reader: BufferedReader, database: CifDb) {
        var scheduleCount = 0
        val today = todayString()
        val todayDow = todayDayOfWeek()
        val db = database.writableDatabase
        if (db.inTransaction()) { try { db.endTransaction() } catch (_: Exception) {} }
        db.rawQuery("PRAGMA journal_mode=MEMORY", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA synchronous=OFF")
        db.execSQL("PRAGMA cache_size=-65536")
        db.execSQL("DROP INDEX IF EXISTS idx_crs_date")
        db.execSQL("DROP INDEX IF EXISTS idx_tiploc_date")
        db.execSQL("DROP INDEX IF EXISTS idx_cp_uid")
        database.beginBatch()
        var batchCount = 0

        try { reader.forEachLine { line ->
            if (line.startsWith("{\"TiplocV1\":")) {
                val tiploc = strVal(line, "tiploc_code").trim().uppercase()
                val crs    = strVal(line, "crs_code").trim().uppercase()
                val desc   = strVal(line, "description").trim()
                if (tiploc.isNotEmpty() && desc.isNotEmpty()) database.insertTiploc(tiploc, crs, desc)
                return@forEachLine
            }
            if (!line.startsWith("{\"JsonScheduleV1\":")) return@forEachLine
            if (line.contains("\"transaction_type\":\"Delete\"")) return@forEachLine
            if (line.contains("\"CIF_stp_indicator\":\"C\"")) return@forEachLine
            if (line.contains("\"train_status\":\"B\"") || line.contains("\"train_status\":\"S\"")) return@forEachLine
            try {
                val startDate = strVal(line, "schedule_start_date"); val endDate = strVal(line, "schedule_end_date")
                if (startDate.isEmpty() || endDate.isEmpty() || today < startDate || today > endDate) return@forEachLine
                val daysRuns = strVal(line, "schedule_days_runs")
                if (daysRuns.length < 7 || daysRuns[todayDow] != '1') return@forEachLine
                val trainStatus = strVal(line, "train_status")
                val uid         = strVal(line, "CIF_train_uid")
                val atocCode    = strVal(line, "atoc_code").uppercase()
                val headcode    = strVal(line, "signalling_id").trim()
                if (uid.isEmpty() || headcode.isEmpty()) return@forEachLine
                val locArrayStart = line.indexOf("\"schedule_location\":["); if (locArrayStart < 0) return@forEachLine
                val arrayOpen  = locArrayStart + "\"schedule_location\":[".length - 1
                val arrayClose = line.indexOf(']', arrayOpen); if (arrayClose < 0) return@forEachLine
                val locObjects = line.substring(arrayOpen + 1, arrayClose).split("},{")
                if (locObjects.size < 2) return@forEachLine
                val originTiploc = strVal(locObjects.first(), "tiploc_code")
                val destTiploc   = strVal(locObjects.last(),  "tiploc_code")
                val originCrs    = CorpusData.crsFromTiploc(originTiploc) ?: ""
                val destCrs      = CorpusData.crsFromTiploc(destTiploc)   ?: ""
                var seq = 0
                for (loc in locObjects) {
                    val tiploc = strVal(loc, "tiploc_code"); if (tiploc.isEmpty()) continue
                    val crs = CorpusData.crsFromTiploc(tiploc) ?: database.queryTiplocCrs(tiploc)?.takeIf { it.length == 3 } ?: ""
                    val locType = strVal(loc, "location_type"); val pass = strVal(loc, "pass"); val isPass = pass.isNotEmpty()
                    val wTime: String; val pTime: String; val pointType: String
                    when (locType) {
                        "LO" -> { wTime = formatJsonTime(strVal(loc, "departure")); pTime = formatJsonTime(strVal(loc, "public_departure")); pointType = "LO" }
                        "LT" -> { wTime = formatJsonTime(strVal(loc, "arrival"));   pTime = formatJsonTime(strVal(loc, "public_arrival"));   pointType = "LT" }
                        else -> { if (isPass) { wTime = formatJsonTime(pass); pTime = ""; pointType = "PP" } else { val wDep = formatJsonTime(strVal(loc, "departure")); val wArr = formatJsonTime(strVal(loc, "arrival")); wTime = wDep.ifEmpty { wArr }; pTime = formatJsonTime(strVal(loc, "public_departure")).ifEmpty { formatJsonTime(strVal(loc, "public_arrival")) }; pointType = "LI" } }
                    }
                    if (wTime.isEmpty()) continue
                    val platform = strVal(loc, "platform").trim()
                    val locationName = if (crs.length == 3) StationData.findByCrs(crs)?.name ?: crs else CorpusData.nameFromTiploc(tiploc) ?: database.queryTiplocName(tiploc) ?: tiploc
                    database.insert(crs, tiploc, uid, headcode, trainStatus, today, wTime, pTime, isPass, pointType, originTiploc, originCrs, destTiploc, destCrs, atocCode)
                    database.insertCallingPoint(uid, seq++, crs, locationName, wTime, pTime, isPass, pointType, platform)
                }
                scheduleCount++; batchCount++
                if (batchCount >= 10_000) { database.commitBatch(); database.beginBatch(); batchCount = 0; _status.value = Status.Parsing(scheduleCount) }
            } catch (_: Exception) {}
        } } catch (e: Exception) {
            if (db.inTransaction()) { try { db.endTransaction() } catch (_: Exception) {} }
            try { db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() } } catch (_: Exception) {}
            try { db.execSQL("PRAGMA synchronous=NORMAL") } catch (_: Exception) {}
            try { db.execSQL("PRAGMA cache_size=-2048") }  catch (_: Exception) {}
            throw e
        }
        database.commitBatch()
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_crs_date    ON movements (crs, run_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tiploc_date ON movements (tiploc, run_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cp_uid      ON calling_points (uid)")
        db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA synchronous=NORMAL")
        db.execSQL("PRAGMA cache_size=-2048")
        database.saveCachedDate(today)
        _status.value = Status.Ready
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun strVal(json: String, key: String): String {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker); if (start < 0) return ""
        val vs = start + marker.length
        val end = json.indexOf('"', vs)
        return if (end < 0) "" else json.substring(vs, end)
    }

    private fun formatJsonTime(raw: String): String {
        val s = raw.trim(); if (s.length < 4) return ""
        val hh = s.substring(0, 2); val mm = s.substring(2, 4)
        if (!hh.all { it.isDigit() } || !mm.all { it.isDigit() }) return ""
        return "$hh:$mm"
    }

    private fun todayString(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun todayDayOfWeek(): Int {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 6 else dow - 2
    }
}

// ─── CifMovement ─────────────────────────────────────────────────────────────

data class CifMovement(
    val crs: String, val tiploc: String, val uid: String, val headcode: String,
    val trainStatus: String, val workingTime: String, val publicTime: String,
    val isPass: Boolean, val pointType: String, val originTiploc: String,
    val originCrs: String, val destTiploc: String, val destCrs: String,
    val atocCode: String = ""
) {
    val displayTime: String get() = publicTime.ifEmpty { workingTime }
    val freightKey:  String get() = "$headcode-$displayTime"
}

// ─── CifDb ───────────────────────────────────────────────────────────────────

private class CifDb(context: Context) : SQLiteOpenHelper(context, "cif_schedule.db", null, DB_VERSION) {

    companion object {
        private const val DB_VERSION   = 8
        private const val TABLE        = "movements"
        private const val CP_TABLE     = "calling_points"
        private const val TIPLOC_TABLE = "tiploc_names"
        private const val META_TABLE   = "meta"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE $TABLE (id INTEGER PRIMARY KEY, crs TEXT NOT NULL, tiploc TEXT NOT NULL, uid TEXT NOT NULL, headcode TEXT NOT NULL, train_status TEXT NOT NULL DEFAULT '', run_date TEXT NOT NULL, working_time TEXT NOT NULL, public_time TEXT NOT NULL DEFAULT '', is_pass INTEGER NOT NULL DEFAULT 0, point_type TEXT NOT NULL DEFAULT 'LI', origin_tiploc TEXT NOT NULL DEFAULT '', origin_crs TEXT NOT NULL DEFAULT '', dest_tiploc TEXT NOT NULL DEFAULT '', dest_crs TEXT NOT NULL DEFAULT '', atoc_code TEXT NOT NULL DEFAULT '')""".trimIndent())
        db.execSQL("CREATE INDEX idx_crs_date    ON $TABLE (crs, run_date)")
        db.execSQL("CREATE INDEX idx_tiploc_date ON $TABLE (tiploc, run_date)")
        db.execSQL("""CREATE TABLE $CP_TABLE (id INTEGER PRIMARY KEY, uid TEXT NOT NULL, seq INTEGER NOT NULL, crs TEXT NOT NULL, location_name TEXT NOT NULL DEFAULT '', working_time TEXT NOT NULL, public_time TEXT NOT NULL DEFAULT '', is_pass INTEGER NOT NULL DEFAULT 0, point_type TEXT NOT NULL DEFAULT 'LI', platform TEXT NOT NULL DEFAULT '')""".trimIndent())
        db.execSQL("CREATE INDEX idx_cp_uid ON $CP_TABLE (uid)")
        db.execSQL("CREATE TABLE $TIPLOC_TABLE (tiploc TEXT PRIMARY KEY, crs TEXT NOT NULL DEFAULT '', name TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE $META_TABLE (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE"); db.execSQL("DROP TABLE IF EXISTS $CP_TABLE")
        db.execSQL("DROP TABLE IF EXISTS $TIPLOC_TABLE"); db.execSQL("DROP TABLE IF EXISTS $META_TABLE")
        onCreate(db)
    }

    fun beginBatch()  { writableDatabase.beginTransaction() }
    fun commitBatch() { writableDatabase.setTransactionSuccessful(); writableDatabase.endTransaction() }

    fun deleteByUid(uid: String, runDate: String) {
        writableDatabase.delete(TABLE,    "uid = ? AND run_date = ?", arrayOf(uid, runDate))
        writableDatabase.delete(CP_TABLE, "uid = ?",                  arrayOf(uid))
    }

    fun insert(crs: String, tiploc: String, uid: String, headcode: String, trainStatus: String,
               runDate: String, workingTime: String, publicTime: String, isPass: Boolean,
               pointType: String, originTiploc: String, originCrs: String,
               destTiploc: String, destCrs: String, atocCode: String = "") {
        writableDatabase.insert(TABLE, null, ContentValues().apply {
            put("crs", crs); put("tiploc", tiploc); put("uid", uid); put("headcode", headcode)
            put("train_status", trainStatus); put("run_date", runDate); put("working_time", workingTime)
            put("public_time", publicTime); put("is_pass", if (isPass) 1 else 0)
            put("point_type", pointType); put("origin_tiploc", originTiploc); put("origin_crs", originCrs)
            put("dest_tiploc", destTiploc); put("dest_crs", destCrs); put("atoc_code", atocCode)
        })
    }

    fun insertCallingPoint(uid: String, seq: Int, crs: String, locationName: String,
                           workingTime: String, publicTime: String, isPass: Boolean,
                           pointType: String, platform: String = "") {
        writableDatabase.insert(CP_TABLE, null, ContentValues().apply {
            put("uid", uid); put("seq", seq); put("crs", crs); put("location_name", locationName)
            put("working_time", workingTime); put("public_time", publicTime)
            put("is_pass", if (isPass) 1 else 0); put("point_type", pointType); put("platform", platform)
        })
    }

    private val CANONICAL_CRS = mapOf(
        "SPL" to "STP", "SPX" to "STP", "ASI" to "AFK", "GCL" to "GLC",
        "LIF" to "LTV", "LVL" to "LIV", "RDZ" to "RDG", "TAH" to "TAM",
        "WJH" to "WIJ", "WJL" to "WIJ"
    )

    fun queryCallingPoints(uid: String, atCrs: String): Pair<List<CallingPoint>, List<CallingPoint>> {
        val all = mutableListOf<CallingPoint>()
        readableDatabase.rawQuery(
            "SELECT crs, location_name, working_time, public_time, is_pass, point_type, platform FROM $CP_TABLE WHERE uid = ? ORDER BY seq ASC",
            arrayOf(uid)
        ).use { c ->
            while (c.moveToNext()) {
                val st = c.getString(2); val pt = c.getString(3).ifEmpty { st }
                val rawCrs = c.getString(0); val canonCrs = CANONICAL_CRS[rawCrs] ?: rawCrs
                val rawName = c.getString(1)
                val canonName = if (rawName == rawCrs && canonCrs != rawCrs) canonCrs else rawName
                all.add(CallingPoint(canonName, canonCrs, pt, "On time", "", false, null, c.getString(6), c.getInt(4) != 0))
            }
        }
        val deduped = mutableListOf<CallingPoint>()
        val seenCrs = mutableSetOf<String>()
        for (cp in all) { val key = if (cp.crs.length == 3) cp.crs else cp.crs + cp.st; if (seenCrs.add(key)) deduped.add(cp) }
        val splitIdx = deduped.indexOfFirst { it.crs == atCrs }
        return if (splitIdx < 0) emptyList<CallingPoint>() to deduped
        else deduped.subList(0, splitIdx).toList() to deduped.subList(splitIdx + 1, deduped.size).toList()
    }

    fun insertTiploc(tiploc: String, crs: String, name: String) {
        writableDatabase.insertWithOnConflict(TIPLOC_TABLE, null, ContentValues().apply {
            put("tiploc", tiploc); put("crs", crs); put("name", name)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun queryTiplocName(tiploc: String): String? =
        readableDatabase.rawQuery("SELECT name FROM $TIPLOC_TABLE WHERE tiploc = ?", arrayOf(tiploc))
            .use { c -> if (c.moveToFirst()) c.getString(0).ifEmpty { null } else null }

    fun queryTiplocCrs(tiploc: String): String? =
        readableDatabase.rawQuery("SELECT crs FROM $TIPLOC_TABLE WHERE tiploc = ?", arrayOf(tiploc))
            .use { c -> if (c.moveToFirst()) c.getString(0).ifEmpty { null } else null }

    fun saveCachedDate(date: String) {
        writableDatabase.insertWithOnConflict(META_TABLE, null, ContentValues().apply {
            put("key", "cached_date"); put("value", date)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getCachedDate(): String =
        readableDatabase.rawQuery("SELECT value FROM $META_TABLE WHERE key = 'cached_date'", null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else "" }

    fun queryByHeadcodeAtCrs(headcode: String, crs: String): CifMovement? {
        val today = todayString()
        readableDatabase.rawQuery(
            "SELECT crs, tiploc, uid, headcode, train_status, working_time, public_time, is_pass, point_type, origin_tiploc, origin_crs, dest_tiploc, dest_crs, atoc_code FROM $TABLE WHERE headcode = ? AND crs = ? AND run_date = ? LIMIT 1",
            arrayOf(headcode, crs, today)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return CifMovement(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) != 0, c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13))
        }
    }

    fun queryArrivalsByCrs(crs: String, tiplocs: List<String> = emptyList()): List<CifMovement> {
        val today = todayString(); val result = mutableListOf<CifMovement>()
        val (crsClause, crsArgs) = crsOrTiplocArgs(crs, tiplocs)
        readableDatabase.rawQuery(
            "SELECT crs, tiploc, uid, headcode, train_status, working_time, public_time, is_pass, point_type, origin_tiploc, origin_crs, dest_tiploc, dest_crs, atoc_code FROM $TABLE WHERE $crsClause AND run_date = ? AND point_type IN ('LI','LT') AND is_pass = 0 ORDER BY working_time ASC",
            crsArgs + arrayOf(today)
        ).use { c ->
            while (c.moveToNext()) result.add(CifMovement(c.getString(0).ifEmpty { crs }, c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), false, c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13)))
        }
        return result
    }

    fun queryByCrs(crs: String, tiplocs: List<String> = emptyList()): List<CifMovement> {
        val today = todayString(); val result = mutableListOf<CifMovement>(); val seen = mutableSetOf<String>()
        val (crsClause, crsArgs) = crsOrTiplocArgs(crs, tiplocs)
        readableDatabase.rawQuery(
            "SELECT crs, tiploc, uid, headcode, train_status, working_time, public_time, is_pass, point_type, origin_tiploc, origin_crs, dest_tiploc, dest_crs, atoc_code FROM $TABLE WHERE $crsClause AND run_date = ? ORDER BY working_time ASC",
            crsArgs + arrayOf(today)
        ).use { c ->
            while (c.moveToNext()) {
                val uid = c.getString(2); if (!seen.add(uid)) continue
                result.add(CifMovement(c.getString(0).ifEmpty { crs }, c.getString(1), uid, c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) != 0, c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13)))
            }
        }
        return result
    }

    private fun crsOrTiplocArgs(crs: String, tiplocs: List<String>): Pair<String, Array<String>> =
        if (tiplocs.isEmpty()) "crs = ?" to arrayOf(crs)
        else "(crs = ? OR tiploc IN (${tiplocs.joinToString(",") { "?" }}))" to (arrayOf(crs) + tiplocs.toTypedArray())

    private fun todayString(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}