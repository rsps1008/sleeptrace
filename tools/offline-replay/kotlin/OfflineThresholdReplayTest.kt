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

/** Explicit offline experiment. This file is excluded from normal test source sets. */
class OfflineThresholdReplayTest {
    private val out = File(requireNotNull(System.getProperty("offlineReplayDir")))
    private val zone = ZoneId.of("Asia/Taipei")
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val rows: List<Map<String, String>> by lazy {
        val lines = File(out, "input.tsv").readLines()
        val header = lines.first().split('\t')
        lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val values = line.split('\t')
            require(values.size == header.size)
            header.zip(values).toMap()
        }
    }
    private fun Map<String, String>.s(key: String) = getValue(key)
    private fun Map<String, String>.d(key: String) = s(key).toDouble()
    private fun Map<String, String>.l(key: String) = s(key).toLong()
    private fun Map<String, String>.time() = LocalDateTime.parse(s("timestamp_local"), timestampFormat).atZone(zone).toInstant().toEpochMilli()
    private fun Map<String, String>.motion(): MotionMinute {
        val covered = (d("covered_seconds") * 1000).roundToLong()
        return MotionMinute(
            startMillis = time(), coveredMillis = covered,
            activeMillis = (d("active_seconds") * 1000).roundToLong(),
            squaredDeltaTime = d("delta_rms_m_s2").let { it * it * covered },
            sampleCount = s("sample_count").toInt(), placement = Placement.AUTO,
            featureVersion = s("feature_version").toInt(), maxDelta = d("max_delta_m_s2"),
            movementEvents = s("movement_events").toInt(), longestActiveMillis = l("longest_active_ms"),
            quietTailMillis = l("quiet_tail_ms"), longestGapMillis = l("longest_gap_ms"),
            postureDelta = d("posture_delta_m_s2"), recordingId = l("recording_id"),
        )
    }
    private val expected: List<SleepStageInterval> by lazy {
        val parts = rows.filter { it.s("session_id").isNotBlank() }.flatMap { r ->
            r.s("exact_computed_parts").split(';').filter { it.isNotBlank() }.map { part ->
                val (start, end, stage) = part.split(':')
                SleepStageInterval(start.toLong(), end.toLong(), SleepStage.valueOf(stage))
            }
        }
        val merged = mutableListOf<SleepStageInterval>()
        parts.forEach { part ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.endMillis == part.startMillis && previous.stage == part.stage) {
                merged[merged.lastIndex] = previous.copy(endMillis = part.endMillis)
            } else merged += part
        }
        merged
    }
    private val start get() = expected.first().startMillis
    private val end get() = expected.last().endMillis
    private val schedule = SleepSchedule(0, 0)
    private val minutes get() = rows.filter { it.time() < end }.map { it.motion() }
    private fun session() = SleepSession(id = "offline-fixed-session", startMillis = start, endMillis = end,
        confidence = 68, awakeMillis = 0, state = SyncState.PENDING, reason = "offline CSV reconstruction")
    private val segments get() = listOf(SleepSegment(start, end, 68))

    data class Profile(
        val id: String, val group: String = "single", val movements: Int = 3,
        val noise: Double = 3.0, val renewNoise: Double = -1.0, val hold: Int = 45,
        val peak: Double = 1.5, val composite: Boolean = false, val entryWindow: Int = 15,
        val entryPercentile: Int = 50, val minimumBout: Int = 10,
        val exitPercentile: Int = 70, val exitWindows: Int = 3, val exitEvents: Int = 8,
        val exitLookback: Int = 5,
    ) {
        fun apply() {
            OfflineCouplingPolicy.MIN_MOVEMENTS = movements
            OfflineCouplingPolicy.NOISE_MULTIPLIER = noise
            OfflineCouplingPolicy.RENEW_NOISE_MULTIPLIER = renewNoise
            OfflineCouplingPolicy.HOLD_MILLIS = hold * MINUTE_MS
            OfflineCouplingPolicy.PEAK_HANDLING_DELTA = peak
            OfflineCouplingPolicy.COMPOSITE_PEAK_RESET = composite
            OfflineSleepStageEstimator.DEEP_WINDOW_MINUTES = entryWindow
            OfflineSleepStageEstimator.ENTRY_PERCENTILE = entryPercentile
            OfflineSleepStageEstimator.TEN_HZ_MIN_DEEP_RUN_MILLIS = minimumBout * MINUTE_MS
            OfflineSleepStageEstimator.EXIT_PERCENTILE = exitPercentile
            OfflineSleepStageEstimator.EXIT_HIGH_WINDOWS = exitWindows
            OfflineSleepStageEstimator.EXIT_DENSE_EVENTS = exitEvents
            OfflineSleepStageEstimator.EXIT_RMS_LOOKBACK_MINUTES = exitLookback
        }
        fun csv() = listOf(id, group, movements, noise, renewNoise, hold, peak, composite, entryWindow,
            entryPercentile, minimumBout, exitPercentile, exitWindows, exitEvents, exitLookback).joinToString(",")
    }

    private fun roundTwoProfiles(): List<Profile> {
        val a = Profile("anchor_A", "anchor", movements=2, entryPercentile=65)
        val b = Profile("anchor_B", "anchor", movements=2, peak=3.0, entryWindow=10,
            entryPercentile=65, minimumBout=5, exitPercentile=50, exitWindows=1)
        val candidates = buildList {
            add(Profile("baseline", "baseline")); add(a); add(b)
            // One-factor neighborhoods make the effects interpretable before combinations.
            for (base in listOf(a,b)) {
                val name = if (base==a) "A" else "B"
                for (v in listOf(55,60,62,65,67,70)) add(base.copy(id="${name}_entryP$v",group="local",entryPercentile=v))
                for (v in listOf(50,55,60,62,65,67,70,75)) add(base.copy(id="${name}_exitP$v",group="local",exitPercentile=v))
                for (v in listOf(10,12,15)) add(base.copy(id="${name}_window$v",group="local",entryWindow=v))
                for (v in listOf(5,7,10)) add(base.copy(id="${name}_bout$v",group="local",minimumBout=v))
                for (v in listOf(1,2,3)) add(base.copy(id="${name}_wait$v",group="local",exitWindows=v))
                for (v in listOf(3,5,7)) add(base.copy(id="${name}_rms$v",group="local",exitLookback=v))
                for (v in listOf(1.5,2.0,2.5,3.0)) add(base.copy(id="${name}_peak$v",group="local",peak=v))
                for (v in listOf(2.5,2.75,3.0)) add(base.copy(id="${name}_noise$v",group="local",noise=v))
                for (v in listOf(35,45,55)) add(base.copy(id="${name}_hold$v",group="local",hold=v))
                for (v in listOf(2.5,2.75)) add(base.copy(id="${name}_renew$v",group="local",renewNoise=v))
            }
            // Refine A while preserving its stricter handling reset and RMS hysteresis.
            for (ep in listOf(55,60,65,70)) for (xp in listOf(60,65,70,75)) if (xp>ep)
                for (window in listOf(10,12,15)) for (wait in listOf(1,2,3))
                    for (bout in listOf(5,7,10)) for (lookback in listOf(3,5)) {
                        add(a.copy(id="A_p${ep}_x${xp}_w${window}_e${wait}_b${bout}_r${lookback}",
                            group="A_grid",entryPercentile=ep,exitPercentile=xp,entryWindow=window,
                            exitWindows=wait,minimumBout=bout,exitLookback=lookback))
                    }
            // Refine B at intermediate exit thresholds, avoiding arbitrary duration caps.
            for (peak in listOf(2.0,2.5,3.0)) for (xp in listOf(50,55,60,65))
                for (window in listOf(10,12,15)) for (wait in listOf(1,2))
                    for (bout in listOf(5,7)) for (lookback in listOf(3,5)) {
                        add(b.copy(id="B_peak${peak}_x${xp}_w${window}_e${wait}_b${bout}_r${lookback}",
                            group="B_grid",peak=peak,exitPercentile=xp,entryWindow=window,
                            exitWindows=wait,minimumBout=bout,exitLookback=lookback))
                    }
            // Bridge supports between the anchors with a small set of noise/renewal changes.
            for (noise in listOf(2.5,2.75,3.0)) for (peak in listOf(1.5,2.0,2.5,3.0))
                for (xp in listOf(60,65,70)) for (lookback in listOf(3,5)) {
                    add(a.copy(id="bridge_n${noise}_peak${peak}_x${xp}_r${lookback}",group="bridge",
                        noise=noise,peak=peak,exitPercentile=xp,minimumBout=7,exitLookback=lookback,exitWindows=2))
                }
            // Local sensitivity around the newly observed 8-bout / shorter-longest-run case.
            // Six- and seven-minute cutoffs reveal whether apparent detail sits on the filter edge.
            for (peak in listOf(2.25,2.5,2.75)) for (xp in listOf(60,62,65,67,70))
                for (window in listOf(12,13,14,15)) for (wait in listOf(1,2))
                    for (bout in listOf(5,6,7)) {
                        add(a.copy(id="C_peak${peak}_x${xp}_w${window}_e${wait}_b${bout}",
                            group="C_neighbors",peak=peak,exitPercentile=xp,entryWindow=window,
                            exitWindows=wait,minimumBout=bout))
                    }
        }
        // Stable order retains baseline/anchors; every round-two parameter tuple runs once.
        return candidates.distinctBy { it.copy(id="",group="") }
    }
    private fun profiles(): List<Profile> = buildList {
        add(Profile("baseline", "baseline"))
        add(Profile("movements2", movements = 2))
        for (v in listOf(2.5, 2.0)) add(Profile("noise$v", noise = v))
        for (v in listOf(2.5, 2.0)) add(Profile("renew$v", renewNoise = v))
        for (v in listOf(60, 90)) add(Profile("hold$v", hold = v))
        for (v in listOf(2.5, 3.0)) add(Profile("peak$v", peak = v))
        add(Profile("composite_peak", composite = true))
        for (v in listOf(12, 10)) add(Profile("entry$v", entryWindow = v))
        for (v in listOf(65, 70)) add(Profile("entryP$v", entryPercentile = v))
        for (v in listOf(5, 2)) add(Profile("bout$v", minimumBout = v))
        for (v in listOf(65, 50)) add(Profile("exitP$v", exitPercentile = v))
        for (v in listOf(2, 1)) add(Profile("exitWindows$v", exitWindows = v))
        for (v in listOf(6, 4)) add(Profile("exitEvents$v", exitEvents = v))
        // Bounded factorial study; no phase templates, target ratio or target bout count.
        for (n in listOf(3, 2)) for (noise in listOf(3.0, 2.5)) for (peak in listOf(1.5, 3.0))
            for (hold in listOf(45, 60)) for (entry in listOf(15, 10)) for (ep in listOf(50, 65))
                for (exit in listOf(70, 65)) {
                    add(Profile("grid_n${n}_noise${noise}_peak${peak}_h${hold}_w${entry}_p${ep}_x${exit}",
                        "grid", movements=n, noise=noise, peak=peak, hold=hold,
                        entryWindow=entry, entryPercentile=ep, exitPercentile=exit))
                }
        for (n in listOf(3, 2)) for (renew in listOf(-1.0, 2.5)) for (entry in listOf(15, 10))
            for (exit in listOf(70, 65)) add(Profile("composite_n${n}_r${renew}_w${entry}_x${exit}",
                "composite", movements=n, renewNoise=renew, composite=true,
                entryWindow=entry, exitPercentile=exit))
        // Follow-up sensitivity around a small-change candidate and three broader controls.
        // Include 2-minute bouts / P50 exits as stress cases, not release recommendations.
        val bases = listOf(
            Profile("pair", movements=2, entryPercentile=65),
            Profile("noise", noise=2.5, entryPercentile=65),
            Profile("peak", movements=2, peak=3.0, entryPercentile=65),
            Profile("composite", movements=2, composite=true, entryPercentile=65),
        )
        for (base in bases) for (bout in listOf(10, 5, 2)) for (exit in listOf(70, 65, 50))
            for (wait in listOf(3, 2, 1)) for (entry in listOf(15, 10)) {
                add(base.copy(id="focused_${base.id}_b${bout}_x${exit}_e${wait}_w${entry}",
                    group="focused", minimumBout=bout, exitPercentile=exit, exitWindows=wait,
                    entryWindow=entry))
            }
    }

    @Test fun replayAndSweep() {
        val originalResolved = AutomaticPlacement.resolve(minutes, emptyList(), schedule)
        val original = SleepStageEstimator.analyze(session(), originalResolved, emptyList(), emptyList(), segments, schedule)
        assertEquals("Production replay must match all exported interval boundaries", expected, original.intervals)
        val exportedRows = rows.filter { it.s("session_id").isNotBlank() }
        assertEquals(exportedRows.size, original.minutes.size)
        original.minutes.zip(exportedRows).forEach { (actual, exported) ->
            assertEquals("canStage ${exported.s("timestamp_local")}", exported.s("can_stage").toBoolean(), actual.canStage)
            assertEquals("formal action ${exported.s("timestamp_local")}", exported.s("formal_action"), actual.action.name)
        }
        originalResolved.zip(rows.filter { it.time() < end }).forEach { (actual, exported) ->
            assertEquals("coupling ${exported.s("timestamp_local")}", exported.s("coupling_state"), actual.coupling!!.state.name)
            assertEquals("coupling age ${exported.s("timestamp_local")}", exported.s("coupling_age_ms").toLongOrNull(), actual.coupling!!.ageMillis)
        }
        val round = requireNotNull(System.getProperty("offlineReplayRound", "1")).toInt()
        val all = if (round==2) roundTwoProfiles() else profiles()
        val summary = mutableListOf("id,group,movements,noise,renew_noise,hold_min,peak_reset,composite_peak,entry_window,entry_percentile,min_bout,exit_percentile,exit_windows,exit_events,exit_rms_lookback,deep_min,deep_bouts,stageable_min,fallback_min,first_half_deep_min,safety_cap_min,short_run_filtered_min")
        val intervals = mutableListOf("profile,start_ms,end_ms,stage")
        val transitions = mutableListOf("profile,start_ms,action,reason,final_stage,retroactive_reason,primary_reason,coupling_reason,can_stage")
        for (p in all) {
            p.apply()
            val resolved = OfflineAutomaticPlacement.resolve(minutes, emptyList(), schedule)
            val result = OfflineSleepStageEstimator.analyze(session(), resolved, emptyList(), emptyList(), segments, schedule)
            if (p.id == "baseline") {
                assertEquals(original.intervals, result.intervals)
                assertEquals(original.minutes.map { it.action.name }, result.minutes.map { it.action.name })
                assertEquals(original.minutes.map { it.primaryReason?.name }, result.minutes.map { it.primaryReason?.name })
            }
            if (p.id in setOf("anchor_A", "anchor_B")) {
                val expectedDeep = if (p.id=="anchor_A") 88L else 101L
                val expectedRuns = if (p.id=="anchor_A") 4 else 8
                assertEquals("Prior report anchor parity: ${p.id}", expectedDeep * MINUTE_MS, result.durations.deep)
                assertEquals("Prior report anchor parity: ${p.id}", expectedRuns,
                    result.intervals.count { it.stage==SleepStage.DEEP })
                val priorId = if (p.id=="anchor_A") "grid_n2_noise3.0_peak1.5_h45_w15_p65_x70"
                    else "focused_peak_b5_x50_e1_w10"
                val priorFile = File(requireNotNull(System.getProperty("offlineReplayRepo")), "docs/offline-replay-2026-10-02/intervals.csv")
                val priorIntervals = priorFile.readLines().drop(1).map { it.split(',') }
                    .filter { it[0]==priorId }.map { SleepStageInterval(it[1].toLong(), it[2].toLong(), SleepStage.valueOf(it[3])) }
                assertEquals("Prior report full interval parity: ${p.id}", priorIntervals, result.intervals)
            }
            assertEquals(p.id, end - start, result.durations.span)
            assertEquals(p.id, result.durations.span, result.durations.deep + result.durations.light + result.durations.awake)
            assertTrue(p.id, result.minutes.filter { !it.canStage }.none { it.finalStage == SleepStage.DEEP })
            val deep = result.intervals.filter { it.stage == SleepStage.DEEP }
            assertTrue(p.id, deep.all { it.endMillis - it.startMillis >= p.minimumBout * MINUTE_MS })
            val mid = start + (end-start)/2
            val early = deep.sumOf { (minOf(it.endMillis, mid) - it.startMillis).coerceAtLeast(0) }
            summary += p.csv() + "," + listOf(result.durations.deep / 60000.0, deep.size,
                result.minutes.count { it.canStage }, result.fallbackLightReasonsMillis.values.sum()/60000.0,
                early/60000.0, result.minutes.count { it.safetyCapAdjusted },
                result.minutes.count { it.retroactiveAdjustmentReason?.name == "SHORT_DEEP_RUN_FILTER" }).joinToString(",")
            result.intervals.forEach { intervals += "${p.id},${it.startMillis},${it.endMillis},${it.stage}" }
            result.minutes.filter { it.action.name in setOf("ENTER", "EXIT") || it.retroactivelyAdjusted }.forEach {
                transitions += "${p.id},${it.startMillis},${it.action},${it.transitionReason.orEmpty()},${it.finalStage},${it.retroactiveAdjustmentReason?.name.orEmpty()},${it.primaryReason?.name.orEmpty()},${it.motion?.coupling?.reason.orEmpty()},${it.canStage}"
            }
            // Negative controls use real observed summary shapes: never treat silence, gaps,
            // an observed phone-use span, or a recording restart as uninterrupted Deep.
            val still = minutes.map { it.copy(activeMillis=0, squaredDeltaTime=.006*.006*it.coveredMillis,
                maxDelta=.012, movementEvents=0, longestActiveMillis=0, postureDelta=0.0) }
            val quietResolved = OfflineAutomaticPlacement.resolve(still, emptyList(), schedule)
            assertTrue("${p.id}: no quiet-only coupling", quietResolved.none { it.placement == Placement.BED })
            val use = listOf(UsageInterval(start, end))
            val inUse = OfflineAutomaticPlacement.resolve(minutes, use, schedule)
            val useResult = OfflineSleepStageEstimator.analyze(session(), inUse, emptyList(), use, segments, schedule)
            assertEquals("${p.id}: all phone use", 0L, useResult.durations.deep)
            val broken = minutes.mapIndexed { i, m -> m.copy(recordingId=i.toLong()) }
            val brokenResolved = OfflineAutomaticPlacement.resolve(broken, emptyList(), schedule)
            assertTrue("${p.id}: recording boundaries", brokenResolved.none { it.placement == Placement.BED })
            val gap = minutes.map { it.copy(longestGapMillis=3_000) }
            val gapResolved = OfflineAutomaticPlacement.resolve(gap, emptyList(), schedule)
            assertTrue("${p.id}: measured gaps", gapResolved.none { it.placement == Placement.BED })
        }
        File(out, "summary.csv").writeText(summary.joinToString("\n") + "\n")
        File(out, "intervals.csv").writeText(intervals.joinToString("\n") + "\n")
        File(out, "transitions.csv").writeText(transitions.joinToString("\n") + "\n")
        File(out, "verification.txt").writeText("Production CSV interval/canStage/action/coupling/age parity PASS\nGenerated baseline parity PASS\n" +
            (if (round==2) "Previous-round A/B full interval parity PASS\n" else "") +
            "${all.size} profiles: conservation, no ineligible Deep, minimum bout, quiet-only, phone-use, recording-boundary and measured-gap controls PASS\n")
        println("OFFLINE_REPLAY profiles=${all.size}; production and generated baseline match CSV exactly")
    }
}
