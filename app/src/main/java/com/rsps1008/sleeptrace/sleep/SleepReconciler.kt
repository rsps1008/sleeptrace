package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.motion.MotionSleepEstimator
import com.rsps1008.sleeptrace.motion.AutomaticPlacement
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.sleepDependencies
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class HistoricalReconcileAction { RECONCILE, COMPLETE_EMPTY_MIGRATION, DEFER_UNCONFIGURED_HISTORY }

/** Recording state is intentionally absent: it only controls future capture, not saved history. */
internal fun historicalReconcileAction(configured: Boolean, hasStoredSession: Boolean): HistoricalReconcileAction = when {
    configured -> HistoricalReconcileAction.RECONCILE
    !hasStoredSession -> HistoricalReconcileAction.COMPLETE_EMPTY_MIGRATION
    else -> HistoricalReconcileAction.DEFER_UNCONFIGURED_HISTORY
}

internal fun automaticSessionIdsEligibleForRuleRevocation(
    sessions: List<SleepSession>,
    completedWindows: List<SleepWindow>
): Set<String> = sessions.asSequence()
    .filter { session ->
        !session.manuallyEdited &&
            session.endMillis > session.startMillis &&
            session.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT) &&
            completedWindows.any { window ->
                session.startMillis >= window.startMillis && session.endMillis <= window.endMillis
            }
    }
    .map { it.id }
    .toSet()

class SleepReconciler(private val context: Context) {
    suspend fun reconcile() = mutex.withLock {
        val dependencies = context.sleepDependencies()
        val preferences = dependencies.preferences
        // Recording controls future sensor capture only. Historical staging and rule migration
        // continue from already saved Sleep API, UsageStats, motion and session data while paused.
        val store = dependencies.store
        val capturedGeneration = AutomaticWorkSignals.generation(context)
        val ruleMigrationPending = AutomaticWorkSignals.hasPendingRuleMigration(context)
        val configured = preferences.configured()
        val action = historicalReconcileAction(
            configured,
            hasStoredSession = configured || store.sessions(limit = 1, includeAwakeIntervals = false).isNotEmpty()
        )
        when (action) {
            HistoricalReconcileAction.RECONCILE -> Unit
            HistoricalReconcileAction.COMPLETE_EMPTY_MIGRATION -> {
                // With no configured sleep window and no stored session, there is no history to
                // stage. Complete the rule marker so an empty install does not remain perpetually dirty.
                store.markReconciled(capturedGeneration)
                return@withLock
            }
            HistoricalReconcileAction.DEFER_UNCONFIGURED_HISTORY -> return@withLock
        }
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
        val existingRecent = store.sessionsInRange(analysisStart, now)
        val staged = selectBestSessions(calculated, fallback).map { session ->
            val sessionUsage = stageUsageFor(session, usageResult.intervals)
            val estimate = SleepStageEstimator.analyze(
                session = session,
                motionMinutes = resolved,
                classifications = samples,
                usageIntervals = sessionUsage,
                sleepSegments = segments,
                schedule = schedule
            )
            val old = existingRecent.firstOrNull { it.id == session.id || (it.startMillis < session.endMillis && it.endMillis > session.startMillis) }
            session.copy(stageIntervals = preserveExistingStagesWithoutCurrentEvidence(estimate, old?.stageIntervals, session))
        }
        val invalidatedAutomaticSessionIds = if (ruleMigrationPending) {
            automaticSessionIdsEligibleForRuleRevocation(existingRecent, completedWindows)
        } else emptySet()
        store.mergeCalculated(
            staged, analysisStart, now,
            invalidatedAutomaticSessionIds = invalidatedAutomaticSessionIds,
            expectedGenerationForInvalidation = capturedGeneration
        )
        store.sessionsInRange(analysisStart, now)
            .filter { it.manuallyEdited && it.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT) }
            .forEach { session ->
                val sessionUsage = stageUsageFor(session, usageResult.intervals)
                val estimate = SleepStageEstimator.analyze(
                    session = session,
                    motionMinutes = resolved,
                    classifications = samples,
                    usageIntervals = sessionUsage,
                    sleepSegments = segments,
                    schedule = schedule
                )
                store.updateStageIntervals(session, preserveExistingStagesWithoutCurrentEvidence(estimate, session.stageIntervals, session))
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

internal fun preserveExistingStagesWithoutCurrentEvidence(
    result: SleepStageEstimator.StagingResult,
    existing: List<SleepStageInterval>?,
    session: SleepSession
): List<SleepStageInterval> {
    if (result.currentFeatureValidMinutes > 0 || existing.isNullOrEmpty() || session.endMillis <= session.startMillis) {
        return result.intervals
    }
    val clipped = existing.mapNotNull { old ->
        val start = maxOf(session.startMillis, old.startMillis)
        val end = minOf(session.endMillis, old.endMillis)
        if (end <= start) null else SleepStageInterval(start, end,
            if (old.stage == SleepStage.AWAKE) SleepStage.LIGHT else old.stage)
    }.sortedBy { it.startMillis }
    val boundaries = (listOf(session.startMillis, session.endMillis) +
        clipped.flatMap { listOf(it.startMillis, it.endMillis) } +
        result.intervals.filter { it.stage == SleepStage.AWAKE }.flatMap { listOf(it.startMillis, it.endMillis) })
        .distinct().sorted()
    val output = mutableListOf<SleepStageInterval>()
    boundaries.zipWithNext().forEach { (start, end) ->
        if (end <= start) return@forEach
        val awake = result.intervals.any { it.stage == SleepStage.AWAKE && it.startMillis < end && it.endMillis > start }
        val oldStage = clipped.firstOrNull { it.startMillis <= start && it.endMillis >= end }?.stage ?: SleepStage.LIGHT
        val stage = if (awake) SleepStage.AWAKE else oldStage
        val previous = output.lastOrNull()
        if (previous != null && previous.endMillis == start && previous.stage == stage)
            output[output.lastIndex] = previous.copy(endMillis = end)
        else output += SleepStageInterval(start, end, stage)
    }
    return output
}

internal const val PRE_SESSION_USAGE_LOOKBACK = 30 * com.rsps1008.sleeptrace.motion.MINUTE_MS

/** Guard evidence remains unclipped; SleepStageEstimator clips only the Awake overlay. */
internal fun stageUsageFor(session: SleepSession, intervals: List<UsageInterval>): List<UsageInterval> =
    intervals.filter {
        it.endMillis > session.startMillis - PRE_SESSION_USAGE_LOOKBACK && it.startMillis < session.endMillis
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

private val REMOTE_RECORD_MAY_EXIST_STATES = setOf(
    SyncState.SYNCED, SyncState.SYNCING, SyncState.FAILED_RETRYABLE, SyncState.FAILED_PERMANENT
)

/** Stable identity/version supports idempotent writes and later automatic corrections. */
fun mergeSleepSessions(
    existing: List<SleepSession>,
    calculated: List<SleepSession>,
    invalidateUnmatchedAutomaticIds: Set<String> = emptySet()
): List<SleepSession> {
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
            result += matches.filter { it != canonical && it.state in REMOTE_RECORD_MAY_EXIST_STATES }.map {
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

    existing.asSequence()
        .filter { old ->
            old.id in invalidateUnmatchedAutomaticIds && !old.manuallyEdited &&
                old.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT) &&
                calculated.none { candidate -> candidate.id == old.id || overlaps(old, candidate) }
        }
        .forEach { old ->
            val index = result.indexOfFirst { it.id == old.id }
            if (index < 0) return@forEach
            val current = result[index]
            if (current.manuallyEdited || current.state in setOf(
                    SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT
                )) return@forEach
            val remoteRecordMayExist = current.state in REMOTE_RECORD_MAY_EXIST_STATES
            result[index] = current.copy(
                state = if (remoteRecordMayExist) SyncState.RETIRED else SyncState.SKIPPED,
                syncError = null,
                reason = if (remoteRecordMayExist) {
                    "新版睡眠規則不再產生此自動紀錄，等待移除舊的 Health Connect 資料"
                } else {
                    "新版睡眠規則重新分析後不再符合自動睡眠候選，已略過"
                }
            )
        }
    return result.sortedByDescending { it.startMillis }
}
