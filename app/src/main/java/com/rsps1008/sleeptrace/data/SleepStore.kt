package com.rsps1008.sleeptrace.data

import android.content.Context
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.mergeSleepSessions
import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.UsageSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Local repository. Raw events expire after fourteen days; sessions use the same SQLite database. */
class SleepStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("sleeptrace_records", Context.MODE_PRIVATE)
    private val sessionsKey = "sessions"
    private val segmentsKey = "segments"
    private val samplesKey = "samples"
    private val eventStore = SleepEventStore(context)

    private fun legacySessions(): List<SleepSession> = readArray(sessionsKey).map { item ->
        SleepSession(
            id = item.getString("id"), startMillis = item.getLong("start"), endMillis = item.getLong("end"),
            confidence = item.getInt("confidence"), awakeMillis = item.getLong("awake"),
            state = SyncState.fromStored(item.getString("state")),
            reason = if (item.getString("state") == "NEEDS_REVIEW") "舊版紀錄已改為 App 自動判斷與同步" else item.getString("reason"),
            manuallyEdited = item.optBoolean("manual"), syncError = item.optString("error").ifBlank { null },
            revision = item.optLong("revision", 1),
            awakeIntervals = item.optJSONArray("awakeIntervals")?.let { array ->
                List(array.length()) { index -> array.getJSONObject(index).let { UsageInterval(it.getLong("start"), it.getLong("end")) } }
            } ?: emptyList(),
            // Existing records were already calculated with the old pre-reconcile query.
            usageSnapshotApplied = item.optBoolean("usageSnapshotApplied", true)
        )
    }.sortedByDescending { it.startMillis }

    fun sessions(
        limit: Int? = null,
        offset: Int = 0,
        includeAwakeIntervals: Boolean = true,
        states: Set<SyncState>? = null
    ): List<SleepSession> {
        migrateSessions()
        return eventStore.sessions(limit, offset, includeAwakeIntervals, states)
    }

    fun hasPendingAutomaticWork(): Boolean = ReconciliationSignals.isDirty(appContext) || sessions(
        includeAwakeIntervals = false,
        states = setOf(SyncState.PENDING, SyncState.FAILED_RETRYABLE, SyncState.SYNCING, SyncState.RETIRED)
    ).isNotEmpty()

    fun hasReconciliationDirty(): Boolean = ReconciliationSignals.isDirty(appContext)

    fun markReconciled(generation: Long) = ReconciliationSignals.markReconciled(appContext, generation)

    fun session(id: String, includeAwakeIntervals: Boolean = true): SleepSession? {
        migrateSessions()
        return eventStore.session(id, includeAwakeIntervals)
    }

    fun saveSessions(sessions: List<SleepSession>) { migrateSessions(); eventStore.replaceSessions(sessions) }
    fun upsert(session: SleepSession) = synchronized(sessionLock) {
        migrateSessions()
        eventStore.upsertSession(session)
    }
    fun mergeCalculated(calculated: List<SleepSession>, analysisStartMillis: Long, analysisEndMillis: Long) = synchronized(sessionLock) {
        migrateSessions()
        val existing = eventStore.sessionsForReconciliation(
            startMillis = analysisStartMillis,
            endMillis = analysisEndMillis,
            unresolvedStates = RECONCILIATION_STATES
        )
        val merged = mergeSleepSessions(existing, calculated)
        val existingById = existing.associateBy { it.id }
        val mergedIds = merged.mapTo(mutableSetOf()) { it.id }
        val removedIds = existing.asSequence()
            .map { it.id }
            .filterNot { it in mergedIds }
            .toSet()
        val changed = merged.filter { existingById[it.id] != it }
        eventStore.applySessionDiff(removedIds, changed)
    }
    fun updateIfCurrent(expected: SleepSession, replacement: SleepSession): Boolean = synchronized(sessionLock) {
        migrateSessions()
        if (eventStore.session(expected.id) != expected) return@synchronized false
        eventStore.upsertSession(replacement)
        true
    }

    fun reviseTimes(id: String, start: Long, end: Long) = synchronized(sessionLock) {
        migrateSessions()
        val current = eventStore.session(id) ?: return@synchronized
        // Keep the time correction local. The one shared UsageStats snapshot is applied right before upload.
        upsert(current.copy(startMillis = start, endMillis = end, awakeMillis = 0, awakeIntervals = emptyList(),
            revision = current.revision + 1, state = SyncState.PENDING,
            manuallyEdited = true, reason = "使用者已修正時間，App 自動同步", syncError = null,
            usageSnapshotApplied = false))
    }

    fun retryPermanentFailure(id: String): Boolean = synchronized(sessionLock) {
        migrateSessions()
        val current = eventStore.session(id) ?: return@synchronized false
        val retryState = when (current.state) {
            SyncState.FAILED_PERMANENT -> SyncState.PENDING
            SyncState.RETIRED_FAILED_PERMANENT -> SyncState.RETIRED
            else -> return@synchronized false
        }
        eventStore.upsertSession(current.copy(state = retryState, syncError = null))
        true
    }

    fun retryPermanentFailures() {
        val failed = sessions(states = setOf(SyncState.FAILED_PERMANENT, SyncState.RETIRED_FAILED_PERMANENT))
        failed.forEach { current -> retryPermanentFailure(current.id) }
    }

    private fun legacySegments(): List<SleepSegment> = readArray(segmentsKey).map {
        SleepSegment(it.getLong("start"), it.getLong("end"), it.getInt("confidence"), it.optString("source", "Sleep API"))
    }
    fun segments(): List<SleepSegment> { migrateRawEvents(); return eventStore.segments() }
    fun segments(sinceMillis: Long, untilMillis: Long? = null): List<SleepSegment> {
        migrateRawEvents()
        return eventStore.segments(sinceMillis, untilMillis)
    }
    fun appendSegments(events: List<SleepSegment>) {
        migrateRawEvents()
        eventStore.append(segments = events)
        if (events.isNotEmpty()) ReconciliationSignals.markDirty(appContext)
    }

    private fun legacySamples(): List<ClassificationSample> = readArray(samplesKey).map {
        ClassificationSample(it.getLong("time"), it.getInt("confidence"), it.getInt("motion"), it.getInt("light"))
    }
    fun samples(): List<ClassificationSample> { migrateRawEvents(); return eventStore.samples() }
    fun latestSample(): ClassificationSample? { migrateRawEvents(); return eventStore.latestSample() }
    fun recentSamples(sinceMillis: Long): List<ClassificationSample> { migrateRawEvents(); return eventStore.recentSamples(sinceMillis) }
    fun appendSamples(events: List<ClassificationSample>) {
        migrateRawEvents()
        eventStore.append(samples = events)
        if (events.isNotEmpty()) ReconciliationSignals.markDirty(appContext)
    }

    fun usageSnapshot(windowStartMillis: Long): UsageSnapshot? {
        migrateRawEvents()
        return eventStore.usageSnapshot(windowStartMillis)
    }

    fun saveUsageSnapshot(snapshot: UsageSnapshot) {
        migrateRawEvents()
        eventStore.saveUsageSnapshot(snapshot)
    }

    private fun migrateRawEvents() = synchronized(rawLock) {
        if (preferences.getBoolean(rawMigrationKey, false)) return@synchronized
        val segments = legacySegments()
        val samples = legacySamples()
        eventStore.import(segments, samples)
        if (segments.isNotEmpty() || samples.isNotEmpty()) ReconciliationSignals.markDirty(appContext)
        check(preferences.edit().remove(segmentsKey).remove(samplesKey).putBoolean(rawMigrationKey, true).commit()) { "睡眠事件遷移失敗" }
    }

    private fun migrateSessions() = synchronized(sessionMigrationLock) {
        if (preferences.getBoolean(sessionMigrationKey, false)) return@synchronized
        eventStore.importSessions(legacySessions())
        check(preferences.edit().remove(sessionsKey).putBoolean(sessionMigrationKey, true).commit()) { "睡眠紀錄遷移失敗" }
    }

    private fun readArray(key: String): List<JSONObject> {
        val array = JSONArray(preferences.getString(key, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }
    companion object {
        private val sessionLock = Any()
        private val rawLock = Any()
        private val sessionMigrationLock = Any()
        private const val rawMigrationKey = "raw_events_migrated_v1"
        private const val sessionMigrationKey = "sessions_migrated_v2"
        private val RECONCILIATION_STATES = setOf(
            SyncState.PENDING, SyncState.SYNCING, SyncState.FAILED_RETRYABLE, SyncState.FAILED_PERMANENT
        )
    }
}
