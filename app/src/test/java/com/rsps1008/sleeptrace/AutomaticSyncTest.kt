package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.health.AutomaticSyncQueue
import com.rsps1008.sleeptrace.sleep.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutomaticSyncTest {
    private fun session(state: SyncState = SyncState.PENDING) = SleepSession(
        id = "night", startMillis = 1_000, endMillis = 7_201_000, confidence = 40,
        awakeMillis = 0, state = state, reason = "estimate"
    )
    private class Repository(var rows: List<SleepSession>) {
        fun read() = rows
        fun update(expected: SleepSession, replacement: SleepSession): Boolean {
            if (!rows.contains(expected)) return false
            rows = rows.map { if (it.id == expected.id) replacement else it }
            return true
        }
    }

    @Test fun `legacy review is automatically eligible`() {
        assertEquals(SyncState.PENDING, SyncState.fromStored("NEEDS_REVIEW"))
        assertEquals(SyncState.SYNCED, SyncState.fromStored("SYNCED"))
    }

    @Test fun `low scoring estimate uploads without any confirmation and is not uploaded twice`() = runBlocking {
        val repo = Repository(listOf(session()))
        var writes = 0
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { writes++ })
        assertEquals(SyncState.SYNCED, repo.rows.single().state)
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun `transient failure retries automatically with the same identity and version`() = runBlocking {
        val repo = Repository(listOf(session()))
        var first: SleepSession? = null
        assertFalse(AutomaticSyncQueue.drain(repo::read, repo::update) { first = it; throw IllegalStateException("temporary") })
        assertEquals(SyncState.FAILED, repo.rows.single().state)
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) {
            assertEquals(first!!.id, it.id)
            assertEquals(first!!.revision, it.revision)
        })
    }

    @Test fun `interrupted upload is resumed without new id`() = runBlocking {
        val repo = Repository(listOf(session(SyncState.SYNCING)))
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { assertEquals("night", it.id) })
        assertEquals(SyncState.SYNCED, repo.rows.single().state)
    }

    @Test fun `cancellation propagates and does not become an ordinary failure`() = runBlocking {
        val repo = Repository(listOf(session()))
        try {
            AutomaticSyncQueue.drain(repo::read, repo::update) { throw CancellationException("stopped") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(SyncState.SYNCING, repo.rows.single().state)
        }
    }

    @Test fun `old request completing cannot overwrite a newer correction`() = runBlocking {
        val repo = Repository(listOf(session()))
        assertFalse(AutomaticSyncQueue.drain(repo::read, repo::update) {
            repo.rows = listOf(it.copy(revision = 2, state = SyncState.PENDING, endMillis = it.endMillis + 60_000))
        })
        assertEquals(2L, repo.rows.single().revision)
        assertEquals(SyncState.PENDING, repo.rows.single().state)
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { assertEquals(2L, it.revision) })
    }

    @Test fun `invalid or incomplete legacy sleep is skipped automatically without prompting`() = runBlocking {
        val repo = Repository(listOf(session().copy(awakeMillis = 60_000)))
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { fail("Missing awake details must not become sleeping") })
        assertEquals(SyncState.SKIPPED, repo.rows.single().state)
    }

    @Test fun `reanalysis updates same remote identity only when sleep content changes`() {
        val original = session(SyncState.SYNCED)
        assertEquals(original, mergeSleepSessions(listOf(original), listOf(original.copy(id = "new", confidence = 80))).single())
        val revised = mergeSleepSessions(listOf(original), listOf(original.copy(id = "new", endMillis = original.endMillis + 60_000))).single()
        assertEquals(original.id, revised.id)
        assertEquals(original.revision + 1, revised.revision)
        assertEquals(SyncState.PENDING, revised.state)
    }

    @Test fun `optional manual correction is retained while upload remains automatic`() {
        val manual = session().copy(manuallyEdited = true)
        assertEquals(manual, mergeSleepSessions(listOf(manual), listOf(session().copy(endMillis = 10_000_000))).single())
    }

    @Test fun `conflicting sources are selected automatically using adjusted scores`() {
        val api = session().copy(confidence = 30)
        val motion = session().copy(id = "motion", confidence = 50)
        assertEquals(listOf(motion), selectBestSessions(listOf(api), listOf(motion)))
        assertEquals(listOf(api.copy(confidence = 80)), selectBestSessions(listOf(api.copy(confidence = 80)), listOf(motion)))
    }

    @Test fun `phone use is clipped unioned and exported as awake instead of sleeping`() {
        val awake = normalizedAwake(1_000, 7_201_000, listOf(UsageInterval(0, 61_000), UsageInterval(31_000, 121_000), UsageInterval(7_141_000, 8_000_000)))
        val record = session().copy(awakeMillis = 180_000, awakeIntervals = awake)
        val parts = sleepParts(record)
        assertEquals(3, parts.size)
        assertEquals(180_000L, parts.filter { it.awake }.sumOf { it.end - it.start })
        assertEquals(record.durationMillis, parts.filterNot { it.awake }.sumOf { it.end - it.start })
        assertEquals(record.startMillis, parts.first().start)
        assertEquals(record.endMillis, parts.last().end)
    }

    @Test fun `phone use deduction is persisted once before upload and not recalculated on retry`() {
        val original = session()
        val snapped = SleepUsageSnapshot.apply(original, listOf(UsageInterval(2_000, 62_000)), usageAvailable = true)

        assertTrue(snapped.usageSnapshotApplied)
        assertEquals(60_000L, snapped.awakeMillis)
        assertEquals(snapped, SleepUsageSnapshot.apply(snapped, listOf(UsageInterval(62_000, 122_000)), usageAvailable = true))
    }

    @Test fun `missing usage access is recorded once with an explicit limitation`() {
        val snapped = SleepUsageSnapshot.apply(session(), emptyList(), usageAvailable = false)

        assertTrue(snapped.usageSnapshotApplied)
        assertTrue(snapped.reason.contains("無法排除手機使用"))
    }

    @Test fun `reanalysis keeps the stored one-time phone use deduction`() {
        val snapped = session(SyncState.SYNCED).copy(
            awakeMillis = 60_000,
            awakeIntervals = listOf(UsageInterval(2_000, 62_000)),
            usageSnapshotApplied = true
        )

        assertEquals(snapped, mergeSleepSessions(listOf(snapped), listOf(session().copy(id = "new"))).single())
    }

    @Test fun `multiple overlapping history records keep one id and retire the extra synced id`() {
        val first = session(SyncState.SYNCED).copy(id = "first", startMillis = 1_000, endMillis = 3_601_000)
        val second = session(SyncState.SYNCED).copy(id = "second", startMillis = 3_601_000, endMillis = 7_201_000)
        val candidate = session().copy(id = "new", startMillis = 1_000, endMillis = 7_201_000)

        val merged = mergeSleepSessions(listOf(first, second), listOf(candidate))

        val replacement = merged.single { it.id == "first" }
        assertEquals(SyncState.PENDING, replacement.state)
        assertEquals(first.revision + 1, replacement.revision)
        assertEquals(SyncState.RETIRED, merged.single { it.id == "second" }.state)
    }
}
