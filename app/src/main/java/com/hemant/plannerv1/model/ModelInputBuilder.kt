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
        currentAppName: String,
        currentPackageName: String? = null,
        detectedTargetAppName: String? = null,
        detectedTargetAppMatch: String? = null,
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

        val promptInjectionManager = runCatching {
            com.hemant.plannerv1.AppContainer.promptInjectionManager
        }.getOrNull()
        val injection = promptInjectionManager?.getChecklistForApp(
            appName = currentAppName,
            packageName = currentPackageName,
        )
        val injectionStr = if (injection != null) "\n$injection" else ""

        val detectedTargetApp = detectedTargetAppName
            ?.sanitizePromptValue()
            ?.takeIf { it.isNotBlank() }
        val detectedTargetMatch = detectedTargetAppMatch
            ?.sanitizePromptValue()
            ?.takeIf { it.isNotBlank() }
            ?: detectedTargetApp
        val detectedTargetAppContext = if (detectedTargetApp != null) {
            """
                Detected target app from user goal:
                - App: $detectedTargetApp
                - Exact installed-app-name match: $detectedTargetMatch
                Instruction: If Current App is not $detectedTargetApp, prefer open_app("$detectedTargetApp") before click, type_text, or scroll actions.
            """.trimIndent()
        } else {
            null
        }
        val detectedTargetAppContextStr = if (detectedTargetAppContext != null) {
            "\n$detectedTargetAppContext"
        } else {
            ""
        }

        val errorWarning = if (lastError != null) {
            """

                PARSER FEEDBACK FROM THE PREVIOUS MODEL ATTEMPT:
                - Error: ${lastError.sanitizePromptValue()}
                - Retry the same UI step now. Correct this error and return one valid JSON action only.
                - Do not repeat the invalid output.
            """.trimIndent()
        } else ""

        val prompt = """
                Act like UIActionAgent, an on-device Android UI automation agent.

                Your task is to decide the next single action that best moves toward the user goal, based on the screenshot and context.

                Before choosing an action:
                1) First analyze the user goal, current screen, and previous actions.
                2) Check whether the goal has already been completed.
                3) If the goal is completed, return action = "done" and done = true.
                4) If the goal is not completed, choose exactly one next action.

                Decision rules:
                1) Evidence priority:
                   - Base the action first on the current screenshot and visible UI, then previous actions and their results, then the user goal and completion state.
                   - Treat app-specific guidance as contextual hints only. Never assume an element exists because guidance mentions it.
                2) Follow this action priority order whenever it matches the visible screen and action history:
                   open_app > type_text > click > scroll_down / scroll_up > wait > back > done.
                3) Prefer high-level actions over low-level visual clicks.
                4) Home Screen rule:
                   - If the current screen is the Home Screen or Launcher and the goal requires opening an app, use open_app.
                   - Do not click app icons from the launcher.
                5) Failure loop rule:
                   - If the previous step failed, do not repeat the same action.
                   - Try a different valid action.
                6) Search rule:
                   - If a search bar/input field is directly visible, use type_text.
                   - If only a search icon is visible, click the search icon first.
                   - After successfully typing a search query, inspect product images and search result descriptions closely.
                   - Match results to the user's final goal by checking category, brand, and exact details.
                   - Ignore ads or sponsored products unless truly relevant.
                   - If no strong matching result is visible, use scroll_down to find a better match.
                7) Click rule:
                   - For click, return bounding_box as [ymin, xmin, ymax, xmax] in 0-1000 scale.
                   - The center of the box will be clicked.
                8) Type rule:
                   - For type_text, return the bounding_box of the input field and the text to type.
                9) Visibility rule:
                   - If the target is not visible, use scroll_down or scroll_up.
                10) Loading rule:
                   - If the page is loading or an animation is still running, use wait.
                11) Stuck rule:
                   - Use back only if the current screen is clearly not useful for the goal.
                12) Completion rule:
                   - Use done only when the goal is fully completed.

                EXAMPLES OF CORRECT OUTPUTS:

                Example 1 (Opening an app from launcher):
                GOAL: Search for movies in Video App
                Current App: Launcher
                Steps done: None.
                Response: {"thought":"Goal is not complete. We are on the launcher screen, so we should launch Video App.","action":"open_app","bounding_box":null,"text":null,"app_name":"Video App","reason":"Open Video App from launcher","done":false}

                Example 2 (Typing search query):
                GOAL: Search for beach clothes in Shopping App
                Current App: Shopping App
                Steps done: 1. open_app (Shopping App) -> success
                Response: {"thought":"Goal is not complete. The search bar is visible in Shopping App, so we should type our query.","action":"type_text","bounding_box":[45,60,110,940],"text":"beach clothes","app_name":null,"reason":"Type search query in the search input field","done":false}

                Example 3 (Completing goal):
                GOAL: Search for beach clothes in Shopping App
                Current App: Shopping App
                Steps done:
                1. open_app (Shopping App) -> success
                2. type_text (text: 'beach clothes') -> success
                Response: {"thought":"Goal is complete since the search results for beach clothes are now displayed on screen.","action":"done","bounding_box":null,"text":null,"app_name":null,"reason":"Search results for beach clothes are displayed","done":true}

                CURRENT TASK CONTEXT:
                GOAL: $goal
                Current App: $currentAppName
                $detectedTargetAppContextStr
                Steps done:
                $stepsDone
                $injectionStr
                $errorWarning
                Return JSON only. Do not include markdown, comments, or extra text.
                Keep "thought" and "reason" to one concise sentence each.

                JSON action requirements:
                - Every action requires "action": "click|type_text|scroll_up|scroll_down|open_app|wait|back|done".
                - click additionally requires "bounding_box": [ymin,xmin,ymax,xmax].
                - type_text additionally requires "bounding_box": [ymin,xmin,ymax,xmax] and non-empty "text".
                - open_app additionally requires non-empty "app_name".
                - "thought" and "reason" are optional concise strings.
                - Other fields are optional and ignored when they are not required by the selected action.
            """.trimIndent()

        DbgLog.d(
            "Prompt built goalChars=${goal.length} step=$stepNumber/$maxSteps " +
                "historySize=${history.records.size} promptChars=${prompt.length} " +
                "currentAppName=$currentAppName currentPackageName=${currentPackageName ?: "unknown"} " +
                "detectedTargetApp=${detectedTargetApp ?: "none"} " +
                "promptInjectionEnabled=${promptInjectionManager?.isEnabled ?: false} " +
                "promptInjectionLength=${injectionStr.length} " +
                "promptPreview=${DbgLog.preview(prompt)}",
        )

        return ModelRequest(
            prompt = prompt,
            screenshotPath = frame.modelPath,
        )
    }

    private fun String.sanitizePromptValue(): String {
        return replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
