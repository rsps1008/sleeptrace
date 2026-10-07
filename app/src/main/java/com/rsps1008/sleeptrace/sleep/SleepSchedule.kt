package com.rsps1008.sleeptrace.sleep

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class SleepWindow(val startMillis: Long, val endMillis: Long) {
    val start: Long get() = startMillis
    val end: Long get() = endMillis
}

/** A daily schedule, optionally with a separate Saturday/Sunday window. */
data class SleepSchedule(
    val startMinute: Int,
    val endMinute: Int,
    val weekendStartMinute: Int? = null,
    val weekendEndMinute: Int? = null,
    /** Persisted effective ends, keyed by the original scheduled window. Labels remain user settings. */
    val observationEnds: Map<SleepWindow, Long> = emptyMap(),
    /** Only persisted closed observation windows may be reconciled in saver mode. */
    val closedObservationWindows: Set<SleepWindow> = emptySet(),
    /** Downloaded Taiwan calendar values override weekend defaults, including make-up workdays. */
    val holidayCalendar: Map<LocalDate, Boolean> = emptyMap()
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY)
        require(endMinute in 0 until MINUTES_PER_DAY)
        require((weekendStartMinute == null) == (weekendEndMinute == null))
        require(weekendStartMinute == null || weekendStartMinute in 0 until MINUTES_PER_DAY)
        require(weekendEndMinute == null || weekendEndMinute in 0 until MINUTES_PER_DAY)
    }

    fun label(): String {
        val weekday = rangeLabel(startMinute, endMinute)
        return if (weekendStartMinute == null || weekendEndMinute == null) "每日 $weekday"
        else "平日 $weekday；週末／假日 ${rangeLabel(weekendStartMinute, weekendEndMinute)}"
    }

    fun windowForStartDate(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SleepWindow {
        val start = startMinuteFor(date)
        val end = endMinuteFor(date)
        val startAt = date.atStartOfDay().plusMinutes(start.toLong()).atZone(zone).toInstant().toEpochMilli()
        val endDate = if (end <= start) date.plusDays(1) else date
        val requestedEnd = endDate.atStartOfDay().plusMinutes(end.toLong()).atZone(zone).toInstant().toEpochMilli()
        val nextDate = date.plusDays(1)
        val nextStart = nextDate.atStartOfDay().plusMinutes(startMinuteFor(nextDate).toLong())
            .atZone(zone).toInstant().toEpochMilli()
        val endAt = minOf(requestedEnd, nextStart)
        val nominal = SleepWindow(startAt, endAt)
        val effectiveEnd = if (isFullDayForStartDate(date)) endAt
            else observationEnds[nominal]?.coerceIn(startAt + 1, nextStart) ?: endAt
        return SleepWindow(startAt, effectiveEnd)
    }

    /** Configuration semantics, independent of DST duration and next-day clipping. */
    fun isFullDayForStartDate(date: LocalDate): Boolean = startMinuteFor(date) == endMinuteFor(date)

    /** Returns the scheduled window containing this instant, if one is active. */
    fun windowAt(timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): SleepWindow? {
        val date = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        return sequenceOf(date.minusDays(1), date)
            .map { windowForStartDate(it, zone) }
            .firstOrNull { timeMillis >= it.startMillis && timeMillis < it.endMillis }
    }

    /**
     * Window whose classify subscription should already be active at this instant.
     * Saver mode may keep the passive Play-services subscription briefly after the scheduled
     * end, so a later waking classification can refine its coarse schedule estimate.  This
     * does not register an alarm or wake the device.
     */
    fun classificationWindowAt(
        timeMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        postWindowMillis: Long = 0L
    ): SleepWindow? =
        windowsBetween(
            timeMillis - CLASSIFICATION_LEAD_MILLIS - postWindowMillis,
            timeMillis + CLASSIFICATION_LEAD_MILLIS + 1,
            zone
        ).firstOrNull {
            timeMillis >= it.startMillis - CLASSIFICATION_LEAD_MILLIS &&
                timeMillis < it.endMillis + postWindowMillis
        }

    /**
     * Saver classification begins at the configured window start and remains passively subscribed
     * only until that night's sufficient waking evidence closes the observation.  No end alarm is
     * needed: Play services delivers the post-window classification itself.
     */
    fun saverClassificationWindowAt(timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): SleepWindow? {
        val date = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        return sequenceOf(date.minusDays(1), date).map { startDate ->
            windowForStartDate(startDate, zone) to windowForStartDate(startDate.plusDays(1), zone).startMillis
        }.firstOrNull { (window, nextStart) ->
            timeMillis >= window.startMillis && timeMillis < nextStart && !isObservationClosed(window)
        }?.first
    }

    fun isAfterWindowBeforeNextStart(timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val date = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        return sequenceOf(date.minusDays(1), date).any { startDate ->
            val window = windowForStartDate(startDate, zone)
            timeMillis >= window.endMillis && timeMillis < windowForStartDate(startDate.plusDays(1), zone).startMillis
        }
    }

    /** Full scheduled windows that overlap the supplied time range. */
    fun windowsBetween(startMillis: Long, endMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<SleepWindow> {
        if (endMillis <= startMillis) return emptyList()
        var date = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate().minusDays(1)
        val lastDate = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
        val result = mutableListOf<SleepWindow>()
        while (!date.isAfter(lastDate)) {
            val window = windowForStartDate(date, zone)
            if (window.startMillis < endMillis && window.endMillis > startMillis) result += window
            date = date.plusDays(1)
        }
        return result
    }

    fun intersections(startMillis: Long, endMillis: Long): List<UsageInterval> {
        if (endMillis <= startMillis) return emptyList()
        return windowsBetween(startMillis, endMillis).map {
            UsageInterval(maxOf(it.startMillis, startMillis), minOf(it.endMillis, endMillis))
        }
    }

    fun overlaps(startMillis: Long, endMillis: Long) = intersections(startMillis, endMillis).isNotEmpty()

    fun requiresWindowBoundary(): Boolean =
        startMinute != endMinute || weekendStartMinute?.let { it != weekendEndMinute } == true

    fun isObservationClosed(window: SleepWindow): Boolean =
        closedObservationWindows.any { it.startMillis == window.startMillis }

    private fun rangeLabel(start: Int, end: Int): String =
        "%02d:%02d–%02d:%02d".format(start / 60, start % 60, end / 60, end % 60)

    private fun startMinuteFor(date: LocalDate): Int =
        if (holidayCalendar[date] ?: (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY))
            weekendStartMinute ?: startMinute else startMinute

    private fun endMinuteFor(date: LocalDate): Int =
        if (holidayCalendar[date] ?: (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY))
            weekendEndMinute ?: endMinute else endMinute

    companion object {
        private const val MINUTES_PER_DAY = 24 * 60
        const val CLASSIFICATION_LEAD_MILLIS = 15 * 60 * 1000L
        const val SAVER_WAKE_CLASSIFICATION_GRACE_MILLIS = 16 * 60 * 60 * 1000L
    }
}
