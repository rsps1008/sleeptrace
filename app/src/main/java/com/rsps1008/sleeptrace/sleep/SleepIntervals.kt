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

data class SleepPart(val start: Long, val end: Long, val awake: Boolean)

fun sleepParts(session: SleepSession): List<SleepPart> = buildList {
    var cursor = session.startMillis
    normalizedAwake(session.startMillis, session.endMillis, session.awakeIntervals).forEach {
        if (it.startMillis > cursor) add(SleepPart(cursor, it.startMillis, false))
        add(SleepPart(it.startMillis, it.endMillis, true))
        cursor = it.endMillis
    }
    if (cursor < session.endMillis) add(SleepPart(cursor, session.endMillis, false))
}
