package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import org.junit.Assert.*
import org.junit.Test

/** Opt-in comparison. The reference session is a staging container, NOT a Google event. */
class SameNightReplayTest {
    private val out = File(requireNotNull(System.getProperty("offlineReplayDir")))
    private val zone = ZoneId.of("Asia/Taipei")
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private fun time(s: String) = LocalDateTime.parse(s, format).atZone(zone).toInstant().toEpochMilli()
    private fun readRows(file: File): List<Map<String,String>> {
        val lines = file.readLines()
        val keys = lines.first().split('\t')
        return lines.drop(1).filter(String::isNotBlank).map {
            val values=it.split('\t')
            require(values.size==keys.size) { "Truncated CSV intermediate" }
            keys.zip(values).toMap()
        }
    }
    private val rows by lazy { readRows(File(out,"input.tsv")) }
    private fun motion(r: Map<String,String>): MotionMinute {
        val covered = (r.getValue("covered_seconds").toDouble() * 1000).roundToLong()
        return MotionMinute(time(r.getValue("timestamp_local")), covered,
            (r.getValue("active_seconds").toDouble() * 1000).roundToLong(),
            r.getValue("delta_rms_m_s2").toDouble().let { it * it * covered },
            r.getValue("sample_count").toInt(), Placement.AUTO, r.getValue("feature_version").toInt(),
            maxDelta=r.getValue("max_delta_m_s2").toDouble(), movementEvents=r.getValue("movement_events").toInt(),
            longestActiveMillis=r.getValue("longest_active_ms").toLong(), quietTailMillis=r.getValue("quiet_tail_ms").toLong(),
            longestGapMillis=r.getValue("longest_gap_ms").toLong(), postureDelta=r.getValue("posture_delta_m_s2").toDoubleOrNull(),
            recordingId=r.getValue("recording_id").toLong())
    }
    private val minutes by lazy { rows.map(::motion) }
    private val reference by lazy { File(out, "reference.tsv").readLines().drop(1).map {
        val p=it.split('\t'); SleepStageInterval(p[0].toLong(),p[1].toLong(),SleepStage.valueOf(p[2]))
    } }
    private val start get() = reference.first().startMillis
    private val end get() = reference.last().endMillis
    private val schedule = SleepSchedule(0,0)
    // Exported blockers include a +/-2 min margin, not exact screen-use intervals.
    // Keep the whole marked interval as a conservative exclusion in this experiment.
    private val usage by lazy { rows.filter { "PHONE_IN_USE" in it.getValue("coupling_current_blocker") }.map {
        val t=time(it.getValue("timestamp_local")); UsageInterval(t,t+MINUTE_MS)
    } }
    private fun session() = SleepSession("paired-staging-container",start,end,50,0,SyncState.PENDING,"reference boundaries for staging comparison")
    private val segments get() = listOf(SleepSegment(start,end,50))

    @Test fun compareSameNight() {
        assertEquals("Re-baseline this paired experiment when the production rule changes",12,SleepStageEstimator.ALGORITHM_VERSION)
        val originalResolved = AutomaticPlacement.resolve(minutes,usage,schedule)
        val original = SleepStageEstimator.analyze(session(),originalResolved,emptyList(),usage,segments,schedule)
        // Before phone-use margins begin, raw CSV coupling must be reproducible exactly.
        val firstUse=usage.minOfOrNull { it.startMillis } ?: Long.MAX_VALUE
        originalResolved.zip(rows).filter { it.first.startMillis < firstUse - 2*MINUTE_MS }.forEach { (m,r) ->
            assertEquals(r.getValue("coupling_state"),m.coupling!!.state.name)
            assertEquals(r.getValue("coupling_age_ms").toLongOrNull(),m.coupling!!.ageMillis)
        }
        val profiles=buildList {
            add(OfflineThresholdReplayTest.Profile("baseline","baseline"))
            add(OfflineThresholdReplayTest.Profile("previous_C","anchor",movements=2,peak=2.5,entryPercentile=65,minimumBout=5,exitPercentile=65,exitWindows=2))
            for (movements in listOf(2,3)) for (hold in listOf(45,60,75,90))
                for (ep in listOf(35,50,65,75)) for (xp in listOf(50,65,70,80))
                    for (wait in listOf(1,2,3)) for (bout in listOf(5,10)) {
                        if (xp<ep) continue
                        add(OfflineThresholdReplayTest.Profile("m${movements}_h${hold}_p${ep}_x${xp}_w${wait}_b${bout}",
                            "grid",movements=movements,hold=hold,entryPercentile=ep,exitPercentile=xp,exitWindows=wait,minimumBout=bout))
                    }
        }
        val localProfiles=buildList {
            for (window in listOf(10,15)) for (hold in listOf(45,60,75))
                for (ep in listOf(35,50,65,75)) for (xp in listOf(50,65,70,80))
                    for (wait in listOf(1,2)) {
                        if(xp<ep) continue
                        add(OfflineThresholdReplayTest.Profile("local_h${hold}_p${ep}_x${xp}_w${wait}_entry${window}",
                            "local",hold=hold,entryWindow=window,entryPercentile=ep,exitPercentile=xp,exitWindows=wait,minimumBout=5))
                    }
        }
        val refinement=buildList {
            for(local in listOf(25,30,40,45)) for(hold in listOf(55,60,65,70))
                for(ep in listOf(30,40,45)) for(xp in listOf(60,65,70))
                    for(wait in listOf(1,2)) for(bout in listOf(5,7)) {
                        add(OfflineThresholdReplayTest.Profile("refine_h${hold}_p${ep}_x${xp}_w${wait}_b${bout}_local$local",
                            "refine",hold=hold,entryWindow=10,entryPercentile=ep,exitPercentile=xp,exitWindows=wait,minimumBout=bout) to local)
                    }
        }
        val experiments=profiles.map { it to 0 } + listOf(30,60,90).flatMap { n -> localProfiles.map { it.copy(id="${it.id}_local$n") to n } } + refinement
        val summary=mutableListOf("id,movements,hold,entry_p,exit_p,exit_wait,min_bout,entry_window,local_baseline_min,deep_min,bouts,longest_min,overlap_min,false_deep_min,missed_deep_min,deep_f1,stage_agreement")
        val intervals=mutableListOf("profile,start_ms,end_ms,stage")
        val diagnostics=mutableListOf("profile,time_ms,stage,reason,action,can_stage,coupling,rms")
        fun save(id:String,result:SleepStageEstimator.StagingResult) {
            result.intervals.forEach { intervals += "$id,${it.startMillis},${it.endMillis},${it.stage}" }
        }
        save("production",original)
        val comparable=original.minutes.filter { d -> minutes.any { it.startMillis==d.startMillis && it.coveredMillis>=45_000 } && d.phoneUseMillis==0L }
        val expectedDeep=comparable.count { d -> reference.any { it.stage==SleepStage.DEEP && d.startMillis+MINUTE_MS/2 in it.startMillis until it.endMillis } }
        for ((p,local) in experiments) {
            p.apply()
            OfflineSleepStageEstimator.LOCAL_BASELINE_MINUTES=local
            val resolved=OfflineAutomaticPlacement.resolve(minutes,usage,schedule)
            val result=OfflineSleepStageEstimator.analyze(session(),resolved,emptyList(),usage,segments,schedule)
            if(p.id=="baseline") assertEquals(original.intervals,result.intervals)
            assertEquals(end-start,result.intervals.sumOf { it.endMillis-it.startMillis })
            assertFalse(result.minutes.any { it.finalStage==SleepStage.DEEP && !it.canStage })
            val deep=result.intervals.filter { it.stage==SleepStage.DEEP }
            assertTrue(deep.all { it.endMillis-it.startMillis>=p.minimumBout*MINUTE_MS })
            val predicted=comparable.count { d -> deep.any { d.startMillis+MINUTE_MS/2 in it.startMillis until it.endMillis } }
            val tp=comparable.count { d ->
                val t=d.startMillis+MINUTE_MS/2
                deep.any { t in it.startMillis until it.endMillis } && reference.any { it.stage==SleepStage.DEEP && t in it.startMillis until it.endMillis }
            }
            val total=deep.sumOf { it.endMillis-it.startMillis }/MINUTE_MS.toDouble()
            val longest=(deep.maxOfOrNull { it.endMillis-it.startMillis }?:0)/MINUTE_MS.toDouble()
            val f1=2.0*tp/(predicted+expectedDeep).coerceAtLeast(1)
            val agreement=1.0-(predicted+expectedDeep-2*tp).toDouble()/comparable.size
            summary += listOf(p.id,p.movements,p.hold,p.entryPercentile,p.exitPercentile,p.exitWindows,p.minimumBout,p.entryWindow,local,total,deep.size,longest,tp,predicted-tp,expectedDeep-tp,f1,agreement).joinToString(",")
            result.intervals.forEach { intervals += "${p.id},${it.startMillis},${it.endMillis},${it.stage}" }
            if(p.id in setOf("baseline","previous_C") || f1>0.40) result.minutes.forEach {
                diagnostics += "${p.id},${it.startMillis},${it.finalStage},${it.primaryReason},${it.action},${it.canStage},${it.couplingState},${it.motion?.rms}"
            }
            val still=minutes.map { it.copy(activeMillis=0,squaredDeltaTime=.006*.006*it.coveredMillis,
                maxDelta=.012,movementEvents=0,longestActiveMillis=0,postureDelta=0.0) }
            assertTrue("${p.id}: stillness cannot establish BED",
                OfflineAutomaticPlacement.resolve(still,emptyList(),schedule).none { it.placement==Placement.BED })
            val allUsed=listOf(UsageInterval(start,end))
            val used=OfflineAutomaticPlacement.resolve(minutes,allUsed,schedule)
            assertEquals("${p.id}: phone use",0L,OfflineSleepStageEstimator.analyze(session(),used,emptyList(),allUsed,segments,schedule).durations.deep)
            val broken=minutes.mapIndexed { i,m -> m.copy(recordingId=i.toLong()) }
            assertTrue("${p.id}: recording boundaries",OfflineAutomaticPlacement.resolve(broken,emptyList(),schedule).none { it.placement==Placement.BED })
            val gaps=minutes.map { it.copy(longestGapMillis=3_000) }
            assertTrue("${p.id}: gaps",OfflineAutomaticPlacement.resolve(gaps,emptyList(),schedule).none { it.placement==Placement.BED })
            assertEquals("${p.id}: no Google evidence",0L,OfflineSleepStageEstimator.analyze(session(),resolved,emptyList(),usage,emptyList(),schedule).durations.deep)
        }
        File(out,"paired-summary.csv").writeText(summary.joinToString("\n"))
        File(out,"paired-intervals.csv").writeText(intervals.joinToString("\n"))
        File(out,"paired-diagnostics.csv").writeText(diagnostics.joinToString("\n"))
        // A separate hypothesis exposes missing Google evidence instead of passing the
        // Xiaomi segment into candidate selection and claiming an end-to-end replay.
        val firstEvent=rows.first().getValue("first_event_ms").toLong()
        val candidates=MotionSleepEstimator.estimate(originalResolved,usage,schedule,minutes.last().startMillis+24*60*MINUTE_MS)
        val assumedClassification=listOf(ClassificationSample(firstEvent,90,0,0))
        val confirmed=candidates.mapNotNull { confirmMotionCandidateOnset(it,emptyList(),assumedClassification) }
            .map { extendConfirmedMotionCandidate(it,originalResolved,assumedClassification,usage) }
        assertTrue(candidates.all { confirmMotionCandidateOnset(it,emptyList(),emptyList())==null })
        File(out,"candidate-scenario.txt").writeText("Hypothesis only: high classification at first sensor event; conservative CSV phone-use margin.\n"+
            "Without Google evidence: no confirmed sessions\n"+confirmed.joinToString("\n") { "${it.startMillis},${it.endMillis}" })
        File(out,"paired-verification.txt").writeText("Generated baseline equals current production in the fixed reference window.\n"+
            "CSV coupling before usage margin matches production. No exported sleep session exists.\n"+
            "${experiments.size} profiles: duration conservation, eligible Deep, minimum bout, stillness, phone use, recording boundaries, gaps and missing Google evidence controls PASS.\n"+
            "Reference is read from image pixels, not physiological ground truth. Classification at capture trigger is a scenario only.\n")
        println("Compared ${experiments.size} variants over ${comparable.size} observed minutes; reference Deep=$expectedDeep min")
    }

    @Test fun previousNightRegression() {
        val priorRows=readRows(File(out,"previous.tsv"))
        val parts=priorRows.flatMap { r -> r.getValue("exact_computed_parts").split(';').filter(String::isNotBlank).map {
            val p=it.split(':');SleepStageInterval(p[0].toLong(),p[1].toLong(),SleepStage.valueOf(p[2]))
        } }
        require(parts.isNotEmpty() && parts.none { it.stage==SleepStage.AWAKE })
        val priorSession=SleepSession("previous-night",parts.first().startMillis,parts.last().endMillis,50,0,SyncState.PENDING,"exported session")
        val input=priorRows.map(::motion).filter { it.startMillis<priorSession.endMillis }
        val evidence=listOf(SleepSegment(priorSession.startMillis,priorSession.endMillis,50))
        val resolved=AutomaticPlacement.resolve(input,emptyList(),schedule)
        val baseline=SleepStageEstimator.analyze(priorSession,resolved,emptyList(),emptyList(),evidence,schedule)
        // Compare every exported sub-interval, not just total minutes.
        parts.forEach { part ->
            assertTrue(baseline.intervals.any { it.stage==part.stage && it.startMillis<=part.startMillis && it.endMillis>=part.endMillis })
        }
        OfflineThresholdReplayTest.Profile("selected",hold=65,entryWindow=10,entryPercentile=45,
            exitPercentile=60,exitWindows=1,minimumBout=7).apply()
        OfflineSleepStageEstimator.LOCAL_BASELINE_MINUTES=30
        val candidate=OfflineSleepStageEstimator.analyze(priorSession,
            OfflineAutomaticPlacement.resolve(input,emptyList(),schedule),emptyList(),emptyList(),evidence,schedule)
        assertEquals(priorSession.endMillis-priorSession.startMillis,candidate.intervals.sumOf { it.endMillis-it.startMillis })
        assertFalse(candidate.minutes.any { it.finalStage==SleepStage.DEEP && !it.canStage })
        val deep=candidate.intervals.filter { it.stage==SleepStage.DEEP }
        assertTrue(deep.all { it.endMillis-it.startMillis>=7*MINUTE_MS })
        File(out,"previous-night.txt").writeText("Previous exported full interval baseline parity PASS. No paired reference for this night.\n"+
            "baseline Deep=${baseline.durations.deep/60000.0}, bouts=${baseline.intervals.count { it.stage==SleepStage.DEEP }}\n"+
            "candidate Deep=${candidate.durations.deep/60000.0}, bouts=${deep.size}, longest=${(deep.maxOfOrNull { it.endMillis-it.startMillis }?:0)/60000.0}\n")
        File(out,"previous-intervals.csv").writeText("profile,start_ms,end_ms,stage\n"+
            baseline.intervals.joinToString("\n") { "baseline,${it.startMillis},${it.endMillis},${it.stage}" }+"\n"+
            candidate.intervals.joinToString("\n") { "selected,${it.startMillis},${it.endMillis},${it.stage}" })
    }
}
