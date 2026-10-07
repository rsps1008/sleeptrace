package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.*
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepObservationPolicyTest {
    @Test fun `late in-window high cannot renew expired extension`() {
        assertEquals(ObservationEnd(at(505), true), resolve(listOf(sample(500, 90)), 515, ObservationEnd(at(505), false)))
    }

    @Test fun `saver waits past scheduled end until consecutive wake reports arrive`() {
        val afterEnd = listOf(sample(500, 10), sample(510, 15))
        assertEquals(ObservationEnd(at(480), false), SleepObservationPolicy.resolve(
            window, null, emptyList(), at(500), at(1440), waitForWakeEvidence = true))
        assertEquals(ObservationEnd(at(500), true), SleepObservationPolicy.resolve(
            window, null, afterEnd, at(510), at(1440), waitForWakeEvidence = true))
    }

    @Test fun `historical saver closure uses the report time rather than current time`() {
        val historicalWake = listOf(sample(500, 10), sample(510, 15))
        // The callback was processed days later, but both reports belong to this same night.
        assertEquals(ObservationEnd(at(500), true), SleepObservationPolicy.resolve(
            window, null, historicalWake, at(3_000), at(1_440),
            waitForWakeEvidence = true, historicalWakeEvidence = true))
        assertFalse(SleepObservationPolicy.resolve(
            window, null, historicalWake, at(3_000), at(1_440),
            waitForWakeEvidence = true).closed)
    }

    @Test fun `historical saver keeps the first proven wake despite later daytime reports`() {
        val morningWakeThenDaytime = listOf(
            sample(480, 10), sample(485, 15), // first proved wake
            sample(840, 90),                   // daytime high confidence must not erase it
            sample(900, 10), sample(905, 15)   // nor move wake to a later low run
        )
        assertEquals(ObservationEnd(at(480), true), SleepObservationPolicy.resolve(
            window, null, morningWakeThenDaytime, at(3_000), at(1_440),
            waitForWakeEvidence = true, historicalWakeEvidence = true))
    }

    @Test fun `expired historical night with no events settles as data insufficient`() {
        assertEquals(ObservationEnd(at(480), true, dataInsufficient = true), SleepObservationPolicy.resolve(
            window, null, emptyList(), at(3_000), at(1_440),
            waitForWakeEvidence = true, historicalWakeEvidence = true, settleEmptyHistoricalWindow = true))
    }

    @Test fun `late in-window high cannot reopen nominal window`() {
        assertEquals(ObservationEnd(at(480), true), resolve(listOf(sample(475, 90)), 485))
    }

    @Test fun `one millisecond after effective end cannot renew but equality can`() {
        val previous = ObservationEnd(at(505), false)
        assertEquals(ObservationEnd(at(530), false), resolve(listOf(sample(500, 90)), 505, previous))
        assertEquals(ObservationEnd(at(505), true), SleepObservationPolicy.resolve(window, previous,
            listOf(sample(500, 90)), at(505) + 1, at(1440)))
    }

    @Test fun `legacy all-day override cannot disable afternoon or create alarm boundaries`() {
        val midnight = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli()
        val nominal = SleepWindow(midnight, midnight + 1440 * minute)
        val schedule = SleepSchedule(0, 0, observationEnds = mapOf(nominal to midnight + 730 * minute))
        assertNotNull(schedule.windowAt(midnight + 900 * minute, zone))
        assertFalse(schedule.requiresWindowBoundary())
    }
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

    @Test fun `second half closes after two low reports spanning five minutes`() {
        val samples = listOf(sample(350, 90), sample(430, 10), sample(440, 20))
        assertEquals(ObservationEnd(at(430), true), resolve(samples, 440))
        assertFalse(resolve(samples.take(2), 430).closed)
        assertEquals(ObservationEnd(at(430), true), resolve(listOf(sample(350, 90), sample(430, 10), sample(439, 10)), 439))
    }

    @Test fun `wake confirmation is fast throughout the second half`() {
        assertFalse(resolve(listOf(sample(300, 90), sample(400, 10), sample(404, 10)), 404).closed)
        assertEquals(ObservationEnd(at(400), true), resolve(
            listOf(sample(300, 90), sample(400, 10), sample(405, 10)), 405))
    }

    @Test fun `fast wake uses nominal end during an extension`() {
        assertEquals(ObservationEnd(at(485), true), resolve(
            listOf(sample(440, 90), sample(485, 10), sample(495, 10)), 495,
            ObservationEnd(at(550), false)))
    }

    @Test fun `a missing report restarts wake proof instead of poisoning all subsequent lows`() {
        val samples = listOf(sample(280, 90), sample(330, 10), sample(380, 10), sample(390, 10))
        assertEquals(ObservationEnd(at(380), true), resolve(samples, 390))
    }

    @Test fun `lows before morning do not permanently block a later complete morning run`() {
        assertEquals(ObservationEnd(at(240), true), resolve(
            listOf(sample(150, 90), sample(220, 10), sample(230, 10),
                sample(240, 10), sample(250, 10), sample(260, 10)), 260))
    }

    @Test fun `fast wake still rejects stale sparse interrupted duplicate and unsupported reports`() {
        val high = sample(350, 90)
        assertFalse(resolve(listOf(high, sample(430, 10), sample(450, 10)), 450).closed)
        assertFalse(resolve(listOf(high, sample(430, 10), sample(440, 10)), 451).closed)
        assertFalse(resolve(listOf(high, sample(430, 10), sample(435, 50), sample(440, 10)), 440).closed)
        assertFalse(resolve(listOf(high, sample(430, 10), sample(430, 10)), 440).closed)
        assertFalse(resolve(listOf(sample(430, 10), sample(440, 10)), 440).closed)
        assertFalse(resolve(listOf(sample(420, 90), sample(430, 10), sample(440, 10)), 440).closed)
    }

    @Test fun `single low score or short interruption cannot close`() {
        assertFalse(resolve(woke.take(2), 400).closed)
        assertFalse(resolve(listOf(sample(300, 90), sample(400, 10), sample(404, 10)), 404).closed)
        assertFalse(resolve(woke + sample(415, 60), 420).closed)
    }

    @Test fun `no sleep evidence night wake gaps and stale low reports cannot close early`() {
        assertFalse(resolve(woke.drop(1), 420).closed)
        assertFalse(resolve(listOf(sample(40, 90), sample(100, 10), sample(110, 10), sample(120, 10)), 120).closed)
        assertFalse(resolve(listOf(sample(300, 90), sample(380, 10), sample(410, 10), sample(414, 10)), 414).closed)
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
        repeat(3) {
            assertEquals(closed, resolve(listOf(sample(500, 90)), 515, closed))
            assertEquals(closed, resolve(listOf(sample(520, 90)), 520, closed))
        }
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
