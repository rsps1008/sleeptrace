package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingReplayHashTest {
    private val base = 20_000L * MINUTE_MS

    @Test fun `same source bytes always produce the same hash`() {
        val fixture = StagingReplayFixtureReader.read(base)
        assertEquals(fixture.sha256, StagingReplayFixtureReader.sha256(fixture.bytes.copyOf()))
    }

    @Test fun `different parseable bytes have different hashes even with equal staging totals`() {
        val fixture = StagingReplayFixtureReader.read(base)
        // A trailing line ending is source data, but it does not change the
        // parsed rows or the estimator result.
        val changed = fixture.bytes + byteArrayOf('\n'.code.toByte())
        val changedRows = StagingReplayFixtureReader.parse(changed, base)
        assertEquals(fixture.rows, changedRows)
        assertNotEquals(fixture.sha256, StagingReplayFixtureReader.sha256(changed))

        val session = SleepSession(
            startMillis = base, endMillis = base + 329 * MINUTE_MS, confidence = 60,
            awakeMillis = 0, state = SyncState.PENDING, reason = "synthetic"
        )
        val segments = listOf(SleepSegment(base, session.endMillis, 60))
        val original = com.rsps1008.sleeptrace.sleep.SleepStageEstimator.analyze(
            session, fixture.rows, emptyList(), emptyList(), segments, SleepSchedule(0, 0)
        )
        val replayed = com.rsps1008.sleeptrace.sleep.SleepStageEstimator.analyze(
            session, changedRows, emptyList(), emptyList(), segments, SleepSchedule(0, 0)
        )
        assertEquals(original.durations, replayed.durations)
        assertEquals(original.intervals, replayed.intervals)
        assertTrue(StagingReplayFixtureReader.sha256(changed).isNotBlank())
    }
}
