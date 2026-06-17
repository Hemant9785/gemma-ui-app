package com.hemant.plannerv1.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hemant.plannerv1.logging.EvaluationSession
import com.hemant.plannerv1.logging.StepData
import com.hemant.plannerv1.logging.TestLogger
import java.io.File

@Composable
fun LogsScreen(
    logger: TestLogger,
    modifier: Modifier = Modifier,
) {
    var sessions by remember { mutableStateOf(logger.loadSessions()) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Evaluation Logs", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { sessions = logger.loadSessions() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                Text("Refresh")
            }
        }

        if (sessions.isEmpty()) {
            Text(
                text = "No runs logged yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionLogCard(session)
                }
            }
        }
    }
}

@Composable
private fun SessionLogCard(session: EvaluationSession) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.goal.ifBlank { session.sessionId }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${session.status} | ${session.steps} steps | avg ${session.averageLatencyMs} ms | invalid JSON ${session.invalidJsonCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                )
            }
            session.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = session.logPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    session.stepDataList.forEach { step ->
                        StepDetailCard(step)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDetailCard(step: StepData) {
    var showFullImage by remember { mutableStateOf(false) }
    val isError = step.error != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Step ${step.stepNumber}", style = MaterialTheme.typography.labelLarge)
                Text("${step.latencyMs} ms", style = MaterialTheme.typography.labelSmall)
            }
            if (isError) {
                Text("Error: ${step.error}", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
            step.rawOutput?.let { raw ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Model Output:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.05f))
                            .padding(8.dp),
                    ) {
                        Text(
                            text = raw,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            step.parsedAction?.let { action ->
                Text("Parsed Action: ${action.signature()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            step.screenshotPath?.let { path ->
                ScreenshotWithBoundingBox(
                    path = path,
                    boundingBox = step.parsedAction?.boundingBox,
                    modifier = Modifier
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showFullImage = true },
                )
            }
        }
    }

    if (showFullImage && step.screenshotPath != null) {
        FullScreenImageDialog(
            path = step.screenshotPath,
            boundingBox = step.parsedAction?.boundingBox,
            onDismiss = { showFullImage = false },
        )
    }
}

@Composable
fun ScreenshotWithBoundingBox(
    path: String,
    boundingBox: List<Double>?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(path) {
        if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
    }
    if (bitmap != null) {
        Box(modifier = modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())) {
            Image(
                bitmap = bitmap,
                contentDescription = "Screenshot",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (boundingBox != null && boundingBox.size >= 4) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val y1 = boundingBox[0].toFloat() / 1000f
                    val x1 = boundingBox[1].toFloat() / 1000f
                    val y2 = boundingBox[2].toFloat() / 1000f
                    val x2 = boundingBox[3].toFloat() / 1000f

                    val rectTop = y1 * size.height
                    val rectLeft = x1 * size.width
                    val rectBottom = y2 * size.height
                    val rectRight = x2 * size.width

                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectRight - rectLeft, rectBottom - rectTop),
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Gray),
            contentAlignment = Alignment.Center,
        ) {
            Text("Screenshot missing", color = Color.White)
        }
    }
}

@Composable
fun FullScreenImageDialog(
    path: String,
    boundingBox: List<Double>?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ScreenshotWithBoundingBox(
                    path = path,
                    boundingBox = boundingBox,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}
