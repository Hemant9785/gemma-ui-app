package com.hemant.plannerv1.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hemant.plannerv1.AppContainer
import com.hemant.plannerv1.ui.theme.PlannerV1Theme

@Composable
fun FloatingBarView(onClose: () -> Unit) {
    PlannerV1Theme {
        val agentState by AppContainer.agentOrchestrator.state.collectAsState()
        var goal by remember { mutableStateOf(agentState.goal) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                        value = goal,
                        onValueChange = { goal = it },
                        singleLine = true,
                        label = { Text("Command") },
                        enabled = !agentState.isRunning,
                    )
                    ElevatedButton(
                        onClick = {
                            AppContainer.agentOrchestrator.start(goal)
                        },
                        enabled = !agentState.isRunning && goal.isNotBlank(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                        Spacer(Modifier.width(6.dp))
                        Text("Run")
                    }
                    IconButton(
                        onClick = { AppContainer.agentOrchestrator.stop() },
                        enabled = agentState.isRunning,
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Step ${agentState.currentStep}/${agentState.maxSteps}") },
                    )
                    Text(
                        text = agentState.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
