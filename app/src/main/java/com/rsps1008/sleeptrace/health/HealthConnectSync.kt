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
import kotlinx.coroutines.CancellationException
import java.io.IOException
import com.rsps1008.sleeptrace.sleep.sleepParts
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
            message.contains("database is locked") || message.contains("busy")) return true
        current = current.cause
    }
    return false
}

class HealthConnectSync(private val context: Context) {
    val writePermissions: Set<String> = setOf(HealthPermission.getWritePermission(SleepSessionRecord::class))

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    suspend fun hasWritePermission(): Boolean {
        if (!available()) return false
        return HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(writePermissions)
    }
    private suspend fun sync(session: SleepSession) {
        check(available()) { "Health Connect 無法使用" }
        check(hasWritePermission()) { "尚未授予 Health Connect 睡眠寫入權限" }
        val zone = ZoneId.systemDefault().rules
        val start = Instant.ofEpochMilli(session.startMillis)
        val end = Instant.ofEpochMilli(session.endMillis)
        val stages = sleepParts(session).map {
            Stage(startTime = Instant.ofEpochMilli(it.start), endTime = Instant.ofEpochMilli(it.end),
                stage = if (it.awake) SleepSessionRecord.STAGE_TYPE_AWAKE else SleepSessionRecord.STAGE_TYPE_SLEEPING)
        }
        HealthConnectClient.getOrCreate(context).insertRecords(
            listOf(SleepSessionRecord(
                startTime = start, startZoneOffset = zone.getOffset(start),
                endTime = end, endZoneOffset = zone.getOffset(end),
                title = "眠迹 SleepTrace", notes = "以手機推估；${session.reason}", stages = stages,
                metadata = Metadata.autoRecorded(
                    clientRecordId = session.id,
                    clientRecordVersion = session.revision,
                    device = Device(type = Device.TYPE_PHONE)
                )
            ))
        )
    }

    suspend fun syncPending(): Boolean {
        return syncPendingOutcome() == SyncOutcome.SUCCESS
    }

    suspend fun syncPendingOutcome(): SyncOutcome {
        val store = context.sleepDependencies().store
        // Permission needs a system grant, not approval for each sleep record. Resume after grant/on launch.
        if (!available()) return SyncOutcome.FAILURE
        if (!hasWritePermission()) return SyncOutcome.SUCCESS
        when (retireSuperseded(store)) {
            SyncOutcome.RETRY -> return SyncOutcome.RETRY
            SyncOutcome.FAILURE -> return SyncOutcome.FAILURE
            SyncOutcome.SUCCESS -> Unit
        }
        SleepUsageSnapshot(context).applyPending(store)
        val failures = mutableListOf<Throwable>()
        val complete = AutomaticSyncQueue.drain(store::sessions, store::updateIfCurrent, failures::add, ::sync)
        if (complete) return SyncOutcome.SUCCESS
        if (failures.isEmpty() || failures.all(::isTransientSyncError)) return SyncOutcome.RETRY
        return SyncOutcome.FAILURE
    }

    private suspend fun retireSuperseded(store: SleepStore): SyncOutcome {
        store.sessions().filter { it.state == com.rsps1008.sleeptrace.sleep.SyncState.RETIRED }.forEach { session ->
            try {
                HealthConnectClient.getOrCreate(context).deleteRecords(
                    SleepSessionRecord::class, emptyList(), listOf(session.id)
                )
                store.updateIfCurrent(session, session.copy(
                    state = com.rsps1008.sleeptrace.sleep.SyncState.SKIPPED,
                    syncError = null,
                    reason = "已由較完整的睡眠紀錄取代，舊的 Health Connect 資料已移除"
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                store.updateIfCurrent(session, session.copy(syncError = error.message ?: "移除舊的 Health Connect 資料暫時失敗"))
                return if (isTransientSyncError(error)) SyncOutcome.RETRY else SyncOutcome.FAILURE
            }
        }
        return SyncOutcome.SUCCESS
    }
}
