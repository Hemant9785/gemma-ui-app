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
    fun blocksBankingAndPaymentPackages() {
        assertTrue(safety.isPackageBlocked("com.example.mobilebank"))
        assertTrue(safety.isPackageBlocked("com.google.android.apps.nbu.paisa.user"))
        assertFalse(safety.isPackageBlocked("com.google.android.youtube"))
    }

    @Test
    fun detectsRepeatedIdenticalAction() {
        val click = UiAction(
            type = UiActionType.CLICK,
            x = 20.0,
            y = 40.0,
            text = null,
            direction = null,
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
}
