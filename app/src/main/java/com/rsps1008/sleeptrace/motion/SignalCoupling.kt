package com.rsps1008.sleeptrace.motion

enum class CouplingState { SUPPORTED, HELD, INSUFFICIENT }
enum class CouplingBasis { FAST, SPARSE }

data class CouplingWindowStats(
    val historyMinutes: Int = 0,
    val qualifyingMovements: Int = 0,
    val movementSpanMillis: Long? = null
)

data class CouplingEvidence(
    val state: CouplingState,
    val ageMillis: Long?,
    val reason: String?,
    val basis: CouplingBasis? = null,
    val currentBlocker: String? = null,
    val lastResetReason: String? = null,
    /** Null means this row was blocked before the corresponding window was evaluated. */
    val fastStats: CouplingWindowStats? = null,
    val sparseStats: CouplingWindowStats? = null
)

/** Uncalibrated engineering policy. Quiet never refreshes evidence; 45 minutes permits a
 * quiet half-hour while requiring renewed observed movement before trusting a whole night. */
object CouplingPolicy {
    const val HOLD_MILLIS = 45 * MINUTE_MS
    const val FAST_EVIDENCE_WINDOW_MILLIS = 30 * MINUTE_MS
    const val FAST_MIN_SPAN_MILLIS = 8 * MINUTE_MS
    const val FAST_MIN_HISTORY_MINUTES = 20
    const val SPARSE_EVIDENCE_WINDOW_MILLIS = 60 * MINUTE_MS
    const val SPARSE_MIN_SPAN_MILLIS = 30 * MINUTE_MS
    const val SPARSE_MIN_HISTORY_MINUTES = 40
    const val PHONE_MARGIN_MILLIS = 2 * MINUTE_MS
    const val HANDLING_DELTA = 1.5
    const val MIN_MOVEMENTS = 3
    const val NOISE_MULTIPLIER = 3.0
    const val MIN_MOVEMENT_RMS = 0.015
    const val MIN_ACTIVE_MILLIS = 200L
    const val MAX_SHORT_ACTIVE_MILLIS = 12_000L
    const val MAX_HISTORY_GAP_MILLIS = 2_000L
}
