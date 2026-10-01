package com.rsps1008.sleeptrace.sleep

import com.rsps1008.sleeptrace.motion.CouplingState
import com.rsps1008.sleeptrace.motion.Placement

/** Separates acquisition, coupling, baseline, and staging failures for user-facing diagnostics. */
data class StagingAvailability(
    val usableFeatureMinutes: Int,
    val couplingSupportedMinutes: Int,
    val baselineSampleCount: Int,
    val stageableMinutes: Int,
    val shortGapMinutes: Int,
    val missingMotionMinutes: Int,
    val blockerCounts: Map<SleepStageEstimator.Reason, Int>,
    val state: State
) {
    enum class State {
        NO_USABLE_MOTION,
        FEATURE_INCOMPATIBLE,
        MOTION_QUALITY_BLOCKED,
        COUPLING_NOT_ESTABLISHED,
        BASELINE_NOT_ESTABLISHED,
        LOW_SIGNAL_DIFFERENTIATION,
        STAGE_WINDOW_BLOCKED,
        AVAILABLE
    }

    private fun blockerSummary(): String {
        val labels = linkedMapOf(
            SleepStageEstimator.Reason.MISSING_MOTION to "沒有動作摘要",
            SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE to "覆蓋或缺口不足",
            SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION to "特徵欄位或版本不相容",
            SleepStageEstimator.Reason.RECORDING_BOUNDARY to "錄製片段邊界",
            SleepStageEstimator.Reason.NO_SLEEP_EVIDENCE to "睡眠證據前或排程外",
            SleepStageEstimator.Reason.PHONE_IN_USE to "已知手機使用",
            SleepStageEstimator.Reason.COUPLING_INSUFFICIENT to "床面動作支持不足",
            SleepStageEstimator.Reason.COUPLING_EXPIRED to "床面動作支持已過期",
            SleepStageEstimator.Reason.COUPLING_INVALIDATED to "床面動作支持被中斷"
        )
        val parts = labels.mapNotNull { (reason, label) ->
            blockerCounts[reason]?.takeIf { it > 0 }?.let { "$label $it 個分鐘列" }
        }
        return if (parts.isEmpty()) "連續分期條件未成立" else parts.joinToString("、")
    }

    fun message(): String = when (state) {
        State.NO_USABLE_MOTION ->
            "沒有足夠的現行動作分鐘通過基本資料檢查；有效睡眠會回退顯示為淺眠，不推估深眠。"
        State.FEATURE_INCOMPATIBLE ->
            (if (usableFeatureMinutes > 0) "已有 $usableFeatureMinutes 分鐘通過覆蓋檢查，"
            else "已有動作摘要，") +
                "但分期需要的特徵欄位或版本不相容；有效睡眠回退淺眠，主要阻擋：${blockerSummary()}。"
        State.MOTION_QUALITY_BLOCKED ->
            "已有 $usableFeatureMinutes 分鐘現行特徵資料，但沒有分鐘通過連續品質檢查；有效睡眠回退淺眠，主要阻擋：${blockerSummary()}。"
        State.COUPLING_NOT_ESTABLISHED ->
            "已有 $usableFeatureMinutes 分鐘通過基本資料檢查，但沒有建立床面動作支持；有效睡眠回退淺眠，不推估深眠。"
        State.BASELINE_NOT_ESTABLISHED ->
            "已有 $usableFeatureMinutes 分鐘有效資料、$couplingSupportedMinutes 分鐘床面動作支持，但分期基準樣本只有 $baselineSampleCount 分鐘；其餘有效睡眠回退淺眠，主要阻擋：${blockerSummary()}。"
        State.LOW_SIGNAL_DIFFERENTIATION ->
            "已建立 $baselineSampleCount 分鐘分期基準，但整晚訊號差異太小；有效睡眠回退淺眠，不推估深眠。"
        State.STAGE_WINDOW_BLOCKED ->
            "已建立 $baselineSampleCount 分鐘分期基準，但沒有完整的可分期窗口；有效睡眠回退淺眠，主要阻擋：${blockerSummary()}。"
        State.AVAILABLE ->
            "依目前規則唯讀分析已有 $stageableMinutes 分鐘可分期；已保存結果可能來自舊規則，這次檢查不會改寫紀錄。"
    }
}

fun stagingAvailability(result: SleepStageEstimator.StagingResult): StagingAvailability {
    val acquisitionBlockers = setOf(
        SleepStageEstimator.Reason.MISSING_MOTION,
        SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE,
        SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION,
        SleepStageEstimator.Reason.RECORDING_BOUNDARY
    )
    // canStage is a per-minute acquisition/coupling/baseline capability. Deep-entry
    // window blockers are hypothetical when entryDecision is not applicable and
    // must not be relabeled as acquisition failures.
    fun SleepStageEstimator.MinuteDiagnostic.reasonsForAvailability() =
        currentEligibilityReasons.distinct()
    val featureCompatibleMinutes = result.minutes.count { minute ->
        val reasons = minute.reasonsForAvailability()
        SleepStageEstimator.Reason.MISSING_MOTION !in reasons &&
            SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION !in reasons
    }
    val qualityEligibleMinutes = result.minutes.count { minute ->
        minute.reasonsForAvailability().none { it in acquisitionBlockers }
    }
    val couplingSupported = result.minutes.count { minute ->
        minute.reasonsForAvailability().none { it in acquisitionBlockers } &&
            minute.motion?.let { motion ->
                motion.coupling?.state?.let { it != CouplingState.INSUFFICIENT }
                    ?: (motion.placement == Placement.BED)
            } == true
    }
    val stageable = result.minutes.count { it.canStage }
    val shortGaps = result.minutes.count { minute ->
        val gap = minute.motion?.longestGapMillis ?: 0L
        gap in 1..2_000L && SleepStageEstimator.Reason.INSUFFICIENT_COVERAGE in minute.currentEligibilityReasons
    }
    val missing = result.minutes.count { SleepStageEstimator.Reason.MISSING_MOTION in it.currentEligibilityReasons }
    val blockerCounts = result.minutes.asSequence()
        .filterNot { it.canStage }
        .flatMap { it.reasonsForAvailability().asSequence() }
        .groupingBy { it }
        .eachCount()
    val featureLimited = blockerCounts[SleepStageEstimator.Reason.LEGACY_FEATURE_LIMITATION] ?: 0
    val state = when {
        stageable > 0 -> StagingAvailability.State.AVAILABLE
        featureCompatibleMinutes == 0 && featureLimited > 0 -> StagingAvailability.State.FEATURE_INCOMPATIBLE
        result.currentFeatureValidMinutes == 0 -> StagingAvailability.State.NO_USABLE_MOTION
        qualityEligibleMinutes == 0 -> StagingAvailability.State.MOTION_QUALITY_BLOCKED
        couplingSupported == 0 -> StagingAvailability.State.COUPLING_NOT_ESTABLISHED
        result.baseline == null -> StagingAvailability.State.BASELINE_NOT_ESTABLISHED
        result.baseline.narrowDistribution -> StagingAvailability.State.LOW_SIGNAL_DIFFERENTIATION
        else -> StagingAvailability.State.STAGE_WINDOW_BLOCKED
    }
    return StagingAvailability(
        usableFeatureMinutes = result.currentFeatureValidMinutes,
        couplingSupportedMinutes = couplingSupported,
        baselineSampleCount = result.baselineSampleCount,
        stageableMinutes = stageable,
        shortGapMinutes = shortGaps,
        missingMotionMinutes = missing,
        blockerCounts = blockerCounts,
        state = state
    )
}
