package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.health.AutomaticSyncQueue
import com.rsps1008.sleeptrace.health.toHealthRecord
import com.rsps1008.sleeptrace.sleep.*
import androidx.health.connect.client.records.SleepSessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
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
        assertEquals(SyncState.FAILED_RETRYABLE, SyncState.fromStored("FAILED"))
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
        assertEquals(SyncState.FAILED_RETRYABLE, repo.rows.single().state)
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) {
            assertEquals(first!!.id, it.id)
            assertEquals(first!!.revision, it.revision)
        })
    }

    @Test fun `permanent failure stays out of automatic retries until explicitly reset`() = runBlocking {
        val repo = Repository(listOf(session()))
        var writes = 0
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) {
            writes++
            throw IllegalArgumentException("invalid record")
        })
        assertEquals(SyncState.FAILED_PERMANENT, repo.rows.single().state)
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun `permanent batch failure falls back to single records and isolates the invalid row`() = runBlocking {
        val repo = Repository(listOf(
            session().copy(id = "valid-1"),
            session().copy(id = "invalid"),
            session().copy(id = "valid-2")
        ))
        val attempts = mutableListOf<List<String>>()
        val failures = mutableListOf<Throwable>()

        val complete = AutomaticSyncQueue.drainBatch(repo::read, repo::update, failures::add) { batch ->
            attempts += batch.map { it.id }
            if (batch.size > 1 || batch.single().id == "invalid") {
                throw IllegalArgumentException("Health Connect rejected record")
            }
        }

        assertTrue(complete)
        assertEquals(
            listOf(listOf("valid-1", "invalid", "valid-2"), listOf("valid-1"), listOf("invalid"), listOf("valid-2")),
            attempts
        )
        assertEquals(SyncState.SYNCED, repo.rows.single { it.id == "valid-1" }.state)
        assertEquals(SyncState.FAILED_PERMANENT, repo.rows.single { it.id == "invalid" }.state)
        assertEquals(SyncState.SYNCED, repo.rows.single { it.id == "valid-2" }.state)
        assertEquals(1, failures.size)
    }

    @Test fun `transient batch failure marks the batch retryable without single-record fallback`() = runBlocking {
        val repo = Repository(listOf(session().copy(id = "one"), session().copy(id = "two")))
        val failures = mutableListOf<Throwable>()
        var writeCalls = 0

        val complete = AutomaticSyncQueue.drainBatch(repo::read, repo::update, failures::add) {
            writeCalls++
            throw IOException("offline")
        }

        assertFalse(complete)
        assertEquals(1, writeCalls)
        assertTrue(repo.rows.all { it.state == SyncState.FAILED_RETRYABLE })
        assertEquals(1, failures.size)
    }

    @Test fun `transient failure during single-record fallback stops further provider calls`() = runBlocking {
        val repo = Repository(listOf("good", "busy", "deferred").map { session().copy(id = it) })
        val attempts = mutableListOf<List<String>>()
        val failures = mutableListOf<Throwable>()
        assertFalse(AutomaticSyncQueue.drainBatch(repo::read, repo::update, failures::add) { batch ->
            attempts += batch.map { it.id }
            if (batch.size > 1) throw IllegalArgumentException("invalid batch")
            if (batch.single().id == "busy") throw IOException("provider unavailable")
        })
        assertEquals(listOf(listOf("good", "busy", "deferred"), listOf("good"), listOf("busy")), attempts)
        assertEquals(SyncState.SYNCED, repo.rows.first().state)
        assertTrue(repo.rows.drop(1).all { it.state == SyncState.FAILED_RETRYABLE })
        val retry = repo.rows.drop(1).map { it.id to it.revision }
        assertTrue(AutomaticSyncQueue.drainBatch(repo::read, repo::update, failures::add) { batch ->
            assertEquals(retry, batch.map { it.id to it.revision })
        })
    }

    @Test fun `interrupted upload is resumed without new id`() = runBlocking {
        val repo = Repository(listOf(session(SyncState.SYNCING)))
        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { assertEquals("night", it.id) })
        assertEquals(SyncState.SYNCED, repo.rows.single().state)
    }

    @Test fun `retired records are not reinserted by the automatic upload queue`() = runBlocking {
        val repo = Repository(listOf(session(SyncState.RETIRED)))
        var writes = 0

        assertTrue(AutomaticSyncQueue.drain(repo::read, repo::update) { writes++ })

        assertEquals(0, writes)
        assertEquals(SyncState.RETIRED, repo.rows.single().state)
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

    @Test fun `rule nine stage-only migration replaces legacy Sleeping with Light on the same identity`() {
        val old = session(SyncState.SYNCED).copy(
            id = "stable-rule-id",
            revision = 4,
            stageAlgorithmVersion = 8,
            stageIntervals = listOf(
                SleepStageInterval(1_000, 7_201_000, SleepStage.SLEEPING)
            )
        )
        val ruleNine = old.copy(
            id = "recomputed-id",
            state = SyncState.PENDING,
            stageAlgorithmVersion = 9,
            stageIntervals = listOf(
                SleepStageInterval(1_000, 7_201_000, SleepStage.LIGHT)
            )
        )

        val mergedRows = mergeSleepSessions(listOf(old), listOf(ruleNine))
        assertEquals(1, mergedRows.size)
        val migrated = mergedRows.single()
        assertEquals(old.id, migrated.id)
        assertEquals(old.revision + 1, migrated.revision)
        assertEquals(SyncState.PENDING, migrated.state)
        assertEquals(9, migrated.stageAlgorithmVersion)
        assertEquals(listOf(SleepStage.LIGHT), migrated.stageIntervals.map { it.stage })
        assertTrue(mergedRows.none { it.state == SyncState.RETIRED })

        val payload = toHealthRecord(migrated)
        assertEquals(listOf(SleepSessionRecord.STAGE_TYPE_LIGHT), payload.stages.map { it.stage })
        assertTrue(payload.stages.none { it.stage == SleepSessionRecord.STAGE_TYPE_SLEEPING })
    }

    @Test fun `Sleep API only mode exports generic sleeping and awake without fabricated stages`() {
        val base = session().copy(
            awakeIntervals = listOf(UsageInterval(61_000, 121_000)),
            awakeMillis = 60_000
        )

        val apiOnly = sleepApiOnlySession(base, base.awakeIntervals)
        val parts = sleepParts(apiOnly)
        val payload = toHealthRecord(apiOnly)

        assertEquals(SLEEP_API_ONLY_ALGORITHM_VERSION, apiOnly.stageAlgorithmVersion)
        assertEquals(60_000L, stageDurations(apiOnly).awake)
        assertEquals(apiOnly.durationMillis, stageDurations(apiOnly).sleep)
        assertTrue(parts.filterNot { it.awake }.all { it.stage == SleepStage.SLEEPING })
        assertTrue(payload.stages.any { it.stage == SleepSessionRecord.STAGE_TYPE_SLEEPING })
        assertTrue(payload.stages.any { it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE })
        assertTrue(payload.stages.none { it.stage == SleepSessionRecord.STAGE_TYPE_LIGHT })
        assertTrue(payload.stages.none { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP })
    }

    @Test fun `mode switch does not restage an already settled night`() {
        val staged = session().copy(stageAlgorithmVersion = SleepStageEstimator.ALGORITHM_VERSION)
        val apiOnly = sleepApiOnlySession(session(), emptyList())

        assertTrue(recordingModeChangedForExistingSession(staged, stagesEnabled = false))
        assertTrue(recordingModeChangedForExistingSession(apiOnly, stagesEnabled = true))
        assertFalse(recordingModeChangedForExistingSession(staged, stagesEnabled = true))
        assertFalse(recordingModeChangedForExistingSession(apiOnly, stagesEnabled = false))
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

    @Test fun `legacy session becomes upload ready without usage access`() {
        val snapped = SleepUsageSnapshot.apply(session(), emptyList(), usageAvailable = false)

        assertTrue(snapped.usageSnapshotApplied)
        assertEquals(session().reason, snapped.reason)
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

    @Test fun `rule migration only targets automatic sessions contained in completed windows`() {
        val window = SleepWindow(0, 3 * 60 * 60 * 1_000L)
        val sessions = listOf(
            session(SyncState.SYNCED).copy(id = "automatic", startMillis = 1_000, endMillis = 7_201_000),
            session().copy(id = "manual", startMillis = 1_000, endMillis = 7_201_000, manuallyEdited = true),
            session().copy(id = "before-window", startMillis = -1, endMillis = 7_201_000),
            session().copy(id = "after-window", startMillis = 1_000, endMillis = window.endMillis + 1),
            session(SyncState.SKIPPED).copy(id = "already-skipped", startMillis = 1_000, endMillis = 7_201_000),
            session(SyncState.RETIRED).copy(id = "already-retired", startMillis = 1_000, endMillis = 7_201_000)
        )

        assertEquals(
            setOf("automatic"),
            automaticSessionIdsEligibleForRuleRevocation(sessions, listOf(window))
        )
    }

    @Test fun `reconcile cutoff keeps full overlapping session and sparse coupling lookback`() {
        val zone = ZoneId.of("Asia/Taipei")
        val schedule = SleepSchedule(23 * 60, 7 * 60)
        val window = schedule.windowForStartDate(LocalDate.of(2026, 10, 1), zone)
        val sessionStart = window.startMillis

        listOf(
            window.startMillis + 30 * 60_000L,
            window.startMillis + 5 * 60 * 60_000L
        ).forEach { cutoff ->
            assertEquals(
                window.startMillis - com.rsps1008.sleeptrace.motion.CouplingPolicy.SPARSE_EVIDENCE_WINDOW_MILLIS,
                reconciliationEvidenceStart(cutoff, listOf(sessionStart), schedule, window.endMillis, zone)
            )
        }
    }

    @Test fun `rule migration retains local tombstones and retires remotely possible records`() {
        val sessions = listOf(
            session(SyncState.SYNCED).copy(id = "synced"),
            session(SyncState.SYNCING).copy(id = "syncing"),
            session(SyncState.FAILED_RETRYABLE).copy(id = "retryable"),
            session(SyncState.FAILED_PERMANENT).copy(id = "permanent"),
            session(SyncState.PENDING).copy(id = "pending"),
            session(SyncState.SYNCED).copy(id = "manual", manuallyEdited = true),
            session(SyncState.SKIPPED).copy(id = "skipped")
        )

        val merged = mergeSleepSessions(sessions, emptyList(), sessions.map { it.id }.toSet())

        assertEquals("No invalidated local row may be deleted", sessions.size, merged.size)
        assertEquals(SyncState.RETIRED, merged.single { it.id == "synced" }.state)
        assertEquals(SyncState.RETIRED, merged.single { it.id == "syncing" }.state)
        assertEquals(SyncState.RETIRED, merged.single { it.id == "retryable" }.state)
        assertEquals(SyncState.RETIRED, merged.single { it.id == "permanent" }.state)
        assertEquals(SyncState.SKIPPED, merged.single { it.id == "pending" }.state)
        assertEquals(sessions.single { it.id == "manual" }, merged.single { it.id == "manual" })
        assertEquals(sessions.single { it.id == "skipped" }, merged.single { it.id == "skipped" })
    }

    @Test fun `new candidate match prevents migration revocation`() {
        val old = session(SyncState.SYNCED).copy(id = "old")
        val candidate = old.copy(id = "new", endMillis = old.endMillis + 60_000)

        val merged = mergeSleepSessions(listOf(old), listOf(candidate), setOf(old.id))

        assertEquals(1, merged.size)
        assertEquals(old.id, merged.single().id)
        assertEquals(SyncState.PENDING, merged.single().state)
        assertEquals(old.revision + 1, merged.single().revision)
    }
}
