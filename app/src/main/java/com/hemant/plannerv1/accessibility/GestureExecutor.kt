package com.hemant.plannerv1.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hemant.plannerv1.agent.ExecutionResult
import com.hemant.plannerv1.agent.SwipeDirection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GestureExecutor(private val context: Context) {
    fun currentPackageName(): String? {
        return serviceOrNull()?.rootInActiveWindow?.packageName?.toString()
    }

    suspend fun click(x: Int, y: Int): ExecutionResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, durationMs = 80, label = "click($x,$y)")
    }

    suspend fun swipe(direction: SwipeDirection): ExecutionResult {
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val distanceX = metrics.widthPixels * 0.32f
        val distanceY = metrics.heightPixels * 0.32f
        val path = Path()
        when (direction) {
            SwipeDirection.UP -> {
                path.moveTo(centerX, centerY + distanceY)
                path.lineTo(centerX, centerY - distanceY)
            }
            SwipeDirection.DOWN -> {
                path.moveTo(centerX, centerY - distanceY)
                path.lineTo(centerX, centerY + distanceY)
            }
            SwipeDirection.LEFT -> {
                path.moveTo(centerX + distanceX, centerY)
                path.lineTo(centerX - distanceX, centerY)
            }
            SwipeDirection.RIGHT -> {
                path.moveTo(centerX - distanceX, centerY)
                path.lineTo(centerX + distanceX, centerY)
            }
        }
        return dispatch(path, durationMs = 420, label = "swipe(${direction.value})")
    }

    fun back(): ExecutionResult {
        val service = serviceOrNull()
            ?: return ExecutionResult(false, "Accessibility service is not connected.")
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return ExecutionResult(success, if (success) "back()" else "Back action failed.")
    }

    fun type(text: String): ExecutionResult {
        val service = serviceOrNull()
            ?: return ExecutionResult(false, "Accessibility service is not connected.")
        val focused = service.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return ExecutionResult(false, "No focused editable field is available.")
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return ExecutionResult(success, if (success) "type(${text.length} chars)" else "Text action failed.")
    }

    private suspend fun dispatch(
        path: Path,
        durationMs: Long,
        label: String,
    ): ExecutionResult {
        val service = serviceOrNull()
            ?: return ExecutionResult(false, "Accessibility service is not connected.")
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(ExecutionResult(true, label))
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(ExecutionResult(false, "$label cancelled."))
                        }
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) {
                continuation.resume(ExecutionResult(false, "$label was rejected."))
            }
        }
    }

    private fun serviceOrNull(): UiActionAccessibilityService? = UiActionAccessibilityService.current
}
