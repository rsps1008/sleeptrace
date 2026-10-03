package com.rsps1008.sleeptrace.sleep

/** Pure analyzer: the phone being idle alone never creates a session. */
object SleepAnalyzer {
    const val MINIMUM_SLEEP_MILLIS = 30 * 60 * 1000L

    /** Analyzes each completed window using Sleep API segments/classifications as sleep evidence. */
    fun analyzeByWindow(
        segments: List<SleepSegment>,
        classifications: List<ClassificationSample>,
        phoneUse: List<UsageInterval>,
        schedule: SleepSchedule,
        windows: List<SleepWindow>,
        @Suppress("UNUSED_PARAMETER") usageAvailable: (SleepWindow) -> Boolean = { true }
    ): List<SleepSession> = windows.flatMap { window ->
        val windowExternalAwake = phoneUse.filter {
            it.endMillis > window.startMillis && it.startMillis < window.endMillis
        }
        SleepApiTimeline.spans(segments, window).mapNotNull { span ->
            val start = SleepApiTimeline.adjustedStart(span, classifications)
            if (span.endMillis - start < MINIMUM_SLEEP_MILLIS) return@mapNotNull null
            val apiAwake = SleepApiTimeline.awakeIntervals(start, span.endMillis, span.segmentGaps, classifications)
            buildSession(
                SleepSegment(start, span.endMillis, span.confidence),
                classifications,
                normalizedAwake(start, span.endMillis, windowExternalAwake + apiAwake)
            ).takeIf { it.durationMillis >= MINIMUM_SLEEP_MILLIS }
        }
    }

    fun analyze(
        segments: List<SleepSegment>,
        classifications: List<ClassificationSample>,
        phoneUse: List<UsageInterval>,
        schedule: SleepSchedule,
        @Suppress("UNUSED_PARAMETER") usageAvailable: Boolean = true
    ): List<SleepSession> {
        val start = segments.minOfOrNull { it.startMillis } ?: return emptyList()
        val end = segments.maxOfOrNull { it.endMillis } ?: return emptyList()
        return analyzeByWindow(
            segments, classifications, phoneUse, schedule,
            schedule.windowsBetween(start, end), { true }
        )
    }

    private fun buildSession(
        segment: SleepSegment,
        classifications: List<ClassificationSample>,
        awakeEvidence: List<UsageInterval>
    ): SleepSession {
        val samples = classifications.filter { it.timeMillis in segment.startMillis..segment.endMillis }
            .sortedBy { it.timeMillis }
        val awakeIntervals = normalizedAwake(segment.startMillis, segment.endMillis, awakeEvidence)
        val awake = awakeIntervals.sumOf { it.endMillis - it.startMillis }
        val rawDuration = segment.endMillis - segment.startMillis
        val covered = normalizedAwake(segment.startMillis, segment.endMillis,
            samples.map { UsageInterval(it.timeMillis - 5 * 60_000, it.timeMillis + 5 * 60_000) })
            .sumOf { it.endMillis - it.startMillis }
        val coverage = covered.toDouble() / rawDuration
        val highConfidence = samples.count { it.confidence >= 80 }
        val highRatio = if (samples.isEmpty()) 0.0 else highConfidence.toDouble() / samples.size
        val score = ((segment.confidence * 0.45) + (highRatio * 100 * 0.35) + (coverage * 100 * 0.20)).toInt()
        val reason = when {
            awake > 0 -> "Sleep API 顯示中途清醒 ${awake / 60000} 分鐘"
            samples.isEmpty() -> "分類樣本不足，App 採用 Sleep API 睡眠區段"
            coverage < 0.8 -> "分類資料有中斷，App 依可用資料推估"
            score < 80 -> "App 已採用目前最佳推估，參考分數較低"
            else -> "Sleep API 睡眠區段與分類一致"
        }
        return SleepSession(
            startMillis = segment.startMillis,
            endMillis = segment.endMillis,
            confidence = score,
            awakeMillis = awake,
            state = SyncState.PENDING,
            reason = reason,
            awakeIntervals = awakeIntervals,
            usageSnapshotApplied = true
        )
    }

}
