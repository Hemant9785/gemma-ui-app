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
        lastError: String? = null,
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

        val injection = com.hemant.plannerv1.AppContainer.promptInjectionManager.getChecklistForPackage(currentActivity)
        val injectionStr = if (injection != null) "\n$injection" else ""

        val errorWarning = if (lastError != null) {
            "\nSYSTEM WARNING: Your previous output failed with error: '$lastError'\nPlease correct your JSON format or action type.\n"
        } else ""

        val prompt = """
                Act like UIActionAgent, an on-device Android UI automation agent.

                Your task is to decide the next single action that best moves toward the user goal, based on the screenshot and context.

                GOAL: $goal
                Current Activity: $currentActivity
                Steps done:
                $stepsDone

                Before choosing an action:
                1) First analyze the user goal, current screen, and previous actions.
                2) Check whether the goal has already been completed.
                3) If the goal is completed, return action = "done" and done = true.
                4) If the goal is not completed, choose exactly one next action.

                Decision rules:
                1) Follow this priority order whenever possible:
                   open_app > type_text > click > scroll_down / scroll_up > wait > back > done.
                2) Prefer high-level actions over low-level visual clicks.
                3) Home Screen rule:
                   - If the current screen is the Home Screen or Launcher and the goal requires opening an app, use open_app.
                   - Example: {"action":"open_app","app_name":"YouTube"}
                   - Do not click app icons from the launcher.
                4) Failure loop rule:
                   - If the previous step failed, do not repeat the same action.
                   - Try a different valid action.
                5) Search rule:
                   - If a search bar/input field is directly visible, use type_text.
                   - If only a search icon is visible, click the search icon first.
                   - After successfully typing a search query, choose the most relevant visible search result.
                   - If no relevant result is visible, use scroll_down.
                6) Click rule:
                   - For click, return bounding_box as [ymin, xmin, ymax, xmax] in 0-1000 scale.
                   - The center of the box will be clicked.
                7) Type rule:
                   - For type_text, return the bounding_box of the input field and the text to type.
                8) Visibility rule:
                   - If the target is not visible, use scroll_down or scroll_up.
                9) Loading rule:
                   - If the page is loading or an animation is still running, use wait.
                10) Stuck rule:
                   - Use back only if the current screen is clearly not useful for the goal.
                11) Completion rule:
                   - Use done only when the goal is fully completed.
                $injectionStr
                $errorWarning
                Return JSON only. Do not include markdown, comments, or extra text.
                Keep "thought" and "reason" to one concise sentence each.

                Required JSON schema:
                {"thought":"concise reasoning for whether the goal is complete and why this action is next","action":"click|type_text|scroll_up|scroll_down|open_app|wait|back|done","bounding_box":[ymin,xmin,ymax,xmax] | null,"text":string|null,"app_name":string|null,"reason":"concise reason for the action","done":boolean}
            """.trimIndent()

        DbgLog.d(
            "Prompt built goalChars=${goal.length} step=$stepNumber/$maxSteps " +
                "historySize=${history.records.size} promptChars=${prompt.length} " +
                "promptInjectionEnabled=${com.hemant.plannerv1.AppContainer.promptInjectionManager.isEnabled} " +
                "promptInjectionLength=${injectionStr.length} " +
                "promptPreview=${DbgLog.preview(prompt)}",
        )

        return ModelRequest(
            prompt = prompt,
            screenshotPath = frame.modelPath,
        )
    }
}
