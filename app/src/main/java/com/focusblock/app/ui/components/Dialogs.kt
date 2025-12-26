@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

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
    var unlockHours by remember { mutableStateOf(1) }
    var unlockMinutes by remember { mutableStateOf(0) }
    var useCustomTime by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<Int?>(1) } // 1 hour default

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
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

                Spacer(modifier = Modifier.height(20.dp))

                // Unlock time selector
                Text(
                    text = "Unlock available after:",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Preset options
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        1 to "1h",
                        2 to "2h",
                        6 to "6h",
                        12 to "12h",
                        24 to "1d",
                        48 to "2d"
                    ).forEach { (hours, label) ->
                        FilterChip(
                            selected = selectedPreset == hours && !useCustomTime,
                            onClick = {
                                selectedPreset = hours
                                unlockHours = hours
                                unlockMinutes = 0
                                useCustomTime = false
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentOrange,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Custom time toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Custom duration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Switch(
                        checked = useCustomTime,
                        onCheckedChange = {
                            useCustomTime = it
                            if (it) selectedPreset = null
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = AccentOrange
                        )
                    )
                }

                // Custom time picker
                if (useCustomTime) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = BackgroundDark)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Hours picker
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                IconButton(onClick = { if (unlockHours < 168) unlockHours++ }) {
                                    Icon(Icons.Filled.KeyboardArrowUp, null, tint = AccentOrange)
                                }
                                Text(
                                    text = String.format("%02d", unlockHours),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = TextPrimary
                                )
                                IconButton(onClick = { if (unlockHours > 0) unlockHours-- }) {
                                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = AccentOrange)
                                }
                                Text("hours", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }

                            Text(
                                text = ":",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )

                            // Minutes picker
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                IconButton(onClick = {
                                    unlockMinutes = if (unlockMinutes >= 55) 0 else unlockMinutes + 5
                                }) {
                                    Icon(Icons.Filled.KeyboardArrowUp, null, tint = AccentOrange)
                                }
                                Text(
                                    text = String.format("%02d", unlockMinutes),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = TextPrimary
                                )
                                IconButton(onClick = {
                                    unlockMinutes = if (unlockMinutes <= 0) 55 else unlockMinutes - 5
                                }) {
                                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = AccentOrange)
                                }
                                Text("mins", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Display selected time
                val totalMinutes = unlockHours * 60 + unlockMinutes
                val displayText = when {
                    totalMinutes >= 1440 -> "${totalMinutes / 1440} day${if (totalMinutes >= 2880) "s" else ""} ${(totalMinutes % 1440) / 60}h"
                    totalMinutes >= 60 -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
                    else -> "${totalMinutes}m"
                }
                Text(
                    text = "Lock for: $displayText",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentOrange
                )

                Spacer(modifier = Modifier.height(16.dp))

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
                        onClick = { onConfirm(pin, unlockHours * 60 + unlockMinutes) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = pin.length >= 4 && (unlockHours > 0 || unlockMinutes > 0),
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
fun StrictModeSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (durationMinutes: Int) -> Unit
) {
    var selectedHours by remember { mutableStateOf(1) }
    var selectedMinutes by remember { mutableStateOf(0) }
    var useCustomTime by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<Int?>(60) } // 1 hour default in minutes

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon with glow effect
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Strict Mode",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Once enabled, blocking cannot be stopped\nuntil the timer expires",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Duration selector title
                Text(
                    text = "Lock Duration",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Preset duration chips - scrollable
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        30 to "30m",
                        60 to "1h",
                        120 to "2h",
                        180 to "3h",
                        300 to "5h",
                        480 to "8h",
                        720 to "12h",
                        1440 to "24h"
                    ).forEach { (minutes, label) ->
                        FilterChip(
                            selected = selectedPreset == minutes && !useCustomTime,
                            onClick = {
                                selectedPreset = minutes
                                selectedHours = minutes / 60
                                selectedMinutes = minutes % 60
                                useCustomTime = false
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

                // Custom time toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Custom duration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Switch(
                        checked = useCustomTime,
                        onCheckedChange = {
                            useCustomTime = it
                            if (it) selectedPreset = null
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Primary
                        )
                    )
                }

                // Custom time picker
                if (useCustomTime) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = BackgroundDark)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Hours picker
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = { if (selectedHours < 72) selectedHours++ }) {
                                    Icon(Icons.Filled.KeyboardArrowUp, null, tint = Primary)
                                }
                                Text(
                                    text = String.format("%02d", selectedHours),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = TextPrimary
                                )
                                IconButton(onClick = { if (selectedHours > 0) selectedHours-- }) {
                                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Primary)
                                }
                                Text("hours", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }

                            Text(
                                text = ":",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )

                            // Minutes picker
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = {
                                    selectedMinutes = if (selectedMinutes >= 55) 0 else selectedMinutes + 5
                                }) {
                                    Icon(Icons.Filled.KeyboardArrowUp, null, tint = Primary)
                                }
                                Text(
                                    text = String.format("%02d", selectedMinutes),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = TextPrimary
                                )
                                IconButton(onClick = {
                                    selectedMinutes = if (selectedMinutes <= 0) 55 else selectedMinutes - 5
                                }) {
                                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Primary)
                                }
                                Text("mins", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Display selected duration
                val totalMinutes = selectedHours * 60 + selectedMinutes
                val displayText = when {
                    totalMinutes >= 1440 -> "${totalMinutes / 1440}d ${(totalMinutes % 1440) / 60}h"
                    totalMinutes >= 60 -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
                    else -> "${totalMinutes}m"
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Lock for $displayText",
                            style = MaterialTheme.typography.titleMedium,
                            color = Primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Warning
                Card(
                    colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp)
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
                            text = "No PIN, restart, or setting can disable\nStrict Mode until the timer expires!",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentRed
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
                        onClick = { onConfirm(selectedHours * 60 + selectedMinutes) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = (selectedHours > 0 || selectedMinutes > 0),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Activate")
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

/**
 * Focus Cycle Setup Dialog - Configure usage window and break duration
 */
@Composable
fun FocusCycleSetupDialog(
    apps: List<AppUtils.AppInfo>,
    quickBlockApps: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (usageWindowMinutes: Int, breakDurationMinutes: Int, selectedPackages: List<String>, useQuickBlockApps: Boolean) -> Unit
) {
    var usageWindowMinutes by remember { mutableStateOf(10) }
    var breakDurationMinutes by remember { mutableStateOf(30) }
    var useQuickBlockApps by remember { mutableStateOf(true) }
    var selectedApps by remember { mutableStateOf(quickBlockApps.toSet()) }
    var showAppSelection by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF9C27B0).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Loop,
                        contentDescription = null,
                        tint = Color(0xFF9C27B0),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Focus Cycles",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Mindful usage with gentle breaks.\nNo hard blocking - just reminders.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Usage Window Duration
                Text(
                    text = "Usage Window",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = "How long you can use apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    // -5 = 5 seconds (for testing)
                    FilterChip(
                        selected = usageWindowMinutes == -5,
                        onClick = { usageWindowMinutes = -5 },
                        label = { Text("5s") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF5722),
                            selectedLabelColor = TextPrimary
                        )
                    )
                    listOf(5, 10, 15, 20, 30).forEach { minutes ->
                        FilterChip(
                            selected = usageWindowMinutes == minutes,
                            onClick = { usageWindowMinutes = minutes },
                            label = { Text("${minutes}m") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF9C27B0),
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Break Duration
                Text(
                    text = "Break Duration",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = "Soft block period (you can override)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    // -10 = 10 seconds (for testing)
                    FilterChip(
                        selected = breakDurationMinutes == -10,
                        onClick = { breakDurationMinutes = -10 },
                        label = { Text("10s") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF5722),
                            selectedLabelColor = TextPrimary
                        )
                    )
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        FilterChip(
                            selected = breakDurationMinutes == minutes,
                            onClick = { breakDurationMinutes = minutes },
                            label = { Text("${minutes}m") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentGreen,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // App Selection
                Text(
                    text = "Apps to Monitor",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Toggle for using Quick Block apps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Use Quick Block apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (useQuickBlockApps) TextPrimary else TextSecondary
                    )
                    Switch(
                        checked = useQuickBlockApps,
                        onCheckedChange = { useQuickBlockApps = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Color(0xFF9C27B0)
                        )
                    )
                }

                if (!useQuickBlockApps) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showAppSelection = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF9C27B0).copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Outlined.Apps, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedApps.isEmpty()) "Select Apps" else "${selectedApps.size} apps selected",
                            color = Color(0xFF9C27B0)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Info card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF9C27B0).copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = Color(0xFF9C27B0),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "During breaks, you can still override and continue. This is soft-nudge, not hard blocking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
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
                        onClick = {
                            val appsToUse = if (useQuickBlockApps) quickBlockApps else selectedApps.toList()
                            onConfirm(usageWindowMinutes, breakDurationMinutes, appsToUse, useQuickBlockApps)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                        enabled = useQuickBlockApps || selectedApps.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Loop, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start")
                    }
                }
            }
        }
    }

    // App selection dialog
    if (showAppSelection) {
        AppSelectionDialog(
            apps = apps,
            selectedApps = selectedApps.toList(),
            onDismiss = { showAppSelection = false },
            onConfirm = { selected ->
                selectedApps = selected.toSet()
                showAppSelection = false
            }
        )
    }
}

/**
 * Focus Cycle Soft-Nudge Override Dialog
 * Shows during break period when user tries to open a blocked app
 */
@Composable
fun FocusCycleOverrideDialog(
    appName: String,
    remainingBreakTime: Long,
    onDismiss: () -> Unit,
    onTakeBreak: () -> Unit,
    onContinueAnyway: () -> Unit
) {
    var canContinue by remember { mutableStateOf(false) }

    // Enable "Continue Anyway" after 2-3 second delay
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500) // 2.5 second delay
        canContinue = true
    }

    val motivationalMessages = listOf(
        "A short break now means better focus later.",
        "Your future self will thank you for this pause.",
        "Taking breaks actually improves productivity.",
        "Rest is part of the process, not a break from it.",
        "Small pauses lead to big achievements."
    )
    val currentMessage = remember { motivationalMessages.random() }

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
                // Friendly icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF9C27B0).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SelfImprovement,
                        contentDescription = null,
                        tint = Color(0xFF9C27B0),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Taking a break?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$appName is in break mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Remaining time
                val minutes = (remainingBreakTime / 60000).toInt()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${minutes}m break remaining",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Motivational message
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF9C27B0).copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LightMode,
                            contentDescription = null,
                            tint = Color(0xFF9C27B0),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = currentMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Primary action - Take Break
                Button(
                    onClick = onTakeBreak,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Icon(Icons.Filled.SelfImprovement, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Take a Break", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Secondary action - Continue Anyway (with delay)
                TextButton(
                    onClick = {
                        if (canContinue) {
                            onContinueAnyway()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canContinue
                ) {
                    Text(
                        text = if (canContinue) "Continue Anyway" else "Wait...",
                        color = if (canContinue) TextSecondary else TextSecondary.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * Strict Mode Intentional Pause Dialog - Refined with reason selection
 */
@Composable
fun StrictModePauseDialog(
    onDismiss: () -> Unit,
    onKeepFocused: () -> Unit,
    onPauseWithReason: (String) -> Unit
) {
    var selectedReason by remember { mutableStateOf<String?>(null) }
    var canPause by remember { mutableStateOf(false) }

    // Add delay before pause becomes available
    LaunchedEffect(selectedReason) {
        if (selectedReason != null) {
            canPause = false
            kotlinx.coroutines.delay(2000) // 2 second delay after selecting reason
            canPause = true
        }
    }

    val pauseReasons = listOf(
        "Emergency" to Icons.Filled.Warning,
        "Work call" to Icons.Filled.Phone,
        "Important message" to Icons.Filled.Email,
        "Quick task" to Icons.Filled.Task,
        "Other" to Icons.Filled.MoreHoriz
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PauseCircle,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Pause Strict Mode?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "You're doing great! Pausing now will interrupt your focus session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Reason selection
                Text(
                    text = "Why do you need to pause?",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pauseReasons.forEach { (reason, icon) ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedReason = reason },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedReason == reason) AccentOrange.copy(alpha = 0.15f) else BackgroundDark,
                            border = if (selectedReason == reason)
                                BorderStroke(1.dp, AccentOrange.copy(alpha = 0.5f))
                            else
                                BorderStroke(1.dp, Divider)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (selectedReason == reason) AccentOrange else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selectedReason == reason) TextPrimary else TextSecondary
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                if (selectedReason == reason) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = AccentOrange,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Keep Focused button (primary)
                Button(
                    onClick = onKeepFocused,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Icon(Icons.Filled.Shield, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Keep Focused", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Pause button (secondary, requires reason and delay)
                OutlinedButton(
                    onClick = {
                        if (canPause && selectedReason != null) {
                            onPauseWithReason(selectedReason!!)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = canPause && selectedReason != null,
                    border = BorderStroke(
                        1.dp,
                        if (canPause && selectedReason != null) AccentOrange.copy(alpha = 0.5f)
                        else Divider
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (canPause && selectedReason != null) AccentOrange else TextSecondary
                    )
                ) {
                    Text(
                        text = when {
                            selectedReason == null -> "Select a reason first"
                            !canPause -> "Wait..."
                            else -> "Pause Anyway"
                        }
                    )
                }
            }
        }
    }
}
