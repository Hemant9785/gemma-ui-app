package com.hemant.plannerv1.agent

data class ActionRecord(
    val stepNumber: Int,
    val action: UiAction,
    val executionResult: ExecutionResult,
    val screenshotPath: String?,
    val latencyMs: Long,
) {
    val signature: String = action.signature()
}

data class ActionHistory(
    val records: List<ActionRecord> = emptyList(),
) {
    fun append(record: ActionRecord): ActionHistory = copy(records = records + record)
}
