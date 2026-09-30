package com.rsps1008.sleeptrace.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rsps1008.sleeptrace.sleepDependencies

/** Debug APK only. Does not start a service or bypass permissions, pause, battery or schedule. */
class CaptureExperimentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = runCatching { CaptureExperiment.valueOf(intent.getStringExtra("mode") ?: "OFF") }.getOrNull() ?: return
        context.sleepDependencies().motionSettings.experiment = mode
        MotionService.active?.refreshConfiguration()
    }
}
