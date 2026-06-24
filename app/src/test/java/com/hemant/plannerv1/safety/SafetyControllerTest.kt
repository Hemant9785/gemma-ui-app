package com.hemant.plannerv1.safety

import com.hemant.plannerv1.agent.ActionHistory
import com.hemant.plannerv1.agent.ActionRecord
import com.hemant.plannerv1.agent.ExecutionResult
import com.hemant.plannerv1.agent.UiAction
import com.hemant.plannerv1.agent.UiActionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyControllerTest {
    private val safety = SafetyController()

    @Test
    fun blocksBankingAndPaymentAppsByAppName() {
        assertTrue(safety.isAppBlocked("Google Pay", "com.google.android.apps.nbu.paisa.user"))
        assertTrue(safety.isAppBlocked("PayPal", "com.paypal.android.p2pmobile"))
        assertFalse(safety.isAppBlocked("YouTube", "com.google.android.youtube"))
    }

    @Test
    fun fallsBackToPackageNameWhenAppNameDoesNotMatch() {
        assertTrue(safety.isAppBlocked("Paisa", "com.google.android.apps.nbu.paisa.user"))
        assertTrue(safety.isAppBlocked(null, "com.example.mobilebank"))
        assertFalse(safety.isAppBlocked("YouTube", "com.google.android.youtube"))
    }

    @Test
    fun detectsRepeatedIdenticalAction() {
        val click = UiAction(
            type = UiActionType.CLICK,
            boundingBox = listOf(20.0, 40.0, 30.0, 50.0),
            text = null,
            appName = null,
            reason = "tap",
            done = false,
        )
        val history = ActionHistory(
            records = listOf(
                ActionRecord(
                    stepNumber = 1,
                    action = click,
                    executionResult = ExecutionResult(true, "click"),
                    screenshotPath = null,
                    latencyMs = 12,
                ),
            ),
        )

        assertTrue(safety.isRepeatedAction(history, click))
    }

    @Test
    fun allowsConfiguredInvalidJsonFeedbackRetriesBeforeStopping() {
        val safetyWithTwoRetries = SafetyController(maxInvalidJson = 2)

        assertFalse(safetyWithTwoRetries.hasExhaustedInvalidJsonRetries(1))
        assertFalse(safetyWithTwoRetries.hasExhaustedInvalidJsonRetries(2))
        assertTrue(safetyWithTwoRetries.hasExhaustedInvalidJsonRetries(3))
    }
}
