package com.rsps1008.sleeptrace

import android.content.SharedPreferences
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

class ObservationPreferencesPersistenceTest {
    private val zone = ZoneId.of("UTC")
    private val start = LocalDate.of(2026, 9, 29).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(m: Int) = start + m * 60_000L
    private val window = SleepWindow(start, at(480))
    private val key = "observation_end_${window.startMillis}_${window.endMillis}"

    /** Implements SP's documented memory-before-disk behavior even when commit returns false. */
    private class Backend {
        val values = mutableMapOf<String, Any>()
        val results = ArrayDeque<Boolean>()
        val events = mutableListOf<String>()
        fun instance(): SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected SharedPreferences method ${method.name}")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String?; proxy }
                    "remove" -> { pending[args!![0] as String] = null; proxy }
                    "commit" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        val success = if (results.isEmpty()) true else results.removeFirst()
                        events += if (success) "commit_success" else "commit_failure"
                        success
                    }
                    else -> error("Unexpected Editor method ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    @Test fun `failed Android commit rolls back memory before next observation read and sends no effects`() {
        val backend = Backend()
        backend.values[key] = "${at(505)}:false"
        backend.values["recording_enabled"] = true
        backend.results.addAll(listOf(false, true))
        val storage = SharedPreferencesObservationPersistence(backend.instance())
        val repository = SleepObservationRepository(storage, { listOf(ClassificationSample(at(500), 90, 0, 0)) },
            { backend.events += "dirty" }, { backend.events += "closed" }, { at(515) }, zone)
        try { repository.apply(SleepSchedule(23 * 60, 7 * 60)); fail("Expected save failure") }
        catch (expected: IllegalStateException) { assertEquals("Unable to persist observation window", expected.message) }
        assertEquals(ObservationEnd(at(505), false), storage.read()[window])
        assertEquals(true, backend.values["recording_enabled"])
        assertEquals(listOf("commit_failure", "commit_success"), backend.events)
        repository.apply(SleepSchedule(23 * 60, 7 * 60))
        assertEquals(ObservationEnd(at(505), true), SharedPreferencesObservationPersistence(backend.instance()).read()[window])
        assertEquals(listOf("commit_failure", "commit_success", "commit_success", "dirty", "closed"), backend.events)
    }

    @Test fun `old wake string is reclassified as a provisional segment and later segment extends it`() {
        val backend = Backend()
        backend.values[key] = "${at(360)}:true:wake" // Previous release also used wake for segment closures.
        val storage = SharedPreferencesObservationPersistence(backend.instance())
        val segments = mutableListOf(SleepSegment(at(60), at(360), 100))
        val repository = SleepObservationRepository(storage, { emptyList() }, {}, {}, { at(500) }, zone,
            waitForWakeEvidence = true, recentSegments = { _, _ -> segments.toList() })

        assertTrue(storage.read()[window]!!.sourceUnknown)
        val first = repository.apply(SleepSchedule(23 * 60, 7 * 60))
        assertEquals(at(360), first.windowForStartDate(LocalDate.of(2026, 9, 29), zone).endMillis)
        assertEquals("${at(360)}:true:segment", backend.values[key])

        segments += SleepSegment(at(390), at(470), 100)
        val extended = repository.apply(SleepSchedule(23 * 60, 7 * 60))
        assertEquals(at(470), extended.windowForStartDate(LocalDate.of(2026, 9, 29), zone).endMillis)
        assertEquals("${at(470)}:true:segment", backend.values[key])
        assertFalse(storage.read()[window]!!.sourceUnknown)
    }

    @Test fun `old source-less closure with confirmed waking is upgraded to final wake`() {
        val backend = Backend()
        backend.values[key] = "${at(400)}:true"
        val storage = SharedPreferencesObservationPersistence(backend.instance())
        val samples = listOf(
            ClassificationSample(at(400), 10, 0, 0),
            ClassificationSample(at(410), 10, 0, 0)
        )
        val repository = SleepObservationRepository(storage, { samples }, {}, {}, { at(500) }, zone,
            waitForWakeEvidence = true,
            recentSegments = { _, _ -> listOf(SleepSegment(at(60), at(470), 100)) })

        val effective = repository.apply(SleepSchedule(23 * 60, 7 * 60))
        assertEquals(at(400), effective.windowForStartDate(LocalDate.of(2026, 9, 29), zone).endMillis)
        assertEquals("${at(400)}:true:confirmed-wake", backend.values[key])
    }

    @Test fun `failed rollback remains an explicit failure and never publishes completion`() {
        val backend = Backend()
        backend.values[key] = "${at(505)}:false"
        backend.results.addAll(listOf(false, false))
        val repository = SleepObservationRepository(SharedPreferencesObservationPersistence(backend.instance()),
            { listOf(ClassificationSample(at(500), 90, 0, 0)) }, { error("Must not notify dirty") },
            { error("Must not notify completion") }, { at(515) }, zone)
        try { repository.apply(SleepSchedule(23 * 60, 7 * 60)); fail("Expected rollback failure") }
        catch (expected: IllegalStateException) {
            assertEquals("Unable to restore observation preferences after failed commit", expected.message)
        }
        assertEquals(listOf("commit_failure", "commit_failure"), backend.events)
    }

    @Test fun `repair removes only confirmed full-day key and preserves unrelated preference values`() {
        val backend = Backend()
        val full = SleepWindow(start, at(1440))
        val fullKey = "observation_end_${full.startMillis}_${full.endMillis}"
        backend.values[fullKey] = "${at(730)}:true"
        backend.values[key] = "${at(400)}:true" // Different nominal end: do not delete it as confirmed pollution.
        backend.values["recording_enabled"] = false
        val storage = SharedPreferencesObservationPersistence(backend.instance())
        val repo = SleepObservationRepository(storage, { error("Full-day cannot query") }, {}, {}, { at(750) }, zone)
        val schedule = repo.apply(SleepSchedule(23 * 60, 23 * 60))
        assertFalse(backend.values.containsKey(fullKey))
        assertEquals("${at(400)}:true", backend.values[key])
        assertEquals(false, backend.values["recording_enabled"])
        assertEquals(full, schedule.windowAt(at(900), zone))
        assertFalse(schedule.requiresWindowBoundary())
    }
}
