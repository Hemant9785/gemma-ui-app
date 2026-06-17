package com.hemant.plannerv1.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hemant.plannerv1.agent.ExecutionResult
import com.hemant.plannerv1.logging.DbgLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GestureExecutor(private val context: Context) {
    fun currentPackageName(): String? {
        return serviceOrNull()?.rootInActiveWindow?.packageName?.toString()
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

    fun openApp(appName: String): ExecutionResult {
        val pm = context.packageManager
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(launcherIntent, 0)
        
        var bestScore = 0
        var bestPackage: String? = null
        
        for (info in activities) {
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            
            val score = when {
                label.equals(appName, ignoreCase = true) -> 100
                label.startsWith(appName, ignoreCase = true) -> 80
                label.contains(appName, ignoreCase = true) -> 60
                appName.contains(label, ignoreCase = true) -> 50
                pkg.contains(appName, ignoreCase = true) -> 40
                else -> {
                    val words = appName.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    var windowScore = 0
                    if (words.size >= 2) {
                        for (i in 0 until words.size - 1) {
                            val window = "${words[i]} ${words[i+1]}"
                            if (label.contains(window, ignoreCase = true) || pkg.contains(window, ignoreCase = true)) {
                                windowScore = 30
                                break
                            }
                        }
                    }
                    windowScore
                }
            }
            
            if (score > bestScore) {
                bestScore = score
                bestPackage = pkg
            }
        }
        
        if (bestPackage != null) {
            val intent = pm.getLaunchIntentForPackage(bestPackage)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                DbgLog.d("Opening app $appName matched $bestPackage with score $bestScore", tag = "ACTION_DBG")
                context.startActivity(intent)
                return ExecutionResult(true, "openApp($appName)")
            }
        }
        
        return ExecutionResult(false, "App not found or cannot be launched: $appName")
    }

    fun back(): ExecutionResult {
        val service = serviceOrNull()
            ?: return ExecutionResult(false, "Accessibility service is not connected.")
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        DbgLog.d("Executing back action, success=$success", tag = "ACTION_DBG")
        return ExecutionResult(success, if (success) "back()" else "Back action failed.")
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
            }
            
            if (success) {
                DbgLog.d("typeText successful on attempt $i, submitting enter", tag = "ACTION_DBG")
                kotlinx.coroutines.delay(300)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    lastTargetNode?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                return ExecutionResult(true, "typeText(${text.length} chars)")
            }
        }
        
        DbgLog.e("typeText failed after 10 attempts. Last node class=${lastTargetNode?.className}", tag = "ACTION_DBG")
        return ExecutionResult(false, "Found input node but SET_TEXT and PASTE failed.")
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
}
