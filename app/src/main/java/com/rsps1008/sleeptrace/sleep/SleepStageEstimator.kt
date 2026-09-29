package com.rsps1008.sleeptrace.sleep

import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionLevel
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.Placement

/**
 * Offline, non-medical staging for an already accepted session. Phone stillness never creates a
 * session; it can only contribute relative stability after the sleep session has been confirmed.
 */
object SleepStageEstimator {
    const val SLEEP_ONSET_GUARD_MILLIS = 15 * MINUTE_MS
    const val MINIMUM_BASELINE_MINUTES = 10
    const val DEEP_WINDOW_MINUTES = 15
    const val MINIMUM_DEEP_MINUTES_IN_WINDOW = 10
    private const val MINUTE_COVERAGE_MILLIS = 45_000L
    private const val MAX_ACTIVE_RATIO = 0.02
    private const val MINIMUM_SLEEP_API_CONFIDENCE = 80

    private data class MinuteSignal(
        val start: Long,
        val end: Long,
        val motion: MotionMinute?,
        val inPhoneUse: Boolean,
        val inOnsetGuard: Boolean,
        val inSchedule: Boolean
    ) {
        val validMotion: Boolean
            get() = motion?.let {
                it.coveredMillis >= MINUTE_COVERAGE_MILLIS && it.level != MotionLevel.UNKNOWN && it.rms.isFinite()
            } == true
        val active: Boolean get() = validMotion && motion?.level == MotionLevel.ACTIVE
        val activeRatio: Double
            get() = motion?.takeIf { validMotion }?.let { it.activeMillis.toDouble() / it.coveredMillis } ?: 1.0
        val canUseForBaseline: Boolean
            get() = validMotion && !inPhoneUse && !inOnsetGuard && inSchedule && motion?.placement == Placement.BED
    }

    /**
     * Recomputes merged intervals from existing minute aggregates. The input session is phase-one
     * evidence; intervals outside it are never staged, even if the phone was perfectly still.
     */
    fun estimate(
        session: SleepSession,
        motionMinutes: List<MotionMinute>,
        classifications: List<ClassificationSample>,
        usageIntervals: List<UsageInterval>,
        sleepSegments: List<SleepSegment>,
        schedule: SleepSchedule
    ): List<SleepStageInterval> {
        if (session.endMillis <= session.startMillis) return emptyList()

        val meaningfulPhoneUse = (session.awakeIntervals + usageIntervals)
            .filter { it.endMillis > it.startMillis }
        val phoneUse = normalizedAwake(
            session.startMillis,
            session.endMillis,
            meaningfulPhoneUse
        )
        val motionByMinute = motionMinutes.associateBy { it.startMillis }
        val timeline = mutableListOf<MinuteSignal>()
        var cursor = session.startMillis
        while (cursor < session.endMillis) {
            val end = minOf(session.endMillis, cursor + MINUTE_MS)
            val motionStart = Math.floorDiv(cursor, MINUTE_MS) * MINUTE_MS
            val lastUse = lastMeaningfulPhoneUseBefore(cursor, meaningfulPhoneUse)
            timeline += MinuteSignal(
                start = cursor,
                end = end,
                motion = motionByMinute[motionStart],
                inPhoneUse = phoneUse.any { it.startMillis < end && it.endMillis > cursor },
                inOnsetGuard = lastUse != null && cursor - lastUse < SLEEP_ONSET_GUARD_MILLIS,
                inSchedule = schedule.windowAt(cursor) != null
            )
            cursor = end
        }

        val apiConfirmsSleep = sleepSegments.any {
            it.startMillis < session.endMillis && it.endMillis > session.startMillis
        } || classifications.any {
            it.confidence >= MINIMUM_SLEEP_API_CONFIDENCE &&
                it.timeMillis >= session.startMillis && it.timeMillis < session.endMillis
        }
        val baseline = timeline.asSequence()
            .filter { it.canUseForBaseline }
            .mapNotNull { it.motion?.rms?.takeIf(Double::isFinite) }
            .toList()
        val threshold = if (baseline.size >= MINIMUM_BASELINE_MINUTES) percentile(baseline, 0.35) else null
        val candidates = timeline.map { minute ->
            val motion = minute.motion
            minute.canUseForBaseline && threshold != null &&
                motion?.level == MotionLevel.QUIET &&
                motion.activeMillis.toDouble() / motion.coveredMillis < MAX_ACTIVE_RATIO &&
                motion.rms <= threshold
        }

        val confirmedWindows = BooleanArray(timeline.size)
        if (apiConfirmsSleep && threshold != null) {
            val windowSize = DEEP_WINDOW_MINUTES
            for (currentIndex in windowSize until timeline.size) {
                if (!candidates[currentIndex]) continue
                val startIndex = currentIndex - windowSize
                val window = startIndex until currentIndex
                if (window.any { index ->
                        val minute = timeline[index]
                        minute.inPhoneUse || !minute.validMotion || !minute.inSchedule ||
                            minute.motion?.placement != Placement.BED
                    }) continue
                if (hasLongActiveRun(timeline, window)) continue
                if (window.count { candidates[it] } >= MINIMUM_DEEP_MINUTES_IN_WINDOW) {
                    confirmedWindows[currentIndex] = true
                }
            }
        }

        val stages = MutableList(timeline.size) { SleepStage.LIGHT }
        var deep = false
        val bridge = mutableListOf<Int>()
        fun closeBridgeAsLight() {
            bridge.forEach { stages[it] = SleepStage.LIGHT }
            bridge.clear()
        }

        timeline.indices.forEach { index ->
            val minute = timeline[index]
            val signalIsHardBreak = minute.inPhoneUse || minute.inOnsetGuard || !minute.validMotion ||
                !minute.inSchedule || minute.motion?.placement != Placement.BED
            when {
                signalIsHardBreak -> {
                    closeBridgeAsLight()
                    deep = false
                }
                candidates[index] -> {
                    if (!deep && confirmedWindows[index]) deep = true
                    if (deep) {
                        bridge.forEach { stages[it] = SleepStage.DEEP }
                        bridge.clear()
                        stages[index] = SleepStage.DEEP
                    } else {
                        closeBridgeAsLight()
                    }
                }
                deep -> {
                    bridge += index
                    stages[index] = SleepStage.DEEP
                    if (bridge.size >= 3) {
                        closeBridgeAsLight()
                        deep = false
                    }
                }
                else -> closeBridgeAsLight()
            }
        }
        // Do not extend Deep into a final unconfirmed movement run at the end of a session.
        closeBridgeAsLight()

        val sleepStages = timeline.indices.map { index ->
            SleepStageInterval(timeline[index].start, timeline[index].end, stages[index])
        }.mergeAdjacentStages()
        return overlayAwakeIntervals(sleepStages, phoneUse).mergeAdjacentStages()
    }

    /** Last proven screen/app interaction ending before this instant. */
    fun lastMeaningfulPhoneUseBefore(timeMillis: Long, intervals: List<UsageInterval>): Long? =
        intervals.asSequence()
            .filter { it.startMillis < timeMillis && it.endMillis > it.startMillis }
            .map { minOf(timeMillis, it.endMillis) }
            .maxOrNull()

    private fun hasLongActiveRun(timeline: List<MinuteSignal>, window: IntRange): Boolean {
        var run = 0
        window.forEach { index ->
            if (timeline[index].active) {
                run++
                if (run >= 3) return true
            } else run = 0
        }
        return false
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = position.toInt()
        val upper = minOf(sorted.lastIndex, lower + 1)
        val mix = position - lower
        return sorted[lower] * (1.0 - mix) + sorted[upper] * mix
    }

    private fun overlayAwakeIntervals(
        stages: List<SleepStageInterval>,
        awakeIntervals: List<UsageInterval>
    ): List<SleepStageInterval> {
        if (stages.isEmpty()) return emptyList()
        val boundaries = buildSet {
            stages.forEach { add(it.startMillis); add(it.endMillis) }
            awakeIntervals.forEach { add(it.startMillis); add(it.endMillis) }
        }.sorted()
        return boundaries.zipWithNext().mapNotNull { (start, end) ->
            if (end <= start) return@mapNotNull null
            val stage = if (awakeIntervals.any { it.startMillis <= start && it.endMillis >= end }) {
                SleepStage.AWAKE
            } else {
                stages.firstOrNull { it.startMillis <= start && it.endMillis >= end }?.stage ?: SleepStage.LIGHT
            }
            SleepStageInterval(start, end, stage)
        }
    }

    private fun List<SleepStageInterval>.mergeAdjacentStages(): List<SleepStageInterval> {
        val result = mutableListOf<SleepStageInterval>()
        forEach { interval ->
            val previous = result.lastOrNull()
            if (previous != null && previous.endMillis == interval.startMillis && previous.stage == interval.stage) {
                result[result.lastIndex] = previous.copy(endMillis = interval.endMillis)
            } else result += interval
        }
        return result
    }
}
