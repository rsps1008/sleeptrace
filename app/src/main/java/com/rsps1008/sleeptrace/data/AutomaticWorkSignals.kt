package com.rsps1008.sleeptrace.data

import android.content.Context
import com.rsps1008.sleeptrace.sleep.SleepStageEstimator

/** Persisted work generation lets KEEP requests detect raw-data, session, and permission changes during a run. */
object AutomaticWorkSignals {
    private const val PREFS = "sleeptrace_maintenance"
    private const val GENERATION = "reconcile_generation"
    private const val RECONCILED = "reconciled_generation"
    private const val STAGING_VERSION = "reconciled_staging_version"
    private const val PENDING_FROM = "reconcile_pending_from"
    private const val LAST_COMPLETED_WINDOW_END = "last_completed_sleep_window_end"

    @Synchronized
    fun markDirty(context: Context, affectedFromMillis: Long = System.currentTimeMillis()) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getLong(PENDING_FROM, Long.MAX_VALUE)
        prefs.edit().putLong(GENERATION, prefs.getLong(GENERATION, 0L) + 1)
            // Late Sleep API delivery can belong to a prior night, so this is a watermark rather
            // than a simple "last run" cursor.
            .putLong(PENDING_FROM, minOf(existing, affectedFromMillis)).apply()
    }

    fun generation(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(GENERATION, 0L)

    fun hasPendingRuleMigration(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(STAGING_VERSION, 1) < SleepStageEstimator.ALGORITHM_VERSION

    fun isDirty(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(GENERATION, 0L) > prefs.getLong(RECONCILED, 0L) ||
            prefs.getLong(PENDING_FROM, Long.MAX_VALUE) != Long.MAX_VALUE ||
            hasPendingRuleMigration(context)
    }

    @Synchronized
    fun reconciliationStart(context: Context, retentionStartMillis: Long): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getLong(PENDING_FROM, Long.MAX_VALUE)
        val previousEnd = prefs.getLong(LAST_COMPLETED_WINDOW_END, 0L)
        return when {
            pending != Long.MAX_VALUE -> maxOf(retentionStartMillis, pending)
            previousEnd > 0L -> maxOf(retentionStartMillis, previousEnd)
            else -> retentionStartMillis
        }
    }

    fun lastCompletedWindowEnd(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_COMPLETED_WINDOW_END, 0L)

    @Synchronized
    fun markReconciled(context: Context, generation: Long, completedWindowEndMillis: Long? = null) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(GENERATION, 0L) == generation) {
            // Mark the reconcile rule upgrade complete only after all data transactions succeeded.
            prefs.edit().putLong(RECONCILED, generation)
                .putInt(STAGING_VERSION, SleepStageEstimator.ALGORITHM_VERSION).apply()
            val completed = completedWindowEndMillis ?: return
            prefs.edit().putLong(LAST_COMPLETED_WINDOW_END,
                maxOf(prefs.getLong(LAST_COMPLETED_WINDOW_END, 0L), completed))
                .remove(PENDING_FROM).apply()
        }
    }
}
