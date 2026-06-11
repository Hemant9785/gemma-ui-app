package com.hemant.plannerv1

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.hemant.plannerv1.capture.ScreenCaptureForegroundService
import com.hemant.plannerv1.overlay.FloatingBarService
import com.hemant.plannerv1.ui.MainScreen
import com.hemant.plannerv1.ui.theme.PlannerV1Theme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private val permissionRefresh = MutableStateFlow(0)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                this,
                ScreenCaptureForegroundService.intent(this),
            )
            AppContainer.screenCaptureManager.setProjectionPermission(
                result.resultCode,
                result.data,
            )
        }
        refreshPermissions()
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            PlannerV1Theme {
                val refresh by permissionRefresh.collectAsState()
                val agentState by AppContainer.agentOrchestrator.state.collectAsState()
                val modelState by AppContainer.modelManager.state.collectAsState()
                val snapshot = AppContainer.permissionManager.snapshot(
                    screenCaptureGranted = AppContainer.screenCaptureManager.isReady,
                )
                refresh.hashCode()
                MainScreen(
                    permissionSnapshot = snapshot,
                    agentState = agentState,
                    modelState = modelState,
                    logger = AppContainer.testLogger,
                    onRequestOverlay = {
                        startActivity(AppContainer.permissionManager.overlaySettingsIntent())
                    },
                    onRequestScreenCapture = {
                        screenCaptureLauncher.launch(
                            AppContainer.permissionManager.screenCaptureIntent(),
                        )
                    },
                    onRequestNotification = {
                        AppContainer.permissionManager.notificationPermission()?.let {
                            notificationLauncher.launch(it)
                        }
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(AppContainer.permissionManager.accessibilitySettingsIntent())
                    },
                    onStartFloatingBar = {
                        ContextCompat.startForegroundService(
                            this,
                            FloatingBarService.startIntent(this),
                        )
                    },
                    onStopFloatingBar = {
                        stopService(FloatingBarService.stopIntent(this))
                        AppContainer.agentOrchestrator.stop()
                    },
                    onInitializeModel = {
                        AppContainer.modelManager.initialize()
                    },
                    onMaxStepsChanged = {
                        AppContainer.agentOrchestrator.setMaxSteps(it)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        permissionRefresh.update { it + 1 }
    }
}
