package com.hemant.plannerv1.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hemant.plannerv1.agent.AppTargetDetector
import com.hemant.plannerv1.agent.ExecutionResult
import com.hemant.plannerv1.agent.TargetAppDetection
import com.hemant.plannerv1.logging.DbgLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class CurrentAppContext(
    val appName: String?,
    val packageName: String?,
) {
    val preferredName: String
        get() = cleanName(appName)
            ?: cleanName(packageName)
            ?: "unknown"

    private fun cleanName(rawValue: String?): String? {
        val value = rawValue?.trim()
        return value?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    }
}

class GestureExecutor(private val context: Context) {
    fun currentPackageName(): String? {
        return serviceOrNull()?.rootInActiveWindow?.packageName?.toString()
    }

    fun currentAppName(): String? = appNameForPackage(currentPackageName())

    fun currentAppContext(): CurrentAppContext {
        val packageName = currentPackageName()
        return CurrentAppContext(
            appName = appNameForPackage(packageName),
            packageName = packageName,
        )
    }

    fun appNameForPackage(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val pm = context.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrNull()
    }

    fun detectTargetAppInGoal(goal: String): TargetAppDetection? {
        val detection = AppTargetDetector.detect(goal, installedLaunchableAppNames())
        if (detection != null) {
            DbgLog.i(
                "Detected target app from goal app=${detection.appName} match=${detection.matchedText}",
                tag = "ACTION_DBG",
            )
        }
        return detection
    }

    private fun installedLaunchableAppNames(): List<String> {
        val pm = context.packageManager
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(launcherIntent, 0)
            .map { it.loadLabel(pm).toString().trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    suspend fun click(x: Int, y: Int): ExecutionResult {
        DbgLog.d("Executing click gesture at x=$x, y=$y", tag = "ACTION_DBG")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, durationMs = 80, label = "click($x,$y)")
    }

    suspend fun scrollUp(): ExecutionResult {
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val distanceY = metrics.heightPixels * 0.32f
        val path = Path().apply {
            moveTo(centerX, centerY - distanceY)
            lineTo(centerX, centerY + distanceY)
        }
        DbgLog.d("Executing scrollUp gesture from (${centerX}, ${centerY - distanceY}) to (${centerX}, ${centerY + distanceY})", tag = "ACTION_DBG")
        return dispatch(path, durationMs = 420, label = "scrollUp()")
    }

    suspend fun scrollDown(): ExecutionResult {
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val distanceY = metrics.heightPixels * 0.32f
        val path = Path().apply {
            moveTo(centerX, centerY + distanceY)
            lineTo(centerX, centerY - distanceY)
        }
        DbgLog.d("Executing scrollDown gesture from (${centerX}, ${centerY + distanceY}) to (${centerX}, ${centerY - distanceY})", tag = "ACTION_DBG")
        return dispatch(path, durationMs = 420, label = "scrollDown()")
    }

    fun openApp(appName: String, clearTask: Boolean = false): ExecutionResult {
        val query = appName.trim()
        if (query.isBlank()) {
            return ExecutionResult(false, "App name is empty.")
        }

        val pm = context.packageManager
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(launcherIntent, 0)

        var bestScore = 0
        var bestPackage: String? = null
        var matchSource = "app label"

        for (info in activities) {
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName

            val score = scoreAppNameMatch(query, label)
            if (score > bestScore) {
                bestScore = score
                bestPackage = pkg
            }
        }

        if (bestScore == 0) {
            matchSource = "package name"
            for (info in activities) {
                val pkg = info.activityInfo.packageName
                val score = scoreAppNameMatch(query, pkg)
                if (score > bestScore) {
                    bestScore = score
                    bestPackage = pkg
                }
            }
        }

        if (bestPackage != null) {
            val intent = pm.getLaunchIntentForPackage(bestPackage)?.apply {
                var flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                if (clearTask) {
                    flags = flags or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                addFlags(flags)
            }
            if (intent != null) {
                DbgLog.d(
                    "Opening app $query matched $bestPackage with score $bestScore source=$matchSource clearTask=$clearTask",
                    tag = "ACTION_DBG",
                )
                context.startActivity(intent)
                return ExecutionResult(true, "openApp($query)")
            }
        }

        return ExecutionResult(false, "App not found or cannot be launched: $query")
    }

    private fun scoreAppNameMatch(query: String, candidate: String?): Int {
        val value = candidate?.trim().orEmpty()
        if (query.isBlank() || value.isBlank()) return 0
        return when {
            value.equals(query, ignoreCase = true) -> 100
            value.startsWith(query, ignoreCase = true) -> 80
            value.contains(query, ignoreCase = true) -> 60
            query.contains(value, ignoreCase = true) -> 50
            else -> scoreWordWindowMatch(query, value)
        }
    }

    private fun scoreWordWindowMatch(query: String, candidate: String): Int {
        val words = query.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size < 2) return 0
        for (i in 0 until words.size - 1) {
            val window = "${words[i]} ${words[i + 1]}"
            if (candidate.contains(window, ignoreCase = true)) {
                return 30
            }
        }
        return 0
    }

    fun back(): ExecutionResult {
        val service = serviceOrNull()
            ?: return ExecutionResult(false, "Accessibility service is not connected.")
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        DbgLog.d("Executing back action, success=$success", tag = "ACTION_DBG")
        return ExecutionResult(success, if (success) "back()" else "Back action failed.")
    }

    /**
     * Presses the Home button via the Accessibility Service global action.
     * Used exclusively by [com.hemant.plannerv1.eval.EvalRunner] to reset to the launcher
     * before each evaluation goal. The model agent never calls or sees this method.
     */
    fun pressHome(): ExecutionResult {
        val service = serviceOrNull()
            ?: return ExecutionResult(false, "Accessibility service is not connected.")
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        DbgLog.d("Executing pressHome action, success=$success", tag = "ACTION_DBG")
        return ExecutionResult(success, if (success) "pressHome()" else "Home action failed.")
    }

    suspend fun typeText(targetRect: Rect, text: String): ExecutionResult {
        val normalizedTargetRect = normalizedRect(targetRect)
        val centerX = normalizedTargetRect.centerX()
        val centerY = normalizedTargetRect.centerY()
        DbgLog.d(
            "Executing typeText targetRect=${normalizedTargetRect.toShortString()} centerX=$centerX centerY=$centerY text: $text",
            tag = "ACTION_DBG",
        )

        val primaryResult = typeText(centerX, centerY, text)
        if (primaryResult.success) {
            return primaryResult
        }

        DbgLog.w(
            "typeText primary point path failed, trying targetRect fallback. " +
                "targetRect=${normalizedTargetRect.toShortString()} error=${primaryResult.message}",
            tag = "ACTION_DBG",
        )
        val fallbackResult = typeTextByTargetRect(normalizedTargetRect, text)
        if (fallbackResult.success) {
            return fallbackResult
        }
        return ExecutionResult(
            false,
            "Primary type failed: ${primaryResult.message}; targetRect fallback failed: ${fallbackResult.message}",
        )
    }

    suspend fun typeText(x: Int, y: Int, text: String): ExecutionResult {
        DbgLog.d("Executing typeText at x=$x, y=$y with text: $text", tag = "ACTION_DBG")
        val service = serviceOrNull() ?: return ExecutionResult(false, "Accessibility service disconnected.")

        // Approach 1: Pre-Click Node Anchoring
        // We find the exact node before the click happens, so even if the keyboard 
        // pushes the layout up and invalidates the (x,y) coordinates, we still have the reference.
        var preClickNode: AccessibilityNodeInfo? = null
        for (window in service.windows) {
            val node = findNodeAtPoint(window.root, x, y)
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isEditable || n.className?.contains("EditText") == true) {
                    preClickNode = n
                    DbgLog.d("Pre-click anchoring found editable node: class=${n.className}", tag = "ACTION_DBG")
                    break
                }
                n = n.parent
            }
            if (preClickNode != null) break
        }

        val clickResult = click(x, y)
        if (!clickResult.success) {
            DbgLog.e("typeText click failed", tag = "ACTION_DBG")
            return ExecutionResult(false, "Click to focus failed.")
        }
        
        var success = false
        var lastTargetNode: AccessibilityNodeInfo? = null
        
        for (i in 1..10) {
            kotlinx.coroutines.delay(300)
            
            var targetNode: AccessibilityNodeInfo? = preClickNode
            
            // 1. Try finding focused input
            if (targetNode == null) {
                for (window in service.windows) {
                    val f = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (f != null) {
                        targetNode = f
                        break
                    }
                }
            }
            
            // 2. Try finding editable nodes
            if (targetNode == null) {
                val editableNodes = mutableListOf<AccessibilityNodeInfo>()
                for (window in service.windows) {
                    editableNodes.addAll(findEditableNodes(window.root))
                }
                targetNode = editableNodes.firstOrNull { it.isFocused } ?: editableNodes.firstOrNull()
            }
            
            // 3. Try finding node at clicked point
            if (targetNode == null) {
                for (window in service.windows) {
                    val node = findNodeAtPoint(window.root, x, y)
                    if (node != null && (node.isEditable || node.className?.contains("EditText") == true)) {
                        targetNode = node
                        break
                    }
                }
            }
            
            if (targetNode != null) {
                lastTargetNode = targetNode
                targetNode.refresh()
                DbgLog.d("typeText attempt $i found target node: class=${targetNode.className}, isEditable=${targetNode.isEditable}", tag = "ACTION_DBG")

                success = setTextOrPaste(targetNode, text)
            }
            
            if (success) {
                DbgLog.d("typeText successful on attempt $i, submitting enter", tag = "ACTION_DBG")
                submitEnter(lastTargetNode)
                return ExecutionResult(true, "typeText(${text.length} chars)")
            }
        }
        
        DbgLog.e("typeText failed after 10 attempts. Last node class=${lastTargetNode?.className}", tag = "ACTION_DBG")
        return ExecutionResult(false, "Found input node but SET_TEXT and PASTE failed.")
    }

    private suspend fun typeTextByTargetRect(targetRect: Rect, text: String): ExecutionResult {
        val service = serviceOrNull()
            ?: return ExecutionResult(false, "Accessibility service disconnected.")
        val candidates = mutableListOf<EditableTargetCandidate>()

        for (window in service.windows) {
            for (node in findEditableNodes(window.root)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val overlapArea = intersectionArea(targetRect, bounds)
                if (overlapArea > 0L) {
                    candidates += EditableTargetCandidate(
                        node = node,
                        bounds = bounds,
                        overlapArea = overlapArea,
                    )
                }
            }
        }

        DbgLog.d(
            "typeText targetRect fallback candidates=${candidates.size} targetRect=${targetRect.toShortString()}",
            tag = "ACTION_DBG",
        )

        val best = candidates.maxByOrNull { it.overlapArea }
            ?: return ExecutionResult(false, "No editable node overlaps targetRect.")

        DbgLog.i(
            "typeText targetRect fallback selected bounds=${best.bounds.toShortString()} " +
                "overlapArea=${best.overlapArea} class=${best.node.className}",
            tag = "ACTION_DBG",
        )

        best.node.refresh()
        best.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        kotlinx.coroutines.delay(200)

        return if (setTextOrPaste(best.node, text)) {
            DbgLog.d("typeText targetRect fallback successful, submitting enter", tag = "ACTION_DBG")
            submitEnter(best.node)
            ExecutionResult(true, "typeText(${text.length} chars, targetRect fallback)")
        } else {
            ExecutionResult(false, "Selected editable node but SET_TEXT and PASTE failed.")
        }
    }

    private fun setTextOrPaste(targetNode: AccessibilityNodeInfo, text: String): Boolean {
        var success = false
        var current: AccessibilityNodeInfo? = targetNode
        while (current != null && !success) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            success = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) {
                current = current.parent
            }
        }

        if (!success) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)

            current = targetNode
            while (current != null && !success) {
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                success = current.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (!success) {
                    current = current.parent
                }
            }
        }

        return success
    }

    private suspend fun submitEnter(targetNode: AccessibilityNodeInfo?) {
        kotlinx.coroutines.delay(300)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val success = targetNode?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
            DbgLog.d("typeText submit enter success=$success", tag = "ACTION_DBG")
        }
    }

    private fun intersectionArea(first: Rect, second: Rect): Long {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return 0L
        return width.toLong() * height.toLong()
    }

    private fun normalizedRect(rect: Rect): Rect {
        return Rect(
            minOf(rect.left, rect.right),
            minOf(rect.top, rect.bottom),
            maxOf(rect.left, rect.right),
            maxOf(rect.top, rect.bottom),
        )
    }

    private fun findEditableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return list
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable || node.className?.contains("EditText") == true) {
                list.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return list
    }

    private fun findNodeAtPoint(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val found = findNodeAtPoint(child, x, y)
            if (found != null) return found
        }
        return root
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

    private data class EditableTargetCandidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val overlapArea: Long,
    )
}
