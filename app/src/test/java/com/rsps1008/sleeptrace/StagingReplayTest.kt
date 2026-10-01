package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test

class StagingReplayTest {
    @Test fun `frozen v4 versus current estimator-only fixture reports aligned transitions without accuracy claims`() {
        val base = 20_000L * MINUTE_MS
        val fixture = StagingReplayFixtureReader.read(base)
        val rows = fixture.rows
        val s = SleepSession(startMillis=base,endMillis=base+329*MINUTE_MS,confidence=60,awakeMillis=0,state=SyncState.PENDING,reason="synthetic")
        val segments = listOf(SleepSegment(base,s.endMillis,60))
        val old = FrozenStageV4.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0))
        // This fixture intentionally keeps its original v4 placement and never
        // calls AUTO resolution.  It is estimator-only, not an end-to-end test.
        val next = SleepStageEstimator.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0))
        val boundaries = (old.intervals.flatMap { listOf(it.startMillis, it.endMillis) } +
            next.intervals.flatMap { listOf(it.startMillis, it.endMillis) }).distinct().sorted()
        val stageOrder = listOf(SleepStage.AWAKE, SleepStage.DEEP, SleepStage.LIGHT, SleepStage.SLEEPING)
        val matrix = linkedMapOf<Pair<SleepStage, SleepStage>, Long>().apply {
            stageOrder.forEach { from -> stageOrder.forEach { to -> put(from to to, 0L) } }
        }
        val diffs = mutableListOf<String>()
        fun stageAt(items: List<SleepStageInterval>, at: Long) = items.single { at >= it.startMillis && at < it.endMillis }.stage
        boundaries.zipWithNext().forEach { (start, end) ->
            val from = stageAt(old.intervals, start); val to = stageAt(next.intervals, start)
            matrix[from to to] = requireNotNull(matrix[from to to]) + end - start
            if (from != to) diffs += "${start - base},${end - base},$from,$to,${end - start}"
        }
        fun summary(name: String, intervals: List<SleepStageInterval>): String {
            val d = stageDurations(s.copy(stageIntervals=intervals))
            val fragments = intervals.count { it.stage != SleepStage.AWAKE && it.endMillis-it.startMillis < 5*MINUTE_MS }
            return "$name: deep_ms=${d.deep}, light_ms=${d.light}, undetermined_ms=${d.sleeping}, awake_ms=${d.awake}, " +
                "transitions=${(intervals.size-1).coerceAtLeast(0)}, fragments_under_5min=$fragments"
        }
        val text = "SYNTHETIC fixture, not an actual recorded night or physiological accuracy evidence.\n" +
            "fixture_sha256=${fixture.sha256}\n" +
            summary("frozen dc92433 algorithm 4",old.intervals) + "\n" +
                summary("algorithm ${SleepStageEstimator.ALGORITHM_VERSION}",next.intervals) + "\n" +
            "old_valid_motion_ratio=${old.motionCoverageRatio}, new_valid_motion_ratio=${next.motionCoverageRatio}, " +
            "new_span_motion_coverage=${next.sensorCoverageRatio}, new_stageable_sleep_coverage=${next.stageableCoverageRatio}\n" +
            "new_undetermined_primary_reason_ms=${next.undeterminedReasonsMillis}\n" +
            "new_fallback_light_primary_reason_ms=${next.fallbackLightReasonsMillis}\n" +
            matrix.entries.joinToString("\n") { "${it.key.first}->${it.key.second}=${it.value}" } + "\n"
        println(text)
        val output = java.io.File("build/reports/staging-replay.txt")
        output.parentFile?.mkdirs(); output.writeText(text, Charsets.UTF_8)
        val matrixCsv = "from_stage,to_stage,duration_ms\n" +
            matrix.entries.joinToString("\n") { "${it.key.first},${it.key.second},${it.value}" } + "\n"
        java.io.File("build/reports/staging-transition-matrix.csv").writeText(matrixCsv, Charsets.UTF_8)
        val diffCsv = "relative_start_ms,relative_end_ms,old_stage,new_stage,duration_ms\n" + diffs.joinToString("\n") + "\n"
        java.io.File("build/reports/staging-diff-intervals.csv").writeText(diffCsv, Charsets.UTF_8)
        assertTrue(next.durations.deep > 0)
        assertTrue(next.durations.light > 0)
        assertEquals(0, next.durations.sleeping)
        assertTrue(next.intervals.none { it.stage == SleepStage.SLEEPING })
        assertEquals(329*MINUTE_MS,next.durations.span)
        assertEquals(329 * MINUTE_MS, matrix.values.sum())
        stageOrder.forEach { stage ->
            assertEquals(old.intervals.filter { it.stage == stage }.sumOf { it.endMillis - it.startMillis },
                matrix.filterKeys { it.first == stage }.values.sum())
            assertEquals(next.intervals.filter { it.stage == stage }.sumOf { it.endMillis - it.startMillis },
                matrix.filterKeys { it.second == stage }.values.sum())
        }
        assertEquals(diffs.sumOf { it.substringAfterLast(',').toLong() },
            matrix.filterKeys { it.first != it.second }.values.sum())
        // Default mode is check-only: it never overwrites committed docs. An
        // explicit SLEEPTRACE_UPDATE_REPLAY_DOCS=true run is the only update
        // mode, used after reviewing the generated build/reports output.
        val docs = listOf(
            java.io.File("../docs/staging-v${SleepStageEstimator.ALGORITHM_VERSION}-replay.txt") to text,
            java.io.File("../docs/staging-v${SleepStageEstimator.ALGORITHM_VERSION}-transition-matrix.csv") to matrixCsv,
            java.io.File("../docs/staging-v${SleepStageEstimator.ALGORITHM_VERSION}-diff-intervals.csv") to diffCsv
        )
        if (System.getenv("SLEEPTRACE_UPDATE_REPLAY_DOCS").equals("true", ignoreCase = true)) {
            docs.forEach { (file, content) -> file.writeText(content, Charsets.UTF_8) }
        } else {
            docs.forEach { (file, content) -> assertEquals(content, file.readText(Charsets.UTF_8)) }
        }
        assertEquals(fixture.sha256, StagingReplayFixtureReader.sha256(fixture.bytes))
        assertEquals(next, SleepStageEstimator.analyze(s,rows,emptyList(),emptyList(),segments,SleepSchedule(0,0)))
    }
}
