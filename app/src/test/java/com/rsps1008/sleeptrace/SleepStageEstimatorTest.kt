package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionLevel
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.MotionAccumulator
import com.rsps1008.sleeptrace.motion.CouplingEvidence
import com.rsps1008.sleeptrace.motion.CouplingState
import com.rsps1008.sleeptrace.motion.MotionSleepEstimator
import com.rsps1008.sleeptrace.motion.Placement
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepStage
import com.rsps1008.sleeptrace.sleep.SleepStageEstimator
import com.rsps1008.sleeptrace.sleep.SleepStageInterval
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.confirmMotionCandidateOnset
import com.rsps1008.sleeptrace.sleep.extendConfirmedMotionCandidate
import com.rsps1008.sleeptrace.sleep.mergeSleepSessions
import com.rsps1008.sleeptrace.sleep.sleepParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepStageEstimatorTest {
    private val schedule = SleepSchedule(0, 0)
    private val base = 20_000L * MINUTE_MS

    @Test fun `tablet use and pre-use still phone cannot establish or backfill Deep`() {
        val phoneUse = UsageInterval(base + 60 * MINUTE_MS, base + 65 * MINUTE_MS)
        val apiStart = base + 80 * MINUTE_MS
        val samples = listOf(ClassificationSample(apiStart, 92, 0, 0))
        val segment = SleepSegment(apiStart, base + 240 * MINUTE_MS, 100)
        val rawCandidates = MotionSleepEstimator.estimate(
            motion(base, 240), listOf(phoneUse), schedule, base + 48 * 60 * MINUTE_MS
        )

        assertTrue(rawCandidates.isNotEmpty())
        assertNull(rawCandidates.firstOrNull { it.endMillis <= phoneUse.startMillis }
            ?.let { confirmMotionCandidateOnset(it, emptyList(), emptyList()) })
        val confirmed = rawCandidates.mapNotNull { confirmMotionCandidateOnset(it, listOf(segment), samples) }
        assertEquals(1, confirmed.size)
        assertEquals(apiStart, confirmed.single().startMillis)

        val staged = estimate(
            confirmed.single(), motion(base, 240), samples, listOf(phoneUse), listOf(segment)
        )
        assertEquals(apiStart, staged.first().startMillis)
        assertEquals(SleepStage.LIGHT, staged.first().stage)
        assertTrue(staged.none { it.stage == SleepStage.DEEP && it.startMillis < apiStart })
        assertTrue(staged.any { it.stage == SleepStage.DEEP && it.startMillis >= apiStart + 15 * MINUTE_MS })
    }

    @Test fun `normal onset is Light through phone-use guard and stability window`() {
        val start = base
        val lastUse = UsageInterval(start - 10 * MINUTE_MS, start - 9 * MINUTE_MS)
        val session = session(start, 35)
        val segment = SleepSegment(start, session.endMillis, 95)
        val stages = estimate(session, motion(start, 35), emptyList(), listOf(lastUse), listOf(segment))

        assertEquals(SleepStage.LIGHT, stageAt(stages, start))
        assertEquals(SleepStage.LIGHT, stageAt(stages, start + 15 * MINUTE_MS))
        assertTrue(stages.any { it.stage == SleepStage.DEEP && it.startMillis >= start + 15 * MINUTE_MS })
    }

    @Test fun `classification before coupling can confirm a later motion candidate`() {
        val candidate = SleepSession(
            id = "motion", startMillis = base + 79 * MINUTE_MS,
            endMillis = base + 125 * MINUTE_MS, confidence = 50,
            awakeMillis = 0, state = SyncState.PENDING, reason = "motion"
        )

        val confirmed = confirmMotionCandidateOnset(
            candidate, emptyList(), listOf(ClassificationSample(base, 92, 0, 0))
        )

        assertEquals(candidate.startMillis, confirmed?.startMillis)
    }

    @Test fun `newer non-sleep classification cancels preceding sleep evidence`() {
        val candidate = SleepSession(
            id = "motion", startMillis = base + 79 * MINUTE_MS,
            endMillis = base + 125 * MINUTE_MS, confidence = 50,
            awakeMillis = 0, state = SyncState.PENDING, reason = "motion"
        )

        val confirmed = confirmMotionCandidateOnset(candidate, emptyList(), listOf(
            ClassificationSample(base, 92, 0, 0),
            ClassificationSample(base + 70 * MINUTE_MS, 10, 0, 0)
        ))

        assertNull(confirmed)
    }

    @Test fun `stale classification cannot confirm a much later motion candidate`() {
        val candidate = SleepSession(
            id = "motion", startMillis = base + 121 * MINUTE_MS,
            endMillis = base + 167 * MINUTE_MS, confidence = 50,
            awakeMillis = 0, state = SyncState.PENDING, reason = "motion"
        )

        assertNull(confirmMotionCandidateOnset(
            candidate, emptyList(), listOf(ClassificationSample(base, 92, 0, 0))
        ))
    }

    @Test fun `confirmed candidate bridges temporary unknown coupling and closes on dense wake`() {
        fun row(minute: Int, active: Boolean = false) = MotionMinute(
            startMillis = base + minute * MINUTE_MS, coveredMillis = 60_000,
            activeMillis = if (active) 40_000 else 0, squaredDeltaTime = if (active) 3_000.0 else 0.0,
            sampleCount = 600, placement = if (minute in 3..5) Placement.BED else Placement.UNKNOWN,
            recordingId = 7
        )
        val candidate = SleepSession(
            id = "motion", startMillis = base + 3 * MINUTE_MS, endMillis = base + 6 * MINUTE_MS,
            confidence = 50, awakeMillis = 0, state = SyncState.PENDING, reason = "motion"
        )
        val minutes = (0..12).map { row(it, active = it >= 10) }

        val expanded = extendConfirmedMotionCandidate(
            candidate, minutes, listOf(ClassificationSample(base + 30_000, 92, 0, 0)), emptyList()
        )

        assertEquals(base + 30_000, expanded.startMillis)
        assertEquals(base + 10 * MINUTE_MS, expanded.endMillis)
    }

    @Test fun `one or two active minutes do not fragment an established Deep run`() {
        val session = session(base, 70)
        val rows = motion(base, 70).map { row ->
            if (row.startMillis == base + 35 * MINUTE_MS || row.startMillis == base + 36 * MINUTE_MS) active(row)
            else row
        }
        val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))

        assertEquals(1, stages.count { it.stage == SleepStage.DEEP })
        val deep = stages.single { it.stage == SleepStage.DEEP }
        assertTrue(deep.startMillis <= base + 35 * MINUTE_MS)
        assertTrue(deep.endMillis >= base + 37 * MINUTE_MS)
    }

    @Test fun `Deep uses night-relative motion thresholds`() {
        val session = session(base, 60)
        val rows = (0 until 60).map { index ->
            val rms = (index + 1) * 0.001
            MotionMinute(base + index * MINUTE_MS, 60_000, 0, rms * rms * 60_000, 60, Placement.BED, featureVersion = 4)
        }
        val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))

        assertEquals(SleepStage.DEEP, stageAt(stages, base + 20 * MINUTE_MS))
        assertEquals(SleepStage.LIGHT, stageAt(stages, base + 50 * MINUTE_MS))
    }

    @Test fun `five active minutes end Deep and require a new stable window`() {
        val session = session(base, 80)
        val rows = motion(base, 80).map { row ->
            if (row.startMillis in (base + 35 * MINUTE_MS) until (base + 40 * MINUTE_MS)) active(row)
            else row
        }
        val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))

        assertFalse(stages.any { it.stage == SleepStage.DEEP && it.startMillis < base + 40 * MINUTE_MS && it.endMillis > base + 35 * MINUTE_MS })
        // A newly confirmed window may backfill its last seven stable minutes.
        assertEquals(SleepStage.DEEP, stageAt(stages, base + 55 * MINUTE_MS))
    }

    @Test fun `night phone use is Awake and resets the Deep guard and continuity`() {
        val use = UsageInterval(base + 120 * MINUTE_MS, base + 123 * MINUTE_MS)
        val session = session(base, 190, awake = listOf(use))
        val signal = motion(base, 190).mapIndexed { i, m -> m.copy(squaredDeltaTime = (if (i < 170) .01 else .04).let { it * it * 60_000 }) }
        val stages = estimate(session, signal, emptyList(), listOf(use), listOf(SleepSegment(base, session.endMillis, 95)))
        val parts = sleepParts(session.copy(stageIntervals = stages))

        assertTrue(stages.any { it.stage == SleepStage.AWAKE && it.startMillis == use.startMillis && it.endMillis == use.endMillis })
        assertTrue(parts.any { it.stage == SleepStage.AWAKE && it.start == use.startMillis && it.end == use.endMillis })
        assertFalse(parts.any { it.stage == SleepStage.DEEP && it.start < use.endMillis + 15 * MINUTE_MS && it.end > use.startMillis })
        assertEquals(SleepStage.DEEP, stageAt(stages, use.endMillis + 30 * MINUTE_MS))
    }

    @Test fun `missing motion coverage falls back to Light and is never treated as Deep evidence`() {
        val session = session(base, 70)
        val rows = motion(base, 70).filterNot { it.startMillis == base + 40 * MINUTE_MS }
        val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))

        assertEquals(SleepStage.LIGHT, stageAt(stages, base + 40 * MINUTE_MS))
        assertFalse(stages.any { it.stage == SleepStage.DEEP && it.startMillis < base + 41 * MINUTE_MS && it.endMillis > base + 40 * MINUTE_MS })
    }

    @Test fun `one explicit short v5 gap falls back to Light but later complete minutes may re-enter Deep`() {
        val session = session(base, 80)
        val rows = (0 until 80).map { index ->
            val rms = if (index < 56) 0.010 + (index % 6) * .001 else .040
            MotionMinute(base + index * MINUTE_MS, 60_000, 0, rms * rms * 60_000, 60, Placement.BED,
                featureVersion = MotionAccumulator.STRICT_CADENCE_FEATURE_VERSION, maxDelta = .02, movementEvents = 0,
                longestActiveMillis = 0, quietTailMillis = 60_000, longestGapMillis = if (index == 40) 1_000 else 0,
                postureDelta = .01, recordingId = 7, coupling = CouplingEvidence(CouplingState.HELD, MINUTE_MS, null))
        }
        val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))
        assertEquals(SleepStage.LIGHT, stageAt(stages, base + 40 * MINUTE_MS))
        assertEquals(SleepStage.DEEP, stageAt(stages, base + 55 * MINUTE_MS))
        val result = SleepStageEstimator.analyze(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)), schedule)
        assertTrue(result.minutes[41].windowBlockingReasons.contains(SleepStageEstimator.Reason.WINDOW_CONTAINS_GAP))
    }

    @Test fun `one missing ten Hz slot remains stageable without a false coverage blocker`() {
        val session = session(base, 80)
        val rows = motion(base, 80).mapIndexed { index, minute ->
            val current = minute.copy(
                sampleCount = 600,
                featureVersion = MotionAccumulator.CURRENT_FEATURE_VERSION,
                maxDelta = .04,
                movementEvents = 0,
                longestActiveMillis = 0,
                quietTailMillis = MINUTE_MS,
                longestGapMillis = 0,
                postureDelta = .01,
                recordingId = 9,
                coupling = CouplingEvidence(CouplingState.HELD, MINUTE_MS, null)
            )
            if (index == 40) current.copy(
                coveredMillis = 59_800,
                sampleCount = 598,
                longestGapMillis = 200
            ) else current
        }

        val result = SleepStageEstimator.analyze(
            session, rows, emptyList(), emptyList(),
            listOf(SleepSegment(base, session.endMillis, 95)), schedule
        )
        val minute = result.minutes[40]
        assertTrue(minute.canStage)
        assertFalse(SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE in minute.currentEligibilityReasons)
        assertTrue(SleepStageEstimator.Reason.ALLOWED_MINOR_GAP in minute.nonBlockingReasons)
    }

    @Test fun `unknown placement with no coupling falls back to Light and never becomes Deep`() {
        val session = session(base, 60)
        val rows = motion(base, 60, Placement.UNKNOWN)
        val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))
        assertTrue(stages.all { it.stage == SleepStage.LIGHT })
        val result = SleepStageEstimator.analyze(
            session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)), schedule
        )
        assertTrue(result.minutes.all { !it.canStage })
        assertTrue(result.minutes.all { SleepStageEstimator.Reason.COUPLING_INSUFFICIENT in it.reasons })
    }

    @Test fun `bedside and unknown placement never infer Deep from phone stillness`() {
        listOf(Placement.BEDSIDE, Placement.UNKNOWN).forEach { placement ->
            val session = session(base, 60)
            val rows = motion(base, 60, placement = placement)
            val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))
            assertFalse("$placement", stages.any { it.stage == SleepStage.DEEP })
        }
    }

    @Test fun `Sleep API session without motion falls back to Light without manufacturing Deep`() {
        val session = session(base, 60)
        val stages = estimate(session, emptyList(), emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))

        assertTrue(stages.isNotEmpty())
        assertTrue(stages.all { it.stage == SleepStage.LIGHT })
        assertFalse(stages.any { it.stage == SleepStage.DEEP })
        val result = SleepStageEstimator.analyze(
            session, emptyList(), emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)), schedule
        )
        assertTrue(result.minutes.all { !it.canStage })
        assertTrue(result.minutes.all { SleepStageEstimator.Reason.MISSING_MOTION in it.reasons })
    }

    @Test fun `last meaningful phone use ends at the last interaction interval`() {
        val use = listOf(UsageInterval(base, base + 3 * MINUTE_MS))
        assertEquals(base + 3 * MINUTE_MS, SleepStageEstimator.lastMeaningfulPhoneUseBefore(base + 10 * MINUTE_MS, use))
        assertEquals(base + MINUTE_MS, SleepStageEstimator.lastMeaningfulPhoneUseBefore(base + MINUTE_MS, use))
    }

    @Test fun `stage-only correction advances the Health Connect revision`() {
        val old = session(base, 60).copy(
            id = "stable-id",
            state = SyncState.SYNCED,
            stageIntervals = listOf(SleepStageInterval(base, base + 60 * MINUTE_MS, SleepStage.LIGHT))
        )
        val corrected = old.copy(stageIntervals = listOf(
            SleepStageInterval(base, base + 60 * MINUTE_MS, SleepStage.DEEP)
        ))

        val merged = mergeSleepSessions(listOf(old), listOf(corrected)).single()
        assertEquals("stable-id", merged.id)
        assertEquals(old.revision + 1, merged.revision)
        assertEquals(SyncState.PENDING, merged.state)
        assertEquals(SleepStage.DEEP, merged.stageIntervals.single().stage)
    }

    private fun estimate(
        session: SleepSession,
        rows: List<MotionMinute>,
        samples: List<ClassificationSample>,
        usage: List<UsageInterval>,
        segments: List<SleepSegment>
    ) = SleepStageEstimator.estimate(session, rows, samples, usage, segments, schedule)

    private fun session(start: Long, minutes: Int, awake: List<UsageInterval> = emptyList()) = SleepSession(
        startMillis = start,
        endMillis = start + minutes * MINUTE_MS,
        confidence = 90,
        awakeMillis = awake.sumOf { it.endMillis - it.startMillis },
        state = SyncState.PENDING,
        reason = "測試",
        awakeIntervals = awake,
        usageSnapshotApplied = true
    )

    private fun stageAt(stages: List<com.rsps1008.sleeptrace.sleep.SleepStageInterval>, time: Long) =
        stages.single { time >= it.startMillis && time < it.endMillis }.stage

    private fun motion(
        start: Long,
        count: Int,
        placement: Placement = Placement.BED
    ) = (0 until count).map { index ->
        val time = start + index * MINUTE_MS
        MotionMinute(time, 60_000, 0, (if (index < count * .70) .01 else .04).let { it * it * 60_000 }, 60, placement, featureVersion = 4)
    }

    private fun active(minute: MotionMinute) = minute.copy(
        activeMillis = 6_000,
        squaredDeltaTime = 0.25 * 0.25 * minute.coveredMillis
    )
}
