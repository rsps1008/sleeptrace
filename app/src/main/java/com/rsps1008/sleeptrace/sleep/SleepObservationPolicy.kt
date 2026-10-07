package com.rsps1008.sleeptrace.sleep

/** `dataInsufficient` settles an expired empty night without pretending it has wake evidence. */
data class ObservationEnd(
    val endMillis: Long,
    val closed: Boolean,
    val dataInsufficient: Boolean = false,
    /** A segment can settle provisionally; later same-night segments may extend this end. */
    val segmentSettled: Boolean = false,
    /** Old persisted closures did not distinguish a wake from a segment. */
    val sourceUnknown: Boolean = false
)

/** Uncalibrated engineering thresholds. Silence, motion stillness and missing reports are not sleep. */
object SleepObservationPolicy {
    private const val MINUTE = 60_000L
    const val SLEEP_FRESHNESS = 20 * MINUTE
    const val EXTENSION = 30 * MINUTE
    private const val MAX_WAKE_REPORT_GAP = 15 * MINUTE
    private const val WAKE_CONFIRMATION_SPAN = 5 * MINUTE

    fun resolve(
        window: SleepWindow,
        previous: ObservationEnd?,
        samples: List<ClassificationSample>,
        now: Long,
        nextStart: Long,
        waitForWakeEvidence: Boolean = false,
        historicalWakeEvidence: Boolean = false,
        settleEmptyHistoricalWindow: Boolean = false
    ): ObservationEnd {
        if (previous?.closed == true && !previous.dataInsufficient) return previous
        val end = previous?.endMillis ?: window.endMillis
        val ordered = orderedSamples(window, samples, now, nextStart)
        val firstLow = wakeStart(window, ordered, historicalWakeEvidence)
        val wakeConfirmed = firstLow != null && (historicalWakeEvidence ||
            now - ordered.last { it.timeMillis >= firstLow && it.confidence <= 20 }.timeMillis <= 10 * MINUTE) &&
            (waitForWakeEvidence || ordered.any { it.confidence >= 80 && it.timeMillis <= firstLow - 30 * MINUTE })
        if (wakeConfirmed) return ObservationEnd(if (waitForWakeEvidence) firstLow!! else minOf(end, firstLow!!), true)

        if (settleEmptyHistoricalWindow && ordered.isEmpty()) return ObservationEnd(end, true, dataInsufficient = true)

        // Saver mode does not assume that the configured end is a wake-up. It waits for the
        // passive, consecutive Sleep API wake reports and never schedules a wake-up itself.
        if (waitForWakeEvidence && now >= end) return ObservationEnd(end, false)

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

    /** Saver closures may be rechecked without treating a provisional segment as a final wake. */
    internal fun confirmedSaverWake(
        window: SleepWindow,
        samples: List<ClassificationSample>,
        now: Long,
        nextStart: Long,
        historical: Boolean
    ): Long? {
        val ordered = orderedSamples(window, samples, now, nextStart)
        val firstLow = wakeStart(window, ordered, historical) ?: return null
        val lastLow = ordered.last { it.timeMillis >= firstLow && it.confidence <= 20 }
        return firstLow.takeIf { historical || now - lastLow.timeMillis <= 10 * MINUTE }
    }

    private fun orderedSamples(
        window: SleepWindow, samples: List<ClassificationSample>, now: Long, nextStart: Long
    ) = samples.filter { it.timeMillis >= window.startMillis && it.timeMillis < minOf(now + 1, nextStart) }
        .distinctBy { it.timeMillis }.sortedBy { it.timeMillis }

    private fun wakeStart(window: SleepWindow, ordered: List<ClassificationSample>, historical: Boolean): Long? {
        // Morning only: a brief nocturnal awakening must not close the night's observation.
        val morning = window.startMillis + (window.endMillis - window.startMillis) / 2
        return if (historical) firstHistoricalWake(ordered, morning) else latestWake(ordered, morning)
    }

    /** Historical import is chronological: later daytime reports cannot erase an earlier wake. */
    private fun firstHistoricalWake(ordered: List<ClassificationSample>, morning: Long): Long? {
        val run = mutableListOf<ClassificationSample>()
        ordered.forEach { sample ->
            if (sample.timeMillis < morning) return@forEach
            if (sample.confidence > 20) {
                run.clear()
                return@forEach
            }
            if (run.lastOrNull()?.let { sample.timeMillis - it.timeMillis > MAX_WAKE_REPORT_GAP } == true) run.clear()
            run += sample
            if (run.size >= 2 && sample.timeMillis - run.first().timeMillis >= WAKE_CONFIRMATION_SPAN) {
                return run.first().timeMillis
            }
        }
        return null
    }

    private fun latestWake(ordered: List<ClassificationSample>, morning: Long): Long? {
        val low = ordered.takeLastWhile { it.confidence <= 20 && it.timeMillis >= morning }
            .let { run -> run.drop(run.zipWithNext().indexOfLast { (a, b) -> b.timeMillis - a.timeMillis > MAX_WAKE_REPORT_GAP } + 1) }
        val first = low.firstOrNull() ?: return null
        return first.timeMillis.takeIf { low.size >= 2 && low.last().timeMillis - first.timeMillis >= WAKE_CONFIRMATION_SPAN }
    }
}
