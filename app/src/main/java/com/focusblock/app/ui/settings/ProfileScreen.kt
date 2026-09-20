package com.focusblock.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.ui.components.HeroState
import com.focusblock.app.ui.components.ScreenTitle
import com.focusblock.app.ui.components.SectionHeading
import com.focusblock.app.ui.components.StatusHero
import com.focusblock.app.ui.components.SummaryRow
import com.focusblock.app.ui.permissions.PermissionFlow
import com.focusblock.app.ui.permissions.PermissionId
import com.focusblock.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings.
 *
 * Only what still drives behaviour. The old screen carried a Daily Usage Limit
 * toggle, 20% Reduction Alerts, separate Strict and Hard Mode switches, and
 * three Pomodoro durations -- most of which no longer controlled anything. A
 * switch that does nothing is worse than no switch.
 */
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAllowlist by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var permissionsExpanded by remember { mutableStateOf(false) }
    // When set, the guided walk-through takes over the screen. null means the
    // settings list is showing.
    var permissionFlowAt by remember { mutableStateOf<PermissionId?>(null) }
    var permissionFlowOpen by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    if (permissionFlowOpen) {
        // Fixing a permission is the same guided walk setup uses. The previous
        // version sent the user straight to a system screen from a one-word
        // "Fix" link, which is where they got lost.
        PermissionFlow(
            onFinished = {
                permissionFlowOpen = false
                viewModel.refresh()
            },
            onExit = {
                permissionFlowOpen = false
                viewModel.refresh()
            },
            startAt = permissionFlowAt
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BackgroundDark),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScreenTitle("Setup") }

            // The answer first, at a size that reads instantly. Five small grey
            // ticks could not distinguish a protected phone from a broken one.
            item {
                val granted = listOf(
                    state.hasAccessibility,
                    state.hasUsageAccess,
                    state.hasOverlay,
                    state.batteryUnrestricted,
                    state.canScheduleExactAlarms
                )
                val ok = granted.count { it }
                StatusHero(
                    state = when {
                        !state.hasAccessibility || !state.hasUsageAccess -> HeroState.BROKEN
                        ok == granted.size -> HeroState.LIVE
                        else -> HeroState.IDLE
                    },
                    headline = when {
                        !state.hasAccessibility -> "Blocking can't run"
                        !state.hasUsageAccess -> "Budgets can't be counted"
                        ok == granted.size -> "Everything's in place"
                        else -> "Working, with gaps"
                    },
                    detail = when {
                        !state.hasAccessibility ->
                            "The accessibility service is off, so no app can be blocked."
                        !state.hasUsageAccess ->
                            "Usage access is off, so time budgets can't be measured."
                        ok == granted.size ->
                            "All $ok permissions granted. ${state.ruleCount} " +
                                (if (state.ruleCount == 1) "routine" else "routines") + " enabled."
                        else -> "$ok of ${granted.size} permissions granted."
                    }
                )
            }

            // ---------------- Permissions ----------------
            item {
                val allOk = state.hasAccessibility && state.hasUsageAccess &&
                    state.hasOverlay && state.batteryUnrestricted && state.canScheduleExactAlarms
                Column {
                    SectionHeading("Permissions")
                    Spacer(Modifier.height(8.dp))
                    if (allOk && !permissionsExpanded) {
                        Box(Modifier.clickable { permissionsExpanded = true }) {
                            SummaryRow(
                                ok = true,
                                okText = "All five granted",
                                problemText = ""
                            )
                        }
                    } else if (!allOk) {
                        // One button to the guided walk, above the list, so the
                        // user who does not know which of five to press has an
                        // obvious move.
                        Button(
                            onClick = {
                                permissionFlowAt = null
                                permissionFlowOpen = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Signal),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(
                                "Walk me through it",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            if (!(state.hasAccessibility && state.hasUsageAccess && state.hasOverlay &&
                    state.batteryUnrestricted && state.canScheduleExactAlarms) || permissionsExpanded
            ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardDark)
                ) {
                    PermRow(
                        "Accessibility service",
                        "Required. Nothing can be blocked without it.",
                        state.hasAccessibility,
                        { permissionFlowAt = PermissionId.ACCESSIBILITY; permissionFlowOpen = true }
                    )
                    PermRow(
                        "Usage access",
                        "Required for time budgets and screen-time figures.",
                        state.hasUsageAccess,
                        { permissionFlowAt = PermissionId.USAGE; permissionFlowOpen = true }
                    )
                    PermRow(
                        "Display over apps",
                        "Makes the block screen appear reliably.",
                        state.hasOverlay,
                        { permissionFlowAt = PermissionId.OVERLAY; permissionFlowOpen = true }
                    )
                    PermRow(
                        "Battery unrestricted",
                        "Stops the system killing blocking in the background. " +
                            "This is the usual reason a blocker quietly stops working.",
                        state.batteryUnrestricted,
                        { permissionFlowAt = PermissionId.BATTERY; permissionFlowOpen = true }
                    )
                    PermRow(
                        "Exact alarms",
                        "Without this, routines can start or lift up to a minute late.",
                        state.canScheduleExactAlarms,
                        { permissionFlowAt = PermissionId.EXACT_ALARM; permissionFlowOpen = true },
                        last = true
                    )
                }
            }
            }

            // ---------------- Apps ----------------
            item { SectionHeading("Apps") }
            item {
                RowCard(
                    title = "Always allowed",
                    subtitle = "${state.allowlistCount} apps stay reachable under every " +
                        "routine, including locked ones",
                    onClick = {
                        showAllowlist = true
                        viewModel.loadApps()
                    }
                )
            }

            // ---------------- Protection ----------------
            item { SectionHeading("Protection") }
            item {
                ProtectionCard(
                    state = state,
                    onLock = viewModel::lockConfiguration,
                    onUnlock = viewModel::unlockConfiguration,
                    onSetPin = { showPinDialog = true }
                )
            }

            item { SectionHeading("About") }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardDark)
                        .padding(16.dp)
                ) {
                    Text("FocusBlock", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Blocks the apps you choose at the times you choose. " +
                            "It doesn't claim to repair attention or reset anything — " +
                            "it makes the apps harder to reach so the decision isn't " +
                            "yours in the moment.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    // Shown so the build on the device is never a guess. Every
                    // APK previously reported version 1.0.0 regardless of what
                    // was in it, which made "did the update install?"
                    // impossible to answer from inside the app.
                    Text(
                        "Build ${com.focusblock.app.BuildConfig.VERSION_NAME}",
                        color = Signal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.ruleCount} " +
                            (if (state.ruleCount == 1) "routine" else "routines") + " enabled",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAllowlist) {
        AllowlistSheet(
            state = state,
            onToggle = viewModel::toggleAllowlist,
            onDismiss = { showAllowlist = false }
        )
    }

    if (showPinDialog) {
        PinDialog(
            onConfirm = {
                viewModel.setPin(it)
                showPinDialog = false
            },
            onDismiss = { showPinDialog = false }
        )
    }
}

@Composable
private fun ProtectionCard(
    state: ProfileUiState,
    onLock: (Int) -> Unit,
    onUnlock: () -> Unit,
    onSetPin: () -> Unit
) {
    val locked = state.lock.isLocked()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardDark)
            .padding(16.dp)
    ) {
        Text(
            if (locked) "Configuration locked" else "Configuration unlocked",
            color = if (locked) AccentOrange else TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))

        if (locked) {
            Text(
                "Until ${SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
                    .format(Date(state.lock.lockedUntil))}",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            // The exact terms, on screen, while they apply.
            Text(
                "While this holds you can't delete routines, remove apps from them, " +
                    "shorten them or turn protection off. You can still add routines, " +
                    "add apps, and allow apps you need.",
                color = TextSecondary,
                fontSize = 12.sp
            )
        } else {
            Text(
                "Freeze your routines so you can't weaken them in a weak moment. " +
                    "You'll still be able to make them stronger, and allowed apps " +
                    "stay reachable throughout.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        if (!locked) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LockChip("12h") { onLock(12) }
                LockChip("24h") { onLock(24) }
                LockChip("3 days") { onLock(72) }
                LockChip("1 week") { onLock(168) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSetPin, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        if (state.lock.pinHash.isEmpty()) "Set a PIN" else "Change PIN",
                        color = Signal,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.lock.pinHash.isEmpty()) "Optional — adds a PIN and a wait"
                    else "PIN set",
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
            if (state.lock.level != CommitmentLevel.OFF) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onUnlock, contentPadding = PaddingValues(0.dp)) {
                    Text("Turn protection off", color = TextSecondary, fontSize = 13.sp)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LockChip("+24h") { onLock(24) }
                LockChip("+1 week") { onLock(168) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "It clears on its own when the time is up.",
                color = TextTertiary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun LockChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PermRow(
    title: String,
    why: String,
    granted: Boolean,
    onFix: () -> Unit,
    last: Boolean = false
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !granted, onClick = onFix)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) AccentGreen else AccentOrange,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp)
                Text(why, color = TextTertiary, fontSize = 12.sp)
            }
            if (!granted) {
                Text("Fix", color = Signal, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (!last) Divider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun RowCard(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardDark)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = TextSecondary, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowlistSheet(
    state: ProfileUiState,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BackgroundDarkTertiary) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Always allowed", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "These stay reachable under every routine, including locked ones. " +
                    "This is the one thing a commitment can never take away.",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))

            if (state.appsLoading) {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Signal, modifier = Modifier.size(24.dp))
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(state.apps, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.allowed,
                                onCheckedChange = { onToggle(app.packageName) },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = TextPrimary, fontSize = 14.sp)
                                if (app.suggestedEssential) {
                                    Text(
                                        "Often needed for calls or clients",
                                        color = AccentOrange,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Set a PIN", color = TextPrimary) },
        text = {
            Column {
                Text(
                    "Used to stop a PIN-locked routine early, after a short wait. " +
                        "Stored hashed, never in the clear.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                    label = { Text("4-8 digits") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }) { Text("Save", color = Signal) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}
