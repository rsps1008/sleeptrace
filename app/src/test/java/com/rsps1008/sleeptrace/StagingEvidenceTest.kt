package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import com.rsps1008.sleeptrace.health.toHealthRecord
import androidx.health.connect.client.records.SleepSessionRecord
import org.junit.Assert.*
import org.junit.Test

/** Semantic regressions: fallback Light keeps blockers explicit and positive examples still stage. */
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
    private fun legacyPlan(fifo: Int = 100) = SamplingPlan.choose(
        fifo,
        targetPeriodUs = SamplingPlan.LEGACY_ONE_HZ_TARGET_PERIOD_US
    )
    private fun stage(r: SleepStageEstimator.StagingResult, i: Int) = r.intervals.single {
        base + i * MINUTE_MS >= it.startMillis && base + i * MINUTE_MS < it.endMillis }.stage

    @Test fun `missing motion has multiple structured reasons and null acquisition delay`() {
        val r = analyze(emptyList())
        assertTrue(r.intervals.all { it.stage == SleepStage.LIGHT })
        assertNull(r.firstMotionDelayMillis); assertNull(r.firstValidMotionDelayMillis)
        assertTrue(r.minutes.all { SleepStageEstimator.Reason.MISSING_MOTION in it.reasons &&
            SleepStageEstimator.Reason.BASELINE_INSUFFICIENT in it.reasons })
        assertTrue(r.minutes.all { !it.canStage })
        assertEquals(120 * MINUTE_MS, r.durations.light)
        assertEquals(0L, r.durations.sleeping)
        assertEquals(120 * MINUTE_MS, r.fallbackLightReasonsMillis.getValue(SleepStageEstimator.Reason.MISSING_MOTION))
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
        assertEquals(4 * MINUTE_MS - 2_000, d.light)
        assertEquals(0L, d.sleeping)
        assertEquals(d.sleep, d.deep + d.light + d.sleeping)
        assertEquals(SleepStage.AWAKE, sleepParts(s).first().stage)
        assertEquals(SleepStage.AWAKE, sleepParts(s).last().stage)
    }
    @Test fun `invalid stage boundaries and contradictory overlaps deterministically fall back to Light`() {
        val s = session(2).copy(stageIntervals = listOf(SleepStageInterval(base, base + MINUTE_MS, SleepStage.DEEP),
            SleepStageInterval(base, base + MINUTE_MS, SleepStage.LIGHT),
            SleepStageInterval(base + MINUTE_MS, base, SleepStage.DEEP)))
        assertTrue(sleepParts(s).all { it.stage == SleepStage.LIGHT })
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
    @Test fun `relative quiet threshold is robust to one maximum outlier`() {
        val baseline = requireNotNull(analyze(rows()).baseline)
        val oneOutlier = rows().mapIndexed { index, row ->
            val rms = if (index == 119) .4 else row.rms
            row.copy(squaredDeltaTime = rms * rms * MINUTE_MS)
        }
        val changed = requireNotNull(analyze(oneOutlier).baseline)
        assertEquals(baseline.p70, changed.p70, 0.0)
    }
    @Test fun `unconfirmed isolated relative quiet minute falls back to Light`() {
        val synthetic = rows(60).mapIndexed { index, row ->
            when {
                index <= 53 -> row.copy(
                    activeMillis = 6_000,
                    squaredDeltaTime = .01 * .01 * MINUTE_MS,
                    movementEvents = 1,
                    longestActiveMillis = 6_000,
                    quietTailMillis = 0,
                )
                index <= 58 -> row.copy(squaredDeltaTime = .15 * .15 * MINUTE_MS)
                else -> row.copy(squaredDeltaTime = .001 * .001 * MINUTE_MS)
            }.copy(featureVersion = MotionAccumulator.ONE_HZ_FEATURE_VERSION)
        }
        val result = analyze(synthetic, session(60))
        val last = result.minutes.last()
        assertEquals(SleepStage.DEEP, last.formalStage)
        assertEquals(SleepStage.LIGHT, last.finalStage)
        assertEquals(SleepStageEstimator.Reason.SHORT_DEEP_RUN, last.primaryReason)
        assertEquals(SleepStageEstimator.PostProcessReason.SHORT_DEEP_RUN_FILTER, last.retroactiveAdjustmentReason)
        assertFalse(SleepStageEstimator.Reason.RELATIVE_QUIET_CONFIRMED in last.nonBlockingReasons)
        assertTrue(last.isFallbackLight)
        assertEquals(
            MINUTE_MS,
            result.fallbackLightReasonsMillis.getValue(SleepStageEstimator.Reason.SHORT_DEEP_RUN)
        )
    }

    @Test fun `ten Hz Deep candidates shorter than ten minutes fall back without padding`() {
        fun active(row: MotionMinute) = row.copy(
            activeMillis = 6_000,
            squaredDeltaTime = .30 * .30 * MINUTE_MS,
            movementEvents = 2,
            longestActiveMillis = 6_000,
            quietTailMillis = 0
        )
        val short = analyze(rows(60).mapIndexed { index, row ->
            if (index in 27..31) active(row) else row
        }, session(60))
        for (index in 20..26) assertEquals(SleepStage.LIGHT, stage(short, index))
        assertFalse(short.minutes.any { it.wasBackfilled })
        assertTrue(short.minutes.any {
            it.retroactiveAdjustmentReason == SleepStageEstimator.PostProcessReason.SHORT_DEEP_RUN_FILTER
        })
        assertTrue(short.intervals.filter { it.stage == SleepStage.DEEP }
            .all { it.endMillis - it.startMillis >= 10 * MINUTE_MS })

        val durable = analyze(rows(60).mapIndexed { index, row ->
            if (index in 36..40) active(row) else row
        }, session(60))
        assertFalse(durable.minutes.any { it.wasBackfilled })
        assertTrue(durable.durations.deep >= 10 * MINUTE_MS)
        assertTrue(durable.intervals.filter { it.stage == SleepStage.DEEP }
            .all { it.endMillis - it.startMillis >= 10 * MINUTE_MS })
    }


    @Test fun `ten Hz Deep run boundary removes nine minutes and preserves exactly ten without backfill`() {
        fun boundary(breakIndex: Int): SleepStageEstimator.StagingResult {
            val signal = rows(40).mapIndexed { index, row ->
                val rms = if (index < 30) .005 else .04
                row.copy(
                    squaredDeltaTime = rms * rms * MINUTE_MS,
                    maxDelta = rms * 3,
                    postureDelta = rms * 3
                ).let { candidate ->
                    if (index == breakIndex) candidate.copy(
                        coveredMillis = 10_000,
                        sampleCount = 100,
                        longestGapMillis = 50_000
                    ) else candidate
                }
            }
            return analyze(signal, session(40))
        }

        val nineMinutes = boundary(29)
        assertEquals(9, nineMinutes.minutes.count { it.formalStage == SleepStage.DEEP })
        assertEquals(0L, nineMinutes.durations.deep)
        assertTrue(nineMinutes.minutes.filter { it.formalStage == SleepStage.DEEP }
            .all { it.finalStage == SleepStage.LIGHT &&
                it.primaryReason == SleepStageEstimator.Reason.SHORT_DEEP_RUN })
        assertFalse(nineMinutes.minutes.any { it.wasBackfilled })

        val tenMinutes = boundary(30)
        assertEquals(10, tenMinutes.minutes.count { it.formalStage == SleepStage.DEEP })
        assertEquals(10 * MINUTE_MS, tenMinutes.durations.deep)
        assertTrue(tenMinutes.minutes.filter { it.formalStage == SleepStage.DEEP }
            .all { it.finalStage == SleepStage.DEEP })
        assertFalse(tenMinutes.minutes.any { it.wasBackfilled })
    }
    @Test fun `flat noise even with explicit legacy bed support cannot manufacture Deep`() {
        val r = analyze(rows().map { it.copy(squaredDeltaTime = .002 * .002 * MINUTE_MS) })
        assertEquals(0L, r.durations.deep); assertEquals(session().durationMillis, r.durations.light)
        assertEquals(0L, r.durations.sleeping)
        assertTrue(r.minutes.all { SleepStageEstimator.Reason.LOW_SIGNAL_DIFFERENTIATION in it.reasons })
    }
    @Test fun `current feature absent temporal fields are not fake zero measurements`() {
        val r = analyze(rows().map { it.copy(movementEvents = null, longestGapMillis = null) })
        assertTrue(r.intervals.all { it.stage == SleepStage.LIGHT })
        assertTrue(r.minutes.all { !it.canStage })
        assertTrue(r.minutes.all { SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION in it.reasons })
    }
    @Test fun `mixed v4 and current baselines are isolated and recording restart cannot carry Deep`() {
        val m = rows().mapIndexed { i, row -> when {
            i < 30 -> row.copy(featureVersion = 4, squaredDeltaTime = .9 * .9 * MINUTE_MS)
            i >= 55 -> row.copy(recordingId = 2)
            else -> row
        } }
        val r = analyze(m)
        assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION, r.baselineFeatureVersion)
        assertTrue(r.baseline!!.p50 < .1)
        assertEquals(SleepStage.LIGHT, stage(r, 55))
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
    @Test fun `safety cap falls removed Deep back to Light and keeps its reason`() {
        val s = session(300)
        val m = rows(300).mapIndexed { i, row -> row.copy(squaredDeltaTime = (if (i < 280) .005 else .08).let { it * it * MINUTE_MS }) }
        val r = analyze(m, s)
        assertTrue(r.minutes.any { SleepStageEstimator.Reason.SAFETY_CAP in it.reasons })
        assertTrue(r.minutes.filter { SleepStageEstimator.Reason.SAFETY_CAP in it.reasons }.all { it.stage == SleepStage.LIGHT })
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
        val next = s.copy(stageAlgorithmVersion = SleepStageEstimator.ALGORITHM_VERSION,
            stageFeatureVersion = MotionAccumulator.CURRENT_FEATURE_VERSION)
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
    @Test fun `generic and legacy Sleeping both upload as Light and remain ordered`() {
        val record = toHealthRecord(session().copy(stageIntervals = analyze(emptyList()).intervals))
        assertEquals(listOf(SleepSessionRecord.STAGE_TYPE_LIGHT), record.stages.map { it.stage })
        assertTrue(record.stages.zipWithNext().all { (a,b) -> a.endTime <= b.startTime })
        val legacy = toHealthRecord(session().copy(stageIntervals = listOf(
            SleepStageInterval(base, base + 120 * MINUTE_MS, SleepStage.SLEEPING)
        )))
        assertEquals(listOf(SleepSessionRecord.STAGE_TYPE_LIGHT), legacy.stages.map { it.stage })
    }
    @Test fun `new fixed cadence features preserve event structure and invalid events are counted`() {
        val engine = MotionAccumulator(SamplingPlan.choose(100), Placement.AUTO)
        for (tick in 0..1200) {
            val second = tick / 10.0
            val x = if (second in 20.0..23.9 || second in 45.0..48.9) .3 else 0.0
            engine.add(base + tick * 100L, x, 0.0, 9.81)
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
        for (tick in 0..1800) if (tick != 100 && tick !in 800..1099) engine.add(base + tick * 100L, 0.0, 0.0, 9.81)
        val m = engine.drain(base + 180_000)
        assertEquals(59_800L, m[0].coveredMillis)
        assertEquals(200L, m[0].longestGapMillis)
        assertEquals(30_100L, m[1].longestGapMillis)
        assertEquals(0.0, m[1].rms, 0.0)
    }
    @Test fun `partial flush never duplicates contributions and restart has a distinct identity`() {
        val engine = MotionAccumulator(SamplingPlan.choose(0), Placement.AUTO)
        for (i in 0..350) engine.add(base + i * 100L, 0.0, 0.0, 9.81)
        val first = engine.drain(Long.MAX_VALUE, true).single()
        assertEquals(35_000L, first.coveredMillis)
        assertTrue(engine.drain(Long.MAX_VALUE, true).isEmpty())
        val restarted = MotionAccumulator(SamplingPlan.choose(0), Placement.AUTO)
        for (i in 400..600) restarted.add(base + i * 100L, 0.0, 0.0, 9.81)
        assertNotEquals(first.recordingId, restarted.drain(Long.MAX_VALUE, true).first().recordingId)
    }
    @Test fun `CSV escaping distinguishes literal separators and missing fields`() {
        assertEquals("\"a,b\"", csvEscape("a,b"))
        assertEquals("\"a\"\"b\"", csvEscape("a\"b"))
        assertEquals("", csvEscape("")); assertEquals("0", csvEscape("0"))
    }
    @Test fun `diagnostic CSV schema keeps header and row aligned with distinct empty and false values`() {
        val values = diagnosticCsvHeaders.mapIndexed { index, _ ->
            when (index) {
                0 -> ""
                1 -> "false"
                else -> "value-$index"
            }
        }
        val row = diagnosticCsvRow(values)
        assertEquals(DIAGNOSTIC_CSV_HEADER.split(',').size, diagnosticCsvHeaders.size)
        assertEquals(diagnosticCsvHeaders.size, row.split(',').size)
        assertEquals("", row.substringBefore(','))
        assertEquals("false", row.substringAfter(',').substringBefore(','))
        assertTrue(diagnosticCsvRow(listOf("a,b") + List(diagnosticCsvHeaders.size - 1) { "" })
            .startsWith("\"a,b\","))
        val v1Headers = DIAGNOSTIC_CSV_V1_HEADER.split(',')
        assertEquals(111, v1Headers.size)
        assertEquals(v1Headers, diagnosticCsvHeaders.take(v1Headers.size))
        assertEquals("requested_period_us", diagnosticCsvHeaders[103])
        assertEquals("raw_events", diagnosticCsvHeaders[107])
        assertEquals("max_event_interval_ms", diagnosticCsvHeaders[110])
        val v2Headers = DIAGNOSTIC_CSV_V2_HEADER.split(',')
        assertEquals(v2Headers, diagnosticCsvHeaders.take(v2Headers.size))
        assertEquals("diagnostic_schema_version", v2Headers.last())
        assertEquals(listOf(
            "light_fallback", "light_fallback_reason", "fallback_light_reasons_ms",
            "relative_quiet_threshold", "relative_quiet_method"
        ), diagnosticCsvHeaders.takeLast(5))
        assertEquals(3, DIAGNOSTIC_SCHEMA_VERSION)
    }
    @Test fun `formal plan is ten Hz while early comparison plans remain explicit`() {
        assertEquals(SamplingPlan.choose(100), capturePlan(CaptureExperiment.OFF, 100))
        assertEquals(100_000, capturePlan(CaptureExperiment.OFF, 100).periodUs)
        assertEquals(1_000_000, capturePlan(CaptureExperiment.EARLY_1HZ, 100).periodUs)
        assertEquals(500_000, capturePlan(CaptureExperiment.EARLY_2HZ, 100).periodUs)
        assertEquals(0, capturePlan(CaptureExperiment.OFF, 0).latencyUs)
    }
    @Test fun `ten second vector means detect sustained low frequency posture change`() {
        val engine = MotionAccumulator(SamplingPlan.choose(100), Placement.AUTO)
        for (tick in 0..600) engine.add(base + tick * 100L, if (tick < 300) 0.0 else 2.0, 0.0, 9.81)
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
        assertEquals(SleepStage.LIGHT, stage(r, 40))
        assertFalse(r.minutes[40].canMaintainDeep)
        assertFalse(r.minutes[40].canStage)
        assertTrue(SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE in r.minutes[40].reasons)
    }

    @Test fun `multiple real-second gaps do not pass the one minor gap budget`() {
        val gappy = rows().mapIndexed { index, minute ->
            if (index == 40) minute.copy(coveredMillis = 52_000, longestGapMillis = 2_000,
                coupling = CouplingEvidence(CouplingState.HELD, 30 * MINUTE_MS, null)) else minute
        }
        val staged = analyze(gappy)
        // The four missing seconds occupy the 31..45 entry window. V6 used
        // longestGapMillis (=2s) as the total and incorrectly entered here.
        assertEquals(SleepStage.LIGHT, stage(staged, 40))
        assertFalse(staged.minutes[40].canStage)
        assertNotEquals("enter_stable_window", staged.minutes[45].event)
        assertTrue(SleepStageEstimator.Reason.WINDOW_CONTAINS_GAP in staged.minutes[45].windowBlockingReasons)
    }

    @Test fun `contradictory full coverage with a claimed gap is never a positive gap fixture`() {
        val contradictory = rows().mapIndexed { index, minute ->
            if (index == 40) minute.copy(longestGapMillis = 2_000,
                coupling = CouplingEvidence(CouplingState.HELD, 30 * MINUTE_MS, null)) else minute
        }
        val staged = analyze(contradictory)
        assertEquals(SleepStage.LIGHT, stage(staged, 40))
        assertFalse(staged.minutes[40].canStage)
        assertTrue(SleepStageEstimator.Reason.WINDOW_CONTAINS_GAP in staged.minutes[45].windowBlockingReasons)
    }
}
