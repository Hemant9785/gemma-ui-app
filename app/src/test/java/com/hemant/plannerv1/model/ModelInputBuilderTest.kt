package com.hemant.plannerv1.model

import com.hemant.plannerv1.agent.ActionHistory
import com.hemant.plannerv1.agent.ActionRecord
import com.hemant.plannerv1.agent.ExecutionResult
import com.hemant.plannerv1.agent.UiAction
import com.hemant.plannerv1.agent.UiActionType
import com.hemant.plannerv1.capture.ScreenshotFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInputBuilderTest {
    @Test
    fun promptContainsGoalDimensionsAllowedActionsAndJsonRules() {
        val frame = ScreenshotFrame(
            sessionId = "session",
            stepNumber = 2,
            originalPath = "/tmp/original.png",
            modelPath = "/tmp/model.png",
            originalWidth = 2400,
            originalHeight = 1080,
            modelWidth = 1024,
            modelHeight = 461,
        )

        val request = ModelInputBuilder().build(
            goal = "Search weather in Chrome",
            history = ActionHistory(),
            stepNumber = 2,
            maxSteps = 10,
            frame = frame,
            currentAppName = "Chrome",
            currentPackageName = "com.android.chrome",
        )

        assertEquals("/tmp/model.png", request.screenshotPath)
        assertTrue(request.prompt.contains("GOAL: Search weather in Chrome"))
        assertTrue(request.prompt.contains("Current App: Chrome"))
        assertTrue(request.prompt.contains("Steps done:"))
        assertTrue(request.prompt.contains("For click, return bounding_box"))
        assertTrue(request.prompt.contains("\"thought\" and \"reason\" are optional"))
        assertTrue(request.prompt.contains("Return JSON only."))
        assertEquals(request.prompt, request.staticPrefix + "\n" + request.dynamicPrompt)
    }

    @Test
    fun promptInjectsDetectedTargetAppAsSeparateContext() {
        val frame = ScreenshotFrame(
            sessionId = "session",
            stepNumber = 1,
            originalPath = "/tmp/original.png",
            modelPath = "/tmp/model.png",
            originalWidth = 2400,
            originalHeight = 1080,
            modelWidth = 1024,
            modelHeight = 461,
        )

        val request = ModelInputBuilder().build(
            goal = "buy shoes from flipkart",
            history = ActionHistory(),
            stepNumber = 1,
            maxSteps = 10,
            frame = frame,
            currentAppName = "Launcher",
            detectedTargetAppName = "Flipkart",
            detectedTargetAppMatch = "flipkart",
        )

        assertTrue(request.prompt.contains("GOAL: buy shoes from flipkart"))
        assertTrue(request.prompt.contains("Detected target app from user goal:"))
        assertTrue(request.prompt.contains("- App: Flipkart"))
        assertTrue(request.prompt.contains("prefer open_app(\"Flipkart\")"))
    }

    @Test
    fun promptIncludesPreviousParserErrorAsCorrectiveFeedback() {
        val frame = ScreenshotFrame(
            sessionId = "session",
            stepNumber = 2,
            originalPath = "/tmp/original.png",
            modelPath = "/tmp/model.png",
            originalWidth = 2400,
            originalHeight = 1080,
            modelWidth = 1024,
            modelHeight = 461,
        )

        val request = ModelInputBuilder().build(
            goal = "Search for YouTube",
            history = ActionHistory(),
            stepNumber = 2,
            maxSteps = 10,
            frame = frame,
            currentAppName = "Play Store",
            lastError = "type_text requires non-empty text.",
        )

        assertTrue(request.prompt.contains("PARSER FEEDBACK FROM THE PREVIOUS MODEL ATTEMPT"))
        assertTrue(request.prompt.contains("type_text requires non-empty text."))
        assertTrue(request.prompt.contains("Retry the same UI step now"))
    }

    @Test
    fun dynamicPromptSanitizesContextValuesWithoutChangingSplitPrompt() {
        val frame = ScreenshotFrame(
            sessionId = "session",
            stepNumber = 3,
            originalPath = "/tmp/original.png",
            modelPath = "/tmp/model.png",
            originalWidth = 2400,
            originalHeight = 1080,
            modelWidth = 1024,
            modelHeight = 461,
        )
        val history = ActionHistory(
            records = listOf(
                ActionRecord(
                    stepNumber = 1,
                    action = UiAction(
                        type = UiActionType.TYPE_TEXT,
                        boundingBox = listOf(10.0, 20.0, 30.0, 40.0),
                        text = "weather\nforecast",
                        appName = null,
                        reason = "search\nbox",
                        done = false,
                    ),
                    executionResult = ExecutionResult(false, "keyboard\nfailed"),
                    screenshotPath = null,
                    latencyMs = 10,
                ),
            ),
        )

        val request = ModelInputBuilder().build(
            goal = "Search\nweather",
            history = history,
            stepNumber = 3,
            maxSteps = 10,
            frame = frame,
            currentAppName = "Chrome\nBrowser",
        )

        assertTrue(request.dynamicPrompt.contains("GOAL: Search weather"))
        assertTrue(request.dynamicPrompt.contains("Current App: Chrome Browser"))
        assertTrue(request.dynamicPrompt.contains("text: 'weather forecast'"))
        assertTrue(request.dynamicPrompt.contains("Error: keyboard failed"))
        assertEquals(request.prompt, request.staticPrefix + "\n" + request.dynamicPrompt)
    }
}
