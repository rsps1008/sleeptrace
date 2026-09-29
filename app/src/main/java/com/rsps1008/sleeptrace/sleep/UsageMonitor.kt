package com.rsps1008.sleeptrace.sleep

import android.app.AppOpsManager
import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

object UsageMonitor {
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun accessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Returns only proven foreground-interaction periods; unavailable data is never treated as sleep. */
    @SuppressLint("MissingPermission") // PACKAGE_USAGE_STATS is verified through AppOps before querying.
    fun interactionIntervals(context: Context, start: Long, end: Long): List<UsageInterval> {
        return interactionIntervals(context, listOf(UsageInterval(start, end)))
    }

    /** Reads the shared event span once; callers can pass overlapping sleep windows without duplicate scans. */
    @SuppressLint("MissingPermission")
    fun interactionIntervals(context: Context, targets: List<UsageInterval>): List<UsageInterval> {
        if (!hasAccess(context)) return emptyList()
        val valid = targets.filter { it.endMillis > it.startMillis }
        if (valid.isEmpty()) return emptyList()
        val start = valid.minOf { it.startMillis }
        val end = valid.maxOf { it.endMillis }
        val manager = context.getSystemService(UsageStatsManager::class.java)
        // Carry screen/foreground state across the left boundary, including an app opened earlier.
        val events = manager.queryEvents(start - 24 * 60 * 60 * 1000L, end) ?: return emptyList()
        val open = mutableMapOf<String, Long>()
        var screenStart: Long? = null
        var screenOff = false
        val result = mutableListOf<UsageInterval>()
        fun add(left: Long, right: Long) {
            if (right > maxOf(start, left)) result += UsageInterval(maxOf(start, left), minOf(end, right))
        }
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> { screenStart = screenStart ?: event.timeStamp; screenOff = false }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenStart?.let { add(it, event.timeStamp) }; screenStart = null; screenOff = true
                    open.values.forEach { add(it, event.timeStamp) }; open.clear()
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> if (!screenOff) open.putIfAbsent(event.packageName, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    open.remove(event.packageName)?.let { add(it, event.timeStamp) }
                }
            }
        }
        screenStart?.let { add(it, end) }
        open.values.forEach { add(it, end) }
        return result
    }
}
