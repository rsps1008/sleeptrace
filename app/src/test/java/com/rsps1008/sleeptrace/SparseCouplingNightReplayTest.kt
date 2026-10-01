package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.AutomaticPlacement
import com.rsps1008.sleeptrace.motion.CouplingBasis
import com.rsps1008.sleeptrace.motion.CouplingPolicy
import com.rsps1008.sleeptrace.motion.CouplingState
import com.rsps1008.sleeptrace.motion.MotionAccumulator
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.Placement
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepStage
import com.rsps1008.sleeptrace.sleep.SleepStageEstimator
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SparseCouplingNightReplayTest {
    @Test
    fun `replay decisions are invariant when the same night is shifted in time`() {
        val fixture = loadFixture()
        val offset = 11 * DAY_MILLIS + 7 * 60 * MINUTE_MILLIS
        val original = replayAtOffset(fixture.rows, 0L)
        val shifted = replayAtOffset(fixture.rows, offset)

        assertEquals(original.durations, shifted.durations)
        assertEquals(original.baseline, shifted.baseline)
        assertEquals(
            original.intervals.map { Triple(it.startMillis - SESSION_START_MILLIS, it.endMillis - SESSION_START_MILLIS, it.stage) },
            shifted.intervals.map { Triple(it.startMillis - SESSION_START_MILLIS - offset, it.endMillis - SESSION_START_MILLIS - offset, it.stage) },
        )
    }

    @Test
    fun `redacted night replays through AUTO placement and staging`() {
        val fixture = loadFixture()
        assertEquals(EXPECTED_FIXTURE_SHA256, fixture.sha256)
        assertEquals(348, fixture.rows.size)
        assertEquals((19..366).toList(), fixture.rows.map { it.minuteIndex })

        val candidates = qualifyingShortMovementsBeforeMorningUse(fixture.rows)
        assertEquals(listOf(44, 50, 85, 138, 151, 185), candidates)
        assertEquals(2, maxCandidatesInWindow(candidates, CouplingPolicy.FAST_EVIDENCE_WINDOW_MILLIS))

        val gapMinute = fixture.rows.single { it.minuteIndex == 63 }
        assertEquals(58_993L, gapMinute.minute.coveredMillis)
        assertEquals(60, gapMinute.minute.sampleCount)
        assertEquals(1_007L, gapMinute.minute.longestGapMillis)
        assertTrue(gapMinute.minute.supportsCurrentStaging)
        assertNotNull(gapMinute.minute.movementEvents)
        assertNotNull(gapMinute.minute.longestGapMillis)

        val usageIntervals = usageIntervals()
        val resolved = AutomaticPlacement.resolve(
            minutes = fixture.rows.map { it.minute },
            usage = usageIntervals,
            schedule = ALL_DAY_SCHEDULE,
        )
        val firstSparseSupport = resolved.firstOrNull {
            it.coupling?.state == CouplingState.SUPPORTED &&
                it.coupling.basis == CouplingBasis.SPARSE
        }
        assertNotNull(firstSparseSupport)
        requireNotNull(firstSparseSupport)
        assertEquals(BASE_MILLIS + 85 * MINUTE_MILLIS, firstSparseSupport.startMillis)
        val sparseStats = requireNotNull(firstSparseSupport.coupling!!.sparseStats)
        val fastStats = requireNotNull(firstSparseSupport.coupling.fastStats)
        assertTrue(sparseStats.historyMinutes >= CouplingPolicy.SPARSE_MIN_HISTORY_MINUTES)
        assertTrue(sparseStats.qualifyingMovements >= CouplingPolicy.MIN_MOVEMENTS)
        assertTrue(
            requireNotNull(sparseStats.movementSpanMillis) >=
                CouplingPolicy.SPARSE_MIN_SPAN_MILLIS,
        )
        assertTrue(fastStats.qualifyingMovements < CouplingPolicy.MIN_MOVEMENTS)
        assertEquals(92, resolved.count { it.placement == Placement.BED })

        val session = SleepSession(
            id = "sparse-coupling-night-redacted",
            startMillis = SESSION_START_MILLIS,
            endMillis = SESSION_END_MILLIS,
            confidence = 68,
            state = SyncState.PENDING,
            reason = "redacted fixed replay",
            awakeMillis = EXPECTED_AWAKE_MILLIS,
            awakeIntervals = usageIntervals,
            usageSnapshotApplied = true,
        )
        val staging = SleepStageEstimator.analyze(
            session = session,
            motionMinutes = resolved,
            classifications = emptyList(),
            usageIntervals = usageIntervals,
            sleepSegments = listOf(SleepSegment(SESSION_START_MILLIS, SESSION_END_MILLIS, 68)),
            schedule = ALL_DAY_SCHEDULE,
        )

        assertEquals(346, staging.currentFeatureValidMinutes)
        assertEquals(0.9432617014, staging.sensorCoverageRatio, 0.000000001)
        assertNotNull(staging.baseline)
        assertEquals(76, staging.baselineSampleCount)
        assertTrue(staging.stageableCoverageRatio > 0.0)

        val durations = staging.durations
        val baseline = requireNotNull(staging.baseline)
        val deepRuns = staging.intervals.filter { it.stage == SleepStage.DEEP }
        println(
            "SPARSE_REPLAY_V9 deep_ms=${durations.deep} light_ms=${durations.light} " +
                "sleeping_ms=${durations.sleeping} awake_ms=${durations.awake} " +
                "deep_runs=${deepRuns.size} " +
                "short_confirmed_runs=${deepRuns.count { it.endMillis - it.startMillis < 2 * MINUTE_MILLIS }} " +
                "relative_quiet_threshold=${baseline.p70} method=NIGHTLY_P70_PROVISIONAL_CONFIRMED",
        )
        assertTrue(
            "fixed real-night replay must produce more than one hour of engineering Deep; actual=${durations.deep}",
            durations.deep > 60 * MINUTE_MILLIS,
        )
        assertEquals(0L, durations.sleeping)
        assertTrue(staging.intervals.all { it.stage in setOf(SleepStage.AWAKE, SleepStage.LIGHT, SleepStage.DEEP) })
        deepRuns.filter {
            it.endMillis - it.startMillis < 2 * MINUTE_MILLIS
        }.forEach { shortRun ->
            assertTrue(staging.minutes.filter {
                it.startMillis < shortRun.endMillis && it.endMillis > shortRun.startMillis
            }.any {
                it.wasBackfilled || SleepStageEstimator.Reason.RELATIVE_QUIET_CONFIRMED in it.nonBlockingReasons
            })
        }
        val relativeEntries = staging.minutes.filter { it.transitionReason == "enter_relative_quiet" }
        assertTrue(relativeEntries.isNotEmpty())
        assertTrue(relativeEntries.any {
            SleepStageEstimator.Reason.RELATIVE_QUIET_CONFIRMED in it.nonBlockingReasons
        })
        relativeEntries.forEach { minute ->
            assertEquals(SleepStageEstimator.Action.ENTER, minute.action)
            assertTrue(minute.entryDecision.allowed)
            assertTrue(SleepStageEstimator.Reason.RELATIVE_QUIET_ENTRY in minute.nonBlockingReasons)
            assertTrue(SleepStageEstimator.Reason.ENTER_DEEP in minute.reasons)
            assertEquals(SleepStageEstimator.Reason.ENTER_DEEP, minute.primaryReason)
            assertTrue(minute.windowBlockingReasons.none { it in minute.reasons })
        }
        val filteredSingletons = staging.minutes.filter {
            SleepStageEstimator.Reason.SHORT_DEEP_RUN in it.reasons
        }
        filteredSingletons.forEach { minute ->
            assertEquals(SleepStage.DEEP, minute.formalStage)
            assertEquals(SleepStage.LIGHT, minute.finalStage)
            assertTrue(minute.retroactivelyAdjusted)
            assertEquals(
                SleepStageEstimator.PostProcessReason.SHORT_DEEP_RUN_FILTER,
                minute.retroactiveAdjustmentReason,
            )
            assertEquals(SleepStageEstimator.Reason.SHORT_DEEP_RUN, minute.primaryReason)
        }
        staging.minutes.filter { it.finalStage == SleepStage.DEEP }.forEach { minute ->
            assertTrue(minute.canStage)
            assertEquals(Placement.BED, minute.placement)
            assertEquals(0L, minute.motion?.longestGapMillis)
            assertEquals(0L, minute.phoneUseMillis)
            assertTrue(minute.hasSleepEvidence)
            assertFalse(minute.inOnsetGuard)
        }
        assertTrue(staging.minutes.filter { !it.canStage }.none { it.finalStage == SleepStage.DEEP })
        assertEquals(SESSION_DURATION_MILLIS, durations.span)
        assertEquals(EXPECTED_AWAKE_MILLIS, durations.awake)
        assertEquals(
            durations.span,
            durations.awake + durations.deep + durations.light,
        )
        assertEquals(
            durations.span - durations.awake,
            durations.deep + durations.light,
        )

        val gapDiagnostic = staging.minutes.single {
            it.startMillis == BASE_MILLIS + 63 * MINUTE_MILLIS
        }
        assertTrue(
            SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE in
                gapDiagnostic.currentEligibilityReasons,
        )
        assertFalse(gapDiagnostic.canStage)
        assertEquals(SleepStage.LIGHT, gapDiagnostic.finalStage)
        assertTrue(staging.fallbackLightReasonsMillis.containsKey(SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE))
    }

    @Test
    fun `v5 baseline absence is not mislabeled as legacy`() {
        val start = BASE_MILLIS + 1_000 * MINUTE_MILLIS
        val minutes = (0 until SleepStageEstimator.MINIMUM_BASELINE_MINUTES - 1).map { index ->
            MotionMinute(
                startMillis = start + index * MINUTE_MILLIS,
                coveredMillis = MINUTE_MILLIS,
                activeMillis = 0L,
                squaredDeltaTime = 0.03 * 0.03 * MINUTE_MILLIS,
                sampleCount = 60,
                placement = Placement.BED,
                featureVersion = MotionAccumulator.STRICT_CADENCE_FEATURE_VERSION,
                maxDelta = 0.03,
                movementEvents = 0,
                longestActiveMillis = 0L,
                quietTailMillis = MINUTE_MILLIS,
                longestGapMillis = 0L,
                postureDelta = 0.0,
                recordingId = 2L,
            )
        }
        val end = start + minutes.size * MINUTE_MILLIS
        val staging = SleepStageEstimator.analyze(
            session = SleepSession(
                id = "v5-baseline-null",
                startMillis = start,
                endMillis = end,
                confidence = 68,
                awakeMillis = 0L,
                state = SyncState.PENDING,
                reason = "baseline-null regression",
            ),
            motionMinutes = minutes,
            classifications = emptyList(),
            usageIntervals = emptyList(),
            sleepSegments = listOf(SleepSegment(start, end, 68)),
            schedule = ALL_DAY_SCHEDULE,
        )

        assertNull(staging.baseline)
        assertTrue(staging.minutes.isNotEmpty())
        staging.minutes.forEach { diagnostic ->
            assertFalse(
                SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION in
                    diagnostic.reasons,
            )
            assertFalse(
                SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION in
                    diagnostic.windowBlockingReasons,
            )
        }
    }

    private fun loadFixture(): Fixture {
        val bytes = requireNotNull(javaClass.getResourceAsStream(FIXTURE_RESOURCE)) {
            "Missing replay fixture $FIXTURE_RESOURCE"
        }.use { it.readBytes() }
        val lines = String(bytes, StandardCharsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(EXPECTED_HEADER, lines.first())
        val rows = lines.drop(1).map { line ->
            val values = line.split(',')
            assertEquals("Unexpected fixture column count in $line", 11, values.size)
            val minuteIndex = values[0].toInt()
            val coveredMillis = values[1].toLong()
            val rms = values[3].toDouble()
            FixtureRow(
                minuteIndex = minuteIndex,
                minute = MotionMinute(
                    startMillis = BASE_MILLIS + minuteIndex * MINUTE_MILLIS,
                    coveredMillis = coveredMillis,
                    activeMillis = values[2].toLong(),
                    squaredDeltaTime = rms * rms * coveredMillis,
                    sampleCount = values[4].toInt(),
                    placement = Placement.AUTO,
                    // The source night was collected by the v5 cadence
                    // definition. Keep that provenance frozen while replaying
                    // it through the current placement/staging rules.
                    featureVersion = MotionAccumulator.STRICT_CADENCE_FEATURE_VERSION,
                    maxDelta = values[5].toDouble(),
                    movementEvents = values[6].toInt(),
                    longestActiveMillis = values[7].toLong(),
                    quietTailMillis = values[8].toLong(),
                    longestGapMillis = values[9].toLong(),
                    postureDelta = values[10].toDouble(),
                    recordingId = 1L,
                    observedStart = if (minuteIndex == 19) BASE_MILLIS + 1_166_399L else null,
                ),
            )
        }
        return Fixture(rows, sha256(bytes))
    }

    private fun qualifyingShortMovementsBeforeMorningUse(rows: List<FixtureRow>): List<Int> {
        val cutoff = BASE_MILLIS + MORNING_USE_MINUTE_INDEX * MINUTE_MILLIS
        return rows.filter { it.minute.startMillis < cutoff }.mapNotNull { row ->
            val history = rows.asSequence()
                .map { it.minute }
                .filter {
                    it.startMillis <= row.minute.startMillis &&
                        it.startMillis >= row.minute.startMillis - CouplingPolicy.FAST_EVIDENCE_WINDOW_MILLIS
                }
                .toList()
            val quietRms = history.filter { it.activeMillis == 0L && it.rms.isFinite() }.map { it.rms }.sorted()
            val localNoiseFloor = quietRms.getOrNull(quietRms.size / 5) ?: 0.0
            val threshold = maxOf(CouplingPolicy.MIN_MOVEMENT_RMS, localNoiseFloor * CouplingPolicy.NOISE_MULTIPLIER)
            row.minuteIndex.takeIf { row.minute.isQualifyingShortMovement(threshold) }
        }
    }

    private fun maxCandidatesInWindow(candidateMinuteIndices: List<Int>, windowMillis: Long): Int {
        val windowMinutes = windowMillis / MINUTE_MILLIS
        return candidateMinuteIndices.maxOf { current ->
            candidateMinuteIndices.count { it in (current - windowMinutes).toInt()..current }
        }
    }

    private fun MotionMinute.isQualifyingShortMovement(threshold: Double): Boolean =
        rms >= threshold &&
            rms <= CouplingPolicy.HANDLING_DELTA &&
            activeMillis in CouplingPolicy.MIN_ACTIVE_MILLIS..CouplingPolicy.MAX_SHORT_ACTIVE_MILLIS &&
            (longestActiveMillis ?: 0L) <= CouplingPolicy.MAX_SHORT_ACTIVE_MILLIS

    private fun usageIntervals(): List<UsageInterval> = listOf(
        UsageInterval(SESSION_START_MILLIS + 21_392_235L, SESSION_START_MILLIS + 21_407_611L),
        UsageInterval(SESSION_START_MILLIS + 21_630_773L, SESSION_START_MILLIS + 21_640_035L),
        UsageInterval(SESSION_START_MILLIS + 21_870_605L, SESSION_START_MILLIS + 21_941_935L),
    )

    private fun replayAtOffset(rows: List<FixtureRow>, offset: Long): SleepStageEstimator.StagingResult {
        val shiftedMotion = rows.map { row ->
            row.minute.copy(
                startMillis = row.minute.startMillis + offset,
                observedStart = row.minute.observedStart?.plus(offset),
                observedEnd = row.minute.observedEnd?.plus(offset),
            )
        }
        val shiftedUsage = usageIntervals().map {
            it.copy(startMillis = it.startMillis + offset, endMillis = it.endMillis + offset)
        }
        val resolved = AutomaticPlacement.resolve(shiftedMotion, shiftedUsage, ALL_DAY_SCHEDULE)
        val start = SESSION_START_MILLIS + offset
        val end = SESSION_END_MILLIS + offset
        return SleepStageEstimator.analyze(
            session = SleepSession(
                id = "translation-invariance-$offset",
                startMillis = start,
                endMillis = end,
                confidence = 68,
                state = SyncState.PENDING,
                reason = "translation invariance",
                awakeMillis = EXPECTED_AWAKE_MILLIS,
                awakeIntervals = shiftedUsage,
                usageSnapshotApplied = true,
            ),
            motionMinutes = resolved,
            classifications = emptyList(),
            usageIntervals = shiftedUsage,
            sleepSegments = listOf(SleepSegment(start, end, 68)),
            schedule = ALL_DAY_SCHEDULE,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02X".format(it) }

    private data class Fixture(
        val rows: List<FixtureRow>,
        val sha256: String,
    )

    private data class FixtureRow(
        val minuteIndex: Int,
        val minute: MotionMinute,
    )

    private companion object {
        const val FIXTURE_RESOURCE = "/staging/sparse_coupling_night_redacted.csv"
        const val EXPECTED_FIXTURE_SHA256 = "BC447C6582F3EEE269A2F3C24CF6BB8609B1AD9F7D899E83DDE78F955AC98373"
        const val EXPECTED_HEADER = "minute_index,covered_ms,active_ms,rms,sample_count,max_delta,movement_events,longest_active_ms,quiet_tail_ms,longest_gap_ms,posture_delta"
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
        // Anchor the redacted relative rows to the same local clock time in every
        // test environment. A fixed epoch crossed the all-day schedule boundary
        // under UTC but not Asia/Taipei, making coupling results timezone-dependent.
        val BASE_MILLIS = LocalDate.of(2026, 10, 1).atTime(1, 18)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val SESSION_START_MILLIS = BASE_MILLIS + 29_427L
        const val SESSION_DURATION_MILLIS = 21_960_000L
        val SESSION_END_MILLIS = SESSION_START_MILLIS + SESSION_DURATION_MILLIS
        const val EXPECTED_AWAKE_MILLIS = 95_968L
        const val MORNING_USE_MINUTE_INDEX = 357
        val ALL_DAY_SCHEDULE = SleepSchedule(0, 0)
    }
}
