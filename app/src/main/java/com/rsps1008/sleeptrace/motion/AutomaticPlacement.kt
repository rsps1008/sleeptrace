package com.rsps1008.sleeptrace.motion

import com.rsps1008.sleeptrace.sleep.UsageInterval

/** Retrospective signal-coupling heuristic, not a claim to identify a physical surface reliably. */
object AutomaticPlacement {
    fun resolve(minutes: List<MotionMinute>, usage: List<UsageInterval>): List<MotionMinute> {
        val ordered = minutes.sortedBy { it.startMillis }
        val result = mutableListOf<MotionMinute>()
        val run = mutableListOf<MotionMinute>()
        fun flush() {
            run.forEachIndexed { index, minute ->
                val nearby = run.subList(maxOf(0, index - 30), minOf(run.size, index + 31))
                // Use the lower tail of apparently still minutes as a local noise-floor estimate.
                // These conservative relative thresholds remain engineering heuristics, not calibration.
                val stillRms = nearby.asSequence()
                    .filter { it.level == MotionLevel.QUIET && it.activeMillis == 0L && it.rms.isFinite() && it.rms >= 0.0 }
                    .map { it.rms }
                    .sorted()
                    .toList()
                val noiseFloor = stillRms.getOrNull(stillRms.size / 5) ?: 0.0
                val movementThreshold = maxOf(0.015, noiseFloor * 3.0)
                val stillThreshold = maxOf(0.008, noiseFloor * 1.5)
                // Several separated, brief movements suggest coupling to a mattress.
                // A single pickup/vibration, missing data, or an illuminated/used phone do not.
                val movements = nearby.filter {
                    it.rms >= movementThreshold && it.rms <= 1.5 && it.activeMillis in 200L..12_000L
                }
                val movementSpan = if (movements.isEmpty()) 0 else movements.last().startMillis - movements.first().startMillis
                val surface = when {
                    nearby.size >= 20 && movements.size >= 3 && movementSpan >= 8 * MINUTE_MS -> Placement.BED
                    nearby.size >= 25 && nearby.all { it.rms <= stillThreshold && it.activeMillis == 0L } -> Placement.BEDSIDE
                    else -> Placement.UNKNOWN
                }
                result += minute.copy(placement = surface)
            }
            run.clear()
        }
        ordered.forEach { minute ->
            val inUse = usage.any { it.startMillis < minute.startMillis + MINUTE_MS + 2 * MINUTE_MS && it.endMillis > minute.startMillis - 2 * MINUTE_MS }
            val unreliable = minute.level == MotionLevel.UNKNOWN || inUse || minute.rms > 1.5
            if (minute.placement != Placement.AUTO || unreliable) {
                flush()
                result += if (minute.placement == Placement.AUTO) minute.copy(placement = Placement.UNKNOWN) else minute
            } else {
                if (run.lastOrNull()?.let { it.startMillis + MINUTE_MS != minute.startMillis } == true) flush()
                run += minute
            }
        }
        flush()
        return result
    }
}
