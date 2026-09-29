package com.rsps1008.sleeptrace.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord.Stage
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepUsageSnapshot
import com.rsps1008.sleeptrace.sleep.sleepParts
import java.time.Instant
import java.time.ZoneId

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
        val store = SleepStore(context)
        // Permission needs a system grant, not approval for each sleep record. Resume after grant/on launch.
        if (!hasWritePermission()) return true
        SleepUsageSnapshot(context).applyPending(store)
        return AutomaticSyncQueue.drain(store::sessions, store::updateIfCurrent, ::sync)
    }
}
