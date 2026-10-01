package com.focusblock.app.ui.focus

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.ui.theme.BackgroundDark
import com.focusblock.app.ui.theme.CardDark
import com.focusblock.app.ui.theme.Divider
import com.focusblock.app.ui.theme.Primary
import com.focusblock.app.ui.theme.SurfaceDark
import com.focusblock.app.ui.theme.TextPrimary
import com.focusblock.app.ui.theme.TextSecondary
import com.focusblock.app.ui.theme.WarningOrange
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.viewmodel.HomeViewModel
import com.focusblock.app.viewmodel.InsightsViewModel
import com.focusblock.app.viewmodel.SchedulesViewModel
import kotlinx.coroutines.launch

private enum class PickerFilter(val label: String) {
    MOST_USED("Most used"), RECENT("Recent"), ALL("All apps")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusExperienceScreen(
    onOpenSettings: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
    insightsViewModel: InsightsViewModel = hiltViewModel(),
    schedulesViewModel: SchedulesViewModel = hiltViewModel()
) {
    val state by homeViewModel.uiState.collectAsState()
    val insights by insightsViewModel.uiState.collectAsState()
    val installedApps by homeViewModel.installedAppsState.collectAsState()
    val schedules by schedulesViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var selectedPackages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var intention by rememberSaveable { mutableStateOf("") }
    var durationMinutes by rememberSaveable { mutableStateOf(45) }
    var strictLock by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showCompletion by rememberSaveable { mutableStateOf(false) }
    var previouslyActive by remember { mutableStateOf(false) }
    var suppressNextCompletion by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        selectedPackages = homeViewModel.getSavedQuickBlockApps()
        intention = homeViewModel.getLastFocusIntention()
        durationMinutes = homeViewModel.getLastFocusDurationMinutes()
        strictLock = homeViewModel.getLastFocusStrict()
    }
    LaunchedEffect(state.isQuickBlockActive) {
        if (previouslyActive && !state.isQuickBlockActive) {
            if (!suppressNextCompletion) showCompletion = true
            suppressNextCompletion = false
        }
        previouslyActive = state.isQuickBlockActive
    }

    val appByPackage = remember(installedApps) { installedApps.associateBy { it.packageName } }
    val activePackages = state.quickBlockSession?.blockedPackages
        ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val selectedApps = selectedPackages.mapNotNull(appByPackage::get)
    val activeApps = activePackages.mapNotNull(appByPackage::get)
    val worstHour = state.insights.hourlyInsight?.worstHour
    val nextSchedule = remember(schedules.schedules) { findNextSchedule(schedules.schedules) }

    Scaffold(
        containerColor = BackgroundDark,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        AnimatedContent(
            targetState = state.isQuickBlockActive,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "focus-state",
            modifier = Modifier.padding(padding)
        ) { active ->
            if (active) {
                ActiveFocusContent(
                    intention = intention,
                    state = state,
                    apps = activeApps,
                    onExtend = { homeViewModel.extendQuickBlock(15) },
                    onFinish = {
                        if (state.isStrictModeLocked) {
                            scope.launch { snackbar.showSnackbar("Strict Lock is active until the session ends.") }
                        } else {
                            showCompletion = true
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 22.dp, 20.dp, 36.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item {
                        FocusHeader(durationMinutes, onOpenSettings)
                        Text(
                            text = if (worstHour != null) {
                                "Your highest-distraction window usually begins around ${formatHour(worstHour)}."
                            } else {
                                "FocusBlock will learn when your attention tends to drift."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.permissionStatus.hasRequiredPermissions) Primary else WarningOrange))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (state.permissionStatus.hasRequiredPermissions) "Protection ready · required permissions active"
                                else "Protection needs permission setup",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.permissionStatus.hasRequiredPermissions) Primary else WarningOrange,
                                modifier = Modifier.clickable(enabled = !state.permissionStatus.hasRequiredPermissions, onClick = onOpenSettings)
                            )
                        }
                        AttentionBrief(state.insights.hourlyInsight?.worstHourBlocks ?: 0, worstHour)
                        if (selectedApps.isNotEmpty()) {
                            RecommendationRow(
                                intention = intention,
                                durationMinutes = durationMinutes,
                                appCount = selectedApps.size,
                                strict = strictLock,
                                onClick = {
                                    if (intention.isBlank()) intention = "Deep work"
                                    homeViewModel.saveFocusSetup(intention.ifBlank { "Deep work" }, durationMinutes, strictLock, selectedPackages)
                                    homeViewModel.setStrictMode(strictLock, durationMinutes)
                                    homeViewModel.startQuickBlock(selectedPackages, durationMinutes)
                                }
                            )
                        }
                        FocusComposer(
                            intention = intention,
                            onIntentionChange = { intention = it },
                            selectedApps = selectedApps,
                            durationMinutes = durationMinutes,
                            onDurationChange = { durationMinutes = it },
                            strictLock = strictLock,
                            onStrictChange = { strictLock = it },
                            onPickApps = { showPicker = true },
                            onStart = {
                                when {
                                    selectedPackages.isEmpty() -> scope.launch { snackbar.showSnackbar("Choose at least one distracting app.") }
                                    else -> {
                                        val finalIntention = intention.trim().ifBlank { "Focused work" }
                                        intention = finalIntention
                                        homeViewModel.saveFocusSetup(finalIntention, durationMinutes, strictLock, selectedPackages)
                                        homeViewModel.setStrictMode(strictLock, durationMinutes)
                                        homeViewModel.startQuickBlock(selectedPackages, durationMinutes)
                                    }
                                }
                            }
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 22.dp),
                            horizontalArrangement = Arrangement.spacedBy(22.dp)
                        ) {
                            TodayMetric("${state.focusStreak}", "day focus streak", Modifier.weight(1f))
                            TodayMetric("${state.todayBlockCount}", "interruptions stopped", Modifier.weight(1f))
                        }
                        HorizontalDivider(color = Divider)
                        Column(Modifier.padding(top = 20.dp)) {
                            Eyebrow("Next protection")
                            Text(
                                nextSchedule?.name ?: "No scheduled guardrail",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                nextSchedule?.let { "${formatScheduleTime(it.startTimeMinutes)} · ${it.blockedPackages.split(',').count { p -> p.isNotBlank() }} apps" }
                                    ?: "Add a routine in Guardrails",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        FocusAppPickerSheet(
            apps = installedApps,
            usageOrder = insights.mostUsedApps.map { it.packageName },
            recentPackages = selectedPackages,
            selectedPackages = selectedPackages.toSet(),
            onSelectionChange = {
                selectedPackages = it.toList()
                homeViewModel.saveQuickBlockSelection(selectedPackages)
            },
            onDismiss = { showPicker = false }
        )
    }

    if (showCompletion) {
        SessionCompletionSheet(
            durationMinutes = durationMinutes,
            intention = intention,
            isStillActive = state.isQuickBlockActive,
            onOutcome = { outcome ->
                homeViewModel.recordFocusOutcome(outcome)
                suppressNextCompletion = true
                if (state.isQuickBlockActive) homeViewModel.stopQuickBlock(forceStop = false)
                showCompletion = false
            },
            onContinue = {
                if (state.isQuickBlockActive) homeViewModel.extendQuickBlock(15)
                else homeViewModel.startQuickBlock(selectedPackages, 15)
                showCompletion = false
            },
            onDismiss = { showCompletion = false }
        )
    }
}

@Composable
private fun FocusHeader(durationMinutes: Int, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Eyebrow("Focus")
            Text(
                "Protect the next\n$durationMinutes minutes.",
                color = TextPrimary,
                fontSize = 30.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.7).sp
            )
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(44.dp).border(1.dp, Divider, CircleShape)
        ) {
            Icon(Icons.Outlined.Settings, "Settings", tint = TextPrimary)
        }
    }
}

@Composable
private fun AttentionBrief(attempts: Int, worstHour: Int?) {
    Column(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp)
            .border(width = 0.dp, color = Color.Transparent)
    ) {
        HorizontalDivider(color = Divider)
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                if (worstHour != null) "Attention drift peaks near ${formatHour(worstHour)}"
                else "Your attention pattern is still forming",
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (attempts > 0) "$attempts attempts" else "Needs more data",
                color = Primary,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Row(
            Modifier.fillMaxWidth().height(42.dp).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val levels = if (attempts > 0) listOf(.16f, .22f, .30f, .42f, .72f, 1f, .48f) else listOf(.18f, .20f, .17f, .22f, .19f, .21f, .18f)
            levels.forEachIndexed { index, level ->
                Box(
                    Modifier.weight(1f).fillMaxWidth().height((30 * level).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (attempts > 0 && index >= 4) WarningOrange.copy(alpha = .78f) else CardDark)
                )
            }
        }
        HorizontalDivider(color = Divider)
    }
}

@Composable
private fun RecommendationRow(
    intention: String,
    durationMinutes: Int,
    appCount: Int,
    strict: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp)
            .clip(RoundedCornerShape(14.dp)).background(CardDark)
            .border(1.dp, Divider, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = Primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Recommended: repeat last focus", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(
                "${intention.ifBlank { "Focused work" }} · ${durationMinutes}m · $appCount apps${if (strict) " · Strict" else ""}",
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextSecondary)
    }
}

@Composable
private fun FocusComposer(
    intention: String,
    onIntentionChange: (String) -> Unit,
    selectedApps: List<AppUtils.AppInfo>,
    durationMinutes: Int,
    onDurationChange: (Int) -> Unit,
    strictLock: Boolean,
    onStrictChange: (Boolean) -> Unit,
    onPickApps: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardDark)
            .border(1.dp, Divider, RoundedCornerShape(18.dp)).padding(16.dp)
    ) {
        Eyebrow("Set this focus")
        Text("One outcome", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
        BasicTextField(
            value = intention,
            onValueChange = onIntentionChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 18.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Primary),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            decorationBox = { field ->
                Box {
                    if (intention.isBlank()) Text("What do you want to finish?", color = TextSecondary, fontSize = 18.sp)
                    field()
                }
            }
        )
        HorizontalDivider(color = Divider)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onPickApps).padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Apps blocked in this focus", color = TextPrimary, fontWeight = FontWeight.Medium)
                Text("Tap to change the selection", color = TextSecondary, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    items(selectedApps.take(5), key = { it.packageName }) { app ->
                        InstalledAppIcon(app.icon, app.appName, 42.dp)
                    }
                    if (selectedApps.isEmpty()) item { Text("Choose from installed apps", color = Primary, fontSize = 12.sp) }
                }
            }
            Text("Choose", color = Primary, fontSize = 12.sp)
            Icon(Icons.Outlined.ChevronRight, null, tint = Primary, modifier = Modifier.size(18.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 45, 60, 90).forEach { minutes ->
                DurationChoice(
                    text = if (minutes == 60) "1h" else if (minutes == 90) "90m" else "${minutes}m",
                    selected = durationMinutes == minutes,
                    onClick = { onDurationChange(minutes) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 18.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Lock, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Strict Lock", color = TextPrimary, fontWeight = FontWeight.Medium)
                Text("Prevents stopping or changing this session", color = TextSecondary, fontSize = 12.sp)
            }
            Switch(
                checked = strictLock,
                onCheckedChange = onStrictChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = Primary,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CardDark,
                    uncheckedBorderColor = Divider
                )
            )
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
        ) {
            Text("Start $durationMinutes-minute focus", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DurationChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) TextPrimary else Color.Transparent)
            .border(1.dp, if (selected) TextPrimary else Divider, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) BackgroundDark else TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun ActiveFocusContent(
    intention: String,
    state: com.focusblock.app.viewmodel.HomeUiState,
    apps: List<AppUtils.AppInfo>,
    onExtend: () -> Unit,
    onFinish: () -> Unit
) {
    val end = state.quickBlockSession?.endTime
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 28.dp, 20.dp, 40.dp)
    ) {
        item {
            Text("PROTECTION ACTIVE", color = Primary, fontSize = 12.sp, letterSpacing = 1.3.sp)
            Text(
                if (end != null) "Focus until\n${formatClock(end)}" else "Focus is\nprotected",
                color = TextPrimary,
                fontSize = 30.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                formatRemaining(state.remainingTime),
                color = TextPrimary,
                fontSize = 50.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-1.5).sp,
                modifier = Modifier.padding(top = 18.dp)
            )
            Text(
                "${intention.ifBlank { "Focused work" }} · ${if (state.isStrictModeLocked) "Strict protection" else "Normal protection"}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 24.dp))
            Column(Modifier.padding(vertical = 20.dp)) {
                Text("Currently blocked", color = TextPrimary, fontWeight = FontWeight.Medium)
                Text("Attempts become part of your attention history", color = TextSecondary, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 15.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                            InstalledAppIcon(app.icon, app.appName)
                            Text(app.appName, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            HorizontalDivider(color = Divider)
            OutlinedButton(
                onClick = onExtend,
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, Divider)
            ) { Text("Add 15 minutes") }
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth().height(58.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
            ) { Text(if (state.isStrictModeLocked) "Strict Lock active" else "Finish session") }
            Text(
                "${state.todayBlockCount} interruptions stopped today",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusAppPickerSheet(
    apps: List<AppUtils.AppInfo>,
    usageOrder: List<String>,
    recentPackages: List<String>,
    selectedPackages: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(PickerFilter.MOST_USED) }
    var query by rememberSaveable { mutableStateOf("") }
    val usageRank = remember(usageOrder) { usageOrder.withIndex().associate { it.value to it.index } }
    val recentRank = remember(recentPackages) { recentPackages.withIndex().associate { it.value to it.index } }
    val displayed = remember(apps, filter, query, usageRank, recentRank) {
        val filtered = apps.filter { it.appName.contains(query, ignoreCase = true) }
        when (filter) {
            PickerFilter.MOST_USED -> filtered.sortedWith(compareBy({ usageRank[it.packageName] ?: Int.MAX_VALUE }, { it.appName.lowercase() }))
            PickerFilter.RECENT -> filtered.sortedWith(compareBy({ recentRank[it.packageName] ?: Int.MAX_VALUE }, { it.appName.lowercase() }))
            PickerFilter.ALL -> filtered.sortedBy { it.appName.lowercase() }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        contentColor = TextPrimary,
        dragHandle = { Box(Modifier.padding(top = 10.dp).width(38.dp).height(4.dp).clip(CircleShape).background(Divider)) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Eyebrow("For this focus")
                    Text("Choose apps to block", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.border(1.dp, Divider, CircleShape)) {
                    Icon(Icons.Outlined.Close, "Close", tint = TextPrimary)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceDark).padding(13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${selectedPackages.size} apps selected", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text("Saved automatically for next time", color = TextSecondary, fontSize = 12.sp)
                }
                Icon(Icons.Outlined.Check, null, tint = Primary)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 15.dp).clip(RoundedCornerShape(12.dp)).background(BackgroundDark).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = TextSecondary, modifier = Modifier.size(19.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    modifier = Modifier.weight(1f).padding(12.dp),
                    decorationBox = { field ->
                        Box {
                            if (query.isBlank()) Text("Search installed apps", color = TextSecondary, fontSize = 14.sp)
                            field()
                        }
                    }
                )
            }
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerFilter.values().forEach { item ->
                    Box(
                        Modifier.clip(CircleShape)
                            .background(if (filter == item) TextPrimary else Color.Transparent)
                            .border(1.dp, if (filter == item) TextPrimary else Divider, CircleShape)
                            .clickable { filter = item }.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(item.label, color = if (filter == item) BackgroundDark else TextPrimary, fontSize = 12.sp) }
                }
            }
            Text(
                if (filter == PickerFilter.MOST_USED && usageOrder.isNotEmpty()) "Ordered from your real device usage. You make the final choice."
                else "Choose from apps installed on this device.",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayed, key = { it.packageName }) { app ->
                    val selected = app.packageName in selectedPackages
                    Column(
                        modifier = Modifier.clickable {
                            onSelectionChange(if (selected) selectedPackages - app.packageName else selectedPackages + app.packageName)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box {
                            InstalledAppIcon(app.icon, app.appName, 50.dp)
                            if (selected) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Primary)
                                        .border(2.dp, CardDark, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                            }
                        }
                        Text(app.appName, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
            Text("Always reachable apps are managed in Settings", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 8.dp),
                enabled = selectedPackages.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
            ) { Text("Use this selection") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCompletionSheet(
    durationMinutes: Int,
    intention: String,
    isStillActive: Boolean,
    onOutcome: (String) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardDark, contentColor = TextPrimary) {
        Column(Modifier.fillMaxWidth().padding(20.dp, 8.dp, 20.dp, 30.dp)) {
            Eyebrow("$durationMinutes-minute focus complete")
            Text(
                if (intention.isBlank()) "Did you finish what you planned?" else "Did you finish $intention?",
                color = TextPrimary,
                fontSize = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Your answer improves future suggestions. It does not rewrite your blocking history.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 9.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onOutcome("finished") },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
                ) { Text("Finished") }
                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Divider),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) { Text("Continue 15m") }
            }
            TextButton(onClick = { onOutcome("unfinished") }, modifier = Modifier.fillMaxWidth()) {
                Text("Not finished", color = TextSecondary)
            }
        }
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(text.uppercase(), color = TextSecondary, fontSize = 12.sp, letterSpacing = 1.25.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun TodayMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.border(width = 0.dp, color = Color.Transparent).padding(start = 10.dp)) {
        Text(value, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

private fun formatHour(hour: Int): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val display = when (val value = hour % 12) { 0 -> 12; else -> value }
    return "$display:00 $suffix"
}

private fun findNextSchedule(schedules: List<com.focusblock.app.database.entity.Schedule>): com.focusblock.app.database.entity.Schedule? {
    val now = java.util.Calendar.getInstance()
    val currentDay = when (now.get(java.util.Calendar.DAY_OF_WEEK)) {
        java.util.Calendar.MONDAY -> 1
        java.util.Calendar.TUESDAY -> 2
        java.util.Calendar.WEDNESDAY -> 3
        java.util.Calendar.THURSDAY -> 4
        java.util.Calendar.FRIDAY -> 5
        java.util.Calendar.SATURDAY -> 6
        else -> 7
    }
    val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    return schedules.filter { it.isEnabled }.minByOrNull { schedule ->
        val days = schedule.daysOfWeek.split(',').mapNotNull(String::toIntOrNull)
        (0..7).mapNotNull { offset ->
            val candidateDay = ((currentDay - 1 + offset) % 7) + 1
            if (candidateDay !in days) null
            else {
                val delta = offset * 1_440 + schedule.startTimeMinutes - minuteNow
                if (delta >= 0) delta else null
            }
        }.minOrNull() ?: Int.MAX_VALUE
    }
}
