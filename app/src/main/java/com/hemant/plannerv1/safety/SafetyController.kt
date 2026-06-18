package com.hemant.plannerv1.safety

import com.hemant.plannerv1.agent.ActionHistory
import com.hemant.plannerv1.agent.UiAction
import com.hemant.plannerv1.agent.UiActionType

class SafetyController(
    val maxSteps: Int = 10,
    val maxInvalidJson: Int = 2,
    val actionDelayMs: Long = 0L,
) {
    private val blockedAppNameFragments = listOf(
        "bank",
        "payment",
        "wallet",
        "upi",
        "paypal",
        "paytm",
        "phonepe",
        "google pay",
        "gpay",
    )

    private val blockedPackageFragments = listOf(
        "bank",
        "payment",
        "wallet",
        "upi",
        "paypal",
        "paytm",
        "phonepe",
        "gpay",
        "google.android.apps.nbu.paisa",
    )

    fun isAppBlocked(appName: String?, packageName: String?): Boolean {
        if (matchesBlockedFragment(appName, blockedAppNameFragments)) return true
        return matchesBlockedFragment(packageName, blockedPackageFragments)
    }

    private fun matchesBlockedFragment(value: String?, fragments: List<String>): Boolean {
        val normalized = value?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank() || normalized == "unknown") return false
        return fragments.any { normalized.contains(it) }
    }

    fun isRepeatedAction(history: ActionHistory, nextAction: UiAction): Boolean {
        val last = history.records.lastOrNull() ?: return false
        return last.signature == nextAction.signature()
    }

    /**
     * Returns true when the model has scrolled twice in a row (same or opposite direction).
     * Scroll loops should NOT terminate the session — instead the caller should inject a
     * feedback warning into the next prompt so the model tries a different action.
     */
    fun isRepeatedScroll(history: ActionHistory, nextAction: UiAction): Boolean {
        val scrollTypes = setOf(UiActionType.SCROLL_UP, UiActionType.SCROLL_DOWN)
        if (nextAction.type !in scrollTypes) return false
        val last = history.records.lastOrNull() ?: return false
        return last.action.type in scrollTypes
    }
}
