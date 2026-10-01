package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin

/** Regressions discovered while reviewing the first formal 10 Hz release. */
class TenHertzReviewRegressionTest {
    private val base = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val schedule = SleepSchedule(0, 600)

    private fun rows(n: Int, smallGaps: Boolean = false) = (0 until n).map { index ->
        val rms = if (index < n * .7) .005 else .04
        val gap = smallGaps && index % 5 == 2
        val covered = if (gap) MINUTE_MS - 200L else MINUTE_MS
        MotionMinute(base + index * MINUTE_MS, covered, 0L, rms * rms * covered,
            if (gap) 599 else 600, Placement.BED, MotionAccumulator.CURRENT_FEATURE_VERSION,
            maxDelta = rms * 3, movementEvents = 0, longestActiveMillis = 0,
            quietTailMillis = 30_000, longestGapMillis = if (gap) 200 else 0,
            postureDelta = 0.0, recordingId = 1,
            observedStart = base + index * MINUTE_MS, observedEnd = base + (index + 1) * MINUTE_MS)
    }

    private fun analyze(input: List<MotionMinute>, start: Int = 0, end: Int = input.size): SleepStageEstimator.StagingResult {
        val session = SleepSession("review", base + start * MINUTE_MS, base + end * MINUTE_MS,
            80, 0, SyncState.PENDING, "review")
        return SleepStageEstimator.analyze(session, input, emptyList(), emptyList(),
            listOf(SleepSegment(session.startMillis, session.endMillis, 100)), schedule)
    }

    @Test fun acceptedTenHzSmallGapsDoNotBlockEveryEntryWindow() {
        val control = analyze(rows(120))
        val gaps = analyze(rows(120, true))
        println("SMALL_GAPS controlDeep=${control.durations.deep / MINUTE_MS} gapsDeep=${gaps.durations.deep / MINUTE_MS} stageable=${gaps.minutes.count { it.canStage }} coverage=${gaps.sensorCoverageRatio} blockers=${gaps.minutes[30].entryDecision.reasons}")
        assertTrue(control.durations.deep > 0)
        assertEquals(120, gaps.minutes.count { it.canStage })
        assertTrue("All 120 minutes pass the v7 quality allowance but none can enter Deep", gaps.durations.deep > 0)
        assertEquals(control.intervals, gaps.intervals)
    }

    @Test fun tenHzQualityBudgetStillRejectsLongAndCumulativeLoss() {
        for ((gap, missing) in listOf(600L to 600L, 200L to 1_200L)) {
            val input = rows(120).map { row ->
                row.copy(coveredMillis = MINUTE_MS - missing,
                    squaredDeltaTime = row.rms * row.rms * (MINUTE_MS - missing),
                    longestGapMillis = gap)
            }
            val result = analyze(input)
            assertEquals(0L, result.durations.deep)
            assertTrue(result.minutes.all { !it.canStage &&
                SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE in it.currentEligibilityReasons })
        }
    }

    @Test fun relativeQuietExportMatchesTheFeatureRoute() {
        val tenHz = requireNotNull(analyze(rows(120)).baseline)
        assertNull(tenHz.relativeQuietThreshold)
        assertNull(tenHz.relativeQuietMethod)
        val legacy = requireNotNull(analyze(rows(120).map {
            it.copy(featureVersion = MotionAccumulator.ONE_HZ_FEATURE_VERSION, sampleCount = 60)
        }).baseline)
        assertEquals(legacy.p70, requireNotNull(legacy.relativeQuietThreshold), 0.0)
        assertEquals("NIGHTLY_P70_PROVISIONAL_CONFIRMED", legacy.relativeQuietMethod)
    }

    @Test fun rawEventsThroughAutoAndStagingTolerateBoundedSparseLoss() {
        fun capture(streamed: Boolean): List<MotionMinute> {
            val engine = MotionAccumulator(SamplingPlan.choose(10_000), Placement.AUTO, 42)
            val output = mutableListOf<MotionMinute>()
            for (offset in 0L..180 * MINUTE_MS + 200 step 44L) {
                val minute = (offset / MINUTE_MS).toInt()
                val withinMinute = offset % MINUTE_MS
                if (minute % 5 == 2 && withinMinute in 10_000L..10_200L) continue
                val amplitude = if (minute < 120) .015 else .10
                val turn = if (minute in setOf(5, 15, 25, 60, 95, 130, 165) &&
                    withinMinute in 30_000L..32_000L) {
                    .35 * sin((withinMinute - 30_000) * Math.PI / 2_000.0)
                } else 0.0
                val time = base + 90 + offset
                if (engine.add(time, amplitude * sin(offset / 3_000.0) + turn, 0.0, 9.81) && streamed) {
                    output += engine.drain(time)
                }
            }
            output += engine.drain(base + 180 * MINUTE_MS)
            return output
        }
        val streamed = capture(true)
        assertEquals(capture(false), streamed)
        assertEquals(180, streamed.size)
        assertTrue(streamed.drop(1).all { it.sampleCount in 595..602 && it.coveredMillis >= 59_000 })
        val resolved = AutomaticPlacement.resolve(streamed, emptyList(), schedule)
        val result = analyze(resolved)
        println("RAW_V7 coverage=${result.sensorCoverageRatio} bed=${resolved.count { it.placement == Placement.BED }} baseline=${result.baselineSampleCount} deepMs=${result.durations.deep}")
        assertTrue(resolved.any { it.coupling?.state == CouplingState.SUPPORTED })
        assertTrue(result.baselineSampleCount >= SleepStageEstimator.MINIMUM_BASELINE_MINUTES)
        assertTrue(result.durations.deep > 0)
        assertTrue(result.intervals.filter { it.stage == SleepStage.DEEP }
            .all { it.endMillis - it.startMillis >= 10 * MINUTE_MS })
        assertTrue(result.minutes.filter { it.finalStage == SleepStage.DEEP }.all { it.canStage })
        assertTrue(result.minutes.none { it.wasBackfilled })
    }

    @Test fun fullWindowDetailAndExportContextPreservesRenewedCoupling() {
        val source = rows(180).mapIndexed { index, row ->
            val moving = index in setOf(5, 15, 25, 60, 95, 130, 165)
            row.copy(placement = Placement.AUTO, activeMillis = if (moving) 1_000 else 0,
                squaredDeltaTime = if (moving) .2 * .2 * MINUTE_MS else row.squaredDeltaTime,
                maxDelta = if (moving) .3 else row.maxDelta,
                movementEvents = if (moving) 1 else 0, longestActiveMillis = if (moving) 1_000 else 0)
        }
        val full = AutomaticPlacement.resolve(source, emptyList(), schedule)
        val contextStart = reconciliationEvidenceStart(base + 120 * MINUTE_MS,
            listOf(base + 120 * MINUTE_MS), schedule, base + 180 * MINUTE_MS)
        val replayed = AutomaticPlacement.resolve(source.filter { it.startMillis >= contextStart }, emptyList(), schedule)
        val fullResult = analyze(full, 120, 180)
        val detailResult = analyze(replayed, 120, 180)
        assertTrue(contextStart <= base)
        assertEquals(fullResult.intervals, detailResult.intervals)
        assertEquals(60, stagingAvailability(fullResult).couplingSupportedMinutes)
        assertEquals(stagingAvailability(fullResult).state, stagingAvailability(detailResult).state)
    }

    @Test fun streamingDrainNeverPublishesAnUnfinishedNormalizedMinute() {
        fun capture(realtime: Boolean, skipAtBoundary: Boolean): List<MotionMinute> {
            val engine = MotionAccumulator(SamplingPlan.choose(1_000), Placement.AUTO)
            val saved = mutableListOf<MotionMinute>()
            for (step in 0..1_201) {
                if (skipAtBoundary && step == 600) continue
                val millis = base + 90L + step * 100L + if (step == 0) 0L else 20L
                if (engine.add(millis, 0.0, 0.0, 9.81) && realtime) saved += engine.drain(millis)
            }
            saved += engine.drain(base + 120_210)
            return saved
        }
        for (skipAtBoundary in listOf(false, true)) {
            val streamed = capture(true, skipAtBoundary)
            assertEquals(capture(false, skipAtBoundary), streamed)
            assertEquals(2, streamed.size)
            assertTrue(streamed.all { it.movementEvents != null && it.quietTailMillis != null })
        }
    }
}
