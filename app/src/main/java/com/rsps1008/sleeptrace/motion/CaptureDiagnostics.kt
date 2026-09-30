package com.rsps1008.sleeptrace.motion

/** Capture metadata is persisted together with five-minute summary batches, not per callback. */
data class CaptureDiagnostics(
    val id: Long, val windowStart: Long, val registeredAt: Long, val trigger: String,
    val periodUs: Int, val latencyUs: Int, val fifoCount: Int, val wakeUp: Boolean,
    val firstEvent: Long? = null, val rawEvents: Long = 0, val rejectedEvents: Long = 0,
    val meanIntervalMillis: Double? = null, val maxIntervalMillis: Long? = null
)

enum class CaptureExperiment { OFF, EARLY_1HZ, EARLY_2HZ }

fun capturePlan(experiment: CaptureExperiment, fifoCount: Int, minDelayUs: Int = 0): SamplingPlan {
    if (experiment != CaptureExperiment.EARLY_2HZ) return SamplingPlan.choose(fifoCount, minDelayUs)
    val period = maxOf(500_000, minDelayUs)
    return SamplingPlan(period, if (fifoCount <= 0) 0 else
        minOf(Int.MAX_VALUE.toLong(), fifoCount.toLong() * period * 8 / 10).toInt())
}
