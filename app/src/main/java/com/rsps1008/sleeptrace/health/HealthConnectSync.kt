package com.rsps1008.sleeptrace.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord.Stage
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteTableLockedException
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.sleepDependencies
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepUsageSnapshot
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.SleepStage
import kotlinx.coroutines.CancellationException
import java.io.IOException
import com.rsps1008.sleeptrace.sleep.sleepParts
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

enum class SyncOutcome { SUCCESS, RETRY, FAILURE }

/** Only provider/network/database-busy conditions should wake the device again automatically. */
internal fun isTransientSyncError(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is IOException || current is SQLiteDatabaseLockedException || current is SQLiteTableLockedException) return true
        val type = current::class.java.simpleName.lowercase()
        val message = current.message.orEmpty().lowercase()
        if (type.contains("timeout") || type.contains("ratelimit") || type.contains("servicebusy") ||
            type.contains("temporar") || message.contains("timed out") || message.contains("timeout") ||
            message.contains("temporar") || message.contains("rate limit") || message.contains("too many requests") ||
            message.contains("database is locked") || message.contains("busy") || message.contains("offline") ||
            message.contains("network")) return true
        current = current.cause
    }
    return false
}

internal fun toHealthRecord(session: SleepSession): SleepSessionRecord {
    val zone = ZoneId.systemDefault().rules
    val start = Instant.ofEpochMilli(session.startMillis)
    val end = Instant.ofEpochMilli(session.endMillis)
    val stages = sleepParts(session).map {
        Stage(startTime = Instant.ofEpochMilli(it.start), endTime = Instant.ofEpochMilli(it.end),
            stage = when (it.stage) {
                SleepStage.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
                SleepStage.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                SleepStage.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                SleepStage.SLEEPING -> SleepSessionRecord.STAGE_TYPE_SLEEPING
            })
    }
    return SleepSessionRecord(
            startTime = start, startZoneOffset = zone.getOffset(start),
            endTime = end, endZoneOffset = zone.getOffset(end),
            title = "眠迹 SleepTrace",
            notes = "以手機推估；非醫療睡眠分期；深淺未判定以 SLEEPING 保存；規則 ${session.stageAlgorithmVersion ?: "舊版來源不明"} / 特徵 ${session.stageFeatureVersion ?: "來源不明"}；${session.reason}",
            stages = stages,
            metadata = Metadata.autoRecorded(
                clientRecordId = session.id,
                clientRecordVersion = session.revision,
                device = Device(type = Device.TYPE_PHONE)
            )
        )
}

class HealthConnectSync(private val context: Context) {
    val writePermissions: Set<String> = setOf(HealthPermission.getWritePermission(SleepSessionRecord::class))

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    suspend fun hasWritePermission(): Boolean {
        if (!available()) return false
        return HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(writePermissions)
    }
    suspend fun syncPending(): Boolean {
        return syncPendingOutcome() == SyncOutcome.SUCCESS
    }

    suspend fun syncPendingOutcome(): SyncOutcome = syncMutex.withLock {
        syncPendingLocked()
    }

    /** Keep retirement deletes ordered after any in-flight insert that may have created the same ID. */
    private suspend fun syncPendingLocked(): SyncOutcome {
        val store = context.sleepDependencies().store
        // Permission needs a system grant, not approval for each sleep record. Resume after grant/on launch.
        if (!available()) return SyncOutcome.FAILURE
        if (!hasWritePermission()) return SyncOutcome.SUCCESS
        when (retireSuperseded(store)) {
            SyncOutcome.RETRY -> return SyncOutcome.RETRY
            SyncOutcome.FAILURE -> return SyncOutcome.FAILURE
            SyncOutcome.SUCCESS -> Unit
        }
        val schedule = context.sleepDependencies().preferences.schedule()
        SleepUsageSnapshot(context).applyPending(store, schedule)
        val now = System.currentTimeMillis()
        val failures = mutableListOf<Throwable>()
        val client = HealthConnectClient.getOrCreate(context)
        val complete = AutomaticSyncQueue.drainBatch(
            read = {
                store.sessions(states = setOf(SyncState.PENDING, SyncState.FAILED_RETRYABLE, SyncState.SYNCING))
                    .filter { it.usageSnapshotApplied && SleepUsageSnapshot.isWindowComplete(it, schedule, now) }
            },
            update = store::updateIfCurrent,
            onFailure = failures::add,
            writeBatch = { sessions -> client.insertRecords(sessions.map(::toHealthRecord)) }
        )
        if (failures.any(::isTransientSyncError)) return SyncOutcome.RETRY
        if (failures.isNotEmpty()) return SyncOutcome.FAILURE
        if (complete) return SyncOutcome.SUCCESS
        return SyncOutcome.RETRY
    }

    private suspend fun retireSuperseded(store: SleepStore): SyncOutcome {
        store.sessions(states = setOf(SyncState.RETIRED)).forEach { session ->
            try {
                HealthConnectClient.getOrCreate(context).deleteRecords(
                    SleepSessionRecord::class, emptyList(), listOf(session.id)
                )
                store.updateIfCurrent(session, session.copy(
                    state = SyncState.SKIPPED,
                    syncError = null,
                    reason = "舊的 Health Connect 睡眠資料已移除"
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val transient = isTransientSyncError(error)
                store.updateIfCurrent(session, session.copy(
                    state = if (transient) SyncState.RETIRED else SyncState.RETIRED_FAILED_PERMANENT,
                    syncError = error.message ?: if (transient) "移除舊資料暫時失敗" else "無法移除舊資料"
                ))
                return if (transient) SyncOutcome.RETRY else SyncOutcome.FAILURE
            }
        }
        return SyncOutcome.SUCCESS
    }

    companion object {
        /** WorkManager immediate and periodic work have distinct names and may otherwise overlap. */
        private val syncMutex = Mutex()
    }
}
