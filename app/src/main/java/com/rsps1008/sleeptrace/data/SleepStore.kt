package com.rsps1008.sleeptrace.data

import android.content.Context
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepStage
import com.rsps1008.sleeptrace.sleep.SleepStageInterval
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
            usageSnapshotApplied = item.optBoolean("usageSnapshotApplied", true),
            stageIntervals = item.optJSONArray("stageIntervals")?.let { array ->
                List(array.length()) { index -> array.getJSONObject(index).let {
                    SleepStageInterval(it.getLong("start"), it.getLong("end"), SleepStage.valueOf(it.getString("stage")))
                } }
            } ?: emptyList()
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

    fun hasPendingAutomaticWork(): Boolean = AutomaticWorkSignals.isDirty(appContext) || sessions(
        includeAwakeIntervals = false,
        states = setOf(SyncState.PENDING, SyncState.FAILED_RETRYABLE, SyncState.SYNCING, SyncState.RETIRED)
    ).isNotEmpty()

    fun hasReconciliationDirty(): Boolean = AutomaticWorkSignals.isDirty(appContext)

    fun markReconciled(generation: Long, contiguousCompletedWindowEndMillis: Long? = null) =
        AutomaticWorkSignals.markReconciled(appContext, generation, contiguousCompletedWindowEndMillis)

    fun session(id: String, includeAwakeIntervals: Boolean = true): SleepSession? {
        migrateSessions()
        return eventStore.session(id, includeAwakeIntervals)
    }

    /** Recent sessions only, for refreshing manually edited stage estimates without loading all history. */
    fun sessionsInRange(startMillis: Long, endMillis: Long): List<SleepSession> {
        migrateSessions()
        return eventStore.sessionsForReconciliation(startMillis, endMillis, emptySet())
    }

    fun updateStageIntervals(expected: SleepSession, stageIntervals: List<SleepStageInterval>, algorithmVersion: Int? = expected.stageAlgorithmVersion,
        featureVersion: Int? = expected.stageFeatureVersion): Boolean = synchronized(sessionLock) {
        migrateSessions()
        if (expected.stageIntervals == stageIntervals) {
            if (eventStore.session(expected.id) != expected) return@synchronized false
            if (expected.stageAlgorithmVersion == algorithmVersion && expected.stageFeatureVersion == featureVersion) return@synchronized false
            eventStore.upsertSession(expected.copy(stageAlgorithmVersion = algorithmVersion, stageFeatureVersion = featureVersion))
            return@synchronized true
        }
        val replacement = expected.copy(
            stageIntervals = stageIntervals, stageAlgorithmVersion = algorithmVersion, stageFeatureVersion = featureVersion,
            revision = expected.revision + 1,
            state = if (expected.state in setOf(SyncState.SYNCED, SyncState.SYNCING)) SyncState.PENDING else expected.state,
            syncError = if (expected.state in setOf(SyncState.SYNCED, SyncState.SYNCING)) null else expected.syncError
        )
        if (eventStore.session(expected.id) != expected) return@synchronized false
        eventStore.upsertSession(replacement)
        true
    }

    fun saveSessions(sessions: List<SleepSession>) { migrateSessions(); eventStore.replaceSessions(sessions) }
    fun upsert(session: SleepSession) = synchronized(sessionLock) {
        migrateSessions()
        eventStore.upsertSession(session)
    }
    fun mergeCalculated(
        calculated: List<SleepSession>,
        analysisStartMillis: Long,
        analysisEndMillis: Long,
        invalidatedAutomaticSessionIds: Set<String> = emptySet(),
        expectedGenerationForInvalidation: Long? = null
    ) = synchronized(sessionLock) {
        migrateSessions()
        val existing = eventStore.sessionsForReconciliation(
            startMillis = analysisStartMillis,
            endMillis = analysisEndMillis,
            unresolvedStates = RECONCILIATION_STATES
        )
        val safeInvalidatedIds = if (expectedGenerationForInvalidation != null &&
            AutomaticWorkSignals.generation(appContext) != expectedGenerationForInvalidation
        ) emptySet() else invalidatedAutomaticSessionIds
        val merged = mergeSleepSessions(existing, calculated, safeInvalidatedIds)
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
        // Keep the time correction local. Legacy readiness is applied right before upload.
        upsert(current.copy(startMillis = start, endMillis = end, awakeMillis = 0, awakeIntervals = emptyList(),
            revision = current.revision + 1, state = SyncState.PENDING,
            manuallyEdited = true, reason = "使用者已修正時間，App 自動同步", syncError = null,
            usageSnapshotApplied = false, stageIntervals = emptyList()))
        AutomaticWorkSignals.markDirty(appContext)
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
        AutomaticWorkSignals.markDirty(appContext)
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
        if (events.isNotEmpty()) AutomaticWorkSignals.markDirty(appContext, events.minOf { it.startMillis })
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
        if (events.isNotEmpty()) AutomaticWorkSignals.markDirty(appContext, events.minOf { it.timeMillis })
    }

    fun usageSnapshot(windowStartMillis: Long, windowEndMillis: Long): UsageSnapshot? {
        migrateRawEvents()
        return eventStore.usageSnapshot(windowStartMillis, windowEndMillis)
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
        if (segments.isNotEmpty() || samples.isNotEmpty()) AutomaticWorkSignals.markDirty(appContext)
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
            SyncState.PENDING, SyncState.SYNCING, SyncState.FAILED_RETRYABLE
        )
    }
}
