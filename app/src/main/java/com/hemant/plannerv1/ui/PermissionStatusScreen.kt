package com.hemant.plannerv1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hemant.plannerv1.permissions.PermissionSnapshot

@Composable
fun PermissionStatusScreen(
    snapshot: PermissionSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        PermissionRow("Overlay window", snapshot.overlayGranted)
        PermissionRow("Screen capture", snapshot.screenCaptureGranted)
        PermissionRow("Accessibility service", snapshot.accessibilityEnabled)
        PermissionRow("Notifications", snapshot.notificationGranted)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    val color = if (granted) Color(0xFF15803D) else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = if (granted) "Granted" else "Missing",
                tint = color,
            )
            Text(
                text = if (granted) "Ready" else "Needed",
                color = color,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
