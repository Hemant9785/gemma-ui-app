package com.hemant.plannerv1.agent

import android.os.SystemClock
import com.hemant.plannerv1.accessibility.GestureExecutor
import com.hemant.plannerv1.capture.ScreenCaptureManager
import com.hemant.plannerv1.capture.ScreenshotFrame
import com.hemant.plannerv1.logging.StepLogEntry
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.model.GemmaModelManager
import com.hemant.plannerv1.model.ModelInputBuilder
import com.hemant.plannerv1.model.ModelOutputParser
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
        var history = ActionHistory()
        var invalidJsonCount = 0
        _state.value = AgentState(
            isRunning = true,
            goal = goal,
            sessionId = sessionId,
            maxSteps = maxSteps,
            status = "Starting",
        )

        try {
            for (step in 1..maxSteps) {
                ensureNotStopped()
                val packageName = gestureExecutor.currentPackageName()
                if (safetyController.isPackageBlocked(packageName)) {
                    finish(
                        sessionId = sessionId,
                        status = "Blocked",
                        invalidJsonCount = invalidJsonCount,
                        error = "Blocked package: $packageName",
                    )
                    return
                }

                _state.update {
                    it.copy(
                        currentStep = step,
                        status = "Capturing screen",
                        history = history,
                        invalidJsonCount = invalidJsonCount,
                    )
                }

                val startedAt = SystemClock.elapsedRealtime()
                var frame: ScreenshotFrame? = null
                var prompt: String? = null
                var rawOutput: String? = null
                var parsedAction: UiAction? = null
                var execution: ExecutionResult? = null
                var error: String? = null

                try {
                    frame = screenCaptureManager.capture(sessionId, step)
                    val request = modelInputBuilder.build(goal, history, step, maxSteps, frame)
                    prompt = request.prompt
                    _state.update { it.copy(status = "Running Gemma") }
                    rawOutput = modelManager.generate(request)
                    parsedAction = modelOutputParser.parse(rawOutput)

                    if (safetyController.isRepeatedAction(history, parsedAction)) {
                        error = "Repeated identical action: ${parsedAction.signature()}"
                        val latency = SystemClock.elapsedRealtime() - startedAt
                        logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, null, latency, error)
                        finish(sessionId, "Failed: repeated action", invalidJsonCount, error)
                        return
                    }

                    _state.update { it.copy(status = "Executing ${parsedAction.type.value}") }
                    execution = execute(parsedAction, frame)
                    val latency = SystemClock.elapsedRealtime() - startedAt
                    val record = ActionRecord(
                        stepNumber = step,
                        action = parsedAction,
                        executionResult = execution,
                        screenshotPath = frame.originalPath,
                        latencyMs = latency,
                    )
                    history = history.append(record)
                    logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, execution, latency, null)
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
                        finish(sessionId, "Failed: execution", invalidJsonCount, execution.message)
                        return
                    }
                    delay(safetyController.actionDelayMs)
                } catch (parseError: IllegalArgumentException) {
                    invalidJsonCount += 1
                    error = parseError.message ?: "Invalid JSON output."
                    val latency = SystemClock.elapsedRealtime() - startedAt
                    logStep(sessionId, goal, step, frame, prompt, rawOutput, null, null, latency, error)
                    _state.update {
                        it.copy(
                            invalidJsonCount = invalidJsonCount,
                            status = "Invalid JSON",
                            lastError = error,
                        )
                    }
                    if (invalidJsonCount >= safetyController.maxInvalidJson) {
                        finish(sessionId, "Failed: invalid JSON", invalidJsonCount, error)
                        return
                    }
                    delay(safetyController.actionDelayMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    error = throwable.message ?: throwable::class.java.simpleName
                    val latency = SystemClock.elapsedRealtime() - startedAt
                    logStep(sessionId, goal, step, frame, prompt, rawOutput, parsedAction, execution, latency, error)
                    finish(sessionId, "Failed: error", invalidJsonCount, error)
                    return
                }
            }
            finish(sessionId, "Failed: max steps", invalidJsonCount, "Reached max steps.")
        } catch (cancelled: CancellationException) {
            testLogger.completeSession(sessionId, "Stopped", invalidJsonCount, "Stopped by user.")
        }
    }

    private suspend fun execute(action: UiAction, frame: ScreenshotFrame): ExecutionResult {
        return when (action.type) {
            UiActionType.CLICK -> gestureExecutor.click(
                frame.mapModelXToScreen(action.x ?: 0.0),
                frame.mapModelYToScreen(action.y ?: 0.0),
            )
            UiActionType.TYPE -> gestureExecutor.type(action.text.orEmpty())
            UiActionType.SWIPE -> gestureExecutor.swipe(action.direction ?: SwipeDirection.UP)
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
