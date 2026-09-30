package com.rsps1008.sleeptrace.sleep

import android.content.Context
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.sleepDependencies
import com.rsps1008.sleeptrace.work.WorkScheduler
import java.time.Instant
import java.time.ZoneId

/** Persist closure before analysis. Recreating a process cannot reopen a completed snapshot. */
object SleepObservationWindows {
    private const val PREFIX = "observation_end_"
    private const val DAY = 24 * 60 * 60 * 1000L

    @Synchronized
    fun apply(context: Context, nominal: SleepSchedule, now: Long = System.currentTimeMillis()): SleepSchedule {
        val prefs = context.getSharedPreferences("sleeptrace_motion", Context.MODE_PRIVATE)
        val records = prefs.all.filterKeys { it.startsWith(PREFIX) }.mapNotNull { (key, value) ->
            val parts = key.removePrefix(PREFIX).split('_')
            val values = (value as? String)?.split(':') ?: return@mapNotNull null
            val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val actual = values.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            SleepWindow(start, end) to ObservationEnd(actual, values.getOrNull(1) == "true")
        }.toMap().toMutableMap()
        val windows = nominal.windowsBetween(now - DAY, now + 1)
            .filter { now >= it.startMillis + (it.endMillis - it.startMillis) / 2 && records[it]?.closed != true }
        val samples = if (windows.isEmpty()) emptyList() else
            context.sleepDependencies().store.recentSamples(windows.minOf { it.startMillis })
        var changed = false
        var closed = false
        val editor = prefs.edit()
        windows.forEach { window ->
            val date = Instant.ofEpochMilli(window.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            val nextStart = nominal.windowForStartDate(date.plusDays(1)).startMillis
            val result = SleepObservationPolicy.resolve(window, records[window], samples, now, nextStart)
            if (result != records[window] && (result.closed || result.endMillis != window.endMillis)) {
                editor.putString("$PREFIX${window.startMillis}_${window.endMillis}", "${result.endMillis}:${result.closed}")
                records[window] = result
                changed = true
                closed = closed || result.closed
            }
        }
        records.keys.filter { it.startMillis < now - 14 * DAY }.forEach { window ->
            editor.remove("$PREFIX${window.startMillis}_${window.endMillis}")
            records.remove(window)
            changed = true
        }
        if (changed) {
            check(editor.commit()) { "Unable to persist observation window" }
            AutomaticWorkSignals.markDirty(context)
            if (closed) {
                // A foreground/worker read can also discover waking; stop capture via its flush path.
                MotionService.active?.refreshConfiguration()
                WorkScheduler.reconcileSoon(context)
            }
        }
        return nominal.copy(observationEnds = records.mapValues { it.value.endMillis })
    }
}
