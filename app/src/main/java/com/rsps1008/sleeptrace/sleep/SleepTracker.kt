package com.rsps1008.sleeptrace.sleep

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepSegmentRequest
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.motion.MotionSettings

object SleepTracker {
    private const val REQUEST_CODE = 1001
    fun hasActivityRecognition(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded immediately above; permission can be revoked between checks.
    fun subscribe(context: Context, complete: (Result<Unit>) -> Unit = {}) {
        if (!hasActivityRecognition(context)) {
            complete(Result.failure(SecurityException("缺少活動辨識權限"))); return
        }
        try {
            ActivityRecognition.getClient(context).requestSleepSegmentUpdates(
                pendingIntent(context), SleepSegmentRequest.getDefaultSleepSegmentRequest()
            ).addOnSuccessListener { complete(Result.success(Unit)) }
                .addOnFailureListener { complete(Result.failure(it)) }
        } catch (error: SecurityException) { complete(Result.failure(error)) }
    }
    fun unsubscribe(context: Context) {
        ActivityRecognition.getClient(context).removeSleepSegmentUpdates(pendingIntent(context))
    }
    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SleepUpdateReceiver::class.java)
        // Play services fills in the sleep-event payload. The receiver remains explicit/private.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
    suspend fun resubscribeIfConfigured(context: Context) {
        if (SleepPreferences(context).configured() && MotionSettings(context).enabled && hasActivityRecognition(context)) subscribe(context)
    }
}
