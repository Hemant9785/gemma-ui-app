package com.hemant.plannerv1

import android.content.Context
import com.hemant.plannerv1.accessibility.GestureExecutor
import com.hemant.plannerv1.agent.AgentOrchestrator
import com.hemant.plannerv1.capture.ScreenCaptureManager
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.model.GemmaModelManager
import com.hemant.plannerv1.model.ModelInputBuilder
import com.hemant.plannerv1.model.ModelOutputParser
import com.hemant.plannerv1.permissions.PermissionManager
import com.hemant.plannerv1.safety.SafetyController

object AppContainer {
    lateinit var appContext: Context
        private set
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var screenCaptureManager: ScreenCaptureManager
        private set
    lateinit var gestureExecutor: GestureExecutor
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

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return

        appContext = context.applicationContext
        permissionManager = PermissionManager(appContext)
        screenCaptureManager = ScreenCaptureManager(appContext)
        gestureExecutor = GestureExecutor(appContext)
        modelManager = GemmaModelManager(appContext)
        modelInputBuilder = ModelInputBuilder()
        modelOutputParser = ModelOutputParser()
        safetyController = SafetyController()
        testLogger = TestLogger(appContext)
        agentOrchestrator = AgentOrchestrator(
            screenCaptureManager = screenCaptureManager,
            modelInputBuilder = modelInputBuilder,
            modelManager = modelManager,
            modelOutputParser = modelOutputParser,
            gestureExecutor = gestureExecutor,
            safetyController = safetyController,
            testLogger = testLogger,
        )
    }
}
