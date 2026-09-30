package com.rsps1008.sleeptrace.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepObservationWindows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.sleepDataStore by preferencesDataStore("sleeptrace_settings")

class SleepPreferences internal constructor(
    private val readSettings: suspend () -> Pair<SleepSchedule, Boolean>,
    private val writeSettings: suspend (SleepSchedule) -> Unit,
    private val applyObservation: (SleepSchedule) -> SleepSchedule,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this(context, { SleepObservationWindows.apply(context, it) })

    /** Small injection seam for fixed-clock persistence and thread regression tests. */
    internal constructor(context: Context, observation: (SleepSchedule) -> SleepSchedule) : this(
        { readAndroidSettings(context) }, { writeAndroidSettings(context, it) }, observation
    )

    suspend fun schedule(): SleepSchedule = withContext(ioDispatcher) {
        val (nominal, configured) = readSettings()
        if (configured) applyObservation(nominal) else nominal
    }

    suspend fun configured(): Boolean = withContext(ioDispatcher) { readSettings().second }

    suspend fun saveSchedule(schedule: SleepSchedule) = withContext(ioDispatcher) { writeSettings(schedule) }

    private companion object {
        private val startKey = intPreferencesKey("schedule_start_minute")
        private val endKey = intPreferencesKey("schedule_end_minute")
        private val weekendStartKey = intPreferencesKey("schedule_weekend_start_minute")
        private val weekendEndKey = intPreferencesKey("schedule_weekend_end_minute")
        private val enabledKey = booleanPreferencesKey("tracking_enabled")

        suspend fun readAndroidSettings(context: Context): Pair<SleepSchedule, Boolean> {
            val values = context.sleepDataStore.data.first()
            val weekendStart = values[weekendStartKey]?.takeIf { it in 0 until MINUTES_PER_DAY }
            val weekendEnd = values[weekendEndKey]?.takeIf { it in 0 until MINUTES_PER_DAY }
            val nominal = SleepSchedule(values[startKey] ?: 0, values[endKey] ?: 540,
                weekendStart?.takeIf { weekendEnd != null }, weekendEnd?.takeIf { weekendStart != null })
            return nominal to (values[enabledKey] ?: false)
        }

        suspend fun writeAndroidSettings(context: Context, schedule: SleepSchedule) {
            context.sleepDataStore.edit {
                it[startKey] = schedule.startMinute
                it[endKey] = schedule.endMinute
                if (schedule.weekendStartMinute == null || schedule.weekendEndMinute == null) {
                    it.remove(weekendStartKey)
                    it.remove(weekendEndKey)
                } else {
                    it[weekendStartKey] = schedule.weekendStartMinute
                    it[weekendEndKey] = schedule.weekendEndMinute
                }
                it[enabledKey] = true
            }
        }

        const val MINUTES_PER_DAY = 24 * 60
    }
}
