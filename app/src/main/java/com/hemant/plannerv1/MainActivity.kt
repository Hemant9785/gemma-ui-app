package com.hemant.plannerv1

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.hemant.plannerv1.capture.ScreenCaptureForegroundService
import com.hemant.plannerv1.logging.DbgLog
import com.hemant.plannerv1.overlay.FloatingBarService
import com.hemant.plannerv1.ui.MainScreen
import com.hemant.plannerv1.ui.theme.PlannerV1Theme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private val permissionRefresh = MutableStateFlow(0)

    /** Holds the URI returned by the file picker until the user taps "Run Benchmark". */
    private val selectedGoalsUri = MutableStateFlow<Uri?>(null)

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

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissions()
    }

    /**
     * System document picker. The selected URI is copied to app-private cache so
     * [com.hemant.plannerv1.eval.EvalRunner] can open it as a plain [File] without
     * needing MANAGE_EXTERNAL_STORAGE.
     */
    private val goalsFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedGoalsUri.value = uri
            DbgLog.i("Goals file selected: $uri")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DbgLog.i("MainActivity onCreate")
        AppContainer.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            PlannerV1Theme {
                val refresh by permissionRefresh.collectAsState()
                val agentState by AppContainer.agentOrchestrator.state.collectAsState()
                val modelState by AppContainer.modelManager.state.collectAsState()
                val evalState by AppContainer.evalRunner.state.collectAsState()
                val goalsUri by selectedGoalsUri.collectAsState()
                val snapshot = AppContainer.permissionManager.snapshot(
                    screenCaptureGranted = AppContainer.screenCaptureManager.isReady,
                )
                refresh.hashCode()
                MainScreen(
                    permissionSnapshot = snapshot,
                    agentState = agentState,
                    modelState = modelState,
                    evalState = evalState,
                    selectedGoalsUri = goalsUri,
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
                    onRequestStorage = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            startActivity(AppContainer.permissionManager.storageSettingsIntent())
                        } else {
                            storageLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
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
                    onPickGoalsFile = {
                        goalsFilePicker.launch(arrayOf("text/*", "text/plain", "*/*"))
                    },
                    onStartBenchmark = {
                        val uri = selectedGoalsUri.value ?: return@MainScreen
                        val goalsFile = copyUriToCache(uri) ?: return@MainScreen
                        ContextCompat.startForegroundService(
                            this,
                            FloatingBarService.startIntent(this),
                        )
                        AppContainer.evalRunner.start(goalsFile)
                    },
                    onStopBenchmark = {
                        AppContainer.evalRunner.stop()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DbgLog.d("MainActivity onResume")
        refreshPermissions()
    }

    private fun refreshPermissions() {
        permissionRefresh.update { it + 1 }
    }

    /**
     * Copies the content at [uri] into a temp file in the app's cache directory so
     * [com.hemant.plannerv1.eval.EvalRunner] can open it as a plain [File].
     * This avoids needing MANAGE_EXTERNAL_STORAGE.
     */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val cacheFile = File(cacheDir, "goals_input.txt")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            DbgLog.i("Goals file copied to cache: ${cacheFile.absolutePath}")
            cacheFile
        } catch (e: Exception) {
            DbgLog.e("Failed to copy goals URI to cache: ${e.message}", e)
            null
        }
    }
}
