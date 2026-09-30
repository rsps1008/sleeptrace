package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MotionEngineTest {
    private val start = LocalDate.of(2026, 9, 27).atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val schedule = SleepSchedule(23 * 60, 7 * 60)
    private val window = requireNotNull(schedule.windowAt(start))
    private val end = window.end
    private fun minute(index: Int, level: MotionLevel = MotionLevel.QUIET, placement: Placement = Placement.BED) = MotionMinute(
        start + index * MINUTE_MS, if (level == MotionLevel.UNKNOWN) 10_000 else MINUTE_MS,
        if (level == MotionLevel.ACTIVE) MINUTE_MS else 0, if (level == MotionLevel.ACTIVE) 60_000.0 else 0.0, 300, placement
    )

    @Test fun `sampling stays at one hertz and batches to FIFO capacity`() {
        assertEquals(SamplingPlan(1_000_000, 800_000_000), SamplingPlan.choose(1000))
        assertEquals(SamplingPlan(1_000_000, 16_000_000), SamplingPlan.choose(20))
        assertEquals(SamplingPlan(1_000_000, 0), SamplingPlan.choose(0))
        assertEquals(1_500_000, SamplingPlan.choose(100, 1_500_000).periodUs)
        assertEquals(Int.MAX_VALUE, SamplingPlan.choose(10_000).latencyUs)
    }

    @Test fun `recent classification from the lead-in can start capture at window start`() {
        val window = requireNotNull(schedule.windowAt(start))
        assertTrue(SleepClassificationTrigger.shouldStart(listOf(ClassificationSample(start + MINUTE_MS, 80, 0, 0)), window, start + 2 * MINUTE_MS))
        assertTrue(SleepClassificationTrigger.shouldStart(listOf(ClassificationSample(start - 10 * MINUTE_MS, 80, 0, 0)), window, start + MINUTE_MS))
        assertFalse(SleepClassificationTrigger.shouldStart(listOf(ClassificationSample(start + MINUTE_MS, 79, 0, 0)), window, start + 2 * MINUTE_MS))
        assertFalse(SleepClassificationTrigger.shouldStart(listOf(ClassificationSample(start + MINUTE_MS, 100, 0, 0)), window, start + 22 * MINUTE_MS))
        assertFalse(SleepClassificationTrigger.shouldStart(listOf(ClassificationSample(start - SleepClassificationTrigger.CLASSIFICATION_LEAD_MILLIS - 1, 100, 0, 0)), window, start + MINUTE_MS))
    }

    @Test fun `long screen off starts low frequency fallback after Google delay`() {
        val window = requireNotNull(schedule.windowAt(start))
        val delayed = window.start + SleepClassificationTrigger.FALLBACK_DELAY_MILLIS

        assertTrue(SleepClassificationTrigger.shouldFallback(window, delayed, window.start))
        assertFalse(SleepClassificationTrigger.shouldFallback(window, delayed - 1, window.start))
        assertFalse(SleepClassificationTrigger.shouldFallback(window, delayed, delayed - MINUTE_MS))
        assertFalse(SleepClassificationTrigger.shouldFallback(window, delayed, null))
    }

    @Test fun `a delayed batch keeps sample time and complete coverage`() {
        val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.BED)
        for (offset in 0L..120_000L step 1_000) engine.add(start + offset, 0.0, 0.0, 9.81)
        val rows = engine.drain(start + 120_000)
        assertEquals(2, rows.size)
        assertEquals(start, rows.first().startMillis)
        assertTrue(rows.all { it.coveredMillis == MINUTE_MS && it.level == MotionLevel.QUIET })
        assertTrue(engine.drain(start + 120_000).isEmpty())
    }

    @Test fun `rotation is detected even when vector magnitude is constant`() {
        val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.BED)
        for (index in 0..60) engine.add(start + index * 1_000L, if (index % 2 == 0) 9.81 else 0.0, 0.0, if (index % 2 == 0) 0.0 else 9.81)
        assertEquals(MotionLevel.ACTIVE, engine.drain(start + MINUTE_MS).single().level)
    }

    @Test fun `suspend gaps duplicate and out of order events are not quiet coverage`() {
        val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.BED)
        for (offset in 0L..10_000L step 1_000) engine.add(start + offset, 0.0, 0.0, 9.81)
        engine.add(start, 500.0, 0.0, 0.0)
        engine.add(start + 10_000, 500.0, 0.0, 0.0)
        engine.add(start + 60_000, 0.0, 0.0, 9.81)
        val row = engine.drain(start + MINUTE_MS).single()
        assertEquals(10_000L, row.coveredMillis)
        assertEquals(MotionLevel.UNKNOWN, row.level)
    }

    @Test fun `non finite events do not contaminate motion`() {
        val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.BED)
        engine.add(start, Double.NaN, 0.0, 0.0)
        assertTrue(engine.drain(Long.MAX_VALUE, true).isEmpty())
    }

    @Test fun `bedside and missing data never generate sleep`() {
        assertTrue(MotionSleepEstimator.estimate((0..119).map { minute(it, placement = Placement.BEDSIDE) }, emptyList(), schedule, end).isEmpty())
        assertTrue(MotionSleepEstimator.estimate((0..119).map { minute(it, MotionLevel.UNKNOWN) }, emptyList(), schedule, end).isEmpty())
    }

    @Test fun `only current feature quiet minutes can create motion sleep candidates`() {
        val v3Quiet = (0..119).map { minute(it).copy(featureVersion = MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION) }
        val v2Quiet = (0..119).map { minute(it).copy(featureVersion = MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION) }
        val v1Quiet = (0..119).map { minute(it).copy(featureVersion = MotionAccumulator.LEGACY_CALLBACK_FEATURE_VERSION) }

        assertTrue(MotionSleepEstimator.estimate(v3Quiet, emptyList(), schedule, end).isEmpty())
        assertTrue(MotionSleepEstimator.estimate(v2Quiet, emptyList(), schedule, end).isEmpty())
        assertTrue(MotionSleepEstimator.estimate(v1Quiet, emptyList(), schedule, end).isEmpty())
    }

    @Test fun `incompatible quiet minutes cannot bridge current feature runs`() {
        val rows = (0..59).map { index ->
            minute(index).copy(
                featureVersion = if (index in 20..39) MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION
                else MotionAccumulator.CURRENT_FEATURE_VERSION
            )
        }

        assertTrue(MotionSleepEstimator.estimate(rows, emptyList(), schedule, end).isEmpty())
    }

    @Test fun `motion alone syncs automatically after the completed window`() {
        val rows = (0..119).map { minute(it) }
        assertTrue(MotionSleepEstimator.estimate(rows, emptyList(), schedule, end - 1).isEmpty())
        val session = MotionSleepEstimator.estimate(rows, emptyList(), schedule, end).single()
        assertEquals(SyncState.PENDING, session.state)
        assertEquals(start, session.startMillis)
        assertEquals(start + 120 * MINUTE_MS, session.endMillis)
        assertEquals(session.id, MotionSleepEstimator.estimate(rows, emptyList(), schedule, end).single().id)
    }

    @Test fun `motion candidates use UsageStats availability from their own night`() {
        val secondWindow = schedule.windowForStartDate(LocalDate.of(2026, 9, 28), ZoneId.systemDefault())
        val rows = (0..119).map { minute(it) } + (0..119).map {
            minute(it).copy(startMillis = secondWindow.startMillis + it * MINUTE_MS)
        }

        val sessions = MotionSleepEstimator.estimate(
            rows, emptyList(), schedule, secondWindow.endMillis,
            usageAvailable = { it.startMillis == window.startMillis }
        )

        assertEquals(2, sessions.size)
        assertFalse(sessions.first { it.startMillis == window.startMillis }.reason.contains("無法排除手機使用"))
        assertTrue(sessions.first { it.startMillis == secondWindow.startMillis }.reason.contains("無法排除手機使用"))
    }

    @Test fun `active phone time breaks sleep candidate even with quiet accelerometer`() {
        val session = MotionSleepEstimator.estimate((0..119).map { minute(it) }, listOf(UsageInterval(start, start + 45 * MINUTE_MS)), schedule, end).single()
        assertEquals(start + 45 * MINUTE_MS, session.startMillis)
        assertEquals(75 * MINUTE_MS, session.durationMillis)
    }

    @Test fun `missing minute cannot bridge two short quiet runs`() {
        assertTrue(MotionSleepEstimator.estimate((0..59).filter { it != 29 && it != 30 }.map { minute(it) }, emptyList(), schedule, end).isEmpty())
    }

    @Test fun `brief movement remains inside candidate but sustained motion closes it`() {
        val rows = (0..99).map { minute(it, if (it in 30..31 || it in 65..99) MotionLevel.ACTIVE else MotionLevel.QUIET) }
        val session = MotionSleepEstimator.estimate(rows, emptyList(), schedule, end).single()
        assertEquals(start, session.startMillis)
        assertEquals(start + 65 * MINUTE_MS, session.endMillis)
    }

    @Test fun `two qualified quiet runs are both retained as segmented sleep`() {
        val rows = (0..124).map { minute(it, if (it in 60..64) MotionLevel.ACTIVE else MotionLevel.QUIET) }
        val sessions = MotionSleepEstimator.estimate(rows, emptyList(), schedule, end)

        assertEquals(2, sessions.size)
        assertEquals(start, sessions[0].startMillis)
        assertEquals(start + 60 * MINUTE_MS, sessions[0].endMillis)
        assertEquals(start + 65 * MINUTE_MS, sessions[1].startMillis)
        assertEquals(start + 125 * MINUTE_MS, sessions[1].endMillis)
    }

    @Test fun `overnight window belongs to preceding date and respects boundaries`() {
        assertEquals(schedule.windowAt(start), schedule.windowAt(start + 7 * 60 * MINUTE_MS))
        assertEquals(start + 8 * 60 * MINUTE_MS, end)
        val outside = (0..119).map { minute(it).copy(startMillis = end + it * MINUTE_MS) }
        assertTrue(MotionSleepEstimator.estimate(outside, emptyList(), schedule, end + 24 * 60 * MINUTE_MS).isEmpty())
    }

    @Test fun `reconcile preserves synced ids and history without duplicate writes`() {
        val session = MotionSleepEstimator.estimate((0..119).map { minute(it) }, emptyList(), schedule, end).single().copy(state = SyncState.SYNCED)
        val history = session.copy(id = "old", startMillis = start - 30L * 24 * 60 * MINUTE_MS, endMillis = end - 30L * 24 * 60 * MINUTE_MS)
        val candidate = session.copy(id = "new-api-id", confidence = 90, state = SyncState.PENDING)
        val merged = mergeSleepSessions(listOf(history, session), listOf(candidate))
        assertEquals(2, merged.size)
        assertEquals(session, merged.first())
        assertTrue(merged.contains(history))
    }

    @Test fun `motion conflict lowers score without requiring confirmation`() {
        val session = MotionSleepEstimator.estimate((0..119).map { minute(it) }, emptyList(), schedule, end).single()
        val conflict = MotionSleepEstimator.annotate(session, (0..119).map { minute(it, MotionLevel.ACTIVE) })
        assertEquals(SyncState.PENDING, conflict.state)
        assertTrue(conflict.confidence < session.confidence)
        assertEquals(SyncState.PENDING, MotionSleepEstimator.annotate(session, (0..119).map { minute(it) }).state)
        assertEquals(session, MotionSleepEstimator.annotate(session, (0..119).map { minute(it, MotionLevel.ACTIVE, Placement.BEDSIDE) }))
    }

    @Test fun `v3 active minutes remain conflict evidence while v3 quiet is ignored`() {
        val session = MotionSleepEstimator.estimate((0..119).map { minute(it) }, emptyList(), schedule, end).single()
        val v3Active = (0..119).map {
            minute(it, MotionLevel.ACTIVE).copy(featureVersion = MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION)
        }
        val v3Quiet = (0..119).map {
            minute(it).copy(featureVersion = MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION)
        }

        assertTrue(MotionSleepEstimator.annotate(session, v3Active).confidence < session.confidence)
        assertEquals(session, MotionSleepEstimator.annotate(session, v3Quiet))
    }
}
