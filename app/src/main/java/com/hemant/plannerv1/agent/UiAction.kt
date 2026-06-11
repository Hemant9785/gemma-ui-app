package com.hemant.plannerv1.agent

enum class UiActionType(val value: String) {
    CLICK("click"),
    TYPE("type"),
    SWIPE("swipe"),
    BACK("back"),
    DONE("done"),
}

enum class SwipeDirection(val value: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
}

data class UiAction(
    val type: UiActionType,
    val x: Double?,
    val y: Double?,
    val text: String?,
    val direction: SwipeDirection?,
    val reason: String,
    val done: Boolean,
) {
    fun signature(): String {
        return listOf(
            type.value,
            x?.toInt()?.toString() ?: "",
            y?.toInt()?.toString() ?: "",
            text ?: "",
            direction?.value ?: "",
        ).joinToString("|")
    }
}

data class ExecutionResult(
    val success: Boolean,
    val message: String,
)
