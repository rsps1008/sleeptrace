package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import com.rsps1008.sleeptrace.health.toHealthRecord
import androidx.health.connect.client.records.SleepSessionRecord
import org.junit.Assert.*
import org.junit.Test

/** Semantic regressions: unknown is not Light; positive examples must still stage. */
class StagingEvidenceTest {
    private val base = 20_000L * MINUTE_MS
    private val schedule = SleepSchedule(0, 0)
    private fun session(n: Int = 120) = SleepSession(id = "identity", startMillis = base,
        endMillis = base + n * MINUTE_MS, confidence = 60, awakeMillis = 0, state = SyncState.PENDING, reason = "synthetic")
    private fun rows(n: Int = 120) = (0 until n).map { i ->
        val rms = if (i < n * .7) .005 else .04
        MotionMinute(base + i * MINUTE_MS, MINUTE_MS, 0, rms * rms * MINUTE_MS, 60, Placement.BED,
            maxDelta = rms * 3, movementEvents = 0, longestActiveMillis = 0,
            quietTailMillis = MINUTE_MS, longestGapMillis = 0, postureDelta = rms * 3, recordingId = 1)
    }
    private fun analyze(m: List<MotionMinute>, s: SleepSession = session(), use: List<UsageInterval> = emptyList()) =
        SleepStageEstimator.analyze(s, m, emptyList(), use, listOf(SleepSegment(s.startMillis, s.endMillis, 60)), schedule)
    private fun stage(r: SleepStageEstimator.StagingResult, i: Int) = r.intervals.single {
        base + i * MINUTE_MS >= it.startMillis && base + i * MINUTE_MS < it.endMillis }.stage

    @Test fun `missing motion has multiple structured reasons and null acquisition delay`() {
        val r = analyze(emptyList())
        assertTrue(r.intervals.all { it.stage == SleepStage.SLEEPING })
        assertNull(r.firstMotionDelayMillis); assertNull(r.firstValidMotionDelayMillis)
        assertTrue(r.minutes.all { SleepStageEstimator.Reason.MISSING_MOTION in it.reasons &&
            SleepStageEstimator.Reason.BASELINE_INSUFFICIENT in it.reasons })
        assertEquals(120 * MINUTE_MS, r.durations.sleeping)
        assertEquals(120 * MINUTE_MS, r.longestGapMillis)
    }
    @Test fun `standardized timeline conserves milliseconds with partial gaps overlaps and edge Awake`() {
        val s = session(5).copy(awakeIntervals = listOf(UsageInterval(base - 100, base + 3_000),
            UsageInterval(base + 1_000, base + 5_000), UsageInterval(base + 5 * MINUTE_MS - 2_000, base + 6 * MINUTE_MS)),
            stageIntervals = listOf(SleepStageInterval(base, base + MINUTE_MS, SleepStage.DEEP),
                SleepStageInterval(base + 2 * MINUTE_MS, base + 3 * MINUTE_MS, SleepStage.LIGHT)))
        val d = stageDurations(s)
        assertEquals(7_000L, d.awake)
        assertEquals(5 * MINUTE_MS, d.span)
        assertEquals(3 * MINUTE_MS - 2_000, d.sleeping)
        assertEquals(d.sleep, d.deep + d.light + d.sleeping)
        assertEquals(SleepStage.AWAKE, sleepParts(s).first().stage)
        assertEquals(SleepStage.AWAKE, sleepParts(s).last().stage)
    }
    @Test fun `invalid stage boundaries and contradictory overlaps are deterministic unknown`() {
        val s = session(2).copy(stageIntervals = listOf(SleepStageInterval(base, base + MINUTE_MS, SleepStage.DEEP),
            SleepStageInterval(base, base + MINUTE_MS, SleepStage.LIGHT),
            SleepStageInterval(base + MINUTE_MS, base, SleepStage.DEEP)))
        assertTrue(sleepParts(s).all { it.stage == SleepStage.SLEEPING })
        assertTrue(sleepParts(s.copy(endMillis = base)).isEmpty())
    }
    @Test fun `five seconds phone use remains exactly five seconds in stats and Health Connect`() {
        val use = listOf(UsageInterval(base + 40 * MINUTE_MS + 5_000, base + 40 * MINUTE_MS + 10_000))
        val r = analyze(rows(), use = use + use)
        assertEquals(5_000L, r.durations.awake)
        assertEquals(5_000L, r.minutes[40].phoneUseMillis)
        assertTrue(r.minutes[40].stage != SleepStage.AWAKE)
        val record = toHealthRecord(session().copy(stageIntervals = r.intervals))
        assertEquals(5_000L, record.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE }
            .sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() })
    }
    @Test fun `sufficient differentiated stable data forms continuous Deep and Light`() {
        val r = analyze(rows())
        assertTrue(r.durations.deep >= 15 * MINUTE_MS)
        assertTrue(r.durations.light > 0)
        assertTrue(r.minutes.any { SleepStageEstimator.Reason.ENTER_DEEP in it.reasons })
        assertTrue(r.minutes.any { SleepStageEstimator.Reason.MAINTAIN_DEEP in it.reasons })
    }
    @Test fun `flat noise even with explicit legacy bed support cannot manufacture Deep or Light`() {
        val r = analyze(rows().map { it.copy(squaredDeltaTime = .002 * .002 * MINUTE_MS) })
        assertEquals(0L, r.durations.deep); assertEquals(0L, r.durations.light)
        assertTrue(r.minutes.all { SleepStageEstimator.Reason.LOW_SIGNAL_DIFFERENTIATION in it.reasons })
    }
    @Test fun `v5 absent temporal fields are not fake zero measurements`() {
        val r = analyze(rows().map { it.copy(movementEvents = null, longestGapMillis = null) })
        assertTrue(r.intervals.all { it.stage == SleepStage.SLEEPING })
        assertTrue(r.minutes.all { SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION in it.reasons })
    }
    @Test fun `mixed v4 and v5 baselines are isolated and recording restart cannot carry Deep`() {
        val m = rows().mapIndexed { i, row -> when {
            i < 30 -> row.copy(featureVersion = 4, squaredDeltaTime = .9 * .9 * MINUTE_MS)
            i >= 55 -> row.copy(recordingId = 2)
            else -> row
        } }
        val r = analyze(m)
        assertEquals(5, r.baselineFeatureVersion)
        assertTrue(r.baseline!!.p50 < .1)
        assertEquals(SleepStage.SLEEPING, stage(r, 55))
        assertTrue(r.intervals.none { it.stage == SleepStage.DEEP && it.startMillis < base + 56 * MINUTE_MS && it.endMillis > base + 55 * MINUTE_MS })
    }
    @Test fun `isolated movement is not Awake and sustained activity exits Deep`() {
        val m = rows().mapIndexed { i, row -> if (i == 35 || i in 50..54) row.copy(
            activeMillis = 6_000, squaredDeltaTime = .3 * .3 * MINUTE_MS, movementEvents = 2, longestActiveMillis = 6_000) else row }
        val r = analyze(m)
        assertEquals(SleepStage.DEEP, stage(r, 35))
        assertEquals(SleepStage.LIGHT, stage(r, 52))
        assertEquals(0L, r.durations.awake)
        assertTrue(r.minutes.any { SleepStageEstimator.Reason.EXIT_SUSTAINED_ACTIVITY in it.reasons })
    }
    @Test fun `dense brief events prevent Deep while preserving valid Light evidence`() {
        val r = analyze(rows().map { it.copy(movementEvents = 5) })
        assertEquals(0L, r.durations.deep)
        assertTrue(r.durations.light > 0)
    }
    @Test fun `safety cap reports unknown instead of inventing Light for removed Deep`() {
        val s = session(300)
        val m = rows(300).mapIndexed { i, row -> row.copy(squaredDeltaTime = (if (i < 280) .005 else .08).let { it * it * MINUTE_MS }) }
        val r = analyze(m, s)
        assertTrue(r.minutes.any { SleepStageEstimator.Reason.SAFETY_CAP in it.reasons })
        assertTrue(r.minutes.filter { SleepStageEstimator.Reason.SAFETY_CAP in it.reasons }.all { it.stage == SleepStage.SLEEPING })
        assertTrue(r.durations.deep <= (300 * MINUTE_MS * .55).toLong())
    }
    private fun couplingRows(n: Int = 150) = rows(n).mapIndexed { i, row -> row.copy(placement = Placement.AUTO,
        activeMillis = if (i in setOf(5, 15, 25)) 1_000 else 0,
        squaredDeltaTime = (if (i in setOf(5, 15, 25)) .05 else .002).let { it * it * MINUTE_MS },
        maxDelta = .1, longestActiveMillis = 1_000) }
    @Test fun `coupling survives quiet thirty minutes then expires without quiet refreshing it`() {
        val m = AutomaticPlacement.resolve(couplingRows(), emptyList(), schedule)
        assertEquals(CouplingState.HELD, m[55].coupling?.state)
        assertEquals(CouplingState.INSUFFICIENT, m[71].coupling?.state)
        assertEquals("COUPLING_EXPIRED", m[71].coupling?.reason)
    }
    @Test fun `handling and missing recording fragment invalidate coupling without declaring Awake`() {
        for (input in listOf(couplingRows().mapIndexed { i, m -> if (i == 40) m.copy(maxDelta = 3.0) else m },
            couplingRows().filterIndexed { i, _ -> i !in 40..50 },
            couplingRows().mapIndexed { i, m -> if (i >= 40) m.copy(recordingId = 2) else m })) {
            val r = AutomaticPlacement.resolve(input, emptyList(), schedule)
            assertTrue(r.filter { it.startMillis >= base + 51 * MINUTE_MS }.all { it.coupling?.state == CouplingState.INSUFFICIENT })
        }
    }
    @Test fun `coupling evidence does not spread across phone use or daily sleep windows`() {
        val usage = listOf(UsageInterval(base + 40 * MINUTE_MS, base + 40 * MINUTE_MS + 5_000))
        val m = AutomaticPlacement.resolve(couplingRows(), usage, schedule)
        assertTrue(m.drop(40).all { it.coupling?.state == CouplingState.INSUFFICIENT })
        val windowSchedule = SleepSchedule(0, 0)
        val midnight = java.time.LocalDate.of(2026, 9, 30).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val shifted = couplingRows().mapIndexed { i, row -> row.copy(startMillis = midnight - 60 * MINUTE_MS + i * MINUTE_MS) }
        assertTrue(AutomaticPlacement.resolve(shifted, emptyList(), windowSchedule).drop(60).all { it.coupling?.state == CouplingState.INSUFFICIENT })
    }
    @Test fun `coupling is never sufficient evidence to accept a motion sleep candidate`() {
        val m = AutomaticPlacement.resolve(couplingRows(), emptyList(), schedule)
        val candidates = MotionSleepEstimator.estimate(m, emptyList(), schedule, base + 2 * 24 * 60 * MINUTE_MS)
        assertTrue(candidates.mapNotNull { confirmMotionCandidateOnset(it, emptyList(), emptyList()) }.isEmpty())
    }
    @Test fun `no usage permission preserves accepted sessions with explicit exclusion limitation`() {
        val s = SleepAnalyzer.analyze(listOf(SleepSegment(base, base + 120 * MINUTE_MS, 60)), emptyList(), emptyList(), schedule, false).single()
        assertTrue(s.reason.contains("無法排除手機使用"))
        assertTrue(analyze(rows(), s).durations.sleep > 0)
    }
    @Test fun `same input and version is deterministic and provenance alone does not revise identity`() {
        val r = analyze(rows()); assertEquals(r, analyze(rows()))
        val s = session().copy(stageIntervals = r.intervals, state = SyncState.SYNCED, revision = 7)
        val next = s.copy(stageAlgorithmVersion = SleepStageEstimator.ALGORITHM_VERSION, stageFeatureVersion = 5)
        val merged = mergeSleepSessions(listOf(s), listOf(next)).single()
        assertEquals(7L, merged.revision); assertEquals("identity", merged.id)
        assertEquals(SleepStageEstimator.ALGORITHM_VERSION, merged.stageAlgorithmVersion)
    }
    @Test fun `historical inputs unavailable retain old stages and manual bounds`() {
        val s = session().copy(manuallyEdited = true, startMillis = base + 20 * MINUTE_MS,
            endMillis = base + 90 * MINUTE_MS)
        val old = listOf(SleepStageInterval(base, base + 120 * MINUTE_MS, SleepStage.DEEP))
        val kept = preserveExistingStagesWithoutCurrentEvidence(analyze(emptyList(), s), old, s)
        assertEquals(s.startMillis, kept.first().startMillis); assertEquals(s.endMillis, kept.last().endMillis)
        assertTrue(kept.all { it.stage == SleepStage.DEEP })
    }
    @Test fun `coverage masks naturally stay within one with a mostly Awake night and zero denominator`() {
        val use = listOf(UsageInterval(base, base + 119 * MINUTE_MS))
        val r = analyze(rows().map { it.copy(coveredMillis = 45_000) }, use = use)
        assertEquals(.75, r.sensorCoverageRatio, 1e-12)
        assertEquals(1.0, r.motionCoverageRatio, 1e-12)
        assertTrue(r.stageableCoverageRatio in 0.0..1.0)
        val allAwake = analyze(rows(), use = listOf(UsageInterval(base, base + 120 * MINUTE_MS)))
        assertEquals(0.0, allAwake.stageableCoverageRatio, 0.0)
        assertEquals(0.0, allAwake.motionCoverageRatio, 0.0)
    }
    @Test fun `generic sleep has the SLEEPING Health Connect constant and remains ordered`() {
        val record = toHealthRecord(session().copy(stageIntervals = analyze(emptyList()).intervals))
        assertEquals(listOf(SleepSessionRecord.STAGE_TYPE_SLEEPING), record.stages.map { it.stage })
        assertTrue(record.stages.zipWithNext().all { (a,b) -> a.endTime <= b.startTime })
    }
    @Test fun `new fixed cadence features preserve event structure and invalid events are counted`() {
        val engine = MotionAccumulator(SamplingPlan.choose(100), Placement.AUTO)
        for (sec in 0..120) {
            val x = if (sec in 20..23 || sec in 45..48) .3 else 0.0
            engine.add(base + sec * 1_000, x, 0.0, 9.81)
        }
        assertFalse(engine.add(base + 120_000, 0.0, 0.0, 9.81))
        assertFalse(engine.add(base + 121_000, Double.NaN, 0.0, 9.81))
        assertFalse(engine.add(base + 122_000, Double.POSITIVE_INFINITY, 0.0, 9.81))
        val m = engine.drain(base + 120_000)
        assertEquals(3L, engine.rejectedEvents)
        assertEquals(4, m[0].movementEvents)
        assertEquals(.3, m[0].maxDelta!!, 1e-12)
        assertEquals(0L, m[0].longestGapMillis)
        assertTrue(m[1].quietTailMillis!! > MINUTE_MS)
    }
    @Test fun `a missing sample and long gap record missing duration without giant motion`() {
        val engine = MotionAccumulator(SamplingPlan.choose(100), Placement.AUTO)
        for (sec in 0..180) if (sec != 10 && sec !in 80..109) engine.add(base + sec * 1000, 0.0, 0.0, 9.81)
        val m = engine.drain(base + 180_000)
        assertEquals(58_000L, m[0].coveredMillis)
        assertEquals(2_000L, m[0].longestGapMillis)
        assertEquals(31_000L, m[1].longestGapMillis)
        assertEquals(0.0, m[1].rms, 0.0)
    }
    @Test fun `partial flush never duplicates contributions and restart has a distinct identity`() {
        val engine = MotionAccumulator(SamplingPlan.choose(0), Placement.AUTO)
        for (i in 0..35) engine.add(base + i * 1000, 0.0, 0.0, 9.81)
        val first = engine.drain(Long.MAX_VALUE, true).single()
        assertEquals(35_000L, first.coveredMillis)
        assertTrue(engine.drain(Long.MAX_VALUE, true).isEmpty())
        val restarted = MotionAccumulator(SamplingPlan.choose(0), Placement.AUTO)
        for (i in 40..60) restarted.add(base + i * 1000, 0.0, 0.0, 9.81)
        assertNotEquals(first.recordingId, restarted.drain(Long.MAX_VALUE, true).first().recordingId)
    }
    @Test fun `CSV escaping distinguishes literal separators and missing fields`() {
        assertEquals("\"a,b\"", csvEscape("a,b"))
        assertEquals("\"a\"\"b\"", csvEscape("a\"b"))
        assertEquals("", csvEscape("")); assertEquals("0", csvEscape("0"))
    }
    @Test fun `formal and early one Hz plans remain identical and two Hz is explicit`() {
        assertEquals(SamplingPlan.choose(100), capturePlan(CaptureExperiment.OFF, 100))
        assertEquals(capturePlan(CaptureExperiment.OFF, 100), capturePlan(CaptureExperiment.EARLY_1HZ, 100))
        assertEquals(1_000_000, capturePlan(CaptureExperiment.OFF, 100).periodUs)
        assertEquals(500_000, capturePlan(CaptureExperiment.EARLY_2HZ, 100).periodUs)
        assertEquals(0, capturePlan(CaptureExperiment.OFF, 0).latencyUs)
    }
    @Test fun `ten second vector means detect sustained low frequency posture change`() {
        val engine = MotionAccumulator(SamplingPlan.choose(100), Placement.AUTO)
        for (s in 0..60) engine.add(base + s * 1000, if (s < 30) 0.0 else 2.0, 0.0, 9.81)
        val row = engine.drain(base + MINUTE_MS).single()
        assertEquals(2.0, row.postureDelta!!, 1e-12)
        assertEquals("HANDLING", AutomaticPlacement.resolve(listOf(row), emptyList()).single().coupling?.reason)
    }
    @Test fun `event density also exits previously established Deep`() {
        val r = analyze(rows().mapIndexed { i, m -> if (i in 40..45) m.copy(movementEvents = 5) else m })
        assertEquals(SleepStage.DEEP, stage(r, 39))
        assertEquals(SleepStage.LIGHT, stage(r, 42))
        assertTrue(r.minutes.any { it.event == "exit_dense_events" })
        assertEquals(0L, r.durations.awake)
    }
    @Test fun `one unobserved second cannot be covered by held Deep even with otherwise valid coverage`() {
        val r = analyze(rows().mapIndexed { i, m -> if (i == 40) m.copy(coveredMillis = 58_000,
            longestGapMillis = 2_000, coupling = CouplingEvidence(CouplingState.HELD, 30*MINUTE_MS, null)) else m })
        assertEquals(SleepStage.DEEP, stage(r, 39))
        assertEquals(SleepStage.SLEEPING, stage(r, 40))
        assertFalse(r.minutes[40].canMaintainDeep)
        assertFalse(r.minutes[40].canStage)
        assertTrue(SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE in r.minutes[40].reasons)
    }
}
