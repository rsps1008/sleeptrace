package com.rsps1008.sleeptrace.data

import android.content.Context
import com.rsps1008.sleeptrace.sleep.SleepStageEstimator

/** Persisted work generation lets KEEP requests detect raw-data, session, and permission changes during a run. */
object AutomaticWorkSignals {
    private const val PREFS = "sleeptrace_maintenance"
    private const val GENERATION = "reconcile_generation"
    private const val RECONCILED = "reconciled_generation"
    private const val STAGING_VERSION = "reconciled_staging_version"

    @Synchronized
    fun markDirty(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(GENERATION, prefs.getLong(GENERATION, 0L) + 1).apply()
    }

    fun generation(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(GENERATION, 0L)

    fun hasPendingRuleMigration(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(STAGING_VERSION, 1) < SleepStageEstimator.ALGORITHM_VERSION

    fun isDirty(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(GENERATION, 0L) > prefs.getLong(RECONCILED, 0L) ||
            hasPendingRuleMigration(context)
    }

    @Synchronized
    fun markReconciled(context: Context, generation: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(GENERATION, 0L) == generation) {
            // Mark the reconcile rule upgrade complete only after all data transactions succeeded.
            prefs.edit().putLong(RECONCILED, generation)
                .putInt(STAGING_VERSION, SleepStageEstimator.ALGORITHM_VERSION).apply()
        }
    }
}
