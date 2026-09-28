package com.rsps1008.sleeptrace.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit

object WorkScheduler {
    fun reconcileSoon(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
        "sleeptrace_reconcile_now", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<SleepReconcileWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
    )
    fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "sleeptrace_reconcile", ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<SleepReconcileWorker>(6, TimeUnit.HOURS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
    )
}
