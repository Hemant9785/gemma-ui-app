package com.hemant.plannerv1.model

import com.hemant.plannerv1.agent.ActionHistory
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
            currentActivity = "com.android.chrome.MainActivity"
        )

        assertEquals("/tmp/model.png", request.screenshotPath)
        assertTrue(request.prompt.contains("GOAL: Search weather in Chrome"))
        assertTrue(request.prompt.contains("Current Activity: com.android.chrome.MainActivity"))
        assertTrue(request.prompt.contains("Steps done:"))
        assertTrue(request.prompt.contains("For click, output bounding_box"))
        assertTrue(request.prompt.contains("Return JSON only."))
    }
}
