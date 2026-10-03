package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.CaptureDiagnostics
import com.rsps1008.sleeptrace.motion.CouplingEvidence
import com.rsps1008.sleeptrace.motion.CouplingState
import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionAccumulator
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.Placement
import com.rsps1008.sleeptrace.motion.homePresentation
import com.rsps1008.sleeptrace.sleep.SleepStage
import com.rsps1008.sleeptrace.sleep.SleepStageEstimator
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.StagingAvailability
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.stagingAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiagnosticPresentationTest {
    @Test fun `capture summary separates policy request raw events and feature cadence`() {
        val capture = CaptureDiagnostics(
            id = 1,
            windowStart = 2,
            registeredAt = Instant.parse("2026-10-01T14:30:00Z").toEpochMilli(),
            trigger = "TEST",
            targetPeriodUs = 100_000,
            periodUs = 100_000,
            latencyUs = 35_400_000,
            sensorMinDelayUs = 5_000,
            sensorMaxDelayUs = 44_000,
            fifoReservedEventCount = 0,
            fifoMaxEventCount = 10_000,
            wakeUp = false,
            firstEvent = Instant.parse("2026-10-01T14:45:00Z").toEpochMilli(),
            rawEvents = 490_746,
            meanIntervalMillis = 44.352778,
            maxIntervalMillis = 5_000
        )

        assertEquals(22.55, capture.observedRawHertz!!, 0.01)
        val presentation = capture.homePresentation(ZoneId.of("Asia/Taipei"))
        assertEquals("2026-10-01 22:30", presentation.started.value)
        assertEquals("10.00 Hz", presentation.requestedRate.value)
        assertNull(presentation.registeredRate)
        assertEquals("約 22.55 Hz", presentation.observedRate.value)
        assertEquals("10.00 Hz", presentation.featureCeiling.value)
        assertEquals("最大 10000 筆\n此感測器保留：0 筆", presentation.fifoCapacity.value)
        // Persisted latency is authoritative; do not reconstruct it from current policy/capabilities.
        assertEquals("35.4 秒", presentation.batchWait.value)
        val summary = capture.homeRateSummary()
        assertEquals(capture.homePresentation().summary(), summary)
        assertTrue(summary.contains("App 要求頻率：10.00 Hz"))
        assertTrue(summary.contains("原始事件實測：約 22.55 Hz"))
        assertTrue(summary.contains("特徵正規化上限：10.00 Hz"))
        assertTrue(!summary.contains("分鐘特徵約"))
        assertTrue(summary.contains("不代表 CPU 喚醒頻率或耗電"))
    }

    @Test fun `capture summary reports one Hz feature fallback when sensor minimum delay is too slow`() {
        val capture = CaptureDiagnostics(
            id = 2,
            windowStart = 2,
            registeredAt = 3,
            trigger = "TEST_SLOW_SENSOR",
            targetPeriodUs = 100_000,
            periodUs = 1_000_000,
            latencyUs = 80_000_000,
            sensorMinDelayUs = 1_000_000,
            sensorMaxDelayUs = 0,
            fifoReservedEventCount = 100,
            fifoMaxEventCount = 100,
            wakeUp = false,
            rawEvents = 120,
            meanIntervalMillis = 1_000.0
        )

        assertEquals(10.0, capture.targetHertz, 0.0)
        assertEquals(1.0, capture.registeredHertz, 0.0)
        assertEquals(1.0, capture.featureHertz, 0.0)
        val presentation = capture.homePresentation()
        assertEquals("10.00 Hz", presentation.requestedRate.value)
        assertEquals("1.00 Hz", presentation.registeredRate?.value)
        assertEquals("1.00 Hz", presentation.featureCeiling.value)
        val slower = capture.copy(periodUs = 2_000_000).homePresentation()
        assertEquals("0.50 Hz", slower.registeredRate?.value)
        assertEquals("0.50 Hz", slower.featureCeiling.value)
    }

    @Test fun `slower callbacks show measured raw rate while ten Hz remains only the normalization ceiling`() {
        listOf(110.0 to "9.09", 120.0 to "8.33").forEach { (intervalMillis, expectedHertz) ->
            val capture = CaptureDiagnostics(
                id = intervalMillis.toLong(),
                windowStart = 2,
                registeredAt = 3,
                trigger = "TEST_SLOW_CALLBACK",
                targetPeriodUs = 100_000,
                periodUs = 100_000,
                latencyUs = 0,
                sensorMinDelayUs = 0,
                sensorMaxDelayUs = 0,
                fifoReservedEventCount = 0,
                fifoMaxEventCount = 0,
                wakeUp = false,
                rawEvents = 500,
                meanIntervalMillis = intervalMillis
            )

            val presentation = capture.homePresentation()
            assertEquals("約 $expectedHertz Hz", presentation.observedRate.value)
            assertEquals("10.00 Hz", presentation.featureCeiling.value)
            assertNull(presentation.registeredRate)
        }
    }

    @Test fun `missing capture does not invent rates hardware capacity or a zero batch request`() {
        val presentation = (null as CaptureDiagnostics?).homePresentation()
        listOf(
            presentation.started, presentation.requestedRate, presentation.featureCeiling,
            presentation.fifoCapacity, presentation.batchWait, presentation.wakeUp
        ).forEach { assertEquals("尚無採集紀錄", it.value) }
        assertEquals("尚無量測", presentation.observedRate.value)
        assertNull(presentation.registeredRate)
    }

    @Test fun `legacy missing reservation differs from zero reservation and zero total capacity`() {
        val old = presentationCapture().copy(
            targetPeriodUs = 1_000_000, periodUs = 1_000_000,
            sensorMinDelayUs = null, sensorMaxDelayUs = null, fifoReservedEventCount = null
        )
        val legacy = old.homePresentation()
        assertEquals("最大 1000 筆\n此感測器保留：舊紀錄未保存", legacy.fifoCapacity.value)
        assertEquals("1.00 Hz", legacy.requestedRate.value)
        assertEquals("1.00 Hz", legacy.featureCeiling.value)
        assertEquals("0.8 秒", legacy.batchWait.value)
        assertTrue(legacy.batchWait.notes.any { it.contains("舊紀錄缺少") })
        assertTrue(presentationCapture().homePresentation().batchWait.notes.none { it.contains("舊紀錄缺少") })
        val zeroReservation = old.copy(fifoReservedEventCount = 0, latencyUs = 0).homePresentation()
        assertEquals("最大 1000 筆\n此感測器保留：0 筆", zeroReservation.fifoCapacity.value)
        assertEquals("未要求批次等待", zeroReservation.batchWait.value)
        assertTrue(zeroReservation.fifoCapacity.notes.none { it.contains("未提供硬體 FIFO") })
        val noFifo = old.copy(fifoReservedEventCount = 0, fifoMaxEventCount = 0).homePresentation()
        assertEquals("最大 0 筆\n此感測器保留：0 筆", noFifo.fifoCapacity.value)
        assertTrue(noFifo.fifoCapacity.notes.any { it.contains("未提供硬體 FIFO") })
    }

    @Test fun `saved batch latency preserves fractional seconds through Android Int limit`() {
        val capture = presentationCapture()
        listOf(
            0 to "未要求批次等待",
            1 to "0.000001 秒",
            800_000 to "0.8 秒",
            35_400_000 to "35.4 秒",
            Int.MAX_VALUE to "2147.483647 秒"
        ).forEach { (latency, expected) ->
            assertEquals(expected, capture.copy(latencyUs = latency).homePresentation().batchWait.value)
        }
    }

    @Test fun `missing insufficient or nonfinite raw measurements never display a measured rate`() {
        val capture = presentationCapture()
        val missingMeasurements = listOf(
            capture.copy(rawEvents = 0, meanIntervalMillis = 100.0),
            capture.copy(rawEvents = 1, meanIntervalMillis = 100.0),
            capture.copy(rawEvents = 2, meanIntervalMillis = null),
            capture.copy(rawEvents = 2, meanIntervalMillis = 0.0),
            capture.copy(rawEvents = 2, meanIntervalMillis = -1.0),
            capture.copy(rawEvents = 2, meanIntervalMillis = Double.NaN),
            capture.copy(rawEvents = 2, meanIntervalMillis = Double.POSITIVE_INFINITY)
        )
        missingMeasurements.forEach {
            assertNull(it.observedRawHertz)
            assertEquals("尚無量測", it.homePresentation().observedRate.value)
        }
        assertEquals("約 10.00 Hz", capture.copy(rawEvents = 2, meanIntervalMillis = 100.0).homePresentation().observedRate.value)
    }

    private fun presentationCapture() = CaptureDiagnostics(
        id = 1, windowStart = 2, registeredAt = 3, trigger = "TEST",
        targetPeriodUs = 100_000, periodUs = 100_000, latencyUs = 800_000,
        sensorMinDelayUs = 5_000, sensorMaxDelayUs = 100_000,
        fifoReservedEventCount = 20, fifoMaxEventCount = 1000, wakeUp = true
    )

    @Test fun `availability message distinguishes valid data from missing coupling`() {
        val motion = MotionMinute(
            startMillis = 0,
            coveredMillis = MINUTE_MS,
            activeMillis = 0,
            squaredDeltaTime = .01 * .01 * MINUTE_MS,
            sampleCount = 60,
            placement = Placement.UNKNOWN,
            featureVersion = MotionAccumulator.CURRENT_FEATURE_VERSION,
            maxDelta = .02,
            movementEvents = 0,
            longestActiveMillis = 0,
            quietTailMillis = MINUTE_MS,
            longestGapMillis = 0,
            postureDelta = .01,
            recordingId = 1,
            coupling = CouplingEvidence(CouplingState.INSUFFICIENT, null, "COUPLING_INSUFFICIENT")
        )
        val minute = SleepStageEstimator.MinuteDiagnostic(
            startMillis = 0,
            placement = Placement.UNKNOWN,
            level = motion.level,
            rollingMedianRms = null,
            stage = SleepStage.LIGHT,
            event = null,
            stagingMotionUsable = false,
            stagingMotionExclusionReason = "COUPLING_INSUFFICIENT",
            motion = motion,
            currentEligibilityReasons = listOf(SleepStageEstimator.Reason.COUPLING_INSUFFICIENT)
        )
        val result = SleepStageEstimator.StagingResult(
            intervals = emptyList(),
            baseline = null,
            motionCoverageRatio = 0.94,
            firstMotionDelayMillis = 0,
            minutes = listOf(minute),
            currentFeatureValidMinutes = 346
        )

        val availability = stagingAvailability(result)
        assertEquals(StagingAvailability.State.COUPLING_NOT_ESTABLISHED, availability.state)
        assertTrue(availability.message().contains("346 分鐘通過基本資料檢查"))
        assertTrue(availability.message().contains("床面動作支持"))
    }

    @Test fun `actual legacy rows report incompatible features without claiming zero minutes`() {
        val durationMinutes = 60
        val motions = (0 until durationMinutes).map { index ->
            MotionMinute(
                startMillis = index * MINUTE_MS,
                coveredMillis = MINUTE_MS,
                activeMillis = 0,
                squaredDeltaTime = .01 * .01 * MINUTE_MS,
                sampleCount = 60,
                placement = Placement.BED,
                featureVersion = MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION,
                recordingId = 1
            )
        }
        val session = SleepSession(
            id = "legacy-night",
            startMillis = 0,
            endMillis = durationMinutes * MINUTE_MS,
            confidence = 80,
            awakeMillis = 0,
            state = SyncState.PENDING,
            reason = "test"
        )
        val result = SleepStageEstimator.analyze(
            session = session,
            motionMinutes = motions,
            classifications = emptyList(),
            usageIntervals = emptyList(),
            sleepSegments = listOf(SleepSegment(0, session.endMillis, 80)),
            schedule = SleepSchedule(0, 0)
        )

        assertEquals(0, result.currentFeatureValidMinutes)
        val availability = stagingAvailability(result)
        assertEquals(StagingAvailability.State.FEATURE_INCOMPATIBLE, availability.state)
        assertTrue(availability.message().contains("已有動作摘要"))
        assertTrue(availability.message().contains("特徵欄位或版本不相容 60 個分鐘列"))
        assertTrue(!availability.message().contains("已有 0 分鐘"))
    }

    @Test fun `stageable minute always reports available even with hypothetical entry blockers`() {
        val motion = MotionMinute(
            startMillis = 0,
            coveredMillis = MINUTE_MS,
            activeMillis = 0,
            squaredDeltaTime = .01 * .01 * MINUTE_MS,
            sampleCount = 60,
            placement = Placement.BED,
            featureVersion = MotionAccumulator.CURRENT_FEATURE_VERSION,
            maxDelta = .02,
            movementEvents = 0,
            longestActiveMillis = 0,
            quietTailMillis = MINUTE_MS,
            longestGapMillis = 0,
            postureDelta = .01,
            recordingId = 1
        )
        val minute = SleepStageEstimator.MinuteDiagnostic(
            startMillis = 0,
            placement = Placement.BED,
            level = motion.level,
            rollingMedianRms = .01,
            stage = SleepStage.LIGHT,
            event = null,
            stagingMotionUsable = true,
            stagingMotionExclusionReason = null,
            motion = motion,
            canStage = true,
            currentEligibilityReasons = emptyList(),
            windowBlockingReasons = listOf(SleepStageEstimator.Reason.RECORDING_BOUNDARY)
        )
        val result = SleepStageEstimator.StagingResult(
            intervals = emptyList(),
            baseline = SleepStageEstimator.NightlyBaseline(.01, .011, .012, .013, .014, .015, 10, false),
            motionCoverageRatio = 1.0,
            firstMotionDelayMillis = 0,
            minutes = listOf(minute),
            currentFeatureValidMinutes = 1
        )

        val availability = stagingAvailability(result)
        assertEquals(1, availability.stageableMinutes)
        assertEquals(StagingAvailability.State.AVAILABLE, availability.state)
    }
}
