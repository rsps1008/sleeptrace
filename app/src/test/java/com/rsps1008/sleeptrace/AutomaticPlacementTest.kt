package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AutomaticPlacementTest {
    private val start = LocalDate.of(2026, 9, 28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun rows(moving: Set<Int> = emptySet(), count: Int = 60) = (0 until count).map {
        MotionMinute(start + it * MINUTE_MS, MINUTE_MS, if (it in moving) 1_000 else 0,
            if (it in moving) 0.05 * 0.05 * MINUTE_MS else 0.0, 300, Placement.AUTO)
    }

    @Test fun `prolonged stillness stays insufficient and cannot create sleep alone`() {
        val resolved = AutomaticPlacement.resolve(rows(), emptyList())
        assertTrue(resolved.all { it.placement == Placement.UNKNOWN && it.coupling?.state == CouplingState.INSUFFICIENT })
        assertTrue(MotionSleepEstimator.estimate(resolved, emptyList(), SleepSchedule(0, 60), start + 60 * MINUTE_MS).isEmpty())
    }

    @Test fun `separated brief movements provide bed evidence without manual settings`() {
        val resolved = AutomaticPlacement.resolve(rows(setOf(5, 15, 25, 35, 45, 55)), emptyList())
        assertTrue(resolved.take(25).none { it.placement == Placement.BED }); assertTrue(resolved.drop(25).all { it.placement == Placement.BED })
        assertEquals(CouplingBasis.FAST, resolved[25].coupling?.basis)
        val sleep = MotionSleepEstimator.estimate(resolved, emptyList(), SleepSchedule(0, 60), start + 60 * MINUTE_MS)
        assertEquals(35 * MINUTE_MS, sleep.single().durationMillis)
        assertEquals(SyncState.PENDING, sleep.single().state)
    }

    @Test fun `three sparse movements establish only through the sixty minute path`() {
        // No thirty-minute window contains all three movements. The sparse
        // proof remains causal and starts only on the third positive minute.
        val resolved = AutomaticPlacement.resolve(rows(setOf(5, 11, 46), 70), emptyList())
        assertTrue(resolved.take(46).all { it.placement == Placement.UNKNOWN })
        val established = resolved[46].coupling!!
        assertEquals(CouplingState.SUPPORTED, established.state)
        assertEquals(CouplingBasis.SPARSE, established.basis)
        assertEquals(3, requireNotNull(established.sparseStats).qualifyingMovements)
        assertEquals(41 * MINUTE_MS, established.sparseStats?.movementSpanMillis)
        assertTrue(requireNotNull(established.fastStats).qualifyingMovements < CouplingPolicy.MIN_MOVEMENTS)
    }

    @Test fun `sparse path still rejects two movements, clustered movements, and overlong spacing`() {
        val cases = listOf(
            rows(setOf(5, 46), 70),
            rows(setOf(5, 6, 7), 70),
            rows(setOf(5, 40, 70), 90)
        )
        cases.forEach { input ->
            assertTrue(AutomaticPlacement.resolve(input, emptyList()).all {
                it.coupling?.state == CouplingState.INSUFFICIENT && it.placement == Placement.UNKNOWN
            })
        }
    }

    @Test fun `single vibration or brief sample cannot establish bed placement`() {
        assertTrue(AutomaticPlacement.resolve(rows(setOf(10)), emptyList()).none { it.placement == Placement.BED })
        assertTrue(AutomaticPlacement.resolve(rows(setOf(1, 5, 9), 10), emptyList()).all { it.placement == Placement.UNKNOWN })
    }

    @Test fun `phone use and surrounding handling do not establish bed evidence`() {
        val moving = setOf(5, 15, 25, 35, 45, 55)
        val usage = moving.map { UsageInterval(start + it * MINUTE_MS, start + (it + 1) * MINUTE_MS) }
        assertTrue(AutomaticPlacement.resolve(rows(moving), usage).none { it.placement == Placement.BED })
    }

    @Test fun `missing data separates placement evidence and preserves unknown`() {
        val input = rows(setOf(5, 15, 25, 35, 45, 55)).mapIndexed { index, row ->
            if (index % 10 == 0) row.copy(coveredMillis = 1_000) else row
        }
        assertTrue(AutomaticPlacement.resolve(input, emptyList()).all { it.placement == Placement.UNKNOWN })
    }

    @Test fun `placement changes are reevaluated instead of applying one label to all night`() {
        val resolved = AutomaticPlacement.resolve(rows(setOf(5, 15, 25), 120), emptyList())
        assertEquals(Placement.UNKNOWN, resolved[10].placement)
        assertEquals(Placement.BED, resolved[55].placement)
        assertEquals("COUPLING_EXPIRED", resolved[71].coupling?.reason)
        assertEquals(Placement.UNKNOWN, resolved[100].placement)
        assertEquals("COUPLING_INSUFFICIENT", resolved[100].coupling?.reason)
        assertEquals("COUPLING_EXPIRED", resolved[100].coupling?.lastResetReason)
    }

    @Test fun `live coupling renews on one new qualifying movement without rebuilding initial history`() {
        // 5/15/25 establish the original proof.  At 60 the old three movements
        // have aged out of the 30-minute establishment window, but 25 is only
        // 35 minutes old, so the new observed movement renews the live proof.
        val resolved = AutomaticPlacement.resolve(rows(setOf(5, 15, 25, 60), 75), emptyList())
        assertEquals(CouplingState.HELD, resolved[59].coupling?.state)
        assertEquals(CouplingState.SUPPORTED, resolved[60].coupling?.state)
        assertEquals(0L, resolved[60].coupling?.ageMillis)
    }

    @Test fun `quiet twentieth historical minute establishes coupling without treating quiet as new evidence`() {
        // The proof has already accumulated by minute 19.  Establishment is
        // confirmed then, but its expiry is anchored to the last movement at 15.
        val resolved = AutomaticPlacement.resolve(rows(setOf(5, 10, 15), 30), emptyList())
        assertTrue(resolved.take(19).none { it.coupling?.state == CouplingState.SUPPORTED || it.coupling?.state == CouplingState.HELD })
        assertEquals(CouplingState.HELD, resolved[19].coupling?.state)
        assertEquals(4 * MINUTE_MS, resolved[19].coupling?.ageMillis)
        assertEquals(CouplingState.HELD, resolved[20].coupling?.state)
        assertEquals(5 * MINUTE_MS, resolved[20].coupling?.ageMillis)
    }

    @Test fun `quiet never renews and a single movement after expiry cannot resurrect coupling`() {
        val expired = AutomaticPlacement.resolve(rows(setOf(5, 15, 25), 90), emptyList())
        assertEquals("COUPLING_EXPIRED", expired[71].coupling?.reason)
        val afterOneMovement = AutomaticPlacement.resolve(rows(setOf(5, 15, 25, 80), 90), emptyList())
        assertEquals(CouplingState.INSUFFICIENT, afterOneMovement[80].coupling?.state)
        assertEquals(Placement.UNKNOWN, afterOneMovement[80].placement)
        assertEquals(1, afterOneMovement[80].coupling?.sparseStats?.qualifyingMovements)
        assertEquals("COUPLING_EXPIRED", afterOneMovement[80].coupling?.lastResetReason)
    }

    @Test fun `hard resets discard sparse history before a later third movement`() {
        val source = rows(setOf(5, 11, 46), 70)
        val nextMidnight = LocalDate.of(2026, 9, 29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val acrossDailyBoundary = source.mapIndexed { i, row ->
            row.copy(startMillis = nextMidnight - 30 * MINUTE_MS + i * MINUTE_MS)
        }
        data class Case(
            val name: String,
            val rows: List<MotionMinute>,
            val usage: List<UsageInterval> = emptyList(),
            val schedule: SleepSchedule? = null
        )
        val cases = listOf(
            Case("phone", source, listOf(UsageInterval(start + 30 * MINUTE_MS, start + 31 * MINUTE_MS))),
            Case("handling", source.mapIndexed { i, row -> if (i == 30) row.copy(maxDelta = 2.0) else row }),
            Case("gap", source.mapIndexed { i, row -> if (i == 30) row.copy(longestGapMillis = 3_000) else row }),
            Case("recording", source.mapIndexed { i, row -> row.copy(recordingId = if (i < 30) 1 else 2) }),
            Case("feature", source.mapIndexed { i, row -> if (i < 30) row else row.copy(featureVersion = MotionAccumulator.CADENCE_ANCHOR_FEATURE_VERSION) }),
            Case("missing minute", source.filterIndexed { i, _ -> i != 30 }),
            Case("schedule", acrossDailyBoundary, schedule = SleepSchedule(0, 0))
        )
        cases.forEach { case ->
            val resolved = AutomaticPlacement.resolve(case.rows, case.usage, case.schedule)
            assertTrue(case.name, resolved.none { it.placement == Placement.BED })
        }
    }

    @Test fun `current blocker clears while the last reset reason remains diagnostic history`() {
        val input = rows(count = 50).mapIndexed { i, row ->
            if (i == 0) row.copy(coveredMillis = 1_000) else row
        }
        val resolved = AutomaticPlacement.resolve(input, emptyList())
        assertEquals("MISSING_MOTION", resolved[0].coupling?.currentBlocker)
        assertEquals("MISSING_MOTION", resolved[0].coupling?.reason)
        assertNull(resolved[1].coupling?.currentBlocker)
        assertEquals("COUPLING_INSUFFICIENT", resolved[1].coupling?.reason)
        assertEquals("MISSING_MOTION", resolved[1].coupling?.lastResetReason)
    }

    @Test fun `unevaluated coupling windows stay null instead of reporting measured zero`() {
        val invalid = rows(count = 1).single().copy(coveredMillis = 1_000)
        val invalidEvidence = requireNotNull(AutomaticPlacement.resolve(listOf(invalid), emptyList()).single().coupling)
        assertNull(invalidEvidence.fastStats)
        assertNull(invalidEvidence.sparseStats)

        val legacy = rows(count = 1).single().copy(placement = Placement.BED)
        val legacyEvidence = requireNotNull(AutomaticPlacement.resolve(listOf(legacy), emptyList()).single().coupling)
        assertNull(legacyEvidence.fastStats)
        assertNull(legacyEvidence.sparseStats)

        val evaluated = requireNotNull(AutomaticPlacement.resolve(rows(count = 1), emptyList()).single().coupling)
        assertEquals(0, requireNotNull(evaluated.fastStats).qualifyingMovements)
        assertEquals(0, requireNotNull(evaluated.sparseStats).qualifyingMovements)
    }

    @Test fun `reset diagnostics distinguish timeline feature recording and schedule boundaries`() {
        fun blocker(input: List<MotionMinute>, schedule: SleepSchedule? = null) =
            AutomaticPlacement.resolve(input, emptyList(), schedule).last().coupling?.currentBlocker

        val first = rows(count = 1).single().copy(recordingId = 1)
        assertEquals("TIMELINE_GAP", blocker(listOf(first, first.copy(startMillis = first.startMillis + 2 * MINUTE_MS))))
        assertEquals("FEATURE_BOUNDARY", blocker(listOf(first, first.copy(
            startMillis = first.startMillis + MINUTE_MS,
            featureVersion = MotionAccumulator.CADENCE_ANCHOR_FEATURE_VERSION
        ))))
        assertEquals("RECORDING_BOUNDARY", blocker(listOf(first, first.copy(
            startMillis = first.startMillis + MINUTE_MS,
            recordingId = 2
        ))))

        val midnight = LocalDate.of(2026, 9, 29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val before = first.copy(startMillis = midnight - MINUTE_MS)
        assertEquals(
            "SCHEDULE_BOUNDARY",
            blocker(listOf(before, before.copy(startMillis = midnight)), SleepSchedule(0, 0))
        )
    }

    @Test fun `recording boundary handling minute is not allowed into fresh evidence history`() {
        val input = rows(setOf(5, 15, 25), 40).mapIndexed { i, row ->
            if (i == 30) row.copy(recordingId = 2, maxDelta = 2.0, squaredDeltaTime = 2.0 * 2.0 * MINUTE_MS)
            else row.copy(recordingId = 1)
        }
        val resolved = AutomaticPlacement.resolve(input, emptyList())
        assertEquals(Placement.UNKNOWN, resolved[30].placement)
        assertTrue(resolved[30].coupling?.reason?.contains("HANDLING") == true)
        assertTrue(resolved[30].coupling?.currentBlocker?.contains("RECORDING_BOUNDARY") == true)
        assertTrue(resolved[30].coupling?.currentBlocker?.contains("HANDLING") == true)
        assertTrue(resolved.drop(31).none { it.placement == Placement.BED })
    }

    @Test fun `end to end accumulator events feed AUTO rather than pre-labelled BED`() {
        val accumulator = MotionAccumulator(SamplingPlan(1_000_000, 0), Placement.AUTO, 44)
        val movements = setOf(5, 15, 25)
        for (minute in 0 until 55) for (second in 0 until 60) {
            // A physically plausible short displacement and return, with all
            // intervening 1 Hz observations retained by the real accumulator.
            val x = when {
                minute in movements && second == 20 -> .20
                minute in movements && second == 21 -> 0.0
                else -> 0.0
            }
            accumulator.add(start + minute * MINUTE_MS + second * 1_000L, x, 0.0, 9.8)
        }
        val raw = accumulator.drain(start + 55 * MINUTE_MS)
        assertTrue(raw.all { it.placement == Placement.AUTO })
        val resolved = AutomaticPlacement.resolve(raw, emptyList())
        assertTrue(resolved.any { it.coupling?.state == CouplingState.SUPPORTED })
        assertTrue(resolved.drop(25).any { it.placement == Placement.BED })
    }

    @Test fun `ten Hz and legacy one Hz preserve coupling for the same smooth bed movement`() {
        val movementMinutes = setOf(5, 20, 35)
        fun capture(plan: SamplingPlan, stepMillis: Long): List<MotionMinute> {
            val accumulator = MotionAccumulator(plan, Placement.AUTO, 66)
            val duration = 45 * MINUTE_MS
            for (offset in 0L..duration step stepMillis) {
                val minute = (offset / MINUTE_MS).toInt()
                val within = offset % MINUTE_MS
                val x = if (minute in movementMinutes) when (within) {
                    in 20_000L..21_000L -> .3 * (within - 20_000L) / 1_000.0
                    in 21_001L..22_000L -> .3 * (22_000L - within) / 1_000.0
                    else -> 0.0
                } else 0.0
                accumulator.add(start + offset, x, 0.0, 9.81)
            }
            return accumulator.drain(start + duration)
        }

        val tenHertz = capture(SamplingPlan.choose(1_000), 100L)
        val oneHertz = capture(
            SamplingPlan.choose(1_000, targetPeriodUs = SamplingPlan.LEGACY_ONE_HZ_TARGET_PERIOD_US),
            1_000L
        )
        listOf(tenHertz, oneHertz).forEach { minutes ->
            movementMinutes.forEach { index ->
                assertTrue("version=${minutes[index].featureVersion} index=$index rms=${minutes[index].rms}",
                    minutes[index].rms >= CouplingPolicy.MIN_MOVEMENT_RMS)
                assertTrue(minutes[index].activeMillis >= CouplingPolicy.MIN_ACTIVE_MILLIS)
            }
            val resolved = AutomaticPlacement.resolve(minutes, emptyList())
            assertEquals(CouplingState.SUPPORTED, resolved[35].coupling?.state)
            assertEquals(CouplingBasis.FAST, resolved[35].coupling?.basis)
            assertEquals(Placement.BED, resolved[35].placement)
        }
    }

    @Test fun `end to end accumulator AUTO and staging retain supported evidence but reject flat signal`() {
        fun capture(movements: Set<Int>): List<MotionMinute> {
            val accumulator = MotionAccumulator(SamplingPlan(1_000_000, 0), Placement.AUTO, 88)
            for (minute in 0 until 90) for (second in 0 until 60) {
                val x = when {
                    minute in movements && second == 20 -> .20
                    minute in movements && second == 21 -> 0.0
                    else -> if (minute < 45) .003 else .015
                }
                accumulator.add(start + minute * MINUTE_MS + second * 1_000L, x, 0.0, 9.8)
            }
            return accumulator.drain(start + 90 * MINUTE_MS)
        }
        fun stages(minutes: List<MotionMinute>) = SleepStageEstimator.analyze(
            SleepSession(startMillis = start, endMillis = start + 90 * MINUTE_MS, confidence = 80,
                awakeMillis = 0, state = SyncState.PENDING, reason = "e2e"),
            AutomaticPlacement.resolve(minutes, emptyList()), emptyList(), emptyList(),
            listOf(SleepSegment(start, start + 90 * MINUTE_MS, 80)), SleepSchedule(0, 0))
        val supported = stages(capture(setOf(5, 10, 15, 60)))
        assertTrue(supported.minutes.any { it.motion?.coupling?.state == CouplingState.SUPPORTED })
        assertTrue(supported.durations.deep > 0)
        assertTrue(supported.durations.light > 0)
        val flat = stages(capture(emptySet()))
        assertTrue(flat.minutes.all { it.motion?.placement != Placement.BED })
        assertEquals(0L, flat.durations.deep)
        assertTrue(flat.intervals.all { it.stage == SleepStage.LIGHT })
        assertTrue(flat.minutes.all { !it.canStage })
        assertTrue(flat.minutes.all { SleepStageEstimator.Reason.COUPLING_INSUFFICIENT in it.reasons })
    }

    @Test fun `legacy minute placement remains readable but cannot spread into new records`() {
        val input = listOf(rows().first().copy(placement = Placement.BED)) + rows().drop(1)
        val resolved = AutomaticPlacement.resolve(input, emptyList())
        assertEquals(Placement.BED, resolved.first().placement)
        assertTrue(resolved.drop(1).none { it.placement == Placement.BED })
    }
}
