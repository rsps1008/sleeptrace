package com.rsps1008.sleeptrace.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rsps1008.sleeptrace.sleepDependencies
import com.rsps1008.sleeptrace.health.SyncOutcome
import com.rsps1008.sleeptrace.health.isTransientSyncError
import com.rsps1008.sleeptrace.sleep.SleepReconciler
import kotlinx.coroutines.CancellationException

class SleepReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        SleepReconciler(applicationContext).reconcile()
        when (applicationContext.sleepDependencies().healthSync.syncPendingOutcome()) {
            SyncOutcome.SUCCESS -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
            SyncOutcome.FAILURE -> Result.failure()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (isTransientSyncError(error)) Result.retry() else Result.failure()
    }
}
