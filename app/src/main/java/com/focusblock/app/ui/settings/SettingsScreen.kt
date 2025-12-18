@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.focusblock.app.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.ui.components.AppSelectionDialog
import com.focusblock.app.ui.components.HardModeSetupDialog
import com.focusblock.app.ui.components.PinSetupDialog
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import com.focusblock.app.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showBlockedAppsDialog by remember { mutableStateOf(false) }
    var showAllowlistDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showHardModeDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Profile header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = "FocusBlock",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Personal Use Edition",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Primary
                        )
                        Text(
                            text = "Version 1.0.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Blocking section
        item {
            SettingsSectionHeader(title = "Blocking")
        }

        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Outlined.Block,
                    title = "Blocked Apps",
                    subtitle = "${uiState.blockedAppsCount} apps",
                    onClick = { showBlockedAppsDialog = true }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Allowlist",
                    subtitle = "${uiState.allowlistCount} apps always allowed",
                    onClick = { showAllowlistDialog = true }
                )
            }
        }

        // Security section
        item {
            SettingsSectionHeader(title = "Security")
        }

        item {
            SettingsCard {
                SettingsToggleItem(
                    icon = Icons.Outlined.Lock,
                    title = "Strict Mode",
                    subtitle = "Prevent disabling blocks",
                    isChecked = uiState.isStrictModeEnabled,
                    onCheckedChange = { viewModel.setStrictMode(it) }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.Security,
                    title = "Hard Mode",
                    subtitle = if (uiState.isHardModeEnabled) "Enabled" else "Require PIN + time lock",
                    onClick = { showHardModeDialog = true },
                    trailing = {
                        if (uiState.isHardModeEnabled) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AccentOrange.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentOrange
                                )
                            }
                        }
                    }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.Pin,
                    title = "Change PIN",
                    subtitle = "Update your security PIN",
                    onClick = { showPinDialog = true }
                )
            }
        }

        // Permissions section
        item {
            SettingsSectionHeader(title = "Permissions")
        }

        item {
            SettingsCard {
                PermissionSettingsItem(
                    icon = Icons.Outlined.QueryStats,
                    title = "Usage Access",
                    isGranted = uiState.permissionStatus.hasUsageStats,
                    onClick = { PermissionUtils.openUsageAccessSettings(context) }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                PermissionSettingsItem(
                    icon = Icons.Outlined.Layers,
                    title = "Display Over Apps",
                    isGranted = uiState.permissionStatus.hasOverlay,
                    onClick = { PermissionUtils.openOverlaySettings(context) }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                PermissionSettingsItem(
                    icon = Icons.Outlined.Accessibility,
                    title = "Accessibility Service",
                    isGranted = uiState.permissionStatus.hasAccessibility,
                    onClick = { PermissionUtils.openAccessibilitySettings(context) }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                PermissionSettingsItem(
                    icon = Icons.Outlined.BatteryChargingFull,
                    title = "Battery Optimization",
                    isGranted = uiState.permissionStatus.isIgnoringBattery,
                    onClick = { PermissionUtils.requestIgnoreBatteryOptimizations(context) }
                )
            }
        }

        // Pomodoro settings
        item {
            SettingsSectionHeader(title = "Pomodoro")
        }

        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Outlined.Timer,
                    title = "Work Duration",
                    subtitle = "${uiState.pomodoroWorkMinutes} minutes",
                    onClick = { /* Show duration picker */ }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.Coffee,
                    title = "Short Break",
                    subtitle = "${uiState.pomodoroShortBreak} minutes",
                    onClick = { /* Show duration picker */ }
                )

                HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.Weekend,
                    title = "Long Break",
                    subtitle = "${uiState.pomodoroLongBreak} minutes",
                    onClick = { /* Show duration picker */ }
                )
            }
        }

        // About section
        item {
            SettingsSectionHeader(title = "About")
        }

        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "About FocusBlock",
                    subtitle = "Personal digital wellbeing app",
                    onClick = { }
                )
            }
        }

        // Spacer at bottom
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Dialogs
    if (showBlockedAppsDialog) {
        AppSelectionDialog(
            apps = viewModel.getInstalledApps(),
            selectedApps = uiState.blockedApps.map { it.packageName },
            onDismiss = { showBlockedAppsDialog = false },
            onConfirm = { selected ->
                viewModel.updateBlockedApps(selected)
                showBlockedAppsDialog = false
            }
        )
    }

    if (showAllowlistDialog) {
        AppSelectionDialog(
            apps = viewModel.getInstalledApps(),
            selectedApps = uiState.allowlistApps.map { it.packageName },
            onDismiss = { showAllowlistDialog = false },
            onConfirm = { selected ->
                viewModel.updateAllowlist(selected)
                showAllowlistDialog = false
            }
        )
    }

    if (showPinDialog) {
        PinSetupDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                viewModel.setPin(pin)
                showPinDialog = false
            }
        )
    }

    if (showHardModeDialog) {
        HardModeSetupDialog(
            onDismiss = { showHardModeDialog = false },
            onConfirm = { pin, unlockMinutes ->
                viewModel.enableHardMode(pin, unlockMinutes)
                showHardModeDialog = false
            }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextTertiary
            )
        }
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Primary,
                checkedTrackColor = Primary.copy(alpha = 0.5f),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Divider
            )
        )
    }
}

@Composable
fun PermissionSettingsItem(
    icon: ImageVector,
    title: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isGranted) StatusActive else TextSecondary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isGranted) StatusActive.copy(alpha = 0.2f) else AccentRed.copy(alpha = 0.2f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isGranted) "Granted" else "Required",
                style = MaterialTheme.typography.labelSmall,
                color = if (isGranted) StatusActive else AccentRed
            )
        }
    }
}
