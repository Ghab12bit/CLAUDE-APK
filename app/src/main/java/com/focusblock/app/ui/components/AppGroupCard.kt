package com.focusblock.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusblock.app.database.entity.AppGroup
import com.focusblock.app.database.entity.AppGroupIconType
import com.focusblock.app.ui.theme.*

@Composable
fun AppGroupsSection(
    groups: List<AppGroup>,
    onGroupClick: (AppGroup) -> Unit,
    onCreateGroup: () -> Unit,
    onToggleGroup: (AppGroup, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "App Groups",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onCreateGroup) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Create", color = Primary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            // Empty state
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No groups yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Create groups to block apps together",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(groups) { group ->
                    AppGroupChip(
                        group = group,
                        onClick = { onGroupClick(group) },
                        onToggle = { enabled -> onToggleGroup(group, enabled) }
                    )
                }
            }
        }
    }
}

@Composable
fun AppGroupChip(
    group: AppGroup,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupColor = try {
        Color(android.graphics.Color.parseColor(group.colorHex))
    } catch (e: Exception) {
        Primary
    }

    Card(
        modifier = modifier
            .width(150.dp)
            .clickable { onClick() }
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.isEnabled) groupColor.copy(alpha = 0.15f) else CardDark
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(groupColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getGroupIcon(group.iconType),
                        contentDescription = null,
                        tint = groupColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Switch(
                    checked = group.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.size(width = 40.dp, height = 24.dp),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = groupColor,
                        checkedThumbColor = TextPrimary,
                        uncheckedTrackColor = SurfaceBorder,
                        uncheckedThumbColor = TextSecondary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )

            Text(
                text = "${group.getAppCount()} apps",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            if (group.dailyLimitMinutes != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = groupColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${group.dailyLimitMinutes}m limit",
                        style = MaterialTheme.typography.labelSmall,
                        color = groupColor
                    )
                }
            }
        }
    }
}

@Composable
fun getGroupIcon(iconType: AppGroupIconType) = when (iconType) {
    AppGroupIconType.SOCIAL -> Icons.Filled.People
    AppGroupIconType.ENTERTAINMENT -> Icons.Filled.Movie
    AppGroupIconType.GAMING -> Icons.Filled.SportsEsports
    AppGroupIconType.NEWS -> Icons.Filled.Article
    AppGroupIconType.WORK -> Icons.Filled.Work
    AppGroupIconType.CUSTOM -> Icons.Filled.Folder
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupDialog(
    installedApps: List<com.focusblock.app.utils.AppUtils.AppInfo>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, iconType: AppGroupIconType, colorHex: String, packages: List<String>, limitMinutes: Int?) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedIconType by remember { mutableStateOf(AppGroupIconType.CUSTOM) }
    var selectedColorHex by remember { mutableStateOf("#0A84FF") }
    var selectedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var dailyLimitEnabled by remember { mutableStateOf(false) }
    var dailyLimitMinutes by remember { mutableStateOf(30) }
    var showAppPicker by remember { mutableStateOf(false) }

    val availableColors = listOf(
        "#0A84FF" to "Blue",
        "#30D158" to "Green",
        "#FF9F0A" to "Orange",
        "#FF453A" to "Red",
        "#BF5AF2" to "Purple",
        "#64D2FF" to "Cyan"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDarkElevated,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Create App Group",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Group name
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = SurfaceBorder,
                        focusedLabelColor = Primary
                    )
                )

                // Icon selection
                Text(
                    text = "Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppGroupIconType.values().forEach { iconType ->
                        val isSelected = selectedIconType == iconType
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedIconType = iconType },
                            color = if (isSelected) Primary.copy(alpha = 0.2f) else SurfaceElevated,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = getGroupIcon(iconType),
                                    contentDescription = null,
                                    tint = if (isSelected) Primary else TextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Color selection
                Text(
                    text = "Color",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableColors.forEach { (hex, _) ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        val isSelected = selectedColorHex == hex
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColorHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Apps selection
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showAppPicker = true },
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Apps,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Select apps",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${selectedPackages.size} selected",
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

                // Daily limit toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Daily limit",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Set a time limit for this group",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = dailyLimitEnabled,
                        onCheckedChange = { dailyLimitEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Primary,
                            checkedThumbColor = TextPrimary
                        )
                    )
                }

                if (dailyLimitEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(15, 30, 60, 120).forEach { minutes ->
                            val isSelected = dailyLimitMinutes == minutes
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { dailyLimitMinutes = minutes },
                                color = if (isSelected) Primary else SurfaceElevated,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (minutes >= 60) "${minutes / 60}h" else "${minutes}m",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onConfirm(
                            groupName,
                            selectedIconType,
                            selectedColorHex,
                            selectedPackages,
                            if (dailyLimitEnabled) dailyLimitMinutes else null
                        )
                    }
                },
                enabled = groupName.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )

    if (showAppPicker) {
        AppSelectionDialog(
            apps = installedApps,
            selectedApps = selectedPackages,
            onDismiss = { showAppPicker = false },
            onConfirm = { selected ->
                selectedPackages = selected
                showAppPicker = false
            }
        )
    }
}
