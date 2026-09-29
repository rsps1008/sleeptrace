package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.data.SleepStore

data class UsageSnapshotResult(
    val snapshots: List<UsageSnapshot>,
    val intervals: List<UsageInterval>
) {
    fun availableFor(windows: List<SleepWindow>): Boolean = windows.isNotEmpty() && windows.all { window ->
            snapshots.firstOrNull { it.windowStartMillis == window.startMillis }?.accessAvailable == true
    }
}

/** One complete, persisted UsageStats read per sleep window, shared by analysis, placement and upload. */
class SleepUsageSnapshot(private val context: Context) {
    fun captureWindows(store: SleepStore, windows: List<SleepWindow>, nowMillis: Long = System.currentTimeMillis()): UsageSnapshotResult {
        val accessNow = UsageMonitor.hasAccess(context)
        val snapshots = completedWindows(windows, nowMillis)
            .map { window ->
                val previous = store.usageSnapshot(window.startMillis)
                when {
                    previous?.accessAvailable == true && previous.capturedAtMillis >= window.endMillis -> previous
                    previous?.accessAvailable == false && !accessNow && previous.capturedAtMillis >= window.endMillis -> previous
                    else -> {
                        val captured = UsageSnapshot(
                            windowStartMillis = window.startMillis,
                            windowEndMillis = window.endMillis,
                            accessAvailable = accessNow,
                            intervals = if (accessNow)
                                UsageMonitor.interactionIntervals(context, window.startMillis, window.endMillis) else emptyList(),
                            capturedAtMillis = nowMillis
                        )
                        store.saveUsageSnapshot(captured)
                        captured
                    }
                }
            }
        return UsageSnapshotResult(snapshots, snapshots.flatMap { it.intervals })
    }

    /** Compatibility path for edited/legacy pending rows; it reuses the same nightly records. */
    fun applyPending(store: SleepStore, schedule: SleepSchedule) {
        val pending = store.sessions(states = setOf(
            SyncState.PENDING, SyncState.FAILED_RETRYABLE, SyncState.SYNCING
        )).filterNot { it.usageSnapshotApplied }
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val windowsBySession = pending.associate { session ->
            session.id to schedule.windowsBetween(session.startMillis, session.endMillis)
                .ifEmpty { listOf(SleepWindow(session.startMillis, session.endMillis)) }
        }
        val result = captureWindows(store, windowsBySession.values.flatten(), now)
        pending.forEach { session ->
            val windows = windowsBySession[session.id].orEmpty()
            if (windows.any { it.endMillis > now }) return@forEach
            val usage = result.intervals.filter { it.endMillis > session.startMillis && it.startMillis < session.endMillis }
            store.updateIfCurrent(session, apply(session, usage, result.availableFor(windows)))
        }
    }

    companion object {
        internal fun completedWindows(windows: List<SleepWindow>, nowMillis: Long): List<SleepWindow> =
            windows.distinctBy { it.startMillis }.filter { it.endMillis <= nowMillis }

        internal fun isWindowComplete(
            session: SleepSession,
            schedule: SleepSchedule,
            nowMillis: Long,
            zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
        ): Boolean {
            val windows = schedule.windowsBetween(session.startMillis, session.endMillis, zone)
                .ifEmpty { listOf(SleepWindow(session.startMillis, session.endMillis)) }
            return windows.all { it.endMillis <= nowMillis }
        }

        internal fun apply(session: SleepSession, usage: List<UsageInterval>, usageAvailable: Boolean): SleepSession {
            if (session.usageSnapshotApplied) return session
            val existing = normalizedAwake(session.startMillis, session.endMillis, session.awakeIntervals)
            val phoneUse = normalizedAwake(session.startMillis, session.endMillis, usage)
            val awake = normalizedAwake(session.startMillis, session.endMillis, existing + phoneUse)
            val phoneMillis = phoneUse.sumOf { it.endMillis - it.startMillis }
            var reason = session.reason
                .replace(Regex("；已扣除夜間手機使用 \\d+ 分鐘"), "")
                .replace("；未授予使用情況存取權，無法排除手機使用", "")
            if (!usageAvailable) {
                reason = reason.replace("Sleep API 與使用紀錄一致", "Sleep API 睡眠區段")
                    .let { "$it；未授予使用情況存取權，無法排除手機使用" }
            } else if (phoneMillis > 0) {
                reason = reason.replace("Sleep API 與使用紀錄一致", "Sleep API 睡眠區段")
                    .let { "$it；已扣除夜間手機使用 ${phoneMillis / 60_000} 分鐘" }
            }
            return session.copy(
                awakeMillis = awake.sumOf { it.endMillis - it.startMillis },
                awakeIntervals = awake,
                reason = reason,
                usageSnapshotApplied = true
            )
        }
    }
}
