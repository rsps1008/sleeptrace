package com.rsps1008.sleeptrace.sleep

import java.time.Instant
import java.time.ZoneId

internal interface ObservationPersistence {
    fun read(): Map<SleepWindow, ObservationEnd>
    /** Atomic update. False must not be followed by observation notifications. */
    fun commit(updates: Map<SleepWindow, ObservationEnd>, removals: Set<SleepWindow>): Boolean
}

/** Blocking core; invoked only by the IO-confined preferences entrance. */
internal class SleepObservationRepository(
    private val persistence: ObservationPersistence,
    private val recentSamples: (Long) -> List<ClassificationSample>,
    private val markDirty: () -> Unit,
    private val onClosed: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val waitForWakeEvidence: Boolean = false
) {
    internal fun apply(nominal: SleepSchedule): SleepSchedule = synchronized(lock) {
        // Sample time inside the shared lock, after any earlier refresh finished persisting.
        val now = clock()
        val records = persistence.read().toMutableMap()
        val removals = records.keys.filter { window ->
            val date = Instant.ofEpochMilli(window.startMillis).atZone(zone).toLocalDate()
            window.startMillis < now - 14 * DAY ||
                (nominal.isFullDayForStartDate(date) && nominal.windowForStartDate(date, zone) == window)
        }.toSet()
        removals.forEach(records::remove)
        // Saver mode may receive a valid wake classification days late. Re-evaluate every retained
        // historical night individually; normal stages retain the narrow live-window behavior.
        val lookback = if (waitForWakeEvidence) 14 * DAY else DAY
        val windows = nominal.windowsBetween(now - lookback, now + 1, zone).filter { window ->
            val date = Instant.ofEpochMilli(window.startMillis).atZone(zone).toLocalDate()
            !nominal.isFullDayForStartDate(date) &&
                now >= window.startMillis + (window.endMillis - window.startMillis) / 2 &&
                    (records[window]?.closed != true || records[window]?.dataInsufficient == true)
        }
        val samples = if (windows.isEmpty()) emptyList() else recentSamples(windows.minOf { it.startMillis })
        val updates = mutableMapOf<SleepWindow, ObservationEnd>()
        windows.forEach { window ->
            val date = Instant.ofEpochMilli(window.startMillis).atZone(zone).toLocalDate()
            val nextStart = nominal.windowForStartDate(date.plusDays(1), zone).startMillis
            val historical = waitForWakeEvidence && now - window.endMillis > 10 * 60_000L
            val settleEmpty = historical && now >= nextStart
            val result = SleepObservationPolicy.resolve(window, records[window], samples, now, nextStart,
                waitForWakeEvidence, historical, settleEmpty)
            if (result != records[window] && (result.closed || result.endMillis != window.endMillis || waitForWakeEvidence)) {
                updates[window] = result
                records[window] = result
            }
        }
        if (updates.isNotEmpty() || removals.isNotEmpty()) {
            check(persistence.commit(updates, removals)) { "Unable to persist observation window" }
            markDirty()
            // Repairing a polluted all-day record also refreshes capture through its existing flush path.
            if (updates.values.any { it.closed } || removals.any { it.startMillis >= now - 14 * DAY }) onClosed()
        }
        nominal.copy(
            observationEnds = records.mapValues { it.value.endMillis },
            closedObservationWindows = records.filterValues { it.closed }.keys,
            dataInsufficientObservationWindows = records.filterValues { it.dataInsufficient }.keys
        )
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        // Shared across repository/preferences instances, including process-recreation test adapters.
        val lock = Any()
    }
}
