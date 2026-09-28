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
        val usageAvailable = UsageMonitor.hasAccess(context)
        val schedule = preferences.schedule()
        val base = SleepAnalyzer.analyze(
            store.segments(), store.samples(),
            store.segments().flatMap { UsageMonitor.interactionIntervals(context, it.startMillis, it.endMillis) }, schedule
        ).map {
            if (usageAvailable) it else it.copy(reason = "App 依現有資料推估；未授予使用情況存取權，無法排除手機使用")
        }
        val now = System.currentTimeMillis()
        val motion = MotionStore(context).use { it.read(now - 14L * 24 * 60 * 60 * 1000, now) }
        val motionUsage = if (motion.isEmpty()) emptyList() else UsageMonitor.interactionIntervals(context, motion.first().startMillis, now)
        val resolved = AutomaticPlacement.resolve(motion, motionUsage)
        val calculated = base.map { MotionSleepEstimator.annotate(it, resolved) }
        val fallback = MotionSleepEstimator.estimate(resolved, motionUsage, schedule, now).map {
            if (usageAvailable) it else it.copy(reason = it.reason + "；未授予使用情況存取權，無法排除手機使用")
        }
        store.mergeCalculated(selectBestSessions(calculated, fallback))
    }
    companion object { private val mutex = Mutex() }
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
        val matches = result.filter { it.id == candidate.id || overlaps(it, candidate) }
        if (matches.any { it.manuallyEdited }) return@forEach
        // Avoid silently merging multiple already-exported identities into one remote record.
        if (matches.size > 1) return@forEach
        val old = matches.firstOrNull()
        if (old != null && old.startMillis == candidate.startMillis && old.endMillis == candidate.endMillis &&
            old.awakeMillis == candidate.awakeMillis && old.awakeIntervals == candidate.awakeIntervals) return@forEach
        result.removeAll(matches.toSet())
        result += if (old == null) candidate else candidate.copy(id = old.id, revision = old.revision + 1, state = SyncState.PENDING, syncError = null)
    }
    return result.sortedByDescending { it.startMillis }
}
