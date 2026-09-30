package com.rsps1008.sleeptrace.motion

import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepWindow
import kotlin.math.sqrt

const val MINUTE_MS = 60_000L
// BED/BEDSIDE remain readable for old minute records. New recordings use AUTO.
enum class Placement { BED, BEDSIDE, AUTO, UNKNOWN }
enum class MotionLevel { QUIET, ACTIVE, UNKNOWN }

data class SamplingPlan(val periodUs: Int, val latencyUs: Int) {
    companion object {
        fun choose(fifoCount: Int, minDelayUs: Int = 0): SamplingPlan {
            // Approximate sleep timing does not need high-rate raw motion. Keep one low-power
            // plan on battery and external power so charging never silently increases sensing.
            val period = maxOf(1_000_000, minDelayUs)
            // Use 80% of the advertised FIFO instead of imposing an app-defined time cap.
            // SensorManager accepts microseconds as Int, so clamp only to the API representation.
            val latency = if (fifoCount <= 0) 0 else minOf(
                Int.MAX_VALUE.toLong(), fifoCount.toLong() * period * 8 / 10
            ).toInt()
            return SamplingPlan(period, latency)
        }
    }
}

object SleepClassificationTrigger {
    const val MIN_CONFIDENCE = 80
    const val MAX_EVENT_AGE_MILLIS = 20 * MINUTE_MS
    const val FALLBACK_DELAY_MILLIS = 2 * 60 * MINUTE_MS
    const val CLASSIFICATION_LEAD_MILLIS = SleepSchedule.CLASSIFICATION_LEAD_MILLIS
    private const val FUTURE_TOLERANCE_MILLIS = 2 * MINUTE_MS

    /** A recent high-confidence Google classification may start motion capture for this window. */
    fun shouldStart(samples: List<ClassificationSample>, window: MotionWindow, now: Long): Boolean =
        samples.any {
            it.confidence >= MIN_CONFIDENCE &&
                it.timeMillis >= window.start - CLASSIFICATION_LEAD_MILLIS && it.timeMillis < window.end &&
                it.timeMillis >= now - MAX_EVENT_AGE_MILLIS &&
                it.timeMillis <= now + FUTURE_TOLERANCE_MILLIS
        }

    /**
     * Do not leave a whole scheduled night without a motion backup when Google delivery is late.
     * Screen-off duration is observable without querying UsageStats; still keep the normal low-battery stop.
     */
    fun shouldFallback(window: MotionWindow, now: Long, screenOffSince: Long?): Boolean =
        now >= window.start + FALLBACK_DELAY_MILLIS &&
            screenOffSince != null && screenOffSince <= now - FALLBACK_DELAY_MILLIS
}

data class MotionMinute(
    val startMillis: Long, val coveredMillis: Long, val activeMillis: Long,
    val squaredDeltaTime: Double, val sampleCount: Int, val placement: Placement,
    val featureVersion: Int = 2
) {
    val rms: Double get() = if (coveredMillis == 0L) 0.0 else sqrt(squaredDeltaTime / coveredMillis)
    val level: MotionLevel get() = when {
        coveredMillis < 45_000 -> MotionLevel.UNKNOWN
        activeMillis.toDouble() / coveredMillis >= 0.05 || rms >= 0.20 -> MotionLevel.ACTIVE
        else -> MotionLevel.QUIET
    }
}

/** Works on event timestamps, never delivery time: FIFO bursts must not look like motion bursts. */
class MotionAccumulator(@Suppress("UNUSED_PARAMETER") plan: SamplingPlan, private val placement: Placement) {
    companion object { const val FEATURE_SAMPLE_PERIOD_MS = 1_000L }
    private data class Bucket(var covered: Long = 0, var active: Long = 0, var squared: Double = 0.0, var count: Int = 0)
    private val buckets = sortedMapOf<Long, Bucket>()
    private var lastTime = Long.MIN_VALUE
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0

    fun add(timeMillis: Long, x: Double, y: Double, z: Double): Boolean {
        if (timeMillis <= lastTime || !x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        // First event in each event-time second: constant cost, no delivery-time/FIFO dependency.
        // Quantize the representative timestamp too; callbacks inside this second add no features.
        val featureTime = Math.floorDiv(timeMillis, FEATURE_SAMPLE_PERIOD_MS) * FEATURE_SAMPLE_PERIOD_MS
        if (featureTime <= lastTime) return false
        val bucket = buckets.getOrPut(Math.floorDiv(featureTime, MINUTE_MS) * MINUTE_MS) { Bucket() }
        bucket.count++
        if (lastTime != Long.MIN_VALUE) {
            val dt = featureTime - lastTime
            if (dt == FEATURE_SAMPLE_PERIOD_MS) {
                val deltaX = x - lastX
                val deltaY = y - lastY
                val deltaZ = z - lastZ
                val deltaSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                var cursor = lastTime
                while (cursor < featureTime) {
                    val key = cursor / MINUTE_MS * MINUTE_MS
                    val end = minOf(featureTime, key + MINUTE_MS)
                    val part = buckets.getOrPut(key) { Bucket() }
                    val duration = end - cursor
                    part.covered += duration
                    if (deltaSquared >= 0.15 * 0.15) part.active += duration
                    part.squared += deltaSquared * duration
                    cursor = end
                }
            }
        }
        lastTime = featureTime
        lastX = x
        lastY = y
        lastZ = z
        return true
    }

    fun drain(throughMillis: Long, includePartial: Boolean = false): List<MotionMinute> {
        val keys = buckets.keys.filter { includePartial || it + MINUTE_MS <= throughMillis }
        return keys.map { key ->
            val value = buckets.remove(key)!!
            MotionMinute(key, value.covered, value.active, value.squared, value.count, placement)
        }
    }
}

typealias MotionWindow = SleepWindow

/** Experimental motion-only fallback. Accepted sessions sync automatically without sleep staging. */
object MotionSleepEstimator {
    fun annotate(session: SleepSession, minutes: List<MotionMinute>): SleepSession {
        val bed = minutes.filter { it.placement == Placement.BED && it.startMillis >= session.startMillis && it.startMillis + MINUTE_MS <= session.endMillis && it.level != MotionLevel.UNKNOWN }
        if (bed.size < 30 || bed.size * MINUTE_MS < (session.endMillis - session.startMillis) / 2) return session
        val active = bed.count { it.level == MotionLevel.ACTIVE }
        val conflicts = active.toDouble() / bed.size >= 0.30
        return session.copy(
            confidence = if (conflicts) (session.confidence - 30).coerceAtLeast(0) else session.confidence,
            reason = session.reason + "；床上動作摘要 ${bed.size} 分鐘，活動 $active 分鐘" +
                if (conflicts) "，已降低參考分數" else "（僅作相對活動參考）"
        )
    }

    fun estimate(
        minutes: List<MotionMinute>,
        usage: List<UsageInterval>,
        schedule: SleepSchedule,
        now: Long,
        usageAvailable: (SleepWindow) -> Boolean = { true }
    ): List<SleepSession> {
        return minutes.mapNotNull { minute -> schedule.windowAt(minute.startMillis)?.let { it to minute } }
            .groupBy({ it.first }, { it.second }).flatMap { (window, all) ->
            if (window.end > now) return@flatMap emptyList()
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
            runs.map { run ->
                SleepSession(
                    id = "motion-${window.start}-${run.first}", startMillis = run.first, endMillis = run.second,
                    confidence = 50, awakeMillis = 0, state = SyncState.PENDING,
                    reason = "加速度計推估：持續安靜至少 20 分鐘；分段睡眠會分別保存；非睡眠分期" +
                        if (usageAvailable(window)) "" else "；未授予使用情況存取權，無法排除手機使用",
                    usageSnapshotApplied = true
                )
            }
        }
    }
}
