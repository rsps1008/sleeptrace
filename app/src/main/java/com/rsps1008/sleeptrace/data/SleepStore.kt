package com.rsps1008.sleeptrace.data

import android.content.Context
import android.annotation.SuppressLint
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.mergeSleepSessions
import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.normalizedAwake
import org.json.JSONArray
import org.json.JSONObject

/** Small local repository. Raw events expire after fourteen days. */
class SleepStore(context: Context) {
    private val preferences = context.getSharedPreferences("sleeptrace_records", Context.MODE_PRIVATE)
    private val sessionsKey = "sessions"
    private val segmentsKey = "segments"
    private val samplesKey = "samples"
    private val eventStore = SleepEventStore(context)

    fun sessions(): List<SleepSession> = readArray(sessionsKey).map { item ->
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

    fun saveSessions(sessions: List<SleepSession>) = writeArray(sessionsKey, sessions.map { it.toJson() })
    fun upsert(session: SleepSession) = synchronized(sessionLock) {
        val current = sessions().filterNot { it.id == session.id }.plus(session)
        saveSessions(current)
    }
    fun mergeCalculated(calculated: List<SleepSession>) = synchronized(sessionLock) {
        saveSessions(mergeSleepSessions(sessions(), calculated))
    }
    fun updateIfCurrent(expected: SleepSession, replacement: SleepSession): Boolean = synchronized(sessionLock) {
        val current = sessions()
        if (current.none { it == expected }) return@synchronized false
        saveSessions(current.map { if (it.id == expected.id) replacement else it })
        true
    }

    fun reviseTimes(id: String, start: Long, end: Long, usage: List<UsageInterval>) = synchronized(sessionLock) {
        val current = sessions().firstOrNull { it.id == id } ?: return@synchronized
        val awake = normalizedAwake(start, end, current.awakeIntervals + usage)
        upsert(current.copy(startMillis = start, endMillis = end, awakeMillis = awake.sumOf { it.endMillis - it.startMillis },
            awakeIntervals = awake, revision = current.revision + 1, state = SyncState.PENDING,
            manuallyEdited = true, reason = "使用者已修正時間，App 自動同步", syncError = null,
            usageSnapshotApplied = true))
    }

    private fun legacySegments(): List<SleepSegment> = readArray(segmentsKey).map {
        SleepSegment(it.getLong("start"), it.getLong("end"), it.getInt("confidence"), it.optString("source", "Sleep API"))
    }
    fun segments(): List<SleepSegment> { migrateRawEvents(); return eventStore.segments() }
    fun appendSegments(events: List<SleepSegment>) { migrateRawEvents(); eventStore.append(segments = events) }

    private fun legacySamples(): List<ClassificationSample> = readArray(samplesKey).map {
        ClassificationSample(it.getLong("time"), it.getInt("confidence"), it.getInt("motion"), it.getInt("light"))
    }
    fun samples(): List<ClassificationSample> { migrateRawEvents(); return eventStore.samples() }
    fun appendSamples(events: List<ClassificationSample>) { migrateRawEvents(); eventStore.append(samples = events) }

    private fun migrateRawEvents() = synchronized(rawLock) {
        if (preferences.getBoolean(rawMigrationKey, false)) return@synchronized
        eventStore.import(legacySegments(), legacySamples())
        check(preferences.edit().remove(segmentsKey).remove(samplesKey).putBoolean(rawMigrationKey, true).commit()) { "睡眠事件遷移失敗" }
    }

    private fun SleepSession.toJson() = JSONObject().put("id", id).put("start", startMillis).put("end", endMillis)
        .put("confidence", confidence).put("awake", awakeMillis).put("state", state.name).put("reason", reason)
        .put("manual", manuallyEdited).put("error", syncError ?: "")
        .put("revision", revision).put("awakeIntervals", JSONArray(awakeIntervals.map { JSONObject().put("start", it.startMillis).put("end", it.endMillis) }))
        .put("usageSnapshotApplied", usageSnapshotApplied)
    private fun readArray(key: String): List<JSONObject> {
        val array = JSONArray(preferences.getString(key, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }
    @SuppressLint("UseKtx") // KTX edit discards commit's success flag; do not upload after a failed local write.
    private fun writeArray(key: String, objects: List<JSONObject>) {
        // Persist identity/version before any external insert, including process-death recovery.
        check(preferences.edit().putString(key, JSONArray(objects).toString()).commit()) { "睡眠紀錄儲存失敗" }
    }
    companion object {
        private val sessionLock = Any()
        private val rawLock = Any()
        private const val rawMigrationKey = "raw_events_migrated_v1"
    }
}
