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
    private fun eligible(session: SleepSession) = session.state in setOf(
        SyncState.PENDING, SyncState.FAILED_RETRYABLE, SyncState.SYNCING
    )
    private fun valid(session: SleepSession): Boolean {
        val knownAwake = normalizedAwake(session.startMillis, session.endMillis, session.awakeIntervals)
            .sumOf { it.endMillis - it.startMillis }
        return session.endMillis > session.startMillis &&
            session.durationMillis >= SleepAnalyzer.MINIMUM_SLEEP_MILLIS && knownAwake == session.awakeMillis
    }

    suspend fun drain(
        read: () -> List<SleepSession>,
        update: (SleepSession, SleepSession) -> Boolean,
        write: suspend (SleepSession) -> Unit
    ): Boolean = drain(read, update, {}, write)

    suspend fun drain(
        read: () -> List<SleepSession>,
        update: (SleepSession, SleepSession) -> Boolean,
        onFailure: (Throwable) -> Unit,
        write: suspend (SleepSession) -> Unit
    ): Boolean = mutex.withLock {
        read().filter(::eligible).forEach { session ->
            if (!valid(session)) {
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
                onFailure(error)
                update(writing, writing.copy(state = failureState(error), syncError = error.message ?: "同步失敗"))
            }
        }
        read().none(::eligible)
    }

    /** Prepares all valid rows before one remote write; stable IDs and revisions make retries idempotent. */
    suspend fun drainBatch(
        read: () -> List<SleepSession>,
        update: (SleepSession, SleepSession) -> Boolean,
        onFailure: (Throwable) -> Unit,
        writeBatch: suspend (List<SleepSession>) -> Unit
    ): Boolean = mutex.withLock {
        val validSessions = read().filter(::eligible).filter { session ->
            if (valid(session)) true else {
                update(session, session.copy(
                    state = SyncState.SKIPPED,
                    syncError = null,
                    reason = "App 已自動略過：有效睡眠不足 30 分鐘或舊資料缺少手機使用明細"
                ))
                false
            }
        }

        for (batch in validSessions.chunked(MAX_BATCH_SIZE)) {
            val writing = batch.mapNotNull { session ->
                val candidate = session.copy(state = SyncState.SYNCING, syncError = null)
                candidate.takeIf { update(session, candidate) }
            }
            if (writing.isEmpty()) continue
            try {
                writeBatch(writing)
                writing.forEach { update(it, it.copy(state = SyncState.SYNCED)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isTransientSyncError(error)) {
                    onFailure(error)
                    writing.forEach { update(it, it.copy(
                        state = SyncState.FAILED_RETRYABLE,
                        syncError = error.message ?: "同步失敗"
                    )) }
                    break
                }

                if (writing.size == 1) {
                    onFailure(error)
                    val only = writing.single()
                    update(only, only.copy(
                        state = SyncState.FAILED_PERMANENT,
                        syncError = error.message ?: "同步失敗"
                    ))
                    continue
                }

                // Health Connect can reject a batch because of one invalid record. Retry this
                // permanent failure one record at a time so valid siblings still get uploaded.
                var transientFallbackFailure = false
                writing.forEach { session ->
                    try {
                        writeBatch(listOf(session))
                        update(session, session.copy(state = SyncState.SYNCED))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (recordError: Exception) {
                        onFailure(recordError)
                        val state = failureState(recordError)
                        if (state == SyncState.FAILED_RETRYABLE) transientFallbackFailure = true
                        update(session, session.copy(
                            state = state,
                            syncError = recordError.message ?: "同步失敗"
                        ))
                    }
                }
                if (transientFallbackFailure) break
            }
        }
        read().none(::eligible)
    }

    private const val MAX_BATCH_SIZE = 1_000

    private fun failureState(error: Throwable) =
        if (isTransientSyncError(error)) SyncState.FAILED_RETRYABLE else SyncState.FAILED_PERMANENT
}
