package com.rsps1008.sleeptrace.sleep

/** Receiver ordering seam: the persisted samples must precede effective-window evaluation. */
internal suspend fun processSleepClassifications(
    samples: List<ClassificationSample>,
    appendSamples: (List<ClassificationSample>) -> Unit,
    effectiveSchedule: suspend () -> SleepSchedule?,
    updateControls: (SleepSchedule?) -> Unit
) {
    appendSamples(samples)
    updateControls(effectiveSchedule())
}
