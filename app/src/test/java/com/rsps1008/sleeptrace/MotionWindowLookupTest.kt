package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MotionWindowLookupTest {
    private val zone = ZoneId.of("UTC")
    private val nominal = SleepSchedule(23 * 60, 7 * 60)
    private val window = nominal.windowForStartDate(LocalDate.of(2026, 9, 30), zone)

    @Test fun `eight hours of ten hertz FIFO events resolve the calendar only once`() {
        var resolutions = 0
        val cache = MotionWindowLookup { schedule, at -> resolutions++; schedule.windowAt(at, zone) }
        cache.update(nominal)
        for (time in window.start until window.end step 100) assertEquals(window, cache.windowAt(time))
        assertEquals(1, resolutions)
        assertNull(cache.windowAt(window.end))
        assertEquals(2, resolutions)
        assertNull(cache.windowAt(window.start - 1))
    }

    @Test fun `early closure and extension invalidate old cached bounds including queued FIFO samples`() {
        val cache = MotionWindowLookup { schedule, at -> schedule.windowAt(at, zone) }
        cache.update(nominal)
        assertEquals(window, cache.windowAt(window.start))
        val earlyEnd = window.end - 40 * MINUTE_MS
        cache.update(nominal.copy(observationEnds = mapOf(window to earlyEnd)))
        assertNull(cache.windowAt(earlyEnd))
        assertEquals(SleepWindow(window.start, earlyEnd), cache.windowAt(earlyEnd - 1))
        val extendedEnd = window.end + 30 * MINUTE_MS
        cache.update(nominal.copy(observationEnds = mapOf(window to extendedEnd)))
        assertEquals(SleepWindow(window.start, extendedEnd), cache.windowAt(window.end))
        assertNull(cache.windowAt(extendedEnd))
        cache.update(null)
        assertNull(cache.windowAt(window.start))
    }

    @Test fun `adjacent full-day windows and zone refresh do not reuse a stale window`() {
        var effectiveZone = zone
        val cache = MotionWindowLookup { schedule, at -> schedule.windowAt(at, effectiveZone) }
        val fullDay = SleepSchedule(0, 0)
        cache.update(fullDay)
        val old = cache.windowAt(window.start)!!
        assertEquals(old.end, cache.windowAt(old.end)!!.start)
        effectiveZone = ZoneId.of("Asia/Taipei")
        cache.update(fullDay)
        assertEquals(fullDay.windowAt(window.start, effectiveZone), cache.windowAt(window.start))
    }
}
