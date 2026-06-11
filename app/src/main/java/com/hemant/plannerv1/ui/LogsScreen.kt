package com.hemant.plannerv1.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hemant.plannerv1.logging.EvaluationSession
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(session.goal.ifBlank { session.sessionId }, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${session.status} | ${session.steps} steps | avg ${session.averageLatencyMs} ms | invalid JSON ${session.invalidJsonCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            session.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (session.actions.isNotEmpty()) {
                Text(
                    text = session.actions.take(5).joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                session.screenshots.take(3).forEach { path ->
                    ScreenshotThumb(path)
                }
            }
            Text(
                text = session.logPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScreenshotThumb(path: String) {
    val bitmap = remember(path) {
        if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Screenshot",
            modifier = Modifier
                .width(72.dp)
                .height(72.dp)
                .aspectRatio(1f),
            contentScale = ContentScale.Crop,
        )
    }
}
