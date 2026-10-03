package com.rsps1008.sleeptrace.sleep

/**
 * Converts Google sleep signals into the user-facing sleep/awake timeline.
 *
 * SleepSegmentEvent is delivered after the fact and may split one night into several sleep
 * segments. Nearby segments are kept in one session and the gap is represented as Awake.
 * Classification lows add finer-grained Awake evidence, while a low-before-high sequence keeps
 * pre-sleep device use outside the accepted sleep span without requiring Usage Access.
 */
object SleepApiTimeline {
    private const val MINUTE = 60_000L
    private const val MAX_SAME_NIGHT_GAP = 2 * 60 * MINUTE
    private const val SLEEP_CONFIDENCE = 80
    private const val AWAKE_CONFIDENCE = 20

    data class Span(
        val startMillis: Long,
        val endMillis: Long,
        val confidence: Int,
        val segmentGaps: List<UsageInterval>
    )

    fun spans(segments: List<SleepSegment>, window: SleepWindow): List<Span> {
        val clipped = segments.mapNotNull { segment ->
            val start = maxOf(segment.startMillis, window.startMillis)
            val end = minOf(segment.endMillis, window.endMillis)
            if (end <= start) null else segment.copy(startMillis = start, endMillis = end)
        }.sortedBy { it.startMillis }
        if (clipped.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<SleepSegment>>()
        clipped.forEach { segment ->
            val current = groups.lastOrNull()
            if (current != null && segment.startMillis - current.maxOf { it.endMillis } <= MAX_SAME_NIGHT_GAP) {
                current += segment
            } else {
                groups += mutableListOf(segment)
            }
        }
        return groups.map { group ->
            val ordered = group.sortedBy { it.startMillis }
            val gaps = buildList {
                var coveredUntil = ordered.first().endMillis
                ordered.drop(1).forEach { segment ->
                    if (segment.startMillis > coveredUntil) add(UsageInterval(coveredUntil, segment.startMillis))
                    coveredUntil = maxOf(coveredUntil, segment.endMillis)
                }
            }
            Span(
                startMillis = ordered.first().startMillis,
                endMillis = ordered.maxOf { it.endMillis },
                confidence = ordered.maxOf { it.confidence },
                segmentGaps = gaps
            )
        }
    }

    fun adjustedStart(span: Span, classifications: List<ClassificationSample>): Long {
        val samples = classifications.filter { it.timeMillis in span.startMillis until span.endMillis }
            .distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        val firstSleeping = samples.firstOrNull { it.confidence >= SLEEP_CONFIDENCE } ?: return span.startMillis
        return if (samples.any { it.timeMillis < firstSleeping.timeMillis && it.confidence <= AWAKE_CONFIDENCE }) {
            firstSleeping.timeMillis.coerceAtMost(span.endMillis)
        } else span.startMillis
    }

    fun awakeIntervals(
        startMillis: Long,
        endMillis: Long,
        segmentGaps: List<UsageInterval>,
        classifications: List<ClassificationSample>
    ): List<UsageInterval> {
        val samples = classifications.filter { it.timeMillis in startMillis until endMillis }
            .distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        val classifiedAwake = samples.mapIndexedNotNull { index, sample ->
            if (sample.confidence > AWAKE_CONFIDENCE) return@mapIndexedNotNull null
            val previous = samples.getOrNull(index - 1)?.timeMillis ?: startMillis
            val next = samples.getOrNull(index + 1)?.timeMillis ?: endMillis
            val left = if (index == 0) startMillis else previous + (sample.timeMillis - previous) / 2
            val right = if (index == samples.lastIndex) endMillis else sample.timeMillis + (next - sample.timeMillis) / 2
            UsageInterval(left, right)
        }
        return normalizedAwake(startMillis, endMillis, segmentGaps + classifiedAwake)
    }
}
