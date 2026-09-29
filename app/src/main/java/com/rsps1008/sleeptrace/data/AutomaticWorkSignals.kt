package com.rsps1008.sleeptrace.data

import android.content.Context

/** Persisted work generation lets KEEP requests detect raw-data, session, and permission changes during a run. */
object AutomaticWorkSignals {
    private const val PREFS = "sleeptrace_maintenance"
    private const val GENERATION = "reconcile_generation"
    private const val RECONCILED = "reconciled_generation"

    @Synchronized
    fun markDirty(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(GENERATION, prefs.getLong(GENERATION, 0L) + 1).apply()
    }

    fun generation(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(GENERATION, 0L)

    fun isDirty(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(GENERATION, 0L) > prefs.getLong(RECONCILED, 0L)
    }

    @Synchronized
    fun markReconciled(context: Context, generation: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(GENERATION, 0L) == generation) {
            prefs.edit().putLong(RECONCILED, generation).apply()
        }
    }
}
