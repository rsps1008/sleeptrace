package com.rsps1008.sleeptrace.motion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.edit
import androidx.core.database.sqlite.transaction
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals

class MotionSettings(context: Context) {
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
class MotionStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "motion.db", null, 2) {
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
        db.execSQL("CREATE TABLE minutes (start INTEGER PRIMARY KEY, covered INTEGER NOT NULL, active INTEGER NOT NULL, squared REAL NOT NULL, samples INTEGER NOT NULL, placement TEXT NOT NULL, featureVersion INTEGER NOT NULL DEFAULT 1)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE minutes ADD COLUMN featureVersion INTEGER NOT NULL DEFAULT 1")
    }

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
                val compatible = existing?.takeIf { it.placement == item.placement && it.featureVersion == item.featureVersion }
                val row = ContentValues().apply {
                    put("start", item.startMillis)
                    put("covered", minOf(MINUTE_MS, item.coveredMillis + (compatible?.coveredMillis ?: 0)))
                    put("active", minOf(MINUTE_MS, item.activeMillis + (compatible?.activeMillis ?: 0)))
                    put("squared", item.squaredDeltaTime + (compatible?.squaredDeltaTime ?: 0.0))
                    put("samples", item.sampleCount + (compatible?.sampleCount ?: 0))
                    put("placement", item.placement.name)
                    put("featureVersion", item.featureVersion)
                }
                db.insertWithOnConflict("minutes", null, row, SQLiteDatabase.CONFLICT_REPLACE)
                existingByStart[item.startMillis] = MotionMinute(
                    item.startMillis,
                    row.getAsLong("covered"),
                    row.getAsLong("active"),
                    row.getAsDouble("squared"),
                    row.getAsInteger("samples"),
                    item.placement, item.featureVersion
                )
            }
            if (shouldCleanup) {
                db.delete("minutes", "start < ?", arrayOf((now - RETENTION).toString()))
            }
        }
        if (shouldCleanup) maintenancePrefs.edit { putLong(CLEANUP_KEY, now) }
        AutomaticWorkSignals.markDirty(appContext)
    }

    private fun android.database.Cursor.readMinute() = MotionMinute(
        getLong(0), getLong(1), getLong(2), getDouble(3), getInt(4), Placement.valueOf(getString(5)),
        getInt(getColumnIndexOrThrow("featureVersion"))
    )

    fun read(start: Long, end: Long): List<MotionMinute> = readableDatabase.query(
        "minutes", null, "start >= ? AND start < ?", arrayOf(start.toString(), end.toString()), null, null, "start ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.readMinute())
        }
    }

    private companion object {
        const val CLEANUP_KEY = "motion_last_cleanup"
        const val CLEANUP_INTERVAL = 24L * 60 * 60 * 1000
        const val RETENTION = 14L * 24 * 60 * MINUTE_MS
    }
}
