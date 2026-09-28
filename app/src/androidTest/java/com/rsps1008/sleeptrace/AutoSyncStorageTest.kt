package com.rsps1008.sleeptrace

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.health.AutomaticSyncQueue
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoSyncStorageTest {
    @Test fun legacyMigrationRetryAndVersionSurviveReload() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use a disposable emulator" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManager.getInstance(context).cancelAllWork().result.get()
        val prefs = context.getSharedPreferences("sleeptrace_records", Context.MODE_PRIVATE)
        val original = prefs.getString("sessions", null)
        try {
            val legacy = JSONObject().put("id", "legacy-night").put("start", 1_000).put("end", 7_201_000)
                .put("confidence", 40).put("awake", 0).put("state", "NEEDS_REVIEW").put("reason", "old")
            assertTrue(prefs.edit().putString("sessions", JSONArray().put(legacy).toString()).commit())
            val store = SleepStore(context)
            assertEquals(SyncState.PENDING, store.sessions().single().state)
            assertFalse(AutomaticSyncQueue.drain(store::sessions, store::updateIfCurrent) { throw IllegalStateException("offline") })
            val reloaded = SleepStore(context)
            assertEquals(SyncState.FAILED, reloaded.sessions().single().state)
            var writes = 0
            assertTrue(AutomaticSyncQueue.drain(reloaded::sessions, reloaded::updateIfCurrent) { writes++ })
            assertEquals(SyncState.SYNCED, SleepStore(context).sessions().single().state)
            assertTrue(AutomaticSyncQueue.drain(reloaded::sessions, reloaded::updateIfCurrent) { writes++ })
            assertEquals(1, writes)
            reloaded.reviseTimes("legacy-night", 1_000, 7_261_000, listOf(UsageInterval(61_000, 121_000)))
            val revised = SleepStore(context).sessions().single()
            assertEquals(2L, revised.revision)
            assertEquals(60_000L, revised.awakeMillis)
            assertEquals(1, revised.awakeIntervals.size)
            assertEquals(SyncState.PENDING, revised.state)
            assertTrue(AutomaticSyncQueue.drain(reloaded::sessions, reloaded::updateIfCurrent) {
                assertEquals("legacy-night", it.id)
                assertEquals(2L, it.revision)
            })
        } finally {
            if (original == null) prefs.edit().remove("sessions").commit()
            else prefs.edit().putString("sessions", original).commit()
        }
    }
}
