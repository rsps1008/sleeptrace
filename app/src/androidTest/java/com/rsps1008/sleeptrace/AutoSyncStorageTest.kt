package com.rsps1008.sleeptrace

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.health.AutomaticSyncQueue
import com.rsps1008.sleeptrace.sleep.SyncState
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
        val migrationComplete = prefs.getBoolean("sessions_migrated_v2", false)
        fun record(store: SleepStore) = store.sessions().first { it.id == "legacy-night" }
        try {
            val legacy = JSONObject().put("id", "legacy-night").put("start", 1_000).put("end", 7_201_000)
                .put("confidence", 40).put("awake", 0).put("state", "NEEDS_REVIEW").put("reason", "old")
            assertTrue(prefs.edit().putString("sessions", JSONArray().put(legacy).toString()).putBoolean("sessions_migrated_v2", false).commit())
            val store = SleepStore(context)
            assertEquals(SyncState.PENDING, record(store).state)
            assertFalse(AutomaticSyncQueue.drain(store::sessions, store::updateIfCurrent) { throw IllegalStateException("offline") })
            val reloaded = SleepStore(context)
            assertEquals(SyncState.FAILED_RETRYABLE, record(reloaded).state)
            var writes = 0
            assertTrue(AutomaticSyncQueue.drain(reloaded::sessions, reloaded::updateIfCurrent) { writes++ })
            assertEquals(SyncState.SYNCED, record(SleepStore(context)).state)
            assertTrue(AutomaticSyncQueue.drain(reloaded::sessions, reloaded::updateIfCurrent) { writes++ })
            assertEquals(1, writes)
            reloaded.reviseTimes("legacy-night", 1_000, 7_261_000)
            val revised = record(SleepStore(context))
            assertEquals(2L, revised.revision)
            assertEquals(0L, revised.awakeMillis)
            assertTrue(revised.awakeIntervals.isEmpty())
            assertFalse(revised.usageSnapshotApplied)
            assertEquals(SyncState.PENDING, revised.state)
            assertTrue(AutomaticSyncQueue.drain(reloaded::sessions, reloaded::updateIfCurrent) {
                assertEquals("legacy-night", it.id)
                assertEquals(2L, it.revision)
            })
        } finally {
            val editor = prefs.edit().putBoolean("sessions_migrated_v2", migrationComplete)
            if (original == null) editor.remove("sessions") else editor.putString("sessions", original)
            editor.commit()
        }
    }
}
