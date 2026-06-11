package com.hemant.plannerv1.model

import com.hemant.plannerv1.agent.ActionHistory
import com.hemant.plannerv1.capture.ScreenshotFrame

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
    ): ModelRequest {
        val previousActions = if (history.records.isEmpty()) {
            "[]"
        } else {
            history.records.joinToString(prefix = "[", postfix = "]") { record ->
                """{"step":${record.stepNumber},"action":"${record.action.type.value}","success":${record.executionResult.success},"reason":${jsonEscape(record.action.reason)}}"""
            }
        }

        val prompt = """
            You are UIActionAgent, an on-device Android UI agent.
            Decide the next single action to make progress toward the user goal.
            
            User goal:
            $goal
            
            Step:
            $stepNumber of $maxSteps
            
            Screenshot:
            The attached image is the current Android screen. Coordinates must use the attached image coordinate space.
            Image width: ${frame.modelWidth}
            Image height: ${frame.modelHeight}
            
            Previous actions:
            $previousActions
            
            Allowed actions:
            - click(x, y)
            - type(text)
            - swipe(up)
            - swipe(down)
            - swipe(left)
            - swipe(right)
            - back()
            - done()
            
            Output rules:
            Return JSON only.
            Do not use markdown.
            Do not include explanations outside JSON.
            Do not invent actions outside the allowed list.
            Use null for fields that do not apply.
            
            Required JSON schema:
            {"action":"click|type|swipe|back|done","x":number|null,"y":number|null,"text":string|null,"direction":"up|down|left|right|null","reason":"short reason","done":boolean}
        """.trimIndent()

        return ModelRequest(
            prompt = prompt,
            screenshotPath = frame.modelPath,
        )
    }

    private fun jsonEscape(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
