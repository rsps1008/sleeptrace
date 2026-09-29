package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.motion.MotionSleepEstimator
import com.rsps1008.sleeptrace.motion.AutomaticPlacement
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.sleepDependencies
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SleepReconciler(private val context: Context) {
    suspend fun reconcile() = mutex.withLock {
        val dependencies = context.sleepDependencies()
        val preferences = dependencies.preferences
        if (!preferences.configured() || !dependencies.motionSettings.enabled) return@withLock
        val store = dependencies.store
        val capturedGeneration = AutomaticWorkSignals.generation(context)
        val schedule = preferences.schedule()
        val now = System.currentTimeMillis()
        val analysisStart = now - RECENT_ANALYSIS_MILLIS
        // Keep unresolved old sessions eligible for matching without loading all historical
        // sessions or deserializing awakeIntervals for completed history.
        val unresolved = store.sessions(
            includeAwakeIntervals = false,
            states = RECONCILIATION_STATES
        )
        val segmentStart = minOf(analysisStart, unresolved.minOfOrNull { it.startMillis } ?: analysisStart)
        val allSegments = store.segments(segmentStart, now)
        val samples = store.recentSamples(analysisStart)
        val windows = schedule.windowsBetween(segmentStart, now)
        val completedWindows = SleepUsageSnapshot.completedWindows(windows, now)
        val usageResult = SleepUsageSnapshot(context).captureWindows(store, completedWindows, now)
        val segments = allSegments.filter { segment ->
            segment.endMillis >= analysisStart || unresolved.any { it.startMillis < segment.endMillis && it.endMillis > segment.startMillis }
        }
        val base = SleepAnalyzer.analyzeByWindow(
            segments, samples, usageResult.intervals, schedule, completedWindows, usageResult::availableFor
        )
        val motion = dependencies.motionStore.read(analysisStart, now)
        val resolved = AutomaticPlacement.resolve(motion, usageResult.intervals)
        val calculated = base.map { MotionSleepEstimator.annotate(it, resolved) }
        val fallback = MotionSleepEstimator.estimate(
            resolved, usageResult.intervals, schedule, now,
            usageAvailable = usageResult::availableFor
        ).mapNotNull { candidate -> confirmMotionCandidateOnset(candidate, segments, samples) }
        val staged = selectBestSessions(calculated, fallback).map { session ->
            val sessionUsage = usageResult.intervals.filter {
                it.endMillis > session.startMillis && it.startMillis < session.endMillis
            }
            session.copy(stageIntervals = SleepStageEstimator.estimate(
                session = session,
                motionMinutes = resolved,
                classifications = samples,
                usageIntervals = sessionUsage,
                sleepSegments = segments,
                schedule = schedule
            ))
        }
        store.mergeCalculated(staged, analysisStart, now)
        store.sessionsInRange(analysisStart, now)
            .filter { it.manuallyEdited && it.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT) }
            .forEach { session ->
                val sessionUsage = usageResult.intervals.filter {
                    it.endMillis > session.startMillis && it.startMillis < session.endMillis
                }
                store.updateStageIntervals(session, SleepStageEstimator.estimate(
                    session = session,
                    motionMinutes = resolved,
                    classifications = samples,
                    usageIntervals = sessionUsage,
                    sleepSegments = segments,
                    schedule = schedule
                ))
            }
        store.markReconciled(capturedGeneration)
    }
    companion object {
        private val mutex = Mutex()
        private const val RECENT_ANALYSIS_MILLIS = 48L * 60 * 60 * 1000
        private val RECONCILIATION_STATES = setOf(
            SyncState.PENDING, SyncState.SYNCING, SyncState.FAILED_RETRYABLE
        )
    }
}

/** Motion-only stillness may refine a confirmed onset, but can never establish sleep by itself. */
internal fun confirmMotionCandidateOnset(
    candidate: SleepSession,
    segments: List<SleepSegment>,
    classifications: List<ClassificationSample>
): SleepSession? {
    val segmentOnsets = segments.asSequence()
        .filter { it.startMillis < candidate.endMillis && it.endMillis > candidate.startMillis }
        .map { maxOf(candidate.startMillis, it.startMillis) }
    val classificationOnsets = classifications.asSequence()
        .filter {
            it.confidence >= 80 && it.timeMillis >= candidate.startMillis && it.timeMillis < candidate.endMillis
        }
        .map { it.timeMillis }
    val confirmedStart = (segmentOnsets + classificationOnsets).minOrNull() ?: return null
    val start = maxOf(candidate.startMillis, confirmedStart)
    if (candidate.endMillis - start < SleepAnalyzer.MINIMUM_SLEEP_MILLIS) return null
    return candidate.copy(
        id = "${candidate.id}-$start",
        startMillis = start,
        awakeMillis = 0,
        awakeIntervals = emptyList(),
        reason = candidate.reason + "；手機靜止僅延伸 Sleep API 已確認的睡眠起點"
    )
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
        val matches = result.filter {
            it.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT) &&
                (it.id == candidate.id || overlaps(it, candidate))
        }
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
        // Keep a previously applied snapshot if the recalculation has no replacement snapshot.
        // Once a complete replacement snapshot is applied, changed awake intervals revise the row.
        if (old?.usageSnapshotApplied == true && !candidate.usageSnapshotApplied &&
            old.startMillis == candidate.startMillis && old.endMillis == candidate.endMillis) return@forEach
        if (old != null && old.startMillis == candidate.startMillis && old.endMillis == candidate.endMillis &&
            old.awakeMillis == candidate.awakeMillis && old.awakeIntervals == candidate.awakeIntervals &&
            old.stageIntervals == candidate.stageIntervals) return@forEach
        result.removeAll(matches.toSet())
        result += if (old == null) candidate else candidate.copy(id = old.id, revision = old.revision + 1, state = SyncState.PENDING, syncError = null)
    }
    return result.sortedByDescending { it.startMillis }
}
