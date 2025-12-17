package com.focusblock.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.PermissionUtils

@Composable
fun PermissionCard(
    permissionStatus: PermissionUtils.PermissionStatus,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AccentOrange.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentOrange
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "FocusBlock needs the following permissions to work properly:",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Usage Access Permission
            PermissionItem(
                title = "Usage Access",
                description = "Required to detect which apps you're using",
                isGranted = permissionStatus.hasUsageStats,
                onClick = { PermissionUtils.openUsageAccessSettings(context) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Overlay Permission
            PermissionItem(
                title = "Display Over Apps",
                description = "Required to show blocking screen",
                isGranted = permissionStatus.hasOverlay,
                onClick = { PermissionUtils.openOverlaySettings(context) }
            )

            if (permissionStatus.hasUsageStats && permissionStatus.hasOverlay) {
                Spacer(modifier = Modifier.height(8.dp))

                // Accessibility (Optional but recommended)
                PermissionItem(
                    title = "Accessibility Service",
                    description = "Optional: For enhanced blocking",
                    isGranted = permissionStatus.hasAccessibility,
                    isOptional = true,
                    onClick = { PermissionUtils.openAccessibilitySettings(context) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Battery Optimization
                PermissionItem(
                    title = "Ignore Battery Optimization",
                    description = "Prevents system from stopping the service",
                    isGranted = permissionStatus.isIgnoringBattery,
                    isOptional = true,
                    onClick = { PermissionUtils.requestIgnoreBatteryOptimizations(context) }
                )
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    isOptional: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { if (!isGranted) onClick() }
            .background(if (isGranted) StatusActive.copy(alpha = 0.1f) else CardDark)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isGranted) StatusActive.copy(alpha = 0.2f) else Divider),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Filled.Check else Icons.Outlined.Settings,
                contentDescription = null,
                tint = if (isGranted) StatusActive else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                if (isOptional) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(Optional)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        if (!isGranted) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextSecondary
            )
        }
    }
}
