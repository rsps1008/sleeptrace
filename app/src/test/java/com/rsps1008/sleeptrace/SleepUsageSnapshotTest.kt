package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepWindow
import com.rsps1008.sleeptrace.sleep.SleepUsageSnapshot
import com.rsps1008.sleeptrace.sleep.UsageSnapshot
import com.rsps1008.sleeptrace.sleep.UsageSnapshotResult
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepUsageSnapshotTest {
    private val base = SleepSession(
        id = "night", startMillis = 0, endMillis = 4 * 60 * 60_000L, confidence = 80,
        awakeMillis = 0, state = SyncState.PENDING, reason = "Sleep API 與使用紀錄一致"
    )

    @Test fun `phone use updates awake intervals and the displayed reason together`() {
        val updated = SleepUsageSnapshot.apply(
            base,
            listOf(UsageInterval(30 * 60_000L, 60 * 60_000L)),
            usageAvailable = true
        )
        assertEquals(30 * 60_000L, updated.awakeMillis)
        assertTrue(updated.reason.contains("已扣除夜間手機使用 30 分鐘"))
        assertFalse(updated.reason.contains("Sleep API 與使用紀錄一致"))
        assertTrue(updated.usageSnapshotApplied)
    }

    @Test fun `unavailable UsageStats is not described as excluded`() {
        val updated = SleepUsageSnapshot.apply(base, emptyList(), usageAvailable = false)
        assertTrue(updated.reason.contains("無法排除手機使用"))
        assertFalse(updated.reason.contains("Sleep API 與使用紀錄一致"))
    }

    @Test fun `a night snapshot and session become ready only after the complete scheduled window`() {
        val zone = ZoneId.of("UTC")
        val schedule = SleepSchedule(23 * 60, 7 * 60)
        fun at(dayOffset: Long, hour: Int, minute: Int) = LocalDate.of(2026, 9, 29).plusDays(dayOffset)
            .atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val start = at(0, 23, 30)
        val window = requireNotNull(schedule.windowAt(start, zone))
        val session = base.copy(startMillis = start, endMillis = at(1, 6, 30))

        assertTrue(SleepUsageSnapshot.completedWindows(listOf(window), at(1, 6, 30)).isEmpty())
        assertFalse(SleepUsageSnapshot.isWindowComplete(session, schedule, at(1, 6, 30), zone))
        assertEquals(listOf(window), SleepUsageSnapshot.completedWindows(listOf(window), at(1, 7, 0)))
        assertTrue(SleepUsageSnapshot.isWindowComplete(session, schedule, at(1, 7, 0), zone))
        assertEquals(listOf(SleepWindow(0, 10)), SleepUsageSnapshot.completedWindows(
            listOf(SleepWindow(0, 10), SleepWindow(10, 20)), 15
        ))
    }

    @Test fun `same start with a changed end is a different usage window`() {
        val previous = UsageSnapshot(
            windowStartMillis = 10,
            windowEndMillis = 20,
            accessAvailable = true,
            intervals = emptyList(),
            capturedAtMillis = 30
        )

        assertTrue(SleepUsageSnapshot.canReuse(previous, SleepWindow(10, 20), accessAvailableNow = true))
        assertFalse(SleepUsageSnapshot.canReuse(previous, SleepWindow(10, 25), accessAvailableNow = true))
        assertEquals(
            listOf(SleepWindow(10, 20), SleepWindow(10, 25)),
            SleepUsageSnapshot.completedWindows(listOf(SleepWindow(10, 20), SleepWindow(10, 25)), 30)
        )
    }

    @Test fun `usage access is resolved for the exact night window`() {
        val result = UsageSnapshotResult(
            snapshots = listOf(
                UsageSnapshot(10, 20, accessAvailable = true, intervals = emptyList(), capturedAtMillis = 20),
                UsageSnapshot(20, 30, accessAvailable = false, intervals = emptyList(), capturedAtMillis = 30)
            ),
            intervals = emptyList()
        )

        assertTrue(result.availableFor(SleepWindow(10, 20)))
        assertFalse(result.availableFor(SleepWindow(20, 30)))
        assertFalse(result.availableFor(listOf(SleepWindow(10, 20), SleepWindow(20, 30))))
    }
}
