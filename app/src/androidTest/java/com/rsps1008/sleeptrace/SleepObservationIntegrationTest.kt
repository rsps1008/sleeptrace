package com.rsps1008.sleeptrace

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.sleeptrace.data.SleepEventStore
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.sleep.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Only a disposable emulator. Uses separate DB/preferences; never Health Connect or service effects. */
@RunWith(AndroidJUnit4::class)
class SleepObservationIntegrationTest {
    private val zone = ZoneId.of("UTC")
    private val minute = 60_000L
    private val start = LocalDate.of(2026, 9, 29).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(minutes: Int) = start + minutes * minute
    private val window = SleepWindow(start, at(480))

    private class IsolatedContext(base: Context, private val suffix: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(filesDir, "observation-regression/$suffix/$name")
            .also { check(it.parentFile!!.mkdirs() || it.parentFile!!.isDirectory) }
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("observation_regression_${suffix}_$name", mode)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }

    private fun context(suffix: String): Context {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use a disposable emulator" }
        return IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext, suffix)
    }
    private fun offMain(operation: String) = assertNotEquals(operation, Looper.getMainLooper(), Looper.myLooper())

    @Test fun realMainPreferencesQueriesSQLiteAndCommitsBeforeNotifications() = runBlocking {
        val context = context("main")
        val backing = SharedPreferencesObservationPersistence(context.getSharedPreferences("sleeptrace_motion", Context.MODE_PRIVATE))
        val events = mutableListOf<String>()
        val store = SleepEventStore(context)
        try {
            withContext(Dispatchers.IO) {
                assertTrue(backing.commit(mapOf(window to ObservationEnd(at(505), false)), emptySet()))
                store.append(samples = listOf(ClassificationSample(at(500), 90, 0, 0)), now = at(515))
            }
            val persistence = object : ObservationPersistence {
                override fun read(): Map<SleepWindow, ObservationEnd> { offMain("SharedPreferences read"); return backing.read() }
                override fun commit(updates: Map<SleepWindow, ObservationEnd>, removals: Set<SleepWindow>): Boolean {
                    offMain("SharedPreferences commit"); events += "commit"
                    return backing.commit(updates, removals)
                }
            }
            val repo = SleepObservationRepository(persistence,
                recentSamples = { offMain("SQLite recentSamples"); events += "query"; store.recentSamples(it) },
                markDirty = {
                    offMain("dirty signal"); assertEquals(ObservationEnd(at(505), true), backing.read()[window]); events += "dirty"
                }, onClosed = { events += "closed" }, clock = { at(515) }, zone = zone)
            val preferences = SleepPreferences({ SleepSchedule(23 * 60, 7 * 60) to true }, {}, repo::apply)
            withContext(Dispatchers.Main) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                assertNull(preferences.schedule().windowAt(at(515), zone))
            }
            assertEquals(listOf("query", "commit", "dirty", "closed"), events)
            events.clear()
            // A fresh repository and fresh Android SharedPreferences adapter read the durable closure.
            val fresh = SleepObservationRepository(
                SharedPreferencesObservationPersistence(context.getSharedPreferences("sleeptrace_motion", Context.MODE_PRIVATE)),
                { error("Closed window should not query") }, { events += "dirty" }, { events += "closed" }, { at(520) }, zone)
            val reloaded = SleepPreferences({ SleepSchedule(23 * 60, 7 * 60) to true }, {}, fresh::apply)
            withContext(Dispatchers.Main) { repeat(2) { assertNull(reloaded.schedule().windowAt(at(520), zone)) } }
            assertTrue(events.isEmpty())
        } finally { withContext(Dispatchers.IO) { store.close() } }
    }

    @Test fun realPersistedAllDayPollutionIsRepairedThroughPreferences() = runBlocking {
        val context = context("all_day")
        val backing = SharedPreferencesObservationPersistence(context.getSharedPreferences("sleeptrace_motion", Context.MODE_PRIVATE))
        val schedule = SleepSchedule(23 * 60, 23 * 60)
        val full = SleepWindow(start, at(1440))
        withContext(Dispatchers.IO) { assertTrue(backing.commit(mapOf(full to ObservationEnd(at(730), true)), emptySet())) }
        val repo = SleepObservationRepository(backing, { error("All-day must not query") }, {}, {}, { at(750) }, zone)
        val preferences = SleepPreferences({ schedule to true }, {}, repo::apply)
        withContext(Dispatchers.Main) {
            val effective = preferences.schedule()
            assertEquals(full, effective.windowAt(at(900), zone))
            assertFalse(effective.requiresWindowBoundary())
        }
        withContext(Dispatchers.IO) { assertFalse(backing.read().containsKey(full)) }
    }
}
