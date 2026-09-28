package com.rsps1008.sleeptrace.motion

import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

const val MINUTE_MS = 60_000L
// BED/BEDSIDE remain readable for old minute records. New recordings use AUTO.
enum class Placement { BED, BEDSIDE, AUTO, UNKNOWN }
enum class MotionLevel { QUIET, ACTIVE, UNKNOWN }

data class SamplingPlan(val periodUs: Int, val latencyUs: Int) {
    companion object {
        fun choose(charging: Boolean, fifoCount: Int, minDelayUs: Int = 0): SamplingPlan {
            val period = maxOf(if (fifoCount <= 0) 1_000_000 else if (charging) 100_000 else 200_000, minDelayUs)
            // Leave FIFO headroom. A requested latency is an upper bound, not a guarantee.
            val latency = if (fifoCount <= 0) 0 else minOf(60_000_000L, fifoCount.toLong() * period * 8 / 10).toInt()
            return SamplingPlan(period, latency)
        }
    }
}

data class MotionMinute(
    val startMillis: Long, val coveredMillis: Long, val activeMillis: Long,
    val squaredDeltaTime: Double, val sampleCount: Int, val placement: Placement
) {
    val rms: Double get() = if (coveredMillis == 0L) 0.0 else sqrt(squaredDeltaTime / coveredMillis)
    val level: MotionLevel get() = when {
        coveredMillis < 45_000 -> MotionLevel.UNKNOWN
        activeMillis.toDouble() / coveredMillis >= 0.05 || rms >= 0.20 -> MotionLevel.ACTIVE
        else -> MotionLevel.QUIET
    }
}

/** Works on event timestamps, never delivery time: FIFO bursts must not look like motion bursts. */
class MotionAccumulator(private val plan: SamplingPlan, private val placement: Placement) {
    private data class Bucket(var covered: Long = 0, var active: Long = 0, var squared: Double = 0.0, var count: Int = 0)
    private val buckets = sortedMapOf<Long, Bucket>()
    private var lastTime = Long.MIN_VALUE
    private var last = doubleArrayOf(0.0, 0.0, 0.0)

    fun add(timeMillis: Long, x: Double, y: Double, z: Double) {
        if (timeMillis <= lastTime || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val values = doubleArrayOf(x, y, z)
        val bucket = buckets.getOrPut(timeMillis / MINUTE_MS * MINUTE_MS) { Bucket() }
        bucket.count++
        if (lastTime != Long.MIN_VALUE) {
            val dt = timeMillis - lastTime
            if (dt <= maxOf(1_500L, plan.periodUs / 1000L * 3)) {
                val deltaSquared = values.indices.sumOf { (values[it] - last[it]) * (values[it] - last[it]) }
                var cursor = lastTime
                while (cursor < timeMillis) {
                    val key = cursor / MINUTE_MS * MINUTE_MS
                    val end = minOf(timeMillis, key + MINUTE_MS)
                    val part = buckets.getOrPut(key) { Bucket() }
                    val duration = end - cursor
                    part.covered += duration
                    if (deltaSquared >= 0.15 * 0.15) part.active += duration
                    part.squared += deltaSquared * duration
                    cursor = end
                }
            }
        }
        lastTime = timeMillis
        last = values
    }

    fun drain(throughMillis: Long, includePartial: Boolean = false): List<MotionMinute> {
        val keys = buckets.keys.filter { includePartial || it + MINUTE_MS <= throughMillis }
        return keys.map { key ->
            val value = buckets.remove(key)!!
            MotionMinute(key, value.covered, value.active, value.squared, value.count, placement)
        }
    }
}

data class MotionWindow(val start: Long, val end: Long)
fun SleepSchedule.windowAt(time: Long, zone: ZoneId = ZoneId.systemDefault()): MotionWindow {
    val local = Instant.ofEpochMilli(time).atZone(zone)
    var date = local.toLocalDate()
    val minute = local.hour * 60 + local.minute
    if (endMinute <= startMinute && minute < startMinute) date = date.minusDays(1)
    val start = date.atStartOfDay().plusMinutes(startMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
    val endDate = if (endMinute <= startMinute) date.plusDays(1) else date
    val end = endDate.atStartOfDay().plusMinutes(endMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
    return MotionWindow(start, end)
}

/** Experimental motion-only fallback. Accepted sessions sync automatically without sleep staging. */
object MotionSleepEstimator {
    fun annotate(session: SleepSession, minutes: List<MotionMinute>): SleepSession {
        val bed = minutes.filter { it.placement == Placement.BED && it.startMillis >= session.startMillis && it.startMillis + MINUTE_MS <= session.endMillis && it.level != MotionLevel.UNKNOWN }
        if (bed.size < 30 || bed.size * MINUTE_MS < (session.endMillis - session.startMillis) / 2) return session
        val active = bed.count { it.level == MotionLevel.ACTIVE }
        val conflicts = active.toDouble() / bed.size >= 0.30
        return session.copy(
            confidence = if (conflicts) (session.confidence - 30).coerceAtLeast(0) else session.confidence,
            reason = session.reason + "；床上動作摘要 ${bed.size} 分鐘，活動 $active 分鐘" + if (conflicts) "，已降低參考分數" else "（非分期）"
        )
    }

    fun estimate(minutes: List<MotionMinute>, usage: List<UsageInterval>, schedule: SleepSchedule, now: Long): List<SleepSession> {
        return minutes.groupBy { schedule.windowAt(it.startMillis) }.mapNotNull { (window, all) ->
            if (window.end > now) return@mapNotNull null
            val usable = all.filter { it.startMillis >= window.start && it.startMillis + MINUTE_MS <= window.end }.sortedBy { it.startMillis }
            var quietStart: Long? = null
            var quietCount = 0
            var sleepStart: Long? = null
            var activeStart: Long? = null
            var previousEnd: Long? = null
            val runs = mutableListOf<Pair<Long, Long>>()
            fun close(end: Long) {
                sleepStart?.let { if (end - it >= 30 * MINUTE_MS) runs += it to end }
                quietStart = null; quietCount = 0; sleepStart = null; activeStart = null
            }
            usable.forEach { minute ->
                val start = minute.startMillis
                if (previousEnd != null && previousEnd != start) close(previousEnd!!)
                val phoneInUse = usage.any { it.startMillis < start + MINUTE_MS && it.endMillis > start }
                if (minute.placement != Placement.BED || minute.level == MotionLevel.UNKNOWN || phoneInUse) {
                    close(start)
                } else if (minute.level == MotionLevel.QUIET) {
                    if (quietStart == null) quietStart = start
                    quietCount++
                    if (quietCount >= 20 && sleepStart == null) sleepStart = quietStart
                    activeStart = null
                } else {
                    quietStart = null; quietCount = 0
                    if (activeStart == null) activeStart = start
                    if (start + MINUTE_MS - activeStart!! >= 5 * MINUTE_MS) close(activeStart!!)
                }
                previousEnd = start + MINUTE_MS
            }
            close(activeStart ?: previousEnd ?: window.start)
            val longest = runs.maxByOrNull { it.second - it.first } ?: return@mapNotNull null
            SleepSession(
                id = "motion-${window.start}", startMillis = longest.first, endMillis = longest.second,
                confidence = 50, awakeMillis = 0, state = SyncState.PENDING,
                reason = "加速度計推估：持續安靜至少 20 分鐘，App 自動採用最長區段；非睡眠分期"
            )
        }
    }
}
