package com.rsps1008.sleeptrace.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import org.json.JSONArray
import org.json.JSONObject

/** Indexed, transactional storage for raw Sleep API events and local sleep records. */
class SleepEventStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "sleep_events.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE segments (start INTEGER NOT NULL, end INTEGER NOT NULL, confidence INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(start, end, source))")
        db.execSQL("CREATE TABLE samples (time INTEGER PRIMARY KEY, confidence INTEGER NOT NULL, motion INTEGER NOT NULL, light INTEGER NOT NULL)")
        createSessions(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSessions(db)
    }

    private fun createSessions(db: SQLiteDatabase) = db.execSQL("""
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
            confidence INTEGER NOT NULL, awake INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL,
            manual INTEGER NOT NULL, error TEXT, revision INTEGER NOT NULL,
            awakeIntervals TEXT NOT NULL, usageSnapshotApplied INTEGER NOT NULL
        )
    """.trimIndent())

    fun import(segments: List<SleepSegment>, samples: List<ClassificationSample>) { append(segments, samples) }
    fun append(segments: List<SleepSegment> = emptyList(), samples: List<ClassificationSample> = emptyList(), now: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            segments.forEach { item ->
                insertWithOnConflict("segments", null, ContentValues().apply { put("start", item.startMillis); put("end", item.endMillis); put("confidence", item.confidence); put("source", item.source) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            samples.forEach { item ->
                insertWithOnConflict("samples", null, ContentValues().apply { put("time", item.timeMillis); put("confidence", item.confidence); put("motion", item.motion); put("light", item.light) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            delete("segments", "end < ?", arrayOf((now - RETENTION).toString()))
            delete("samples", "time < ?", arrayOf((now - RETENTION).toString()))
        }
    }
    fun segments(): List<SleepSegment> = readableDatabase.query("segments", null, null, null, null, null, "start ASC").use { c -> buildList { while (c.moveToNext()) add(SleepSegment(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3))) } }
    fun samples(): List<ClassificationSample> = readableDatabase.query("samples", null, null, null, null, null, "time ASC").use { c -> buildList { while (c.moveToNext()) add(ClassificationSample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3))) } }
    fun importSessions(sessions: List<SleepSession>) = writableDatabase.transaction { sessions.forEach { insertSession(it) } }
    fun replaceSessions(sessions: List<SleepSession>) = writableDatabase.transaction {
        delete("sessions", null, null)
        sessions.forEach { insertSession(it) }
    }
    fun sessions(): List<SleepSession> = readableDatabase.query("sessions", null, null, null, null, null, "start DESC").use { c ->
        buildList {
            while (c.moveToNext()) add(SleepSession(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                startMillis = c.getLong(c.getColumnIndexOrThrow("start")),
                endMillis = c.getLong(c.getColumnIndexOrThrow("end")),
                confidence = c.getInt(c.getColumnIndexOrThrow("confidence")),
                awakeMillis = c.getLong(c.getColumnIndexOrThrow("awake")),
                state = SyncState.fromStored(c.getString(c.getColumnIndexOrThrow("state"))),
                reason = c.getString(c.getColumnIndexOrThrow("reason")),
                manuallyEdited = c.getInt(c.getColumnIndexOrThrow("manual")) != 0,
                syncError = c.getString(c.getColumnIndexOrThrow("error")),
                revision = c.getLong(c.getColumnIndexOrThrow("revision")),
                awakeIntervals = JSONArray(c.getString(c.getColumnIndexOrThrow("awakeIntervals"))).let { array ->
                    List(array.length()) { index -> array.getJSONObject(index).let { UsageInterval(it.getLong("start"), it.getLong("end")) } }
                },
                usageSnapshotApplied = c.getInt(c.getColumnIndexOrThrow("usageSnapshotApplied")) != 0
            ))
        }
    }
    private fun SQLiteDatabase.insertSession(item: SleepSession) {
        insertWithOnConflict("sessions", null, ContentValues().apply {
            put("id", item.id); put("start", item.startMillis); put("end", item.endMillis)
            put("confidence", item.confidence); put("awake", item.awakeMillis); put("state", item.state.name)
            put("reason", item.reason); put("manual", if (item.manuallyEdited) 1 else 0); put("error", item.syncError)
            put("revision", item.revision)
            put("awakeIntervals", JSONArray(item.awakeIntervals.map { JSONObject().put("start", it.startMillis).put("end", it.endMillis) }).toString())
            put("usageSnapshotApplied", if (item.usageSnapshotApplied) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    companion object { private const val RETENTION = 14L * 24 * 60 * 60 * 1000 }
}
