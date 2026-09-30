package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.CouplingState
import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionAccumulator
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.Placement
import com.rsps1008.sleeptrace.motion.SamplingPlan
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepStage
import com.rsps1008.sleeptrace.sleep.SleepStageEstimator
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression cases for the V7 formal-decision and diagnostic provenance contract. */
class StagingDiagnosticConsistencyTest {
    private val base = 20_000L * MINUTE_MS
    private val schedule = SleepSchedule(0, 0)

    private fun session(minutes: Int = 80) = SleepSession(
        id = "diagnostic-consistency",
        startMillis = base,
        endMillis = base + minutes * MINUTE_MS,
        confidence = 90,
        awakeMillis = 0,
        state = SyncState.PENDING,
        reason = "synthetic review regression"
    )

    private fun row(index: Int, rms: Double = if (index < 56) .005 else .04) = MotionMinute(
        startMillis = base + index * MINUTE_MS,
        coveredMillis = MINUTE_MS,
        activeMillis = 0,
        squaredDeltaTime = rms * rms * MINUTE_MS,
        sampleCount = 60,
        placement = Placement.BED,
        featureVersion = MotionAccumulator.CURRENT_FEATURE_VERSION,
        maxDelta = rms * 3,
        movementEvents = 0,
        longestActiveMillis = 0,
        quietTailMillis = MINUTE_MS,
        longestGapMillis = 0,
        postureDelta = rms * 3,
        recordingId = 77L
    )

    private fun rows(minutes: Int = 80) = (0 until minutes).map(::row)

    private fun analyze(input: List<MotionMinute>, minutes: Int = 80) = SleepStageEstimator.analyze(
        session(minutes), input, emptyList(), emptyList(),
        listOf(SleepSegment(base, base + minutes * MINUTE_MS, 95)), schedule
    )

    private fun stageAt(result: SleepStageEstimator.StagingResult, index: Int) = result.intervals.single {
        base + index * MINUTE_MS >= it.startMillis && base + index * MINUTE_MS < it.endMillis
    }.stage

    private fun accumulatorMinute(index: Int, missingSeconds: Set<Int>): MotionMinute {
        val accumulator = MotionAccumulator(SamplingPlan.choose(1000), Placement.BED, 77L)
        for (second in 0..60) {
            if (second !in missingSeconds) accumulator.add(
                base + index * MINUTE_MS + second * 1_000L, 0.0, 0.0, 9.81
            )
        }
        return accumulator.drain(base + (index + 1) * MINUTE_MS).single()
    }

    private fun withAccumulatorGap(missingSeconds: Set<Int>): List<MotionMinute> = rows().map { minute ->
        if (minute.startMillis == base + 40 * MINUTE_MS) accumulatorMinute(40, missingSeconds) else minute
    }

    private fun active(minute: MotionMinute) = minute.copy(
        activeMillis = 6_000,
        squaredDeltaTime = .30 * .30 * MINUTE_MS,
        maxDelta = .30,
        movementEvents = 1,
        longestActiveMillis = 6_000,
        quietTailMillis = 0
    )

    @Test fun `dense short events formally exit and reject maintenance with the same reason`() {
        val result = analyze(rows().mapIndexed { index, minute ->
            if (index == 40 || index == 41) minute.copy(movementEvents = 5) else minute
        })
        val exit = result.minutes.first { it.event == "exit_dense_events" }

        assertTrue(exit.priorState)
        assertEquals(SleepStageEstimator.Action.EXIT, exit.action)
        assertTrue(exit.maintenanceDecision.applicable)
        assertFalse(exit.maintenanceDecision.allowed)
        assertFalse(exit.canMaintainDeep)
        assertTrue(SleepStageEstimator.Reason.EXIT_SUSTAINED_ACTIVITY in exit.maintenanceDecision.reasons)
        assertTrue(SleepStageEstimator.Reason.EXIT_SUSTAINED_ACTIVITY in exit.reasons)
    }

    @Test fun `low signal has no applicable Deep maintenance decision`() {
        val result = analyze((0 until 80).map { row(it, .002) })

        assertTrue(result.baseline!!.narrowDistribution)
        assertTrue(result.minutes.all { !it.canMaintainDeep })
        assertTrue(result.minutes.none { it.maintenanceDecision.applicable })
        assertTrue(result.minutes.all { SleepStageEstimator.Reason.LOW_SIGNAL_DIFFERENTIATION in it.reasons })
        assertTrue(result.intervals.all { it.stage == SleepStage.SLEEPING })
    }

    @Test fun `isolated activity uses the formal maintenance tolerance`() {
        val result = analyze(rows().mapIndexed { index, minute ->
            if (index == 35) minute.copy(
                activeMillis = 6_000,
                squaredDeltaTime = .30 * .30 * MINUTE_MS,
                maxDelta = .30,
                movementEvents = 1,
                longestActiveMillis = 6_000,
                quietTailMillis = 0
            ) else minute
        })
        val isolated = result.minutes[35]

        assertTrue(isolated.priorState)
        assertEquals(SleepStageEstimator.Action.MAINTAIN, isolated.action)
        assertTrue(isolated.maintenanceDecision.allowed)
        assertTrue(isolated.canMaintainDeep)
        assertEquals(SleepStage.DEEP, stageAt(result, 35))
    }

    @Test fun `high rolling motion keeps the counter until the formal three-window exit`() {
        val result = analyze(rows().mapIndexed { index, minute ->
            if (index in 40..52) minute.copy(
                squaredDeltaTime = .08 * .08 * MINUTE_MS,
                maxDelta = .24
            ) else minute
        })
        val candidates = result.minutes.filter {
            it.priorState && it.highMotionWindowsCandidate > 0
        }
        val exit = candidates.first { it.highMotionWindowsCandidate >= 3 }

        assertTrue(candidates.any { it.highMotionWindowsCandidate == 1 && it.action == SleepStageEstimator.Action.MAINTAIN })
        assertTrue(candidates.any { it.highMotionWindowsCandidate == 2 && it.action == SleepStageEstimator.Action.MAINTAIN })
        assertEquals(SleepStageEstimator.Action.EXIT, exit.action)
        assertFalse(exit.maintenanceDecision.allowed)
        assertEquals(0, exit.highMotionWindowsAfter)
        assertTrue(exit.transitionReason == "exit_sustained_rolling_motion")
    }

    @Test fun `active three in five rewrites only prior Deep minutes and records its confirmation source`() {
        val result = analyze(rows().mapIndexed { index, minute ->
            if (index in 40..42) active(minute) else minute
        })
        val exit = result.minutes[42]

        assertEquals(SleepStageEstimator.Action.EXIT, exit.action)
        assertEquals("exit_active_3_in_5", exit.event)
        assertFalse(exit.retroactivelyAdjusted)
        assertEquals(SleepStage.LIGHT, exit.formalStage)
        for (index in 40..41) {
            val minute = result.minutes[index]
            assertEquals(SleepStage.DEEP, minute.formalStage)
            assertEquals(SleepStage.LIGHT, minute.finalStage)
            assertTrue(minute.retroactivelyAdjusted)
            assertEquals(SleepStageEstimator.PostProcessReason.SUSTAINED_ACTIVITY_REWRITE,
                minute.retroactiveAdjustmentReason)
            assertEquals(base + 42 * MINUTE_MS, minute.retroactiveAdjustmentSourceMillis)
            assertFalse(minute.action == SleepStageEstimator.Action.EXIT)
            assertFalse(minute.event?.startsWith("exit_") == true)
        }
    }

    @Test fun `isolated activity keeps Deep without retrospective provenance`() {
        val result = analyze(rows().mapIndexed { index, minute ->
            if (index == 40) active(minute) else minute
        })
        val isolated = result.minutes[40]

        assertEquals(SleepStageEstimator.Action.MAINTAIN, isolated.action)
        assertEquals(SleepStage.DEEP, isolated.formalStage)
        assertEquals(SleepStage.DEEP, isolated.finalStage)
        assertFalse(isolated.retroactivelyAdjusted)
        assertEquals(null, isolated.retroactiveAdjustmentReason)
        assertEquals(null, isolated.retroactiveAdjustmentSourceMillis)
    }

    @Test fun `hard break transition is derived from complete formal reasons with fixed precedence`() {
        fun at(index: Int, transform: (MotionMinute) -> MotionMinute): SleepStageEstimator.MinuteDiagnostic =
            analyze(rows().mapIndexed { i, minute -> if (i == index) transform(minute) else minute }).minutes[index]

        val recording = at(40) { it.copy(recordingId = 78L) }
        assertTrue(SleepStageEstimator.Reason.RECORDING_BOUNDARY in recording.maintenanceDecision.reasons)
        assertEquals("exit_recording_boundary", recording.transitionReason)
        assertFalse(recording.transitionReason == "exit_no_sleep_evidence_or_schedule")

        val feature = at(40) { it.copy(featureVersion = MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION) }
        assertTrue(SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION in feature.maintenanceDecision.reasons)
        assertEquals("exit_feature_boundary", feature.transitionReason)

        val coverage = at(40) { it.copy(coveredMillis = 30_000L, sampleCount = 30) }
        assertTrue(SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE in coverage.maintenanceDecision.reasons)
        assertEquals("exit_missing_motion", coverage.transitionReason)
        assertFalse(coverage.transitionReason == "exit_no_sleep_evidence")

        val phoneAndBoundary = SleepStageEstimator.analyze(
            session(), rows().mapIndexed { i, minute -> if (i == 40) minute.copy(recordingId = 78L) else minute },
            emptyList(), listOf(UsageInterval(base + 40 * MINUTE_MS, base + 40 * MINUTE_MS + 5_000L)),
            listOf(SleepSegment(base, base + 80 * MINUTE_MS, 95)), schedule
        ).minutes[40]
        assertTrue(SleepStageEstimator.Reason.PHONE_IN_USE in phoneAndBoundary.maintenanceDecision.reasons)
        assertTrue(SleepStageEstimator.Reason.RECORDING_BOUNDARY in phoneAndBoundary.maintenanceDecision.reasons)
        assertEquals("exit_phone_use", phoneAndBoundary.transitionReason)
    }

    @Test fun `onset guard blocks entry but remains outside current eligibility blockers`() {
        val result = SleepStageEstimator.analyze(
            session(), rows(), emptyList(),
            listOf(UsageInterval(base + 39 * MINUTE_MS, base + 40 * MINUTE_MS)),
            listOf(SleepSegment(base, base + 80 * MINUTE_MS, 95)), schedule
        )
        val guarded = result.minutes[40]

        assertTrue(guarded.currentEligibility)
        assertTrue(guarded.currentEligibilityReasons.isEmpty())
        assertFalse(guarded.canEnterDeep)
        assertTrue(SleepStageEstimator.Reason.ONSET_GUARD in guarded.entryDecision.reasons)
        assertEquals(SleepStage.LIGHT, guarded.finalStage)
    }

    @Test fun `backfill activity and safety provenance use independent channels`() {
        val result = analyze(rows().mapIndexed { index, minute ->
            if (index in 40..42) active(minute) else minute
        })
        val rewritten = result.minutes.first { it.retroactivelyAdjusted }
        assertTrue(rewritten.retroactivelyAdjusted)
        assertEquals(SleepStageEstimator.PostProcessReason.SUSTAINED_ACTIVITY_REWRITE,
            rewritten.retroactiveAdjustmentReason)
        assertTrue(result.minutes.any { it.wasBackfilled })

        // The fields are deliberately independent: a later safety-cap pass can
        // set its flag without erasing the activity source and reason.
        val both = rewritten.copy(safetyCapAdjusted = true, finalStage = SleepStage.SLEEPING)
        assertTrue(both.retroactivelyAdjusted)
        assertEquals(SleepStageEstimator.PostProcessReason.SUSTAINED_ACTIVITY_REWRITE,
            both.retroactiveAdjustmentReason)
        assertTrue(both.safetyCapAdjusted)
    }

    @Test fun `legal real accumulator gap blocks only while it remains in the recent five-minute context`() {
        val result = analyze(withAccumulatorGap(setOf(10)))
        val gapStart = base + 40 * MINUTE_MS

        val gap = result.minutes[40].motion!!
        assertEquals(58_000L, gap.coveredMillis)
        assertEquals(2_000L, gap.longestGapMillis)
        for (index in 41..44) {
            val minute = result.minutes[index]
            assertFalse(minute.canEnterDeep)
            assertTrue(SleepStageEstimator.Reason.RECENT_WINDOW_INCOMPLETE in minute.windowBlockingReasons)
            assertTrue(minute.windowBlockingIntervals.any { gapStart in it })
            assertTrue(SleepStageEstimator.Reason.ALLOWED_MINOR_GAP in minute.nonBlockingReasons)
        }

        val reentry = result.minutes[45]
        assertTrue(reentry.canEnterDeep)
        assertTrue(reentry.windowBlockingReasons.isEmpty())
        assertTrue(reentry.windowBlockingIntervals.isEmpty())
        assertTrue(SleepStageEstimator.Reason.ALLOWED_MINOR_GAP in reentry.nonBlockingReasons)
        assertFalse(SleepStageEstimator.Reason.WINDOW_TOO_SHORT in reentry.currentEligibilityReasons)
        assertEquals("enter_stable_window", reentry.transitionReason)
    }

    @Test fun `real accumulator cumulative eight-second gap exceeds the minor-gap budget`() {
        val result = analyze(withAccumulatorGap(setOf(10, 20, 30, 40)))
        val minute = result.minutes[45]

        assertFalse(minute.canEnterDeep)
        assertTrue(SleepStageEstimator.Reason.MINOR_GAP_BUDGET_EXCEEDED in minute.windowBlockingReasons)
        assertTrue(SleepStageEstimator.Reason.WINDOW_CONTAINS_GAP in minute.windowBlockingReasons)
        assertFalse(SleepStageEstimator.Reason.ALLOWED_MINOR_GAP in minute.nonBlockingReasons)
        assertTrue(minute.windowBlockingIntervals.any { base + 40 * MINUTE_MS in it })
    }

    @Test fun `formal entry and later output retain backfill and safety-cap provenance`() {
        val normal = SleepStageEstimator.analyze(
            session(60), rows(60), emptyList(),
            listOf(UsageInterval(base + 20 * MINUTE_MS, base + 20 * MINUTE_MS + 1_000L)),
            listOf(SleepSegment(base, base + 60 * MINUTE_MS, 95)), schedule
        )
        val entry = normal.minutes.first { it.action == SleepStageEstimator.Action.ENTER }
        val backfilled = normal.minutes.filter { it.wasBackfilled }

        assertTrue(backfilled.isNotEmpty())
        assertTrue(backfilled.all { it.formalStage != SleepStage.DEEP })
        assertEquals(SleepStage.DEEP, entry.formalStage)
        assertTrue(backfilled.any { it.finalStage == SleepStage.DEEP })

        val capped = analyze((0 until 300).map { index -> row(index, if (index < 280) .005 else .08) }, 300)
        val adjusted = capped.minutes.filter { it.safetyCapAdjusted }
        assertTrue(adjusted.isNotEmpty())
        assertTrue(adjusted.all { it.formalStage == SleepStage.DEEP && it.finalStage == SleepStage.SLEEPING })
        capped.minutes.filter { it.action == SleepStageEstimator.Action.ENTER }.forEach {
            assertEquals("enter_stable_window", it.transitionReason)
        }
    }

    @Test fun `hard interruption is an explicit formal exit rather than a false maintenance approval`() {
        val useStart = base + 40 * MINUTE_MS
        val useEnd = useStart + 5_000L
        val result = SleepStageEstimator.analyze(
            session(), rows(), emptyList(), listOf(com.rsps1008.sleeptrace.sleep.UsageInterval(useStart, useEnd)),
            listOf(SleepSegment(base, base + 80 * MINUTE_MS, 95)), schedule
        )
        val minute = result.minutes[40]

        assertTrue(minute.priorState)
        assertEquals(SleepStageEstimator.Action.EXIT, minute.action)
        assertFalse(minute.maintenanceDecision.allowed)
        assertTrue(SleepStageEstimator.Reason.PHONE_IN_USE in minute.maintenanceDecision.reasons)
        assertEquals("exit_phone_use", minute.transitionReason)
    }
}
