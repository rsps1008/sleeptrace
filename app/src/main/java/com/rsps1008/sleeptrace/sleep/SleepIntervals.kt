package com.rsps1008.sleeptrace.sleep

/** Clip and union phone-use intervals so local duration and uploaded stages agree. */
fun normalizedAwake(start: Long, end: Long, input: List<UsageInterval>): List<UsageInterval> {
    val result = mutableListOf<UsageInterval>()
    input.mapNotNull {
        val left = maxOf(start, it.startMillis)
        val right = minOf(end, it.endMillis)
        if (right > left) UsageInterval(left, right) else null
    }.sortedBy { it.startMillis }.forEach { interval ->
        val last = result.lastOrNull()
        if (last != null && interval.startMillis <= last.endMillis)
            result[result.lastIndex] = last.copy(endMillis = maxOf(last.endMillis, interval.endMillis))
        else result += interval
    }
    return result
}

/** SLEEPING is retained only to decode legacy stored rows; effective rule-9 output normalizes it to Light. */
enum class SleepStage { AWAKE, LIGHT, DEEP, SLEEPING }

data class SleepPart(val start: Long, val end: Long, val stage: SleepStage) {
    val awake: Boolean get() = stage == SleepStage.AWAKE
}

fun sleepParts(session: SleepSession): List<SleepPart> {
    if (session.endMillis <= session.startMillis) return emptyList()
    val stageIntervals = session.stageIntervals.filter { it.endMillis > it.startMillis }
    val awakeIntervals = normalizedAwake(session.startMillis, session.endMillis, session.awakeIntervals)
    val boundaries = buildSet {
        add(session.startMillis)
        add(session.endMillis)
        stageIntervals.forEach {
            if (it.endMillis > session.startMillis && it.startMillis < session.endMillis) {
                add(it.startMillis.coerceIn(session.startMillis, session.endMillis))
                add(it.endMillis.coerceIn(session.startMillis, session.endMillis))
            }
        }
        awakeIntervals.forEach { add(it.startMillis); add(it.endMillis) }
    }.sorted()

    val parts = mutableListOf<SleepPart>()
    boundaries.zipWithNext().forEach { (start, end) ->
        if (end <= start) return@forEach
        val awake = awakeIntervals.any { it.startMillis <= start && it.endMillis >= end }
        val matching = stageIntervals.filter { it.startMillis <= start && it.endMillis >= end }.map { it.stage }.distinct()
        // Actual Awake evidence always wins. Missing, contradictory and legacy generic-sleep
        // labels are accepted-session fallback Light, not a fourth user-facing stage.
        val selected = matching.singleOrNull()
        val stage = if (awake || SleepStage.AWAKE in matching) SleepStage.AWAKE else when (selected) {
            SleepStage.DEEP -> SleepStage.DEEP
            SleepStage.LIGHT, SleepStage.SLEEPING, null -> SleepStage.LIGHT
            SleepStage.AWAKE -> SleepStage.AWAKE
        }
        val previous = parts.lastOrNull()
        if (previous != null && previous.end == start && previous.stage == stage) {
            parts[parts.lastIndex] = previous.copy(end = end)
        } else {
            parts += SleepPart(start, end, stage)
        }
    }
    return parts
}

data class StageDurations(val deep: Long, val light: Long, val sleeping: Long, val awake: Long) {
    val sleep: Long get() = deep + light + sleeping
    val span: Long get() = sleep + awake
}

fun stageDurations(session: SleepSession): StageDurations {
    val totals = sleepParts(session).groupBy { it.stage }.mapValues { (_, parts) -> parts.sumOf { it.end - it.start } }
    return StageDurations(totals[SleepStage.DEEP] ?: 0, totals[SleepStage.LIGHT] ?: 0,
        totals[SleepStage.SLEEPING] ?: 0, totals[SleepStage.AWAKE] ?: 0)
}
