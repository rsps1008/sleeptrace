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

    @Test fun v2MotionAndV10SessionsUpgradeWithoutInventingFeaturesOrErasingStages() = isolated { context ->
        context.openOrCreateDatabase("motion.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE minutes (start INTEGER PRIMARY KEY, covered INTEGER NOT NULL, active INTEGER NOT NULL, squared REAL NOT NULL, samples INTEGER NOT NULL, placement TEXT NOT NULL, featureVersion INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("INSERT INTO minutes VALUES (0,60000,0,1.5,60,'BED',4)")
            db.version = 2
        }
        MotionStore(context).use { store ->
            val old = store.read(0, MINUTE_MS).single()
            assertEquals(4, old.featureVersion); assertNull(old.movementEvents); assertNull(old.maxDelta)
            assertNull(old.longestGapMillis); assertNull(old.recordingId)
            val engine = MotionAccumulator(SamplingPlan.choose(0), Placement.AUTO)
            for (i in 0..600) engine.add(MINUTE_MS + i * 100L, .01, 0.0, 9.81)
            val fresh = engine.drain(2 * MINUTE_MS)
            store.append(fresh, now = 2 * MINUTE_MS)
            assertEquals(fresh, store.read(MINUTE_MS, 2 * MINUTE_MS))
            store.append(fresh, now = 2 * MINUTE_MS)
            assertEquals(fresh, store.read(MINUTE_MS, 2 * MINUTE_MS))
        }
        // Recreate the preceding version's complete schema, keeping existing session identity.
        SleepEventStore(context).use { store ->
            store.upsertSession(SleepSession(id="old",startMillis=0,endMillis=60*MINUTE_MS,confidence=60,
                awakeMillis=0,state=SyncState.SYNCED,reason="legacy",revision=7,
                stageIntervals=listOf(SleepStageInterval(0,60*MINUTE_MS,SleepStage.DEEP))))
        }
        context.openOrCreateDatabase("sleep_events.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("ALTER TABLE sessions RENAME TO sessions_v11_test")
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, confidence INTEGER NOT NULL, awake INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL, manual INTEGER NOT NULL, error TEXT, revision INTEGER NOT NULL, awakeIntervals TEXT NOT NULL, usageSnapshotApplied INTEGER NOT NULL, stageIntervals TEXT NOT NULL DEFAULT '[]')")
            db.execSQL("INSERT INTO sessions SELECT id,start,end,confidence,awake,state,reason,manual,error,revision,awakeIntervals,usageSnapshotApplied,stageIntervals FROM sessions_v11_test")
            db.execSQL("DROP TABLE sessions_v11_test")
            db.version = 10
        }
        SleepEventStore(context).use { store ->
            val old = requireNotNull(store.session("old"))
            assertEquals(7L, old.revision); assertEquals(SyncState.SYNCED, old.state)
            assertNull(old.stageAlgorithmVersion); assertNull(old.stageFeatureVersion)
            assertEquals(SleepStage.DEEP, old.stageIntervals.single().stage)
            val next = old.copy(stageAlgorithmVersion=5,stageFeatureVersion=4)
            store.upsertSession(next); assertEquals(next,store.session("old"))
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
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION,row.featureVersion)
            assertEquals(60,row.sampleCount)
            assertEquals(MINUTE_MS,row.coveredMillis)
            assertEquals(0L,row.activeMillis)
            assertEquals(.01*.01*MINUTE_MS,row.squaredDeltaTime,.000001)
            assertEquals(.01,row.rms,.000001)
            store.append(listOf(MotionMinute(0,10_000,5_000,25.0,7,Placement.AUTO,1)),now=2*MINUTE_MS)
            val afterLateLegacy = store.read(0,MINUTE_MS).single()
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION,afterLateLegacy.featureVersion)
            assertEquals(MINUTE_MS,afterLateLegacy.coveredMillis)
            assertEquals(60,afterLateLegacy.sampleCount)
        }
    }

    @Test fun v3CaptureMigrationReconstructsPolicyTargetWithoutInventingSensorCapabilities() = isolated { context ->
        context.openOrCreateDatabase("motion.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE capture_runs (id INTEGER PRIMARY KEY, windowStart INTEGER NOT NULL, registeredAt INTEGER NOT NULL, trigger TEXT NOT NULL, periodUs INTEGER NOT NULL, latencyUs INTEGER NOT NULL, fifoCount INTEGER NOT NULL, wakeUp INTEGER NOT NULL, firstEvent INTEGER, rawEvents INTEGER NOT NULL, rejectedEvents INTEGER NOT NULL, meanInterval REAL, maxInterval INTEGER)")
            db.execSQL("INSERT INTO capture_runs VALUES (1,0,100,'GOOGLE_CLASSIFICATION',1500000,0,10000,0,NULL,100,0,44.0,50)")
            db.execSQL("INSERT INTO capture_runs VALUES (2,0,200,'EARLY_2HZ',750000,0,10000,0,NULL,200,0,40.0,45)")
            db.version = 3
        }

        MotionStore(context).use { store ->
            val captures = store.captures(0, 1_000)
            assertEquals(2, captures.size)
            assertEquals(1_000_000, captures[0].targetPeriodUs)
            assertEquals(1_500_000, captures[0].periodUs)
            assertEquals(500_000, captures[1].targetPeriodUs)
            assertEquals(750_000, captures[1].periodUs)
            captures.forEach {
                assertNull(it.sensorMinDelayUs)
                assertNull(it.sensorMaxDelayUs)
                assertNull(it.fifoReservedEventCount)
            }
        }
    }

    @Test fun motionStoreUsesSemanticPriorityAndNeverAddsDifferentFeatures() = isolated { context ->
        MotionStore(context).use { store ->
            fun put(start: Long, version: Int, covered: Long, active: Long, squared: Double, samples: Int) =
                store.append(listOf(MotionMinute(start, covered, active, squared, samples, Placement.AUTO, version)), now = start + MINUTE_MS)
            put(0, MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION, 20_000, 1_000, 4.0, 20)
            put(0, MotionAccumulator.CURRENT_FEATURE_VERSION, 30_000, 2_000, 9.0, 30)
            var saved = store.read(0, MINUTE_MS).single()
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION, saved.featureVersion)
            assertEquals(30_000L, saved.coveredMillis)
            assertEquals(2_000L, saved.activeMillis)
            assertEquals(9.0, saved.squaredDeltaTime, 0.0)
            assertEquals(30, saved.sampleCount)
            put(0, MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION, 10_000, 4_000, 16.0, 10)
            saved = store.read(0, MINUTE_MS).single()
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION, saved.featureVersion)
            assertEquals(30_000L, saved.coveredMillis)
            put(MINUTE_MS, MotionAccumulator.CURRENT_FEATURE_VERSION, 30_000, 2_000, 9.0, 30)
            put(MINUTE_MS, MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION, 10_000, 4_000, 16.0, 10)
            saved = store.read(MINUTE_MS, 2 * MINUTE_MS).single()
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION, saved.featureVersion)
            assertEquals(30_000L, saved.coveredMillis)
            put(2 * MINUTE_MS, MotionAccumulator.LEGACY_CALLBACK_FEATURE_VERSION, 10_000, 2_000, 4.0, 10)
            put(2 * MINUTE_MS, MotionAccumulator.CURRENT_FEATURE_VERSION, 20_000, 3_000, 9.0, 20)
            put(2 * MINUTE_MS, MotionAccumulator.CURRENT_FEATURE_VERSION, 5_000, 1_000, 1.0, 5)
            saved = store.read(2 * MINUTE_MS, 3 * MINUTE_MS).single()
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION, saved.featureVersion)
            assertEquals(25_000L, saved.coveredMillis)
            assertEquals(4_000L, saved.activeMillis)
            assertEquals(10.0, saved.squaredDeltaTime, 0.0)
            assertEquals(25, saved.sampleCount)

            put(3 * MINUTE_MS, MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION, 20_000, 1_000, 4.0, 20)
            put(3 * MINUTE_MS, MotionAccumulator.CURRENT_FEATURE_VERSION, 30_000, 2_000, 9.0, 30)
            saved = store.read(3 * MINUTE_MS, 4 * MINUTE_MS).single()
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION, saved.featureVersion)
            assertEquals(30_000L, saved.coveredMillis)
            assertEquals(2_000L, saved.activeMillis)
            assertEquals(9.0, saved.squaredDeltaTime, 0.0)
            assertEquals(30, saved.sampleCount)

            put(4 * MINUTE_MS, MotionAccumulator.CURRENT_FEATURE_VERSION, 30_000, 2_000, 9.0, 30)
            put(4 * MINUTE_MS, MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION, 20_000, 1_000, 4.0, 20)
            saved = store.read(4 * MINUTE_MS, 5 * MINUTE_MS).single()
            assertEquals(MotionAccumulator.CURRENT_FEATURE_VERSION, saved.featureVersion)
            assertEquals(30_000L, saved.coveredMillis)
            assertEquals(2_000L, saved.activeMillis)
            assertEquals(9.0, saved.squaredDeltaTime, 0.0)
            assertEquals(30, saved.sampleCount)
        }
    }

    @Test fun motionStoreKeepsV2OverV1RegardlessOfWriteOrder() = isolated { context ->
        MotionStore(context).use { store ->
            fun put(start: Long, version: Int, covered: Long, samples: Int) = store.append(
                listOf(MotionMinute(start, covered, 0, .01 * .01 * covered, samples, Placement.AUTO, version)),
                now = start + MINUTE_MS
            )

            put(0, MotionAccumulator.LEGACY_CALLBACK_FEATURE_VERSION, 10_000, 10)
            put(0, MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION, 20_000, 20)
            var saved = store.read(0, MINUTE_MS).single()
            assertEquals(MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION, saved.featureVersion)
            assertEquals(20_000L, saved.coveredMillis)
            assertEquals(20, saved.sampleCount)
            put(0, MotionAccumulator.LEGACY_CALLBACK_FEATURE_VERSION, 5_000, 5)
            saved = store.read(0, MINUTE_MS).single()
            assertEquals(MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION, saved.featureVersion)
            assertEquals(20_000L, saved.coveredMillis)
            assertEquals(20, saved.sampleCount)
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

    @Test fun currentAlgorithmMigratesPreviouslyReconciledVersionNine() = isolated { context ->
        val prefs = context.getSharedPreferences("sleeptrace_maintenance", Context.MODE_PRIVATE)
        prefs.edit().putLong("reconcile_generation", 5L).putLong("reconciled_generation", 5L)
            .putInt("reconciled_staging_version", 9).commit()
        assertTrue(SleepStageEstimator.ALGORITHM_VERSION > 9)
        assertTrue(AutomaticWorkSignals.isDirty(context))
        val migrationGeneration = AutomaticWorkSignals.generation(context)
        AutomaticWorkSignals.markReconciled(context, migrationGeneration)
        assertFalse(AutomaticWorkSignals.isDirty(context))
        assertEquals(SleepStageEstimator.ALGORITHM_VERSION, prefs.getInt("reconciled_staging_version", 0))
    }

    @Test fun ruleMigrationKeepsRetirementTombstonesInsteadOfDeletingRows() = isolated { context ->
        val store = SleepStore(context)
        val synced = SleepSession(
            id = "old-synced", startMillis = 1_000, endMillis = 7_201_000,
            confidence = 50, awakeMillis = 0, state = SyncState.SYNCED, reason = "old rule"
        )
        val pending = synced.copy(id = "old-pending", state = SyncState.PENDING)
        val manual = synced.copy(id = "manual", manuallyEdited = true)
        listOf(synced, pending, manual).forEach(store::upsert)

        store.mergeCalculated(
            calculated = emptyList(),
            analysisStartMillis = 0,
            analysisEndMillis = 3 * 60 * 60 * 1_000L,
            invalidatedAutomaticSessionIds = setOf(synced.id, pending.id, manual.id),
            expectedGenerationForInvalidation = AutomaticWorkSignals.generation(context)
        )

        val saved = store.sessions().associateBy { it.id }
        assertEquals(setOf("old-synced", "old-pending", "manual"), saved.keys)
        assertEquals(SyncState.RETIRED, saved.getValue("old-synced").state)
        assertEquals(SyncState.SKIPPED, saved.getValue("old-pending").state)
        assertEquals(manual, saved.getValue("manual"))

        val generationFenced = synced.copy(id = "generation-fenced")
        store.upsert(generationFenced)
        val capturedGeneration = AutomaticWorkSignals.generation(context)
        AutomaticWorkSignals.markDirty(context)
        store.mergeCalculated(
            calculated = emptyList(),
            analysisStartMillis = 0,
            analysisEndMillis = 3 * 60 * 60 * 1_000L,
            invalidatedAutomaticSessionIds = setOf(generationFenced.id),
            expectedGenerationForInvalidation = capturedGeneration
        )
        assertEquals(SyncState.SYNCED, store.session(generationFenced.id)?.state)
    }
}
