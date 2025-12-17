package com.focusblock.app.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionDialog(
    apps: List<AppUtils.AppInfo>,
    selectedApps: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(selectedApps.toSet()) }
    var searchQuery by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(ViewMode.CATEGORIES) }
    var selectedCategory by remember { mutableStateOf<AppUtils.AppCategory?>(null) }

    val filteredApps = apps.filter {
        it.appName.contains(searchQuery, ignoreCase = true) ||
        it.packageName.contains(searchQuery, ignoreCase = true)
    }

    // Group apps by category
    val categorizedApps = remember(apps) {
        AppUtils.AppCategory.entries.associateWith { category ->
            AppUtils.getAppsInCategory(apps, category)
        }.filter { it.value.isNotEmpty() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedCategory != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedCategory = null }) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextPrimary
                                )
                            }
                            Text(
                                text = selectedCategory!!.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                        }
                    } else {
                        Text(
                            text = "Select Apps",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "${selected.size} selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Primary
                    )
                }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        if (it.isNotEmpty()) {
                            viewMode = ViewMode.SEARCH
                            selectedCategory = null
                        } else if (viewMode == ViewMode.SEARCH) {
                            viewMode = ViewMode.CATEGORIES
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("Search apps...", color = TextSecondary) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                viewMode = ViewMode.CATEGORIES
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear",
                                    tint = TextSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Divider,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Primary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // View mode tabs (only when not in a category)
                if (selectedCategory == null && searchQuery.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = viewMode == ViewMode.CATEGORIES,
                            onClick = { viewMode = ViewMode.CATEGORIES },
                            label = { Text("Categories") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Category,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = TextPrimary
                            )
                        )
                        FilterChip(
                            selected = viewMode == ViewMode.ALL_APPS,
                            onClick = { viewMode = ViewMode.ALL_APPS },
                            label = { Text("All Apps") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick select chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                val socialApps = AppUtils.getAppsInCategory(apps, AppUtils.AppCategory.SOCIAL_MEDIA)
                                    .map { it.packageName }
                                selected = selected + socialApps
                            },
                            label = { Text("+ Social") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Primary.copy(alpha = 0.1f),
                                labelColor = Primary
                            )
                        )
                        AssistChip(
                            onClick = {
                                val gameApps = AppUtils.getAppsInCategory(apps, AppUtils.AppCategory.GAMES)
                                    .map { it.packageName }
                                selected = selected + gameApps
                            },
                            label = { Text("+ Games") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = AccentOrange.copy(alpha = 0.1f),
                                labelColor = AccentOrange
                            )
                        )
                        AssistChip(
                            onClick = { selected = emptySet() },
                            label = { Text("Clear") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Divider,
                                labelColor = TextPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Content based on view mode
                when {
                    searchQuery.isNotEmpty() -> {
                        // Search results
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(filteredApps) { app ->
                                val isSelected = selected.contains(app.packageName)
                                AppListItem(
                                    app = app,
                                    isSelected = isSelected,
                                    onClick = {
                                        selected = if (isSelected) {
                                            selected - app.packageName
                                        } else {
                                            selected + app.packageName
                                        }
                                    }
                                )
                            }
                        }
                    }
                    selectedCategory != null -> {
                        // Show apps in selected category
                        val categoryApps = AppUtils.getAppsInCategory(apps, selectedCategory!!)

                        // Select/Deselect all in category
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val allCategorySelected = categoryApps.all { selected.contains(it.packageName) }
                            AssistChip(
                                onClick = {
                                    if (allCategorySelected) {
                                        selected = selected - categoryApps.map { it.packageName }.toSet()
                                    } else {
                                        selected = selected + categoryApps.map { it.packageName }
                                    }
                                },
                                label = { Text(if (allCategorySelected) "Deselect All" else "Select All") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (allCategorySelected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (allCategorySelected) Primary.copy(alpha = 0.1f) else Divider,
                                    labelColor = if (allCategorySelected) Primary else TextPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(categoryApps) { app ->
                                val isSelected = selected.contains(app.packageName)
                                AppListItem(
                                    app = app,
                                    isSelected = isSelected,
                                    onClick = {
                                        selected = if (isSelected) {
                                            selected - app.packageName
                                        } else {
                                            selected + app.packageName
                                        }
                                    }
                                )
                            }
                        }
                    }
                    viewMode == ViewMode.CATEGORIES -> {
                        // Show category cards
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(categorizedApps.entries.toList()) { (category, categoryApps) ->
                                val selectedInCategory = categoryApps.count { selected.contains(it.packageName) }
                                CategoryCard(
                                    category = category,
                                    appCount = categoryApps.size,
                                    selectedCount = selectedInCategory,
                                    onClick = { selectedCategory = category },
                                    onSelectAll = {
                                        selected = selected + categoryApps.map { it.packageName }
                                    }
                                )
                            }
                        }
                    }
                    else -> {
                        // All apps view
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(apps) { app ->
                                val isSelected = selected.contains(app.packageName)
                                AppListItem(
                                    app = app,
                                    isSelected = isSelected,
                                    onClick = {
                                        selected = if (isSelected) {
                                            selected - app.packageName
                                        } else {
                                            selected + app.packageName
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        ),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(selected.toList()) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = selected.isNotEmpty()
                    ) {
                        Text("Block ${selected.size} apps")
                    }
                }
            }
        }
    }
}

private enum class ViewMode {
    CATEGORIES,
    ALL_APPS,
    SEARCH
}

@Composable
private fun CategoryCard(
    category: AppUtils.AppCategory,
    appCount: Int,
    selectedCount: Int,
    onClick: () -> Unit,
    onSelectAll: () -> Unit
) {
    val icon = when (category) {
        AppUtils.AppCategory.SOCIAL_MEDIA -> Icons.Outlined.People
        AppUtils.AppCategory.GAMES -> Icons.Outlined.SportsEsports
        AppUtils.AppCategory.ENTERTAINMENT -> Icons.Outlined.Movie
        AppUtils.AppCategory.COMMUNICATION -> Icons.Outlined.Chat
        AppUtils.AppCategory.SHOPPING -> Icons.Outlined.ShoppingCart
        AppUtils.AppCategory.NEWS -> Icons.Outlined.Article
        AppUtils.AppCategory.PRODUCTIVITY -> Icons.Outlined.Work
        AppUtils.AppCategory.DATING -> Icons.Outlined.Favorite
        AppUtils.AppCategory.BROWSER -> Icons.Outlined.Web
        AppUtils.AppCategory.OTHER -> Icons.Outlined.Apps
    }

    val categoryColor = when (category) {
        AppUtils.AppCategory.SOCIAL_MEDIA -> Primary
        AppUtils.AppCategory.GAMES -> AccentOrange
        AppUtils.AppCategory.ENTERTAINMENT -> AccentRed
        AppUtils.AppCategory.COMMUNICATION -> AccentGreen
        AppUtils.AppCategory.SHOPPING -> Color(0xFF9C27B0) // Purple
        AppUtils.AppCategory.NEWS -> Color(0xFF607D8B) // Blue Grey
        AppUtils.AppCategory.PRODUCTIVITY -> Color(0xFF00BCD4) // Cyan
        AppUtils.AppCategory.DATING -> Color(0xFFE91E63) // Pink
        AppUtils.AppCategory.BROWSER -> Color(0xFF3F51B5) // Indigo
        AppUtils.AppCategory.OTHER -> TextSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = BackgroundDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(categoryColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = categoryColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Category info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "$appCount apps${if (selectedCount > 0) " • $selectedCount selected" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedCount > 0) Primary else TextSecondary
                )
            }

            // Quick select button
            IconButton(
                onClick = onSelectAll,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(categoryColor.copy(alpha = 0.1f))
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Select all",
                    tint = categoryColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Arrow
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextSecondary
            )
        }
    }
}

@Composable
fun AppListItem(
    app: AppUtils.AppInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .background(if (isSelected) Primary.copy(alpha = 0.1f) else Color.Transparent)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        app.icon?.let { drawable ->
            Image(
                bitmap = drawable.toBitmap(48, 48).asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } ?: Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Divider),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = TextSecondary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(
                checkedColor = Primary,
                uncheckedColor = TextSecondary,
                checkmarkColor = TextPrimary
            )
        )
    }
}

@Composable
fun TimerPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(30) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Set Timer",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hours
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (hours < 23) hours++ }) {
                            Icon(Icons.Filled.KeyboardArrowUp, null, tint = Primary)
                        }
                        Text(
                            text = String.format("%02d", hours),
                            style = MaterialTheme.typography.displaySmall,
                            color = TextPrimary
                        )
                        IconButton(onClick = { if (hours > 0) hours-- }) {
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Primary)
                        }
                        Text("hours", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }

                    Text(
                        text = ":",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary
                    )

                    // Minutes
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (minutes < 59) minutes++ else { minutes = 0; if (hours < 23) hours++ } }) {
                            Icon(Icons.Filled.KeyboardArrowUp, null, tint = Primary)
                        }
                        Text(
                            text = String.format("%02d", minutes),
                            style = MaterialTheme.typography.displaySmall,
                            color = TextPrimary
                        )
                        IconButton(onClick = { if (minutes > 0) minutes-- else if (hours > 0) { minutes = 59; hours-- } }) {
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Primary)
                        }
                        Text("mins", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick select presets
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60, 120).forEach { preset ->
                        FilterChip(
                            selected = hours * 60 + minutes == preset,
                            onClick = {
                                hours = preset / 60
                                minutes = preset % 60
                            },
                            label = {
                                Text(
                                    if (preset >= 60) "${preset / 60}h" else "${preset}m"
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(hours * 60 + minutes) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = hours > 0 || minutes > 0
                    ) {
                        Text("Set")
                    }
                }
            }
        }
    }
}

@Composable
fun PinSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (step == 1) "Set PIN" else "Confirm PIN",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )

                Text(
                    text = if (step == 1) "Enter a 4-6 digit PIN" else "Re-enter your PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = if (step == 1) pin else confirmPin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                            if (step == 1) pin = it else confirmPin = it
                            error = null
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = AccentRed) } },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Divider,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (step == 2) {
                                step = 1
                                confirmPin = ""
                            } else {
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text(if (step == 2) "Back" else "Cancel")
                    }
                    Button(
                        onClick = {
                            if (step == 1) {
                                if (pin.length >= 4) {
                                    step = 2
                                } else {
                                    error = "PIN must be at least 4 digits"
                                }
                            } else {
                                if (pin == confirmPin) {
                                    onConfirm(pin)
                                } else {
                                    error = "PINs do not match"
                                    confirmPin = ""
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = (if (step == 1) pin else confirmPin).length >= 4
                    ) {
                        Text(if (step == 1) "Next" else "Confirm")
                    }
                }
            }
        }
    }
}

@Composable
fun HardModeSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (pin: String, unlockMinutes: Int) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var unlockHours by remember { mutableStateOf(24) }
    var step by remember { mutableStateOf(1) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Hard Mode",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )

                Text(
                    text = "Set PIN and unlock time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PIN input
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                            pin = it
                        }
                    },
                    label = { Text("PIN (4-6 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Divider,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = Primary,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = Primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Unlock time selector
                Text(
                    text = "Unlock available after:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 6, 12, 24, 48).forEach { hours ->
                        FilterChip(
                            selected = unlockHours == hours,
                            onClick = { unlockHours = hours },
                            label = {
                                Text(if (hours < 24) "${hours}h" else "${hours / 24}d")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentOrange,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Warning
                Card(
                    colors = CardDefaults.cardColors(containerColor = AccentOrange.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "You won't be able to disable blocking until the timer expires!",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentOrange
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(pin, unlockHours * 60) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = pin.length >= 4,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                    ) {
                        Text("Enable")
                    }
                }
            }
        }
    }
}

@Composable
fun PomodoroSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (workMinutes: Int, breakMinutes: Int) -> Unit
) {
    var workMinutes by remember { mutableStateOf(25) }
    var breakMinutes by remember { mutableStateOf(5) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Pomodoro Timer",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )

                Text(
                    text = "Focus with timed work sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Work duration
                Text(
                    text = "Work Duration",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 25, 30, 45, 60).forEach { minutes ->
                        FilterChip(
                            selected = workMinutes == minutes,
                            onClick = { workMinutes = minutes },
                            label = { Text("${minutes}m") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Break duration
                Text(
                    text = "Break Duration",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 15, 20).forEach { minutes ->
                        FilterChip(
                            selected = breakMinutes == minutes,
                            onClick = { breakMinutes = minutes },
                            label = { Text("${minutes}m") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentGreen,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(workMinutes, breakMinutes) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                    ) {
                        Text("Start")
                    }
                }
            }
        }
    }
}

@Composable
fun TimeLimitSetupDialog(
    apps: List<AppUtils.AppInfo>,
    existingLimits: Map<String, Int>, // packageName to limitMinutes
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Int>) -> Unit
) {
    var selectedLimits by remember { mutableStateOf(existingLimits.toMutableMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<AppUtils.AppInfo?>(null) }
    var customLimitMinutes by remember { mutableStateOf(60) }

    val filteredApps = apps.filter {
        it.appName.contains(searchQuery, ignoreCase = true) ||
        it.packageName.contains(searchQuery, ignoreCase = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "App Time Limits",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "${selectedLimits.size} apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Primary
                    )
                }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("Search apps...", color = TextSecondary) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Divider,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Primary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Preset time limits
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Presets:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    listOf(15, 30, 60, 120).forEach { minutes ->
                        AssistChip(
                            onClick = { customLimitMinutes = minutes },
                            label = {
                                Text(
                                    if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (customLimitMinutes == minutes) Primary.copy(alpha = 0.2f) else Divider,
                                labelColor = if (customLimitMinutes == minutes) Primary else TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // App list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(filteredApps) { app ->
                        val hasLimit = selectedLimits.containsKey(app.packageName)
                        val limitMinutes = selectedLimits[app.packageName]

                        TimeLimitAppItem(
                            app = app,
                            hasLimit = hasLimit,
                            limitMinutes = limitMinutes,
                            onSetLimit = {
                                selectedLimits = selectedLimits.toMutableMap().apply {
                                    put(app.packageName, customLimitMinutes)
                                }
                            },
                            onRemoveLimit = {
                                selectedLimits = selectedLimits.toMutableMap().apply {
                                    remove(app.packageName)
                                }
                            },
                            onEditLimit = { selectedApp = app }
                        )
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        ),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(selectedLimits) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Limits")
                    }
                }
            }
        }
    }

    // Edit limit dialog for specific app
    selectedApp?.let { app ->
        EditTimeLimitDialog(
            appName = app.appName,
            currentLimit = selectedLimits[app.packageName] ?: 60,
            onDismiss = { selectedApp = null },
            onConfirm = { minutes ->
                selectedLimits = selectedLimits.toMutableMap().apply {
                    put(app.packageName, minutes)
                }
                selectedApp = null
            }
        )
    }
}

@Composable
private fun TimeLimitAppItem(
    app: AppUtils.AppInfo,
    hasLimit: Boolean,
    limitMinutes: Int?,
    onSetLimit: () -> Unit,
    onRemoveLimit: () -> Unit,
    onEditLimit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { if (hasLimit) onEditLimit() else onSetLimit() }
            .background(if (hasLimit) Primary.copy(alpha = 0.1f) else Color.Transparent)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        app.icon?.let { drawable ->
            Image(
                bitmap = drawable.toBitmap(48, 48).asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } ?: Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Divider),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = TextSecondary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hasLimit && limitMinutes != null) {
                Text(
                    text = formatTimeLimit(limitMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary
                )
            } else {
                Text(
                    text = "No limit set",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        if (hasLimit) {
            IconButton(onClick = onRemoveLimit) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove limit",
                    tint = AccentRed
                )
            }
        } else {
            IconButton(onClick = onSetLimit) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add limit",
                    tint = Primary
                )
            }
        }
    }
}

@Composable
private fun EditTimeLimitDialog(
    appName: String,
    currentLimit: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hours by remember { mutableStateOf(currentLimit / 60) }
    var minutes by remember { mutableStateOf(currentLimit % 60) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Set Time Limit",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )

                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hours
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (hours < 23) hours++ }) {
                            Icon(Icons.Filled.KeyboardArrowUp, null, tint = Primary)
                        }
                        Text(
                            text = String.format("%02d", hours),
                            style = MaterialTheme.typography.displaySmall,
                            color = TextPrimary
                        )
                        IconButton(onClick = { if (hours > 0) hours-- }) {
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Primary)
                        }
                        Text("hours", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }

                    Text(
                        text = ":",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary
                    )

                    // Minutes
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            if (minutes < 55) minutes += 5 else { minutes = 0; if (hours < 23) hours++ }
                        }) {
                            Icon(Icons.Filled.KeyboardArrowUp, null, tint = Primary)
                        }
                        Text(
                            text = String.format("%02d", minutes),
                            style = MaterialTheme.typography.displaySmall,
                            color = TextPrimary
                        )
                        IconButton(onClick = {
                            if (minutes >= 5) minutes -= 5 else if (hours > 0) { minutes = 55; hours-- }
                        }) {
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Primary)
                        }
                        Text("mins", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick presets
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60, 120, 180).forEach { preset ->
                        val totalMins = hours * 60 + minutes
                        FilterChip(
                            selected = totalMins == preset,
                            onClick = {
                                hours = preset / 60
                                minutes = preset % 60
                            },
                            label = {
                                Text(
                                    if (preset >= 60) "${preset / 60}h" else "${preset}m"
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(hours * 60 + minutes) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = hours > 0 || minutes > 0
                    ) {
                        Text("Set Limit")
                    }
                }
            }
        }
    }
}

private fun formatTimeLimit(minutes: Int): String {
    return when {
        minutes >= 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m > 0) "${h}h ${m}m daily limit" else "${h}h daily limit"
        }
        else -> "${minutes}m daily limit"
    }
}

@Composable
fun WorkModeDialog(
    apps: List<AppUtils.AppInfo>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>, Int) -> Unit // apps to block, duration in minutes
) {
    var selectedApps by remember {
        mutableStateOf(
            apps.filter { app ->
                // Pre-select social media and games
                AppUtils.getAppCategory(app.packageName) in listOf(
                    AppUtils.AppCategory.SOCIAL_MEDIA,
                    AppUtils.AppCategory.GAMES,
                    AppUtils.AppCategory.ENTERTAINMENT
                )
            }.map { it.packageName }.toSet()
        )
    }
    var duration by remember { mutableStateOf(60) } // Default 1 hour

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Work,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Work Mode",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Block distracting apps while you focus",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Duration selection
                Text(
                    text = "Focus Duration",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(30, 60, 90, 120, 180).forEach { minutes ->
                        FilterChip(
                            selected = duration == minutes,
                            onClick = { duration = minutes },
                            label = {
                                Text(
                                    if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Category quick select
                Text(
                    text = "Block Categories",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        AppUtils.AppCategory.SOCIAL_MEDIA to "Social",
                        AppUtils.AppCategory.GAMES to "Games",
                        AppUtils.AppCategory.ENTERTAINMENT to "Videos",
                        AppUtils.AppCategory.COMMUNICATION to "Chat"
                    ).forEach { (category, label) ->
                        val categoryApps = AppUtils.getAppsInCategory(apps, category).map { it.packageName }
                        val allSelected = categoryApps.all { it in selectedApps }
                        FilterChip(
                            selected = allSelected,
                            onClick = {
                                selectedApps = if (allSelected) {
                                    selectedApps - categoryApps.toSet()
                                } else {
                                    selectedApps + categoryApps
                                }
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "${selectedApps.size} apps will be blocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Primary
                )

                Spacer(modifier = Modifier.weight(1f))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(selectedApps.toList(), duration) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = selectedApps.isNotEmpty()
                    ) {
                        Text("Start Focus")
                    }
                }
            }
        }
    }
}

@Composable
fun DigitalDetoxDialog(
    apps: List<AppUtils.AppInfo>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>, Int) -> Unit // apps to block, duration in minutes
) {
    var selectedApps by remember {
        mutableStateOf(
            // Pre-select almost everything except essentials
            apps.filter { app ->
                AppUtils.getAppCategory(app.packageName) !in listOf(
                    AppUtils.AppCategory.PRODUCTIVITY
                )
            }.map { it.packageName }.toSet()
        )
    }
    var duration by remember { mutableStateOf(240) } // Default 4 hours

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SelfImprovement,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Digital Detox",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Take a complete break from your phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Duration selection
                Text(
                    text = "Detox Duration",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(60, 120, 240, 480, 720).forEach { minutes ->
                        FilterChip(
                            selected = duration == minutes,
                            onClick = { duration = minutes },
                            label = {
                                Text(
                                    when {
                                        minutes >= 720 -> "12h"
                                        minutes >= 480 -> "8h"
                                        minutes >= 240 -> "4h"
                                        minutes >= 60 -> "${minutes / 60}h"
                                        else -> "${minutes}m"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentGreen,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Warning
                Card(
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Complete digital break",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "All non-essential apps will be blocked. Only phone and basic functions remain.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "${selectedApps.size} apps will be blocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentGreen
                )

                Spacer(modifier = Modifier.weight(1f))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = BorderStroke(1.dp, Divider)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(selectedApps.toList(), duration) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        enabled = selectedApps.isNotEmpty()
                    ) {
                        Text("Start Detox")
                    }
                }
            }
        }
    }
}
