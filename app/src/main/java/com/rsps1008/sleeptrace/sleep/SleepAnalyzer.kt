package com.rsps1008.sleeptrace.sleep

/** Pure analyzer: the phone being idle alone never creates a session. */
object SleepAnalyzer {
    const val MINIMUM_SLEEP_MILLIS = 30 * 60 * 1000L
    private const val SLEEP_CONFIDENCE = 80
    private const val CLASSIFICATION_TAIL_MILLIS = 20 * 60 * 1000L

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

    /**
     * Fallback for completed windows where Google has not delivered a SleepSegmentEvent.
     * A schedule or an idle phone never creates sleep: at least one high-confidence classify
     * event is required. The final high event receives only one classify freshness window,
     * unless wake evidence already closed the effective observation window earlier.
     */
    fun analyzeClassificationsByWindow(
        classifications: List<ClassificationSample>,
        windows: List<SleepWindow>
    ): List<SleepSession> = windows.mapNotNull { window ->
        val samples = classifications.asSequence()
            .filter { it.timeMillis >= window.startMillis && it.timeMillis < window.endMillis }
            .distinctBy { it.timeMillis }
            .sortedBy { it.timeMillis }
            .toList()
        val firstHigh = samples.firstOrNull { it.confidence >= SLEEP_CONFIDENCE } ?: return@mapNotNull null
        val lastHigh = samples.lastOrNull { it.confidence >= SLEEP_CONFIDENCE } ?: return@mapNotNull null
        val end = minOf(window.endMillis, lastHigh.timeMillis + CLASSIFICATION_TAIL_MILLIS)
        if (end <= firstHigh.timeMillis) return@mapNotNull null
        val awakeIntervals = SleepApiTimeline.awakeIntervals(
            firstHigh.timeMillis, end, emptyList(), samples
        )
        val awake = awakeIntervals.sumOf { it.endMillis - it.startMillis }
        if (end - firstHigh.timeMillis - awake < MINIMUM_SLEEP_MILLIS) return@mapNotNull null
        val inSpan = samples.filter { it.timeMillis in firstHigh.timeMillis until end }
        val highRatio = inSpan.count { it.confidence >= SLEEP_CONFIDENCE }.toDouble() /
            inSpan.size.coerceAtLeast(1)
        SleepSession(
            id = "classify-${window.startMillis}-${firstHigh.timeMillis}-${end}",
            startMillis = firstHigh.timeMillis,
            endMillis = end,
            confidence = (50 + highRatio * 30).toInt().coerceIn(50, 80),
            awakeMillis = awake,
            state = SyncState.PENDING,
            reason = "尚未收到 Sleep API 睡眠區段，依睡眠分類事件完成昨晚紀錄",
            awakeIntervals = awakeIntervals,
            usageSnapshotApplied = true
        )
    }

    /**
     * Saver mode deliberately has no motion evidence to fall back on.  When Google Play
     * services supplies no usable sleep candidate at all, preserve the user's requested
     * nightly schedule as an explicitly low-confidence estimate instead of leaving a
     * completed night blank.  This is never used by the stages mode and is replaced on a
     * later reconciliation if Sleep API evidence arrives.
     */
    fun scheduledEstimateByWindow(
        windows: List<SleepWindow>,
        classifications: List<ClassificationSample>
    ): List<SleepSession> = windows.mapNotNull { window ->
        val duration = window.endMillis - window.startMillis
        // A 24-hour schedule is an observation setting, not an assertion of 24 hours asleep.
        if (duration < MINIMUM_SLEEP_MILLIS || duration >= 16 * 60 * 60 * 1000L) return@mapNotNull null
        val wake = classifications.asSequence()
            .filter { it.confidence <= 20 && it.timeMillis >= window.endMillis &&
                it.timeMillis <= window.endMillis + SleepSchedule.SAVER_WAKE_CLASSIFICATION_GRACE_MILLIS }
            .minByOrNull { it.timeMillis }
        val end = wake?.timeMillis ?: window.endMillis
        val awake = SleepApiTimeline.awakeIntervals(
            window.startMillis, end, emptyList(), classifications.filter {
                it.timeMillis in window.startMillis until end
            }
        )
        SleepSession(
            id = "saver-schedule-${window.startMillis}-${window.endMillis}",
            startMillis = window.startMillis,
            endMillis = end,
            confidence = 0,
            awakeMillis = awake.sumOf { it.endMillis - it.startMillis },
            state = SyncState.PENDING,
            reason = if (wake == null) "省電模式粗略排程估計；未收到可用的 Google 睡眠訊號"
                else "省電模式粗略排程估計；Sleep API 在起床後回報清醒",
            awakeIntervals = awake,
            usageSnapshotApplied = true
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
