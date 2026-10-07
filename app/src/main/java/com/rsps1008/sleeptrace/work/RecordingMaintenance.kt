package com.rsps1008.sleeptrace.work

import android.content.Context
import com.rsps1008.sleeptrace.motion.RecordingMode
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler
import com.rsps1008.sleeptrace.sleep.SleepSubscriptionHealth
import com.rsps1008.sleeptrace.sleep.SleepTracker
import com.rsps1008.sleeptrace.sleepDependencies

/** One daily, best-effort repair point.  It never starts a sensor or adds a polling alarm. */
internal suspend fun runRecordingMaintenance(context: Context) {
    val dependencies = context.sleepDependencies()
    if (!dependencies.preferences.configured() || !dependencies.motionSettings.enabled) return
    val schedule = dependencies.preferences.schedule()
    if (!SleepTracker.hasActivityRecognition(context)) {
        dependencies.motionSettings.status = "活動辨識權限已關閉，無法接收 Google 睡眠訊號"
        return
    }
    val subscription = SleepSubscriptionHealth.read(context)
    // A recorded failure is deliberately retried only by this existing daily worker (or another
    // natural trigger), never by a tight background loop.
    SleepTracker.syncSubscription(context, schedule, enabled = true, nowMillis = System.currentTimeMillis(),
        force = subscription.lastFailure != null,
        saverWakeGrace = dependencies.motionSettings.recordingMode == RecordingMode.BATTERY_SAVER)
    SleepWindowScheduler.schedule(context, schedule,
        saverMode = dependencies.motionSettings.recordingMode == RecordingMode.BATTERY_SAVER)
}
