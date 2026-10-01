package com.rsps1008.sleeptrace.motion

import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.SleepSchedule

/** Causal coupling evidence with bounded memory. It never establishes sleep. */
object AutomaticPlacement {
    private data class WindowEvaluation(
        val stats: CouplingWindowStats,
        val movements: List<MotionMinute>,
        val established: Boolean
    )

    private fun evaluateWindow(
        history: List<MotionMinute>,
        minimumHistoryMinutes: Int,
        minimumSpanMillis: Long
    ): WindowEvaluation {
        val still = history.filter { it.activeMillis == 0L && it.rms.isFinite() }
            .map { it.rms }
            .sorted()
        val noise = still.getOrNull(still.size / 5) ?: 0.0
        val movementThreshold = maxOf(
            CouplingPolicy.MIN_MOVEMENT_RMS,
            noise * CouplingPolicy.NOISE_MULTIPLIER
        )
        val movements = history.filter {
            it.rms >= movementThreshold && it.rms <= CouplingPolicy.HANDLING_DELTA &&
                it.activeMillis in CouplingPolicy.MIN_ACTIVE_MILLIS..CouplingPolicy.MAX_SHORT_ACTIVE_MILLIS &&
                (it.longestActiveMillis ?: 0) <= CouplingPolicy.MAX_SHORT_ACTIVE_MILLIS
        }
        val span = movements.takeIf { it.isNotEmpty() }
            ?.let { it.last().startMillis - it.first().startMillis }
        val stats = CouplingWindowStats(history.size, movements.size, span)
        return WindowEvaluation(
            stats,
            movements,
            history.size >= minimumHistoryMinutes &&
                movements.size >= CouplingPolicy.MIN_MOVEMENTS &&
                (span ?: 0L) >= minimumSpanMillis
        )
    }

    private fun reasons(vararg groups: List<String>): String? = groups.asSequence()
        .flatten()
        .distinct()
        .joinToString("|")
        .ifEmpty { null }

    fun resolve(minutes: List<MotionMinute>, usage: List<UsageInterval>, schedule: SleepSchedule? = null): List<MotionMinute> {
        val history = mutableListOf<MotionMinute>()
        // `supportedAt` is deliberately the *last positive movement evidence*, not
        // the time at which a quiet minute happened to be processed.  Initial
        // establishment and a renewal have different proof requirements.
        var supportedAt: Long? = null
        var supportBasis: CouplingBasis? = null
        var previous: MotionMinute? = null
        var lastResetReason: String? = null

        fun reset(reason: String) {
            history.clear()
            supportedAt = null
            supportBasis = null
            lastResetReason = reason
        }

        return minutes.sortedBy { it.startMillis }.map { minute ->
            val prior = previous
            val inUse = usage.any { it.startMillis < minute.startMillis + MINUTE_MS + CouplingPolicy.PHONE_MARGIN_MILLIS &&
                it.endMillis > minute.startMillis - CouplingPolicy.PHONE_MARGIN_MILLIS }
            val boundaryReasons = if (prior == null) emptyList() else buildList {
                if (prior.startMillis + MINUTE_MS != minute.startMillis) add("TIMELINE_GAP")
                if (prior.featureVersion != minute.featureVersion) add("FEATURE_BOUNDARY")
                if (prior.recordingId != minute.recordingId) add("RECORDING_BOUNDARY")
                if (schedule != null && schedule.windowAt(prior.startMillis) != schedule.windowAt(minute.startMillis)) {
                    add("SCHEDULE_BOUNDARY")
                }
            }
            val handling = minute.rms > CouplingPolicy.HANDLING_DELTA ||
                (minute.maxDelta ?: 0.0) > CouplingPolicy.HANDLING_DELTA ||
                (minute.postureDelta ?: 0.0) > CouplingPolicy.HANDLING_DELTA
            val invalidReasons = buildList {
                if (inUse) add("PHONE_IN_USE")
                if (handling) add("HANDLING")
                if (minute.level == MotionLevel.UNKNOWN || (minute.longestGapMillis ?: 0) > CouplingPolicy.MAX_HISTORY_GAP_MILLIS) add("MISSING_MOTION")
            }
            // A boundary first discards prior evidence. It must not, however,
            // hide handling/missing data on the first minute of the new run.
            val currentBlocker = reasons(
                boundaryReasons,
                invalidReasons
            )
            currentBlocker?.let(::reset)
            previous = minute
            if (minute.placement != Placement.AUTO) {
                reset(currentBlocker ?: "LEGACY_PLACEMENT")
                // Preserve old explicit placement as a limited compatibility path, never propagate it.
                minute.copy(coupling = CouplingEvidence(
                    if (minute.placement == Placement.BED && invalidReasons.isEmpty())
                        CouplingState.SUPPORTED else CouplingState.INSUFFICIENT,
                    null,
                    "LEGACY_PLACEMENT",
                    currentBlocker = currentBlocker,
                    lastResetReason = lastResetReason
                ))
            } else if (invalidReasons.isNotEmpty()) {
                minute.copy(placement = Placement.UNKNOWN, coupling = CouplingEvidence(
                    CouplingState.INSUFFICIENT,
                    null,
                    currentBlocker,
                    currentBlocker = currentBlocker,
                    lastResetReason = lastResetReason
                ))
            } else {
                var effectiveCurrentBlocker = currentBlocker
                val expired = supportedAt?.let { minute.startMillis - it > CouplingPolicy.HOLD_MILLIS } == true
                if (expired) {
                    // Long-window evidence must not let old, already-consumed
                    // movements plus one later movement resurrect an expired proof.
                    reset("COUPLING_EXPIRED")
                    effectiveCurrentBlocker = reasons(
                        listOfNotNull(currentBlocker),
                        listOf("COUPLING_EXPIRED")
                    )
                }

                // Boundary and expiry reset prior evidence, but a valid current
                // minute begins the next causal history immediately.
                history += minute
                history.removeAll {
                    it.startMillis < minute.startMillis - CouplingPolicy.SPARSE_EVIDENCE_WINDOW_MILLIS
                }

                val fastHistory = history.filter {
                    it.startMillis >= minute.startMillis - CouplingPolicy.FAST_EVIDENCE_WINDOW_MILLIS
                }
                val fast = evaluateWindow(
                    fastHistory,
                    CouplingPolicy.FAST_MIN_HISTORY_MINUTES,
                    CouplingPolicy.FAST_MIN_SPAN_MILLIS
                )
                val sparse = evaluateWindow(
                    history,
                    CouplingPolicy.SPARSE_MIN_HISTORY_MINUTES,
                    CouplingPolicy.SPARSE_MIN_SPAN_MILLIS
                )
                val newQualifyingMovement = (fast.movements + sparse.movements)
                    .any { it.startMillis == minute.startMillis }
                val supportStillValid = supportedAt?.let {
                    minute.startMillis - it <= CouplingPolicy.HOLD_MILLIS
                } == true

                // Initial proof still needs three movements. The sparse route
                // changes their time distribution, never their count. Once a
                // proof is alive, one newly observed qualifying movement renews
                // it; quiet time can never renew it.
                if (supportStillValid && newQualifyingMovement) {
                    supportedAt = minute.startMillis
                } else if (supportedAt == null) {
                    val establishment = when {
                        fast.established -> CouplingBasis.FAST to fast.movements.last()
                        sparse.established -> CouplingBasis.SPARSE to sparse.movements.last()
                        else -> null
                    }?.takeIf { (_, lastMovement) ->
                        minute.startMillis - lastMovement.startMillis <= CouplingPolicy.HOLD_MILLIS
                    }
                    if (establishment != null) {
                        supportBasis = establishment.first
                        supportedAt = establishment.second.startMillis
                    }
                }

                val age = supportedAt?.let { minute.startMillis - it }
                val state = when {
                    age == null -> CouplingState.INSUFFICIENT
                    age > CouplingPolicy.HOLD_MILLIS -> CouplingState.INSUFFICIENT
                    age == 0L -> CouplingState.SUPPORTED
                    else -> CouplingState.HELD
                }
                val reason = if (state == CouplingState.INSUFFICIENT)
                    effectiveCurrentBlocker ?: "COUPLING_INSUFFICIENT" else null
                minute.copy(
                    placement = if (state == CouplingState.INSUFFICIENT) Placement.UNKNOWN else Placement.BED,
                    coupling = CouplingEvidence(
                        state,
                        age,
                        reason,
                        basis = supportBasis,
                        currentBlocker = effectiveCurrentBlocker,
                        lastResetReason = lastResetReason,
                        fastStats = fast.stats,
                        sparseStats = sparse.stats
                    )
                )
            }
        }
    }
}
