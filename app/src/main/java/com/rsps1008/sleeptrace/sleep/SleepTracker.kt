package com.rsps1008.sleeptrace.sleep

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepSegmentRequest

object SleepTracker {
    private const val REQUEST_CODE = 1001
    @Volatile private var lastRequestedMode: Int? = null
    fun hasActivityRecognition(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun unsubscribe(context: Context) {
        ActivityRecognition.getClient(context).removeSleepSegmentUpdates(pendingIntent(context))
        lastRequestedMode = null
    }

    /** Keep segment delivery all day; request periodic classify events from 15 minutes before a sleep window through its end. */
    @SuppressLint("MissingPermission")
    fun syncSubscription(
        context: Context,
        schedule: SleepSchedule?,
        enabled: Boolean,
        nowMillis: Long,
        force: Boolean = false
    ) {
        if (!enabled || schedule == null || !hasActivityRecognition(context)) {
            runCatching { unsubscribe(context) }
            return
        }
        val requestMode = if (schedule.classificationWindowAt(nowMillis) != null) {
            SleepSegmentRequest.SEGMENT_AND_CLASSIFY_EVENTS
        } else SleepSegmentRequest.SEGMENT_EVENTS_ONLY
        if (!force && lastRequestedMode == requestMode) return
        try {
            ActivityRecognition.getClient(context).requestSleepSegmentUpdates(
                pendingIntent(context), SleepSegmentRequest(requestMode)
            ).addOnSuccessListener { lastRequestedMode = requestMode }
                .addOnFailureListener { lastRequestedMode = null }
        } catch (_: SecurityException) {
            lastRequestedMode = null
        }
    }
    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SleepUpdateReceiver::class.java)
        // Play services fills in the sleep-event payload. The receiver remains explicit/private.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
