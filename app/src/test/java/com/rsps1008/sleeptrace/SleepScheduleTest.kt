package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepScheduleTest {
    private val zone = ZoneId.of("UTC")
    private val schedule = SleepSchedule(
        startMinute = 23 * 60,
        endMinute = 7 * 60,
        weekendStartMinute = 1 * 60,
        weekendEndMinute = 9 * 60
    )

    private fun at(day: Int, hour: Int, minute: Int) =
        LocalDate.of(2026, 10, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun `weekday and weekend windows use the day they start`() {
        val friday = requireNotNull(schedule.windowAt(at(2, 23, 30), zone))
        assertEquals(at(2, 23, 0), friday.startMillis)
        // Friday's overnight schedule is capped when Saturday's earlier weekend window begins.
        assertEquals(at(3, 1, 0), friday.endMillis)

        val saturday = requireNotNull(schedule.windowAt(at(3, 1, 30), zone))
        assertEquals(at(3, 1, 0), saturday.startMillis)
        assertEquals(at(3, 9, 0), saturday.endMillis)

        assertNull(schedule.windowAt(at(4, 0, 30), zone))
        val sunday = requireNotNull(schedule.windowAt(at(4, 1, 30), zone))
        assertEquals(at(4, 1, 0), sunday.startMillis)
    }

    @Test fun `intersections return non-overlapping weekday and weekend windows`() {
        val windows = schedule.windowsBetween(at(2, 22, 0), at(3, 10, 0), zone)
        assertEquals(2, windows.size)
        assertEquals(at(2, 23, 0), windows[0].startMillis)
        assertEquals(at(3, 1, 0), windows[0].endMillis)
        assertEquals(at(3, 1, 0), windows[1].startMillis)
        assertTrue(windows[0].endMillis <= windows[1].startMillis)
    }

    @Test fun `legacy daily schedule remains daily and a full-day schedule needs no boundary`() {
        val daily = SleepSchedule(23 * 60, 7 * 60)
        assertTrue(daily.label().startsWith("每日 "))
        assertTrue(daily.requiresWindowBoundary())

        val fullDay = SleepSchedule(0, 0)
        assertFalse(fullDay.requiresWindowBoundary())
        assertEquals(at(3, 0, 0), requireNotNull(fullDay.windowAt(at(3, 12, 0), zone)).startMillis)
    }

    @Test fun `next alarm boundary follows the weekend override`() {
        val next = requireNotNull(SleepWindowScheduler.nextBoundary(schedule, at(2, 22, 0), zone))
        assertEquals(at(2, 22, 45), next.atMillis)
        assertTrue(next.isClassificationStart)

        val windowStart = requireNotNull(SleepWindowScheduler.nextBoundary(schedule, next.atMillis, zone))
        assertEquals(at(2, 23, 0), windowStart.atMillis)
        assertTrue(windowStart.isWindowStart)
    }

    @Test fun `classify subscription opens fifteen minutes before the selected schedule`() {
        assertNull(schedule.classificationWindowAt(at(2, 22, 44), zone))
        assertEquals(at(2, 23, 0), schedule.classificationWindowAt(at(2, 22, 45), zone)?.startMillis)
        assertEquals(at(2, 23, 0), schedule.classificationWindowAt(at(2, 23, 0), zone)?.startMillis)
        assertNull(schedule.classificationWindowAt(at(3, 12, 0), zone))
    }

    @Test fun `foreground service is restricted to configured windows`() {
        assertTrue(SleepWindowScheduler.shouldRunForegroundService(schedule, at(2, 23, 30), zone))
        assertFalse(SleepWindowScheduler.shouldRunForegroundService(schedule, at(3, 12, 0), zone))
        assertTrue(SleepWindowScheduler.shouldRunForegroundService(SleepSchedule(0, 0), at(3, 12, 0), zone))
        assertFalse(SleepWindowScheduler.shouldRunForegroundService(null, at(3, 12, 0), zone))
    }
}
