package com.rsps1008.sleeptrace.motion

import com.rsps1008.sleeptrace.sleep.UsageInterval
import com.rsps1008.sleeptrace.sleep.SleepSchedule

/** Causal coupling evidence with bounded memory. It never establishes sleep. */
object AutomaticPlacement {
    fun resolve(minutes: List<MotionMinute>, usage: List<UsageInterval>, schedule: SleepSchedule? = null): List<MotionMinute> {
        val history = mutableListOf<MotionMinute>()
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
            val invalid = when {
                inUse -> "PHONE_IN_USE"
                boundary -> "RECORDING_BOUNDARY"
                handling -> "HANDLING"
                minute.level == MotionLevel.UNKNOWN || (minute.longestGapMillis ?: 0) > CouplingPolicy.MAX_HISTORY_GAP_MILLIS -> "MISSING_MOTION"
                else -> null
            }
            if (invalid != null) { history.clear(); supportedAt = null; invalidation = invalid }
            previous = minute
            if (minute.placement != Placement.AUTO) {
                history.clear(); supportedAt = null
                // Preserve old explicit placement as a limited compatibility path, never propagate it.
                minute.copy(coupling = CouplingEvidence(if (minute.placement == Placement.BED && invalid == null)
                    CouplingState.SUPPORTED else CouplingState.INSUFFICIENT, null, "LEGACY_PLACEMENT"))
            } else if (invalid != null && invalid != "RECORDING_BOUNDARY") {
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
                // Only a new observed movement refreshes an already established coupling.
                if (established && (supportedAt == null || movements.last() == minute)) {
                    supportedAt = movements.last().startMillis; invalidation = null
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
