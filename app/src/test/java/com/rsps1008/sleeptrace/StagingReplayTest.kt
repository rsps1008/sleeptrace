package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test

class StagingReplayTest {
    @Test fun `frozen v4 versus v6 estimator-only fixture reports aligned transitions without accuracy claims`() {
        val base = 20_000L * MINUTE_MS
        val rows = requireNotNull(javaClass.getResourceAsStream("/staging/real_night_style.csv"))
            .bufferedReader().useLines { lines -> lines.drop(1).map { line ->
                val v = line.split(','); val covered = (v[1].toDouble() * 1000).toLong(); val rms = v[3].toDouble()
                MotionMinute(base + v[0].toLong() * MINUTE_MS, covered, (v[2].toDouble() * 1000).toLong(),
                    rms * rms * covered, 60, Placement.valueOf(v[4]), 4)
            }.toList() }
        val s = SleepSession(startMillis=base,endMillis=base+329*MINUTE_MS,confidence=60,awakeMillis=0,state=SyncState.PENDING,reason="synthetic")
        val segments = listOf(SleepSegment(base,s.endMillis,60))
        val old = FrozenStageV4.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0))
        // This fixture intentionally keeps its original v4 placement and never
        // calls AUTO resolution.  It is estimator-only, not an end-to-end test.
        val next = SleepStageEstimator.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0))
        val boundaries = (old.intervals.flatMap { listOf(it.startMillis, it.endMillis) } +
            next.intervals.flatMap { listOf(it.startMillis, it.endMillis) }).distinct().sorted()
        val matrix = linkedMapOf<Pair<SleepStage, SleepStage>, Long>()
        val diffs = mutableListOf<String>()
        fun stageAt(items: List<SleepStageInterval>, at: Long) = items.single { at >= it.startMillis && at < it.endMillis }.stage
        boundaries.zipWithNext().forEach { (start, end) ->
            val from = stageAt(old.intervals, start); val to = stageAt(next.intervals, start)
            matrix[from to to] = (matrix[from to to] ?: 0) + end - start
            if (from != to) diffs += "${start - base},${end - base},$from,$to,${end - start}"
        }
        fun summary(name: String, intervals: List<SleepStageInterval>): String {
            val d = stageDurations(s.copy(stageIntervals=intervals))
            val fragments = intervals.count { it.stage != SleepStage.AWAKE && it.endMillis-it.startMillis < 5*MINUTE_MS }
            return "$name: deep_ms=${d.deep}, light_ms=${d.light}, undetermined_ms=${d.sleeping}, awake_ms=${d.awake}, " +
                "transitions=${(intervals.size-1).coerceAtLeast(0)}, fragments_under_5min=$fragments"
        }
        val text = "SYNTHETIC fixture, not an actual recorded night or physiological accuracy evidence.\n" +
            summary("frozen dc92433 algorithm 4",old.intervals) + "\n" + summary("algorithm 6",next.intervals) + "\n" +
            "old_valid_motion_ratio=${old.motionCoverageRatio}, new_valid_motion_ratio=${next.motionCoverageRatio}, " +
            "new_span_motion_coverage=${next.sensorCoverageRatio}, new_stageable_sleep_coverage=${next.stageableCoverageRatio}\n" +
            "new_undetermined_primary_reason_ms=${next.undeterminedReasonsMillis}\n" +
            matrix.entries.joinToString("\n") { "${it.key.first}->${it.key.second}=${it.value}" } + "\n"
        println(text)
        val output = java.io.File("build/reports/staging-replay.txt")
        output.parentFile?.mkdirs(); output.writeText(text)
        java.io.File("build/reports/staging-transition-matrix.csv").writeText("from_stage,to_stage,duration_ms\n" +
            matrix.entries.joinToString("\n") { "${it.key.first},${it.key.second},${it.value}" } + "\n")
        java.io.File("build/reports/staging-diff-intervals.csv").writeText("relative_start_ms,relative_end_ms,old_stage,new_stage,duration_ms\n" +
            diffs.joinToString("\n") + "\n")
        assertTrue(next.durations.deep > 0)
        assertTrue(next.durations.light > 0)
        assertTrue(next.durations.sleeping > 0)
        assertEquals(329*MINUTE_MS,next.durations.span)
        assertEquals(329 * MINUTE_MS, matrix.values.sum())
        assertEquals(next, SleepStageEstimator.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0)))
    }
}
