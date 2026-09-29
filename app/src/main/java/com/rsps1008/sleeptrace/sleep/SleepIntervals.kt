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

/** SLEEPING is retained for old records whose stage was not estimated. */
enum class SleepStage { AWAKE, LIGHT, DEEP, SLEEPING }

data class SleepPart(val start: Long, val end: Long, val stage: SleepStage) {
    val awake: Boolean get() = stage == SleepStage.AWAKE
}

fun sleepParts(session: SleepSession): List<SleepPart> {
    if (session.endMillis <= session.startMillis) return emptyList()
    val stageIntervals = session.stageIntervals
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
        val stage = if (awake) SleepStage.AWAKE else stageIntervals
            .firstOrNull { it.startMillis <= start && it.endMillis >= end }
            ?.stage
            ?: if (stageIntervals.isEmpty()) SleepStage.SLEEPING else SleepStage.LIGHT
        val previous = parts.lastOrNull()
        if (previous != null && previous.end == start && previous.stage == stage) {
            parts[parts.lastIndex] = previous.copy(end = end)
        } else {
            parts += SleepPart(start, end, stage)
        }
    }
    return parts
}
