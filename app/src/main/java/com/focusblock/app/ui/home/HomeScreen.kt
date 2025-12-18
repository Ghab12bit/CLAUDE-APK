@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.focusblock.app.ui.home

import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.ui.components.AppSelectionDialog
import com.focusblock.app.ui.components.HardModeSetupDialog
import com.focusblock.app.ui.components.PermissionCard
import com.focusblock.app.ui.components.TimerPickerDialog
import com.focusblock.app.ui.components.PomodoroSetupDialog
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.TimeUtils
import com.focusblock.app.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showAppSelectionDialog by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showPomodoroDialog by remember { mutableStateOf(false) }
    var showHardModeDialog by remember { mutableStateOf(false) }
    var showBlockedAppsDialog by remember { mutableStateOf(false) }
    var selectedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var timerDurationMinutes by remember { mutableStateOf<Int?>(null) }
    var isEditingApps by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
    }

    // Show snackbar when blocking starts
    LaunchedEffect(uiState.isQuickBlockActive) {
        if (uiState.isQuickBlockActive) {
            val appsCount = uiState.quickBlockSession?.blockedPackages?.split(",")?.filter { it.isNotEmpty() }?.size ?: 0
            snackbarHostState.showSnackbar(
                message = "Blocking $appsCount apps",
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark
    ) { paddingValues ->
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(BackgroundDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "FocusBlock",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Primary
                    )
                    Text(
                        text = "Take control of your digital life",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                // Logo placeholder
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Permission warnings
        if (!uiState.permissionStatus.hasRequiredPermissions) {
            item {
                PermissionCard(
                    permissionStatus = uiState.permissionStatus,
                    onRequestPermission = { /* Handle in PermissionCard */ }
                )
            }
        }

        // Quick Block Card
        item {
            QuickBlockCard(
                isActive = uiState.isQuickBlockActive,
                remainingTime = uiState.remainingTime,
                endTime = uiState.quickBlockSession?.endTime,
                blockedAppsCount = uiState.quickBlockSession?.blockedPackages?.split(",")?.filter { it.isNotEmpty() }?.size ?: 0,
                isPomodoroMode = uiState.isPomodoroMode,
                onStartClick = { showAppSelectionDialog = true },
                onStopClick = { viewModel.stopQuickBlock() },
                onTimerClick = { showTimerDialog = true },
                onPomodoroClick = { showPomodoroDialog = true },
                onSelectAppsClick = {
                    isEditingApps = true
                    showAppSelectionDialog = true
                },
                isStrictMode = uiState.isStrictModeEnabled,
                isHardMode = uiState.isHardModeEnabled
            )
        }

        // Statistics summary
        item {
            StatsSummaryCard(
                todayBlockCount = uiState.todayBlockCount,
                blockedAppsCount = uiState.blockedAppsCount,
                onAppsBlockedClick = { showBlockedAppsDialog = true }
            )
        }

        // Active Schedules
        if (uiState.activeSchedules.isNotEmpty()) {
            item {
                Text(
                    text = "Schedules",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.activeSchedules.take(5)) { schedule ->
                        SchedulePreviewCard(schedule = schedule)
                    }
                }
            }
        }

        // Strict Mode Card
        item {
            StrictModeCard(
                isEnabled = uiState.isStrictModeEnabled,
                isHardModeEnabled = uiState.isHardModeEnabled,
                onToggle = { viewModel.setStrictMode(it) },
                onHardModeClick = { showHardModeDialog = true }
            )
        }
    }
    } // End Scaffold

    // Dialogs
    if (showAppSelectionDialog) {
        AppSelectionDialog(
            apps = viewModel.getInstalledApps(),
            selectedApps = selectedApps,
            onDismiss = { showAppSelectionDialog = false },
            onConfirm = { selected ->
                selectedApps = selected
                viewModel.startQuickBlock(selected)
                showAppSelectionDialog = false
            }
        )
    }

    if (showTimerDialog) {
        TimerPickerDialog(
            onDismiss = { showTimerDialog = false },
            onConfirm = { minutes ->
                timerDurationMinutes = minutes
                showTimerDialog = false
                showAppSelectionDialog = true
            }
        )
    }

    if (showPomodoroDialog) {
        PomodoroSetupDialog(
            onDismiss = { showPomodoroDialog = false },
            onConfirm = { workMinutes, breakMinutes ->
                showPomodoroDialog = false
                showAppSelectionDialog = true
            }
        )
    }

    if (showHardModeDialog) {
        HardModeSetupDialog(
            onDismiss = { showHardModeDialog = false },
            onConfirm = { pin, unlockMinutes ->
                viewModel.setHardMode(true, pin, unlockMinutes)
                showHardModeDialog = false
            }
        )
    }

    // Blocked Apps Dialog - View and manage currently blocked apps
    if (showBlockedAppsDialog) {
        BlockedAppsDialog(
            blockedApps = uiState.blockedApps,
            onDismiss = { showBlockedAppsDialog = false },
            onEditApps = {
                showBlockedAppsDialog = false
                showAppSelectionDialog = true
            },
            onToggleApp = { packageName, blocked ->
                viewModel.toggleAppBlocked(packageName, blocked)
            }
        )
    }
}

@Composable
fun QuickBlockCard(
    isActive: Boolean,
    remainingTime: Long,
    endTime: Long?,
    blockedAppsCount: Int,
    isPomodoroMode: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onTimerClick: () -> Unit,
    onPomodoroClick: () -> Unit,
    onSelectAppsClick: () -> Unit,
    isStrictMode: Boolean,
    isHardMode: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Block",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                // Select Apps button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectAppsClick() }
                        .background(Primary.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Apps,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (blockedAppsCount > 0) "$blockedAppsCount apps" else "Select Apps",
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main button
            Button(
                onClick = { if (isActive) onStopClick() else onStartClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) AccentRed else Primary
                ),
                enabled = !isStrictMode && !isHardMode || !isActive
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isActive && remainingTime > 0) {
                        Text(
                            text = TimeUtils.formatTimerWithHours(remainingTime),
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text(
                            text = if (isActive) "Stop" else "Start",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // Active indicator with end time
            if (isActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusActive.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(StatusActive)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Blocking Active",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StatusActive
                            )
                            if (isPomodoroMode) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "• Pomodoro",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentOrange
                                )
                            }
                        }
                        if (endTime != null && endTime > 0) {
                            Text(
                                text = "until ${TimeUtils.getTimeString(endTime)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Timer and Pomodoro buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onTimerClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextPrimary
                    ),
                    border = BorderStroke(1.dp, Divider)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Timer")
                }

                OutlinedButton(
                    onClick = onPomodoroClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextPrimary
                    ),
                    border = BorderStroke(1.dp, Divider)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Pending,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pomodoro")
                }
            }
        }
    }
}

@Composable
fun StatsSummaryCard(
    todayBlockCount: Int,
    blockedAppsCount: Int,
    onAppsBlockedClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                value = todayBlockCount.toString(),
                label = "Blocks today",
                icon = Icons.Outlined.Block
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(48.dp)
                    .background(Divider)
            )
            // Make Apps blocked section clickable
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAppsBlockedClick() }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Apps,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = blockedAppsCount.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Apps blocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
fun SchedulePreviewCard(
    schedule: com.focusblock.app.database.entity.Schedule
) {
    val color = when (schedule.iconType) {
        com.focusblock.app.database.entity.ScheduleIconType.WORK -> ScheduleWork
        com.focusblock.app.database.entity.ScheduleIconType.SLEEP -> ScheduleSleep
        com.focusblock.app.database.entity.ScheduleIconType.STUDY -> ScheduleStudy
        com.focusblock.app.database.entity.ScheduleIconType.FAMILY -> ScheduleFamily
        com.focusblock.app.database.entity.ScheduleIconType.SOCIAL -> ScheduleSocial
        com.focusblock.app.database.entity.ScheduleIconType.DETOX -> ScheduleDetox
        else -> Primary
    }

    Card(
        modifier = Modifier
            .width(160.dp)
            .height(120.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (schedule.isEnabled) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(StatusActive.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusActive
                        )
                    }
                }
            }

            Column {
                Text(
                    text = schedule.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = "${TimeUtils.minutesToTimeString(schedule.startTimeMinutes)} - ${TimeUtils.minutesToTimeString(schedule.endTimeMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }
    }
}

@Composable
fun StrictModeCard(
    isEnabled: Boolean,
    isHardModeEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onHardModeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isEnabled) Primary.copy(alpha = 0.2f) else Divider),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (isEnabled) Primary else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Strict Mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isEnabled) "Cannot disable blocking" else "Lock your settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.5f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = Divider
                    )
                )
            }

            if (isEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Divider)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHardModeClick() }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Hard Mode",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Require PIN + time lock",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun BlockedAppsDialog(
    blockedApps: List<com.focusblock.app.database.entity.BlockedApp>,
    onDismiss: () -> Unit,
    onEditApps: () -> Unit,
    onToggleApp: (String, Boolean) -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Blocked Apps",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "${blockedApps.size} apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        },
        text = {
            if (blockedApps.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No apps blocked yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Add apps to start blocking",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(blockedApps) { app ->
                        val appInfo = try {
                            context.packageManager.getApplicationInfo(app.packageName, 0)
                        } catch (e: Exception) { null }
                        val appName = appInfo?.let {
                            context.packageManager.getApplicationLabel(it).toString()
                        } ?: app.packageName
                        val appIcon = appInfo?.let {
                            try { context.packageManager.getApplicationIcon(it) } catch (e: Exception) { null }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardDark)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (appIcon != null) {
                                    Image(
                                        bitmap = appIcon.toBitmap(48, 48).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Divider),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Apps,
                                            contentDescription = null,
                                            tint = TextSecondary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                            }
                            Switch(
                                checked = app.isBlocked,
                                onCheckedChange = { onToggleApp(app.packageName, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Primary,
                                    checkedTrackColor = Primary.copy(alpha = 0.5f),
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = Divider
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onEditApps,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit Apps")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
