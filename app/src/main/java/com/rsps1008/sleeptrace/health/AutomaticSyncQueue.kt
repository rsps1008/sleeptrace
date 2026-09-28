package com.rsps1008.sleeptrace.health

import com.rsps1008.sleeptrace.sleep.SleepAnalyzer
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.normalizedAwake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialized queue; optimistic updates prevent an old request from overwriting a newer estimate. */
object AutomaticSyncQueue {
    private val mutex = Mutex()
    private fun eligible(session: SleepSession) = session.state in setOf(SyncState.PENDING, SyncState.FAILED, SyncState.SYNCING)

    suspend fun drain(
        read: () -> List<SleepSession>,
        update: (SleepSession, SleepSession) -> Boolean,
        write: suspend (SleepSession) -> Unit
    ): Boolean = mutex.withLock {
        read().filter(::eligible).forEach { session ->
            val knownAwake = normalizedAwake(session.startMillis, session.endMillis, session.awakeIntervals).sumOf { it.endMillis - it.startMillis }
            if (session.endMillis <= session.startMillis || session.durationMillis < SleepAnalyzer.MINIMUM_SLEEP_MILLIS || knownAwake != session.awakeMillis) {
                update(session, session.copy(state = SyncState.SKIPPED, syncError = null,
                    reason = "App 已自動略過：有效睡眠不足 30 分鐘或舊資料缺少手機使用明細"))
                return@forEach
            }
            val writing = session.copy(state = SyncState.SYNCING, syncError = null)
            if (!update(session, writing)) return@forEach
            try {
                write(writing)
                update(writing, writing.copy(state = SyncState.SYNCED))
            } catch (cancelled: CancellationException) {
                throw cancelled // Keep SYNCING for idempotent recovery on the next worker run.
            } catch (error: Exception) {
                update(writing, writing.copy(state = SyncState.FAILED, syncError = error.message ?: "同步暫時失敗"))
            }
        }
        read().none(::eligible)
    }
}
