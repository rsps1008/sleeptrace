package com.rsps1008.sleeptrace

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.sleeptrace.data.SleepEventStore
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Database migration checks use isolated names and run only on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class StagingStorageRuntimeTest {
    private fun isolated(block: (Context) -> Unit) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use a disposable emulator" }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "staging_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = target.getSharedPreferences(prefix + name, mode)
            override fun getDatabasePath(name: String): File = target.getDatabasePath(prefix + name)
            override fun deleteDatabase(name: String): Boolean = target.deleteDatabase(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                target.openOrCreateDatabase(prefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                target.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
        }
        try { block(context) } finally {
            context.deleteDatabase("motion.db")
            context.deleteDatabase("sleep_events.db")
            for (name in listOf("sleeptrace_records", "sleeptrace_maintenance", "sleeptrace_automatic_work"))
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun oldMotionFeaturesKeepTheirVersionAndNeverMixWithResampledContributions() = isolated { context ->
        context.openOrCreateDatabase("motion.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE minutes (start INTEGER PRIMARY KEY, covered INTEGER NOT NULL, active INTEGER NOT NULL, squared REAL NOT NULL, samples INTEGER NOT NULL, placement TEXT NOT NULL)")
            db.execSQL("INSERT INTO minutes VALUES (0,60000,0,1.5,2990,'AUTO')")
            db.version = 1
        }
        MotionStore(context).use { store ->
            assertEquals(1, store.read(0,MINUTE_MS).single().featureVersion)
            store.append(listOf(MotionMinute(0,MINUTE_MS,0,.01*.01*MINUTE_MS,60,Placement.AUTO)),now=MINUTE_MS)
            val row = store.read(0,MINUTE_MS).single()
            assertEquals(2,row.featureVersion)
            assertEquals(60,row.sampleCount)
            assertEquals(.01,row.rms,.000001)
        }
    }

    @Test fun v7ToV9UsageSnapshotsKeepWindowKeysAndRefreshMissingLookbackEvidence() {
      for (version in 7..9) isolated { context ->
        context.openOrCreateDatabase("sleep_events.db",Context.MODE_PRIVATE,null).use { db ->
            val key=if(version==7) "windowStart" else "windowStart,windowEnd"
            db.execSQL("CREATE TABLE usage_snapshots (windowStart INTEGER NOT NULL, windowEnd INTEGER NOT NULL, accessAvailable INTEGER NOT NULL, intervals TEXT NOT NULL, capturedAt INTEGER NOT NULL, PRIMARY KEY($key))")
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO usage_snapshots VALUES (10000000,20000000,1,'[]',20000000)")
            db.version=version
        }
        SleepEventStore(context).use { store ->
            val old=requireNotNull(store.usageSnapshot(10_000_000,20_000_000))
            assertEquals(old.windowStartMillis,old.evidenceStartMillis)
            val lookback=old.windowStartMillis-30*MINUTE_MS
            val updated=old.copy(evidenceStartMillis=lookback,intervals=listOf(UsageInterval(lookback,lookback+MINUTE_MS)))
            store.saveUsageSnapshot(updated)
            assertEquals(updated,store.usageSnapshot(old.windowStartMillis,old.windowEndMillis))
            assertNull(store.usageSnapshot(old.windowStartMillis,old.windowEndMillis+1))
        }
      }
    }

    @Test fun manuallyEditedStagingChangesOnlyStagesAndRevisionAndRejectsStaleSync() = isolated { context ->
        val store=SleepStore(context)
        val original=SleepSession(id="manual",startMillis=0,endMillis=60*MINUTE_MS,confidence=69,
            awakeMillis=0,state=SyncState.SYNCED,reason="manual",manuallyEdited=true,
            stageIntervals=listOf(SleepStageInterval(0,60*MINUTE_MS,SleepStage.LIGHT)))
        store.upsert(original)
        val newStages=listOf(SleepStageInterval(0,20*MINUTE_MS,SleepStage.LIGHT),SleepStageInterval(20*MINUTE_MS,60*MINUTE_MS,SleepStage.DEEP))
        assertTrue(store.updateStageIntervals(original,newStages))
        val updated=requireNotNull(store.session("manual"))
        assertEquals(original.startMillis,updated.startMillis)
        assertEquals(original.endMillis,updated.endMillis)
        assertTrue(updated.manuallyEdited)
        assertEquals(original.revision+1,updated.revision)
        assertEquals(SyncState.PENDING,updated.state)
        assertFalse(store.updateIfCurrent(original,original.copy(state=SyncState.SYNCED)))
        assertFalse(store.updateStageIntervals(updated,newStages))
        val syncing=updated.copy(state=SyncState.SYNCING)
        store.upsert(syncing)
        val revisedStages=listOf(SleepStageInterval(0,25*MINUTE_MS,SleepStage.LIGHT),SleepStageInterval(25*MINUTE_MS,60*MINUTE_MS,SleepStage.DEEP))
        assertTrue(store.updateStageIntervals(syncing,revisedStages))
        val pending=requireNotNull(store.session("manual"))
        assertEquals(SyncState.PENDING,pending.state)
        assertEquals(syncing.revision+1,pending.revision)
        assertEquals(syncing.startMillis,pending.startMillis)
        assertEquals(syncing.endMillis,pending.endMillis)
        assertFalse(store.updateIfCurrent(syncing,syncing.copy(state=SyncState.SYNCED)))
        assertEquals(1,store.sessions().size)
    }

    @Test fun stagingRuleUpgradeRemainsDirtyUntilACompleteCurrentGenerationReconcile() = isolated { context ->
        assertTrue(AutomaticWorkSignals.isDirty(context))
        val initial=AutomaticWorkSignals.generation(context)
        AutomaticWorkSignals.markDirty(context)
        AutomaticWorkSignals.markReconciled(context,initial)
        assertTrue(AutomaticWorkSignals.isDirty(context))
        AutomaticWorkSignals.markReconciled(context,AutomaticWorkSignals.generation(context))
        assertFalse(AutomaticWorkSignals.isDirty(context))
    }
}
