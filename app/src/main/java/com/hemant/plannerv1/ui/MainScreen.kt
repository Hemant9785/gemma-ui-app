package com.hemant.plannerv1.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemant.plannerv1.agent.AgentState
import com.hemant.plannerv1.eval.EvalGoalResult
import com.hemant.plannerv1.eval.EvalRunnerState
import com.hemant.plannerv1.logging.TestLogger
import com.hemant.plannerv1.model.BackendConfig
import com.hemant.plannerv1.model.ModelState
import com.hemant.plannerv1.permissions.PermissionSnapshot

enum class MainTab(val label: String, val icon: ImageVector) {
    Control("Dashboard", Icons.Default.Dashboard),
    Benchmark("Benchmark", Icons.Default.Science),
    Logs("Logs", Icons.Default.ListAlt),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionSnapshot: PermissionSnapshot,
    agentState: AgentState,
    modelState: ModelState,
    evalState: EvalRunnerState,
    selectedGoalsUri: Uri?,
    logger: TestLogger,
    onRequestOverlay: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestStorage: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartFloatingBar: () -> Unit,
    onStopFloatingBar: () -> Unit,
    onInitializeModel: (BackendConfig.BackendType) -> Unit,
    onReleaseModel: () -> Unit,
    onMaxStepsChanged: (Int) -> Unit,
    onPickGoalsFile: () -> Unit,
    onStartBenchmark: () -> Unit,
    onStopBenchmark: () -> Unit,
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
                onReleaseModel = onReleaseModel,
                onMaxStepsChanged = onMaxStepsChanged,
            )
            MainTab.Benchmark -> BenchmarkScreen(
                evalState = evalState,
                selectedGoalsUri = selectedGoalsUri,
                modelState = modelState,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                onPickGoalsFile = onPickGoalsFile,
                onStartBenchmark = onStartBenchmark,
                onStopBenchmark = onStopBenchmark,
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

// ── Benchmark Screen ────────────────────────────────────────────────────────

@Composable
private fun BenchmarkScreen(
    evalState: EvalRunnerState,
    selectedGoalsUri: Uri?,
    modelState: ModelState,
    onPickGoalsFile: () -> Unit,
    onStartBenchmark: () -> Unit,
    onStopBenchmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = evalState.progress,
        label = "evalProgress"
    )
    val modelReady = modelState is ModelState.Ready
    val canStart = modelReady && selectedGoalsUri != null && !evalState.isRunning

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Setup Card ──────────────────────────────────────────────────────
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Science, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Benchmark Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                HorizontalDivider()

                // Model status indicator
                BenchmarkStatusRow(
                    label = "Gemma Engine",
                    ok = modelReady,
                    detail = if (modelReady) "Ready" else "Not initialized — go to Dashboard",
                )

                // File picker
                BenchmarkStatusRow(
                    label = "Goals File (.txt)",
                    ok = selectedGoalsUri != null,
                    detail = if (selectedGoalsUri != null) "File selected ✓" else "No file selected",
                )

                OutlinedButton(
                    onClick = onPickGoalsFile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !evalState.isRunning,
                ) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (selectedGoalsUri != null) "Change Goals File" else "Upload Goals File (.txt)")
                }

                if (!modelReady) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "⚠ Initialize the Gemma engine on the Dashboard tab before running a benchmark.",
                            modifier = Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Run / Stop button
                if (evalState.isRunning) {
                    Button(
                        onClick = onStopBenchmark,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Benchmark", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = onStartBenchmark,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = canStart,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Run Benchmark", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ── Progress Card — shown while running or after completion ─────────
        AnimatedVisibility(visible = evalState.isRunning || evalState.results.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timeline, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider()

                    Text(
                        text = evalState.currentStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (evalState.totalGoals > 0) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50)),
                        )
                        Text(
                            text = "${evalState.completedGoals} / ${evalState.totalGoals} goals complete",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (evalState.isRunning && evalState.currentGoalText.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = "Running Goal #${evalState.currentGoalNumber}  •  Step ${evalState.currentStep}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = evalState.currentGoalText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Output path shown on completion
                    if (!evalState.isRunning && evalState.outputDirPath != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Reports saved to:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = evalState.outputDirPath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "Pull via: adb pull ${evalState.outputDirPath}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Results Summary Card ─────────────────────────────────────────────
        AnimatedVisibility(visible = evalState.results.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Assessment, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text("Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider()

                    val successCount = evalState.results.count { it.status == "Success" }
                    val totalCount = evalState.results.size

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricChip(
                            modifier = Modifier.weight(1f),
                            label = "Success Rate",
                            value = if (totalCount == 0) "—" else "${(successCount * 100 / totalCount)}%",
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        MetricChip(
                            modifier = Modifier.weight(1f),
                            label = "Passed",
                            value = "$successCount / $totalCount",
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }

                    val avgMs = if (evalState.results.isEmpty()) 0L
                    else evalState.results
                        .flatMap { it.steps }
                        .map { it.modelInferenceLatencyMs }
                        .average()
                        .toLong()
                    MetricChip(
                        modifier = Modifier.fillMaxWidth(),
                        label = "Avg Model Inference",
                        value = "$avgMs ms",
                        color = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    HorizontalDivider()

                    // Per-goal rows
                    evalState.results.forEach { result ->
                        GoalResultRow(result)
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun BenchmarkStatusRow(label: String, ok: Boolean, detail: String) {
    val statusColor = if (ok) Color(0xFF15803D) else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text(label, style = MaterialTheme.typography.labelSmall, color = textColor)
        }
    }
}

@Composable
private fun GoalResultRow(result: EvalGoalResult) {
    val isSuccess = result.status == "Success"
    val chipColor = if (isSuccess)
        MaterialTheme.colorScheme.tertiaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val chipTextColor = if (isSuccess)
        MaterialTheme.colorScheme.onTertiaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Goal number bubble
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${result.goalNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = result.goal,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${result.totalSteps} steps  •  avg ${result.avgInferenceMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(color = chipColor, shape = RoundedCornerShape(6.dp)) {
                Text(
                    text = if (isSuccess) "✓ Pass" else "✗ Fail",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = chipTextColor,
                )
            }
        }
    }
}

// ── Control Screen ──────────────────────────────────────────────────────────

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
    onInitializeModel: (BackendConfig.BackendType) -> Unit,
    onReleaseModel: () -> Unit,
    onMaxStepsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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

                // ── Backend toggle ───────────────────────────────────────────
                val engineBusy = modelState is ModelState.CopyingAsset ||
                    modelState is ModelState.InitializingGpu ||
                    modelState is ModelState.InitializingCpu
                val engineReady = modelState is ModelState.Ready

                var useLlamaCpp by remember {
                    mutableStateOf(
                        com.hemant.plannerv1.AppContainer.backendConfig.activeBackend ==
                            BackendConfig.BackendType.LLAMACPP
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = useLlamaCpp,
                        onCheckedChange = { checked ->
                            useLlamaCpp = checked
                            com.hemant.plannerv1.AppContainer.backendConfig.activeBackend =
                                if (checked) BackendConfig.BackendType.LLAMACPP
                                else BackendConfig.BackendType.LITERTLM
                        },
                        enabled = !engineBusy && !engineReady,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (useLlamaCpp) "llama.cpp GGUF backend" else "LiteRT-LM backend",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = if (useLlamaCpp)
                                "Loads gemma-4-E4B-it-Q4_K_M.gguf; vision if mmproj is present"
                            else
                                "Loads .litertlm · multimodal (GPU/CPU)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (engineReady) {
                    Text(
                        text = "⚠ Release the engine before switching backends.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── Initialize / Release buttons ─────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val selectedBackend =
                                if (useLlamaCpp) BackendConfig.BackendType.LLAMACPP
                                else BackendConfig.BackendType.LITERTLM
                            onInitializeModel(selectedBackend)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !engineBusy && !engineReady,
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (engineReady) "Engine Ready" else "Initialize")
                    }

                    if (engineReady) {
                        OutlinedButton(
                            onClick = onReleaseModel,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Release")
                        }
                    }
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = com.hemant.plannerv1.AppContainer.promptInjectionManager.isEnabled,
                        onCheckedChange = { com.hemant.plannerv1.AppContainer.promptInjectionManager.isEnabled = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.tertiary)
                    )
                    Text("Enable prompt injection", style = MaterialTheme.typography.bodyMedium)
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
        ModelState.NotInitialized  -> "Not initialized. Tap Initialize to load the model."
        ModelState.MissingAsset   -> "Missing model file in /sdcard/multiturn/model/ (.litertlm or .gguf)."
        ModelState.CopyingAsset   -> "Copying bundled model into app-private storage."
        ModelState.InitializingGpu -> "Initializing engine with GPU backend…"
        ModelState.InitializingCpu -> "Initializing engine with CPU backend…"
        is ModelState.Ready       -> "Ready on $backend"
        is ModelState.Error       -> message
    }
}
