package com.hemant.plannerv1.eval

/**
 * Aggregated result for one evaluated goal.
 *
 * [status] values:
 *  - "Success"              — agent emitted done=true within [totalSteps]
 *  - "Failed: max steps"    — loop exhausted without done
 *  - "Failed: timeout"      — per-goal wall-clock timeout exceeded
 *  - "Failed: home reset"   — pressHome() failed before the goal even started
 *  - "Failed: repeated action" — safety controller detected loop
 *  - "Failed: invalid JSON" — maxInvalidJson threshold reached
 *  - "Failed: error"        — uncaught exception during a step
 *  - "Stopped"              — coroutine was cancelled externally
 */
data class EvalGoalResult(
    val goalNumber: Int,
    val goal: String,
    val status: String,
    val totalSteps: Int,
    /** Average of [EvalStepRecord.modelInferenceLatencyMs] across all recorded steps. */
    val avgInferenceMs: Long,
    val steps: List<EvalStepRecord>,
)
