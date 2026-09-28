package com.rsps1008.sleeptrace.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.work.WorkScheduler

class ResubscribeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SleepTracker.resubscribeIfConfigured(context)
                WorkScheduler.schedule(context)
                val settings = MotionSettings(context)
                if (SleepPreferences(context).configured() && settings.enabled && SleepTracker.hasActivityRecognition(context)) {
                    runCatching { MotionService.start(context) }.onFailure {
                        settings.status = "系統暫時限制背景啟動，開啟 App 後會自動恢復"
                    }
                }
            } finally { pending.finish() }
        }
    }
}
