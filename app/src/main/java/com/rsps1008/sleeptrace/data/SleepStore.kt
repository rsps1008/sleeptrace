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
            } ?: emptyList()
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
            manuallyEdited = true, reason = "使用者已修正時間，App 自動同步", syncError = null))
    }

    fun segments(): List<SleepSegment> = readArray(segmentsKey).map {
        SleepSegment(it.getLong("start"), it.getLong("end"), it.getInt("confidence"), it.optString("source", "Sleep API"))
    }
    fun appendSegments(events: List<SleepSegment>) {
        val cutoff = System.currentTimeMillis() - RAW_RETENTION_MILLIS
        val all = (segments() + events).filter { it.endMillis >= cutoff }.distinctBy { "${it.startMillis}:${it.endMillis}" }
        writeArray(segmentsKey, all.map { JSONObject().put("start", it.startMillis).put("end", it.endMillis).put("confidence", it.confidence).put("source", it.source) })
    }

    fun samples(): List<ClassificationSample> = readArray(samplesKey).map {
        ClassificationSample(it.getLong("time"), it.getInt("confidence"), it.getInt("motion"), it.getInt("light"))
    }
    fun appendSamples(events: List<ClassificationSample>) {
        val cutoff = System.currentTimeMillis() - RAW_RETENTION_MILLIS
        val all = (samples() + events).filter { it.timeMillis >= cutoff }.distinctBy { it.timeMillis }
        writeArray(samplesKey, all.map { JSONObject().put("time", it.timeMillis).put("confidence", it.confidence).put("motion", it.motion).put("light", it.light) })
    }

    private fun SleepSession.toJson() = JSONObject().put("id", id).put("start", startMillis).put("end", endMillis)
        .put("confidence", confidence).put("awake", awakeMillis).put("state", state.name).put("reason", reason)
        .put("manual", manuallyEdited).put("error", syncError ?: "")
        .put("revision", revision).put("awakeIntervals", JSONArray(awakeIntervals.map { JSONObject().put("start", it.startMillis).put("end", it.endMillis) }))
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
        private const val RAW_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
