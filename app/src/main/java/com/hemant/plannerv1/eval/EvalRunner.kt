package com.hemant.plannerv1.eval

import android.os.SystemClock
import com.hemant.plannerv1.accessibility.GestureExecutor
import com.hemant.plannerv1.agent.ActionHistory
import com.hemant.plannerv1.agent.ActionRecord
import com.hemant.plannerv1.agent.UiActionType
import com.hemant.plannerv1.capture.ScreenCaptureManager
import com.hemant.plannerv1.capture.ScreenshotFrame
import com.hemant.plannerv1.agent.ExecutionResult
import com.hemant.plannerv1.agent.UiAction
import com.hemant.plannerv1.logging.DbgLog
import com.hemant.plannerv1.logging.StepLogEntry
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.model.GemmaModelManager
import com.hemant.plannerv1.model.ModelInputBuilder
import com.hemant.plannerv1.model.ModelOutputParser
import com.hemant.plannerv1.safety.SafetyController
import com.hemant.plannerv1.overlay.FloatingBarService
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
import java.io.File
import java.time.Instant
import kotlin.system.measureTimeMillis

/**
 * Evaluation runner that reads goals from a .txt file, executes each goal from the
 * home screen, and writes [EvalReportWriter] output files when all goals are complete.
 *
 * Design principles:
 *  - Does NOT touch or replace [com.hemant.plannerv1.agent.AgentOrchestrator]. It re-uses
 *    the same lower-level primitives (screenCaptureManager, modelManager, etc.) independently
 *    so the normal agent remains fully operational and its code is not modified.
 *  - Home-screen reset is hardcoded via [GestureExecutor.pressHome]; the model never sees it.
 *  - [modelInferenceLatencyMs] measures ONLY [GemmaModelManager.generate], excluding all
 *    UI delays, settle waits, capture time, and parse time.
 *  - Token counts use the chars/4 approximation (±5–10% accuracy for structured prompts).
 */
class EvalRunner(
    private val gestureExecutor: GestureExecutor,
    private val screenCaptureManager: ScreenCaptureManager,
    private val modelManager: GemmaModelManager,
    private val modelInputBuilder: ModelInputBuilder,
    private val modelOutputParser: ModelOutputParser,
    private val safetyController: SafetyController,
    private val testLogger: TestLogger,
    private val maxStepsPerGoal: Int = 20,
    private val goalTimeoutMs: Long = 5 * 60 * 1000L, // 5 minutes per goal
    private val outputDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EvalRunnerState(maxStepsPerGoal = maxStepsPerGoal))
    val state: StateFlow<EvalRunnerState> = _state.asStateFlow()

    private var job: Job? = null

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Starts the evaluation run. No-op if already running.
     * Results and reports are written to [outputDir] when complete.
     */
    fun start(goalsFile: File) {
        if (_state.value.isRunning) {
            DbgLog.w("EvalRunner.start() called while already running — ignored")
            return
        }
        DbgLog.i("EvalRunner starting with goals file: ${goalsFile.absolutePath}")
        job = scope.launch {
            runAll(goalsFile)
        }
    }

    /** Cancels the current run. The current step finishes cleanly then stops. */
    fun stop() {
        DbgLog.i("EvalRunner stop requested")
        job?.cancel(CancellationException("Stopped by user."))
        _state.update { it.copy(isRunning = false, currentStatus = "Stopped") }
    }

    // ── Main loop ──────────────────────────────────────────────────────────

    private suspend fun runAll(goalsFile: File) {
        val goals: List<String>
        try {
            goals = EvalGoalReader.readGoals(goalsFile)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(isRunning = false, lastError = e.message, currentStatus = "Failed: bad goals file") }
            return
        }

        val results = mutableListOf<EvalGoalResult>()
        _state.value = EvalRunnerState(
            isRunning = true,
            totalGoals = goals.size,
            currentStatus = "Starting",
            maxStepsPerGoal = maxStepsPerGoal,
        )

        try {
            for ((index, goal) in goals.withIndex()) {
                val goalNumber = index + 1
                DbgLog.i("EvalRunner ▶ Goal $goalNumber/${goals.size}: $goal")

                _state.update {
                    it.copy(
                        currentGoalNumber = goalNumber,
                        currentGoalText = goal,
                        currentStep = 0,
                        currentStatus = "Resetting to home screen…",
                    )
                }

                // ── HARDCODED HOME RESET — model does not see or execute this ──
                val homeResult: ExecutionResult
                try {
                    FloatingBarService.setCaptureVisibility(hidden = true)
                    delay(50)
                    homeResult = gestureExecutor.pressHome()
                } finally {
                    FloatingBarService.setCaptureVisibility(hidden = false)
                }
                if (!homeResult.success) {
                    DbgLog.e("EvalRunner home reset failed for goal $goalNumber: ${homeResult.message}")
                    val failedResult = EvalGoalResult(
                        goalNumber = goalNumber,
                        goal = goal,
                        status = "Failed: home reset",
                        totalSteps = 0,
                        avgInferenceMs = 0L,
                        steps = emptyList(),
                    )
                    results += failedResult
                    _state.update {
                        it.copy(
                            completedGoals = results.size,
                            results = results.toList(),
                            currentStatus = "Home reset failed, skipping goal",
                        )
                    }
                    continue
                }
                // Launcher settle delay — not counted in any latency measurement
                delay(1200)

                // ── Run the goal ──────────────────────────────────────────────
                val result = runSingleGoal(goalNumber, goal)
                results += result
                DbgLog.i("EvalRunner ✔ Goal $goalNumber done — ${result.status}")

                _state.update {
                    it.copy(
                        completedGoals = results.size,
                        results = results.toList(),
                        currentStatus = "Goal $goalNumber: ${result.status}",
                    )
                }

                // Brief pause between goals
                delay(500)
            }

            // ── Write reports ─────────────────────────────────────────────────
            outputDir.mkdirs()
            val jsonFile = File(outputDir, "report.json")
            val txtFile = File(outputDir, "reports.txt")
            EvalReportWriter.writeJson(results, jsonFile)
            EvalReportWriter.writeTxt(results, txtFile)

            _state.update {
                it.copy(
                    isRunning = false,
                    results = results.toList(),
                    outputDirPath = outputDir.absolutePath,
                    currentStatus = "Complete — ${results.count { r -> r.status == "Success" }}/${results.size} succeeded",
                )
            }
            DbgLog.i("EvalRunner complete. Reports: ${outputDir.absolutePath}")

        } catch (cancelled: CancellationException) {
            DbgLog.w("EvalRunner cancelled after ${results.size} goals")
            // Write partial reports if any goals completed
            if (results.isNotEmpty()) {
                outputDir.mkdirs()
                EvalReportWriter.writeJson(results, File(outputDir, "report.json"))
                EvalReportWriter.writeTxt(results, File(outputDir, "reports.txt"))
            }
            _state.update {
                it.copy(
                    isRunning = false,
                    results = results.toList(),
                    outputDirPath = if (results.isNotEmpty()) outputDir.absolutePath else null,
                    currentStatus = "Stopped by user",
                )
            }
        }
    }

    // ── Single goal loop ───────────────────────────────────────────────────

    private suspend fun runSingleGoal(goalNumber: Int, goal: String): EvalGoalResult {
        val steps = mutableListOf<EvalStepRecord>()
        val sessionId = testLogger.startSession(goal, maxStepsPerGoal)
        var history = ActionHistory()
        var finalStatus = "Failed: max steps"
        var invalidJsonCount = 0
        var consecutiveInvalidJsonCount = 0
        var lastError: String? = null
        val detectedTargetApp = gestureExecutor.detectTargetAppInGoal(goal)
        val resolvedTargetApp = detectedTargetApp?.appName?.let(gestureExecutor::resolveLaunchableApp)
        var attemptedAutoLaunch = false
        val deadline = SystemClock.elapsedRealtime() + goalTimeoutMs

        var step = 1
        while (step <= maxStepsPerGoal) {

            // ── Timeout guard ─────────────────────────────────────────────
            if (SystemClock.elapsedRealtime() > deadline) {
                DbgLog.w("EvalRunner goal $goalNumber timed out at step $step")
                finalStatus = "Failed: timeout"
                break
            }
            if (!attemptedAutoLaunch && resolvedTargetApp != null) {
                attemptedAutoLaunch = true
                if (!gestureExecutor.isForegroundApp(resolvedTargetApp.packageName)) {
                    _state.update {
                        it.copy(
                            currentStep = step,
                            currentStatus = "Goal $goalNumber | Step $step - Opening ${resolvedTargetApp.appName}",
                        )
                    }
                    DbgLog.i(
                        "EvalRunner auto-launch attempt goal=$goalNumber step=$step " +
                            "app=${resolvedTargetApp.appName} package=${resolvedTargetApp.packageName}",
                        tag = "ACTION_DBG",
                    )
                    val autoLaunchResult = gestureExecutor.openResolvedAppAndWait(
                        match = resolvedTargetApp,
                        clearTask = true,
                    )
                    DbgLog.i(
                        "EvalRunner auto-launch result goal=$goalNumber step=$step " +
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
                    "EvalRunner auto-launch skipped: target app already foreground " +
                        "package=${resolvedTargetApp.packageName}",
                    tag = "ACTION_DBG",
                )
            }

            _state.update {
                it.copy(
                    currentStep = step,
                    currentStatus = "Goal $goalNumber | Step $step — Capturing screen",
                )
            }

            var actionPerformed = "unknown"
            var reasoning = ""
            var inputTokensApprox = 0
            var outputTokensApprox = 0
            var inferenceMs = 0L
            var done = false
            var stepError: String? = null

            var frame: ScreenshotFrame? = null
            var rawOutput: String? = null
            var prompt: String? = null

            try {
                DbgLog.d("EvalRunner goal=$goalNumber step=$step")

                // 1. Capture screen (not timed)
                frame = screenCaptureManager.capture(sessionId, step)
                val currentAppContext = gestureExecutor.currentAppContext()

                // 2. Build prompt (not timed)
                val request = modelInputBuilder.build(
                    goal = goal,
                    history = history,
                    stepNumber = step,
                    maxSteps = maxStepsPerGoal,
                    frame = frame,
                    currentAppName = currentAppContext.preferredName,
                    currentPackageName = currentAppContext.packageName,
                    detectedTargetAppName = detectedTargetApp?.appName,
                    detectedTargetAppMatch = detectedTargetApp?.matchedText,
                    lastError = lastError,
                )
                prompt = request.prompt
                lastError = null // consumed — clear after passing to builder

                // 3. Approximate INPUT tokens — chars/4 heuristic
                //    Substitution point: replace with message.promptTokenCount if SDK exposes it.
                inputTokensApprox = (request.prompt.length / 4).coerceAtLeast(1)

                _state.update { it.copy(currentStatus = "Goal $goalNumber | Step $step — Running model") }

                // 4. Model inference — ONLY this block is timed ──────────────
                rawOutput = ""
                inferenceMs = measureTimeMillis {
                    rawOutput = modelManager.generate(request)
                }
                // ─────────────────────────────────────────────────────────────

                // 5. Approximate OUTPUT tokens — chars/4 heuristic
                //    Substitution point: replace with message.completionTokenCount if SDK exposes it.
                outputTokensApprox = (rawOutput!!.length / 4).coerceAtLeast(1)

                DbgLog.d(
                    "EvalRunner goal=$goalNumber step=$step inferenceMs=$inferenceMs " +
                        "inTok≈$inputTokensApprox outTok≈$outputTokensApprox",
                )

                // 6. Parse output (not timed)
                val parsedAction = modelOutputParser.parse(rawOutput!!)
                consecutiveInvalidJsonCount = 0
                reasoning = parsedAction.reason
                actionPerformed = formatAction(parsedAction)
                done = parsedAction.done || parsedAction.type == UiActionType.DONE

                // ── Scroll-repeat: execute but inject feedback, do NOT stop ──────────
                if (safetyController.isRepeatedScroll(history, parsedAction)) {
                    DbgLog.w("EvalRunner repeated scroll at goal=$goalNumber step=$step — injecting feedback")
                    lastError = "You have scrolled twice in a row. " +
                        "Scrolling again is unlikely to help. " +
                        "Please try a different action: click a visible element, " +
                        "type_text, back, wait, or done."
                    // still execute the scroll so the screen actually moves
                }

                // ── Hard stop: non-scroll identical repeated action ───────────────────
                if (!safetyController.isRepeatedScroll(history, parsedAction) &&
                    safetyController.isRepeatedAction(history, parsedAction)) {
                    stepError = "Repeated identical action: ${parsedAction.signature()}"
                    DbgLog.w("EvalRunner repeated action at goal=$goalNumber step=$step: $stepError")
                    steps += EvalStepRecord(
                        stepNumber = step,
                        actionPerformed = actionPerformed,
                        reasoning = reasoning,
                        inputTokensApprox = inputTokensApprox,
                        outputTokensApprox = outputTokensApprox,
                        modelInferenceLatencyMs = inferenceMs,
                        done = false,
                        error = stepError,
                    )
                    logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, null, inferenceMs, stepError)
                    testLogger.completeSession(sessionId, "Failed: repeated action", invalidJsonCount, stepError)
                    return buildResult(goalNumber, goal, "Failed: repeated action", steps)
                }

                // 8. Execute UI action (not timed — UI delays happen here, after inference)
                _state.update { it.copy(currentStatus = "Goal $goalNumber | Step $step — Executing ${parsedAction.type.value}") }
                // frame is always non-null here: capture() above either succeeds or throws,
                // in which case the inner catch block handles it before we reach this line.
                var markerX: Int? = null
                var markerY: Int? = null
                var markerBounds: android.graphics.Rect? = null
                val box = parsedAction.boundingBox
                if (box != null && box.size >= 4) {
                    val top = frame!!.mapModelYToScreen(box[0])
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

                val execution: ExecutionResult
                try {
                    FloatingBarService.setCaptureVisibility(hidden = true)
                    delay(50)
                    execution = execute(parsedAction, frame!!)
                } finally {
                    FloatingBarService.setCaptureVisibility(hidden = false)
                    FloatingBarService.hideActionMarker()
                }

                // 9. Update history
                history = history.append(
                    ActionRecord(
                        stepNumber = step,
                        action = parsedAction,
                        executionResult = execution,
                        screenshotPath = frame.originalPath,
                        latencyMs = inferenceMs, // store inference-only latency in history record
                    )
                )

                // 10. Persist to existing JSONL infrastructure (unchanged)
                logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, execution, inferenceMs, null)

            } catch (parseError: IllegalArgumentException) {
                // Bad JSON from model — count against threshold
                invalidJsonCount++
                consecutiveInvalidJsonCount++
                stepError = parseError.message ?: "Invalid JSON output"
                lastError = stepError
                actionPerformed = "PARSE_ERROR"
                DbgLog.w(
                    "EvalRunner invalid JSON goal=$goalNumber step=$step total=$invalidJsonCount " +
                        "consecutive=$consecutiveInvalidJsonCount error=$stepError",
                )
                logStep(sessionId, goal, step, frame, prompt, rawOutput, null, null, inferenceMs, stepError)

                steps += EvalStepRecord(
                    stepNumber = step,
                    actionPerformed = actionPerformed,
                    reasoning = "",
                    inputTokensApprox = inputTokensApprox,
                    outputTokensApprox = outputTokensApprox,
                    modelInferenceLatencyMs = inferenceMs,
                    done = false,
                    error = stepError,
                )

                if (safetyController.hasExhaustedInvalidJsonRetries(consecutiveInvalidJsonCount)) {
                    finalStatus = "Failed: invalid JSON"
                    testLogger.completeSession(sessionId, finalStatus, invalidJsonCount, stepError)
                    return buildResult(goalNumber, goal, finalStatus, steps)
                }

                DbgLog.i(
                    "Retrying eval goal=$goalNumber step=$step with parser feedback " +
                        "retry=$consecutiveInvalidJsonCount/${safetyController.maxInvalidJson}",
                )
                delay(safetyController.actionDelayMs) // not counted in latency
                continue

            } catch (cancelled: CancellationException) {
                DbgLog.w("EvalRunner cancelled during goal=$goalNumber step=$step")
                testLogger.completeSession(sessionId, "Stopped", invalidJsonCount, "Stopped by user.")
                throw cancelled // re-throw so runAll() catches it

            } catch (t: Throwable) {
                stepError = t.message ?: t::class.java.simpleName
                DbgLog.e("EvalRunner fatal error goal=$goalNumber step=$step: $stepError", t)
                logStep(sessionId, goal, step, frame, null, rawOutput, null, null, inferenceMs, stepError)
                steps += EvalStepRecord(
                    stepNumber = step,
                    actionPerformed = actionPerformed,
                    reasoning = reasoning,
                    inputTokensApprox = inputTokensApprox,
                    outputTokensApprox = outputTokensApprox,
                    modelInferenceLatencyMs = inferenceMs,
                    done = false,
                    error = stepError,
                )
                testLogger.completeSession(sessionId, "Failed: error", invalidJsonCount, stepError)
                return buildResult(goalNumber, goal, "Failed: error", steps)
            }

            // Record the step after all successful processing
            steps += EvalStepRecord(
                stepNumber = step,
                actionPerformed = actionPerformed,
                reasoning = reasoning,
                inputTokensApprox = inputTokensApprox,
                outputTokensApprox = outputTokensApprox,
                modelInferenceLatencyMs = inferenceMs,
                done = done,
                error = stepError,
            )

            if (done) {
                finalStatus = "Success"
                break
            }

            // Settle delay — mirrors AgentOrchestrator's logic; NOT counted in latency
            delay(settleDelayFor(history.records.lastOrNull()?.action))
            step++
        }

        testLogger.completeSession(sessionId, finalStatus, invalidJsonCount, steps.lastOrNull()?.error)
        return buildResult(goalNumber, goal, finalStatus, steps)
    }

    // ── Action execution (mirrors AgentOrchestrator.execute) ──────────────

    private suspend fun execute(action: UiAction, frame: ScreenshotFrame): ExecutionResult {
        return when (action.type) {
            UiActionType.CLICK -> {
                val box = action.boundingBox ?: return ExecutionResult(false, "No bounding box")
                val screenX = frame.mapModelXToScreen(((box[1] + box[3]) / 2.0))
                val screenY = frame.mapModelYToScreen(((box[0] + box[2]) / 2.0))
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
            UiActionType.OPEN_APP -> gestureExecutor.openApp(action.appName.orEmpty(), clearTask = true)
            UiActionType.WAIT -> ExecutionResult(true, "wait()")
            UiActionType.BACK -> gestureExecutor.back()
            UiActionType.DONE -> ExecutionResult(true, "done()")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun formatAction(action: UiAction): String = when (action.type) {
        UiActionType.TYPE_TEXT -> "type_text(${action.text.orEmpty()})"
        UiActionType.OPEN_APP -> "open_app(${action.appName.orEmpty()})"
        else -> action.type.value
    }

    private fun buildResult(
        goalNumber: Int,
        goal: String,
        status: String,
        steps: List<EvalStepRecord>,
    ): EvalGoalResult {
        val avg = if (steps.isEmpty()) 0L
        else steps.map { it.modelInferenceLatencyMs }.average().toLong()
        return EvalGoalResult(goalNumber, goal, status, steps.size, avg, steps)
    }

    /**
     * Settle delays matching AgentOrchestrator's timing so the device has time to render
     * before the next screenshot capture. These are NOT included in latency.
     */
    private fun settleDelayFor(action: UiAction?): Long {
        val base = safetyController.actionDelayMs
        val settle = when (action?.type) {
            UiActionType.OPEN_APP -> 2000L
            UiActionType.WAIT -> 2000L
            UiActionType.TYPE_TEXT -> 1000L
            UiActionType.CLICK -> 800L
            UiActionType.SCROLL_UP, UiActionType.SCROLL_DOWN -> 600L
            UiActionType.BACK -> 800L
            else -> 0L
        }
        return maxOf(base, settle)
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
        latencyMs: Long,
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
                latencyMs = latencyMs,   // inference-only latency stored here
                error = error,
            )
        )
    }
}
