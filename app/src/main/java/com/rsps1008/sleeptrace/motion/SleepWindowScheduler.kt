package com.rsps1008.sleeptrace.motion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import java.time.ZoneId

data class ScheduleBoundary(
    val atMillis: Long,
    val isWindowStart: Boolean = false,
    val isWindowEnd: Boolean = false,
    val isClassificationStart: Boolean = false
)

/** Schedules one next boundary independently of MotionService's lifetime. */
object SleepWindowScheduler {
    const val ACTION_BOUNDARY = "com.rsps1008.sleeptrace.SLEEP_WINDOW_BOUNDARY"
    const val EXTRA_WINDOW_START = "window_start_boundary"
    const val EXTRA_WINDOW_END = "window_end_boundary"
    const val EXTRA_CLASSIFICATION_START = "classification_start_boundary"
    private const val REQUEST_CODE = 2003
    private const val PREFS = "sleeptrace_motion"
    private const val SCHEDULED_AT_KEY = "scheduled_boundary_at"

    fun hasExactAlarmAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun shouldRunForegroundService(
        schedule: SleepSchedule?,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = schedule?.windowAt(nowMillis, zone) != null

    fun nextBoundary(
        schedule: SleepSchedule,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        saverMode: Boolean = false
    ): ScheduleBoundary? {
        // Saver mode begins its Play-services subscription at the next sleep-window start.
        // It deliberately has no prewarm, motion fallback, or end alarm: those would wake a
        // sleeping device without creating new Sleep API evidence.  A delivered API event,
        // app launch, boot, or the next window start performs any needed follow-up instead.
        if (saverMode) {
            val futureStart = schedule.windowsBetween(nowMillis + 1, nowMillis + 8L * 24 * 60 * 60 * 1000, zone)
                .firstOrNull { it.startMillis > nowMillis }
            return futureStart?.let { ScheduleBoundary(it.startMillis, isWindowStart = true) }
        }
        val futureEnd = nowMillis + 8L * 24 * 60 * 60 * 1000
        val classificationAlreadyRequested = schedule.classificationWindowAt(nowMillis, zone) != null
        val candidates = schedule.windowsBetween(nowMillis, futureEnd, zone).flatMap { window ->
            buildList {
                val classificationStart = window.startMillis - SleepClassificationTrigger.CLASSIFICATION_LEAD_MILLIS
                if (!classificationAlreadyRequested && classificationStart > nowMillis) {
                    add(ScheduleBoundary(classificationStart, isClassificationStart = true))
                }
                if (window.startMillis > nowMillis) add(ScheduleBoundary(window.startMillis, true, false))
                val fallbackAt = window.startMillis + SleepClassificationTrigger.FALLBACK_DELAY_MILLIS
                if (fallbackAt > nowMillis && fallbackAt < window.endMillis) {
                    add(ScheduleBoundary(fallbackAt, false, false))
                }
                if (window.endMillis > nowMillis) add(ScheduleBoundary(window.endMillis, false, true))
            }
        }
        return candidates.minByOrNull { it.atMillis }
    }

    fun schedule(
        context: Context,
        schedule: SleepSchedule?,
        nowMillis: Long = System.currentTimeMillis(),
        saverMode: Boolean = false
    ): ScheduleBoundary? {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existingIntent = boundaryIntent(context, PendingIntent.FLAG_NO_CREATE)
        if (schedule == null || !schedule.requiresWindowBoundary()) {
            if (existingIntent != null) alarm.cancel(existingIntent)
            prefs.edit().remove(SCHEDULED_AT_KEY).apply()
            return null
        }

        val next = nextBoundary(schedule, nowMillis, saverMode = saverMode)
        if (next == null) return null
        val cachedAt = prefs.getLong(SCHEDULED_AT_KEY, 0L)
        if (cachedAt == next.atMillis && existingIntent != null) return next

        val operation = boundaryIntent(context, PendingIntent.FLAG_UPDATE_CURRENT, next) ?: return next
        try {
            if (hasExactAlarmAccess(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, operation)
            } else {
                // This is a best-effort wakeup only. The receiver still checks the active window
                // before attempting to start the foreground service.
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, operation)
            }
            prefs.edit().putLong(SCHEDULED_AT_KEY, next.atMillis).apply()
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, operation)
            prefs.edit().putLong(SCHEDULED_AT_KEY, next.atMillis).apply()
        }
        return next
    }

    fun cancel(context: Context) {
        boundaryIntent(context, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SCHEDULED_AT_KEY).apply()
    }

    fun clearDelivered(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SCHEDULED_AT_KEY).apply()
    }

    private fun boundaryIntent(context: Context, mode: Int, boundary: ScheduleBoundary? = null): PendingIntent? {
        val flags = mode or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, MotionBoundaryReceiver::class.java).setAction(ACTION_BOUNDARY)
                .putExtra(EXTRA_WINDOW_START, boundary?.isWindowStart == true)
                .putExtra(EXTRA_WINDOW_END, boundary?.isWindowEnd == true)
                .putExtra(EXTRA_CLASSIFICATION_START, boundary?.isClassificationStart == true), flags)
    }
}
