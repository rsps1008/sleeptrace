package com.rsps1008.sleeptrace.sleep

fun csvEscape(value: String): String = if (value.any { it in ",\"\r\n" })
    "\"${value.replace("\"", "\"\"")}\"" else value

/** Single source of truth for the minute diagnostic export schema. */
const val DIAGNOSTIC_CSV_HEADER = "timestamp_local,covered_seconds,active_seconds,delta_rms_m_s2,sample_count,placement,feature_version,resampled_sample_count,resolved_placement,motion_level,session_id,nightly_p25,nightly_p35,nightly_p50,nightly_p65,nightly_p70,nightly_p75,rolling_median_rms,computed_stage,stored_stage,staging_event,valid_motion_minute_percent,sensor_coverage_percent,first_motion_delay_minutes,staging_motion_usable,staging_motion_role,staging_motion_exclusion_reason,baseline_feature_version,baseline_sample_count,baseline_eligible_minutes,current_feature_valid_minutes,baseline_reason,legacy_feature_minutes,current_feature_minutes,cadence_incompatible_minutes,bed_minutes,unknown_minutes,deep_enter_events,deep_exit_events,stage_algorithm_version,stored_stage_algorithm_version,stored_stage_feature_version,minute_end_epoch_ms,missing_ms,longest_gap_ms,max_delta_m_s2,movement_events,longest_active_ms,quiet_tail_ms,posture_delta_m_s2,recording_id,coupling_state,coupling_age_ms,coupling_invalidation,can_stage,can_enter_deep,can_maintain_deep,prior_deep,current_eligibility,entry_decision_applicable,entry_decision_allowed,entry_decision_reasons,maintenance_decision_applicable,maintenance_decision_allowed,maintenance_decision_reasons,formal_action,transition_reason,formal_stage,was_backfilled,safety_cap_adjusted,retroactively_adjusted,retroactive_adjustment_reason,retroactive_adjustment_source_ms,final_stage,high_motion_windows_before,high_motion_windows_candidate,high_motion_windows_after,current_eligibility_reasons,window_blocking_reasons,window_blocking_intervals,non_blocking_reasons,reason_codes,primary_reason,phone_use_overlap_ms,onset_guard,sleep_evidence,first_valid_motion_delay_ms,span_motion_coverage,stageable_sleep_coverage,session_span_ms,sleep_ms,deep_ms,light_ms,undetermined_ms,awake_ms,undetermined_reasons_ms,night_longest_gap_ms,exact_computed_parts,exact_stored_parts,capture_trigger,scheduled_window_start_ms,sensor_registered_at_ms,first_event_ms,requested_period_us,fifo_latency_us,fifo_count,wake_up,raw_events,rejected_events,mean_event_interval_ms,max_event_interval_ms"

val diagnosticCsvHeaders: List<String> = DIAGNOSTIC_CSV_HEADER.split(',')

/** Escapes one complete row and rejects accidental header/row drift. */
fun diagnosticCsvRow(values: List<String>): String {
    require(values.size == diagnosticCsvHeaders.size) {
        "Diagnostic CSV row has ${values.size} fields; expected ${diagnosticCsvHeaders.size}"
    }
    return values.joinToString(",", transform = ::csvEscape)
}
