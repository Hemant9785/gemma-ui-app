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
                Instruction: If Current App is not $detectedTargetApp, expect the runtime to open it before the next screenshot when possible.
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

Decide the next single action that best progresses the user goal.

First analyze the goal, current screen, and previous actions. If the goal is already fully completed, return action="done" and done=true.

Rules:
- Current screenshot is the source of truth.
- Choose exactly one action.
- Prefer: type_text > click > scroll_down > scroll_up > wait > back.
- Do not repeat a failed previous action.
- If a relevant input/search field is visible and needed text is not already entered, use type_text.
- If only a search icon is visible, click it first.
- If target is not visible, scroll.
- Use wait only for loading/transition.
- Use back only if current screen is clearly wrong or blocked.l
- Use done only when the final user goal is fully satisfied.
- For click/type_text, bounding_box format is [ymin,xmin,ymax,xmax] in 0-1000 scale.
- Overlay rule: If a popup, modal, ad, permission dialog, bottom sheet, or spin-wheel overlay blocks the screen, handle it first. Prefer Close/X, Skip, Not now, Maybe later, Continue, or Allow when required. Do not interact with background UI behind the overlay.
- Search completion rule: If the goal is only to search/find/show results, return done once relevant results for the query are visible. Do not click filters, sort, product cards, or ads unless the goal specifically asks to refine, select, buy, or open an item.
- Return JSON only.

CURRENT TASK CONTEXT:
GOAL: $goal
Current App: $currentAppName
$detectedTargetAppContextStr
Steps done:
$stepsDone
$injectionStr
$errorWarning

Return JSON only. Keep "thought" and "reason" concise.

JSON action requirements:
- Every action requires "action": "click|type_text|scroll_up|scroll_down|wait|back|done".
- click additionally requires "bounding_box": [ymin,xmin,ymax,xmax].
- type_text additionally requires "bounding_box": [ymin,xmin,ymax,xmax] and non-empty "text".
- "thought" and "reason" are optional concise strings.
- Other fields are optional and ignored when not required.
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
