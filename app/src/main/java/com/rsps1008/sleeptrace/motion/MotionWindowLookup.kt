package com.rsps1008.sleeptrace.motion

import com.rsps1008.sleeptrace.sleep.SleepSchedule

/** Handler-thread-only cache. FIFO callbacks normally share one already-resolved window. */
internal class MotionWindowLookup(
    private val resolve: (SleepSchedule, Long) -> MotionWindow? = { schedule, at -> schedule.windowAt(at) }
) {
    private var schedule: SleepSchedule? = null
    private var cached: MotionWindow? = null

    /** Also invalidate for time-zone changes and extensions, even if the settings are equal. */
    fun update(schedule: SleepSchedule?) {
        this.schedule = schedule
        cached = null
    }

    fun windowAt(eventTimeMillis: Long): MotionWindow? {
        cached?.let { if (eventTimeMillis >= it.start && eventTimeMillis < it.end) return it }
        return schedule?.let { resolve(it, eventTimeMillis) }.also { cached = it }
    }
}
