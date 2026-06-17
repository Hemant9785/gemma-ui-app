package com.hemant.plannerv1.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemant.plannerv1.agent.AgentState
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.model.ModelState
import com.hemant.plannerv1.permissions.PermissionSnapshot
import kotlinx.coroutines.launch

enum class MainTab(val label: String, val icon: ImageVector) {
    Control("Dashboard", Icons.Default.Dashboard),
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
    onRequestStorage: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartFloatingBar: () -> Unit,
    onStopFloatingBar: () -> Unit,
    onInitializeModel: suspend () -> Unit,
    onMaxStepsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(MainTab.Control) }
    
    val allPermissionsGranted = permissionSnapshot.overlayGranted && 
                               permissionSnapshot.screenCaptureGranted && 
                               permissionSnapshot.accessibilityEnabled

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(top = 48.dp, bottom = 16.dp, start = 24.dp, end = 24.dp)
            ) {
                Column {
                    Text(
                        text = "PlannerV1",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Agent Console",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
        floatingActionButton = {
            if (tab == MainTab.Control) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (allPermissionsGranted) onStartFloatingBar()
                    },
                    containerColor = if (allPermissionsGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (allPermissionsGranted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = { Icon(Icons.Default.RocketLaunch, contentDescription = "Launch Agent") },
                    text = { Text(if (allPermissionsGranted) "Launch Agent" else "Permissions Required", fontWeight = FontWeight.SemiBold) },
                    expanded = true
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
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
                onRequestStorage = onRequestStorage,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
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
    onRequestStorage: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onInitializeModel: suspend () -> Unit,
    onMaxStepsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var maxSteps by remember { mutableIntStateOf(agentState.maxSteps) }
    
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // System Health Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("System Health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                
                HorizontalDivider()
                
                PermissionRow("Overlay Window", permissionSnapshot.overlayGranted, onRequestOverlay)
                PermissionRow("Screen Capture", permissionSnapshot.screenCaptureGranted, onRequestScreenCapture)
                PermissionRow("Accessibility Service", permissionSnapshot.accessibilityEnabled, onOpenAccessibilitySettings)
                PermissionRow("Notifications", permissionSnapshot.notificationGranted, onRequestNotification)
                PermissionRow("Storage Access", permissionSnapshot.storageGranted, onRequestStorage)
            }
        }

        // Engine Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text("Gemma Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                
                Text(
                    text = modelState.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                
                if (modelState is ModelState.CopyingAsset ||
                    modelState is ModelState.InitializingGpu ||
                    modelState is ModelState.InitializingCpu
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                }
                
                Button(
                    onClick = { scope.launch { onInitializeModel() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = modelState !is ModelState.CopyingAsset &&
                        modelState !is ModelState.InitializingGpu &&
                        modelState !is ModelState.InitializingCpu &&
                        modelState !is ModelState.Ready,
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (modelState is ModelState.Ready) "Engine Ready" else "Initialize Engine")
                }
            }
        }

        // Agent Settings Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text("Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                
                Column {
                    Text("Max Steps: $maxSteps", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = maxSteps.toFloat(),
                        onValueChange = {
                            maxSteps = it.toInt().coerceIn(1, 10)
                            onMaxStepsChanged(maxSteps)
                        },
                        valueRange = 1f..10f,
                        steps = 8,
                        enabled = !agentState.isRunning,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.tertiary, activeTrackColor = MaterialTheme.colorScheme.tertiary)
                    )
                }
                
                if (agentState.lastError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Error: ${agentState.lastError}",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp)) // Padding for FAB
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    val color = if (granted) Color(0xFF15803D) else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Granted", tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ready", color = color, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            OutlinedButton(
                onClick = onRequest,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Grant", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun ModelState.label(): String {
    return when (this) {
        ModelState.NotInitialized -> "Not initialized. Model asset required."
        ModelState.MissingAsset -> "Missing model asset (gemma-4-E4B-it.litertlm)."
        ModelState.CopyingAsset -> "Copying bundled model into app-private storage."
        ModelState.InitializingGpu -> "Initializing Gemma with GPU backend."
        ModelState.InitializingCpu -> "GPU failed; initializing Gemma with CPU backend."
        is ModelState.Ready -> "Ready on $backend"
        is ModelState.Error -> message
    }
}
