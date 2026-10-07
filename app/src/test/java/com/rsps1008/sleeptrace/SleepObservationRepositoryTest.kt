package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler
import com.rsps1008.sleeptrace.sleep.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SleepObservationRepositoryTest {
    private val zone = ZoneId.of("UTC")
    private val minute = 60_000L
    private lateinit var originalZone: TimeZone
    @Before fun setZone() { originalZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone(zone)) }
    @After fun restoreZone() { TimeZone.setDefault(originalZone) }
    private fun time(date: String, h: Int, m: Int = 0) = LocalDate.parse(date).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val start get() = time("2026-09-29", 23)
    private fun at(m: Int) = start + m * minute
    private fun sample(m: Int, confidence: Int) = ClassificationSample(at(m), confidence, 0, 0)
    private val nominal get() = SleepSchedule(23 * 60, 7 * 60)
    private val window get() = SleepWindow(start, at(480))
    private val woke get() = listOf(sample(300, 90), sample(400, 10), sample(410, 10), sample(420, 10))

    // A disk-backed test adapter: recreated repositories/preferences read a new storage instance.
    private class Disk(private val file: Path, val events: MutableList<String>) : ObservationPersistence {
        var fail = false
        var access: (String) -> Unit = {}
        override fun read(): Map<SleepWindow, ObservationEnd> {
            access("read")
            if (!Files.exists(file)) return emptyMap()
            return Files.readAllLines(file).associate {
                val v = it.split('|')
                SleepWindow(v[0].toLong(), v[1].toLong()) to ObservationEnd(v[2].toLong(), v[3].toBoolean())
            }
        }
        override fun commit(updates: Map<SleepWindow, ObservationEnd>, removals: Set<SleepWindow>): Boolean {
            access("commit"); events += "commit"
            if (fail) return false
            val result = read().toMutableMap()
            removals.forEach(result::remove); result.putAll(updates)
            Files.write(file, result.map { (w, end) -> "${w.startMillis}|${w.endMillis}|${end.endMillis}|${end.closed}" })
            return true
        }
    }

    private inner class Harness(var schedule: SleepSchedule = nominal, var now: Long = at(420)) : AutoCloseable {
        val file = Files.createTempDirectory("sleep-observation-test").resolve("records")
        val events = mutableListOf<String>()
        var disk = Disk(file, events)
        var samples = woke
        var queryAccess: () -> Unit = {}
        var dirtyAction: () -> Unit = {}
        fun repository(
            clock: () -> Long = { now },
            saver: Boolean = false,
            segments: (Long, Long) -> List<SleepSegment> = { _, _ -> emptyList() }
        ) = SleepObservationRepository(disk,
            recentSamples = { since -> queryAccess(); events += "query"; samples.filter { it.timeMillis >= since } },
            markDirty = { events += "dirty"; dirtyAction() },
            onClosed = { events += "closed"; events += "refresh"; events += "reconcile" }, clock = clock, zone = zone,
            waitForWakeEvidence = saver, recentSegments = segments)
        fun preferences(repository: SleepObservationRepository = repository()) = SleepPreferences(
            readSettings = { schedule to true }, writeSettings = { schedule = it }, applyObservation = repository::apply)
        fun seed(w: SleepWindow, end: ObservationEnd) { assertTrue(disk.commit(mapOf(w to end), emptySet())); events.clear() }
        fun reload() { disk = Disk(file, events) }
        override fun close() { Files.deleteIfExists(file); Files.deleteIfExists(file.parent) }
    }

    @Test fun `saver segment prevents data-insufficient state when classifications are absent`() = runBlocking {
        Harness(now = at(3_000)).use { h ->
            h.samples = emptyList()
            val effective = h.preferences(h.repository(saver = true,
                segments = { _, _ -> listOf(SleepSegment(at(60), at(470), 100)) })).schedule()
            assertTrue(effective.isObservationClosed(window))
            assertFalse(effective.isObservationDataInsufficient(window))
        }
    }

    @Test fun `midnight and nonmidnight all-day settings ignore wake evidence via preferences`() = runBlocking {
        for (hour in listOf(0, 23)) Harness(SleepSchedule(hour * 60, hour * 60)).use { h ->
            val w = h.schedule.windowForStartDate(LocalDate.parse("2026-09-29"), zone)
            h.now = w.startMillis + 750 * minute
            h.samples = listOf(600 to 90, 730 to 10, 740 to 10, 750 to 10).map {
                ClassificationSample(w.startMillis + it.first * minute, it.second, 0, 0)
            }
            val effective = h.preferences().schedule()
            assertEquals("all-day start $hour", w, effective.windowAt(w.startMillis + 900 * minute, zone))
            assertFalse(effective.requiresWindowBoundary())
            assertTrue(SleepWindowScheduler.shouldRunForegroundService(effective, h.now, zone))
            assertNotNull(effective.classificationWindowAt(h.now, zone))
            assertEquals(SleepWindowScheduler.nextBoundary(h.schedule, h.now, zone),
                SleepWindowScheduler.nextBoundary(effective, h.now, zone))
            assertFalse(SleepUsageSnapshot.isWindowComplete(session(w.startMillis, w.endMillis), effective, h.now, zone))
            assertEquals(h.schedule.label(), effective.label())
            assertTrue(h.disk.read().isEmpty())
        }
    }

    @Test fun `weekday and weekend full-day decisions do not contaminate ordinary days or inheritance`() = runBlocking {
        val cases = listOf(
            Triple(SleepSchedule(0, 0, 23 * 60, 7 * 60), "2026-09-28", true),
            Triple(SleepSchedule(0, 0, 23 * 60, 7 * 60), "2026-10-03", false),
            Triple(SleepSchedule(23 * 60, 7 * 60, 0, 0), "2026-09-28", false),
            Triple(SleepSchedule(23 * 60, 7 * 60, 0, 0), "2026-10-03", true),
            Triple(SleepSchedule(23 * 60, 23 * 60), "2026-10-03", true)
        )
        cases.forEach { (schedule, dateText, fullDay) -> Harness(schedule).use { h ->
            val date = LocalDate.parse(dateText)
            val w = schedule.windowForStartDate(date, zone)
            val firstLow = w.startMillis + (w.endMillis - w.startMillis) * 3 / 4
            h.now = firstLow + 20 * minute
            h.samples = listOf(ClassificationSample(firstLow - 40 * minute, 90, 0, 0)) +
                (0..2).map { ClassificationSample(firstLow + it * 10 * minute, 10, 0, 0) }
            val effective = h.preferences().schedule()
            assertEquals("$dateText $schedule", if (fullDay) w.endMillis else firstLow,
                effective.windowForStartDate(date, zone).endMillis)
            assertEquals(fullDay, schedule.isFullDayForStartDate(date))
            if (!fullDay) {
                h.samples = listOf(ClassificationSample(w.endMillis - 5 * minute, 90, 0, 0))
                h.now = w.endMillis
                h.disk.commit(emptyMap(), h.disk.read().keys)
                assertEquals(w.endMillis + 25 * minute, h.preferences().schedule().windowForStartDate(date, zone).endMillis)
            }
        } }
    }

    @Test fun `persisted all-day pollution is cleaned without deleting legal ordinary or unrelated keys`() = runBlocking {
        val mixed = SleepSchedule(0, 0, 23 * 60, 7 * 60)
        Harness(mixed, time("2026-09-29", 15)).use { h ->
            val full = mixed.windowForStartDate(LocalDate.parse("2026-09-28"), zone)
            val ordinary = mixed.windowForStartDate(LocalDate.parse("2026-09-26"), zone)
            val legal = ObservationEnd(ordinary.startMillis + 400 * minute, true)
            val unrelated = SleepWindow(full.startMillis + minute, full.endMillis + minute)
            h.disk.commit(mapOf(full to ObservationEnd(full.startMillis + 730 * minute, true), ordinary to legal,
                unrelated to ObservationEnd(unrelated.endMillis - minute, true)), emptySet())
            h.reload()
            val effective = h.preferences().schedule()
            assertEquals(full, effective.windowForStartDate(LocalDate.parse("2026-09-28"), zone))
            assertFalse(h.disk.read().containsKey(full))
            assertEquals(legal, h.disk.read()[ordinary])
            assertTrue(h.disk.read().containsKey(unrelated))
            val allDay = effective.copy(weekendStartMinute = 0, weekendEndMinute = 0)
            assertFalse(allDay.requiresWindowBoundary())
            assertFalse(effective.copy(weekendStartMinute = null, weekendEndMinute = null).requiresWindowBoundary())
        }
    }

    @Test fun `DST full-day setting ignores shortened override even on 23 and 25 hour days`() {
        val dst = ZoneId.of("America/New_York")
        for ((dateText, hours) in listOf("2026-03-08" to 23, "2026-11-01" to 25)) {
            val date = LocalDate.parse(dateText)
            val schedule = SleepSchedule(0, 0)
            val w = schedule.windowForStartDate(date, dst)
            assertEquals(hours * 60 * minute, w.endMillis - w.startMillis)
            assertTrue(schedule.isFullDayForStartDate(date))
            val legacy = schedule.copy(observationEnds = mapOf(w to w.startMillis + 12 * 60 * minute))
            assertEquals(w, legacy.windowForStartDate(date, dst))
            val file = Files.createTempFile("dst-observation", ".txt")
            try {
                val disk = Disk(file, mutableListOf())
                disk.commit(mapOf(w to ObservationEnd(w.startMillis + 12 * 60 * minute, true)), emptySet())
                val repo = SleepObservationRepository(disk, { error("Full-day must not query") }, {}, {},
                    { w.startMillis + 15 * 60 * minute }, dst)
                assertEquals(w, repo.apply(schedule).windowForStartDate(date, dst))
                assertTrue(disk.read().isEmpty())
            } finally { Files.deleteIfExists(file) }
        }
        val mixed = SleepSchedule(23 * 60, 23 * 60, 60, 540)
        val friday = LocalDate.parse("2026-10-02")
        assertTrue(mixed.isFullDayForStartDate(friday))
        val w = mixed.windowForStartDate(friday, dst)
        assertEquals(2 * 60 * minute, w.endMillis - w.startMillis)
        assertEquals(w.endMillis, mixed.windowForStartDate(friday.plusDays(1), dst).startMillis)
    }

    @Test fun `completed all-day nominal windows still reach analyzer and usage completion`() = runBlocking {
        Harness(SleepSchedule(0, 0), time("2026-09-30", 1)).use { h ->
            val effective = h.preferences().schedule()
            val ended = effective.windowForStartDate(LocalDate.parse("2026-09-29"), zone)
            val completed = SleepUsageSnapshot.completedWindows(effective.windowsBetween(ended.startMillis, h.now, zone), h.now)
            assertTrue(completed.contains(ended))
            val session = session(ended.startMillis, ended.endMillis)
            assertTrue(SleepUsageSnapshot.isWindowComplete(session, effective, h.now, zone))
            val analyzed = SleepAnalyzer.analyzeByWindow(listOf(SleepSegment(ended.startMillis, ended.endMillis, 100)),
                emptyList(), emptyList(), effective, completed) { true }
            assertEquals(ended.endMillis, analyzed.single().endMillis)
            assertNotNull(effective.windowAt(h.now, zone))
        }
    }

    @Test fun `late callback saves closure and reloaded preferences never reopen it or notify twice`() = runBlocking {
        Harness(now = at(515)).use { h ->
            h.seed(window, ObservationEnd(at(505), false)); h.samples = listOf(sample(500, 90))
            h.reload()
            val effective = h.preferences().schedule()
            assertEquals(at(505), effective.windowForStartDate(LocalDate.parse("2026-09-29"), zone).endMillis)
            assertEquals(ObservationEnd(at(505), true), h.disk.read()[window])
            assertEquals(listOf("query", "commit", "dirty", "closed", "refresh", "reconcile"), h.events)
            h.events.clear(); h.reload(); h.samples += sample(520, 90); h.now = at(520)
            val preferences = h.preferences()
            repeat(3) { assertNull(preferences.schedule().windowAt(h.now, zone)) }
            assertEquals(ObservationEnd(at(505), true), h.disk.read()[window])
            assertTrue(h.events.isEmpty())
        }
    }

    @Test fun `failed commit throws without dirty closure refresh or reconcile`() = runBlocking {
        Harness().use { h ->
            h.disk.fail = true
            try { h.preferences().schedule(); fail("Expected commit failure") } catch (expected: IllegalStateException) {
                assertEquals("Unable to persist observation window", expected.message)
            }
            assertEquals(listOf("query", "commit"), h.events)
            assertTrue(h.disk.read().isEmpty())
        }
    }

    @Test fun `failed closure save preserves old durable open record`() = runBlocking {
        Harness(now = at(515)).use { h ->
            h.seed(window, ObservationEnd(at(505), false)); h.samples = listOf(sample(500, 90))
            h.disk.fail = true
            try { h.preferences().schedule(); fail("Expected commit failure") } catch (_: IllegalStateException) { }
            assertEquals(ObservationEnd(at(505), false), h.disk.read()[window])
            assertEquals(listOf("query", "commit"), h.events)
        }
    }

    @Test fun `concurrent older refresh cannot overwrite a persisted closure`() {
        Harness(now = at(515)).use { h ->
            h.seed(window, ObservationEnd(at(505), false)); h.samples = listOf(sample(500, 90))
            val closedCommitted = CountDownLatch(1)
            val olderStarted = CountDownLatch(1)
            h.dirtyAction = { closedCommitted.countDown(); check(olderStarted.await(10, TimeUnit.SECONDS)) }
            val newer = h.repository(clock = { at(515) })
            val older = h.repository(clock = { at(500) })
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<SleepSchedule> { newer.apply(nominal) }
                check(closedCommitted.await(10, TimeUnit.SECONDS))
                val second = executor.submit<SleepSchedule> { olderStarted.countDown(); older.apply(nominal) }
                first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS)
                assertEquals(ObservationEnd(at(505), true), h.disk.read()[window])
                assertEquals(1, h.events.count { it == "commit" })
                assertEquals(1, h.events.count { it == "closed" })
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `changed nominal key does not apply old end or usage snapshot`() = runBlocking {
        Harness().use { h ->
            h.seed(window, ObservationEnd(at(400), true))
            val snapshot = UsageSnapshot(window.startMillis, at(400), true, emptyList(), at(420),
                window.startMillis - 30 * minute)
            h.preferences().saveSchedule(SleepSchedule(23 * 60, 8 * 60))
            h.samples = emptyList()
            val effective = h.preferences().schedule()
            val changed = effective.windowForStartDate(LocalDate.parse("2026-09-29"), zone)
            assertEquals(at(540), changed.endMillis)
            assertEquals(ObservationEnd(at(400), true), h.disk.read()[window])
            assertFalse(SleepUsageSnapshot.canReuse(snapshot, changed, true))
        }
    }

    @Test fun `fast wake closure persists once and all consumers use the shorter window`() = runBlocking {
        Harness(now = at(440)).use { h ->
            h.samples = listOf(sample(350, 90), sample(430, 10), sample(440, 10))
            val effective = h.preferences().schedule()
            assertEquals(ObservationEnd(at(430), true), h.disk.read()[window])
            assertFalse(SleepWindowScheduler.shouldRunForegroundService(effective, h.now, zone))
            assertNull(effective.classificationWindowAt(h.now, zone))
            assertEquals(at(430), analyze(effective, at(480)).single().endMillis)
            assertTrue(SleepUsageSnapshot.isWindowComplete(session(start, at(430)), effective, h.now, zone))
            assertFalse(SleepUsageSnapshot.canReuse(
                UsageSnapshot(start, at(480), true, emptyList(), at(480)),
                SleepWindow(start, at(430)), true))
            h.events.clear(); h.reload(); h.samples += sample(445, 90); h.now = at(445)
            assertNull(h.preferences().schedule().windowAt(h.now, zone))
            assertTrue(h.events.isEmpty())
        }
    }

    @Test fun `formal preferences called on controlled Main move reads queries commit and notifications to IO`() {
        Executors.newSingleThreadExecutor { Thread(it, "controlled-Main") }.asCoroutineDispatcher().use { main ->
            Harness().use { h ->
                lateinit var mainThread: Thread
                h.disk.access = { assertNotSame("storage $it", mainThread, Thread.currentThread()) }
                h.queryAccess = { assertNotSame("SQLite query seam", mainThread, Thread.currentThread()) }
                h.dirtyAction = {
                    assertNotSame("notification", mainThread, Thread.currentThread())
                    assertEquals(ObservationEnd(at(400), true), h.disk.read()[window])
                }
                val preferences = h.preferences()
                runBlocking(main) {
                    mainThread = Thread.currentThread()
                    assertNull(preferences.schedule().windowAt(at(420), zone))
                    assertSame(mainThread, Thread.currentThread())
                }
                assertEquals(listOf("query", "commit", "dirty", "closed", "refresh", "reconcile"), h.events)
            }
        }
    }

    @Test fun `preferences propagate cancellation without observation side effects`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val preferences = SleepPreferences(readSettings = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, writeSettings = {}, applyObservation = { error("Cancelled settings must not apply") })
        val job = launch { preferences.schedule() }
        entered.await(); job.cancelAndJoin(); cancelled.await()
        assertTrue(job.isCancelled)
    }

    @Test fun `receiver seam appends late samples before formal preferences and publishes expired controls`() = runBlocking {
        Harness(now = at(515)).use { h ->
            h.seed(window, ObservationEnd(at(505), false)); h.samples = emptyList()
            processSleepClassifications(listOf(sample(500, 90)),
                appendSamples = { h.events += "append"; h.samples = it },
                effectiveSchedule = { h.preferences().schedule() },
                updateControls = { effective ->
                    h.events += "controls"
                    assertNotNull(effective)
                    assertFalse(SleepWindowScheduler.shouldRunForegroundService(effective, h.now, zone))
                    assertNull(effective!!.classificationWindowAt(h.now, zone))
                    assertTrue(SleepUsageSnapshot.isWindowComplete(session(start, at(505)), effective, h.now, zone))
                })
            assertEquals(listOf("append", "query", "commit", "dirty", "closed", "refresh", "reconcile", "controls"), h.events)
            assertEquals(ObservationEnd(at(505), true), h.disk.read()[window])
        }
    }

    @Test fun `formal effective schedules keep all control and analyzer consumers consistent`() = runBlocking {
        Harness().use { h ->
            val early = h.preferences().schedule()
            assertFalse(SleepWindowScheduler.shouldRunForegroundService(early, h.now, zone))
            assertNull(early.classificationWindowAt(h.now, zone))
            assertTrue(SleepUsageSnapshot.isWindowComplete(session(start, at(400)), early, h.now, zone))
            assertEquals(at(400), SleepWindowScheduler.nextBoundary(early, at(390), zone)!!.atMillis)
            assertEquals(at(400), analyze(early, at(480)).single().endMillis)
            assertEquals(nominal.label(), early.label())
        }
        Harness(now = at(480)).use { h ->
            h.samples = listOf(sample(475, 90))
            val extended = h.preferences().schedule()
            assertTrue(SleepWindowScheduler.shouldRunForegroundService(extended, at(500), zone))
            assertNotNull(extended.classificationWindowAt(at(500), zone))
            assertFalse(SleepUsageSnapshot.isWindowComplete(session(start, at(500)), extended, at(500), zone))
            assertEquals(at(505), SleepWindowScheduler.nextBoundary(extended, at(500), zone)!!.atMillis)
            assertEquals(at(505), analyze(extended, at(520)).single().endMillis)
        }
        Harness(now = at(485)).use { h ->
            h.samples = listOf(sample(475, 90))
            val expired = h.preferences().schedule()
            assertNull(expired.windowAt(h.now, zone)); assertNull(expired.classificationWindowAt(h.now, zone))
            assertTrue(SleepUsageSnapshot.isWindowComplete(session(start, at(480)), expired, h.now, zone))
            assertTrue(SleepWindowScheduler.nextBoundary(expired, h.now, zone)!!.atMillis > h.now)
            assertEquals(at(480), analyze(expired, at(520)).single().endMillis)
            assertNotNull(expired.windowAt(at(1440), zone))
        }
    }

    private fun session(begin: Long, end: Long) = SleepSession(startMillis = begin, endMillis = end,
        confidence = 80, awakeMillis = 0, state = SyncState.PENDING, reason = "test")
    private fun analyze(schedule: SleepSchedule, end: Long) = SleepAnalyzer.analyzeByWindow(
        listOf(SleepSegment(start, end, 100)), emptyList(), emptyList(), schedule,
        listOf(schedule.windowForStartDate(LocalDate.parse("2026-09-29"), zone))) { true }
}
