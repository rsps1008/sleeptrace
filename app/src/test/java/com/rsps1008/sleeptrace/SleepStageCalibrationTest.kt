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
            assertEquals(slow, fast)
            assertTrue(fast.all { it.sampleCount == 60 && it.featureVersion == 2 })
            slow.zip(fast).forEach { (a,b) ->
                assertEquals(a.rms, b.rms, .000001)
                assertEquals(a.activeMillis, b.activeMillis)
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
        assertEquals(33*MINUTE_MS,staged.firstMotionDelayMillis)
        assertEquals(295.0/329,staged.motionCoverageRatio,.00001)
        for(i in 0..33) assertEquals(SleepStage.LIGHT,stage(staged,i))
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
