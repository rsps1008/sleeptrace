package com.rsps1008.sleeptrace.sleep

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class SyncState {
    PENDING, SYNCING, SYNCED, FAILED_RETRYABLE, FAILED_PERMANENT, SKIPPED, RETIRED, RETIRED_FAILED_PERMANENT;
    companion object {
        fun fromStored(value: String) = when (value) {
            "NEEDS_REVIEW" -> PENDING
            "FAILED" -> FAILED_RETRYABLE
            else -> valueOf(value)
        }
    }
}

data class SleepSegment(
    val startMillis: Long,
    val endMillis: Long,
    val confidence: Int,
    val source: String = "Sleep API"
)

data class UsageInterval(val startMillis: Long, val endMillis: Long)

/** A compact, reconciled estimate; raw accelerometer data is never stored here. */
data class SleepStageInterval(
    val startMillis: Long,
    val endMillis: Long,
    val stage: SleepStage
)

data class UsageSnapshot(
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val accessAvailable: Boolean,
    val intervals: List<UsageInterval>,
    val capturedAtMillis: Long,
    val evidenceStartMillis: Long = windowStartMillis
)

data class SleepSession(
    val id: String = UUID.randomUUID().toString(),
    val startMillis: Long,
    val endMillis: Long,
    val confidence: Int,
    val awakeMillis: Long,
    val state: SyncState,
    val reason: String,
    val manuallyEdited: Boolean = false,
    val syncError: String? = null,
    val revision: Long = 1,
    val awakeIntervals: List<UsageInterval> = emptyList(),
    /** True once the shared per-window UsageStats snapshot has been applied and persisted. */
    val usageSnapshotApplied: Boolean = false,
    /** Merged stage intervals, recomputed during reconciliation; empty means legacy generic sleep. */
    val stageIntervals: List<SleepStageInterval> = emptyList()
) {
    val durationMillis: Long get() = (endMillis - startMillis - awakeMillis).coerceAtLeast(0)
    fun title(): String = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}

data class ClassificationSample(
    val timeMillis: Long,
    val confidence: Int,
    val motion: Int,
    val light: Int
)
