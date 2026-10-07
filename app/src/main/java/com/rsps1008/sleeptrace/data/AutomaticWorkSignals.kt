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
    fun reconciliationStart(
        context: Context,
        retentionStartMillis: Long,
        earliestUncompletedWindowStart: Long
    ): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return reconciliationStartFor(
            retentionStartMillis,
            prefs.getLong(PENDING_FROM, Long.MAX_VALUE),
            prefs.getLong(LAST_COMPLETED_WINDOW_END, 0L),
            earliestUncompletedWindowStart
        )
    }

    internal fun reconciliationStartFor(
        retentionStartMillis: Long,
        pendingFromMillis: Long,
        previousEndMillis: Long,
        earliestUncompletedWindowStart: Long
    ): Long {
        // An upgrade has no trustworthy per-window progress. Scan the retained raw evidence once
        // instead of assuming that the first new callback represents the oldest unfinished night.
        if (previousEndMillis == 0L) return retentionStartMillis
        return maxOf(retentionStartMillis, minOf(
            previousEndMillis,
            earliestUncompletedWindowStart,
            pendingFromMillis.takeUnless { it == Long.MAX_VALUE } ?: Long.MAX_VALUE
        ))
    }

    fun lastCompletedWindowEnd(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_COMPLETED_WINDOW_END, 0L)

    @Synchronized
    fun markReconciled(context: Context, generation: Long, contiguousCompletedWindowEndMillis: Long? = null) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(GENERATION, 0L) == generation) {
            // Mark the reconcile rule upgrade complete only after all data transactions succeeded.
            prefs.edit().putLong(RECONCILED, generation)
                .putInt(STAGING_VERSION, SleepStageEstimator.ALGORITHM_VERSION).apply()
            val completed = contiguousCompletedWindowEndMillis ?: return
            val pending = prefs.getLong(PENDING_FROM, Long.MAX_VALUE)
            val editor = prefs.edit().putLong(LAST_COMPLETED_WINDOW_END,
                maxOf(prefs.getLong(LAST_COMPLETED_WINDOW_END, 0L), completed))
            // Only clear raw-event work proven to be before the contiguous boundary. A newer
            // callback belongs to an open/unknown night and must remain pending.
            if (pending < completed) editor.remove(PENDING_FROM)
            editor.apply()
        }
    }
}
