package com.hemant.plannerv1.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotFrameTest {
    @Test
    fun mapsModelCoordinatesBackToOriginalScreen() {
        val frame = ScreenshotFrame(
            sessionId = "s",
            stepNumber = 1,
            originalPath = "original.png",
            modelPath = "model.png",
            originalWidth = 2000,
            originalHeight = 1000,
            modelWidth = 1000,
            modelHeight = 500,
        )

        assertEquals(200, frame.mapModelXToScreen(100.0))
        assertEquals(400, frame.mapModelYToScreen(200.0))
    }

    @Test
    fun clampsMappedCoordinatesToScreenBounds() {
        val frame = ScreenshotFrame(
            sessionId = "s",
            stepNumber = 1,
            originalPath = "original.png",
            modelPath = "model.png",
            originalWidth = 100,
            originalHeight = 50,
            modelWidth = 100,
            modelHeight = 50,
        )

        assertEquals(99, frame.mapModelXToScreen(999.0))
        assertEquals(49, frame.mapModelYToScreen(999.0))
    }
}
