package com.rsps1008.sleeptrace.sleep

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.motion.RecordingMode
import com.rsps1008.sleeptrace.sleepDependencies
import com.rsps1008.sleeptrace.work.WorkScheduler
import java.time.ZoneId

/** Android adapter. Production access is exclusively through SleepPreferences.schedule() on IO. */
internal object SleepObservationWindows {
    @WorkerThread
    internal fun apply(context: Context, nominal: SleepSchedule): SleepSchedule = SleepObservationRepository(
        SharedPreferencesObservationPersistence(context.getSharedPreferences("sleeptrace_motion", Context.MODE_PRIVATE)),
        recentSamples = context.sleepDependencies().store::recentSamples,
        recentSegments = { start, end -> context.sleepDependencies().store.segments(start, end) },
        markDirty = { AutomaticWorkSignals.markDirty(context) },
        onClosed = {
            // Async refresh never waits for a service callback while the repository lock is held.
            MotionService.active?.refreshConfiguration()
            WorkScheduler.reconcileSoon(context)
        },
        waitForWakeEvidence = context.sleepDependencies().motionSettings.recordingMode == RecordingMode.BATTERY_SAVER,
        zone = ZoneId.systemDefault()
    ).apply(nominal)
}

internal class SharedPreferencesObservationPersistence(private val prefs: SharedPreferences) : ObservationPersistence {
    override fun read(): Map<SleepWindow, ObservationEnd> = prefs.all
        .filterKeys { it.startsWith(PREFIX) }.mapNotNull { (key, value) ->
            val parts = key.removePrefix(PREFIX).split('_')
            val values = (value as? String)?.split(':') ?: return@mapNotNull null
            val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val actual = values.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val source = values.getOrNull(2)
            val closed = values.getOrNull(1) == "true"
            SleepWindow(start, end) to ObservationEnd(
                actual,
                closed,
                source == "insufficient",
                source == "segment",
                closed && (source == null || source == "wake")
            )
        }.toMap()

    override fun commit(updates: Map<SleepWindow, ObservationEnd>, removals: Set<SleepWindow>): Boolean {
        val affectedKeys = (updates.keys + removals).map(::key)
        val previous = affectedKeys.associateWith { prefs.getString(it, null) }
        val editor = prefs.edit()
        removals.forEach { editor.remove(key(it)) }
        updates.forEach { (window, result) -> editor.putString(key(window),
            "${result.endMillis}:${result.closed}:${when {
                result.dataInsufficient -> "insufficient"
                result.segmentSettled -> "segment"
                else -> "confirmed-wake"
            }}") }
        if (editor.commit()) return true
        // SharedPreferences can update its in-memory map even when the disk commit fails.
        // Restore only our affected keys so a later refresh cannot mistake that map for durability.
        val rollback = prefs.edit()
        previous.forEach { (key, value) ->
            if (value == null) rollback.remove(key) else rollback.putString(key, value)
        }
        check(rollback.commit()) { "Unable to restore observation preferences after failed commit" }
        return false
    }

    private fun key(window: SleepWindow) = "$PREFIX${window.startMillis}_${window.endMillis}"
    private companion object { const val PREFIX = "observation_end_" }
}
