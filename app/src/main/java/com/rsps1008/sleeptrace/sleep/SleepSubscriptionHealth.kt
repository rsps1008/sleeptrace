package com.rsps1008.sleeptrace.sleep

import android.content.Context

/** Durable diagnostic only; Play services has no public read-back API for an existing request. */
data class SleepSubscriptionHealth(
    val lastSuccessMillis: Long,
    val lastFailure: String?
) {
    val isHealthy: Boolean get() = lastFailure == null && lastSuccessMillis > 0L

    companion object {
        private const val PREFS = "sleeptrace_subscription"
        private const val SUCCESS = "last_success"
        private const val FAILURE = "last_failure"

        fun read(context: Context): SleepSubscriptionHealth {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return SleepSubscriptionHealth(prefs.getLong(SUCCESS, 0L), prefs.getString(FAILURE, null))
        }

        fun record(context: Context, success: Boolean, error: String? = null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (success) prefs.edit().putLong(SUCCESS, System.currentTimeMillis()).remove(FAILURE).apply()
            else prefs.edit().putString(FAILURE, error?.take(240) ?: "Google Play services 未接受睡眠訂閱，等待下次自動重試").apply()
        }
    }
}
