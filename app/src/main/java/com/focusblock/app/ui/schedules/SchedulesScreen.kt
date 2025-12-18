@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.focusblock.app.ui.schedules

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.database.entity.ScheduleIconType
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.TimeUtils
import com.focusblock.app.viewmodel.SchedulesViewModel

@Composable
fun SchedulesScreen(
    viewModel: SchedulesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
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
                        text = "Schedules",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Block apps on a schedule",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = Primary,
                    contentColor = TextPrimary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Schedule")
                }
            }
        }

        // Schedule templates
        item {
            Text(
                text = "Quick Templates",
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ScheduleTemplateChip(
                    name = "Work Focus",
                    icon = Icons.Outlined.Work,
                    color = ScheduleWork,
                    onClick = { viewModel.createFromTemplate(ScheduleIconType.WORK) }
                )
                ScheduleTemplateChip(
                    name = "Sleep",
                    icon = Icons.Outlined.Bedtime,
                    color = ScheduleSleep,
                    onClick = { viewModel.createFromTemplate(ScheduleIconType.SLEEP) }
                )
                ScheduleTemplateChip(
                    name = "Study",
                    icon = Icons.Outlined.School,
                    color = ScheduleStudy,
                    onClick = { viewModel.createFromTemplate(ScheduleIconType.STUDY) }
                )
            }
        }

        // Active schedules
        if (uiState.schedules.isNotEmpty()) {
            item {
                Text(
                    text = "Your Schedules",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(uiState.schedules) { schedule ->
                ScheduleCard(
                    schedule = schedule,
                    onToggle = { viewModel.toggleSchedule(schedule.id, it) },
                    onEdit = { editingSchedule = schedule },
                    onDelete = { viewModel.deleteSchedule(schedule) }
                )
            }
        } else {
            item {
                EmptySchedulesCard(onAddClick = { showAddDialog = true })
            }
        }
    }

    // Add/Edit Schedule Dialog
    if (showAddDialog || editingSchedule != null) {
        ScheduleEditDialog(
            schedule = editingSchedule,
            installedApps = viewModel.getInstalledApps(),
            onDismiss = {
                showAddDialog = false
                editingSchedule = null
            },
            onSave = { schedule ->
                if (editingSchedule != null) {
                    viewModel.updateSchedule(schedule)
                } else {
                    viewModel.addSchedule(schedule)
                }
                showAddDialog = false
                editingSchedule = null
            }
        )
    }
}

@Composable
fun ScheduleTemplateChip(
    name: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(name) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.1f),
            labelColor = TextPrimary
        ),
        border = null
    )
}

@Composable
fun ScheduleCard(
    schedule: Schedule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val color = getScheduleColor(schedule.iconType)
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
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
                    // Icon
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getScheduleIcon(schedule.iconType),
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = schedule.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "${TimeUtils.minutesToTimeString(schedule.startTimeMinutes)} - ${TimeUtils.minutesToTimeString(schedule.endTimeMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Active badge
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
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                                tint = TextSecondary
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (schedule.isEnabled) "Disable" else "Enable") },
                                leadingIcon = {
                                    Icon(
                                        if (schedule.isEnabled) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                                        null
                                    )
                                },
                                onClick = {
                                    onToggle(!schedule.isEnabled)
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = {
                                    onEdit()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = AccentRed) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = AccentRed) },
                                onClick = {
                                    onDelete()
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Days of week
            Text(
                text = TimeUtils.getDaysOfWeekString(schedule.daysOfWeek),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Blocked apps count
            val appsCount = schedule.blockedPackages.split(",").filter { it.isNotEmpty() }.size
            if (appsCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$appsCount apps blocked",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
fun EmptySchedulesCard(onAddClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No schedules yet",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = "Create a schedule to automatically block apps",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Schedule")
            }
        }
    }
}

@Composable
fun ScheduleEditDialog(
    schedule: Schedule?,
    installedApps: List<com.focusblock.app.utils.AppUtils.AppInfo>,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit
) {
    // Dialog implementation - simplified for space
    var name by remember { mutableStateOf(schedule?.name ?: "") }
    var iconType by remember { mutableStateOf(schedule?.iconType ?: ScheduleIconType.WORK) }
    var startTime by remember { mutableStateOf(schedule?.startTimeMinutes ?: 9 * 60) }
    var endTime by remember { mutableStateOf(schedule?.endTimeMinutes ?: 17 * 60) }
    var selectedDays by remember { mutableStateOf(schedule?.daysOfWeek?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(1, 2, 3, 4, 5)) }
    var selectedApps by remember { mutableStateOf(schedule?.blockedPackages?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()) }
    var showAppSelector by remember { mutableStateOf(false) }

    if (showAppSelector) {
        com.focusblock.app.ui.components.AppSelectionDialog(
            apps = installedApps,
            selectedApps = selectedApps,
            onDismiss = { showAppSelector = false },
            onConfirm = {
                selectedApps = it
                showAppSelector = false
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (schedule == null) "New Schedule" else "Edit Schedule") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Icon type selection
                    Text("Icon", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScheduleIconType.values().take(6).forEach { type ->
                            val color = getScheduleColor(type)
                            val isSelected = iconType == type
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) color else color.copy(alpha = 0.2f))
                                    .border(
                                        2.dp,
                                        if (isSelected) color else Color.Transparent,
                                        CircleShape
                                    )
                                    .clickable { iconType = type },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = getScheduleIcon(type),
                                    contentDescription = null,
                                    tint = if (isSelected) TextPrimary else color,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Time selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Start", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Text(
                                text = TimeUtils.minutesToTimeString(startTime),
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("End", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Text(
                                text = TimeUtils.minutesToTimeString(endTime),
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Apps selection
                    OutlinedButton(
                        onClick = { showAppSelector = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Apps, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Apps (${selectedApps.size})")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newSchedule = Schedule(
                            id = schedule?.id ?: 0,
                            name = name.ifEmpty { "Schedule" },
                            iconType = iconType,
                            colorHex = getScheduleColor(iconType).toString(),
                            startTimeMinutes = startTime,
                            endTimeMinutes = endTime,
                            daysOfWeek = selectedDays.joinToString(","),
                            blockedPackages = selectedApps.joinToString(",")
                        )
                        onSave(newSchedule)
                    },
                    enabled = name.isNotEmpty() && selectedApps.isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun getScheduleColor(type: ScheduleIconType): Color {
    return when (type) {
        ScheduleIconType.WORK -> ScheduleWork
        ScheduleIconType.SLEEP -> ScheduleSleep
        ScheduleIconType.STUDY -> ScheduleStudy
        ScheduleIconType.FAMILY -> ScheduleFamily
        ScheduleIconType.SOCIAL -> ScheduleSocial
        ScheduleIconType.DETOX -> ScheduleDetox
        else -> Primary
    }
}

fun getScheduleIcon(type: ScheduleIconType): ImageVector {
    return when (type) {
        ScheduleIconType.WORK -> Icons.Outlined.Work
        ScheduleIconType.SLEEP -> Icons.Outlined.Bedtime
        ScheduleIconType.STUDY -> Icons.Outlined.School
        ScheduleIconType.FAMILY -> Icons.Outlined.FamilyRestroom
        ScheduleIconType.SOCIAL -> Icons.Outlined.Groups
        ScheduleIconType.DETOX -> Icons.Outlined.SelfImprovement
        ScheduleIconType.FOCUS -> Icons.Outlined.CenterFocusStrong
        ScheduleIconType.WIND_DOWN -> Icons.Outlined.NightsStay
        ScheduleIconType.CUSTOM -> Icons.Outlined.Settings
    }
}
