package com.rsps1008.sleeptrace.motion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.edit
import androidx.core.database.sqlite.transaction
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals

class MotionSettings(context: Context) {
    private val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    var experiment: CaptureExperiment
        get() = if (!debug) CaptureExperiment.OFF else runCatching { CaptureExperiment.valueOf(prefs.getString("capture_experiment", "OFF")!!) }.getOrDefault(CaptureExperiment.OFF)
        set(value) { if (debug) prefs.edit { putString("capture_experiment", value.name) } }
    private val prefs = context.getSharedPreferences("sleeptrace_motion", Context.MODE_PRIVATE)
    var enabled: Boolean
        // Old motion opt-in and placement settings no longer control automatic recording.
        get() = prefs.getBoolean("recording_enabled", true)
        set(value) = prefs.edit { putBoolean("recording_enabled", value) }
    var status: String
        get() = prefs.getString("status", "尚未啟動")!!
        set(value) = prefs.edit { putString("status", value) }
    var windowAlarmGuideShown: Boolean
        get() = prefs.getBoolean("window_alarm_guide_shown", false)
        set(value) = prefs.edit { putBoolean("window_alarm_guide_shown", value) }
}

/** Stores minute features only; no raw accelerometer stream. Inserts are batched in one transaction. */
class MotionStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "motion.db", null, 4) {
    private val appContext = context.applicationContext
    private val maintenancePrefs = appContext.getSharedPreferences("sleeptrace_maintenance", Context.MODE_PRIVATE)
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCaptureTable(db)
        db.execSQL("CREATE TABLE minutes (start INTEGER PRIMARY KEY, covered INTEGER NOT NULL, active INTEGER NOT NULL, squared REAL NOT NULL, samples INTEGER NOT NULL, placement TEXT NOT NULL, featureVersion INTEGER NOT NULL DEFAULT 1, maxDelta REAL, movementEvents INTEGER, longestActiveMillis INTEGER, quietTailMillis INTEGER, longestGapMillis INTEGER, postureDelta REAL, recordingId INTEGER, observedStart INTEGER, observedEnd INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE minutes ADD COLUMN featureVersion INTEGER NOT NULL DEFAULT 1")
        if (oldVersion < 3) {
            FEATURE_COLUMNS.forEach { (name, type) -> db.execSQL("ALTER TABLE minutes ADD COLUMN $name $type") }
            createCaptureTable(db)
        }
        if (oldVersion in 3 until 4) {
            db.execSQL("ALTER TABLE capture_runs ADD COLUMN targetPeriodUs INTEGER")
            // These capabilities were not persisted by v3. Keep them NULL rather
            // than presenting a synthetic zero as a measured sensor property.
            db.execSQL("ALTER TABLE capture_runs ADD COLUMN sensorMinDelayUs INTEGER")
            db.execSQL("ALTER TABLE capture_runs ADD COLUMN sensorMaxDelayUs INTEGER")
            db.execSQL("ALTER TABLE capture_runs ADD COLUMN fifoReservedEventCount INTEGER")
            // The policy target is recoverable from the isolated experiment trigger;
            // periodUs remains the actual argument passed to registerListener.
            db.execSQL("UPDATE capture_runs SET targetPeriodUs = CASE WHEN trigger = 'EARLY_2HZ' THEN 500000 ELSE 1000000 END WHERE targetPeriodUs IS NULL")
        }
    }

    private fun createCaptureTable(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS capture_runs (id INTEGER PRIMARY KEY, windowStart INTEGER NOT NULL, registeredAt INTEGER NOT NULL, trigger TEXT NOT NULL, targetPeriodUs INTEGER NOT NULL, periodUs INTEGER NOT NULL, latencyUs INTEGER NOT NULL, sensorMinDelayUs INTEGER NOT NULL, sensorMaxDelayUs INTEGER NOT NULL, fifoReservedEventCount INTEGER NOT NULL, fifoCount INTEGER NOT NULL, wakeUp INTEGER NOT NULL, firstEvent INTEGER, rawEvents INTEGER NOT NULL, rejectedEvents INTEGER NOT NULL, meanInterval REAL, maxInterval INTEGER)")

    fun saveCapture(run: CaptureDiagnostics) {
        writableDatabase.insertWithOnConflict("capture_runs", null, ContentValues().apply {
            put("id", run.id); put("windowStart", run.windowStart); put("registeredAt", run.registeredAt)
            put("trigger", run.trigger); put("targetPeriodUs", run.targetPeriodUs); put("periodUs", run.periodUs)
            put("latencyUs", run.latencyUs); put("sensorMinDelayUs", run.sensorMinDelayUs)
            put("sensorMaxDelayUs", run.sensorMaxDelayUs); put("fifoReservedEventCount", run.fifoReservedEventCount)
            put("fifoCount", run.fifoMaxEventCount); put("wakeUp", if (run.wakeUp) 1 else 0); put("firstEvent", run.firstEvent)
            put("rawEvents", run.rawEvents); put("rejectedEvents", run.rejectedEvents)
            put("meanInterval", run.meanIntervalMillis); put("maxInterval", run.maxIntervalMillis)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun captures(start: Long, end: Long): List<CaptureDiagnostics> = readableDatabase.query("capture_runs", null,
        "registeredAt < ? AND registeredAt >= ?", arrayOf(end.toString(), (start - 24 * 60 * MINUTE_MS).toString()),
        null, null, "registeredAt ASC").use { c -> buildList {
            while (c.moveToNext()) add(c.readCapture())
        } }

    /** Last persisted capture summary for the read-only homepage frequency diagnostic. */
    fun latestCapture(): CaptureDiagnostics? = readableDatabase.query(
        "capture_runs", null, null, null, null, null, "registeredAt DESC", "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readCapture() else null }

    @Synchronized
    fun append(minutes: List<MotionMinute>, now: Long = System.currentTimeMillis()) {
        if (minutes.isEmpty()) return
        val shouldCleanup = now - maintenancePrefs.getLong(CLEANUP_KEY, 0L) >= CLEANUP_INTERVAL
        val db = writableDatabase
        db.transaction {
            val existingByStart = db.query(
                "minutes", null, "start >= ? AND start <= ?",
                arrayOf(minutes.minOf { it.startMillis }.toString(), minutes.maxOf { it.startMillis }.toString()),
                null, null, "start ASC"
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getLong(0), cursor.readMinute())
                }.toMutableMap()
            }
            minutes.forEach { item ->
                // Restarting or changing mode can yield two partial contributions to the same minute.
                val existing = existingByStart[item.startMillis]
                // Keep only the highest-priority feature definition for each minute. Matching
                // version and placement contributions are the only rows that may be combined.
                val incomingPriority = MotionFeaturePolicy.storagePriority(item.featureVersion)
                val existingPriority = existing?.let { MotionFeaturePolicy.storagePriority(it.featureVersion) }
                if (existing != null && existingPriority!! > incomingPriority) return@forEach
                val compatible = existing?.takeIf {
                    it.placement == item.placement && it.featureVersion == item.featureVersion
                }
                // A repeated/overlapping v5 contribution is not additive. Never double-count a retry.
                if (compatible?.observedEnd != null && item.observedStart != null && item.observedStart < compatible.observedEnd) return@forEach
                val row = ContentValues().apply {
                    put("start", item.startMillis)
                    put("covered", minOf(MINUTE_MS, item.coveredMillis + (compatible?.coveredMillis ?: 0)))
                    put("active", minOf(MINUTE_MS, item.activeMillis + (compatible?.activeMillis ?: 0)))
                    put("squared", item.squaredDeltaTime + (compatible?.squaredDeltaTime ?: 0.0))
                    put("samples", item.sampleCount + (compatible?.sampleCount ?: 0))
                    put("placement", item.placement.name)
                    put("featureVersion", item.featureVersion)
                    fun maxNullable(a: Long?, b: Long?) = if (a == null) b else if (b == null) a else maxOf(a, b)
                    put("maxDelta", listOfNotNull(item.maxDelta, compatible?.maxDelta).maxOrNull())
                    put("movementEvents", item.movementEvents?.let { it + (compatible?.movementEvents ?: 0) })
                    put("longestActiveMillis", maxNullable(item.longestActiveMillis, compatible?.longestActiveMillis))
                    put("quietTailMillis", item.quietTailMillis)
                    val restart = compatible != null && compatible.recordingId != item.recordingId
                    put("longestGapMillis", if (restart) MINUTE_MS else maxNullable(item.longestGapMillis, compatible?.longestGapMillis))
                    put("postureDelta", listOfNotNull(item.postureDelta, compatible?.postureDelta).maxOrNull())
                    put("recordingId", if (restart) 0L else item.recordingId)
                    put("observedStart", compatible?.observedStart ?: item.observedStart)
                    put("observedEnd", item.observedEnd)
                }
                db.insertWithOnConflict("minutes", null, row, SQLiteDatabase.CONFLICT_REPLACE)
                existingByStart[item.startMillis] = item.copy(
                    coveredMillis = row.getAsLong("covered"), activeMillis = row.getAsLong("active"),
                    squaredDeltaTime = row.getAsDouble("squared"), sampleCount = row.getAsInteger("samples"),
                    maxDelta = row.getAsDouble("maxDelta"), movementEvents = row.getAsInteger("movementEvents"),
                    longestActiveMillis = row.getAsLong("longestActiveMillis"), quietTailMillis = row.getAsLong("quietTailMillis"),
                    longestGapMillis = row.getAsLong("longestGapMillis"), postureDelta = row.getAsDouble("postureDelta"),
                    recordingId = row.getAsLong("recordingId"), observedStart = row.getAsLong("observedStart"), observedEnd = row.getAsLong("observedEnd"))
            }
            if (shouldCleanup) {
                db.delete("minutes", "start < ?", arrayOf((now - RETENTION).toString()))
                db.delete("capture_runs", "registeredAt < ?", arrayOf((now - RETENTION).toString()))
            }
        }
        if (shouldCleanup) maintenancePrefs.edit { putLong(CLEANUP_KEY, now) }
        AutomaticWorkSignals.markDirty(appContext)
    }

    private fun android.database.Cursor.nullLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun android.database.Cursor.nullDouble(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }
    private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun android.database.Cursor.readCapture(): CaptureDiagnostics {
        val periodUs = int("periodUs")
        val trigger = string("trigger")
        return CaptureDiagnostics(
            id = long("id"),
            windowStart = long("windowStart"),
            registeredAt = long("registeredAt"),
            trigger = trigger,
            targetPeriodUs = nullLong("targetPeriodUs")?.toInt()
                ?: if (trigger == CaptureExperiment.EARLY_2HZ.name) 500_000
                else SamplingPlan.LEGACY_ONE_HZ_TARGET_PERIOD_US,
            periodUs = periodUs,
            latencyUs = int("latencyUs"),
            sensorMinDelayUs = nullLong("sensorMinDelayUs")?.toInt(),
            sensorMaxDelayUs = nullLong("sensorMaxDelayUs")?.toInt(),
            fifoReservedEventCount = nullLong("fifoReservedEventCount")?.toInt(),
            fifoMaxEventCount = int("fifoCount"),
            wakeUp = int("wakeUp") != 0,
            firstEvent = nullLong("firstEvent"),
            rawEvents = long("rawEvents"),
            rejectedEvents = long("rejectedEvents"),
            meanIntervalMillis = nullDouble("meanInterval"),
            maxIntervalMillis = nullLong("maxInterval")
        )
    }
    private fun android.database.Cursor.readMinute() = MotionMinute(
        getLong(0), getLong(1), getLong(2), getDouble(3), getInt(4), Placement.valueOf(getString(5)),
        getInt(getColumnIndexOrThrow("featureVersion")),
        nullDouble("maxDelta"), nullLong("movementEvents")?.toInt(), nullLong("longestActiveMillis"),
        nullLong("quietTailMillis"), nullLong("longestGapMillis"), nullDouble("postureDelta"),
        nullLong("recordingId"), nullLong("observedStart"), nullLong("observedEnd")
    )

    fun read(start: Long, end: Long): List<MotionMinute> = readableDatabase.query(
        "minutes", null, "start >= ? AND start < ?", arrayOf(start.toString(), end.toString()), null, null, "start ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.readMinute())
        }
    }

    private companion object {
        val FEATURE_COLUMNS = listOf("maxDelta" to "REAL", "movementEvents" to "INTEGER", "longestActiveMillis" to "INTEGER",
            "quietTailMillis" to "INTEGER", "longestGapMillis" to "INTEGER", "postureDelta" to "REAL",
            "recordingId" to "INTEGER", "observedStart" to "INTEGER", "observedEnd" to "INTEGER")
        const val CLEANUP_KEY = "motion_last_cleanup"
        const val CLEANUP_INTERVAL = 24L * 60 * 60 * 1000
        const val RETENTION = 14L * 24 * 60 * MINUTE_MS
    }
}
