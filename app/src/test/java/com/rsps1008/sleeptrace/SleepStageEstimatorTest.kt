package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionLevel
import com.rsps1008.sleeptrace.motion.MotionMinute
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

    @Test fun `Deep uses the night relative P35 motion level`() {
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

    @Test fun `missing motion coverage is never treated as quiet`() {
        val session = session(base, 70)
        val rows = motion(base, 70).filterNot { it.startMillis == base + 40 * MINUTE_MS }
        val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))

        assertEquals(SleepStage.SLEEPING, stageAt(stages, base + 40 * MINUTE_MS))
        assertFalse(stages.any { it.stage == SleepStage.DEEP && it.startMillis < base + 41 * MINUTE_MS && it.endMillis > base + 40 * MINUTE_MS })
    }

    @Test fun `bedside and unknown placement never infer Deep from phone stillness`() {
        listOf(Placement.BEDSIDE, Placement.UNKNOWN).forEach { placement ->
            val session = session(base, 60)
            val rows = motion(base, 60, placement = placement)
            val stages = estimate(session, rows, emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))
            assertFalse("$placement", stages.any { it.stage == SleepStage.DEEP })
        }
    }

    @Test fun `Sleep API session without motion is undetermined rather than Light`() {
        val session = session(base, 60)
        val stages = estimate(session, emptyList(), emptyList(), emptyList(), listOf(SleepSegment(base, session.endMillis, 95)))

        assertTrue(stages.isNotEmpty())
        assertTrue(stages.all { it.stage == SleepStage.SLEEPING })
        assertFalse(stages.any { it.stage == SleepStage.DEEP })
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
