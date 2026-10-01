package com.focusblock.app.ui.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.ui.statistics.AppUsageInfo
import com.focusblock.app.ui.theme.BackgroundDark
import com.focusblock.app.ui.theme.CardDark
import com.focusblock.app.ui.theme.Divider
import com.focusblock.app.ui.theme.Primary
import com.focusblock.app.ui.theme.SurfaceDark
import com.focusblock.app.ui.theme.TextPrimary
import com.focusblock.app.ui.theme.TextSecondary
import com.focusblock.app.ui.theme.WarningOrange
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.viewmodel.ActionSuggestion
import com.focusblock.app.viewmodel.ActionType
import com.focusblock.app.viewmodel.HomeViewModel
import com.focusblock.app.viewmodel.InsightsTab
import com.focusblock.app.viewmodel.InsightsViewModel
import com.focusblock.app.viewmodel.SchedulesViewModel
import com.focusblock.app.viewmodel.StatisticsViewModel
import com.focusblock.app.viewmodel.StatsPeriod
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun GuardrailsScreen(
    onOpenScheduleEditor: () -> Unit,
    onOpenProtectionSettings: () -> Unit,
    schedulesViewModel: SchedulesViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val scheduleState by schedulesViewModel.uiState.collectAsState()
    val homeState by homeViewModel.uiState.collectAsState()
    val apps by homeViewModel.installedAppsState.collectAsState()
    val appMap = remember(apps) { apps.associateBy { it.packageName } }
    val enabledSchedules = scheduleState.schedules.filter { it.isEnabled }
    val layerCount = enabledSchedules.size +
        (if (homeState.isGlobalDailyLimitEnabled || homeState.isAppTimerEnabled) 1 else 0) +
        (if (homeState.isBedtimeModeEnabled) 1 else 0)
    var confirmGap by rememberSaveable { mutableStateOf(false) }
    val riskHour = homeState.insights.hourlyInsight?.worstHour
    val hasGap = riskHour != null && (!homeState.isBedtimeModeEnabled || riskHour < homeState.bedtimeStartHour)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BackgroundDark),
        contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, 38.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Eyebrow("Guardrails")
                    Text("Your protection\nsystem", color = TextPrimary, fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(
                    onClick = onOpenScheduleEditor,
                    modifier = Modifier.size(44.dp).border(1.dp, Divider, CircleShape)
                ) { Icon(Icons.Outlined.Add, "Add guardrail", tint = TextPrimary) }
            }
            Text(
                "Layer routines, daily boundaries and vulnerable hours so focus does not depend on repeated setup.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            CoverageOverview(enabledSchedules, homeState.isBedtimeModeEnabled, homeState.bedtimeStartHour, layerCount)
            Text("Protection layers", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }
        items(scheduleState.schedules, key = { "schedule-${it.id}" }) { schedule ->
            GuardrailLayer(
                icon = Icons.Outlined.Work,
                title = schedule.name,
                description = "${daysLabel(schedule.daysOfWeek)} · ${formatScheduleTime(schedule.startTimeMinutes)}–${formatScheduleTime(schedule.endTimeMinutes)} · ${packageCount(schedule.blockedPackages)} apps",
                strength = if (schedule.isStrictMode) "Strict" else if (schedule.isEnabled) "Ready" else "Paused",
                apps = schedule.blockedPackages.split(',').mapNotNull { appMap[it] }.take(4),
                enabled = schedule.isEnabled,
                onToggle = { schedulesViewModel.toggleSchedule(schedule.id, it) },
                onClick = onOpenScheduleEditor
            )
        }
        item {
            GuardrailLayer(
                icon = Icons.Outlined.Speed,
                title = "Daily boundaries",
                description = when {
                    homeState.isGlobalDailyLimitEnabled -> "${homeState.currentDailyUsageMinutes}m of ${homeState.globalDailyLimitMinutes}m used · resets at midnight"
                    homeState.isAppTimerEnabled -> "${homeState.appTimerUsageMinutes}m of ${homeState.appTimerLimitMinutes}m used across ${homeState.appTimerAppsCount} apps"
                    else -> "No daily usage boundary is active"
                },
                strength = if (homeState.isHardModeLocked) "Locked" else if (homeState.isGlobalDailyLimitEnabled || homeState.isAppTimerEnabled) "Adaptive" else "Off",
                apps = homeState.trackedAppsUsage.mapNotNull { appMap[it.packageName] }.take(4),
                enabled = homeState.isGlobalDailyLimitEnabled || homeState.isAppTimerEnabled,
                onToggle = { homeViewModel.setGlobalDailyLimitEnabled(it) },
                onClick = onOpenProtectionSettings
            )
            GuardrailLayer(
                icon = Icons.Outlined.Bedtime,
                title = "Wind down",
                description = "${formatScheduleTime(homeState.bedtimeStartHour * 60 + homeState.bedtimeStartMinute)}–${formatScheduleTime(homeState.bedtimeEndHour * 60 + homeState.bedtimeEndMinute)} · essential apps stay reachable",
                strength = if (homeState.isBedtimeModeEnabled) "Ready" else "Off",
                apps = emptyList(),
                enabled = homeState.isBedtimeModeEnabled,
                onToggle = { homeViewModel.toggleBedtimeMode() },
                onClick = onOpenProtectionSettings
            )
            if (hasGap) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 21.dp).border(width = 0.dp, color = Color.Transparent)
                ) {
                    Row {
                        Box(Modifier.width(3.dp).height(62.dp).background(WarningOrange))
                        Column(Modifier.padding(start = 13.dp)) {
                            Text("Protection gap detected", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                "Most block attempts cluster near ${formatHour(riskHour ?: 0)}, outside your current Wind down protection.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                    Button(
                        onClick = { confirmGap = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
                    ) { Text("Cover this gap") }
                }
            }
            OutlinedButton(
                onClick = onOpenScheduleEditor,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Divider),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text("Add another guardrail") }
        }
    }

    if (confirmGap && riskHour != null) {
        AlertDialog(
            onDismissRequest = { confirmGap = false },
            containerColor = CardDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Move Wind down earlier?") },
            text = { Text("Wind down will start at ${formatHour(riskHour)}. Your end time and essential apps will stay unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    homeViewModel.setBedtimeTimes(riskHour, 0, homeState.bedtimeEndHour, homeState.bedtimeEndMinute)
                    if (!homeState.isBedtimeModeEnabled) homeViewModel.toggleBedtimeMode()
                    confirmGap = false
                }) { Text("Update protection", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { confirmGap = false }) { Text("Keep current", color = TextSecondary) } }
        )
    }
}

@Composable
private fun CoverageOverview(schedules: List<Schedule>, bedtime: Boolean, bedtimeStart: Int, count: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Today's coverage", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text("$count protections ready", color = TextSecondary, fontSize = 12.sp)
        }
        val track = CardDark
        val moss = Primary
        val night = WarningOrange
        Canvas(Modifier.fillMaxWidth().height(22.dp).padding(top = 10.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
            schedules.forEach { schedule ->
                val start = schedule.startTimeMinutes.coerceIn(0, 1_440) / 1_440f
                val end = schedule.endTimeMinutes.coerceIn(0, 1_440) / 1_440f
                if (end >= start) {
                    drawRoundRect(moss, topLeft = Offset(size.width * start, 0f), size = Size(size.width * (end - start), size.height), cornerRadius = CornerRadius(size.height / 2))
                } else {
                    drawRoundRect(moss, size = Size(size.width * end, size.height), cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(moss, topLeft = Offset(size.width * start, 0f), size = Size(size.width * (1f - start), size.height), cornerRadius = CornerRadius(size.height / 2))
                }
            }
            if (bedtime) {
                val start = bedtimeStart.coerceIn(0, 23) / 24f
                drawRoundRect(night, topLeft = Offset(size.width * start, 0f), size = Size(size.width * (1f - start), size.height), cornerRadius = CornerRadius(size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("12 AM", color = TextSecondary, fontSize = 10.sp)
            Text("6 AM", color = TextSecondary, fontSize = 10.sp)
            Text("12 PM", color = TextSecondary, fontSize = 10.sp)
            Text("6 PM", color = TextSecondary, fontSize = 10.sp)
            Text("12 AM", color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun GuardrailLayer(
    icon: ImageVector,
    title: String,
    description: String,
    strength: String,
    apps: List<AppUtils.AppInfo>,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(CardDark), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(description, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            if (apps.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 9.dp)) {
                    items(apps, key = { it.packageName }) { app -> InstalledAppIcon(app.icon, app.appName, 26.dp) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(strength, color = if (enabled) Primary else TextSecondary, fontSize = 10.sp, modifier = Modifier.border(1.dp, Divider, CircleShape).padding(horizontal = 7.dp, vertical = 5.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(top = 6.dp).size(width = 42.dp, height = 26.dp),
                colors = SwitchDefaults.colors(checkedTrackColor = Primary, checkedThumbColor = TextPrimary, uncheckedTrackColor = CardDark, uncheckedBorderColor = Divider)
            )
        }
    }
    HorizontalDivider(color = Divider)
}

@Composable
fun PatternsScreen(
    onOpenFocus: () -> Unit,
    insightsViewModel: InsightsViewModel = hiltViewModel(),
    statisticsViewModel: StatisticsViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val insights by insightsViewModel.uiState.collectAsState()
    val statistics by statisticsViewModel.uiState.collectAsState()
    val home by homeViewModel.uiState.collectAsState()
    val apps by homeViewModel.installedAppsState.collectAsState()
    val appMap = remember(apps) { apps.associateBy { it.packageName } }
    val attemptsByHour = remember(statistics.recentBlocks) {
        statistics.recentBlocks.groupingBy {
            Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY)
        }.eachCount()
    }
    var pendingSuggestion by remember { mutableStateOf<ActionSuggestion?>(null) }

    LaunchedEffect(insights.selectedTab) {
        statisticsViewModel.setPeriod(
            when (insights.selectedTab) {
                InsightsTab.DAY -> StatsPeriod.TODAY
                InsightsTab.WEEK -> StatsPeriod.WEEK
                InsightsTab.TREND -> StatsPeriod.MONTH
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BackgroundDark),
        contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, 40.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Eyebrow("Patterns")
                    Text("Your attention\npatterns", color = TextPrimary, fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = { insightsViewModel.goToPreviousDay() }, modifier = Modifier.size(44.dp).border(1.dp, Divider, CircleShape)) {
                    Icon(Icons.Outlined.DateRange, "Change review period", tint = TextPrimary)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightsTab.values().forEach { tab ->
                    val selected = insights.selectedTab == tab
                    Text(
                        tab.label,
                        color = if (selected) BackgroundDark else TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f).clip(CircleShape)
                            .background(if (selected) TextPrimary else Color.Transparent)
                            .border(1.dp, if (selected) TextPrimary else Divider, CircleShape)
                            .clickable { insightsViewModel.setTab(tab) }.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                PatternMetric(insights.totalScreenTime, "screen time · ${insights.dateLabel}", Modifier.weight(1f))
                PatternMetric(statistics.totalBlocks.toString(), "blocked attempts", Modifier.weight(1f))
            }
            PatternSectionHeader("When attention slipped", "Blocked attempts by hour")
            AttemptsChart(attemptsByHour)
            HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 20.dp))
            PatternSectionHeader("Most used ${insights.dateLabel.lowercase()}", "Usage and the behaviour behind it")
        }
        items(insights.mostUsedApps.take(if (insights.appsExpanded) 12 else 5), key = { "usage-${it.packageName}" }) { usage ->
            UsageRow(usage, appMap[usage.packageName], statistics.mostBlockedApps.toMap()[usage.packageName] ?: 0)
        }
        if (insights.mostUsedApps.size > 5) {
            item {
                TextButton(onClick = { insightsViewModel.toggleAppsExpanded() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (insights.appsExpanded) "Show less" else "Show all ${insights.mostUsedApps.size} apps", color = Primary)
                }
            }
        }
        item {
            HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 8.dp))
            PatternSectionHeader("Attention history", "Every intervention becomes evidence here")
        }
        if (statistics.recentBlocks.isEmpty()) {
            item { Text("No blocked attempts in this period yet.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 20.dp)) }
        } else {
            items(statistics.recentBlocks.take(6), key = { "log-${it.id}" }) { log -> AttentionHistoryRow(log, appMap[log.packageName]) }
        }
        home.insights.actionSuggestions.firstOrNull()?.let { suggestion ->
            item {
                Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
                    Row {
                        Box(Modifier.width(3.dp).height(68.dp).background(Primary))
                        Column(Modifier.padding(start = 13.dp)) {
                            Text("One change for tomorrow", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(suggestion.description, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                    Button(
                        onClick = { pendingSuggestion = suggestion },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
                    ) { Text(suggestion.title) }
                }
            }
        }
    }

    pendingSuggestion?.let { suggestion ->
        AlertDialog(
            onDismissRequest = { pendingSuggestion = null },
            containerColor = CardDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Apply this protection?") },
            text = { Text(suggestion.description) },
            confirmButton = {
                TextButton(onClick = {
                    when (suggestion.type) {
                        ActionType.QUICK_BLOCK_DISTRACTION -> suggestion.targetPackage?.let { homeViewModel.startQuickBlock(listOf(it), 15) }
                        ActionType.SET_TIME_LIMIT -> suggestion.targetPackage?.let { homeViewModel.enableAppTimer(20, listOf(it)) }
                        ActionType.FOCUS_CYCLE_FOR_PEAK_TIME -> suggestion.targetPackage?.let { homeViewModel.enableFocusCycle(10, 20, listOf(it), false) }
                    }
                    pendingSuggestion = null
                    if (suggestion.type == ActionType.QUICK_BLOCK_DISTRACTION) onOpenFocus()
                }) { Text("Confirm", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { pendingSuggestion = null }) { Text("Not now", color = TextSecondary) } }
        )
    }
}

@Composable
private fun PatternMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 14.dp)) {
        Text(value, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Text(label, color = TextSecondary, fontSize = 12.sp)
        HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun PatternSectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 20.dp, bottom = 14.dp)) {
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun AttemptsChart(attempts: Map<Int, Int>) {
    val maxCount = max(1, attempts.values.maxOrNull() ?: 0)
    val bars = listOf(6, 9, 12, 15, 18, 21, 23, 0)
    Row(
        Modifier.fillMaxWidth().height(112.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEach { hour ->
            val value = attempts[hour] ?: 0
            val height = max(4, (96f * value / maxCount).toInt())
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (value > 0 && value == maxCount) Text(value.toString(), color = TextSecondary, fontSize = 9.sp)
                Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (value == maxCount && value > 0) Primary else Primary.copy(alpha = .28f)))
            }
        }
    }
    HorizontalDivider(color = Divider)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("6 AM", color = TextSecondary, fontSize = 10.sp)
        Text("12 PM", color = TextSecondary, fontSize = 10.sp)
        Text("6 PM", color = TextSecondary, fontSize = 10.sp)
        Text("12 AM", color = TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun UsageRow(usage: AppUsageInfo, app: AppUtils.AppInfo?, attemptCount: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        InstalledAppIcon(app?.icon, usage.appName, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(usage.appName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${usage.usageDuration} used · $attemptCount blocked attempts", color = TextSecondary, fontSize = 11.sp)
        }
        Text(usage.category.label, color = Primary, fontSize = 10.sp)
    }
    HorizontalDivider(color = Divider)
}

@Composable
private fun AttentionHistoryRow(log: BlockLog, app: AppUtils.AppInfo?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        InstalledAppIcon(app?.icon, log.appName, 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text("${formatLogTime(log.timestamp)} · ${log.appName} blocked", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("${log.blockedBy.name.lowercase().replace('_', ' ')} protection", color = TextSecondary, fontSize = 11.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(color = Divider)
}

private fun packageCount(packages: String) = packages.split(',').count { it.isNotBlank() }

private fun daysLabel(days: String): String = when (days) {
    "1,2,3,4,5" -> "Weekdays"
    "6,7" -> "Weekends"
    "1,2,3,4,5,6,7" -> "Every day"
    else -> "${days.split(',').count { it.isNotBlank() }} days"
}

private fun formatHour(hour: Int): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val display = when (val value = hour % 12) { 0 -> 12; else -> value }
    return "$display:00 $suffix"
}

private fun formatLogTime(timestamp: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
