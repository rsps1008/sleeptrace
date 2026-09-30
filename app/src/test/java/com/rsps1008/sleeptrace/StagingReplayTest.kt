package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test

class StagingReplayTest {
    @Test fun `frozen HEAD v4 versus v5 on synthetic fixture reports evidence without accuracy claims`() {
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
        val next = SleepStageEstimator.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0))
        fun summary(name: String, intervals: List<SleepStageInterval>): String {
            val d = stageDurations(s.copy(stageIntervals=intervals))
            val fragments = intervals.count { it.stage != SleepStage.AWAKE && it.endMillis-it.startMillis < 5*MINUTE_MS }
            return "$name: deep_ms=${d.deep}, light_ms=${d.light}, undetermined_ms=${d.sleeping}, awake_ms=${d.awake}, " +
                "transitions=${(intervals.size-1).coerceAtLeast(0)}, fragments_under_5min=$fragments"
        }
        val text = "SYNTHETIC fixture, not an actual recorded night or physiological accuracy evidence.\n" +
            summary("HEAD dc92433 algorithm 4",old.intervals) + "\n" + summary("algorithm 5",next.intervals) + "\n" +
            "old_valid_motion_ratio=${old.motionCoverageRatio}, new_valid_motion_ratio=${next.motionCoverageRatio}, " +
            "new_span_motion_coverage=${next.sensorCoverageRatio}, new_stageable_sleep_coverage=${next.stageableCoverageRatio}\n" +
            "new_undetermined_primary_reason_ms=${next.undeterminedReasonsMillis}\n"
        println(text)
        val output = java.io.File("build/reports/staging-replay.txt")
        output.parentFile?.mkdirs(); output.writeText(text)
        assertTrue(next.durations.deep > 0)
        assertTrue(next.durations.light > 0)
        assertTrue(next.durations.sleeping > 0)
        assertEquals(329*MINUTE_MS,next.durations.span)
        assertEquals(next, SleepStageEstimator.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0)))
    }
}
