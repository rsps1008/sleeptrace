package com.rsps1008.sleeptrace.sleep

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class SleepSchedule(val startMinute: Int, val endMinute: Int) {
    fun label(): String = "%02d:%02d–%02d:%02d".format(startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60)
    fun intersections(startMillis: Long, endMillis: Long): List<UsageInterval> {
        if (endMillis <= startMillis) return emptyList()
        val zone = ZoneId.systemDefault()
        var day = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate().minusDays(1)
        val lastDay = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
        val result = mutableListOf<UsageInterval>()
        while (!day.isAfter(lastDay)) {
            val start = day.atTime(LocalTime.of(startMinute / 60, startMinute % 60)).atZone(zone).toInstant().toEpochMilli()
            val endDay = if (endMinute <= startMinute) day.plusDays(1) else day
            val end = endDay.atTime(LocalTime.of(endMinute / 60, endMinute % 60)).atZone(zone).toInstant().toEpochMilli()
            if (start < endMillis && end > startMillis) result += UsageInterval(maxOf(start, startMillis), minOf(end, endMillis))
            day = day.plusDays(1)
        }
        return result
    }
    fun overlaps(startMillis: Long, endMillis: Long) = intersections(startMillis, endMillis).isNotEmpty()
}
