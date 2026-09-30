package com.rsps1008.sleeptrace.sleep

data class ObservationEnd(val endMillis: Long, val closed: Boolean)

/** Uncalibrated engineering thresholds. Silence, motion stillness and missing reports are not sleep. */
object SleepObservationPolicy {
    private const val MINUTE = 60_000L
    const val SLEEP_FRESHNESS = 20 * MINUTE
    const val EXTENSION = 30 * MINUTE

    fun resolve(
        window: SleepWindow,
        previous: ObservationEnd?,
        samples: List<ClassificationSample>,
        now: Long,
        nextStart: Long
    ): ObservationEnd {
        if (previous?.closed == true) return previous
        val end = previous?.endMillis ?: window.endMillis
        val ordered = samples.filter { it.timeMillis >= window.startMillis && it.timeMillis <= now }
            .distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        // Morning only: a brief nocturnal awakening must not close the night's observation.
        val morning = window.startMillis + (window.endMillis - window.startMillis) / 2
        val low = ordered.takeLastWhile { it.confidence <= 20 }
        val firstLow = low.firstOrNull()?.timeMillis
        val wakeConfirmed = low.size >= 3 && firstLow != null && firstLow >= morning &&
            low.last().timeMillis - firstLow >= 20 * MINUTE && now - low.last().timeMillis <= 10 * MINUTE &&
            low.zipWithNext().all { (a, b) -> b.timeMillis - a.timeMillis <= 15 * MINUTE } &&
            ordered.any { it.confidence >= 80 && it.timeMillis <= firstLow - 30 * MINUTE }
        if (wakeConfirmed) return ObservationEnd(minOf(end, firstLow!!), true)

        // Event time inside the window does not excuse a callback arriving after it expired.
        // Equality remains renewable: the boundary alarm may assess current sleep evidence.
        if (now > end) return ObservationEnd(end, true)

        val latest = ordered.lastOrNull()
        val sleeping = latest != null && latest.confidence >= 80 && now - latest.timeMillis <= SLEEP_FRESHNESS &&
            latest.timeMillis <= end
        if (sleeping && now >= window.endMillis - SLEEP_FRESHNESS && now < nextStart) {
            return ObservationEnd(minOf(nextStart, maxOf(end, latest!!.timeMillis + EXTENSION)), false)
        }
        return ObservationEnd(end, now >= end)
    }
}
