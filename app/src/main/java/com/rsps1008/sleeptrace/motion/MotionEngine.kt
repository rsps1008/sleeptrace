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

object MotionFeaturePolicy {
    /** Newer compatible resamplers supersede older cadence summaries without mixing them. */
    fun storagePriority(featureVersion: Int): Int = when (featureVersion) {
        MotionAccumulator.CURRENT_FEATURE_VERSION -> 7
        MotionAccumulator.ONE_HZ_FEATURE_VERSION -> 6
        MotionAccumulator.STRICT_CADENCE_FEATURE_VERSION -> 5
        MotionAccumulator.CADENCE_ANCHOR_FEATURE_VERSION -> 4
        MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION -> 3
        MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION -> 2
        MotionAccumulator.LEGACY_CALLBACK_FEATURE_VERSION -> 1
        else -> 0
    }

    /** v3 can report movement, but neither its quiet minutes nor v1/v2 can support sleep inference. */
    fun supportsSleepConflictEvidence(featureVersion: Int, level: MotionLevel): Boolean = when (featureVersion) {
        MotionAccumulator.CURRENT_FEATURE_VERSION,
        MotionAccumulator.ONE_HZ_FEATURE_VERSION,
        MotionAccumulator.STRICT_CADENCE_FEATURE_VERSION,
        MotionAccumulator.CADENCE_ANCHOR_FEATURE_VERSION -> level != MotionLevel.UNKNOWN
        MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION -> level == MotionLevel.ACTIVE
        else -> false
    }
}

data class SamplingPlan(
    /** The period passed to SensorManager.registerListener. */
    val periodUs: Int,
    val latencyUs: Int,
    /** The policy target before applying the sensor's minimum-delay constraint. */
    val targetPeriodUs: Int = periodUs
) {
    companion object {
        /** Formal release policy: ten timestamp-normalized motion features per second. */
        const val DEFAULT_TARGET_PERIOD_US = 100_000
        /** Historical/default debug comparison rate; not used by the release policy. */
        const val LEGACY_ONE_HZ_TARGET_PERIOD_US = 1_000_000

        fun choose(
            fifoMaxEventCount: Int,
            minDelayUs: Int = 0,
            maxDelayUs: Int = 0,
            fifoReservedEventCount: Int = 0,
            targetPeriodUs: Int = DEFAULT_TARGET_PERIOD_US
        ): SamplingPlan {
            // Keep one explicit policy on battery and external power so charging never
            // silently changes feature semantics or increases sensing beyond 10 Hz.
            val requestedPeriodUs = maxOf(targetPeriodUs, minDelayUs)
            // Some drivers deliver at maxDelay even when a slower period was requested. Size the
            // batching window for that faster possible event cadence so the FIFO cannot overflow.
            val possibleEventPeriodUs = if (maxDelayUs > 0) {
                minOf(requestedPeriodUs, maxDelayUs)
            } else {
                requestedPeriodUs
            }
            // Reserved capacity is the per-sensor guarantee. Fall back to the shared maximum only
            // when no reservation is advertised, and leave 20% headroom.
            val availableFifoEvents = fifoReservedEventCount.takeIf { it > 0 }
                ?: fifoMaxEventCount.coerceAtLeast(0)
            val latency = if (availableFifoEvents <= 0) 0 else {
                val fifoSpanUs = availableFifoEvents.toLong() * possibleEventPeriodUs.toLong()
                // Compute floor(span * 0.8) without overflowing on defensive
                // synthetic Int.MAX inputs; real Android FIFO sizes are much smaller.
                val withHeadroom = (fifoSpanUs / 10) * 8 + (fifoSpanUs % 10) * 8 / 10
                minOf(Int.MAX_VALUE.toLong(), withHeadroom).toInt()
            }
            return SamplingPlan(requestedPeriodUs, latency, targetPeriodUs)
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
    val featureVersion: Int = MotionAccumulator.CURRENT_FEATURE_VERSION,
    // Nullable fields mean not measured by legacy summaries, never zero-filled.
    val maxDelta: Double? = null, val movementEvents: Int? = null,
    val longestActiveMillis: Long? = null, val quietTailMillis: Long? = null,
    val longestGapMillis: Long? = null, val postureDelta: Double? = null,
    val recordingId: Long? = null, val observedStart: Long? = null, val observedEnd: Long? = null,
    val coupling: CouplingEvidence? = null
) {
    val supportsCurrentStaging: Boolean get() = featureVersion in setOf(
        MotionAccumulator.CURRENT_FEATURE_VERSION,
        MotionAccumulator.ONE_HZ_FEATURE_VERSION,
        MotionAccumulator.STRICT_CADENCE_FEATURE_VERSION,
        MotionAccumulator.CADENCE_ANCHOR_FEATURE_VERSION
    )
    val rms: Double get() = if (coveredMillis == 0L) 0.0 else sqrt(squaredDeltaTime / coveredMillis)
    val level: MotionLevel get() = when {
        coveredMillis < 45_000 -> MotionLevel.UNKNOWN
        activeMillis.toDouble() / coveredMillis >= 0.05 || rms >= 0.20 -> MotionLevel.ACTIVE
        else -> MotionLevel.QUIET
    }
}

/** Works on event timestamps, never delivery time: FIFO bursts must not look like motion bursts. */
class MotionAccumulator(plan: SamplingPlan, private val placement: Placement, private val recordingEpochId: Long? = null) {
    companion object {
        const val FEATURE_SAMPLE_PERIOD_MS = 100L
        const val LEGACY_FEATURE_SAMPLE_PERIOD_MS = 1_000L
        const val LEGACY_CALLBACK_FEATURE_VERSION = 1
        const val LEGACY_FIXED_FEATURE_VERSION = 2
        const val CADENCE_INCOMPATIBLE_FEATURE_VERSION = 3
        const val CADENCE_ANCHOR_FEATURE_VERSION = 4
        /** v5 treated a valid adjacent pair as a gap when its cadence anchor skipped a slot. */
        const val STRICT_CADENCE_FEATURE_VERSION = 5
        /** v6 normalized arbitrary callbacks to approximately one representative per second. */
        const val ONE_HZ_FEATURE_VERSION = 6
        /** v7 preserves approximately ten timestamp-normalized representatives per second. */
        const val CURRENT_FEATURE_VERSION = 7
        private const val MAX_TEN_HZ_REGISTERED_PERIOD_US = SamplingPlan.DEFAULT_TARGET_PERIOD_US
        private const val MAX_ONE_HZ_STAGING_CADENCE_MS = 1_200L
        /** Coalesce the exact +1 s echo introduced by the v7 legacy-scale energy channel. */
        private const val MOVEMENT_EVENT_REFRACTORY_MILLIS = LEGACY_FEATURE_SAMPLE_PERIOD_MS

        fun featurePeriodMillis(targetPeriodUs: Int, registeredPeriodUs: Int): Long =
            if (targetPeriodUs <= SamplingPlan.DEFAULT_TARGET_PERIOD_US &&
                registeredPeriodUs <= MAX_TEN_HZ_REGISTERED_PERIOD_US) {
                FEATURE_SAMPLE_PERIOD_MS
            } else {
                maxOf(LEGACY_FEATURE_SAMPLE_PERIOD_MS, registeredPeriodUs / 1_000L)
            }
    }
    private val featurePeriodMs = featurePeriodMillis(plan.targetPeriodUs, plan.periodUs)
    private val featureVersion = when {
        featurePeriodMs == FEATURE_SAMPLE_PERIOD_MS -> CURRENT_FEATURE_VERSION
        featurePeriodMs <= MAX_ONE_HZ_STAGING_CADENCE_MS -> ONE_HZ_FEATURE_VERSION
        else -> CADENCE_INCOMPATIBLE_FEATURE_VERSION
    }
    // Keep the 10 Hz acceptance band narrower than half a slot so faster OEM
    // callback streams cannot silently become 20+ Hz features.
    private val jitterToleranceMs = if (featureVersion == CURRENT_FEATURE_VERSION) 35L else 100L
    private val postureWindowSamples = maxOf(1, (10_000L / featurePeriodMs).toInt())
    private data class Bucket(var covered: Long = 0, var active: Long = 0, var squared: Double = 0.0, var count: Int = 0,
        var peak: Double = 0.0, var events: Int = 0, var longestActive: Long = 0, var quietTail: Long = 0,
        var gap: Long = 0, var posture: Double? = null, var first: Long? = null, var end: Long? = null,
        var vectorCount: Int = 0, var sumX: Double = 0.0, var sumY: Double = 0.0, var sumZ: Double = 0.0,
        var reference: Triple<Double, Double, Double>? = null)
    private data class FeatureVector(val timeMillis: Long, val x: Double, val y: Double, val z: Double)
    private val buckets = sortedMapOf<Long, Bucket>()
    /** V7 keeps enough normalized points to measure movement on the legacy one-second scale. */
    private val recentFeatureVectors = java.util.ArrayDeque<FeatureVector>()
    private var recordingId: Long? = null
    private var activeRun = 0L
    private var quietRun = 0L
    private var lastMovementEventTime = Long.MIN_VALUE
    var rejectedEvents: Long = 0; private set
    var rawEventCount: Long = 0; private set
    var intervalSumMillis: Long = 0; private set
    var intervalMaxMillis: Long = 0; private set
    private var lastRawTime = Long.MIN_VALUE
    private var anchorTime = Long.MIN_VALUE
    private var nextFeatureTime = Long.MIN_VALUE
    private var lastFeatureTime = Long.MIN_VALUE
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0

    fun add(timeMillis: Long, x: Double, y: Double, z: Double): Boolean {
        if (timeMillis <= lastRawTime || !x.isFinite() || !y.isFinite() || !z.isFinite()) { rejectedEvents++; return false }
        val rawIntervalMillis = if (lastRawTime == Long.MIN_VALUE) null else timeMillis - lastRawTime
        if (rawIntervalMillis != null) {
            intervalSumMillis += rawIntervalMillis
            intervalMaxMillis = maxOf(intervalMaxMillis, rawIntervalMillis)
        }
        rawEventCount++
        lastRawTime = timeMillis
        if (recordingId == null) recordingId = recordingEpochId ?: timeMillis
        if (anchorTime == Long.MIN_VALUE) {
            anchorTime = timeMillis
            nextFeatureTime = timeMillis
        }
        val isTenHertz = featureVersion == CURRENT_FEATURE_VERSION
        val followsAcceptedCadence = lastFeatureTime != Long.MIN_VALUE &&
            timeMillis - lastFeatureTime in
            (featurePeriodMs - jitterToleranceMs)..(featurePeriodMs + jitterToleranceMs)
        // Legacy one-second summaries need a small re-anchor when a stable 993 ms stream
        // drifts ahead of the original anchor. V7 instead maps every raw callback onto a
        // fixed 100 ms slot and accepts at most one representative per slot; otherwise an
        // 80 ms driver stream could silently become 12.5 Hz features.
        val reanchorFromAcceptedCadence = !isTenHertz &&
            timeMillis < nextFeatureTime - jitterToleranceMs &&
            followsAcceptedCadence && rawIntervalMillis != null && rawIntervalMillis in
            (featurePeriodMs - jitterToleranceMs)..(featurePeriodMs + jitterToleranceMs)
        val featureTime = if (isTenHertz) {
            val elapsed = timeMillis - anchorTime
            val slot = Math.floorDiv(elapsed + featurePeriodMs / 2, featurePeriodMs)
            anchorTime + slot * featurePeriodMs
        } else {
            timeMillis
        }
        if (isTenHertz) {
            if (lastFeatureTime != Long.MIN_VALUE && featureTime <= lastFeatureTime) return false
        } else if (timeMillis < nextFeatureTime - jitterToleranceMs && !reanchorFromAcceptedCadence) {
            return false
        }
        val skippedSlots = if (lastFeatureTime == Long.MIN_VALUE) {
            0L
        } else if (isTenHertz) {
            ((featureTime - lastFeatureTime) / featurePeriodMs - 1L).coerceAtLeast(0L)
        } else if (timeMillis > nextFeatureTime + jitterToleranceMs) {
            (timeMillis - nextFeatureTime) / featurePeriodMs
        } else {
            0L
        }
        val dt = if (lastFeatureTime == Long.MIN_VALUE) 0L else featureTime - lastFeatureTime
        if (!isTenHertz && lastFeatureTime != Long.MIN_VALUE && dt < featurePeriodMs - jitterToleranceMs) return false
        val sampleBucket = buckets.getOrPut(Math.floorDiv(featureTime, MINUTE_MS) * MINUTE_MS) { Bucket() }
        sampleBucket.count++
        sampleBucket.vectorCount++; sampleBucket.sumX += x; sampleBucket.sumY += y; sampleBucket.sumZ += z
        if (sampleBucket.vectorCount == postureWindowSamples) {
            val mean = Triple(
                sampleBucket.sumX / postureWindowSamples,
                sampleBucket.sumY / postureWindowSamples,
                sampleBucket.sumZ / postureWindowSamples
            )
            sampleBucket.reference?.let { ref ->
                val dx = mean.first - ref.first; val dy = mean.second - ref.second; val dz = mean.third - ref.third
                sampleBucket.posture = maxOf(sampleBucket.posture ?: 0.0, sqrt(dx * dx + dy * dy + dz * dz))
            }
            if (sampleBucket.reference == null) sampleBucket.reference = mean
            sampleBucket.vectorCount = 0; sampleBucket.sumX = 0.0; sampleBucket.sumY = 0.0; sampleBucket.sumZ = 0.0
        }
        if (lastFeatureTime != Long.MIN_VALUE) {
            // Coverage describes the interval between the two representative samples. The cadence
            // anchor only selects representatives; it must not turn a valid 1 s (+/- jitter) pair
            // into a gap merely because integer slot advancement reports a skipped slot.
            if (dt in (featurePeriodMs - jitterToleranceMs)..(featurePeriodMs + jitterToleranceMs)) {
                val instantDeltaX = x - lastX
                val instantDeltaY = y - lastY
                val instantDeltaZ = z - lastZ
                val instantDeltaSquared = instantDeltaX * instantDeltaX +
                    instantDeltaY * instantDeltaY + instantDeltaZ * instantDeltaZ
                // Adjacent 100 ms deltas make the same smooth physical movement roughly
                // ten times smaller than a v6 one-second delta. Use the 10 Hz stream for
                // timing, but derive the RMS/active coupling channel from a point exactly
                // one second earlier. This preserves the established m/s² thresholds while
                // the adjacent channel still retains sub-second peaks for handling safety.
                val energyReference = if (isTenHertz) {
                    val target = featureTime - LEGACY_FEATURE_SAMPLE_PERIOD_MS
                    recentFeatureVectors.firstOrNull { it.timeMillis == target }
                } else {
                    null
                }
                val energyDeltaSquared = if (isTenHertz) {
                    energyReference?.let { reference ->
                        val dx = x - reference.x
                        val dy = y - reference.y
                        val dz = z - reference.z
                        dx * dx + dy * dy + dz * dz
                    } ?: 0.0
                } else {
                    instantDeltaSquared
                }
                // Multi-scale energy keeps smooth one-second displacement comparable
                // with v6 while preserving a genuinely faster turn or impact visible
                // only between adjacent 100 ms slots.
                val motionDeltaSquared = maxOf(energyDeltaSquared, instantDeltaSquared)
                var cursor = lastFeatureTime
                while (cursor < featureTime) {
                    val key = cursor / MINUTE_MS * MINUTE_MS
                    val end = minOf(featureTime, key + MINUTE_MS)
                    val part = buckets.getOrPut(key) { Bucket() }
                    val duration = end - cursor
                    val active = motionDeltaSquared >= 0.15 * 0.15
                    if (active && activeRun == 0L) {
                        // A single short pulse appears once in the adjacent 100 ms channel and
                        // again exactly one second later when that pulse becomes the lag
                        // reference. Keep the multi-scale energy for RMS/active duration, but do
                        // not count the deterministic lag echo as a second physical movement.
                        val distinctEvent = !isTenHertz || lastMovementEventTime == Long.MIN_VALUE ||
                            featureTime - lastMovementEventTime > MOVEMENT_EVENT_REFRACTORY_MILLIS
                        if (distinctEvent) {
                            part.events++
                            lastMovementEventTime = featureTime
                        }
                    }
                    activeRun = if (active) activeRun + duration else 0L
                    quietRun = if (active) 0L else quietRun + duration
                    part.longestActive = maxOf(part.longestActive, activeRun)
                    part.quietTail = quietRun
                    part.peak = maxOf(part.peak, sqrt(motionDeltaSquared))
                    part.first = part.first ?: cursor
                    part.end = end
                    part.covered += duration
                    if (active) part.active += duration
                    part.squared += motionDeltaSquared * duration
                    cursor = end
                }
            } else {
                activeRun = 0; quietRun = 0
                if (isTenHertz) recentFeatureVectors.clear()
                var cursor = lastFeatureTime
                // Record gaps, never fill them with stillness. Bound memory for a long outage.
                while (cursor < featureTime) {
                    val key = Math.floorDiv(cursor, MINUTE_MS) * MINUTE_MS
                    val end = minOf(featureTime, key + MINUTE_MS)
                    if (buckets.size >= 1440 && key !in buckets) break
                    val bucket = buckets.getOrPut(key) { Bucket() }
                    bucket.gap = maxOf(bucket.gap, dt)
                    bucket.reference = null; bucket.vectorCount = 0
                    bucket.sumX = 0.0; bucket.sumY = 0.0; bucket.sumZ = 0.0
                    cursor = end
                }
            }
        }
        if (isTenHertz) {
            recentFeatureVectors.addLast(FeatureVector(featureTime, x, y, z))
            val oldestRequired = featureTime - LEGACY_FEATURE_SAMPLE_PERIOD_MS
            while (recentFeatureVectors.peekFirst()?.timeMillis?.let { it < oldestRequired } == true) {
                recentFeatureVectors.removeFirst()
            }
        }
        lastFeatureTime = featureTime
        nextFeatureTime = if (isTenHertz) {
            featureTime + featurePeriodMs
        } else if (reanchorFromAcceptedCadence) {
            timeMillis + featurePeriodMs
        } else {
            nextFeatureTime + (skippedSlots + 1) * featurePeriodMs
        }
        lastX = x
        lastY = y
        lastZ = z
        return true
    }

    fun drain(throughMillis: Long, includePartial: Boolean = false): List<MotionMinute> {
        // A raw event may fall just after a minute boundary while its normalized
        // timestamp is still before it. The next representative then contributes
        // to that minute's tail. Seal only through the accepted feature watermark
        // so realtime drains and FIFO drains produce the same complete summaries.
        val completeThrough = if (featureVersion == CURRENT_FEATURE_VERSION) {
            minOf(throughMillis, lastFeatureTime)
        } else throughMillis
        val keys = buckets.keys.filter { includePartial || it + MINUTE_MS <= completeThrough }
        return keys.map { key ->
            val value = buckets.remove(key)!!
            MotionMinute(key, value.covered, value.active, value.squared, value.count, placement, featureVersion,
                value.peak.takeIf { value.covered > 0 }, value.events.takeIf { value.covered > 0 },
                value.longestActive.takeIf { value.covered > 0 }, value.quietTail.takeIf { value.covered > 0 }, value.gap, value.posture,
                recordingId, value.first, value.end)
        }
    }
}

typealias MotionWindow = SleepWindow

/** Experimental motion-only fallback. Accepted sessions sync automatically without sleep staging. */
object MotionSleepEstimator {
    fun annotate(session: SleepSession, minutes: List<MotionMinute>): SleepSession {
        val bed = minutes.filter {
            it.placement == Placement.BED &&
                it.startMillis >= session.startMillis && it.startMillis + MINUTE_MS <= session.endMillis &&
                MotionFeaturePolicy.supportsSleepConflictEvidence(it.featureVersion, it.level)
        }
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
                if (minute.placement != Placement.BED || minute.level == MotionLevel.UNKNOWN || phoneInUse ||
                    (!minute.supportsCurrentStaging && !MotionFeaturePolicy.supportsSleepConflictEvidence(minute.featureVersion, minute.level))
                ) {
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
