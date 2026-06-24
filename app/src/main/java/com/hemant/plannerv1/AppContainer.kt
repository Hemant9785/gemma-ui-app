package com.hemant.plannerv1

import android.content.Context
import com.hemant.plannerv1.accessibility.GestureExecutor
import com.hemant.plannerv1.agent.AgentOrchestrator
import com.hemant.plannerv1.capture.ScreenCaptureManager
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.model.BackendConfig
import com.hemant.plannerv1.model.GemmaModelManager
import com.hemant.plannerv1.model.ModelInputBuilder
import com.hemant.plannerv1.model.ModelOutputParser
import com.hemant.plannerv1.permissions.PermissionManager
import com.hemant.plannerv1.safety.SafetyController
import com.hemant.plannerv1.eval.EvalRunner
import com.hemant.plannerv1.logging.DbgLog
import com.hemant.plannerv1.model.PromptInjectionManager
import java.io.File

object AppContainer {
    lateinit var appContext: Context
        private set
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var screenCaptureManager: ScreenCaptureManager
        private set
    lateinit var gestureExecutor: GestureExecutor
        private set
    lateinit var backendConfig: BackendConfig
        private set
    lateinit var modelManager: GemmaModelManager
        private set
    lateinit var modelInputBuilder: ModelInputBuilder
        private set
    lateinit var modelOutputParser: ModelOutputParser
        private set
    lateinit var safetyController: SafetyController
        private set
    lateinit var testLogger: TestLogger
        private set
    lateinit var agentOrchestrator: AgentOrchestrator
        private set
    lateinit var promptInjectionManager: PromptInjectionManager
        private set
    lateinit var evalRunner: EvalRunner
        private set

    fun initialize(context: Context) {
        if (::appContext.isInitialized) {
            DbgLog.d("AppContainer initialize skipped: already initialized")
            return
        }

        DbgLog.i("AppContainer initialize start")
        appContext = context.applicationContext
        permissionManager = PermissionManager(appContext)
        screenCaptureManager = ScreenCaptureManager(appContext)
        gestureExecutor = GestureExecutor(appContext)
        backendConfig = BackendConfig(appContext)
        modelManager = GemmaModelManager(appContext, backendConfig)
        modelInputBuilder = ModelInputBuilder()
        modelOutputParser = ModelOutputParser()
        safetyController = SafetyController()
        testLogger = TestLogger(appContext)
        promptInjectionManager = PromptInjectionManager(appContext)
        evalRunner = EvalRunner(
            gestureExecutor = gestureExecutor,
            screenCaptureManager = screenCaptureManager,
            modelManager = modelManager,
            modelInputBuilder = modelInputBuilder,
            modelOutputParser = modelOutputParser,
            safetyController = safetyController,
            testLogger = testLogger,
            maxStepsPerGoal = 20,
            goalTimeoutMs = 5 * 60 * 1000L,
            outputDir = File(appContext.getExternalFilesDir(null), "eval_reports"),
        )
        agentOrchestrator = AgentOrchestrator(
            screenCaptureManager = screenCaptureManager,
            modelInputBuilder = modelInputBuilder,
            modelManager = modelManager,
            modelOutputParser = modelOutputParser,
            gestureExecutor = gestureExecutor,
            safetyController = safetyController,
            testLogger = testLogger,
        )
        DbgLog.i("AppContainer initialize complete")
    }
}
