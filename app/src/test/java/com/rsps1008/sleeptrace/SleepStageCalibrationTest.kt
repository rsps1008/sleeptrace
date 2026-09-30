package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import com.rsps1008.sleeptrace.health.toHealthRecord
import androidx.health.connect.client.records.SleepSessionRecord

class SleepStageCalibrationTest {
    private val base = 20_000L * MINUTE_MS
    private val schedule = SleepSchedule(0, 0)
    private fun session(minutes: Int = 120) = SleepSession(
        id = "calibration", startMillis = base, endMillis = base + minutes * MINUTE_MS,
        confidence = 69, awakeMillis = 0, state = SyncState.PENDING, reason = "synthetic regression"
    )
    private fun row(i: Int, rms: Double = if (i < 70) .002 else .015,
                    placement: Placement = Placement.BED, active: Long = 0) =
        MotionMinute(base + i * MINUTE_MS, MINUTE_MS, active, rms * rms * MINUTE_MS, 60, placement)
    private fun rows(minutes: Int = 120) = (0 until minutes).map { row(it) }
    private fun result(rows: List<MotionMinute>, minutes: Int = 120,
                       usage: List<UsageInterval> = emptyList(), evidenceStart: Int = 0,
                       samples: List<ClassificationSample> = emptyList()) = SleepStageEstimator.analyze(
        session(minutes), rows, samples, stageUsageFor(session(minutes), usage),
        listOf(SleepSegment(base + evidenceStart * MINUTE_MS, base + minutes * MINUTE_MS, 60)), schedule
    )
    private fun stage(result: SleepStageEstimator.StagingResult, index: Int) =
        result.intervals.single { base + index * MINUTE_MS >= it.startMillis && base + index * MINUTE_MS < it.endMillis }.stage
    private fun deepMinutes(result: SleepStageEstimator.StagingResult) =
        result.intervals.filter { it.stage == SleepStage.DEEP }.sumOf { it.endMillis - it.startMillis } / MINUTE_MS

    @Test fun `one ten and fifty Hz callbacks produce the same second scale features`() {
        fun capture(step: Long): List<MotionMinute> {
            val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.AUTO)
            for (ms in 0L..120_000L step step) {
                val movement = sin(ms / 4000.0) * if (ms in 30_000..42_000) .8 else .02
                engine.add(base + ms, movement, .1 * movement, 9.81)
            }
            return engine.drain(base + 120_000)
        }
        val slow = capture(1000)
        for (step in listOf(100L, 20L)) {
            val fast = capture(step)
            assertTrue(fast.all { it.sampleCount in 58..62 && it.featureVersion == 2 })
            slow.zip(fast).forEach { (a,b) ->
                assertEquals(a.rms, b.rms, .01)
                assertTrue(kotlin.math.abs(a.activeMillis - b.activeMillis) <= 1_000L)
                assertEquals(a.level, b.level)
            }
        }
    }

    @Test fun `FIFO burst and realtime drains use identical event timestamps`() {
        fun capture(realtime: Boolean): List<MotionMinute> {
            val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.AUTO)
            val saved = mutableListOf<MotionMinute>()
            for (ms in 0L..180_000L step 20) {
                val accepted = engine.add(base + ms, sin(ms / 5000.0) * .4, 0.0, 9.81)
                if (realtime && accepted) saved.addAll(engine.drain(base + ms))
            }
            saved.addAll(engine.drain(base + 180_000))
            return saved
        }
        assertEquals(capture(true), capture(false))
    }

    @Test fun `skipped feature seconds are not interpolated as quiet coverage`() {
        val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.AUTO)
        for (sec in 0..60) if (sec != 11) engine.add(base + sec * 1000, 0.0, 0.0, 9.81)
        val minute = engine.drain(base + MINUTE_MS).single()
        assertEquals(58_000L, minute.coveredMillis)
        assertEquals(59, minute.sampleCount)
    }

    @Test fun `timestamp jitter at one and fifty Hz stays on stable cadence`() {
        fun capture(events: List<Long>): List<MotionMinute> {
            val engine = MotionAccumulator(SamplingPlan.choose(1000), Placement.AUTO)
            events.forEach { ms ->
                val value = sin(ms / 4_000.0) * if (ms in 30_000..42_000) .8 else .02
                engine.add(base + ms, value, value * .1, 9.81)
            }
            return engine.drain(base + 120_000)
        }
        val ideal = capture((0L..120_000L step 1_000L).toList())
        val oneHz = capture((0L..120L).map { it * 1_000L + when (it % 4) { 0L -> 3L; 1L -> 18L; 2L -> -12L; else -> 9L } })
        val tenHz = capture((0L..1_200L).map { i -> i * 100L + when (i % 3) { 0L -> 8L; 1L -> -11L; else -> 3L } })
        val fiftyHz = capture((0L..6_000L).map { i -> i * 20L + when (i % 3) { 0L -> 2L; 1L -> -3L; else -> 1L } })
        listOf(oneHz, tenHz, fiftyHz).forEach { jittered ->
            assertEquals(ideal.size, jittered.size)
            ideal.zip(jittered).forEach { (a,b) ->
                assertTrue(kotlin.math.abs(a.sampleCount - b.sampleCount) <= 2)
                assertTrue(kotlin.math.abs(a.coveredMillis - b.coveredMillis) <= 500)
                assertEquals(a.rms, b.rms, .01)
                assertTrue("active difference=${kotlin.math.abs(a.activeMillis - b.activeMillis)}", kotlin.math.abs(a.activeMillis - b.activeMillis) <= 2_000)
            }
        }
    }

    @Test fun `sensor cadence above one point five seconds is retained but not Deep eligible`() {
        val engine = MotionAccumulator(SamplingPlan.choose(100, 2_000_000), Placement.BED)
        for (ms in 0L..180_000L step 2_000L) engine.add(base + ms, 0.01, 0.0, 9.81)
        val rows = engine.drain(base + 180_000)
        assertTrue(rows.sumOf { it.coveredMillis } >= 175_000)
        assertTrue(rows.all { it.featureVersion == MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION })
        assertNull(result(rows, 3).baseline)
        assertEquals(0L, deepMinutes(result(rows, 3)))
    }

    @Test fun `v1 values never influence v2 nightly baseline`() {
        val v2 = (60 until 120).map { row(it, .002 + (it % 10) * .001) }
        val modernOnly = result(v2)
        for (legacyRms in listOf(0.0001, 0.9)) {
            val mixed = (0 until 60).map { row(it, legacyRms).copy(featureVersion = 1) } + v2
            val staged = result(mixed)
            assertEquals(modernOnly.baseline!!.p50, staged.baseline!!.p50, 1e-12)
            assertEquals(60, staged.baseline.bedMinutes)
            assertEquals(60 * MINUTE_MS, staged.firstMotionDelayMillis)
            assertTrue(staged.minutes.take(60).all { it.stage == SleepStage.LIGHT && !it.stagingMotionUsable })
            for (i in 60..66) assertEquals(SleepStage.LIGHT, stage(staged, i))
        }
    }

    @Test fun `legacy only has no baseline and preserves existing staged intervals`() {
        val legacy = rows().map { it.copy(featureVersion = 1) }
        val staged = result(legacy)
        assertNull(staged.baseline)
        assertEquals(0L, deepMinutes(staged))
        val existing = listOf(SleepStageInterval(base, base + 120 * MINUTE_MS, SleepStage.LIGHT))
        assertEquals(existing, preserveExistingStagesWithoutV2Evidence(staged, existing))
        val synced = session().copy(state = SyncState.SYNCED, revision = 7, stageIntervals = existing)
        val candidate = synced.copy(stageIntervals = preserveExistingStagesWithoutV2Evidence(staged, synced.stageIntervals))
        val merged = com.rsps1008.sleeptrace.sleep.mergeSleepSessions(listOf(synced), listOf(candidate)).single()
        assertEquals(7L, merged.revision)
        assertEquals(SyncState.SYNCED, merged.state)
        assertEquals(existing, merged.stageIntervals)
    }

    @Test fun `recording disabled still reconciles stored history and empty install can complete migration`() {
        // Capture preference is intentionally not an input to historical reconciliation policy.
        assertEquals(HistoricalReconcileAction.RECONCILE, historicalReconcileAction(configured = true, hasStoredSession = true))
        assertEquals(HistoricalReconcileAction.COMPLETE_EMPTY_MIGRATION, historicalReconcileAction(configured = false, hasStoredSession = false))
        assertEquals(HistoricalReconcileAction.DEFER_UNCONFIGURED_HISTORY, historicalReconcileAction(configured = false, hasStoredSession = true))
    }

    @Test fun `placement local evidence does not cross a feature version boundary`() {
        val legacy = (0 until 45).map { i -> row(i, .5, Placement.AUTO, if (i % 10 == 0) 1_000 else 0).copy(featureVersion = 1) }
        val current = (45 until 105).map { i -> row(i, .02, Placement.AUTO, if (i % 10 == 5) 1_000 else 0) }
        val isolated = AutomaticPlacement.resolve(current, emptyList()).associateBy { it.startMillis }
        val mixed = AutomaticPlacement.resolve(legacy + current, emptyList()).associateBy { it.startMillis }
        for (i in 75 until 105) assertEquals(isolated.getValue(base + i * MINUTE_MS).placement, mixed.getValue(base + i * MINUTE_MS).placement)
    }

    @Test fun `inclusive fifteen minute window confirms even when its last minute is above enter RMS`() {
        val use = listOf(UsageInterval(base, base + 5 * MINUTE_MS))
        val signal = rows().map { if (it.startMillis in (base + 30 * MINUTE_MS)..(base + 34 * MINUTE_MS)) row(((it.startMillis-base)/MINUTE_MS).toInt(), .006) else it }
        val staged = result(signal, usage = use)
        assertNull(staged.minutes[33].event)
        assertEquals("enter_stable_window", staged.minutes[34].event)
        assertEquals(SleepStage.DEEP, stage(staged, 34))
        assertTrue(staged.minutes.take(20).none { it.stage == SleepStage.DEEP })
    }

    @Test fun `five quiet minutes above enter threshold keep established Deep`() {
        val signal = rows().map { if (it.startMillis in (base+40*MINUTE_MS)..(base+44*MINUTE_MS)) row(((it.startMillis-base)/MINUTE_MS).toInt(), .006) else it }
        val staged = result(signal)
        assertTrue(staged.baseline!!.p50 < .006)
        for (i in 39..45) assertEquals(SleepStage.DEEP, stage(staged, i))
    }

    @Test fun `three ACTIVE minutes in five exit and remove the activity bridge`() {
        val staged = result(rows().map { if (((it.startMillis-base)/MINUTE_MS).toInt() in listOf(45,47,49)) it.copy(activeMillis=6_000, squaredDeltaTime=.3*.3*MINUTE_MS) else it })
        assertEquals(SleepStage.DEEP, stage(staged, 44))
        for (i in 45..49) assertEquals(SleepStage.LIGHT, stage(staged, i))
        assertEquals("exit_active_3_in_5", staged.minutes[49].event)
    }

    @Test fun `one or two turn-over minutes retain one Deep interval`() {
        val staged = result(rows().map { if (((it.startMillis-base)/MINUTE_MS).toInt() in 40..41) it.copy(activeMillis=6_000, squaredDeltaTime=.3*.3*MINUTE_MS) else it })
        for (i in 39..43) assertEquals(SleepStage.DEEP, stage(staged, i))
        assertEquals(1, staged.intervals.count { it.stage == SleepStage.DEEP })
    }

    @Test fun `sustained high rolling motion exits even if each minute is QUIET`() {
        val staged = result(rows().map { if (((it.startMillis-base)/MINUTE_MS).toInt() in 40..52) row(((it.startMillis-base)/MINUTE_MS).toInt(), .08) else it })
        assertEquals(SleepStage.LIGHT, stage(staged, 47))
        assertTrue(staged.minutes.any { it.event == "exit_sustained_rolling_motion" })
    }

    @Test fun `pre-session phone evidence resets guard without adding Awake duration`() {
        val use = listOf(UsageInterval(base - 4*MINUTE_MS, base - MINUTE_MS))
        val staged = result(rows(), usage=use)
        assertEquals(use, stageUsageFor(session(), use))
        assertEquals(base-MINUTE_MS, SleepStageEstimator.lastMeaningfulPhoneUseBefore(base, use))
        assertTrue(staged.minutes.take(15).none { it.stage == SleepStage.DEEP })
        assertEquals(0L, staged.intervals.filter { it.stage == SleepStage.AWAKE }.sumOf { it.endMillis-it.startMillis })
        assertEquals(0L, SleepUsageSnapshot.apply(session(), use, true).awakeMillis)
    }

    @Test fun `tablet stillness before later API onset is never backfilled Deep`() {
        val use = listOf(UsageInterval(base+60*MINUTE_MS, base+65*MINUTE_MS))
        val staged = result(rows(180), 180, use, evidenceStart=80)
        assertTrue(staged.minutes.take(100).none { it.stage == SleepStage.DEEP })
        for (i in 60..64) assertEquals(SleepStage.AWAKE, stage(staged,i))
    }

    @Test fun `short UNKNOWN placement retains Deep for five quiet minutes`() {
        val staged = result(rows().map { if (((it.startMillis-base)/MINUTE_MS).toInt() in 40..44) it.copy(placement=Placement.UNKNOWN) else it })
        for (i in 39..45) assertEquals(SleepStage.DEEP, stage(staged,i))
    }

    @Test fun `UNKNOWN longer than five minutes falls back to Light`() {
        val staged = result(rows().map { if (((it.startMillis-base)/MINUTE_MS).toInt() in 40..50) it.copy(placement=Placement.UNKNOWN) else it })
        assertEquals(SleepStage.DEEP, stage(staged,44))
        assertEquals(SleepStage.LIGHT, stage(staged,45))
        assertEquals("exit_unknown_timeout", staged.minutes[45].event)
    }

    @Test fun `UNKNOWN or BEDSIDE alone never establish Deep`() {
        for (placement in listOf(Placement.UNKNOWN,Placement.BEDSIDE))
            assertEquals(0L, deepMinutes(result(rows().map { it.copy(placement=placement) })))
    }

    @Test fun `ten minute data gap cannot extend or receive backfilled Deep`() {
        val staged = result(rows().filterNot { ((it.startMillis-base)/MINUTE_MS).toInt() in 40..49 })
        for (i in 40..49) assertEquals(SleepStage.LIGHT, stage(staged,i))
        assertEquals("exit_missing_motion", staged.minutes[40].event)
    }

    @Test fun `flat signal is bounded and never becomes an entire Deep night`() {
        val staged = result((0 until 180).map { row(it,.002) },180)
        assertTrue(staged.baseline!!.narrowDistribution)
        assertTrue(deepMinutes(staged) <= 180*.35)
        assertTrue(staged.intervals.any { it.stage == SleepStage.LIGHT })
        assertTrue(staged.intervals.count { it.stage == SleepStage.DEEP } <= 1)
    }

    @Test fun `partial session endpoints remain clipped and coverage cannot exceed one`() {
        val start = base + 37_000
        val end = base + 60 * MINUTE_MS + 22_000
        val partialSession = session().copy(startMillis = start, endMillis = end)
        val rows = (0..60).map { i -> MotionMinute(base + i * MINUTE_MS, MINUTE_MS, 0, .01 * .01 * MINUTE_MS, 60, Placement.BED) }
        val result = SleepStageEstimator.analyze(partialSession, rows,
            emptyList(), listOf(UsageInterval(start + 30_000, start + 90_000)),
            listOf(SleepSegment(start, end, 95)), schedule)
        assertTrue(result.intervals.all { it.startMillis >= start && it.endMillis <= end && it.endMillis > it.startMillis })
        assertTrue(result.motionCoverageRatio in 0.0..1.0)
        assertEquals(0.0, result.minutes.first().rollingMedianRms ?: 0.0, 0.0)
        assertEquals(base + 18 * MINUTE_MS, result.minutes.first { it.stagingMotionUsable }.startMillis)
    }

    @Test fun `safety cap removes weaker earlier run before stronger later run`() {
        val rows = (0 until 180).map { i ->
            when {
                i in 70..74 -> row(i, .00204).copy(activeMillis = 6_000)
                i in 75..84 -> row(i, .00205)
                i < 70 -> row(i, .00204)
                else -> row(i, .002)
            }
        }
        val staged = result(rows, 180)
        assertTrue(staged.baseline!!.narrowDistribution)
        assertTrue(staged.minutes.any { it.event == "safety_cap_low_differentiation" })
        assertEquals(SleepStage.LIGHT, stage(staged, 30))
        assertEquals(SleepStage.DEEP, stage(staged, 150))
    }

    @Test fun `Google confidence and light do not create Deep without usable local BED evidence`() {
        val staged = SleepStageEstimator.analyze(session(),rows().map { it.copy(placement=Placement.UNKNOWN) },
            listOf(ClassificationSample(base,100,0,0)),emptyList(),emptyList(),schedule)
        assertEquals(0L, deepMinutes(staged))
        val noEvidence = SleepStageEstimator.analyze(session(),rows(),emptyList(),emptyList(),emptyList(),schedule)
        assertEquals(0L, deepMinutes(noEvidence))
    }

    @Test fun `Health Connect retains stage constants stable client ID and revised version`() {
        val use=UsageInterval(base+60*MINUTE_MS,base+65*MINUTE_MS)
        val staged=result(rows(),usage=listOf(use))
        val accepted=session().copy(stageIntervals=staged.intervals,awakeIntervals=listOf(use),awakeMillis=5*MINUTE_MS,revision=7)
        val record=toHealthRecord(accepted)
        assertEquals("calibration",record.metadata.clientRecordId)
        assertEquals(7L,record.metadata.clientRecordVersion)
        assertEquals(setOf(SleepSessionRecord.STAGE_TYPE_LIGHT,SleepSessionRecord.STAGE_TYPE_DEEP,SleepSessionRecord.STAGE_TYPE_AWAKE),record.stages.map { it.stage }.toSet())
        val changed=accepted.copy(revision=8)
        assertEquals(record.metadata.clientRecordId,toHealthRecord(changed).metadata.clientRecordId)
        assertEquals(8L,toHealthRecord(changed).metadata.clientRecordVersion)
    }

    @Test fun `synthetic real night improves old three minute result without forcing a ratio`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/staging/real_night_style.csv")).bufferedReader().useLines { lines ->
            lines.drop(1).map { line ->
                val v=line.split(','); val cover=(v[1].toDouble()*1000).toLong(); val rms=v[3].toDouble()
                MotionMinute(base+v[0].toLong()*MINUTE_MS,cover,(v[2].toDouble()*1000).toLong(),rms*rms*cover,60,Placement.valueOf(v[4]))
            }.toList()
        }
        val staged=result(fixture,329)
        val old=legacyDeepMinutes(fixture,329)
        val new=deepMinutes(staged)
        println("Synthetic real-night regression: legacy=$old minutes, calibrated=$new minutes; not physiological truth")
        assertEquals(295,fixture.count { it.level != MotionLevel.UNKNOWN })
        assertEquals(146,fixture.count { it.placement==Placement.BED })
        assertEquals(3L,old)
        assertTrue("Deep=$new",new>30)
        assertTrue(new<329*.60)
        assertEquals(34*MINUTE_MS,staged.firstMotionDelayMillis)
        assertEquals(295.0/329,staged.motionCoverageRatio,.00001)
        for(i in 0..33) assertEquals(SleepStage.LIGHT,stage(staged,i))
    }

    @Test fun `synthetic night legacy and mixed versions build baseline only from v2`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/staging/real_night_style.csv")).bufferedReader().useLines { lines ->
            lines.drop(1).map { line ->
                val v=line.split(','); val cover=(v[1].toDouble()*1000).toLong(); val rms=v[3].toDouble()
                MotionMinute(base+v[0].toLong()*MINUTE_MS,cover,(v[2].toDouble()*1000).toLong(),rms*rms*cover,60,Placement.valueOf(v[4]))
            }.toList()
        }
        val allLegacy = fixture.map { it.copy(featureVersion = 1) }
        assertNull(result(allLegacy, 329).baseline)
        assertEquals(0L, deepMinutes(result(allLegacy, 329)))
        val mixed = fixture.map { if ((it.startMillis - base) / MINUTE_MS < 133) it.copy(featureVersion = 1) else it }
        val mixedResult = result(mixed, 329)
        val v2OnlyResult = result(mixed.filter { it.featureVersion == 2 }, 329)
        assertEquals(v2OnlyResult.baseline!!.p50, mixedResult.baseline!!.p50, 1e-12)
        assertEquals(v2OnlyResult.baseline.bedMinutes, mixedResult.baseline.bedMinutes)
        for (i in 133..139) assertEquals(SleepStage.LIGHT, stage(mixedResult, i))
        assertTrue(deepMinutes(mixedResult) < deepMinutes(result(fixture, 329)))
    }

    /** Frozen v1 rule, retained only to measure the reported failure on synthetic input. */
    private fun legacyDeepMinutes(rows: List<MotionMinute>, count:Int):Long {
        val sorted=rows.filter { it.placement==Placement.BED && it.level!=MotionLevel.UNKNOWN }.map { it.rms }.sorted()
        val position=(sorted.size-1)*.35; val lower=position.toInt(); val mix=position-lower
        val threshold=sorted[lower]*(1-mix)+sorted[minOf(sorted.lastIndex,lower+1)]*mix
        val map=rows.associateBy { ((it.startMillis-base)/MINUTE_MS).toInt() }
        val candidates=(0 until count).map { i -> map[i]?.let { it.placement==Placement.BED && it.level==MotionLevel.QUIET && it.activeMillis.toDouble()/it.coveredMillis<.02 && it.rms<=threshold }==true }
        val deepMinutes=BooleanArray(count); var deep=false; val bridge=mutableListOf<Int>()
        fun close(){bridge.forEach { deepMinutes[it]=false };bridge.clear()}
        for(i in 0 until count){
            if(map[i]?.placement!=Placement.BED || map[i]?.level==MotionLevel.UNKNOWN){close();deep=false;continue}
            if(candidates[i]){
                if(!deep && i>=15 && (i-15 until i).all { map[it]?.placement==Placement.BED && map[it]?.level!=MotionLevel.UNKNOWN } && (i-15 until i).count { candidates[it] }>=10)deep=true
                if(deep){bridge.clear();deepMinutes[i]=true}else close()
            }else if(deep){bridge.add(i);deepMinutes[i]=true;if(bridge.size>=3){close();deep=false}}
        }
        close()
        return deepMinutes.count { it }.toLong()
    }
}
