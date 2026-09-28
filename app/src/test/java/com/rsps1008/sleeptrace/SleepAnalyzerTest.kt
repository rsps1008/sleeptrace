package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepAnalyzer
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepAnalyzerTest {
    private val start = 1_700_000_000_000L
    private val segment = SleepSegment(start, start + 8 * 60 * 60 * 1000L, 100)
    private val schedule = SleepSchedule(0, 23 * 60 + 59)

    @Test fun `phone use is removed from sleep duration`() {
        val session = SleepAnalyzer.analyze(listOf(segment), samples(), listOf(UsageInterval(start + 60_000, start + 16 * 60_000)), schedule).single()
        assertEquals(15 * 60_000L, session.awakeMillis)
        assertEquals(7 * 60 * 60 * 1000L + 45 * 60 * 1000L, session.durationMillis)
        assertTrue(session.reason.contains("手機使用"))
    }

    @Test fun `missing classification still syncs the best available segment automatically`() {
        val session = SleepAnalyzer.analyze(listOf(segment), emptyList(), emptyList(), schedule).single()
        assertEquals(SyncState.PENDING, session.state)
    }

    @Test fun `confident complete session is pending sync`() {
        val session = SleepAnalyzer.analyze(listOf(segment), samples(), emptyList(), schedule).single()
        assertEquals(SyncState.PENDING, session.state)
    }

    private fun samples() = (0..47).map { index ->
        ClassificationSample(start + index * 10 * 60_000L, 90, 0, 0)
    }
}
