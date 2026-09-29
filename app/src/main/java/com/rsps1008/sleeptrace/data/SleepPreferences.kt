package com.rsps1008.sleeptrace.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import kotlinx.coroutines.flow.first

private val Context.sleepDataStore by preferencesDataStore("sleeptrace_settings")

class SleepPreferences(private val context: Context) {
    private val startKey = intPreferencesKey("schedule_start_minute")
    private val endKey = intPreferencesKey("schedule_end_minute")
    private val weekendStartKey = intPreferencesKey("schedule_weekend_start_minute")
    private val weekendEndKey = intPreferencesKey("schedule_weekend_end_minute")
    private val enabledKey = booleanPreferencesKey("tracking_enabled")

    suspend fun schedule(): SleepSchedule {
        val values = context.sleepDataStore.data.first()
        val weekendStart = values[weekendStartKey]?.takeIf { it in 0 until MINUTES_PER_DAY }
        val weekendEnd = values[weekendEndKey]?.takeIf { it in 0 until MINUTES_PER_DAY }
        return SleepSchedule(values[startKey] ?: 0, values[endKey] ?: 540,
            weekendStart?.takeIf { weekendEnd != null }, weekendEnd?.takeIf { weekendStart != null })
    }

    suspend fun configured(): Boolean = context.sleepDataStore.data.first()[enabledKey] ?: false

    suspend fun saveSchedule(schedule: SleepSchedule) {
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

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
