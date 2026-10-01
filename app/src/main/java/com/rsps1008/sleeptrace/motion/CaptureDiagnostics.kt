package com.rsps1008.sleeptrace.motion

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** In-process invalidation for the homepage's latest persisted capture summary. */
object CaptureUpdates {
    private val mutableUpdates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: SharedFlow<Unit> = mutableUpdates.asSharedFlow()

    fun notifyPersisted() {
        mutableUpdates.tryEmit(Unit)
    }
}

/** Capture metadata is persisted together with five-minute summary batches, not per callback. */
data class CaptureDiagnostics(
    val id: Long, val windowStart: Long, val registeredAt: Long, val trigger: String,
    /** Policy target and the actual period argument passed to SensorManager, respectively. */
    val targetPeriodUs: Int, val periodUs: Int, val latencyUs: Int,
    /** Null means an older capture did not persist this sensor capability. */
    val sensorMinDelayUs: Int?, val sensorMaxDelayUs: Int?,
    val fifoReservedEventCount: Int?, val fifoMaxEventCount: Int, val wakeUp: Boolean,
    val firstEvent: Long? = null, val rawEvents: Long = 0, val rejectedEvents: Long = 0,
    val meanIntervalMillis: Double? = null, val maxIntervalMillis: Long? = null
) {
    /** Backward-compatible name used by the existing diagnostics UI. */
    val fifoCount: Int get() = fifoMaxEventCount
    val targetHertz: Double get() = 1_000_000.0 / targetPeriodUs
    val registeredHertz: Double get() = 1_000_000.0 / periodUs
    val observedRawHertz: Double? get() = meanIntervalMillis
        ?.takeIf { rawEvents > 1 && it > 0.0 }
        ?.let { 1_000.0 / it }
    /** Timestamp-normalization cadence ceiling, not an observed accepted-feature rate. */
    val featureHertz: Double get() = 1_000.0 /
        MotionAccumulator.featurePeriodMillis(targetPeriodUs, periodUs)

    fun homeRateSummary(): String {
        fun hz(value: Double) = String.format(java.util.Locale.US, "%.2f", value)
        val capturedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(registeredAt).atZone(ZoneId.systemDefault()))
        val registered = if (periodUs == targetPeriodUs) "" else "；註冊參數 ${hz(registeredHertz)} Hz"
        val observed = observedRawHertz?.let { "；原始事件實測約 ${hz(it)} Hz" }
            ?: "；原始事件實測尚未形成"
        val fifo = if (fifoMaxEventCount > 0) {
            val reserved = fifoReservedEventCount?.takeIf { it > 0 }?.let { "、保留 $it 筆" }.orEmpty()
            "FIFO 上限 $fifoMaxEventCount 筆$reserved"
        } else "無硬體 FIFO"
        val wake = if (wakeUp) "wake-up" else "非 wake-up"
        return "最近採集（$capturedAt）：目標要求 ${hz(targetHertz)} Hz$registered$observed；特徵正規化上限 ${hz(featureHertz)} Hz。\n" +
            "$fifo、$wake。原始事件頻率不等於 CPU 喚醒頻率或耗電。"
    }
}

enum class CaptureExperiment { OFF, EARLY_1HZ, EARLY_2HZ }

fun capturePlan(
    experiment: CaptureExperiment,
    fifoMaxEventCount: Int,
    minDelayUs: Int = 0,
    maxDelayUs: Int = 0,
    fifoReservedEventCount: Int = 0
): SamplingPlan = SamplingPlan.choose(
    fifoMaxEventCount = fifoMaxEventCount,
    minDelayUs = minDelayUs,
    maxDelayUs = maxDelayUs,
    fifoReservedEventCount = fifoReservedEventCount,
    targetPeriodUs = when (experiment) {
        CaptureExperiment.OFF -> SamplingPlan.DEFAULT_TARGET_PERIOD_US
        CaptureExperiment.EARLY_1HZ -> SamplingPlan.LEGACY_ONE_HZ_TARGET_PERIOD_US
        CaptureExperiment.EARLY_2HZ -> 500_000
    }
)
