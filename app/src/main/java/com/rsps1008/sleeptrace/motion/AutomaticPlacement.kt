package com.rsps1008.sleeptrace.motion

import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.SleepSchedule

/** Causal coupling evidence with bounded memory. It never establishes sleep. */
object AutomaticPlacement {
    fun resolve(minutes: List<MotionMinute>, usage: List<UsageInterval>, schedule: SleepSchedule? = null): List<MotionMinute> {
        val history = mutableListOf<MotionMinute>()
        // `supportedAt` is deliberately the *last positive movement evidence*, not
        // the time at which a quiet minute happened to be processed.  Initial
        // establishment and a renewal have different proof requirements.
        var supportedAt: Long? = null
        var previous: MotionMinute? = null
        var invalidation: String? = null
        return minutes.sortedBy { it.startMillis }.map { minute ->
            val prior = previous
            val inUse = usage.any { it.startMillis < minute.startMillis + MINUTE_MS + CouplingPolicy.PHONE_MARGIN_MILLIS &&
                it.endMillis > minute.startMillis - CouplingPolicy.PHONE_MARGIN_MILLIS }
            val boundary = prior != null && (prior.startMillis + MINUTE_MS != minute.startMillis ||
                prior.featureVersion != minute.featureVersion || prior.recordingId != minute.recordingId ||
                (schedule != null && schedule.windowAt(prior.startMillis) != schedule.windowAt(minute.startMillis)))
            val handling = minute.rms > CouplingPolicy.HANDLING_DELTA ||
                (minute.maxDelta ?: 0.0) > CouplingPolicy.HANDLING_DELTA ||
                (minute.postureDelta ?: 0.0) > CouplingPolicy.HANDLING_DELTA
            // A boundary first discards prior evidence.  It must not, however,
            // hide handling/missing data on the first minute of the new run.
            if (boundary) { history.clear(); supportedAt = null; invalidation = "RECORDING_BOUNDARY" }
            val invalidReasons = buildList {
                if (inUse) add("PHONE_IN_USE")
                if (handling) add("HANDLING")
                if (minute.level == MotionLevel.UNKNOWN || (minute.longestGapMillis ?: 0) > CouplingPolicy.MAX_HISTORY_GAP_MILLIS) add("MISSING_MOTION")
            }
            val invalid = invalidReasons.joinToString("|").ifEmpty { null }
            if (invalid != null) { history.clear(); supportedAt = null; invalidation = invalid }
            previous = minute
            if (minute.placement != Placement.AUTO) {
                history.clear(); supportedAt = null
                // Preserve old explicit placement as a limited compatibility path, never propagate it.
                minute.copy(coupling = CouplingEvidence(if (minute.placement == Placement.BED && invalid == null)
                    CouplingState.SUPPORTED else CouplingState.INSUFFICIENT, null, "LEGACY_PLACEMENT"))
            } else if (invalid != null) {
                minute.copy(placement = Placement.UNKNOWN, coupling = CouplingEvidence(CouplingState.INSUFFICIENT, null, invalid))
            } else {
                history += minute
                history.removeAll { it.startMillis < minute.startMillis - CouplingPolicy.EVIDENCE_WINDOW_MILLIS }
                val still = history.filter { it.activeMillis == 0L && it.rms.isFinite() }.map { it.rms }.sorted()
                val noise = still.getOrNull(still.size / 5) ?: 0.0
                val movements = history.filter { it.rms >= maxOf(CouplingPolicy.MIN_MOVEMENT_RMS, noise * CouplingPolicy.NOISE_MULTIPLIER) && it.rms <= CouplingPolicy.HANDLING_DELTA &&
                    it.activeMillis in CouplingPolicy.MIN_ACTIVE_MILLIS..CouplingPolicy.MAX_SHORT_ACTIVE_MILLIS && (it.longestActiveMillis ?: 0) <= CouplingPolicy.MAX_SHORT_ACTIVE_MILLIS }
                val established = history.size >= CouplingPolicy.MIN_HISTORY_MINUTES && movements.size >= CouplingPolicy.MIN_MOVEMENTS &&
                    movements.last().startMillis - movements.first().startMillis >= CouplingPolicy.MIN_SPAN_MILLIS
                val newQualifyingMovement = movements.any { it.startMillis == minute.startMillis }
                val ageBefore = supportedAt?.let { minute.startMillis - it }
                val supportStillValid = ageBefore != null && ageBefore <= CouplingPolicy.HOLD_MILLIS
                if (ageBefore != null && !supportStillValid) {
                    // Expiry intentionally discards the old proof.  A later full
                    // establishment may create fresh support, but a lone movement
                    // cannot revive this timestamp.
                    supportedAt = null
                    invalidation = "COUPLING_EXPIRED"
                }
                // Initial proof still needs the complete three-separated-movement
                // history.  Once that proof is alive, one new qualifying movement
                // renews it; quiet time can never renew it.
                if ((supportedAt == null && established && newQualifyingMovement) ||
                    (supportStillValid && newQualifyingMovement)) {
                    supportedAt = minute.startMillis
                    invalidation = null
                }
                val age = supportedAt?.let { minute.startMillis - it }
                val state = when {
                    age == null -> CouplingState.INSUFFICIENT
                    age > CouplingPolicy.HOLD_MILLIS -> CouplingState.INSUFFICIENT
                    age == 0L -> CouplingState.SUPPORTED
                    else -> CouplingState.HELD
                }
                val reason = if (age != null && age > CouplingPolicy.HOLD_MILLIS) "COUPLING_EXPIRED"
                    else if (state == CouplingState.INSUFFICIENT) invalidation ?: "COUPLING_INSUFFICIENT" else null
                minute.copy(placement = if (state == CouplingState.INSUFFICIENT) Placement.UNKNOWN else Placement.BED,
                    coupling = CouplingEvidence(state, age, reason))
            }
        }
    }
}
