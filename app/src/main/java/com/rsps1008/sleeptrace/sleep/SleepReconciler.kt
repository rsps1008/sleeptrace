package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.motion.MotionStore
import com.rsps1008.sleeptrace.motion.MotionSleepEstimator
import com.rsps1008.sleeptrace.motion.AutomaticPlacement
import com.rsps1008.sleeptrace.motion.MotionSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SleepReconciler(private val context: Context) {
    suspend fun reconcile() = mutex.withLock {
        val preferences = SleepPreferences(context)
        if (!preferences.configured() || !MotionSettings(context).enabled) return@withLock
        val store = SleepStore(context)
        val schedule = preferences.schedule()
        val now = System.currentTimeMillis()
        val analysisStart = now - RECENT_ANALYSIS_MILLIS
        val allSegments = store.segments()
        val samples = store.samples()
        val existing = store.sessions()
        val unresolved = existing.filter { it.state !in setOf(SyncState.SYNCED, SyncState.SKIPPED, SyncState.RETIRED) }
        val segments = allSegments.filter { segment ->
            segment.endMillis >= analysisStart || unresolved.any { it.startMillis < segment.endMillis && it.endMillis > segment.startMillis }
        }
        val base = SleepAnalyzer.analyze(
            segments, samples, emptyList(), schedule
        )
        val motion = MotionStore(context).use { it.read(analysisStart, now) }
        val resolved = AutomaticPlacement.resolve(motion, emptyList())
        val calculated = base.map { MotionSleepEstimator.annotate(it, resolved) }
        val fallback = MotionSleepEstimator.estimate(resolved, emptyList(), schedule, now)
        store.mergeCalculated(selectBestSessions(calculated, fallback))
    }
    companion object {
        private val mutex = Mutex()
        private const val RECENT_ANALYSIS_MILLIS = 48L * 60 * 60 * 1000
    }
}

private fun overlaps(a: SleepSession, b: SleepSession) = a.startMillis < b.endMillis && a.endMillis > b.startMillis

/** Resolve conflicting sources automatically. Scores rank estimates; they are not accuracy percentages. */
fun selectBestSessions(api: List<SleepSession>, motion: List<SleepSession>): List<SleepSession> {
    val selected = mutableListOf<SleepSession>()
    (api + motion).sortedByDescending { it.confidence }.forEach { candidate ->
        if (selected.none { overlaps(it, candidate) }) selected += candidate
    }
    return selected
}

/** Stable identity/version supports idempotent writes and later automatic corrections. */
fun mergeSleepSessions(existing: List<SleepSession>, calculated: List<SleepSession>): List<SleepSession> {
    val result = existing.toMutableList()
    calculated.forEach { candidate ->
        val matches = result.filter { it.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED) && (it.id == candidate.id || overlaps(it, candidate)) }
        if (matches.any { it.manuallyEdited }) return@forEach
        val old = matches.firstOrNull()
        if (matches.size > 1) {
            // Keep one identity/version as the replacement record. Any other successful remote
            // records must be deleted before the replacement is sent; unsynced fragments are local only.
            val canonical = matches.firstOrNull { it.state == SyncState.SYNCED } ?: old!!
            result.removeAll(matches.toSet())
            result += matches.filter { it != canonical && it.state == SyncState.SYNCED }.map {
                it.copy(state = SyncState.RETIRED, syncError = null, reason = "已由較完整的睡眠紀錄取代，等待移除舊的 Health Connect 資料")
            }
            result += candidate.copy(id = canonical.id, revision = canonical.revision + 1, state = SyncState.PENDING, syncError = null)
            return@forEach
        }
        // Phone-use deduction is frozen immediately before the first upload; a routine raw-event
        // reconciliation with the same interval must not erase it and trigger another scan.
        if (old?.usageSnapshotApplied == true && old.startMillis == candidate.startMillis && old.endMillis == candidate.endMillis) return@forEach
        if (old != null && old.startMillis == candidate.startMillis && old.endMillis == candidate.endMillis &&
            old.awakeMillis == candidate.awakeMillis && old.awakeIntervals == candidate.awakeIntervals) return@forEach
        result.removeAll(matches.toSet())
        result += if (old == null) candidate else candidate.copy(id = old.id, revision = old.revision + 1, state = SyncState.PENDING, syncError = null)
    }
    return result.sortedByDescending { it.startMillis }
}
