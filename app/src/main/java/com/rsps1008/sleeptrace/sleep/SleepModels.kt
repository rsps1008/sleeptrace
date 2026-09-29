package com.rsps1008.sleeptrace.sleep

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class SyncState {
    PENDING, SYNCING, SYNCED, FAILED, SKIPPED, RETIRED;
    companion object {
        fun fromStored(value: String) = if (value == "NEEDS_REVIEW") PENDING else valueOf(value)
    }
}

data class SleepSegment(
    val startMillis: Long,
    val endMillis: Long,
    val confidence: Int,
    val source: String = "Sleep API"
)

data class UsageInterval(val startMillis: Long, val endMillis: Long)

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
    /** True once the one-time pre-upload UsageStats snapshot has been persisted. */
    val usageSnapshotApplied: Boolean = false
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
