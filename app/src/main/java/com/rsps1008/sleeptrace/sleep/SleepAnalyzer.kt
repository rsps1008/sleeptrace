package com.rsps1008.sleeptrace.sleep

/** Pure analyzer: the phone being idle alone never creates a session. */
object SleepAnalyzer {
    const val MINIMUM_SLEEP_MILLIS = 30 * 60 * 1000L

    fun analyze(
        segments: List<SleepSegment>,
        classifications: List<ClassificationSample>,
        phoneUse: List<UsageInterval>,
        schedule: SleepSchedule
    ): List<SleepSession> = segments
        .filter { it.endMillis > it.startMillis && it.endMillis - it.startMillis >= MINIMUM_SLEEP_MILLIS }
        .flatMap { segment -> schedule.intersections(segment.startMillis, segment.endMillis).map { window -> segment.copy(startMillis = window.startMillis, endMillis = window.endMillis) } }
        .filter { it.endMillis - it.startMillis >= MINIMUM_SLEEP_MILLIS }
        .map { segment -> buildSession(segment, classifications, phoneUse) }
        .filter { it.durationMillis >= MINIMUM_SLEEP_MILLIS }

    private fun buildSession(
        segment: SleepSegment,
        classifications: List<ClassificationSample>,
        phoneUse: List<UsageInterval>
    ): SleepSession {
        val samples = classifications.filter { it.timeMillis in segment.startMillis..segment.endMillis }
            .sortedBy { it.timeMillis }
        val awakeIntervals = normalizedAwake(segment.startMillis, segment.endMillis, phoneUse)
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
            awake > 0 -> "已扣除夜間手機使用 ${awake / 60000} 分鐘"
            samples.isEmpty() -> "分類樣本不足，App 採用 Sleep API 睡眠區段"
            coverage < 0.8 -> "分類資料有中斷，App 依可用資料推估"
            score < 80 -> "App 已採用目前最佳推估，參考分數較低"
            else -> "Sleep API 與使用紀錄一致"
        }
        return SleepSession(
            startMillis = segment.startMillis,
            endMillis = segment.endMillis,
            confidence = score,
            awakeMillis = awake,
            state = SyncState.PENDING,
            reason = reason,
            awakeIntervals = awakeIntervals
        )
    }

}
