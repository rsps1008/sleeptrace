package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.motion.MotionSleepEstimator
import com.rsps1008.sleeptrace.motion.AutomaticPlacement
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.motion.CouplingPolicy
import com.rsps1008.sleeptrace.motion.RecordingMode
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler
import com.rsps1008.sleeptrace.sleepDependencies
import java.time.ZoneId
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
        // continue from already saved Sleep API, motion and session data while paused.
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
        // Closing a saver observation after two waking classifications must immediately stop the
        // classify stream.  The next once-per-night start boundary re-enables it; segments remain
        // subscribed all day so delayed daily results can still repair a previous night.
        val saverMode = dependencies.motionSettings.recordingMode == RecordingMode.BATTERY_SAVER
        SleepTracker.syncSubscription(context, schedule, dependencies.motionSettings.enabled, System.currentTimeMillis(),
            saverWakeGrace = saverMode)
        SleepWindowScheduler.schedule(context,
            schedule.takeIf { dependencies.motionSettings.enabled && SleepTracker.hasActivityRecognition(context) },
            saverMode = saverMode)
        val now = System.currentTimeMillis()
        val retentionStart = now - RAW_EVENT_RETENTION_MILLIS
        val allRetainedWindows = schedule.windowsBetween(retentionStart, now)
        val stagesEnabled = dependencies.motionSettings.recordingMode == RecordingMode.STAGES
        val retainedCompleted = SleepUsageSnapshot.completedWindows(allRetainedWindows, now).filter { window ->
            stagesEnabled || schedule.isObservationClosed(window)
        }
        val completedStarts = retainedCompleted.mapTo(mutableSetOf()) { it.startMillis }
        val earliestUncompleted = allRetainedWindows.firstOrNull { it.startMillis !in completedStarts }?.startMillis ?: now
        val analysisStart = AutomaticWorkSignals.reconciliationStart(context, retentionStart, earliestUncompleted)
        // Keep unresolved old sessions eligible for matching without loading all historical
        // sessions or deserializing awakeIntervals for completed history.
        val unresolved = store.sessions(
            includeAwakeIntervals = false,
            states = RECONCILIATION_STATES
        )
        // A session which merely overlaps the 48-hour cutoff still needs its full
        // saved span plus the 60-minute sparse-coupling lookback. Otherwise the
        // arbitrary cutoff can erase the first movements and wash old stages to
        // fallback Light during a rule migration.
        val existingRecent = store.sessionsInRange(analysisStart, now)
        val segmentStart = minOf(
            analysisStart,
            unresolved.minOfOrNull { it.startMillis } ?: analysisStart,
            existingRecent.minOfOrNull { it.startMillis } ?: analysisStart
        )
        val evidenceStart = reconciliationEvidenceStart(
            analysisStart = segmentStart,
            sessionStarts = unresolved.map { it.startMillis } + existingRecent.map { it.startMillis },
            schedule = schedule,
            now = now
        )
        val allSegments = store.segments(evidenceStart, now)
        val samples = store.recentSamples(evidenceStart)
        val windows = schedule.windowsBetween(evidenceStart, now)
        val completedWindows = SleepUsageSnapshot.completedWindows(windows, now).filter { window ->
            stagesEnabled || schedule.isObservationClosed(window)
        }
        val segments = allSegments.filter { segment ->
            segment.endMillis >= analysisStart || unresolved.any { it.startMillis < segment.endMillis && it.endMillis > segment.startMillis }
        }
        val segmentBase = SleepAnalyzer.analyzeByWindow(
            segments, samples, emptyList(), schedule, completedWindows
        )
        val classificationBase = SleepAnalyzer.analyzeClassificationsByWindow(
            samples,
            completedWindows.filter { window ->
                segmentBase.none { it.startMillis < window.endMillis && it.endMillis > window.startMillis }
            }
        )
        val apiBase = segmentBase + classificationBase
        // The user's selected saver mode is a schedule-based, deliberately coarse fallback.
        // Stages mode still requires Sleep API evidence and must never manufacture a session.
        val scheduleBase = if (stagesEnabled) emptyList() else SleepAnalyzer.scheduledEstimateByWindow(
            saverFallbackWindows(completedWindows, schedule).filter { window ->
                apiBase.none { it.startMillis < window.endMillis && it.endMillis > window.startMillis }
            }, samples
        )
        val base = apiBase + scheduleBase
        val classifiedAwake = completedWindows.flatMap { window ->
            SleepApiTimeline.awakeIntervals(
                window.startMillis, window.endMillis, emptyList(), samples
            )
        }
        val apiAwake = normalizedAwake(
            evidenceStart, now, base.flatMap { it.awakeIntervals } + classifiedAwake
        )
        val motion = if (stagesEnabled) dependencies.motionStore.read(evidenceStart, now) else emptyList()
        val resolved = if (stagesEnabled) AutomaticPlacement.resolve(motion, apiAwake, schedule) else emptyList()
        fun inReconciliationScope(session: SleepSession): Boolean =
            session.endMillis > analysisStart || unresolved.any {
                it.startMillis < session.endMillis && it.endMillis > session.startMillis
            }
        val calculated = base.map { if (stagesEnabled) MotionSleepEstimator.annotate(it, resolved) else it }
            .filter(::inReconciliationScope)
        val fallback = if (stagesEnabled) MotionSleepEstimator.estimate(
            resolved, apiAwake, schedule, now
        ).mapNotNull { candidate ->
            confirmMotionCandidateOnset(candidate, segments, samples)?.let {
                extendConfirmedMotionCandidate(it, resolved, samples, apiAwake)
            }
        }
            .filter(::inReconciliationScope) else emptyList()
        val staged = selectBestSessions(calculated, fallback).map { session ->
            val sessionAwake = stageUsageFor(session, apiAwake + session.awakeIntervals)
            val old = existingRecent.firstOrNull {
                it.id == session.id || (it.startMillis < session.endMillis && it.endMillis > session.startMillis)
            }
            // A mode switch controls nights that have not been settled yet. Do not rewrite an
            // existing staged night as generic sleep, or fabricate stages for an API-only night.
            if (old != null && recordingModeChangedForExistingSession(old, stagesEnabled)) return@map old
            if (!stagesEnabled) return@map sleepApiOnlySession(session, sessionAwake)
            val estimate = SleepStageEstimator.analyze(
                session = session,
                motionMinutes = resolved,
                classifications = samples,
                usageIntervals = sessionAwake,
                sleepSegments = segments,
                schedule = schedule
            )
            session.copy(stageIntervals = preserveExistingStagesWithoutCurrentEvidence(estimate, old?.stageIntervals, session),
                stageAlgorithmVersion = SleepStageEstimator.ALGORITHM_VERSION,
                stageFeatureVersion = if (estimate.currentFeatureValidMinutes > 0) estimate.baselineFeatureVersion else old?.stageFeatureVersion)
        }
        val invalidatedAutomaticSessionIds = if (ruleMigrationPending) {
            automaticSessionIdsEligibleForRuleRevocation(existingRecent, completedWindows.filter { window ->
                segments.any { it.startMillis < window.end && it.endMillis > window.start } ||
                    samples.any { it.timeMillis >= window.start && it.timeMillis < window.end }
            })
        } else emptySet()
        store.mergeCalculated(
            staged, analysisStart, now,
            invalidatedAutomaticSessionIds = invalidatedAutomaticSessionIds,
            expectedGenerationForInvalidation = capturedGeneration
        )
        store.sessionsInRange(analysisStart, now)
            .filter { it.manuallyEdited && it.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT) }
            .forEach { session ->
                val sessionAwake = stageUsageFor(session, apiAwake + session.awakeIntervals)
                val sessionWasApiOnly = session.stageAlgorithmVersion == SLEEP_API_ONLY_ALGORITHM_VERSION
                if (sessionWasApiOnly != !stagesEnabled) return@forEach
                if (!stagesEnabled) {
                    val apiOnly = sleepApiOnlySession(session, sessionAwake)
                    store.updateStageIntervals(session, apiOnly.stageIntervals,
                        SLEEP_API_ONLY_ALGORITHM_VERSION, null)
                    return@forEach
                }
                val estimate = SleepStageEstimator.analyze(
                    session = session,
                    motionMinutes = resolved,
                    classifications = samples,
                    usageIntervals = sessionAwake,
                    sleepSegments = segments,
                    schedule = schedule
                )
                store.updateStageIntervals(session, preserveExistingStagesWithoutCurrentEvidence(estimate, session.stageIntervals, session),
                    SleepStageEstimator.ALGORITHM_VERSION,
                    if (estimate.currentFeatureValidMinutes > 0) estimate.baselineFeatureVersion else session.stageFeatureVersion)
            }
        // Advance only after all local calculation and database writes above completed.  A raw
        // callback arriving during this run increments generation and deliberately leaves its
        // old timestamp pending for the next worker run.
        // Never jump over a saver night that is still waiting for wake evidence. The persisted
        // cursor advances only to a continuous prefix of retained nightly windows.
        val contiguousCompletedEnd = contiguousCompletedWindowEnd(allRetainedWindows, completedStarts)
        store.markReconciled(capturedGeneration, contiguousCompletedEnd)
    }
    companion object {
        private val mutex = Mutex()
        private const val RAW_EVENT_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
        private val RECONCILIATION_STATES = setOf(
            SyncState.PENDING, SyncState.SYNCING, SyncState.FAILED_RETRYABLE
        )
    }
}

internal fun contiguousCompletedWindowEnd(windows: List<SleepWindow>, completedStarts: Set<Long>): Long? = windows.asSequence()
    .takeWhile { it.startMillis in completedStarts }
    .lastOrNull()?.endMillis

/** A settled no-evidence night advances progress, but must never manufacture a saver session. */
internal fun saverFallbackWindows(windows: List<SleepWindow>, schedule: SleepSchedule): List<SleepWindow> =
    windows.filterNot(schedule::isObservationDataInsufficient)

internal fun recordingModeChangedForExistingSession(
    session: SleepSession,
    stagesEnabled: Boolean
): Boolean = (session.stageAlgorithmVersion == SLEEP_API_ONLY_ALGORITHM_VERSION) != !stagesEnabled

internal fun sleepApiOnlySession(session: SleepSession, awake: List<UsageInterval>): SleepSession = session.copy(
    awakeIntervals = normalizedAwake(session.startMillis, session.endMillis, awake),
    awakeMillis = normalizedAwake(session.startMillis, session.endMillis, awake)
        .sumOf { it.endMillis - it.startMillis },
    stageIntervals = listOf(SleepStageInterval(session.startMillis, session.endMillis, SleepStage.SLEEPING)),
    stageAlgorithmVersion = SLEEP_API_ONLY_ALGORITHM_VERSION,
    stageFeatureVersion = null
)

internal fun reconciliationEvidenceStart(
    analysisStart: Long,
    sessionStarts: List<Long>,
    schedule: SleepSchedule,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val scopeStart = minOf(analysisStart, sessionStarts.minOrNull() ?: analysisStart)
    val containingWindowStart = schedule.windowsBetween(scopeStart, now, zone)
        .minOfOrNull { it.startMillis } ?: scopeStart
    return minOf(scopeStart, containingWindowStart) - CouplingPolicy.SPARSE_EVIDENCE_WINDOW_MILLIS
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
            old.stage)
    }.sortedBy { it.startMillis }
    val boundaries = (listOf(session.startMillis, session.endMillis) +
        clipped.flatMap { listOf(it.startMillis, it.endMillis) } +
        result.intervals.filter { it.stage == SleepStage.AWAKE }.flatMap { listOf(it.startMillis, it.endMillis) })
        .distinct().sorted()
    val output = mutableListOf<SleepStageInterval>()
    boundaries.zipWithNext().forEach { (start, end) ->
        if (end <= start) return@forEach
        val awake = result.intervals.any { it.stage == SleepStage.AWAKE && it.startMillis < end && it.endMillis > start }
        val oldStage = clipped.firstOrNull { it.startMillis <= start && it.endMillis >= end }?.stage
            ?.takeUnless { it == SleepStage.SLEEPING } ?: SleepStage.LIGHT
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
    val latestRelevantClassification = classifications.asSequence()
        .filter {
            it.timeMillis >= candidate.startMillis - MOTION_ONSET_CLASSIFICATION_LOOKBACK &&
                it.timeMillis < candidate.endMillis
        }
        .maxByOrNull { it.timeMillis }
    val classificationOnsets = listOfNotNull(latestRelevantClassification).asSequence()
        .filter { it.confidence >= 80 }
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

/**
 * Classification may arrive before coupling has enough motion history to resolve BED.
 * Keep it bounded to the existing two-hour capture fallback horizon, and only trust the
 * latest report so a newer awake/unknown classification cancels older sleep evidence.
 */
internal const val MOTION_ONSET_CLASSIFICATION_LOOKBACK = 2 * 60 * com.rsps1008.sleeptrace.motion.MINUTE_MS

/**
 * Once BED coupling and Google sleep evidence have independently accepted a candidate,
 * preserve contiguous compatible quiet minutes while coupling is temporarily UNKNOWN.
 * This does not let stillness establish sleep: without the accepted BED candidate this
 * function is never called. Three consecutive dense-active minutes close the session at
 * the first such minute; sparse turning does not.
 */
internal fun extendConfirmedMotionCandidate(
    candidate: SleepSession,
    minutes: List<com.rsps1008.sleeptrace.motion.MotionMinute>,
    classifications: List<ClassificationSample>,
    usage: List<UsageInterval>
): SleepSession {
    val evidence = classifications.asSequence()
        .filter {
            it.confidence >= 80 &&
                it.timeMillis >= candidate.startMillis - MOTION_ONSET_CLASSIFICATION_LOOKBACK &&
                it.timeMillis <= candidate.startMillis
        }
        .maxByOrNull { it.timeMillis }
    val ordered = minutes.sortedBy { it.startMillis }
    val recordingId = ordered.firstOrNull { it.startMillis in candidate.startMillis until candidate.endMillis }?.recordingId
    fun phoneUsed(start: Long) = usage.any { it.startMillis < start + com.rsps1008.sleeptrace.motion.MINUTE_MS && it.endMillis > start }
    fun compatible(row: com.rsps1008.sleeptrace.motion.MotionMinute) =
        row.recordingId == recordingId && row.supportsCurrentStaging && !phoneUsed(row.startMillis)

    var start = candidate.startMillis
    if (evidence != null) {
        val prefix = ordered.filter { it.startMillis >= evidence.timeMillis - evidence.timeMillis % com.rsps1008.sleeptrace.motion.MINUTE_MS && it.startMillis < candidate.startMillis }
        val contiguous = prefix.isNotEmpty() && prefix.zipWithNext().all { (a, b) ->
            b.startMillis == a.startMillis + com.rsps1008.sleeptrace.motion.MINUTE_MS
        }
        val quiet = prefix.drop(1).all { compatible(it) && it.level == com.rsps1008.sleeptrace.motion.MotionLevel.QUIET }
        if (contiguous && quiet) start = evidence.timeMillis
    }

    var end = candidate.endMillis
    var denseActiveStart: Long? = null
    var denseActiveCount = 0
    val tail = ordered.filter { it.startMillis >= candidate.endMillis }
    var expected = candidate.endMillis
    for (row in tail) {
        if (row.startMillis != expected || !compatible(row)) break
        val denseActive = row.level == com.rsps1008.sleeptrace.motion.MotionLevel.ACTIVE && row.activeMillis >= DENSE_WAKE_ACTIVE_MILLIS
        if (denseActive) {
            if (denseActiveStart == null) denseActiveStart = row.startMillis
            denseActiveCount++
            if (denseActiveCount >= DENSE_WAKE_MINUTES) {
                end = denseActiveStart!!
                break
            }
        } else {
            denseActiveStart = null
            denseActiveCount = 0
            end = row.startMillis + com.rsps1008.sleeptrace.motion.MINUTE_MS
        }
        expected = row.startMillis + com.rsps1008.sleeptrace.motion.MINUTE_MS
    }
    return candidate.copy(startMillis = start, endMillis = end, id = "${candidate.id}-$start-$end",
        reason = candidate.reason + "；Google 入睡證據與連續動作摘要補足耦合建立前後區間")
}

private const val DENSE_WAKE_MINUTES = 3
private const val DENSE_WAKE_ACTIVE_MILLIS = 30_000L

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
            old.stageIntervals == candidate.stageIntervals) {
            result[result.indexOf(old)] = old.copy(stageAlgorithmVersion = candidate.stageAlgorithmVersion,
                stageFeatureVersion = candidate.stageFeatureVersion)
            return@forEach
        }
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
