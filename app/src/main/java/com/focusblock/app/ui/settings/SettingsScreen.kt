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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
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
    var showAboutDialog by remember { mutableStateOf(false) }
    var showPomodoroPicker by remember { mutableStateOf<String?>(null) } // "work", "short", "long"
    var showDailyLimitPicker by remember { mutableStateOf(false) }
    var showStrictModePinDialog by remember { mutableStateOf(false) }
    var showStrictModeLockedDialog by remember { mutableStateOf(false) }

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

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Allowlist",
                    subtitle = "${uiState.allowlistCount} apps always allowed",
                    onClick = { showAllowlistDialog = true }
                )
            }
        }

        // Screen Time section - Global Daily Limit
        item {
            SettingsSectionHeader(title = "Screen Time")
        }

        item {
            SettingsCard {
                SettingsToggleItem(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = "Daily Usage Limit",
                    subtitle = if (uiState.isGlobalDailyLimitEnabled)
                        "Limit: ${formatDailyLimit(uiState.globalDailyLimitMinutes)}"
                    else "Set daily limit for distracting apps",
                    isChecked = uiState.isGlobalDailyLimitEnabled,
                    onCheckedChange = { viewModel.setGlobalDailyLimitEnabled(it) }
                )

                if (uiState.isGlobalDailyLimitEnabled) {
                    Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsItem(
                        icon = Icons.Outlined.Timer,
                        title = "Daily Limit",
                        subtitle = formatDailyLimit(uiState.globalDailyLimitMinutes),
                        onClick = { showDailyLimitPicker = true }
                    )

                    Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsItem(
                        icon = Icons.Outlined.Block,
                        title = "Apps Tracked",
                        subtitle = "Social media, videos, games & more",
                        onClick = { /* TODO: Add app picker */ }
                    )
                }
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
                    subtitle = if (uiState.isStrictModeLocked) "Time-locked" else "Prevent disabling blocks",
                    isChecked = uiState.isStrictModeEnabled,
                    onCheckedChange = { enabled ->
                        when (viewModel.setStrictMode(enabled)) {
                            SettingsViewModel.StrictModeResult.SUCCESS -> { /* Done */ }
                            SettingsViewModel.StrictModeResult.TIME_LOCKED -> {
                                showStrictModeLockedDialog = true
                            }
                            SettingsViewModel.StrictModeResult.NEEDS_PIN -> {
                                showStrictModePinDialog = true
                            }
                        }
                    }
                )

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

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

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

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

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                PermissionSettingsItem(
                    icon = Icons.Outlined.Layers,
                    title = "Display Over Apps",
                    isGranted = uiState.permissionStatus.hasOverlay,
                    onClick = { PermissionUtils.openOverlaySettings(context) }
                )

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                PermissionSettingsItem(
                    icon = Icons.Outlined.Accessibility,
                    title = "Accessibility Service",
                    isGranted = uiState.permissionStatus.hasAccessibility,
                    onClick = { PermissionUtils.openAccessibilitySettings(context) }
                )

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

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
                    onClick = { showPomodoroPicker = "work" }
                )

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.Coffee,
                    title = "Short Break",
                    subtitle = "${uiState.pomodoroShortBreak} minutes",
                    onClick = { showPomodoroPicker = "short" }
                )

                Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))

                SettingsItem(
                    icon = Icons.Outlined.Weekend,
                    title = "Long Break",
                    subtitle = "${uiState.pomodoroLongBreak} minutes",
                    onClick = { showPomodoroPicker = "long" }
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
                    onClick = { showAboutDialog = true }
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

    // Strict Mode PIN verification dialog
    if (showStrictModePinDialog) {
        StrictModePinDialog(
            onDismiss = { showStrictModePinDialog = false },
            onVerify = { pin ->
                val success = viewModel.verifyPinAndDisableStrictMode(pin)
                if (success) {
                    showStrictModePinDialog = false
                }
                success
            }
        )
    }

    // Strict Mode time-locked dialog
    if (showStrictModeLockedDialog) {
        StrictModeLockedDialog(
            remainingTime = (uiState.strictModeEndTime ?: 0L) - System.currentTimeMillis(),
            onDismiss = { showStrictModeLockedDialog = false }
        )
    }

    // Pomodoro Duration Picker
    showPomodoroPicker?.let { type ->
        val title = when (type) {
            "work" -> "Work Duration"
            "short" -> "Short Break"
            else -> "Long Break"
        }
        val currentValue = when (type) {
            "work" -> uiState.pomodoroWorkMinutes
            "short" -> uiState.pomodoroShortBreak
            else -> uiState.pomodoroLongBreak
        }
        val options = when (type) {
            "work" -> listOf(15, 20, 25, 30, 45, 60)
            "short" -> listOf(3, 5, 10, 15)
            else -> listOf(10, 15, 20, 30)
        }

        AlertDialog(
            onDismissRequest = { showPomodoroPicker = null },
            title = { Text(title, color = TextPrimary) },
            containerColor = CardDark,
            text = {
                Column {
                    options.forEach { minutes ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (type) {
                                        "work" -> viewModel.setPomodoroWorkMinutes(minutes)
                                        "short" -> viewModel.setPomodoroShortBreak(minutes)
                                        else -> viewModel.setPomodoroLongBreak(minutes)
                                    }
                                    showPomodoroPicker = null
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = minutes == currentValue,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Primary,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "$minutes minutes",
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPomodoroPicker = null }) {
                    Text("Cancel", color = Primary)
                }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            containerColor = CardDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("FocusBlock", color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your personal digital wellbeing companion",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Version 1.5.0",
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Take control of your screen time and build healthier digital habits.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close", color = Primary)
                }
            }
        )
    }

    // Daily Limit Picker Dialog
    if (showDailyLimitPicker) {
        val limitOptions = listOf(30, 60, 90, 120, 150, 180, 240, 300, 360) // 30m to 6h
        AlertDialog(
            onDismissRequest = { showDailyLimitPicker = false },
            title = { Text("Daily Usage Limit", color = TextPrimary) },
            containerColor = CardDark,
            text = {
                Column {
                    Text(
                        "Total phone usage allowed per day (excludes system apps)",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    limitOptions.forEach { minutes ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setGlobalDailyLimit(minutes)
                                    showDailyLimitPicker = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = minutes == uiState.globalDailyLimitMinutes,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Primary,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = formatDailyLimit(minutes),
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDailyLimitPicker = false }) {
                    Text("Cancel", color = Primary)
                }
            }
        )
    }
}

/**
 * Format daily limit minutes for display
 */
private fun formatDailyLimit(minutes: Int): String {
    return when {
        minutes >= 60 -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins > 0) "${hours}h ${mins}m" else "${hours} hour${if (hours > 1) "s" else ""}"
        }
        else -> "$minutes minutes"
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

@Composable
fun StrictModePinDialog(
    onDismiss: () -> Unit,
    onVerify: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDarkElevated,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "PIN Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Hard Mode is enabled. Enter your PIN to disable Strict Mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                            pin = it
                            error = null
                        }
                    },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = AccentRed) } },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!onVerify(pin)) {
                        error = "Incorrect PIN"
                        pin = ""
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                enabled = pin.length >= 4
            ) {
                Text("Disable", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
fun StrictModeLockedDialog(
    remainingTime: Long,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDarkElevated,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AccentRed.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = null,
                        tint = AccentRed,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Strict Mode Locked",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Strict Mode is time-locked. You cannot disable it until the timer expires.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (remainingTime > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val hours = (remainingTime / 3600000).toInt()
                    val minutes = ((remainingTime % 3600000) / 60000).toInt()
                    Text(
                        text = "Remaining: ${hours}h ${minutes}m",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("OK", fontWeight = FontWeight.Medium)
            }
        }
    )
}
