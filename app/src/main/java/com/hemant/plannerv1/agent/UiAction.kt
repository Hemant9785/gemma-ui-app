package com.hemant.plannerv1.agent

enum class UiActionType(val value: String) {
    CLICK("click"),
    TYPE_TEXT("type_text"),
    SCROLL_UP("scroll_up"),
    SCROLL_DOWN("scroll_down"),
    OPEN_APP("open_app"),
    BACK("back"),
    DONE("done"),
}

data class UiAction(
    val type: UiActionType,
    val boundingBox: List<Double>?,
    val text: String?,
    val appName: String?,
    val reason: String,
    val done: Boolean,
) {
    fun signature(): String {
        return listOf(
            type.value,
            boundingBox?.joinToString(",") { it.toInt().toString() } ?: "",
            text ?: "",
            appName ?: ""
        ).joinToString("|")
    }
}

data class ExecutionResult(
    val success: Boolean,
    val message: String,
)
