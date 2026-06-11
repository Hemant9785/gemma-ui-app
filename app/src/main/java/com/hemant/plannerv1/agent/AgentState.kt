package com.hemant.plannerv1.agent

data class AgentState(
    val isRunning: Boolean = false,
    val goal: String = "",
    val sessionId: String? = null,
    val currentStep: Int = 0,
    val maxSteps: Int = 10,
    val invalidJsonCount: Int = 0,
    val status: String = "Idle",
    val lastError: String? = null,
    val history: ActionHistory = ActionHistory(),
)
