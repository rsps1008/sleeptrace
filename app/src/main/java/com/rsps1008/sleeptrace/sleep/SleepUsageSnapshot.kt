package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.data.SleepStore

data class UsageSnapshotResult(
    val snapshots: List<UsageSnapshot>,
    val intervals: List<UsageInterval>
) {
    fun availableFor(window: SleepWindow): Boolean = snapshots.firstOrNull {
        it.windowStartMillis == window.startMillis && it.windowEndMillis == window.endMillis
    }?.accessAvailable == true

    fun availableFor(windows: List<SleepWindow>): Boolean = windows.isNotEmpty() && windows.all(::availableFor)
}

/** Legacy snapshot compatibility. New records use Sleep API awake evidence and never query UsageStats. */
class SleepUsageSnapshot(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun captureWindows(store: SleepStore, windows: List<SleepWindow>, nowMillis: Long = System.currentTimeMillis()): UsageSnapshotResult {
        val snapshots = completedWindows(windows, nowMillis)
            .mapNotNull { window -> store.usageSnapshot(window.startMillis, window.endMillis) }
        return UsageSnapshotResult(snapshots, snapshots.flatMap { it.intervals })
    }

    /** Compatibility path for edited/legacy pending rows; preserve saved Awake and mark them ready. */
    fun applyPending(store: SleepStore, schedule: SleepSchedule) {
        val pending = store.sessions(states = setOf(
            SyncState.PENDING, SyncState.FAILED_RETRYABLE, SyncState.SYNCING
        )).filterNot { it.usageSnapshotApplied }
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        pending.forEach { session ->
            if (!isWindowComplete(session, schedule, now)) return@forEach
            store.updateIfCurrent(session, apply(session, emptyList(), true))
        }
    }

    companion object {
        internal fun completedWindows(windows: List<SleepWindow>, nowMillis: Long): List<SleepWindow> =
            windows.distinctBy { it.startMillis to it.endMillis }.filter { it.endMillis <= nowMillis }

        internal fun canReuse(
            previous: UsageSnapshot?,
            window: SleepWindow,
            accessAvailableNow: Boolean
        ): Boolean = previous != null &&
            previous.windowStartMillis == window.startMillis &&
            previous.windowEndMillis == window.endMillis &&
            previous.capturedAtMillis >= window.endMillis &&
            (!previous.accessAvailable || previous.evidenceStartMillis <= window.startMillis - PRE_SESSION_USAGE_LOOKBACK) &&
            (previous.accessAvailable || !accessAvailableNow)

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
            val importedLegacyAwake = if (usageAvailable) usage else emptyList()
            val awake = normalizedAwake(session.startMillis, session.endMillis, existing + importedLegacyAwake)
            return session.copy(
                awakeMillis = awake.sumOf { it.endMillis - it.startMillis },
                awakeIntervals = awake,
                usageSnapshotApplied = true
            )
        }
    }
}
