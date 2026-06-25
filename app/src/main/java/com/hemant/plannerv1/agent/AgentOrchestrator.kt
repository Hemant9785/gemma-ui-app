package com.hemant.plannerv1.agent

import com.hemant.plannerv1.accessibility.GestureExecutor
import com.hemant.plannerv1.capture.ScreenCaptureManager
import com.hemant.plannerv1.capture.ScreenshotFrame
import com.hemant.plannerv1.logging.StepLogEntry
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.logging.DbgLog
import com.hemant.plannerv1.logging.DbgLog.preview
import com.hemant.plannerv1.logging.DbgLog.summary
import com.hemant.plannerv1.model.GemmaModelManager
import com.hemant.plannerv1.model.ModelInputBuilder
import com.hemant.plannerv1.model.ModelOutputParser
import com.hemant.plannerv1.overlay.FloatingBarService
import com.hemant.plannerv1.safety.SafetyController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

class AgentOrchestrator(
    private val screenCaptureManager: ScreenCaptureManager,
    private val modelInputBuilder: ModelInputBuilder,
    private val modelManager: GemmaModelManager,
    private val modelOutputParser: ModelOutputParser,
    private val gestureExecutor: GestureExecutor,
    private val safetyController: SafetyController,
    private val testLogger: TestLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopRequested = AtomicBoolean(false)
    private val _state = MutableStateFlow(AgentState(maxSteps = safetyController.maxSteps))
    val state: StateFlow<AgentState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(goal: String, maxSteps: Int = _state.value.maxSteps) {
        if (goal.isBlank()) {
            _state.update { it.copy(lastError = "Enter a goal before running.") }
            return
        }
        if (_state.value.isRunning) return
        DbgLog.i("Agent start requested goalChars=${goal.length} maxSteps=$maxSteps goalPreview=${preview(goal)}")
        stopRequested.set(false)
        job = scope.launch {
            runLoop(goal.trim(), maxSteps.coerceIn(1, safetyController.maxSteps))
        }
    }

    fun setMaxSteps(maxSteps: Int) {
        if (_state.value.isRunning) return
        _state.update { it.copy(maxSteps = maxSteps.coerceIn(1, safetyController.maxSteps)) }
    }

    fun stop() {
        stopRequested.set(true)
        job?.cancel(CancellationException("User stopped the agent."))
        _state.update {
            it.copy(
                isRunning = false,
                status = "Stopped",
                lastError = "Stopped by user.",
            )
        }
    }

    private suspend fun runLoop(goal: String, maxSteps: Int) {
        val sessionId = testLogger.startSession(goal, maxSteps)
        DbgLog.i("Agent session start sessionId=$sessionId goalChars=${goal.length} maxSteps=$maxSteps")
        var history = ActionHistory()
        var invalidJsonCount = 0
        var consecutiveInvalidJsonCount = 0
        var lastError: String? = null
        val detectedTargetApp = gestureExecutor.detectTargetAppInGoal(goal)
        val resolvedTargetApp = detectedTargetApp?.appName?.let(gestureExecutor::resolveLaunchableApp)
        var attemptedAutoLaunch = false
        _state.value = AgentState(
            isRunning = true,
            goal = goal,
            sessionId = sessionId,
            maxSteps = maxSteps,
            status = "Starting",
        )

        try {
            var step = 1
            while (step <= maxSteps) {
                ensureNotStopped()
                val appContext = gestureExecutor.currentAppContext()
                if (safetyController.isAppBlocked(appContext.appName, appContext.packageName)) {
                    finish(
                        sessionId = sessionId,
                        status = "Blocked",
                        invalidJsonCount = invalidJsonCount,
                        error = "Blocked app: ${appContext.preferredName}",
                    )
                    return
                }
                if (!attemptedAutoLaunch && resolvedTargetApp != null) {
                    attemptedAutoLaunch = true
                    if (!gestureExecutor.isForegroundApp(resolvedTargetApp.packageName)) {
                        _state.update {
                            it.copy(
                                currentStep = step,
                                status = "Opening ${resolvedTargetApp.appName}",
                                history = history,
                                invalidJsonCount = invalidJsonCount,
                            )
                        }
                        DbgLog.i(
                            "Agent auto-launch attempt sessionId=$sessionId step=$step " +
                                "app=${resolvedTargetApp.appName} package=${resolvedTargetApp.packageName}",
                            tag = "ACTION_DBG",
                        )
                        val autoLaunchResult = gestureExecutor.openResolvedAppAndWait(resolvedTargetApp)
                        DbgLog.i(
                            "Agent auto-launch result sessionId=$sessionId step=$step " +
                                "success=${autoLaunchResult.success} message=${autoLaunchResult.message}",
                            tag = "ACTION_DBG",
                        )
                        if (!autoLaunchResult.success) {
                            lastError = autoLaunchResult.message
                        }
                        delay(maxOf(safetyController.actionDelayMs, 300L))
                        continue
                    }
                    DbgLog.i(
                        "Agent auto-launch skipped: target app already foreground package=${resolvedTargetApp.packageName}",
                        tag = "ACTION_DBG",
                    )
                }

                _state.update {
                    it.copy(
                        currentStep = step,
                        status = "Capturing screen",
                        history = history,
                        invalidJsonCount = invalidJsonCount,
                    )
                }

                var frame: ScreenshotFrame? = null
                var prompt: String? = null
                var rawOutput: String? = null
                var parsedAction: UiAction? = null
                var execution: ExecutionResult? = null
                var error: String? = null
                var inferenceMs = 0L

                try {
                    DbgLog.d(
                        "Agent step start sessionId=$sessionId step=$step historySize=${history.records.size}",
                    )
                    frame = screenCaptureManager.capture(sessionId, step)
                    val currentAppContext = gestureExecutor.currentAppContext()
                    val request = modelInputBuilder.build(
                        goal = goal,
                        history = history,
                        stepNumber = step,
                        maxSteps = maxSteps,
                        frame = frame,
                        currentAppName = currentAppContext.preferredName,
                        currentPackageName = currentAppContext.packageName,
                        detectedTargetAppName = detectedTargetApp?.appName,
                        detectedTargetAppMatch = detectedTargetApp?.matchedText,
                        lastError = lastError,
                    )
                    lastError = null // consumed — clear after passing to builder
                    prompt = request.prompt
                    DbgLog.d(
                        "Agent prompt ready sessionId=$sessionId step=$step promptChars=${prompt?.length} " +
                            "promptPreview=${preview(prompt)}",
                    )
                    DbgLog.d("FULL PROMPT:\n$prompt", tag = "MODEL_DBG")
                    _state.update { it.copy(status = "Running Gemma") }
                    rawOutput = ""
                    inferenceMs = measureTimeMillis {
                        rawOutput = modelManager.generate(request)
                    }
                    DbgLog.d(
                        "Agent model output sessionId=$sessionId step=$step rawChars=${rawOutput?.length} " +
                            "rawPreview=${preview(rawOutput)}",
                    )
                    DbgLog.d("FULL OUTPUT:\n$rawOutput", tag = "MODEL_DBG")
                    parsedAction = modelOutputParser.parse(rawOutput ?: "")
                    consecutiveInvalidJsonCount = 0

                    // ── Scroll-repeat: execute but inject feedback, do NOT stop ──────────
                    if (safetyController.isRepeatedScroll(history, parsedAction)) {
                        DbgLog.w("Repeated scroll detected at step=$step — executing and injecting feedback")
                        lastError = "You have scrolled twice in a row. " +
                            "Scrolling again is unlikely to help. " +
                            "Please try a different action: click a visible element, " +
                            "type_text, back, wait, or done."
                        // still execute the scroll so the screen actually moves
                    }

                    // ── Hard stop: non-scroll identical repeated action ───────────────────
                    if (!safetyController.isRepeatedScroll(history, parsedAction) &&
                        safetyController.isRepeatedAction(history, parsedAction)) {
                        error = "Repeated identical action: ${parsedAction.signature()}"
                        logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, null, inferenceMs, error)
                        finish(sessionId, "Failed: repeated action", invalidJsonCount, error)
                        return
                    }

                    _state.update { it.copy(status = "Executing ${parsedAction.type.value}") }
                    
                    var markerX: Int? = null
                    var markerY: Int? = null
                    var markerBounds: android.graphics.Rect? = null
                    val box = parsedAction.boundingBox
                    if (box != null && box.size >= 4) {
                        val top = frame.mapModelYToScreen(box[0])
                        val left = frame.mapModelXToScreen(box[1])
                        val bottom = frame.mapModelYToScreen(box[2])
                        val right = frame.mapModelXToScreen(box[3])
                        markerBounds = android.graphics.Rect(left, top, right, bottom)
                        markerX = (left + right) / 2
                        markerY = (top + bottom) / 2
                    }
                    val actionText = when (parsedAction.type) {
                        UiActionType.OPEN_APP -> "open_app(${parsedAction.appName ?: ""})"
                        UiActionType.TYPE_TEXT -> "type_text(${parsedAction.text ?: ""})"
                        else -> parsedAction.type.value
                    }
                    FloatingBarService.showActionMarker(
                        text = actionText,
                        x = markerX,
                        y = markerY,
                        bounds = markerBounds
                    )
                    delay(300)

                    try {
                        FloatingBarService.setCaptureVisibility(hidden = true)
                        delay(50)
                        execution = execute(parsedAction, frame)
                    } finally {
                        FloatingBarService.setCaptureVisibility(hidden = false)
                    }
                    DbgLog.i(
                        "Agent execution sessionId=$sessionId step=$step action=${parsedAction.type.value} " +
                            "success=${execution.success} message=${execution.message}",
                    )
                    val record = ActionRecord(
                        stepNumber = step,
                        action = parsedAction,
                        executionResult = execution,
                        screenshotPath = frame.originalPath,
                        latencyMs = inferenceMs,
                    )
                    history = history.append(record)
                    logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, execution, inferenceMs, null)
                    _state.update {
                        it.copy(
                            history = history,
                            currentStep = step,
                            status = if (execution.success) "Step $step complete" else "Execution failed",
                            lastError = if (execution.success) null else execution.message,
                        )
                    }

                    if (parsedAction.type == UiActionType.DONE || parsedAction.done) {
                        finish(sessionId, "Success", invalidJsonCount, null)
                        return
                    }
                    if (!execution.success) {
                        DbgLog.w("Action failed, continuing to next step for recovery. error=${execution.message}")
                        lastError = execution.message
                        delay(safetyController.actionDelayMs)
                        FloatingBarService.hideActionMarker()
                        step += 1
                        continue
                    }
                    val settleDelay = when (parsedAction.type) {
                        UiActionType.OPEN_APP -> 2000L
                        UiActionType.WAIT -> 2000L
                        UiActionType.CLICK -> 800L
                        UiActionType.TYPE_TEXT -> 1000L
                        UiActionType.SCROLL_UP, UiActionType.SCROLL_DOWN -> 600L
                        UiActionType.BACK -> 800L
                        else -> 0L
                    }
                    val finalDelay = maxOf(safetyController.actionDelayMs, settleDelay)
                    DbgLog.d("Waiting $finalDelay ms for action ${parsedAction.type.value} to settle...")
                    delay(finalDelay)
                    FloatingBarService.hideActionMarker()
                    step += 1
                } catch (parseError: IllegalArgumentException) {
                    invalidJsonCount += 1
                    consecutiveInvalidJsonCount += 1
                    error = parseError.message ?: "Invalid JSON output."
                    lastError = error
                    DbgLog.w(
                        "Agent invalid json sessionId=$sessionId step=$step total=$invalidJsonCount " +
                            "consecutive=$consecutiveInvalidJsonCount " +
                            "error=$error rawPreview=${preview(rawOutput)}",
                        parseError,
                    )
                    logStep(sessionId, goal, step, frame, prompt, rawOutput, null, null, inferenceMs, error)
                    _state.update {
                        it.copy(
                            invalidJsonCount = invalidJsonCount,
                            status = "Invalid JSON",
                            lastError = error,
                        )
                    }
                    if (safetyController.hasExhaustedInvalidJsonRetries(consecutiveInvalidJsonCount)) {
                        finish(sessionId, "Failed: invalid JSON", invalidJsonCount, error)
                        return
                    }
                    DbgLog.i(
                        "Retrying agent step=$step with parser feedback " +
                            "retry=$consecutiveInvalidJsonCount/${safetyController.maxInvalidJson}",
                    )
                    delay(safetyController.actionDelayMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    error = throwable.message ?: throwable::class.java.simpleName
                    DbgLog.e(
                        "Agent step failed sessionId=$sessionId step=$step error=$error rawPreview=${preview(rawOutput)}",
                        throwable,
                    )
                    logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, execution, inferenceMs, error)
                    finish(sessionId, "Failed: error", invalidJsonCount, error)
                    return
                }
            }
            finish(sessionId, "Failed: max steps", invalidJsonCount, "Reached max steps.")
        } catch (cancelled: CancellationException) {
            DbgLog.w("Agent session cancelled sessionId=$sessionId invalidJsonCount=$invalidJsonCount")
            testLogger.completeSession(sessionId, "Stopped", invalidJsonCount, "Stopped by user.")
        }
    }

    private suspend fun execute(action: UiAction, frame: ScreenshotFrame): ExecutionResult {
        return when (action.type) {
            UiActionType.CLICK -> {
                val box = action.boundingBox ?: return ExecutionResult(false, "No bounding box")
                val centerY = (box[0] + box[2]) / 2.0
                val centerX = (box[1] + box[3]) / 2.0
                val screenX = frame.mapModelXToScreen(centerX)
                val screenY = frame.mapModelYToScreen(centerY)
                gestureExecutor.click(screenX, screenY)
            }
            UiActionType.TYPE_TEXT -> {
                val box = action.boundingBox ?: return ExecutionResult(false, "No bounding box")
                val top = frame.mapModelYToScreen(box[0])
                val left = frame.mapModelXToScreen(box[1])
                val bottom = frame.mapModelYToScreen(box[2])
                val right = frame.mapModelXToScreen(box[3])
                val targetRect = android.graphics.Rect(left, top, right, bottom)
                gestureExecutor.typeText(targetRect, action.text.orEmpty())
            }
            UiActionType.SCROLL_UP -> gestureExecutor.scrollUp()
            UiActionType.SCROLL_DOWN -> gestureExecutor.scrollDown()
            UiActionType.OPEN_APP -> gestureExecutor.openApp(action.appName.orEmpty())
            UiActionType.WAIT -> ExecutionResult(true, "waiting")
            UiActionType.BACK -> gestureExecutor.back()
            UiActionType.DONE -> ExecutionResult(true, "done()")
        }
    }

    private fun finish(
        sessionId: String,
        status: String,
        invalidJsonCount: Int,
        error: String?,
    ) {
        FloatingBarService.hideActionMarker()
        testLogger.completeSession(sessionId, status, invalidJsonCount, error)
        _state.update {
            it.copy(
                isRunning = false,
                status = status,
                invalidJsonCount = invalidJsonCount,
                lastError = error,
            )
        }
    }

    private fun ensureNotStopped() {
        if (stopRequested.get()) throw CancellationException("Stopped by user.")
    }

    private fun logStep(
        sessionId: String,
        goal: String,
        step: Int,
        frame: ScreenshotFrame?,
        prompt: String?,
        rawOutput: String?,
        parsedAction: UiAction?,
        execution: ExecutionResult?,
        latency: Long,
        error: String?,
    ) {
        testLogger.logStep(
            StepLogEntry(
                timestamp = Instant.now().toString(),
                sessionId = sessionId,
                goal = goal,
                stepNumber = step,
                screenshotPath = frame?.originalPath,
                prompt = prompt,
                rawModelOutput = rawOutput,
                parsedAction = parsedAction,
                executionResult = execution?.message,
                executionSuccess = execution?.success ?: false,
                latencyMs = latency,
                error = error,
            ),
        )
    }
}
