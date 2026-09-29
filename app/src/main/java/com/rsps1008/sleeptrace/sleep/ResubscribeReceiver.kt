package com.rsps1008.sleeptrace.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.sleepDependencies
import com.rsps1008.sleeptrace.work.WorkScheduler
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler

class ResubscribeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val recoveryBroadcast = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
        val alarmPermissionChanged = action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        val clockChanged = action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED
        if (!recoveryBroadcast && !alarmPermissionChanged && !clockChanged) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dependencies = context.sleepDependencies()
                val configured = dependencies.preferences.configured()
                val schedule = if (configured) dependencies.preferences.schedule() else null
                val enabled = dependencies.motionSettings.enabled
                if (recoveryBroadcast) {
                    SleepTracker.syncSubscription(context, schedule, enabled, System.currentTimeMillis(), force = true)
                } else {
                    SleepTracker.syncSubscription(context, schedule, enabled, System.currentTimeMillis())
                }
                if (configured && enabled && schedule != null) SleepWindowScheduler.schedule(context, schedule)
                else SleepWindowScheduler.cancel(context)
                WorkScheduler.schedule(context)

                if (configured && enabled && schedule != null && SleepTracker.hasActivityRecognition(context)) {
                    val inWindow = schedule.windowAt(System.currentTimeMillis()) != null
                    val shouldStart = inWindow
                    val service = MotionService.active
                    if (service != null) {
                        service.refreshConfiguration()
                    } else if (shouldStart) {
                        runCatching { MotionService.start(context) }.onFailure {
                            dependencies.motionSettings.status = "系統暫時限制背景啟動，開啟 App 後會自動恢復"
                        }
                    }
                }
            } finally { pending.finish() }
        }
    }
}
