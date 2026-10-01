package com.rsps1008.sleeptrace.sleep

import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionLevel
import com.rsps1008.sleeptrace.motion.MotionAccumulator
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.MotionFeaturePolicy
import com.rsps1008.sleeptrace.motion.Placement
import com.rsps1008.sleeptrace.motion.CouplingState

/** Offline engineering estimates inside an accepted session; never a clinical sleep stage. */
object SleepStageEstimator {
    // This version also gates automatic reconciliation-rule migrations.
    const val ALGORITHM_VERSION = 11
    const val SLEEP_ONSET_GUARD_MILLIS = 15 * MINUTE_MS
    const val MINIMUM_BASELINE_MINUTES = 10
    const val DEEP_WINDOW_MINUTES = 15
    const val MIN_SESSION_AGE_FOR_DEEP = 20 * MINUTE_MS
    private const val DEEP_BACKFILL_MINUTES = 7
    private const val MIN_DEEP_RUN_MILLIS = 2 * MINUTE_MS
    /** New 10 Hz evidence must form a durable bout; this filter never pads a short run. */
    private const val TEN_HZ_MIN_DEEP_RUN_MILLIS = 10 * MINUTE_MS
    private const val MINUTE_COVERAGE_MILLIS = 45_000L
    private const val MINIMUM_SLEEP_API_CONFIDENCE = 80
    // Uncalibrated engineering limits, not physiological Deep probabilities.
    private const val MIN_SIGNAL_RANGE = 0.0005
    private const val MIN_RELATIVE_SIGNAL_RANGE = 0.25
    private const val ENTER_MAX_EVENTS = 6
    private const val EXIT_DENSE_EVENTS = 8
    private const val MAX_CONTINUOUS_ACTIVE_MILLIS = 12_000L
    /** A recorded v5/v6 short gap can bridge entry context, never be staged itself. */
    private const val MINOR_GAP_MAX_MILLIS = 2_000L
    private const val TEN_HZ_MAX_SINGLE_GAP_MILLIS = 500L
    private const val TEN_HZ_MAX_MISSING_MILLIS_PER_MINUTE = 1_000L
    private const val MAX_MINOR_GAP_MINUTES_IN_WINDOW = 1
    private const val MAX_MINOR_GAP_TOTAL_MILLIS = 2_000L

    enum class Reason {
        NO_SLEEP_EVIDENCE, PHONE_IN_USE, ONSET_GUARD, MISSING_MOTION, INSUFFICIENT_COVERAGE,
        COUPLING_INSUFFICIENT, COUPLING_EXPIRED, BASELINE_INSUFFICIENT, LOW_SIGNAL_DIFFERENTIATION,
        WINDOW_TOO_SHORT, ACTIVITY_TOO_HIGH, ENTER_DEEP, MAINTAIN_DEEP, EXIT_SUSTAINED_ACTIVITY,
        EXIT_ACTIVE_3_IN_5, EXIT_DENSE_EVENTS, EXIT_SUSTAINED_ROLLING_MOTION,
        EXIT_COUPLING_LOST, SAFETY_CAP, LEGACY_FEATURE_LIMITATION, WINDOW_CONTAINS_GAP,
        RECORDING_BOUNDARY, COUPLING_INVALIDATED, LEGACY_PLACEMENT_UNKNOWN, PLACEMENT_NOT_BED, ALLOWED_MINOR_GAP,
        RECENT_WINDOW_INCOMPLETE, MINOR_GAP_BUDGET_EXCEEDED, RELATIVE_QUIET_ENTRY,
        RELATIVE_QUIET_CONFIRMED,
        SHORT_DEEP_RUN
    }
    enum class Action { ENTER, MAINTAIN, EXIT, NONE }
    /** Independent provenance for a stage changed after its formal minute decision. */
    enum class PostProcessReason { SUSTAINED_ACTIVITY_REWRITE, SHORT_DEEP_RUN_FILTER }
    data class Decision(
        val applicable: Boolean,
        val allowed: Boolean,
        val reasons: List<Reason> = emptyList()
    )

    private data class EntryEvaluation(
        val decision: Decision,
        val windowReasons: List<Reason>,
        val blockingIndices: Set<Int>,
        val nonBlockingReasons: List<Reason>,
        val transitionReason: String?,
        val relativeQuietSupportIndices: Set<Int>
    )

    private data class FormalDecision(
        val priorState: Boolean,
        val currentEligibility: Boolean,
        val currentEligibilityReasons: List<Reason>,
        val baselineReasons: List<Reason>,
        val entryDecision: Decision,
        val windowBlockingReasons: List<Reason>,
        val windowBlockingIndices: Set<Int>,
        val nonBlockingReasons: List<Reason>,
        val maintenanceDecision: Decision,
        val action: Action,
        val transitionReason: String?,
        val relativeQuietSupportIndices: Set<Int>,
        val highMotionWindowsBefore: Int,
        val highMotionWindowsCandidate: Int,
        val highMotionWindowsAfter: Int
    )
    data class NightlyBaseline(
        val p25: Double, val p35: Double, val p50: Double,
        val p65: Double, val p70: Double, val p75: Double,
        val bedMinutes: Int, val narrowDistribution: Boolean,
        val featureVersion: Int = MotionAccumulator.CURRENT_FEATURE_VERSION,
        val sampleCount: Int = bedMinutes,
        val reason: String? = null
    ) {
        /** Null means this feature definition has no legacy relative-quiet route. */
        val relativeQuietThreshold: Double? get() = p70.takeUnless {
            featureVersion == MotionAccumulator.CURRENT_FEATURE_VERSION
        }
        val relativeQuietMethod: String? get() = relativeQuietThreshold?.let {
            "NIGHTLY_P70_PROVISIONAL_CONFIRMED"
        }
    }
    data class RollingFeatures(
        val validMinutes: Int, val quietMinutes: Int, val activeMinutes: Int,
        val medianRms: Double?, val p75Rms: Double?,
        val bedMinutes: Int, val unknownMinutes: Int
    )
    data class MinuteDiagnostic(
        val startMillis: Long, val placement: Placement?, val level: MotionLevel?,
        val rollingMedianRms: Double?, val stage: SleepStage, val event: String?,
        val stagingMotionUsable: Boolean, val stagingMotionExclusionReason: String?,
        val stagingMotionRole: String = "excluded",
        val endMillis: Long = startMillis + MINUTE_MS,
        val reasons: List<Reason> = emptyList(), val canStage: Boolean = false,
        val canEnterDeep: Boolean = false, val canMaintainDeep: Boolean = false,
        val phoneUseMillis: Long = 0, val inOnsetGuard: Boolean = false, val hasSleepEvidence: Boolean = false,
        val motion: MotionMinute? = null,
        val stageAlgorithmVersion: Int = ALGORITHM_VERSION,
        val couplingState: CouplingState? = motion?.coupling?.state,
        val couplingAgeMillis: Long? = motion?.coupling?.ageMillis,
        val couplingInvalidation: String? = motion?.coupling?.reason,
        val currentEligibilityReasons: List<Reason> = reasons,
        val windowBlockingReasons: List<Reason> = emptyList(),
        val windowBlockingIntervals: List<LongRange> = emptyList(),
        /** Informational evidence which did not block the formal decision. */
        val nonBlockingReasons: List<Reason> = emptyList(),
        val baselineReasons: List<Reason> = emptyList(),
        val transitionReason: String? = event,
        /** The state before later backfill and safety-cap post-processing. */
        val priorState: Boolean = false,
        val currentEligibility: Boolean = canStage,
        val entryDecision: Decision = Decision(!priorState, canEnterDeep),
        val maintenanceDecision: Decision = Decision(priorState, canMaintainDeep),
        val action: Action = Action.NONE,
        val formalStage: SleepStage = stage,
        val wasBackfilled: Boolean = false,
        val safetyCapAdjusted: Boolean = false,
        val retroactivelyAdjusted: Boolean = false,
        val retroactiveAdjustmentReason: PostProcessReason? = null,
        val retroactiveAdjustmentSourceMillis: Long? = null,
        val finalStage: SleepStage = stage,
        val highMotionWindowsBefore: Int = 0,
        val highMotionWindowsCandidate: Int = 0,
        val highMotionWindowsAfter: Int = 0
    ) {
        /** Final Light produced because full Deep evidence was unavailable or later withdrawn. */
        val isFallbackLight: Boolean get() = finalStage == SleepStage.LIGHT &&
            (!canStage || safetyCapAdjusted || retroactiveAdjustmentReason == PostProcessReason.SHORT_DEEP_RUN_FILTER)

        val primaryReason: Reason? get() = when {
            Reason.SAFETY_CAP in reasons -> Reason.SAFETY_CAP
            Reason.SHORT_DEEP_RUN in reasons -> Reason.SHORT_DEEP_RUN
            action == Action.ENTER -> Reason.ENTER_DEEP
            action == Action.MAINTAIN -> Reason.MAINTAIN_DEEP
            canStage -> reasons.firstOrNull()
            else -> reasons.firstOrNull { it !in setOf(Reason.ONSET_GUARD, Reason.WINDOW_TOO_SHORT) }
                ?: reasons.firstOrNull()
        }

    }
    data class StagingResult(
        val intervals: List<SleepStageInterval>, val baseline: NightlyBaseline?,
        val motionCoverageRatio: Double, val firstMotionDelayMillis: Long?,
        val minutes: List<MinuteDiagnostic>,
        val baselineFeatureVersion: Int? = baseline?.featureVersion,
        val baselineSampleCount: Int = baseline?.sampleCount ?: 0,
        val baselineReason: String? = if (baseline == null) "BASELINE_INSUFFICIENT" else if (baseline.narrowDistribution) "LOW_SIGNAL_DIFFERENTIATION" else null,
        val sensorCoverageRatio: Double = motionCoverageRatio,
        val currentFeatureValidMinutes: Int = 0,
        val baselineEligibleMinutes: Int = 0,
        val firstValidMotionDelayMillis: Long? = null,
        val stageableCoverageRatio: Double = 0.0,
        val longestGapMillis: Long = 0,
        val durations: StageDurations = StageDurations(0, 0, 0, 0),
        /** Retained for append-only CSV compatibility; rule 9 never emits SLEEPING. */
        val undeterminedReasonsMillis: Map<Reason, Long> = emptyMap(),
        /** Reasons for accepted sleep minutes shown as fallback Light without full stage evidence. */
        val fallbackLightReasonsMillis: Map<Reason, Long> = emptyMap()
    )
    private data class MinuteSignal(
        val start: Long, val end: Long, val motion: MotionMinute?, val coverageMotion: MotionMinute?,
        val inPhoneUse: Boolean, val inOnsetGuard: Boolean, val inSchedule: Boolean,
        val beforeEvidence: Boolean, val recordingBoundary: Boolean = false,
        val recordingIdBoundary: Boolean = false, val featureBoundary: Boolean = false
    ) {
        private val isFullBucket: Boolean get() = end - start == MINUTE_MS && start % MINUTE_MS == 0L
        /** Null means this row cannot safely use the full-minute gap exception. */
        val fullBucketMissingMillis: Long? get() = motion?.let { candidate ->
            if (!isFullBucket || candidate.coveredMillis !in 0L..MINUTE_MS ||
                (candidate.coveredMillis == MINUTE_MS && (candidate.longestGapMillis ?: 0L) > 0L)) null
            else MINUTE_MS - candidate.coveredMillis
        }
        val consistentCoverage: Boolean get() = motion?.let { candidate ->
            candidate.coveredMillis in 0L..MINUTE_MS &&
                !(candidate.coveredMillis == MINUTE_MS && (candidate.longestGapMillis ?: 0L) > 0L)
        } == true
        val validMotion: Boolean get() = motion?.let {
            consistentCoverage && it.coveredMillis >= MINUTE_COVERAGE_MILLIS && it.level != MotionLevel.UNKNOWN && it.rms.isFinite()
        } == true
        val currentFeature: Boolean get() = motion?.supportsCurrentStaging == true
        val couplingSupported: Boolean get() = motion?.let { it.coupling?.state?.let { state -> state != CouplingState.INSUFFICIENT }
            ?: (it.placement == Placement.BED) } == true
        val hasRequiredFeatures: Boolean get() = motion?.let { it.featureVersion == MotionAccumulator.CADENCE_ANCHOR_FEATURE_VERSION ||
            (it.maxDelta != null && it.movementEvents != null && it.longestActiveMillis != null && it.quietTailMillis != null && it.longestGapMillis != null) } == true
        val observedQuality: Boolean get() {
            if (!validMotion || !currentFeature || !hasRequiredFeatures || recordingBoundary) return false
            val gap = motion?.longestGapMillis ?: 0L
            if (motion?.featureVersion != MotionAccumulator.CURRENT_FEATURE_VERSION) return gap == 0L
            // At 10 Hz, one missing 100 ms slot must not discard an otherwise
            // observed minute. Keep both per-gap and cumulative loss bounded.
            val missing = fullBucketMissingMillis ?: return gap == 0L
            return gap <= TEN_HZ_MAX_SINGLE_GAP_MILLIS &&
                missing <= TEN_HZ_MAX_MISSING_MILLIS_PER_MINUTE
        }
        val minorObservedGap: Boolean get() = motion?.let { candidate ->
            validMotion && currentFeature && hasRequiredFeatures && !recordingBoundary &&
                // V7's bounded sub-second loss already passes observedQuality.
                // Do not charge it again against the legacy one-gap-in-15 rule.
                // V7 losses above its own quality budget remain hard breaks.
                candidate.featureVersion in setOf(
                    MotionAccumulator.ONE_HZ_FEATURE_VERSION,
                    MotionAccumulator.STRICT_CADENCE_FEATURE_VERSION
                ) &&
                (candidate.longestGapMillis ?: 0) in 1..MINOR_GAP_MAX_MILLIS &&
                fullBucketMissingMillis != null
        } == true
        // Coupling says the signal is still plausibly connected to the bed, while
        // placement records the resolver's minute-level conclusion.  Require both:
        // malformed/legacy UNKNOWN+HELD rows must not maintain Deep indefinitely.
        val stageEligible: Boolean get() = !inPhoneUse && inSchedule && !beforeEvidence &&
            observedQuality && couplingSupported && motion?.placement == Placement.BED
        val hardBreak: Boolean get() = inPhoneUse || inOnsetGuard || !inSchedule ||
            beforeEvidence || !observedQuality || !couplingSupported
        val usable: Boolean get() = !hardBreak
        val quiet: Boolean get() = usable && motion?.level == MotionLevel.QUIET
        val bed: Boolean get() = usable && motion?.placement == Placement.BED
    }

    fun estimate(
        session: SleepSession, motionMinutes: List<MotionMinute>,
        classifications: List<ClassificationSample>, usageIntervals: List<UsageInterval>,
        sleepSegments: List<SleepSegment>, schedule: SleepSchedule
    ): List<SleepStageInterval> = analyze(
        session, motionMinutes, classifications, usageIntervals, sleepSegments, schedule
    ).intervals

    /** Diagnostics are derived in memory and never persisted as nightly thresholds. */
    fun analyze(
        session: SleepSession, motionMinutes: List<MotionMinute>,
        classifications: List<ClassificationSample>, usageIntervals: List<UsageInterval>,
        sleepSegments: List<SleepSegment>, schedule: SleepSchedule
    ): StagingResult {
        if (session.endMillis <= session.startMillis) return StagingResult(emptyList(), null, 0.0, null, emptyList())
        val meaningfulUse = (session.awakeIntervals + usageIntervals).filter { it.endMillis > it.startMillis }
        // Pre-session evidence participates in the guard, but only the clipped union is Awake.
        val phoneUse = normalizedAwake(session.startMillis, session.endMillis, meaningfulUse)
        val evidenceStart = (sleepSegments.asSequence()
            .filter { it.startMillis < session.endMillis && it.endMillis > session.startMillis }
            .map { maxOf(session.startMillis, it.startMillis) } + classifications.asSequence()
            .filter { it.confidence >= MINIMUM_SLEEP_API_CONFIDENCE && it.timeMillis >= session.startMillis && it.timeMillis < session.endMillis }
            .map { it.timeMillis }).minOrNull()
        val motionByMinute = motionMinutes.groupBy { it.startMillis }.mapValues { (_, rows) ->
            rows.sortedByDescending { MotionFeaturePolicy.storagePriority(it.featureVersion) }.first()
        }
        val timeline = buildList {
            var cursor = session.startMillis
            while (cursor < session.endMillis) {
                // Align subsequent boundaries to stored minute buckets, including partial endpoints.
                val motionStart = Math.floorDiv(cursor, MINUTE_MS) * MINUTE_MS
                val end = minOf(session.endMillis, motionStart + MINUTE_MS)
                val lastUse = lastMeaningfulPhoneUseBefore(cursor, meaningfulUse)
                val fullBucketInSession = motionStart >= session.startMillis && motionStart + MINUTE_MS <= session.endMillis
                val bucketMotion = motionByMinute[motionStart]
                val previousMotion = motionByMinute[motionStart - MINUTE_MS]
                val recordingIdBoundary = bucketMotion != null && previousMotion != null && previousMotion.recordingId != bucketMotion.recordingId
                val featureBoundary = bucketMotion != null && previousMotion != null && previousMotion.featureVersion != bucketMotion.featureVersion
                add(MinuteSignal(cursor, end, bucketMotion.takeIf { fullBucketInSession }, bucketMotion,
                    phoneUse.any { it.startMillis < end && it.endMillis > cursor },
                    lastUse != null && cursor - lastUse < SLEEP_ONSET_GUARD_MILLIS,
                    schedule.windowAt(cursor) != null,
                    evidenceStart == null || cursor < evidenceStart,
                    recordingIdBoundary || featureBoundary, recordingIdBoundary, featureBoundary))
                cursor = end
            }
        }
        val baseline = nightlyBaseline(timeline)
        val rolling = timeline.indices.map { rollingFeatures(timeline, maxOf(0, it - DEEP_WINDOW_MINUTES + 1)..it) }
        fun canStage(minute: MinuteSignal): Boolean = minute.stageEligible && baseline != null &&
            !baseline.narrowDistribution && minute.motion?.featureVersion == baseline.featureVersion
        val stages = MutableList(timeline.size) { SleepStage.LIGHT }
        val formalStages = MutableList(timeline.size) { SleepStage.LIGHT }
        val wasBackfilled = BooleanArray(timeline.size)
        val safetyCapAdjusted = BooleanArray(timeline.size)
        val retroactivelyAdjusted = BooleanArray(timeline.size)
        val retroactiveAdjustmentReason = arrayOfNulls<PostProcessReason>(timeline.size)
        val retroactiveAdjustmentSourceMillis = arrayOfNulls<Long>(timeline.size)
        val events = arrayOfNulls<String>(timeline.size)
        val formalDecisions = arrayOfNulls<FormalDecision>(timeline.size)
        var deep = false
        var highMotionWindows = 0
        var lastSustainedMotionExitIndex = -1
        timeline.indices.forEach { index ->
            val minute = timeline[index]
            val decision = evaluateFormalDecision(
                index, timeline, rolling, baseline, evidenceStart, deep,
                highMotionWindows, lastSustainedMotionExitIndex, ::canStage
            )
            formalDecisions[index] = decision
            highMotionWindows = decision.highMotionWindowsAfter
            if (decision.action == Action.EXIT &&
                Reason.EXIT_SUSTAINED_ACTIVITY in decision.maintenanceDecision.reasons) {
                lastSustainedMotionExitIndex = index
            }
            val formalStage = if (canStage(minute) && decision.action in setOf(Action.ENTER, Action.MAINTAIN)) {
                SleepStage.DEEP
            } else if (canStage(minute)) {
                SleepStage.LIGHT
            } else SleepStage.LIGHT
            // Set the current minute before post-processing so a rewrite range
            // can include its confirmation minute without making that minute
            // look retroactively changed.
            formalStages[index] = formalStage
            stages[index] = formalStage
            when (decision.action) {
                Action.EXIT -> {
                    // Sustained activity cannot remain a Deep bridge; isolated turns are retained.
                    if (decision.maintenanceDecision.reasons.contains(Reason.EXIT_SUSTAINED_ACTIVITY) &&
                        rollingFeatures(timeline, maxOf(0, index - 4)..index).activeMinutes >= 3) {
                        val recent = maxOf(0, index - 4)..index
                        val firstActive = recent.first { timeline[it].motion?.level == MotionLevel.ACTIVE }
                        for (i in firstActive..index) {
                            val rewritten = SleepStage.LIGHT
                            if (stages[i] != rewritten) {
                                stages[i] = rewritten
                                retroactivelyAdjusted[i] = true
                                retroactiveAdjustmentReason[i] = PostProcessReason.SUSTAINED_ACTIVITY_REWRITE
                                retroactiveAdjustmentSourceMillis[i] = minute.start
                            }
                        }
                    }
                    events[index] = decision.transitionReason
                    deep = false
                }
                Action.ENTER -> {
                    deep = true
                    events[index] = decision.transitionReason
                    // Relative entry is confirmed by at least two independent
                    // eligible low-RMS observations. Backfill only the observed
                    // support points themselves: a higher-RMS quiet minute between
                    // them was not positive Deep evidence and must remain Light.
                    decision.relativeQuietSupportIndices.forEach { supportIndex ->
                        val support = timeline[supportIndex]
                        if (canStage(support) && support.bed && support.quiet &&
                            stages[supportIndex] != SleepStage.DEEP) {
                            stages[supportIndex] = SleepStage.DEEP
                            wasBackfilled[supportIndex] = true
                        }
                    }
                    // The legacy seven-minute reconstruction belongs only to a
                    // fully satisfied strict window. Relative entry backfills the
                    // explicitly corroborating observations above and nothing else.
                    if (decision.transitionReason == "enter_stable_window" &&
                        baseline?.featureVersion != MotionAccumulator.CURRENT_FEATURE_VERSION) {
                        var backfillMovementEvents = 0
                        val lowerBound = maxOf(0, index - DEEP_BACKFILL_MINUTES, lastSustainedMotionExitIndex + 1)
                        for (i in index - 1 downTo lowerBound) {
                            val previous = timeline[i]
                            backfillMovementEvents += previous.motion?.movementEvents ?: 0
                            if (!canStage(previous) || !previous.quiet || previous.motion!!.rms > baseline!!.p70 ||
                                backfillMovementEvents >= EXIT_DENSE_EVENTS || previous.inOnsetGuard ||
                                previous.start < maxOf(session.startMillis, evidenceStart ?: session.startMillis) + MIN_SESSION_AGE_FOR_DEEP) break
                            stages[i] = SleepStage.DEEP
                            wasBackfilled[i] = true
                        }
                    }
                }
                Action.MAINTAIN -> deep = true
                Action.NONE -> if (!decision.priorState) deep = false
            }
        }
        applySafetyBound(timeline, rolling, stages, events, safetyCapAdjusted, baseline,
            session.endMillis - session.startMillis - phoneUse.sumOf { it.endMillis - it.startMillis })
        filterShortDeepRuns(
            timeline, stages, events, retroactivelyAdjusted,
            retroactiveAdjustmentReason, retroactiveAdjustmentSourceMillis,
            wasBackfilled, formalDecisions
        )
        val merged = timeline.indices.map {
            SleepStageInterval(timeline[it].start, timeline[it].end, stages[it])
        }.mergeAdjacentStages()
        fun awakeOverlap(start: Long, end: Long) = phoneUse.sumOf {
            (minOf(end, it.endMillis) - maxOf(start, it.startMillis)).coerceAtLeast(0L)
        }
        val sleepMillis = session.endMillis - session.startMillis - phoneUse.sumOf { it.endMillis - it.startMillis }
        val validMinuteSleepMillis = timeline.filter {
            it.coverageMotion?.let { motion -> motion.supportsCurrentStaging &&
                motion.coveredMillis >= MINUTE_COVERAGE_MILLIS && motion.level != MotionLevel.UNKNOWN && motion.rms.isFinite()
            } == true
        }.sumOf { (it.end - it.start - awakeOverlap(it.start, it.end)).coerceAtLeast(0L) }
        val coverage = if (sleepMillis == 0L) 0.0 else validMinuteSleepMillis.toDouble() / sleepMillis
        val span = session.endMillis - session.startMillis
        val spanCovered = timeline.sumOf { minute ->
            // Never coerce corrupt stored coverage into a plausible result.
            minute.coverageMotion?.coveredMillis?.takeIf { it in 0L..MINUTE_MS }?.toDouble()
                ?.times(minute.end - minute.start)?.div(MINUTE_MS) ?: 0.0
        }
        val sensorCoverage = spanCovered / span
        val normalized = sleepParts(session.copy(stageIntervals = merged, awakeIntervals = phoneUse))
        val finalIntervals = normalized.map { SleepStageInterval(it.start, it.end, it.stage) }
        val durations = stageDurations(session.copy(stageIntervals = finalIntervals, awakeIntervals = phoneUse))
        fun reasons(i: Int): List<Reason> {
            val decision = requireNotNull(formalDecisions[i])
            return buildList {
                addAll(decision.currentEligibilityReasons)
                addAll(decision.baselineReasons)
                // Window blockers and informational minor-gap evidence belong
                // to the entry evaluation.  Keep them in their dedicated
                // diagnostic columns, but expose them as formal reasons only
                // when entry is the state machine's applicable branch.
                if (decision.entryDecision.applicable && !decision.entryDecision.allowed) {
                    addAll(decision.windowBlockingReasons)
                }
                if (decision.entryDecision.applicable) {
                    addAll(decision.nonBlockingReasons)
                }
                if (decision.maintenanceDecision.applicable && !decision.maintenanceDecision.allowed) {
                    addAll(decision.maintenanceDecision.reasons)
                }
                when (decision.action) {
                    Action.ENTER -> add(Reason.ENTER_DEEP)
                    Action.MAINTAIN -> add(Reason.MAINTAIN_DEEP)
                    else -> Unit
                }
                if (safetyCapAdjusted[i]) add(Reason.SAFETY_CAP)
                if (retroactiveAdjustmentReason[i] == PostProcessReason.SHORT_DEEP_RUN_FILTER) {
                    add(Reason.SHORT_DEEP_RUN)
                }
            }.distinct()
        }
        val diagnostic = timeline.indices.map { i ->
            val m = timeline[i]
            val decision = requireNotNull(formalDecisions[i])
            val finalStage = if (awakeOverlap(m.start, m.end) == m.end - m.start) SleepStage.AWAKE else stages[i]
            val formalStage = if (awakeOverlap(m.start, m.end) == m.end - m.start) SleepStage.AWAKE else formalStages[i]
            val diagnosticReasons = reasons(i)
            MinuteDiagnostic(
                startMillis = m.start,
                placement = (m.motion ?: m.coverageMotion)?.placement,
                level = (m.motion ?: m.coverageMotion)?.level,
                rollingMedianRms = rolling[i].medianRms,
                stage = finalStage,
                event = events[i],
                stagingMotionUsable = canStage(m),
                stagingMotionExclusionReason = if (canStage(m)) null else diagnosticReasons.firstOrNull()?.name,
                stagingMotionRole = if (canStage(m)) "full" else stagingRole(m),
                endMillis = m.end,
                reasons = diagnosticReasons,
                canStage = canStage(m),
                canEnterDeep = decision.entryDecision.allowed,
                canMaintainDeep = decision.maintenanceDecision.allowed,
                phoneUseMillis = awakeOverlap(m.start, m.end),
                inOnsetGuard = m.inOnsetGuard,
                hasSleepEvidence = !m.beforeEvidence,
                motion = m.motion ?: m.coverageMotion,
                currentEligibilityReasons = decision.currentEligibilityReasons,
                windowBlockingReasons = decision.windowBlockingReasons,
                windowBlockingIntervals = decision.windowBlockingIndices.map { blocked ->
                    timeline[blocked].start..(timeline[blocked].end - 1)
                },
                nonBlockingReasons = decision.nonBlockingReasons,
                baselineReasons = decision.baselineReasons,
                transitionReason = decision.transitionReason,
                priorState = decision.priorState,
                currentEligibility = decision.currentEligibility,
                entryDecision = decision.entryDecision,
                maintenanceDecision = decision.maintenanceDecision,
                action = decision.action,
                formalStage = formalStage,
                wasBackfilled = wasBackfilled[i],
                safetyCapAdjusted = safetyCapAdjusted[i],
                retroactivelyAdjusted = retroactivelyAdjusted[i],
                retroactiveAdjustmentReason = retroactiveAdjustmentReason[i],
                retroactiveAdjustmentSourceMillis = retroactiveAdjustmentSourceMillis[i],
                finalStage = finalStage,
                highMotionWindowsBefore = decision.highMotionWindowsBefore,
                highMotionWindowsCandidate = decision.highMotionWindowsCandidate,
                highMotionWindowsAfter = decision.highMotionWindowsAfter
            )
        }
        var gapRun = 0L; var longestGap = 0L
        timeline.forEach { m ->
            gapRun = if (m.coverageMotion == null || m.coverageMotion.coveredMillis == 0L) gapRun + m.end - m.start else 0L
            longestGap = maxOf(longestGap, gapRun, m.coverageMotion?.longestGapMillis ?: 0L)
        }
        val currentFeatureValidMinutes = timeline.count { it.validMotion && it.currentFeature && it.consistentCoverage }
        return StagingResult(
            finalIntervals, baseline, coverage,
            timeline.firstNotNullOfOrNull { m -> m.coverageMotion?.takeIf { it.sampleCount > 0 || it.coveredMillis > 0 }?.let { it.observedStart ?: it.startMillis } }?.let { maxOf(0, it - session.startMillis) },
            diagnostic,
            sensorCoverageRatio = sensorCoverage,
            currentFeatureValidMinutes = currentFeatureValidMinutes,
            baselineEligibleMinutes = baseline?.sampleCount ?: 0,
            firstValidMotionDelayMillis = timeline.indices.firstOrNull { i -> i >= DEEP_WINDOW_MINUTES - 1 &&
                (i - DEEP_WINDOW_MINUTES + 1..i).all { j -> timeline[j].observedQuality &&
                    timeline[j].motion?.recordingId == timeline[i].motion?.recordingId &&
                    timeline[j].motion?.featureVersion == timeline[i].motion?.featureVersion }
            }?.let { timeline[it].end - session.startMillis },
            stageableCoverageRatio = if (sleepMillis == 0L) 0.0 else timeline.filter { canStage(it) }
                .sumOf { it.end - it.start - awakeOverlap(it.start, it.end) }.toDouble() / sleepMillis,
            longestGapMillis = longestGap, durations = durations,
            undeterminedReasonsMillis = emptyMap(),
            fallbackLightReasonsMillis = diagnostic.filter { it.isFallbackLight }
                .groupBy { it.primaryReason ?: Reason.BASELINE_INSUFFICIENT }
                .mapValues { (_, rows) -> rows.sumOf { it.endMillis - it.startMillis - it.phoneUseMillis } }
        )
    }

    private fun nightlyBaseline(timeline: List<MinuteSignal>): NightlyBaseline? {
        // BED-only baseline: UNKNOWN/BEDSIDE never establish or maintain Deep.
        val eligible = timeline.filter { it.bed && it.currentFeature }
        val version = eligible.groupBy { it.motion!!.featureVersion }.filterValues { it.size >= MINIMUM_BASELINE_MINUTES }
            .keys.maxOrNull() ?: return null
        val sorted = eligible.filter { it.motion!!.featureVersion == version }.map { it.motion!!.rms }.sorted()
        if (sorted.size < MINIMUM_BASELINE_MINUTES) return null
        val p25 = percentileSorted(sorted, 0.25)
        val p50 = percentileSorted(sorted, 0.50)
        val p75 = percentileSorted(sorted, 0.75)
        return NightlyBaseline(p25, percentileSorted(sorted, 0.35), p50,
            percentileSorted(sorted, 0.65), percentileSorted(sorted, 0.70), p75, sorted.size,
            sorted.last() - sorted.first() <= maxOf(MIN_SIGNAL_RANGE, p50 * MIN_RELATIVE_SIGNAL_RANGE), featureVersion = version)
    }

    private fun rollingFeatures(timeline: List<MinuteSignal>, range: IntRange): RollingFeatures {
        val version = timeline[range.last].motion?.featureVersion
        val usable = range.map { timeline[it] }.filter { it.usable && it.currentFeature && it.motion?.featureVersion == version }
        val sorted = usable.map { it.motion!!.rms }.sorted()
        return RollingFeatures(usable.size, usable.count { it.quiet },
            usable.count { it.motion?.level == MotionLevel.ACTIVE },
            sorted.takeIf { it.isNotEmpty() }?.let { percentileSorted(it, 0.50) },
            sorted.takeIf { it.isNotEmpty() }?.let { percentileSorted(it, 0.75) },
            usable.count { it.bed }, usable.count { it.motion?.placement != Placement.BED })
    }

    private fun currentEligibilityReasons(minute: MinuteSignal, baseline: NightlyBaseline?): List<Reason> = buildList {
        if (minute.beforeEvidence || !minute.inSchedule) add(Reason.NO_SLEEP_EVIDENCE)
        if (minute.inPhoneUse) add(Reason.PHONE_IN_USE)
        if (minute.motion == null) add(Reason.MISSING_MOTION)
        else {
            if (!minute.validMotion ||
                (minute.currentFeature && minute.hasRequiredFeatures &&
                    !minute.recordingBoundary && !minute.observedQuality)) {
                add(Reason.INSUFFICIENT_COVERAGE)
            }
            if (!minute.currentFeature || !minute.hasRequiredFeatures ||
                (baseline != null && minute.motion.featureVersion != baseline.featureVersion)) add(Reason.LEGACY_FEATURE_LIMITATION)
            if (!minute.couplingSupported) add(couplingReason(minute.motion))
            if (minute.motion.placement != Placement.BED) add(Reason.PLACEMENT_NOT_BED)
        }
        if (minute.recordingIdBoundary) add(Reason.RECORDING_BOUNDARY)
        if (minute.featureBoundary) add(Reason.LEGACY_FEATURE_LIMITATION)
    }.distinct()

    private fun baselineReasons(baseline: NightlyBaseline?): List<Reason> = listOfNotNull(
        if (baseline == null) Reason.BASELINE_INSUFFICIENT else null,
        if (baseline?.narrowDistribution == true) Reason.LOW_SIGNAL_DIFFERENTIATION else null
    )

    private fun couplingReason(motion: MotionMinute): Reason = when {
        motion.coupling?.reason == "COUPLING_EXPIRED" -> Reason.COUPLING_EXPIRED
        motion.coupling?.reason?.contains("HANDLING") == true || motion.coupling?.reason?.contains("PHONE") == true -> Reason.COUPLING_INVALIDATED
        motion.coupling?.reason == "LEGACY_PLACEMENT" -> Reason.LEGACY_PLACEMENT_UNKNOWN
        else -> Reason.COUPLING_INSUFFICIENT
    }

    private fun evaluateFormalDecision(
        index: Int,
        timeline: List<MinuteSignal>,
        rolling: List<RollingFeatures>,
        baseline: NightlyBaseline?,
        evidenceStart: Long?,
        priorState: Boolean,
        highMotionWindowsBefore: Int,
        lastSustainedMotionExitIndex: Int,
        canStage: (MinuteSignal) -> Boolean
    ): FormalDecision {
        val minute = timeline[index]
        val currentReasons = currentEligibilityReasons(minute, baseline)
        val baselineReasons = baselineReasons(baseline)
        val currentEligibility = canStage(minute)
        val entry = evaluateEntry(
            index, timeline, rolling[index], baseline, evidenceStart,
            currentEligibility, currentReasons, baselineReasons,
            lastSustainedMotionExitIndex
        )
        // Retain the entry evaluation for diagnostic context, but mark only the
        // decision branch selected by the formal state machine as applicable.
        val entryDecision = entry.decision.copy(applicable = !priorState)
        val currentBreak = minute.hardBreak || !currentEligibility || baseline == null

        if (currentBreak) {
            val maintenance = if (priorState) {
                Decision(true, false, maintenanceBreakReasons(minute, baseline, currentReasons, baselineReasons))
            } else {
                Decision(false, false)
            }
            return FormalDecision(
                priorState, currentEligibility, currentReasons, baselineReasons,
                entryDecision, entry.windowReasons, entry.blockingIndices, entry.nonBlockingReasons,
                maintenance, if (priorState) Action.EXIT else Action.NONE,
                if (priorState) transitionReasonFor(maintenance.reasons, hardBreak = true) else null,
                entry.relativeQuietSupportIndices,
                highMotionWindowsBefore, 0, 0
            )
        }

        if (priorState) {
            val recent = maxOf(0, index - 4)..index
            val short = rollingFeatures(timeline, recent)
            val highCandidate = if (short.validMinutes == 5 && (short.medianRms ?: Double.MAX_VALUE) > baseline!!.p70) {
                highMotionWindowsBefore + 1
            } else {
                0
            }
            val maintenanceReasons = buildList {
                if (short.activeMinutes >= 3) {
                    add(Reason.EXIT_SUSTAINED_ACTIVITY)
                    add(Reason.EXIT_ACTIVE_3_IN_5)
                }
                if (recent.sumOf { timeline[it].motion?.movementEvents ?: 0 } >= EXIT_DENSE_EVENTS) {
                    add(Reason.EXIT_SUSTAINED_ACTIVITY)
                    add(Reason.EXIT_DENSE_EVENTS)
                }
                if (!minute.couplingSupported) add(Reason.EXIT_COUPLING_LOST)
                if (highCandidate >= 3) {
                    add(Reason.EXIT_SUSTAINED_ACTIVITY)
                    add(Reason.EXIT_SUSTAINED_ROLLING_MOTION)
                }
            }.distinct()
            val maintenance = Decision(true, maintenanceReasons.isEmpty(), maintenanceReasons)
            val action = if (maintenance.allowed) Action.MAINTAIN else Action.EXIT
            val transition = if (maintenance.allowed) null else transitionReasonFor(maintenance.reasons, hardBreak = false)
            return FormalDecision(
                priorState, currentEligibility, currentReasons, baselineReasons,
                entryDecision, entry.windowReasons, entry.blockingIndices, entry.nonBlockingReasons,
                maintenance, action, transition, entry.relativeQuietSupportIndices,
                highMotionWindowsBefore, highCandidate,
                if (maintenance.allowed) highCandidate else 0
            )
        }

        val action = if (entryDecision.allowed) Action.ENTER else Action.NONE
        return FormalDecision(
            priorState, currentEligibility, currentReasons, baselineReasons,
            entryDecision, entry.windowReasons, entry.blockingIndices, entry.nonBlockingReasons,
            Decision(false, false), action, if (action == Action.ENTER) entry.transitionReason else null,
            entry.relativeQuietSupportIndices,
            highMotionWindowsBefore, 0, 0
        )
    }

    private fun evaluateEntry(
        index: Int,
        timeline: List<MinuteSignal>,
        features: RollingFeatures,
        baseline: NightlyBaseline?,
        evidenceStart: Long?,
        currentEligibility: Boolean,
        currentReasons: List<Reason>,
        baselineReasons: List<Reason>,
        lastSustainedMotionExitIndex: Int
    ): EntryEvaluation {
        val windowReasons = linkedSetOf<Reason>()
        val blockingIndices = linkedSetOf<Int>()
        val nonBlockingReasons = linkedSetOf<Reason>()
        val range = if (index >= DEEP_WINDOW_MINUTES - 1) index - DEEP_WINDOW_MINUTES + 1..index else 0..index
        fun block(reason: Reason, indices: Iterable<Int>) {
            windowReasons += reason
            blockingIndices += indices
        }
        fun blockCurrent(reason: Reason) = block(reason, listOf(index))

        if (index < DEEP_WINDOW_MINUTES - 1) {
            block(Reason.WINDOW_TOO_SHORT, range)
        } else {
            val gaps = range.filter { timeline[it].minorObservedGap }
            val incomplete = range.filter { !timeline[it].observedQuality && !timeline[it].minorObservedGap }
            val missingMillis = gaps.sumOf { timeline[it].fullBucketMissingMillis ?: Long.MAX_VALUE }
            if (incomplete.isNotEmpty()) block(Reason.WINDOW_CONTAINS_GAP, incomplete)
            if (gaps.size > MAX_MINOR_GAP_MINUTES_IN_WINDOW || missingMillis > MAX_MINOR_GAP_TOTAL_MILLIS) {
                val overBudget = gaps.ifEmpty { incomplete }
                block(Reason.MINOR_GAP_BUDGET_EXCEEDED, overBudget)
                block(Reason.WINDOW_CONTAINS_GAP, overBudget)
            }
            val recentIncomplete = (index - 4..index).filter { !timeline[it].observedQuality }
            if (recentIncomplete.isNotEmpty()) block(Reason.RECENT_WINDOW_INCOMPLETE, recentIncomplete)
            if (range.any { !timeline[it].couplingSupported }) {
                block(Reason.COUPLING_INSUFFICIENT, range.filter { !timeline[it].couplingSupported })
            }
            if (range.any { timeline[it].recordingIdBoundary }) {
                block(Reason.RECORDING_BOUNDARY, range.filter { timeline[it].recordingIdBoundary })
            }
            // A missing nightly baseline is its own formal failure.  Comparing a
            // valid current feature with `null` used to mislabel almost the whole
            // night as legacy in diagnostics, even when every measured row was
            // produced by the current collector.
            if (baseline != null && range.any { it != index && timeline[it].motion?.featureVersion != baseline.featureVersion }) {
                block(Reason.LEGACY_FEATURE_LIMITATION, range.filter { timeline[it].motion?.featureVersion != baseline.featureVersion })
            }
            val phone = range.filter { timeline[it].inPhoneUse }
            if (phone.isNotEmpty()) block(Reason.PHONE_IN_USE, phone)
            // The formal V7 entry rule protects the current minute's onset age;
            // older minutes in the 15-minute context are not retroactively
            // rejected merely because they preceded the age threshold.
            val onset = range.filter { timeline[it].inOnsetGuard }
            if (onset.isNotEmpty()) block(Reason.ONSET_GUARD, onset)
            val noEvidence = range.filter { timeline[it].beforeEvidence || !timeline[it].inSchedule }
            if (noEvidence.isNotEmpty()) block(Reason.NO_SLEEP_EVIDENCE, noEvidence)
            val bedside = range.filter { timeline[it].coverageMotion?.placement == Placement.BEDSIDE }
            if (bedside.isNotEmpty()) block(Reason.LEGACY_PLACEMENT_UNKNOWN, bedside)
        }

        if (baseline != null && evidenceStart != null && index >= DEEP_WINDOW_MINUTES - 1) {
            val minute = timeline[index]
            if (!minute.bed || !minute.quiet) blockCurrent(Reason.ACTIVITY_TOO_HIGH)
            if (minute.start < maxOf(timeline.first().start, evidenceStart) + MIN_SESSION_AGE_FOR_DEEP) {
                blockCurrent(Reason.ONSET_GUARD)
            }
            val short = rollingFeatures(timeline, index - 4..index)
            if ((short.medianRms ?: Double.MAX_VALUE) > baseline.p70 || short.activeMinutes >= 3) {
                block(Reason.ACTIVITY_TOO_HIGH, index - 4..index)
            }
            if (features.bedMinutes < 10 || features.quietMinutes.toDouble() / features.validMinutes.coerceAtLeast(1) < 0.80 ||
                features.activeMinutes > 1 || (features.medianRms ?: Double.MAX_VALUE) > baseline.p50 ||
                range.sumOf { timeline[it].motion?.movementEvents ?: 0 } > ENTER_MAX_EVENTS ||
                range.any { (timeline[it].motion?.longestActiveMillis ?: 0L) >= MAX_CONTINUOUS_ACTIVE_MILLIS }) {
                block(Reason.ACTIVITY_TOO_HIGH, range)
            }
        }

        val allReasons = (currentReasons + baselineReasons + windowReasons).distinct()
        val acceptedTenHertzGap = range.any {
            val candidate = timeline[it]
            candidate.motion?.featureVersion == MotionAccumulator.CURRENT_FEATURE_VERSION &&
                candidate.observedQuality && (candidate.motion.longestGapMillis ?: 0L) > 0L
        }
        if (acceptedTenHertzGap || (range.any { timeline[it].minorObservedGap } &&
            range.filter { timeline[it].minorObservedGap }.size <= MAX_MINOR_GAP_MINUTES_IN_WINDOW &&
            range.filter { timeline[it].minorObservedGap }.sumOf { timeline[it].fullBucketMissingMillis ?: Long.MAX_VALUE } <= MAX_MINOR_GAP_TOTAL_MILLIS)) {
            nonBlockingReasons += Reason.ALLOWED_MINOR_GAP
        }
        // A strict 15-minute window remains the preferred entry path. Real phones can,
        // however, produce sparse high-quality BED observations separated by collector
        // gaps. The alternative treats an observed value at or below nightly p70 as a
        // provisional relative-quiet entry. It becomes durable either through a second
        // prior low observation or by naturally lasting for at least two minutes; the
        // short-run post-filter removes an otherwise isolated entry. This adds no duration
        // quota and no fixture-specific threshold. Missing, UNKNOWN and BEDSIDE minutes
        // still fall back to Light and immediately exit Deep.
        val current = timeline[index]
        val sparseHardReset = range.any {
            it != index && (timeline[it].inPhoneUse || timeline[it].beforeEvidence ||
                !timeline[it].inSchedule || timeline[it].recordingBoundary)
        }
        // A brief turn should not poison the full sparse 15-minute context. Reuse
        // the five-minute maintenance horizon to reject only recent dense activity.
        val relativeActivityRange = maxOf(0, index - 4)..index
        val relativeActivity = rollingFeatures(timeline, relativeActivityRange)
        val sparseActivityBlocked = relativeActivity.activeMinutes >= 3 ||
            relativeActivityRange.sumOf { timeline[it].motion?.movementEvents ?: 0 } >= EXIT_DENSE_EVENTS ||
            relativeActivityRange.any { (timeline[it].motion?.longestActiveMillis ?: 0L) >= MAX_CONTINUOUS_ACTIVE_MILLIS }
        // Find corroborating, independently stage-eligible low-RMS BED observations in
        // the existing 15-minute horizon. The current point may enter provisionally at
        // p70; corroboration is recorded separately so only actual low observations may
        // survive as a short confirmed run. This never targets a nightly duration.
        val relativeQuietSupportIndices = if (baseline?.relativeQuietThreshold == null || evidenceStart == null) emptySet() else range.filterTo(linkedSetOf()) { candidateIndex ->
            candidateIndex != index && timeline[candidateIndex].let { candidate ->
                val candidateRecent = maxOf(0, candidateIndex - 4)..candidateIndex
                val candidateActivityBlocked = rollingFeatures(timeline, candidateRecent).activeMinutes >= 3 ||
                    candidateRecent.sumOf { timeline[it].motion?.movementEvents ?: 0 } >= EXIT_DENSE_EVENTS ||
                    candidateRecent.any { (timeline[it].motion?.longestActiveMillis ?: 0L) >= MAX_CONTINUOUS_ACTIVE_MILLIS }
                candidateIndex > lastSustainedMotionExitIndex &&
                candidate.stageEligible && candidate.motion?.featureVersion == baseline.featureVersion &&
                    !candidate.inOnsetGuard &&
                    candidate.start >= maxOf(timeline.first().start, evidenceStart) + MIN_SESSION_AGE_FOR_DEEP &&
                    candidate.quiet && candidate.bed && candidate.motion.rms <= baseline.p70 &&
                    (candidate.motion.movementEvents ?: Int.MAX_VALUE) <= ENTER_MAX_EVENTS &&
                    !candidateActivityBlocked
            }
        }
        // Sparse relative entry remains a compatibility path for legacy 1 Hz
        // summaries. New 10 Hz evidence has enough temporal resolution to require
        // the strict continuous 15-minute entry window instead.
        val provisionalRelativeQuiet = baseline?.relativeQuietThreshold?.let { threshold ->
            current.motion?.rms?.let { it <= threshold } == true
        } == true
        val confirmedRelativeQuiet = provisionalRelativeQuiet && relativeQuietSupportIndices.isNotEmpty()
        val relativeQuietEntry = baseline != null && evidenceStart != null && currentEligibility &&
            !current.inOnsetGuard &&
            current.start >= maxOf(timeline.first().start, evidenceStart) + MIN_SESSION_AGE_FOR_DEEP &&
            current.bed && current.quiet && provisionalRelativeQuiet &&
            !sparseHardReset && !sparseActivityBlocked &&
            (current.motion?.movementEvents ?: Int.MAX_VALUE) <= ENTER_MAX_EVENTS &&
            (current.motion?.longestActiveMillis ?: Long.MAX_VALUE) < MAX_CONTINUOUS_ACTIVE_MILLIS
        if (allReasons.isNotEmpty() && relativeQuietEntry) {
            nonBlockingReasons += Reason.RELATIVE_QUIET_ENTRY
            if (confirmedRelativeQuiet) nonBlockingReasons += Reason.RELATIVE_QUIET_CONFIRMED
        }
        val strictEntry = allReasons.isEmpty()
        return EntryEvaluation(
            Decision(true, strictEntry || relativeQuietEntry, if (strictEntry || relativeQuietEntry) emptyList() else allReasons),
            windowReasons.toList(), blockingIndices,
            nonBlockingReasons.toList(),
            when {
                strictEntry -> "enter_stable_window"
                relativeQuietEntry -> "enter_relative_quiet"
                else -> null
            },
            relativeQuietSupportIndices.takeIf {
                !strictEntry && relativeQuietEntry && confirmedRelativeQuiet
            } ?: emptySet()
        )
    }

    private fun maintenanceBreakReasons(
        minute: MinuteSignal,
        baseline: NightlyBaseline?,
        currentReasons: List<Reason>,
        baselineReasons: List<Reason>
    ): List<Reason> = buildList {
        // Reuse the formal current blockers.  Maintenance contributes only the
        // explicit coupling-exit marker retained by the existing diagnostics.
        addAll(currentReasons)
        if (minute.inOnsetGuard) add(Reason.ONSET_GUARD)
        if (minute.motion != null && !minute.couplingSupported) add(Reason.EXIT_COUPLING_LOST)
        addAll(baselineReasons)
        if (isEmpty() && baseline == null) add(Reason.BASELINE_INSUFFICIENT)
    }.distinct()

    /** Fixed precedence over formal decision reasons; never re-inspects a minute. */
    private fun transitionReasonFor(reasons: List<Reason>, hardBreak: Boolean): String? {
        val transition = when {
            Reason.PHONE_IN_USE in reasons -> "exit_phone_use"
            Reason.ONSET_GUARD in reasons -> "exit_onset_guard"
            Reason.RECORDING_BOUNDARY in reasons -> "exit_recording_boundary"
            Reason.LEGACY_FEATURE_LIMITATION in reasons -> "exit_feature_boundary"
            reasons.any { it in setOf(
                Reason.COUPLING_EXPIRED, Reason.COUPLING_INVALIDATED,
                Reason.COUPLING_INSUFFICIENT, Reason.EXIT_COUPLING_LOST,
                Reason.LEGACY_PLACEMENT_UNKNOWN
            ) } -> "exit_coupling_lost"
            Reason.PLACEMENT_NOT_BED in reasons -> "exit_placement_not_bed"
            reasons.any { it in setOf(Reason.MISSING_MOTION, Reason.INSUFFICIENT_COVERAGE) } -> "exit_missing_motion"
            Reason.NO_SLEEP_EVIDENCE in reasons -> "exit_no_sleep_evidence"
            Reason.EXIT_ACTIVE_3_IN_5 in reasons -> "exit_active_3_in_5"
            Reason.EXIT_DENSE_EVENTS in reasons -> "exit_dense_events"
            Reason.EXIT_SUSTAINED_ROLLING_MOTION in reasons -> "exit_sustained_rolling_motion"
            Reason.EXIT_SUSTAINED_ACTIVITY in reasons -> "exit_sustained_activity"
            else -> null
        }
        return transition ?: if (hardBreak) "exit_unclassified_hard_break" else null
    }

    private fun stagingRole(minute: MinuteSignal): String {
        val motion = minute.motion ?: minute.coverageMotion
        return when {
        minute.inPhoneUse || minute.inOnsetGuard || !minute.inSchedule || minute.beforeEvidence -> "excluded"
        motion?.featureVersion == MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION -> "activity_only"
        motion?.featureVersion != null && !motion.supportsCurrentStaging -> "excluded"
        motion == null -> "excluded"
        motion.coveredMillis < MINUTE_COVERAGE_MILLIS || motion.level == MotionLevel.UNKNOWN -> "excluded"
        motion.placement == Placement.BEDSIDE -> "excluded"
        minute.bed -> "full"
        else -> "excluded"
        }
    }

    private fun applySafetyBound(
        timeline: List<MinuteSignal>, rolling: List<RollingFeatures>, stages: MutableList<SleepStage>,
        events: Array<String?>, safetyCapAdjusted: BooleanArray, baseline: NightlyBaseline?, sleepDuration: Long
    ) {
        if (baseline == null) return
        val limit = (sleepDuration * if (baseline.narrowDistribution) 0.35 else 0.55).toLong()
        var deepDuration = timeline.indices.filter { stages[it] == SleepStage.DEEP }.sumOf { timeline[it].end - timeline[it].start }
        if (deepDuration <= limit) return
        // Remove entire weakest runs, then trim their boundaries: no fixed physiological allocation.
        val runs = mutableListOf<List<Int>>()
        var run = mutableListOf<Int>()
        timeline.indices.forEach { i ->
            if (stages[i] == SleepStage.DEEP) run.add(i)
            else if (run.isNotEmpty()) { runs.add(run); run = mutableListOf() }
        }
        if (run.isNotEmpty()) runs.add(run)
        val weakestFirst = runs.sortedByDescending { indices ->
            indices.map { rolling[it].medianRms ?: Double.MAX_VALUE }.average()
        }
        for (indices in weakestFirst) {
            if (deepDuration <= limit) break
            val duration = indices.sumOf { timeline[it].end - timeline[it].start }
            if (deepDuration - duration >= limit || duration <= deepDuration - limit) {
                indices.forEach { i ->
                    stages[i] = SleepStage.LIGHT
                    safetyCapAdjusted[i] = true
                    events[i] = "safety_cap_low_differentiation"
                }
                deepDuration -= duration
            } else {
                // If no full run fits the remaining excess, trim the weakest boundary minute(s)
                // from this weakest run rather than mechanically trimming the latest run.
                val remaining = indices.toMutableList()
                while (remaining.isNotEmpty() && deepDuration > limit) {
                    val first = remaining.first()
                    val last = remaining.last()
                    val firstStrength = rolling[first].medianRms ?: Double.MAX_VALUE
                    val lastStrength = rolling[last].medianRms ?: Double.MAX_VALUE
                    val i = if (lastStrength >= firstStrength) last else first
                    stages[i] = SleepStage.LIGHT
                    safetyCapAdjusted[i] = true
                    events[i] = "safety_cap_low_differentiation"
                    deepDuration -= timeline[i].end - timeline[i].start
                    remaining.remove(i)
                }
            }
        }
    }

    /**
     * An isolated Deep minute is not enough temporal evidence for a Deep bout.
     * This filter only removes Deep; it never fills Light/gap minutes or targets
     * a nightly duration.
     */
    private fun filterShortDeepRuns(
        timeline: List<MinuteSignal>,
        stages: MutableList<SleepStage>,
        events: Array<String?>,
        retroactivelyAdjusted: BooleanArray,
        retroactiveAdjustmentReason: Array<PostProcessReason?>,
        retroactiveAdjustmentSourceMillis: Array<Long?>,
        wasBackfilled: BooleanArray,
        formalDecisions: Array<FormalDecision?>
    ) {
        var runStart: Int? = null
        fun finishRun(endExclusive: Int) {
            val start = runStart ?: return
            val duration = (start until endExclusive).sumOf { timeline[it].end - timeline[it].start }
            val tenHertzRun = (start until endExclusive).any { index ->
                timeline[index].motion?.featureVersion == MotionAccumulator.CURRENT_FEATURE_VERSION
            }
            val minimumDuration = if (tenHertzRun) TEN_HZ_MIN_DEEP_RUN_MILLIS else MIN_DEEP_RUN_MILLIS
            val independentlyConfirmed = !tenHertzRun && (start until endExclusive).any { index ->
                wasBackfilled[index] ||
                    formalDecisions[index]?.nonBlockingReasons?.contains(Reason.RELATIVE_QUIET_CONFIRMED) == true
            }
            if (duration < minimumDuration && !independentlyConfirmed) {
                val source = timeline[endExclusive - 1].end
                for (index in start until endExclusive) {
                    stages[index] = SleepStage.LIGHT
                    events[index] = "short_deep_run_fallback"
                    retroactivelyAdjusted[index] = true
                    retroactiveAdjustmentReason[index] = PostProcessReason.SHORT_DEEP_RUN_FILTER
                    retroactiveAdjustmentSourceMillis[index] = source
                }
            }
            runStart = null
        }
        timeline.indices.forEach { index ->
            if (stages[index] == SleepStage.DEEP) {
                if (runStart == null) runStart = index
            } else {
                finishRun(index)
            }
        }
        finishRun(timeline.size)
    }

    fun lastMeaningfulPhoneUseBefore(timeMillis: Long, intervals: List<UsageInterval>): Long? =
        intervals.asSequence().filter { it.startMillis < timeMillis && it.endMillis > it.startMillis }
            .map { minOf(timeMillis, it.endMillis) }.maxOrNull()

    private fun percentileSorted(sorted: List<Double>, fraction: Double): Double {
        val position = sorted.lastIndex * fraction
        val lower = position.toInt()
        val upper = minOf(sorted.lastIndex, lower + 1)
        val mix = position - lower
        return sorted[lower] * (1.0 - mix) + sorted[upper] * mix
    }
    private fun overlayAwakeIntervals(stages: List<SleepStageInterval>, awake: List<UsageInterval>): List<SleepStageInterval> {
        if (stages.isEmpty()) return emptyList()
        // Both lists are ordered. Sweep once rather than searching all stages for every boundary.
        val boundaries = (stages.flatMap { listOf(it.startMillis, it.endMillis) } +
            awake.flatMap { listOf(it.startMillis, it.endMillis) }).distinct().sorted()
        var stageIndex = 0
        var awakeIndex = 0
        return boundaries.zipWithNext().map { (start, end) ->
            while (stageIndex < stages.lastIndex && stages[stageIndex].endMillis <= start) stageIndex++
            while (awakeIndex < awake.size && awake[awakeIndex].endMillis <= start) awakeIndex++
            val isAwake = awakeIndex < awake.size && awake[awakeIndex].startMillis <= start && awake[awakeIndex].endMillis >= end
            SleepStageInterval(start, end, if (isAwake) SleepStage.AWAKE else stages[stageIndex].stage)
        }
    }
    private fun List<SleepStageInterval>.mergeAdjacentStages(): List<SleepStageInterval> {
        val result = mutableListOf<SleepStageInterval>()
        forEach { interval ->
            val previous = result.lastOrNull()
            if (previous != null && previous.endMillis == interval.startMillis && previous.stage == interval.stage)
                result[result.lastIndex] = previous.copy(endMillis = interval.endMillis)
            else result.add(interval)
        }
        return result
    }
}
