package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.data.SleepStore

/** Applies phone-use deductions once, immediately before an automatic upload. */
class SleepUsageSnapshot(private val context: Context) {
    fun applyPending(store: SleepStore) {
        val pending = store.sessions(states = setOf(SyncState.PENDING, SyncState.FAILED))
            .filterNot { it.usageSnapshotApplied }
        if (pending.isEmpty()) return

        val available = UsageMonitor.hasAccess(context)
        val usage = if (available) {
            UsageMonitor.interactionIntervals(context, pending.map { UsageInterval(it.startMillis, it.endMillis) })
        } else emptyList()
        pending.forEach { session -> store.updateIfCurrent(session, apply(session, usage, available)) }
    }

    companion object {
        internal fun apply(session: SleepSession, usage: List<UsageInterval>, usageAvailable: Boolean): SleepSession {
            if (session.usageSnapshotApplied) return session
            val awake = normalizedAwake(session.startMillis, session.endMillis, session.awakeIntervals + usage)
            val reason = if (usageAvailable) session.reason else {
                "${session.reason}；未授予使用情況存取權，無法排除手機使用"
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
