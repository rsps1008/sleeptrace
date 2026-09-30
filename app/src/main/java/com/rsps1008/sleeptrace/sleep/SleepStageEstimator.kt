package com.rsps1008.sleeptrace.sleep

import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionLevel
import com.rsps1008.sleeptrace.motion.MotionAccumulator
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.MotionFeaturePolicy
import com.rsps1008.sleeptrace.motion.Placement

/** Offline engineering estimates inside an accepted session; never a clinical sleep stage. */
object SleepStageEstimator {
    const val ALGORITHM_VERSION = 3
    const val SLEEP_ONSET_GUARD_MILLIS = 15 * MINUTE_MS
    const val MINIMUM_BASELINE_MINUTES = 10
    const val DEEP_WINDOW_MINUTES = 15
    const val MIN_SESSION_AGE_FOR_DEEP = 20 * MINUTE_MS
    const val UNKNOWN_DEEP_GRACE_MINUTES = 5
    private const val DEEP_BACKFILL_MINUTES = 7
    private const val MINUTE_COVERAGE_MILLIS = 45_000L
    private const val MINIMUM_SLEEP_API_CONFIDENCE = 80

    data class NightlyBaseline(
        val p25: Double, val p35: Double, val p50: Double,
        val p65: Double, val p70: Double, val p75: Double,
        val bedMinutes: Int, val narrowDistribution: Boolean,
        val featureVersion: Int = MotionAccumulator.CURRENT_FEATURE_VERSION,
        val sampleCount: Int = bedMinutes,
        val reason: String? = null
    )
    data class RollingFeatures(
        val validMinutes: Int, val quietMinutes: Int, val activeMinutes: Int,
        val medianRms: Double?, val p75Rms: Double?,
        val bedMinutes: Int, val unknownMinutes: Int
    )
    data class MinuteDiagnostic(
        val startMillis: Long, val placement: Placement?, val level: MotionLevel?,
        val rollingMedianRms: Double?, val stage: SleepStage, val event: String?,
        val stagingMotionUsable: Boolean, val stagingMotionExclusionReason: String?,
        val stagingMotionRole: String = "excluded"
    )
    data class StagingResult(
        val intervals: List<SleepStageInterval>, val baseline: NightlyBaseline?,
        val motionCoverageRatio: Double, val firstMotionDelayMillis: Long?,
        val minutes: List<MinuteDiagnostic>,
        val baselineFeatureVersion: Int? = baseline?.featureVersion,
        val baselineSampleCount: Int = baseline?.sampleCount ?: 0,
        val baselineReason: String? = baseline?.reason ?: "insufficient_current_bed_motion",
        val sensorCoverageRatio: Double = motionCoverageRatio,
        val currentFeatureValidMinutes: Int = 0,
        val baselineEligibleMinutes: Int = 0
    )
    private data class MinuteSignal(
        val start: Long, val end: Long, val motion: MotionMinute?, val coverageMotion: MotionMinute?,
        val inPhoneUse: Boolean, val inOnsetGuard: Boolean, val inSchedule: Boolean,
        val beforeEvidence: Boolean
    ) {
        val validMotion: Boolean get() = motion?.let {
            it.coveredMillis >= MINUTE_COVERAGE_MILLIS && it.level != MotionLevel.UNKNOWN && it.rms.isFinite()
        } == true
        val currentFeature: Boolean get() = motion?.supportsCurrentStaging == true
        val hardBreak: Boolean get() = inPhoneUse || inOnsetGuard || !inSchedule ||
            beforeEvidence || !validMotion || !currentFeature || motion?.placement == Placement.BEDSIDE
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
                add(MinuteSignal(cursor, end, bucketMotion.takeIf { fullBucketInSession }, bucketMotion,
                    phoneUse.any { it.startMillis < end && it.endMillis > cursor },
                    lastUse != null && cursor - lastUse < SLEEP_ONSET_GUARD_MILLIS,
                    schedule.windowAt(cursor) != null,
                    evidenceStart == null || cursor < evidenceStart))
                cursor = end
            }
        }
        val baseline = nightlyBaseline(timeline)
        val rolling = timeline.indices.map { rollingFeatures(timeline, maxOf(0, it - DEEP_WINDOW_MINUTES + 1)..it) }
        val stages = MutableList(timeline.size) { SleepStage.LIGHT }
        val events = arrayOfNulls<String>(timeline.size)
        var deep = false
        var unknownRun = 0
        var highMotionWindows = 0
        timeline.indices.forEach { index ->
            val minute = timeline[index]
            if (minute.hardBreak || baseline == null) {
                if (deep) events[index] = when {
                    minute.inPhoneUse -> "exit_phone_use"
                    minute.inOnsetGuard -> "exit_onset_guard"
                    minute.coverageMotion?.placement == Placement.BEDSIDE -> "exit_bedside"
                    minute.coverageMotion != null && !minute.coverageMotion.supportsCurrentStaging -> "exit_legacy_or_incompatible_feature"
                    !minute.validMotion -> "exit_missing_motion"
                    else -> "exit_no_sleep_evidence_or_schedule"
                }
                deep = false; unknownRun = 0; highMotionWindows = 0
                return@forEach
            }
            val recent = maxOf(0, index - 4)..index
            val short = rollingFeatures(timeline, recent)
            val unknown = minute.motion?.placement != Placement.BED
            unknownRun = if (unknown) unknownRun + 1 else 0
            var exited = false
            if (deep) {
                val high = short.validMinutes == 5 && short.medianRms!! > baseline.p70
                highMotionWindows = if (high) highMotionWindows + 1 else 0
                val exitReason = when {
                    short.activeMinutes >= 3 -> "exit_active_3_in_5"
                    unknown && (!minute.quiet || minute.motion!!.rms > baseline.p75) -> "exit_unreliable_unknown"
                    unknownRun > UNKNOWN_DEEP_GRACE_MINUTES -> "exit_unknown_timeout"
                    highMotionWindows >= 3 -> "exit_sustained_rolling_motion"
                    else -> null
                }
                if (exitReason != null) {
                    // Sustained activity cannot remain a Deep bridge; isolated turns are retained.
                    if (short.activeMinutes >= 3) {
                        val firstActive = recent.first { timeline[it].motion?.level == MotionLevel.ACTIVE }
                        for (i in firstActive..index) stages[i] = SleepStage.LIGHT
                    }
                    events[index] = exitReason
                    deep = false; highMotionWindows = 0
                    exited = true
                }
            }
            if (!deep && !exited && canEnterDeep(index, timeline, rolling[index], baseline, evidenceStart)) {
                deep = true; unknownRun = 0; highMotionWindows = 0
                events[index] = "enter_stable_window"
                // At most seven proven stable minutes. Never cross any guard, gap or weak placement.
                for (i in index - 1 downTo maxOf(0, index - DEEP_BACKFILL_MINUTES)) {
                    val previous = timeline[i]
                    if (!previous.bed || !previous.quiet || !previous.validMotion || !previous.currentFeature || previous.motion!!.rms > baseline.p70 ||
                        previous.start < maxOf(session.startMillis, evidenceStart ?: session.startMillis) + MIN_SESSION_AGE_FOR_DEEP) break
                    stages[i] = SleepStage.DEEP
                }
            }
            if (deep) stages[index] = SleepStage.DEEP
        }
        applySafetyBound(timeline, rolling, stages, events, baseline)
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
        val sensorCoveredSleepMillis = timeline.sumOf { minute ->
            val overlap = (minute.end - minute.start).coerceAtLeast(0L)
            val raw = minute.coverageMotion?.takeIf { it.supportsCurrentStaging }?.coveredMillis?.coerceIn(0L, MINUTE_MS) ?: 0L
            val estimated = raw.toDouble() * overlap / MINUTE_MS
            val awake = awakeOverlap(minute.start, minute.end).coerceAtMost(overlap).toDouble() / MINUTE_MS
            (estimated - raw * awake).coerceAtLeast(0.0).toLong()
        }
        val coverage = (validMinuteSleepMillis.toDouble() / sleepMillis.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        val sensorCoverage = (sensorCoveredSleepMillis.toDouble() / sleepMillis.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        val currentFeatureValidMinutes = timeline.count { it.validMotion && it.currentFeature }
        val baselineEligibleMinutes = timeline.count { it.bed && it.currentFeature }
        return StagingResult(
            overlayAwakeIntervals(merged, phoneUse).mergeAdjacentStages(), baseline, coverage,
            timeline.firstOrNull { it.validMotion && it.currentFeature }?.let { it.start - session.startMillis },
            timeline.indices.map { i -> MinuteDiagnostic(timeline[i].start, (timeline[i].motion ?: timeline[i].coverageMotion)?.placement,
                (timeline[i].motion ?: timeline[i].coverageMotion)?.level, rolling[i].medianRms,
                if (timeline[i].inPhoneUse) SleepStage.AWAKE else stages[i], events[i],
                stagingUsable(timeline[i]), stagingExclusionReason(timeline[i], baseline), stagingRole(timeline[i])) },
            sensorCoverageRatio = sensorCoverage,
            currentFeatureValidMinutes = currentFeatureValidMinutes,
            baselineEligibleMinutes = baselineEligibleMinutes
        )
    }

    private fun nightlyBaseline(timeline: List<MinuteSignal>): NightlyBaseline? {
        // BED-only baseline: UNKNOWN can maintain established Deep briefly, never establish it alone.
        val sorted = timeline.filter { it.bed && it.currentFeature }.map { it.motion!!.rms }.sorted()
        if (sorted.size < MINIMUM_BASELINE_MINUTES) return null
        val p25 = percentileSorted(sorted, 0.25)
        val p50 = percentileSorted(sorted, 0.50)
        val p75 = percentileSorted(sorted, 0.75)
        return NightlyBaseline(p25, percentileSorted(sorted, 0.35), p50,
            percentileSorted(sorted, 0.65), percentileSorted(sorted, 0.70), p75, sorted.size,
            p75 - p25 <= maxOf(0.0001, p50 * 0.15))
    }

    private fun rollingFeatures(timeline: List<MinuteSignal>, range: IntRange): RollingFeatures {
        val usable = range.map { timeline[it] }.filter { it.usable && it.currentFeature }
        val sorted = usable.map { it.motion!!.rms }.sorted()
        return RollingFeatures(usable.size, usable.count { it.quiet },
            usable.count { it.motion?.level == MotionLevel.ACTIVE },
            sorted.takeIf { it.isNotEmpty() }?.let { percentileSorted(it, 0.50) },
            sorted.takeIf { it.isNotEmpty() }?.let { percentileSorted(it, 0.75) },
            usable.count { it.bed }, usable.count { it.motion?.placement != Placement.BED })
    }

    private fun canEnterDeep(
        index: Int, timeline: List<MinuteSignal>, features: RollingFeatures,
        baseline: NightlyBaseline, evidenceStart: Long?
    ): Boolean {
        if (index < DEEP_WINDOW_MINUTES - 1 || evidenceStart == null) return false
        val minute = timeline[index]
        if (!minute.bed || !minute.quiet ||
            minute.start < maxOf(timeline.first().start, evidenceStart) + MIN_SESSION_AGE_FOR_DEEP) return false
        val range = index - DEEP_WINDOW_MINUTES + 1..index
        if (range.any { (timeline[it].coverageMotion?.let { motion -> !motion.supportsCurrentStaging } == true) || timeline[it].inPhoneUse || timeline[it].inOnsetGuard ||
                timeline[it].beforeEvidence || !timeline[it].inSchedule ||
                timeline[it].coverageMotion?.placement == Placement.BEDSIDE }) return false
        return features.validMinutes >= 12 && features.bedMinutes >= (if (baseline.narrowDistribution) 12 else 10) &&
            features.quietMinutes.toDouble() / features.validMinutes >= 0.80 &&
            features.activeMinutes <= 1 && features.medianRms!! <= baseline.p50
    }

    private fun stagingRole(minute: MinuteSignal): String {
        val motion = minute.motion ?: minute.coverageMotion
        return when {
        minute.inPhoneUse || minute.inOnsetGuard || !minute.inSchedule || minute.beforeEvidence -> "excluded"
        motion?.featureVersion == MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION -> "activity_only"
        motion?.featureVersion != null && motion.featureVersion != MotionAccumulator.CURRENT_FEATURE_VERSION -> "excluded"
        motion == null -> "excluded"
        motion.coveredMillis < MINUTE_COVERAGE_MILLIS || motion.level == MotionLevel.UNKNOWN -> "excluded"
        motion.placement == Placement.BEDSIDE -> "excluded"
        minute.motion?.placement == Placement.UNKNOWN && minute.quiet -> "stay_only"
        minute.bed -> "full"
        else -> "excluded"
        }
    }

    private fun stagingUsable(minute: MinuteSignal): Boolean =
        stagingRole(minute) in setOf("full", "stay_only")

    private fun stagingExclusionReason(minute: MinuteSignal, baseline: NightlyBaseline?): String? {
        if (stagingRole(minute) in setOf("full", "stay_only")) return null
        val motion = minute.motion ?: minute.coverageMotion
        return when {
        minute.inPhoneUse -> "in_phone_use"
        minute.inOnsetGuard -> "onset_guard"
        !minute.inSchedule -> "outside_schedule"
        minute.beforeEvidence -> "before_sleep_evidence"
        motion == null -> "missing_motion_or_partial_minute"
        motion.featureVersion == MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION -> "cadence_incompatible_feature"
        motion.featureVersion != MotionAccumulator.CURRENT_FEATURE_VERSION -> "legacy_feature_version"
        motion.coveredMillis < MINUTE_COVERAGE_MILLIS || motion.level == MotionLevel.UNKNOWN -> "insufficient_coverage"
        motion.placement == Placement.BEDSIDE -> "bedside"
        motion.placement == Placement.UNKNOWN && minute.quiet -> null
        motion.placement == Placement.UNKNOWN -> "placement_unknown"
        baseline == null -> "insufficient_current_bed_motion"
        else -> null
        }
    }

    private fun applySafetyBound(
        timeline: List<MinuteSignal>, rolling: List<RollingFeatures>, stages: MutableList<SleepStage>,
        events: Array<String?>, baseline: NightlyBaseline?
    ) {
        if (baseline == null) return
        val sleepDuration = timeline.filterNot { it.inPhoneUse }.sumOf { it.end - it.start }
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
                indices.forEach { i -> stages[i] = SleepStage.LIGHT; events[i] = "safety_cap_low_differentiation" }
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
                    events[i] = "safety_cap_low_differentiation"
                    deepDuration -= timeline[i].end - timeline[i].start
                    remaining.remove(i)
                }
            }
        }
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
