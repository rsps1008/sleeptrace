package com.rsps1008.sleeptrace.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rsps1008.sleeptrace.health.HealthConnectSync
import com.rsps1008.sleeptrace.sleep.SleepReconciler
import kotlinx.coroutines.CancellationException

class SleepReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        SleepReconciler(applicationContext).reconcile()
        if (HealthConnectSync(applicationContext).syncPending()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { Result.retry() }
}
