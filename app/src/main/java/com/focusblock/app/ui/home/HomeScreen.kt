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
    var showUnlockPinDialog by remember { mutableStateOf(false) }
    var showStrictModeUnlockDialog by remember { mutableStateOf(false) }
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
                onStopClick = {
                    if (uiState.isHardModeEnabled) {
                        // Show PIN dialog for hard mode
                        showUnlockPinDialog = true
                    } else if (uiState.isStrictModeEnabled) {
                        // Show strict mode unlock dialog
                        showStrictModeUnlockDialog = true
                    } else {
                        viewModel.stopQuickBlock()
                    }
                },
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

        // Weekly Summary Card
        item {
            WeeklySummaryCard(
                weekBlockCount = uiState.weekBlockCount,
                todayBlockCount = uiState.todayBlockCount
            )
        }

        // Focus Tips
        item {
            FocusTipsCard()
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

    // Hard Mode Unlock PIN Dialog
    if (showUnlockPinDialog) {
        UnlockPinDialog(
            onDismiss = { showUnlockPinDialog = false },
            onVerify = { pin ->
                val success = viewModel.verifyPinAndStop(pin)
                if (success) {
                    showUnlockPinDialog = false
                }
                success
            }
        )
    }

    // Strict Mode Unlock Dialog
    if (showStrictModeUnlockDialog) {
        StrictModeUnlockDialog(
            onDismiss = { showStrictModeUnlockDialog = false },
            onDisableStrictMode = {
                viewModel.setStrictMode(false)
                viewModel.stopQuickBlock()
                showStrictModeUnlockDialog = false
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
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardDarkElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            // Header with icon and title
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
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Quick Block",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isActive) "Protection active" else "Start blocking apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) StatusActive else TextSecondary
                        )
                    }
                }
                // Select Apps button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectAppsClick() },
                    color = Primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Apps,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (blockedAppsCount > 0) "$blockedAppsCount apps" else "Select",
                            style = MaterialTheme.typography.labelLarge,
                            color = Primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main action button - Hero CTA
            Button(
                onClick = { if (isActive) onStopClick() else onStartClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) AccentRed else Primary
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 8.dp
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    if (isActive && remainingTime > 0) {
                        Text(
                            text = TimeUtils.formatTimerWithHours(remainingTime),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = if (isActive) "Stop Blocking" else "Start Blocking",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Active indicator with end time
            if (isActive) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = StatusActive.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(StatusActive)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Blocking Active",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StatusActive,
                                fontWeight = FontWeight.Medium
                            )
                            if (isPomodoroMode) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = AccentOrange.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Pomodoro",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentOrange,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
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

            Spacer(modifier = Modifier.height(20.dp))

            // Timer and Pomodoro buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onTimerClick() },
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceElevated,
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Timer",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPomodoroClick() },
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceElevated,
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Pending,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pomodoro",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Blocks today card
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null,
                        tint = AccentRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = todayBlockCount.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Blocks today",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Apps blocked card - clickable
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable { onAppsBlockedClick() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Apps,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = blockedAppsCount.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Apps blocked",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
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
            .width(170.dp)
            .height(130.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Accent bar on left
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (schedule.iconType) {
                                com.focusblock.app.database.entity.ScheduleIconType.WORK -> Icons.Outlined.Work
                                com.focusblock.app.database.entity.ScheduleIconType.SLEEP -> Icons.Outlined.Bedtime
                                com.focusblock.app.database.entity.ScheduleIconType.STUDY -> Icons.Outlined.School
                                else -> Icons.Outlined.Schedule
                            },
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (schedule.isEnabled) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(StatusActive)
                        )
                    }
                }

                Column {
                    Text(
                        text = schedule.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${TimeUtils.minutesToTimeString(schedule.startTimeMinutes)} - ${TimeUtils.minutesToTimeString(schedule.endTimeMinutes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun WeeklySummaryCard(
    weekBlockCount: Int,
    todayBlockCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentGreen.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.TrendingUp,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Weekly Summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = weekBlockCount.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Distractions\nblocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(50.dp)
                        .background(SurfaceBorder)
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val avgDaily = if (weekBlockCount > 0) weekBlockCount / 7 else 0
                    Text(
                        text = avgDaily.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Daily\naverage",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(50.dp)
                        .background(SurfaceBorder)
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val trend = if (todayBlockCount > 0 && weekBlockCount > 0) {
                        val avgDaily = weekBlockCount / 7
                        if (todayBlockCount > avgDaily) "↑" else if (todayBlockCount < avgDaily) "↓" else "→"
                    } else "→"
                    Text(
                        text = trend,
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (trend == "↓") AccentGreen else if (trend == "↑") AccentOrange else TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Today's\ntrend",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun FocusTipsCard() {
    val tips = listOf(
        "🎯 Start with short focus sessions and gradually increase duration",
        "📱 Put your phone face-down to reduce temptation",
        "⏰ Use the Pomodoro technique: 25 min work, 5 min break",
        "🌙 Enable Sleep schedule to avoid late-night scrolling",
        "💪 Celebrate small wins - every blocked distraction counts!",
        "🧘 Take regular breaks to maintain productivity",
        "📊 Check your stats weekly to track progress"
    )
    val currentTipIndex = remember { (System.currentTimeMillis() / (1000 * 60 * 60)).toInt() % tips.size }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Focus Tip",
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = tips[currentTipIndex],
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
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
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isEnabled) Primary.copy(alpha = 0.15f) else SurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (isEnabled) Primary else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Strict Mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
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
                        checkedThumbColor = TextPrimary,
                        checkedTrackColor = Primary,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SurfaceBorder
                    )
                )
            }

            if (isEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = SurfaceBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onHardModeClick() },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isHardModeEnabled) AccentOrange.copy(alpha = 0.1f) else SurfaceElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AccentOrange.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = AccentOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Hard Mode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (isHardModeEnabled) "Enabled" else "Require PIN + time lock",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isHardModeEnabled) AccentOrange else TextSecondary
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
        containerColor = CardDarkElevated,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Blocked Apps",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "${blockedApps.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            if (blockedApps.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(SurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Block,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No apps blocked yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add apps to start blocking",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = SurfaceElevated
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
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
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(SurfaceBorder),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Apps,
                                                contentDescription = null,
                                                tint = TextSecondary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = appName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                                Switch(
                                    checked = app.isBlocked,
                                    onCheckedChange = { onToggleApp(app.packageName, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = TextPrimary,
                                        checkedTrackColor = Primary,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = SurfaceBorder
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onEditApps,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit Apps", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}

@Composable
fun UnlockPinDialog(
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
                    text = "Enter PIN to Unlock",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Hard Mode is enabled. Enter your PIN to stop blocking.",
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
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
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
                Text("Unlock", fontWeight = FontWeight.Medium)
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
fun StrictModeUnlockDialog(
    onDismiss: () -> Unit,
    onDisableStrictMode: () -> Unit
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
                        .background(Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Strict Mode Active",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Strict Mode prevents you from stopping the block easily. Are you sure you want to disable it?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AccentRed.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = AccentRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "This will also stop the current blocking session",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentRed
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDisableStrictMode,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
            ) {
                Text("Disable & Stop", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep Blocking", color = Primary)
            }
        }
    )
}
