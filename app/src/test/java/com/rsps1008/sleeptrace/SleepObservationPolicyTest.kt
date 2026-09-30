package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.*
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepObservationPolicyTest {
    private val zone = ZoneId.of("UTC")
    private val start = LocalDate.of(2026, 9, 29).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L
    private fun at(minutes: Int) = start + minutes * minute
    private val window = SleepWindow(start, at(480))
    private fun sample(minutes: Int, confidence: Int) = ClassificationSample(at(minutes), confidence, 0, 0)
    private val woke = listOf(sample(300, 90), sample(400, 10), sample(410, 15), sample(420, 20))
    private fun resolve(samples: List<ClassificationSample>, now: Int, previous: ObservationEnd? = null) =
        SleepObservationPolicy.resolve(window, previous, samples, at(now), at(1440))

    @Test fun `sustained morning wake closes before scheduled end`() {
        assertEquals(ObservationEnd(at(400), true), resolve(woke, 420))
    }

    @Test fun `single low score or short interruption cannot close`() {
        assertFalse(resolve(woke.take(2), 400).closed)
        assertFalse(resolve(listOf(sample(300, 90), sample(400, 10), sample(405, 10), sample(410, 10)), 410).closed)
        assertFalse(resolve(woke + sample(415, 60), 420).closed)
    }

    @Test fun `no sleep evidence night wake gaps and stale low reports cannot close early`() {
        assertFalse(resolve(woke.drop(1), 420).closed)
        assertFalse(resolve(listOf(sample(40, 90), sample(100, 10), sample(110, 10), sample(120, 10)), 120).closed)
        assertFalse(resolve(listOf(sample(300, 90), sample(380, 10), sample(410, 10), sample(420, 10)), 420).closed)
        assertFalse(resolve(woke, 440).closed)
    }

    @Test fun `recent sleep extends and subsequent sleep renews observation`() {
        val first = resolve(listOf(sample(475, 90)), 480)
        assertEquals(ObservationEnd(at(505), false), first)
        assertEquals(ObservationEnd(at(530), false), resolve(listOf(sample(475, 90), sample(500, 85)), 505, first))
        assertEquals(ObservationEnd(at(505), true), resolve(listOf(sample(475, 90)), 505, first))
    }

    @Test fun `latest contrary report future sample or missing evidence does not renew`() {
        assertEquals(ObservationEnd(at(480), true), resolve(emptyList(), 480))
        assertEquals(ObservationEnd(at(480), true), resolve(listOf(sample(450, 90)), 480))
        assertEquals(ObservationEnd(at(480), true), resolve(listOf(sample(475, 90), sample(480, 30)), 480))
        assertEquals(ObservationEnd(at(480), true), resolve(listOf(sample(490, 90)), 480))
    }

    @Test fun `persistent closure survives delayed reports and cannot reopen`() {
        val closed = resolve(woke, 420)
        assertEquals(closed, resolve(woke + sample(475, 90), 480, closed))
    }

    @Test fun `extension stops at next scheduled start`() {
        assertEquals(ObservationEnd(at(510), false), SleepObservationPolicy.resolve(window, ObservationEnd(at(505), false),
            listOf(sample(500, 90)), at(500), at(510)))
        assertEquals(ObservationEnd(at(510), true), SleepObservationPolicy.resolve(window,
            ObservationEnd(at(510), false), listOf(sample(510, 90)), at(510), at(510)))
    }

    @Test fun `later sleep cannot resurrect a lapsed observation`() {
        assertEquals(ObservationEnd(at(480), true), resolve(listOf(sample(500, 90)), 500))
        assertEquals(ObservationEnd(at(505), true), resolve(listOf(sample(515, 90)), 515, ObservationEnd(at(505), false)))
    }

    @Test fun `effective window governs service subscription alarm snapshot and candidate clipping`() {
        val nominal = SleepSchedule(23 * 60, 7 * 60)
        val early = nominal.copy(observationEnds = mapOf(window to at(400)))
        assertNull(early.windowAt(at(420), zone))
        assertNull(early.classificationWindowAt(at(420), zone))
        assertEquals(at(400), SleepWindowScheduler.nextBoundary(early, at(390), zone)?.atMillis)
        assertTrue(SleepUsageSnapshot.isWindowComplete(session(at(400)), early, at(420), zone))
        assertFalse(SleepUsageSnapshot.isWindowComplete(session(at(400)), nominal, at(420), zone))

        val extended = nominal.copy(observationEnds = mapOf(window to at(530)))
        assertTrue(SleepWindowScheduler.shouldRunForegroundService(extended, at(500), zone))
        assertNotNull(extended.classificationWindowAt(at(500), zone))
        assertFalse(SleepUsageSnapshot.isWindowComplete(session(at(510)), extended, at(510), zone))
        assertEquals(at(530), SleepWindowScheduler.nextBoundary(extended, at(500), zone)?.atMillis)
        assertEquals(nominal.label(), extended.label())
        // The analyzer uses the local zone for intersections; choose a matching schedule for UTC instants.
        val local = ZoneId.systemDefault()
        val localStart = java.time.Instant.ofEpochMilli(start).atZone(local)
        val localEnd = java.time.Instant.ofEpochMilli(at(480)).atZone(local)
        val localNominal = SleepSchedule(localStart.hour * 60 + localStart.minute, localEnd.hour * 60 + localEnd.minute)
        val localEffective = localNominal.copy(observationEnds = mapOf(window to at(530)))
        val sessions = SleepAnalyzer.analyzeByWindow(listOf(SleepSegment(start, at(520), 100)),
            emptyList(), emptyList(), localEffective, listOf(SleepWindow(start, at(530)))) { true }
        assertEquals(at(520), sessions.single().endMillis)
    }

    private fun session(end: Long) = SleepSession(startMillis = start, endMillis = end,
        confidence = 80, awakeMillis = 0, state = SyncState.PENDING, reason = "test")
}
