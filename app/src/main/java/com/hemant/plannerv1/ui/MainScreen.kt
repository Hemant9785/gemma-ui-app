package com.hemant.plannerv1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hemant.plannerv1.agent.AgentState
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.model.ModelState
import com.hemant.plannerv1.permissions.PermissionSnapshot
import kotlinx.coroutines.launch

enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    Control("Control", Icons.Default.BugReport),
    Logs("Logs", Icons.Default.ListAlt),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionSnapshot: PermissionSnapshot,
    agentState: AgentState,
    modelState: ModelState,
    logger: TestLogger,
    onRequestOverlay: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartFloatingBar: () -> Unit,
    onStopFloatingBar: () -> Unit,
    onInitializeModel: suspend () -> Unit,
    onMaxStepsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(MainTab.Control) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column {
                        Text("UIActionAgent")
                        Text(
                            text = "PlannerV1 research console",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            MainTab.Control -> ControlScreen(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                permissionSnapshot = permissionSnapshot,
                agentState = agentState,
                modelState = modelState,
                onRequestOverlay = onRequestOverlay,
                onRequestScreenCapture = onRequestScreenCapture,
                onRequestNotification = onRequestNotification,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onStartFloatingBar = onStartFloatingBar,
                onStopFloatingBar = onStopFloatingBar,
                onInitializeModel = onInitializeModel,
                onMaxStepsChanged = onMaxStepsChanged,
            )
            MainTab.Logs -> LogsScreen(
                logger = logger,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun ControlScreen(
    permissionSnapshot: PermissionSnapshot,
    agentState: AgentState,
    modelState: ModelState,
    onRequestOverlay: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartFloatingBar: () -> Unit,
    onStopFloatingBar: () -> Unit,
    onInitializeModel: suspend () -> Unit,
    onMaxStepsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var maxSteps by remember { mutableIntStateOf(agentState.maxSteps) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PermissionStatusScreen(snapshot = permissionSnapshot)

        ActionGrid(
            onRequestOverlay = onRequestOverlay,
            onRequestScreenCapture = onRequestScreenCapture,
            onRequestNotification = onRequestNotification,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onStartFloatingBar = onStartFloatingBar,
            onStopFloatingBar = onStopFloatingBar,
            canStartFloatingBar = permissionSnapshot.overlayGranted,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gemma Model", style = MaterialTheme.typography.titleMedium)
            Text(
                text = modelState.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (modelState is ModelState.CopyingAsset ||
                modelState is ModelState.InitializingGpu ||
                modelState is ModelState.InitializingCpu
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = { scope.launch { onInitializeModel() } },
                enabled = modelState !is ModelState.CopyingAsset &&
                    modelState !is ModelState.InitializingGpu &&
                    modelState !is ModelState.InitializingCpu,
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Initialize model")
                Text("Initialize Gemma")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Agent", style = MaterialTheme.typography.titleMedium)
            Text(
                text = agentState.status,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            agentState.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Text("Max steps: $maxSteps")
            Slider(
                value = maxSteps.toFloat(),
                onValueChange = {
                    maxSteps = it.toInt().coerceIn(1, 10)
                    onMaxStepsChanged(maxSteps)
                },
                valueRange = 1f..10f,
                steps = 8,
                enabled = !agentState.isRunning,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onStopFloatingBar() },
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop floating bar")
                    Text("Stop Bar")
                }
                Text(
                    text = "Run commands from the floating bar after permissions are ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionGrid(
    onRequestOverlay: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartFloatingBar: () -> Unit,
    onStopFloatingBar: () -> Unit,
    canStartFloatingBar: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Controls", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onStartFloatingBar, enabled = canStartFloatingBar, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.BugReport, contentDescription = "Start")
                Text("Start Bar")
            }
            OutlinedButton(onClick = onStopFloatingBar, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Stop, contentDescription = "Stop")
                Text("Stop Bar")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRequestOverlay, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Layers, contentDescription = "Overlay")
                Text("Overlay")
            }
            OutlinedButton(onClick = onRequestScreenCapture, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ScreenShare, contentDescription = "Screen capture")
                Text("Capture")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PersonSearch, contentDescription = "Accessibility")
                Text("Accessibility")
            }
            OutlinedButton(onClick = onRequestNotification, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ListAlt, contentDescription = "Notifications")
                Text("Notify")
            }
        }
    }
}

private fun ModelState.label(): String {
    return when (this) {
        ModelState.NotInitialized -> "Not initialized. Required asset: app/src/main/assets/models/gemma-4-E4B-it.litertlm"
        ModelState.MissingAsset -> "Missing model asset. Add gemma-4-E4B-it.litertlm under app/src/main/assets/models."
        ModelState.CopyingAsset -> "Copying bundled model into app-private storage."
        ModelState.InitializingGpu -> "Initializing Gemma with GPU backend."
        ModelState.InitializingCpu -> "GPU failed; initializing Gemma with CPU backend."
        is ModelState.Ready -> "Ready on $backend: $modelPath"
        is ModelState.Error -> message
    }
}
