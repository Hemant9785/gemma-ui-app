package com.hemant.plannerv1.eval

/**
 * Immutable state snapshot for the evaluation runner, consumed by the UI.
 */
data class EvalRunnerState(
    val isRunning: Boolean = false,
    /** Number of goals that have been fully processed (success or fail). */
    val completedGoals: Int = 0,
    val totalGoals: Int = 0,
    val currentGoalNumber: Int = 0,
    val currentGoalText: String = "",
    val currentStep: Int = 0,
    val currentStatus: String = "Idle",
    /** Absolute path of the output directory once a run completes. */
    val outputDirPath: String? = null,
    val lastError: String? = null,
    /** Snapshot of results accumulated so far (grows as goals complete). */
    val results: List<EvalGoalResult> = emptyList(),
    val maxStepsPerGoal: Int = 20,
) {
    val progress: Float
        get() = if (totalGoals == 0) 0f else completedGoals.toFloat() / totalGoals.toFloat()
}
