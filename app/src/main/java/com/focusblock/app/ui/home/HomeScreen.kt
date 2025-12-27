@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.focusblock.app.ui.home

import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
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
import com.focusblock.app.ui.components.StrictModeSetupDialog
import com.focusblock.app.ui.components.FocusCycleSetupDialog
import com.focusblock.app.ui.components.StrictModePauseDialog
import com.focusblock.app.service.FocusBlockAccessibilityService
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import com.focusblock.app.utils.TimeUtils
import com.focusblock.app.viewmodel.FocusCyclePhase
import com.focusblock.app.viewmodel.HomeViewModel
import com.focusblock.app.viewmodel.StrictModePauseResult
import com.focusblock.app.viewmodel.InsightsState
import com.focusblock.app.viewmodel.DistractionInsight
import com.focusblock.app.viewmodel.HourlyInsight
import com.focusblock.app.viewmodel.LongSessionRisk
import com.focusblock.app.viewmodel.ActionSuggestion
import com.focusblock.app.viewmodel.ActionType

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
    var showStrictModeSetupDialog by remember { mutableStateOf(false) }
    var showPauseMotivationDialog by remember { mutableStateOf(false) }
    var showFocusCycleSetupDialog by remember { mutableStateOf(false) }
    var showStrictModePauseDialog by remember { mutableStateOf(false) }
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

        // ========== BLOCKING SECTION ==========
        item {
            SectionHeader(
                title = "Quick Block",
                subtitle = "Instantly block distracting apps"
            )
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

        // ========== INSIGHTS SECTION ==========
        item {
            InsightsSection(
                insights = uiState.insights,
                onStartQuickBlock = { packageName ->
                    // Start Quick Block for the specific app
                    viewModel.startQuickBlock(listOf(packageName), 30)
                },
                onStartFocusCycle = { packageName, peakHour ->
                    // Show Focus Cycle setup for this app at peak hour
                    showFocusCycleSetupDialog = true
                }
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

        // ========== FOCUS MODES SECTION ==========
        item {
            SectionHeader(
                title = "Focus Modes",
                subtitle = "Control your digital habits"
            )
        }

        // Strict Mode Card
        item {
            StrictModeCard(
                isEnabled = uiState.isStrictModeEnabled,
                isLocked = uiState.isStrictModeLocked,
                isPaused = uiState.isStrictModePaused,
                remainingTime = uiState.strictModeRemainingTime,
                isHardModeEnabled = uiState.isHardModeEnabled,
                onToggle = { enabled ->
                    if (enabled) {
                        // Show duration picker when enabling
                        showStrictModeSetupDialog = true
                    } else if (!uiState.isStrictModeLocked) {
                        // Can only disable if not locked
                        viewModel.setStrictMode(false)
                    }
                },
                onHardModeClick = { showHardModeDialog = true },
                onAddTime = { minutes -> viewModel.addStrictModeTime(minutes) },
                onPauseClick = { showStrictModePauseDialog = true },
                onResumeClick = { viewModel.resumeStrictMode() }
            )
        }

        // Focus Cycles Card (Soft-Nudge Mode)
        item {
            val trackedAppsCount = uiState.focusCycle?.selectedPackages
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.size ?: 0
            val useQuickBlockApps = uiState.focusCycle?.useQuickBlockApps ?: true
            val isAccessibilityEnabled = PermissionUtils.hasAccessibilityServiceEnabled(context)

            FocusCycleCard(
                isEnabled = uiState.isFocusCycleEnabled,
                phase = uiState.focusCyclePhase,
                remainingTime = uiState.focusCycleRemainingTime,
                usageWindowMinutes = uiState.focusCycle?.usageWindowMinutes ?: 10,
                breakDurationMinutes = uiState.focusCycle?.breakDurationMinutes ?: 30,
                trackedAppsCount = trackedAppsCount,
                useQuickBlockApps = useQuickBlockApps,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onStartClick = { showFocusCycleSetupDialog = true },
                onStopClick = { viewModel.disableFocusCycle() },
                onEnableAccessibility = {
                    // Open accessibility settings
                    val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
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
            isLocked = uiState.isStrictModeLocked,
            remainingTime = uiState.strictModeRemainingTime,
            onDismiss = { showStrictModeUnlockDialog = false },
            onDisableStrictMode = {
                if (!uiState.isStrictModeLocked) {
                    viewModel.setStrictMode(false)
                    viewModel.stopQuickBlock()
                    showStrictModeUnlockDialog = false
                }
            }
        )
    }

    // Strict Mode Setup Dialog (Duration Picker)
    if (showStrictModeSetupDialog) {
        StrictModeSetupDialog(
            onDismiss = { showStrictModeSetupDialog = false },
            onConfirm = { durationMinutes ->
                viewModel.setStrictMode(true, durationMinutes)
                showStrictModeSetupDialog = false
            }
        )
    }

    // Pause Motivation Dialog (legacy)
    if (showPauseMotivationDialog) {
        PauseMotivationDialog(
            onDismiss = { showPauseMotivationDialog = false },
            onKeepFocused = { showPauseMotivationDialog = false },
            onPauseAnyway = {
                viewModel.pauseStrictMode()
                showPauseMotivationDialog = false
            }
        )
    }

    // Strict Mode Pause Dialog (new intentional flow)
    if (showStrictModePauseDialog) {
        StrictModePauseDialog(
            remainingPausesToday = uiState.strictModeRemainingPausesToday,
            maxPausesPerDay = uiState.strictModeMaxPausesPerDay,
            onDismiss = { showStrictModePauseDialog = false },
            onKeepFocused = { showStrictModePauseDialog = false },
            onPauseWithReason = { reason ->
                val result = viewModel.pauseStrictMode()
                if (result == StrictModePauseResult.LIMIT_REACHED) {
                    // Toast already shown by ViewModel, just close dialog
                }
                showStrictModePauseDialog = false
            }
        )
    }

    // Focus Cycle Setup Dialog
    if (showFocusCycleSetupDialog) {
        // Get blocked apps from database (apps marked as blocked), not from active Quick Block session
        val quickBlockApps = uiState.blockedApps
            .filter { it.isBlocked }
            .map { it.packageName }

        FocusCycleSetupDialog(
            apps = viewModel.getInstalledApps(),
            quickBlockApps = quickBlockApps,
            onDismiss = { showFocusCycleSetupDialog = false },
            onConfirm = { usageWindow, breakDuration, selectedPackages, useQuickBlockApps ->
                viewModel.enableFocusCycle(usageWindow, breakDuration, selectedPackages, useQuickBlockApps)
                showFocusCycleSetupDialog = false
            }
        )
    }
}

@Composable
fun PauseMotivationDialog(
    onDismiss: () -> Unit,
    onKeepFocused: () -> Unit,
    onPauseAnyway: () -> Unit
) {
    val motivationalTips = listOf(
        "📚 Read for 10 minutes - it'll refresh your mind!",
        "🚶 Take a 5-minute walk to boost creativity",
        "🧘 Try 3 minutes of deep breathing",
        "💧 Grab some water and stretch",
        "🎵 Listen to one calming song",
        "✍️ Write down 3 things you're grateful for"
    )
    val currentTip = remember { motivationalTips.random() }

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
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Stay focused a little longer?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "You're doing great! Taking a break now might break your momentum.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Motivational suggestion card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.1f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Instead, try this:",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentGreen,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentTip,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stats encouragement
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalFireDepartment,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Keep your focus streak going!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onKeepFocused,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Filled.Shield, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Keep Strict Mode", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onPauseAnyway,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Pause anyway",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    )
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
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
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
    isLocked: Boolean,
    isPaused: Boolean = false,
    remainingTime: Long,
    isHardModeEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onHardModeClick: () -> Unit,
    onAddTime: (Int) -> Unit = {},
    onPauseClick: () -> Unit = {},
    onResumeClick: () -> Unit = {}
) {
    // Format remaining time
    val remainingTimeText = if (remainingTime > 0) {
        val hours = (remainingTime / (1000 * 60 * 60)).toInt()
        val minutes = ((remainingTime / (1000 * 60)) % 60).toInt()
        val seconds = ((remainingTime / 1000) % 60).toInt()
        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    } else ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) Primary.copy(alpha = 0.08f) else CardDark
        ),
        border = if (isLocked) BorderStroke(1.dp, Primary.copy(alpha = 0.3f)) else null,
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
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isEnabled) Primary.copy(alpha = 0.15f) else SurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Filled.LockClock else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (isEnabled) Primary else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Strict Mode",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            if (isLocked) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Primary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "LOCKED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isLocked) "Time-locked until timer expires"
                                   else if (isEnabled) "Cannot disable blocking"
                                   else "Lock your settings with timer",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    enabled = !isLocked, // Disable switch when locked
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextPrimary,
                        checkedTrackColor = Primary,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SurfaceBorder,
                        disabledCheckedThumbColor = TextPrimary.copy(alpha = 0.6f),
                        disabledCheckedTrackColor = Primary.copy(alpha = 0.6f)
                    )
                )
            }

            // Show remaining time when locked
            if (isLocked && remainingTime > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.12f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Timer,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Unlocks in",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                Text(
                                    text = remainingTimeText,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Add Time buttons
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Add more time",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onAddTime(30) },
                                shape = RoundedCornerShape(10.dp),
                                color = AccentGreen.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "+30m",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AccentGreen,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onAddTime(60) },
                                shape = RoundedCornerShape(10.dp),
                                color = AccentGreen.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "+1h",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AccentGreen,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onAddTime(120) },
                                shape = RoundedCornerShape(10.dp),
                                color = AccentGreen.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "+2h",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AccentGreen,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }

                        // Pause button
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onPauseClick,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = AccentOrange
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pause Strict Mode", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Show paused state with resume button
            if (isPaused) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentOrange.copy(alpha = 0.12f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PauseCircle,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Strict Mode Paused",
                                style = MaterialTheme.typography.titleMedium,
                                color = AccentOrange,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onResumeClick,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentOrange
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Resume", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            if (isEnabled && !isLocked) {
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
    isLocked: Boolean = false,
    remainingTime: Long = 0,
    onDismiss: () -> Unit,
    onDisableStrictMode: () -> Unit
) {
    // Format remaining time
    val remainingTimeText = if (remainingTime > 0) {
        val hours = (remainingTime / (1000 * 60 * 60)).toInt()
        val minutes = ((remainingTime / (1000 * 60)) % 60).toInt()
        val seconds = ((remainingTime / 1000) % 60).toInt()
        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    } else ""

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
                        .background(if (isLocked) AccentRed.copy(alpha = 0.15f) else Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Filled.LockClock else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (isLocked) AccentRed else Primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isLocked) "Strict Mode Locked" else "Strict Mode Active",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLocked) {
                    // Show locked state with remaining time
                    Text(
                        text = "Strict Mode is time-locked and cannot be disabled until the timer expires.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.1f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Time remaining",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = remainingTimeText,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
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
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = AccentRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "No PIN, restart, or setting can bypass this lock",
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentRed
                            )
                        }
                    }
                } else {
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
            }
        },
        confirmButton = {
            if (isLocked) {
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("OK, I'll Wait", fontWeight = FontWeight.Medium)
                }
            } else {
                Button(
                    onClick = onDisableStrictMode,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text("Disable & Stop", fontWeight = FontWeight.Medium)
                }
            }
        },
        dismissButton = {
            if (!isLocked) {
                TextButton(onClick = onDismiss) {
                    Text("Keep Blocking", color = Primary)
                }
            }
        }
    )
}

/**
 * Section Header for UX clarity
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

/**
 * Focus Cycle Card - Soft-nudge mode for mindful app usage
 */
@Composable
fun FocusCycleCard(
    isEnabled: Boolean,
    phase: FocusCyclePhase,
    remainingTime: Long,
    usageWindowMinutes: Int,
    breakDurationMinutes: Int,
    trackedAppsCount: Int = 0,
    useQuickBlockApps: Boolean = true,
    isAccessibilityEnabled: Boolean = true,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onEnableAccessibility: () -> Unit = {}
) {
    val cycleColor = Color(0xFF9C27B0) // Purple
    val warningColor = Color(0xFFFF5722) // Deep Orange for warning

    // Format remaining time
    val remainingTimeText = if (remainingTime > 0) {
        val minutes = ((remainingTime / (1000 * 60)) % 60).toInt()
        val seconds = ((remainingTime / 1000) % 60).toInt()
        String.format("%02d:%02d", minutes, seconds)
    } else ""

    // App source label
    val appSourceLabel = if (useQuickBlockApps) {
        "Using blocked apps list"
    } else {
        "Using custom Focus Cycle list"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) cycleColor.copy(alpha = 0.08f) else CardDark
        ),
        border = if (isEnabled) BorderStroke(1.dp, cycleColor.copy(alpha = 0.3f)) else null,
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
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isEnabled) cycleColor.copy(alpha = 0.15f) else SurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Loop,
                            contentDescription = null,
                            tint = if (isEnabled) cycleColor else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Focus Cycles",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            if (isEnabled) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when (phase) {
                                        FocusCyclePhase.ARMED -> Color(0xFFFF9800).copy(alpha = 0.2f) // Orange
                                        FocusCyclePhase.USAGE_WINDOW -> AccentGreen.copy(alpha = 0.2f)
                                        FocusCyclePhase.PAUSED -> Color(0xFFFFEB3B).copy(alpha = 0.2f) // Yellow
                                        FocusCyclePhase.BREAK -> cycleColor.copy(alpha = 0.2f)
                                        else -> Color.Transparent
                                    }
                                ) {
                                    Text(
                                        text = when (phase) {
                                            FocusCyclePhase.ARMED -> "READY"
                                            FocusCyclePhase.USAGE_WINDOW -> "ACTIVE"
                                            FocusCyclePhase.PAUSED -> "PAUSED"
                                            FocusCyclePhase.BREAK -> "BREAK"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (phase) {
                                            FocusCyclePhase.ARMED -> Color(0xFFFF9800)
                                            FocusCyclePhase.USAGE_WINDOW -> AccentGreen
                                            FocusCyclePhase.PAUSED -> Color(0xFFFFC107)
                                            FocusCyclePhase.BREAK -> cycleColor
                                            else -> TextSecondary
                                        },
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isEnabled) {
                                when (phase) {
                                    FocusCyclePhase.ARMED -> "Open a tracked app to start timer"
                                    FocusCyclePhase.USAGE_WINDOW -> "Timer running while using apps"
                                    FocusCyclePhase.PAUSED -> "Timer paused - not on tracked app"
                                    FocusCyclePhase.BREAK -> "Take a mindful break"
                                    else -> "Soft-nudge for mindful usage"
                                }
                            } else "Soft-nudge for mindful usage",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                if (!isEnabled) {
                    Button(
                        onClick = onStartClick,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = cycleColor),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Start", fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Show accessibility warning if not enabled
            if (isEnabled && !isAccessibilityEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = warningColor.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, warningColor.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = warningColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Accessibility Service Required",
                                style = MaterialTheme.typography.titleSmall,
                                color = warningColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Focus Cycle needs Accessibility permission to detect when you open apps and track usage time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onEnableAccessibility,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = warningColor)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enable Accessibility Service")
                        }
                    }
                }
            }

            // Show cycle status when enabled
            if (isEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (phase) {
                            FocusCyclePhase.ARMED -> Color(0xFFFF9800).copy(alpha = 0.12f)
                            FocusCyclePhase.USAGE_WINDOW -> AccentGreen.copy(alpha = 0.12f)
                            FocusCyclePhase.PAUSED -> Color(0xFFFFC107).copy(alpha = 0.12f)
                            FocusCyclePhase.BREAK -> cycleColor.copy(alpha = 0.12f)
                            else -> SurfaceElevated
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (phase) {
                                    FocusCyclePhase.ARMED -> Icons.Filled.TouchApp
                                    FocusCyclePhase.USAGE_WINDOW -> Icons.Filled.PlayCircle
                                    FocusCyclePhase.PAUSED -> Icons.Filled.Pause
                                    FocusCyclePhase.BREAK -> Icons.Filled.SelfImprovement
                                    else -> Icons.Filled.Timer
                                },
                                contentDescription = null,
                                tint = when (phase) {
                                    FocusCyclePhase.ARMED -> Color(0xFFFF9800)
                                    FocusCyclePhase.USAGE_WINDOW -> AccentGreen
                                    FocusCyclePhase.PAUSED -> Color(0xFFFFC107)
                                    FocusCyclePhase.BREAK -> cycleColor
                                    else -> TextSecondary
                                },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when (phase) {
                                        FocusCyclePhase.ARMED -> "Waiting for app"
                                        FocusCyclePhase.USAGE_WINDOW -> "Usage remaining"
                                        FocusCyclePhase.PAUSED -> "Paused - remaining"
                                        FocusCyclePhase.BREAK -> "Break ends in"
                                        else -> "Cycle"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                Text(
                                    text = if (phase == FocusCyclePhase.ARMED) "${usageWindowMinutes}:00" else remainingTimeText,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = when (phase) {
                                        FocusCyclePhase.ARMED -> Color(0xFFFF9800)
                                        FocusCyclePhase.USAGE_WINDOW -> AccentGreen
                                        FocusCyclePhase.PAUSED -> Color(0xFFFFC107)
                                        FocusCyclePhase.BREAK -> cycleColor
                                        else -> TextPrimary
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Cycle info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${usageWindowMinutes}m",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AccentGreen,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Usage",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${breakDurationMinutes}m",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cycleColor,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Break",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$trackedAppsCount",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Apps",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        // Show app source
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = appSourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Info note based on phase
                val infoNote = when (phase) {
                    FocusCyclePhase.ARMED -> "Open any tracked app to begin counting usage time"
                    FocusCyclePhase.PAUSED -> "Timer will resume when you return to a tracked app"
                    FocusCyclePhase.BREAK -> "You can override if needed - this is soft-nudge mode"
                    else -> null
                }
                if (infoNote != null) {
                    val infoColor = when (phase) {
                        FocusCyclePhase.ARMED -> Color(0xFFFF9800)
                        FocusCyclePhase.PAUSED -> Color(0xFFFFC107)
                        FocusCyclePhase.BREAK -> cycleColor
                        else -> TextSecondary
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = infoColor.copy(alpha = 0.08f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = infoColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = infoNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Stop button
                OutlinedButton(
                    onClick = onStopClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, cycleColor.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cycleColor)
                ) {
                    Text("Stop Focus Cycles")
                }
            }
        }
    }
}

// ========== INSIGHTS SECTION ==========

@Composable
fun InsightsSection(
    insights: InsightsState,
    onStartQuickBlock: (String) -> Unit,
    onStartFocusCycle: (String, Int?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section Header
        Text(
            text = "Today's Insights",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )

        // Show "No data yet" card if insufficient data
        if (!insights.hasEnoughData) {
            NoInsightsCard()
            return@Column
        }

        // Biggest Distraction Card
        insights.biggestDistraction?.let { distraction ->
            BiggestDistractionCard(
                distraction = distraction,
                onAddFocusCycle = { onStartFocusCycle(distraction.packageName, distraction.peakHour) }
            )
        }

        // Hourly Insights Row
        insights.hourlyInsight?.let { hourly ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Best Focus Hour
                hourly.bestFocusHour?.let { hour ->
                    HourlyTile(
                        modifier = Modifier.weight(1f),
                        title = "Best Focus Hour",
                        hour = hour,
                        blockCount = hourly.bestFocusHourBlocks,
                        isPositive = true
                    )
                }

                // Worst Hour
                hourly.worstHour?.let { hour ->
                    HourlyTile(
                        modifier = Modifier.weight(1f),
                        title = "Weak Hour",
                        hour = hour,
                        blockCount = hourly.worstHourBlocks,
                        isPositive = false
                    )
                }
            }
        }

        // Long Session Risks
        if (insights.longSessionRisks.isNotEmpty()) {
            LongSessionRiskCard(
                sessions = insights.longSessionRisks,
                onStartQuickBlock = onStartQuickBlock
            )
        }

        // Context-aware Action Suggestions
        if (insights.actionSuggestions.isNotEmpty()) {
            ActionSuggestionsCard(
                suggestions = insights.actionSuggestions,
                onStartQuickBlock = onStartQuickBlock,
                onStartFocusCycle = onStartFocusCycle
            )
        }

        // Streak & Progress Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Focus Streak
            StreakTile(
                modifier = Modifier.weight(1f),
                streakDays = insights.focusStreakDays
            )

            // Time Saved
            TimeSavedTile(
                modifier = Modifier.weight(1f),
                minutesSaved = insights.weeklyTimeSavedMinutes
            )
        }
    }
}

@Composable
fun BiggestDistractionCard(
    distraction: DistractionInsight,
    onAddFocusCycle: () -> Unit
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentOrange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Biggest Distraction",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = distraction.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Block count badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentOrange.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${distraction.blockCount} blocks",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentOrange,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Peak hour info
            distraction.peakHour?.let { hour ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Peak time: ${formatHour(hour)} (${distraction.peakHourCount} blocks)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action button
            OutlinedButton(
                onClick = onAddFocusCycle,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentOrange)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Focus Cycle for this window", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun HourlyTile(
    modifier: Modifier = Modifier,
    title: String,
    hour: Int,
    blockCount: Int,
    isPositive: Boolean
) {
    val tileColor = if (isPositive) Primary else Color(0xFFE53935)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isPositive) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                tint = tileColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatHour(hour),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$blockCount blocks",
                style = MaterialTheme.typography.labelSmall,
                color = tileColor
            )
        }
    }
}

@Composable
fun LongSessionRiskCard(
    sessions: List<LongSessionRisk>,
    onStartQuickBlock: (String) -> Unit
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Long Session Alert",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You had extended usage sessions today. Consider setting limits.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            sessions.forEach { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onStartQuickBlock(session.packageName) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.appName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "${session.durationMinutes}min • ${formatHour(session.startHour)}-${formatHour(session.endHour)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = "Block",
                        tint = AccentOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ActionSuggestionsCard(
    suggestions: List<ActionSuggestion>,
    onStartQuickBlock: (String) -> Unit,
    onStartFocusCycle: (String, Int?) -> Unit
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Suggested Actions",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            suggestions.forEach { suggestion ->
                ActionSuggestionItem(
                    suggestion = suggestion,
                    onStartQuickBlock = onStartQuickBlock,
                    onStartFocusCycle = onStartFocusCycle
                )
                if (suggestion != suggestions.last()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun ActionSuggestionItem(
    suggestion: ActionSuggestion,
    onStartQuickBlock: (String) -> Unit,
    onStartFocusCycle: (String, Int?) -> Unit
) {
    val (icon, iconColor, buttonText, onClick) = when (suggestion.type) {
        ActionType.FOCUS_CYCLE_FOR_PEAK_TIME -> {
            Tuple4(
                Icons.Filled.Refresh,
                Color(0xFF4CAF50),
                "Start Focus Cycle",
                { onStartFocusCycle("", suggestion.peakStartHour) }
            )
        }
        ActionType.QUICK_BLOCK_DISTRACTION -> {
            Tuple4(
                Icons.Filled.Block,
                AccentOrange,
                "Block Now",
                { suggestion.targetPackage?.let { onStartQuickBlock(it) } }
            )
        }
        ActionType.SET_TIME_LIMIT -> {
            Tuple4(
                Icons.Filled.Timer,
                Color(0xFF2196F3),
                "Set Limit",
                { /* Navigate to time limits */ }
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = suggestion.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = suggestion.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = iconColor)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(buttonText, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// Helper data class for destructuring
private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun StreakTile(
    modifier: Modifier = Modifier,
    streakDays: Int
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$streakDays",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "day streak",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun TimeSavedTile(
    modifier: Modifier = Modifier,
    minutesSaved: Int
) {
    val hoursAndMinutes = if (minutesSaved >= 60) {
        "${minutesSaved / 60}h ${minutesSaved % 60}m"
    } else {
        "${minutesSaved}m"
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.TrendingUp,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = hoursAndMinutes,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "saved this week",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

private fun formatHour(hour: Int): String {
    return when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
}

@Composable
fun NoInsightsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Insights,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "No insights yet",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Start using Focus Block to see your distraction patterns and productivity insights here.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Primary.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lightbulb,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Try Quick Block or set up a Focus Cycle to get started",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary
                    )
                }
            }
        }
    }
}
