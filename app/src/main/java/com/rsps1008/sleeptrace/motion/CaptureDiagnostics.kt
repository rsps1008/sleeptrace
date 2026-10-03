package com.rsps1008.sleeptrace.motion

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.util.Locale

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
        ?.takeIf { rawEvents > 1 && it.isFinite() && it > 0.0 }
        ?.let { 1_000.0 / it }
        ?.takeIf { it.isFinite() }
    /** Timestamp-normalization cadence ceiling, not an observed accepted-feature rate. */
    val featureHertz: Double get() = 1_000.0 /
        MotionAccumulator.featurePeriodMillis(targetPeriodUs, periodUs)

    /** Text-only consumers use the same fields and provenance notes as the structured home view. */
    fun homeRateSummary(): String = homePresentation().summary()
}

data class CapturePresentationField(
    val label: String,
    val value: String,
    val notes: List<String> = emptyList()
)

/** Presentation only: no sensor reads, live status, or reconstructed historical batch requests. */
data class CaptureHomePresentation(
    val started: CapturePresentationField,
    val requestedRate: CapturePresentationField,
    val registeredRate: CapturePresentationField?,
    val observedRate: CapturePresentationField,
    val featureCeiling: CapturePresentationField,
    val fifoCapacity: CapturePresentationField,
    val batchWait: CapturePresentationField,
    val wakeUp: CapturePresentationField
) {
    fun summary(): String = listOfNotNull(
        started, requestedRate, registeredRate, observedRate, featureCeiling,
        fifoCapacity, batchWait, wakeUp
    ).joinToString("\n") { field ->
        (listOf("${field.label}：${field.value}") + field.notes).joinToString("\n")
    }
}

/** A missing capture is unknown, never a fabricated 10 Hz request or a zero-capacity sensor. */
fun CaptureDiagnostics?.homePresentation(zoneId: ZoneId = ZoneId.systemDefault()): CaptureHomePresentation {
    val capture = this
    val noCapture = "尚無採集紀錄"
    fun hz(value: Double): String = String.format(Locale.US, "%.2f Hz", value)
    fun seconds(microseconds: Int): String =
        BigDecimal.valueOf(microseconds.toLong(), 6).stripTrailingZeros().toPlainString() + " 秒"

    return CaptureHomePresentation(
        started = CapturePresentationField(
            "最近一次採集開始",
            capture?.let {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.TAIWAN)
                    .format(Instant.ofEpochMilli(it.registeredAt).atZone(zoneId))
            } ?: noCapture,
            listOf("顯示已保存的採集紀錄，非即時狀態。")
        ),
        requestedRate = CapturePresentationField(
            "App 要求頻率", capture?.let { hz(it.targetHertz) } ?: noCapture
        ),
        registeredRate = capture?.takeIf { it.periodUs != it.targetPeriodUs }?.let {
            CapturePresentationField("Android 註冊要求", hz(it.registeredHertz))
        },
        observedRate = CapturePresentationField(
            "原始事件實測", capture?.observedRawHertz?.let { "約 ${hz(it)}" } ?: "尚無量測",
            listOf("依已保存的事件時間間隔估算；不代表 CPU 喚醒頻率或耗電。")
        ),
        featureCeiling = CapturePresentationField(
            "特徵正規化上限", capture?.let { hz(it.featureHertz) } ?: noCapture,
            listOf("這是正規化上限，不是實測特徵率。")
        ),
        fifoCapacity = CapturePresentationField(
            "硬體 FIFO 容量",
            capture?.let {
                "最大 ${it.fifoMaxEventCount} 筆\n此感測器保留：" +
                    (it.fifoReservedEventCount?.let { count -> "$count 筆" } ?: "舊紀錄未保存")
            } ?: noCapture,
            listOf("Android 感測器回報的硬體能力，非 App 實測容量或目前使用量。") +
                when {
                    capture == null -> emptyList()
                    capture.fifoMaxEventCount == 0 -> listOf("最大容量為 0：此感測器未提供硬體 FIFO 批次能力。")
                    else -> listOf("保留量是此感測器的保障容量；保留 0 筆不代表沒有 FIFO。最大容量可能與其他感測器共用。")
                }
        ),
        batchWait = CapturePresentationField(
            "App 批次等待上限",
            capture?.let { if (it.latencyUs == 0) "未要求批次等待" else seconds(it.latencyUs) } ?: noCapture,
            listOf(
                "App 依手機回報的 FIFO 容量與事件間隔計算，保留約 20% 緩衝。",
                "優先使用保留容量；沒有保留量時才參考最大容量。App 沒有另設固定秒數上限，仍受 Android 系統限制。",
                "這是送給 Android 的等待要求，手機可能提早回報；不代表實際 CPU 喚醒間隔。"
            ) + if (capture != null && (capture.sensorMinDelayUs == null ||
                    capture.sensorMaxDelayUs == null || capture.fifoReservedEventCount == null)) {
                listOf("以上為目前策略；舊紀錄缺少部分能力資料，僅顯示當時保存的等待要求。")
            } else emptyList()
        ),
        wakeUp = CapturePresentationField(
            "感測器喚醒類型",
            capture?.let { if (it.wakeUp) "wake-up（可喚醒 CPU）" else "非 wake-up" } ?: noCapture,
            listOf(when (capture?.wakeUp) {
                true -> "回報類型支援喚醒，不代表整夜資料完整或耗電已驗證。"
                false -> "CPU 休眠時可能延遲回報或缺資料。"
                null -> "尚無已保存的感測器類型資料。"
            })
        )
    )
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
