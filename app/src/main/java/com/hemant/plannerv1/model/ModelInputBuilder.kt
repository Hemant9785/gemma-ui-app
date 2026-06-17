package com.hemant.plannerv1.model

import com.hemant.plannerv1.agent.ActionHistory
import com.hemant.plannerv1.capture.ScreenshotFrame
import com.hemant.plannerv1.logging.DbgLog

import com.hemant.plannerv1.agent.UiActionType

data class ModelRequest(
    val prompt: String,
    val screenshotPath: String,
)

class ModelInputBuilder {
    fun build(
        goal: String,
        history: ActionHistory,
        stepNumber: Int,
        maxSteps: Int,
        frame: ScreenshotFrame,
        currentActivity: String,
    ): ModelRequest {
        val stepsDone = if (history.records.isEmpty()) {
            "None."
        } else {
            history.records.joinToString("\n") { record ->
                val action = record.action
                
                val actionDetails = when (action.type) {
                    UiActionType.OPEN_APP -> "${action.appName}"
                    UiActionType.TYPE_TEXT -> "text: '${action.text}'"
                    UiActionType.CLICK -> "reason: ${action.reason}"
                    else -> "reason: ${action.reason}"
                }
                
                val resultStr = if (record.executionResult.success) {
                    "success"
                } else {
                    if (action.type == UiActionType.OPEN_APP) {
                        "failure (Try different approach. Try different name or go to home screen, scroll up and use search bar. Error: ${record.executionResult.message})"
                    } else {
                        "failure (Try different approach. Error: ${record.executionResult.message})"
                    }
                }
                
                "${record.stepNumber}. ${action.type.value} ($actionDetails) -> $resultStr"
            }
        }

            val prompt = """
                You are UIActionAgent, an on-device Android UI agent.
                Decide the next single action to make progress toward the user goal.
                
                GOAL: $goal
                Current Activity: $currentActivity
                Steps done:
                $stepsDone
                
                Strategy:
                - PRIORITY ORDER: open_app > type_text > click > scroll. Always prefer high-level commands over low-level visual clicks.
                - HOME SCREEN RULE: If you are currently on the Home Screen/Launcher and the goal requires navigating to an app, ALWAYS use open_app (e.g. {"action": "open_app", "app_name": "YouTube"}). DO NOT try to click the app icon.
                - FAILURE LOOP: If the previous step failed, DO NOT repeat it. Try an alternative approach.
                - For click, output bounding_box [ymin, xmin, ymax, xmax] in 0-1000 scale. Center of the box will be clicked.
                - For type_text, output bounding_box of the input field to focus, and the text to type.
                - If a target is not visible, use scroll_up or scroll_down.
                - If you are waiting for a page to load or an animation to finish, use wait.
                - Use back if stuck. Use done when goal is completed.

                Based on the screenshot and above context, what is the next action?
                
                Output rules:
                Return JSON only. Keep 'thought' and 'reason' extremely concise (max 1 sentence). Do not be verbose.
                Required JSON schema:
                {"thought":"your concise step-by-step reasoning","action":"click|type_text|scroll_up|scroll_down|open_app|wait|back|done","bounding_box":[ymin,xmin,ymax,xmax] | null,"text":string|null,"app_name":string|null,"reason":"concise reason","done":boolean}
            """.trimIndent()

        DbgLog.d(
            "Prompt built goalChars=${goal.length} step=$stepNumber/$maxSteps " +
                "historySize=${history.records.size} promptChars=${prompt.length} " +
                "promptPreview=${DbgLog.preview(prompt)}",
        )

        return ModelRequest(
            prompt = prompt,
            screenshotPath = frame.modelPath,
        )
    }
}
