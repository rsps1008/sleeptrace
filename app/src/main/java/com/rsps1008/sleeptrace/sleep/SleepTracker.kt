package com.rsps1008.sleeptrace.sleep

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepSegmentRequest
import java.util.concurrent.Executor

object SleepTracker {
    private const val REQUEST_CODE = 1001
    private var subscriptionController: SleepSubscriptionController? = null
    private val completionExecutor = Executor { it.run() }
    fun hasActivityRecognition(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun unsubscribe(context: Context) {
        controller(context).request(null)
    }

    @Synchronized
    @SuppressLint("MissingPermission") // Permission is checked for each requested subscription; revocation is caught below.
    private fun controller(context: Context): SleepSubscriptionController {
        subscriptionController?.let { return it }
        val appContext = context.applicationContext
        return SleepSubscriptionController { mode, complete ->
            try {
                val client = ActivityRecognition.getClient(appContext)
                val operation = if (mode == null) client.removeSleepSegmentUpdates(pendingIntent(appContext))
                    else client.requestSleepSegmentUpdates(pendingIntent(appContext), SleepSegmentRequest(mode))
                operation.addOnCompleteListener(completionExecutor) { complete(it.isSuccessful) }
            } catch (error: RuntimeException) {
                Log.w("SleepTracker", "睡眠訂閱更新失敗，等待下次更新", error)
                complete(false)
            }
        }.also { subscriptionController = it }
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
        controller(context).request(requestMode, force)
    }
    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SleepUpdateReceiver::class.java)
        // Play services fills in the sleep-event payload. The receiver remains explicit/private.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
