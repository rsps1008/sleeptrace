package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepAnalyzer
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.UsageSnapshot
import com.rsps1008.sleeptrace.sleep.UsageSnapshotResult
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `each night uses its own phone intervals and access limitation`() {
        val zone = ZoneId.systemDefault()
        val schedule = SleepSchedule(23 * 60, 7 * 60)
        val firstDate = LocalDate.of(2026, 9, 27)
        val firstWindow = schedule.windowForStartDate(firstDate, zone)
        val secondWindow = schedule.windowForStartDate(firstDate.plusDays(1), zone)
        val phoneUse = UsageInterval(firstWindow.startMillis + 60_000, firstWindow.startMillis + 20 * 60_000)
        val snapshots = listOf(
            UsageSnapshot(firstWindow.startMillis, firstWindow.endMillis, true, listOf(phoneUse), firstWindow.endMillis),
            UsageSnapshot(secondWindow.startMillis, secondWindow.endMillis, false, emptyList(), secondWindow.endMillis)
        )
        val usage = UsageSnapshotResult(snapshots, snapshots.flatMap { it.intervals })
        val sessions = SleepAnalyzer.analyzeByWindow(
            segments = listOf(
                SleepSegment(firstWindow.startMillis, firstWindow.endMillis, 100),
                SleepSegment(secondWindow.startMillis, secondWindow.endMillis, 100)
            ),
            classifications = emptyList(),
            phoneUse = usage.intervals,
            schedule = schedule,
            windows = listOf(firstWindow, secondWindow),
            usageAvailable = usage::availableFor
        )

        assertEquals(2, sessions.size)
        assertTrue(sessions[0].reason.contains("已扣除夜間手機使用"))
        assertFalse(sessions[0].reason.contains("無法排除手機使用"))
        assertTrue(sessions[1].reason.contains("無法排除手機使用"))
        assertFalse(sessions[1].reason.contains("已扣除夜間手機使用"))
    }

    private fun samples() = (0..47).map { index ->
        ClassificationSample(start + index * 10 * 60_000L, 90, 0, 0)
    }
}
