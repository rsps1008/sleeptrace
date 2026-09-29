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
class SleepEventStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "sleep_events.db", null, 6) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE segments (start INTEGER NOT NULL, end INTEGER NOT NULL, confidence INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(start, end, source))")
        db.execSQL("CREATE TABLE samples (time INTEGER PRIMARY KEY, confidence INTEGER NOT NULL, motion INTEGER NOT NULL, light INTEGER NOT NULL)")
        createSessions(db)
        createSegmentIndex(db)
        createSessionIndex(db)
        createSessionStateIndex(db)
        createSessionOverlapIndex(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSessions(db)
        if (oldVersion < 3) createSegmentIndex(db)
        if (oldVersion < 4) createSessionIndex(db)
        if (oldVersion < 5) createSessionStateIndex(db)
        if (oldVersion < 6) createSessionOverlapIndex(db)
    }

    private fun createSessions(db: SQLiteDatabase) = db.execSQL("""
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
            confidence INTEGER NOT NULL, awake INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL,
            manual INTEGER NOT NULL, error TEXT, revision INTEGER NOT NULL,
            awakeIntervals TEXT NOT NULL, usageSnapshotApplied INTEGER NOT NULL
        )
    """.trimIndent())

    private fun createSegmentIndex(db: SQLiteDatabase) = db.execSQL(
        "CREATE INDEX IF NOT EXISTS segments_end_start_idx ON segments(end, start)"
    )

    private fun createSessionIndex(db: SQLiteDatabase) = db.execSQL(
        "CREATE INDEX IF NOT EXISTS sessions_start_idx ON sessions(start DESC)"
    )

    private fun createSessionStateIndex(db: SQLiteDatabase) = db.execSQL(
        "CREATE INDEX IF NOT EXISTS sessions_state_idx ON sessions(state)"
    )

    private fun createSessionOverlapIndex(db: SQLiteDatabase) = db.execSQL(
        "CREATE INDEX IF NOT EXISTS sessions_end_start_idx ON sessions(end, start)"
    )

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
    fun segments(): List<SleepSegment> = querySegments(null, null)
    fun segments(sinceMillis: Long, untilMillis: Long? = null): List<SleepSegment> = querySegments(sinceMillis, untilMillis)
    private fun querySegments(sinceMillis: Long?, untilMillis: Long?): List<SleepSegment> {
        val selection = when {
            sinceMillis != null && untilMillis != null -> "end >= ? AND start <= ?"
            sinceMillis != null -> "end >= ?"
            untilMillis != null -> "start <= ?"
            else -> null
        }
        val args = when {
            sinceMillis != null && untilMillis != null -> arrayOf(sinceMillis.toString(), untilMillis.toString())
            sinceMillis != null -> arrayOf(sinceMillis.toString())
            untilMillis != null -> arrayOf(untilMillis.toString())
            else -> null
        }
        return readableDatabase.query("segments", null, selection, args, null, null, "start ASC").use { c ->
            buildList { while (c.moveToNext()) add(SleepSegment(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3))) }
        }
    }
    fun samples(): List<ClassificationSample> = readableDatabase.query("samples", null, null, null, null, null, "time ASC").use { c -> buildList { while (c.moveToNext()) add(ClassificationSample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3))) } }
    fun latestSample(): ClassificationSample? = readableDatabase.query(
        "samples", null, null, null, null, null, "time DESC", "1"
    ).use { c ->
        if (!c.moveToFirst()) null else ClassificationSample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3))
    }
    fun recentSamples(sinceMillis: Long): List<ClassificationSample> = readableDatabase.query(
        "samples", null, "time >= ?", arrayOf(sinceMillis.toString()), null, null, "time ASC"
    ).use { c ->
        buildList { while (c.moveToNext()) add(ClassificationSample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3))) }
    }
    fun importSessions(sessions: List<SleepSession>) = writableDatabase.transaction { sessions.forEach { insertSession(it) } }
    fun replaceSessions(sessions: List<SleepSession>) = writableDatabase.transaction {
        delete("sessions", null, null)
        sessions.forEach { insertSession(it) }
    }
    fun upsertSession(session: SleepSession) = writableDatabase.transaction { insertSession(session) }

    /** Applies only rows changed by reconciliation, preserving untouched history. */
    fun applySessionDiff(removeIds: Set<String>, upserts: List<SleepSession>) {
        if (removeIds.isEmpty() && upserts.isEmpty()) return
        writableDatabase.transaction {
            removeIds.forEach { id -> delete("sessions", "id = ?", arrayOf(id)) }
            upserts.forEach { insertSession(it) }
        }
    }

    /** Loads the analysis window plus old records still waiting for sync. */
    fun sessionsForReconciliation(
        startMillis: Long,
        endMillis: Long,
        unresolvedStates: Set<SyncState>,
        includeAwakeIntervals: Boolean = true
    ): List<SleepSession> {
        require(endMillis >= startMillis) { "endMillis must not be before startMillis" }
        val states = unresolvedStates.sortedBy { it.name }
        val stateClause = if (states.isEmpty()) null else
            "state IN (${states.joinToString(",") { "?" }})"
        val selection = if (stateClause == null) {
            "end >= ? AND start <= ?"
        } else {
            "(end >= ? AND start <= ?) OR $stateClause"
        }
        val args = buildList {
            add(startMillis.toString())
            add(endMillis.toString())
            addAll(states.map { it.name })
        }
        return readableDatabase.query(
            "sessions", if (includeAwakeIntervals) null else SESSION_SUMMARY_COLUMNS,
            selection, args.toTypedArray(), null, null, "start DESC, id DESC"
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.readSession(includeAwakeIntervals)) }
        }
    }

    fun session(id: String, includeAwakeIntervals: Boolean = true): SleepSession? = readableDatabase.query(
        "sessions", if (includeAwakeIntervals) null else SESSION_SUMMARY_COLUMNS,
        "id = ?", arrayOf(id), null, null, null, "1"
    ).use { c -> if (c.moveToFirst()) c.readSession(includeAwakeIntervals) else null }
    fun sessions(
        limit: Int? = null,
        offset: Int = 0,
        includeAwakeIntervals: Boolean = true,
        states: Set<SyncState>? = null
    ): List<SleepSession> {
        require(limit == null || limit >= 0) { "limit must be non-negative" }
        require(offset >= 0) { "offset must be non-negative" }
        val stateList = states?.sortedBy { it.name }
        val selection = when {
            stateList == null -> null
            stateList.isEmpty() -> "0"
            else -> "state IN (${stateList.joinToString(",") { "?" }})"
        }
        val limitClause = when {
            limit != null && offset > 0 -> "$limit OFFSET $offset"
            limit != null -> limit.toString()
            offset > 0 -> "-1 OFFSET $offset"
            else -> null
        }
        val columns = if (includeAwakeIntervals) null else SESSION_SUMMARY_COLUMNS
        return readableDatabase.query(
            "sessions", columns, selection, stateList?.map { it.name }?.toTypedArray(),
            null, null, "start DESC, id DESC", limitClause
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(c.readSession(includeAwakeIntervals))
            }
        }
    }
    private fun android.database.Cursor.readSession(includeAwakeIntervals: Boolean = true): SleepSession = SleepSession(
        id = getString(getColumnIndexOrThrow("id")),
        startMillis = getLong(getColumnIndexOrThrow("start")),
        endMillis = getLong(getColumnIndexOrThrow("end")),
        confidence = getInt(getColumnIndexOrThrow("confidence")),
        awakeMillis = getLong(getColumnIndexOrThrow("awake")),
        state = SyncState.fromStored(getString(getColumnIndexOrThrow("state"))),
        reason = getString(getColumnIndexOrThrow("reason")),
        manuallyEdited = getInt(getColumnIndexOrThrow("manual")) != 0,
        syncError = getString(getColumnIndexOrThrow("error")),
        revision = getLong(getColumnIndexOrThrow("revision")),
        awakeIntervals = if (includeAwakeIntervals) {
            JSONArray(getString(getColumnIndexOrThrow("awakeIntervals"))).let { array ->
                List(array.length()) { index -> array.getJSONObject(index).let { UsageInterval(it.getLong("start"), it.getLong("end")) } }
            }
        } else emptyList(),
        usageSnapshotApplied = getInt(getColumnIndexOrThrow("usageSnapshotApplied")) != 0
    )
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
    companion object {
        private val SESSION_SUMMARY_COLUMNS = arrayOf(
            "id", "start", "end", "confidence", "awake", "state", "reason", "manual", "error", "revision", "usageSnapshotApplied"
        )
        private const val RETENTION = 14L * 24 * 60 * 60 * 1000
    }
}
