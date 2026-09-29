package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepAnalyzer
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import java.time.LocalDate
import java.time.ZoneId
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

    @Test fun `api segment is clamped to the overnight schedule`() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026, 9, 27)
        val segment = SleepSegment(
            day.atTime(20, 30).atZone(zone).toInstant().toEpochMilli(),
            day.plusDays(1).atTime(8, 30).atZone(zone).toInstant().toEpochMilli(),
            100
        )

        val session = SleepAnalyzer.analyze(
            listOf(segment), emptyList(), emptyList(), SleepSchedule(23 * 60, 7 * 60)
        ).single()

        assertEquals(day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli(), session.startMillis)
        assertEquals(day.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), session.endMillis)
    }

    private fun samples() = (0..47).map { index ->
        ClassificationSample(start + index * 10 * 60_000L, 90, 0, 0)
    }
}
