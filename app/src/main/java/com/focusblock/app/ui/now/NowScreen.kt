package com.focusblock.app.ui.now

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.ui.theme.*

/**
 * The home screen.
 *
 * It answers four questions, top to bottom, in the order they matter:
 *
 *   1. Is blocking actually working?
 *   2. What is blocked, why, and until when?
 *   3. What starts next?
 *   4. How do I start a block or adjust my plan?
 *
 * What is NOT here is as deliberate as what is. There is no goal field, no
 * intention prompt and no reflection step: the blocker must be usable without
 * typing anything. Stats tiles, suggestions and commitment settings have moved
 * to the screens where they belong, so this screen stays one screenful.
 */
@Composable
fun NowScreen(
    viewModel: NowViewModel = hiltViewModel(),
    onEditApps: () -> Unit = {},
    onOpenRoutines: () -> Unit = {},
    onFixPermissions: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { ProtectionCard(state.protection, onFixPermissions) }

            item {
                SectionLabel(
                    if (state.blockedGroups.isEmpty()) "Nothing is blocked right now"
                    else "Blocked right now"
                )
            }

            if (state.blockedGroups.isEmpty()) {
                item { EmptyBlockingCard() }
            } else {
                items(state.blockedGroups, key = { it.ruleId }) { group ->
                    BlockedGroupCard(
                        group = group,
                        onEnd = { viewModel.endRule(group.ruleId) }
                    )
                }
            }

            if (state.alwaysAllowed.isNotEmpty()) {
                item { AlwaysAllowedRow(state.alwaysAllowed) }
            }

            if (state.upcoming.isNotEmpty()) {
                item { SectionLabel("Next up") }
                items(state.upcoming, key = { "next_${it.ruleId}" }) { next ->
                    UpcomingRow(next, onOpenRoutines)
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                ActionRow(
                    hasManualRunning = state.manualRuleRunning != null,
                    onStart = { minutes -> viewModel.startBlockNow(state.savedApps, minutes) },
                    onEditApps = onEditApps
                )
            }
        }
    }
}

/**
 * Reports the signals that actually determine whether a block will happen,
 * rather than a badge derived from a flag. A green tick here is a claim the
 * app can back up.
 */
@Composable
private fun ProtectionCard(status: ProtectionStatus, onFix: () -> Unit) {
    val tint = when {
        !status.enforcing -> AccentRed
        !status.fullyHealthy -> AccentOrange
        else -> AccentGreen
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(tint)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = status.headline,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(12.dp))

            SignalRow("Accessibility service", status.accessibilityConnected)
            SignalRow("Usage access", status.usageAccessGranted)
            SignalRow("Display over apps", status.overlayGranted)
            SignalRow("Battery unrestricted", status.batteryUnrestricted)

            status.firstProblem?.let { problem ->
                Spacer(Modifier.height(12.dp))
                Text(problem, color = tint, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onFix, contentPadding = PaddingValues(0.dp)) {
                    Text("Fix this", color = Primary, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SignalRow(label: String, ok: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) AccentGreen else AccentOrange,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (ok) TextSecondary else TextPrimary, fontSize = 13.sp)
    }
}

/**
 * One rule and the apps it blocks.
 *
 * Grouping by rule rather than by app is the whole point: when two rules cover
 * the same app the user sees two cards, so overlap is visible and ending one
 * of them clearly leaves the other standing.
 */
@Composable
private fun BlockedGroupCard(group: BlockedGroup, onEnd: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = group.apps.joinToString(", ") { it.label },
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.commitment != CommitmentLevel.OFF) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Locked",
                        tint = AccentOrange,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(group.reasonLine, color = TextSecondary, fontSize = 13.sp)
            }

            Spacer(Modifier.height(12.dp))

            if (group.canEndNow) {
                OutlinedButton(
                    onClick = onEnd,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Stop this", fontSize = 13.sp)
                }
            } else {
                // The terms were shown before this armed; restate them here
                // rather than presenting a button that will simply refuse.
                Text(
                    text = when (group.commitment) {
                        CommitmentLevel.LOCKED ->
                            "Locked until it ends. One emergency unlock a day if you need it."
                        CommitmentLevel.PIN_LOCKED ->
                            "Needs your PIN, after a short wait."
                        CommitmentLevel.OFF -> ""
                    },
                    color = TextTertiary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onEnd, contentPadding = PaddingValues(0.dp)) {
                    Text("I need to unlock", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Shown so the user can see at a glance that the apps they need for work are
 * reachable. The allowlist beats every rule, including a locked one.
 */
@Composable
private fun AlwaysAllowedRow(apps: List<AppLabel>) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                apps.joinToString(", ") { it.label },
                color = TextPrimary,
                fontSize = 13.sp
            )
            Text("Always allowed", color = TextTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun UpcomingRow(next: UpcomingRule, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .background(SurfaceDark)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(next.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${next.appCount} apps", color = TextTertiary, fontSize = 12.sp)
        }
        Text(next.startsAtLabel, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyBlockingCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(18.dp)
    ) {
        Text(
            "No rule is blocking anything at the moment.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Start a block below, or set up a routine so it happens without you deciding.",
            color = TextTertiary,
            fontSize = 13.sp
        )
    }
}

/**
 * Start a block, or change which apps it covers. Two controls, no more: the
 * common case is one tap.
 */
@Composable
private fun ActionRow(
    hasManualRunning: Boolean,
    onStart: (Int?) -> Unit,
    onEditApps: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = !expanded },
            enabled = !hasManualRunning,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasManualRunning) "Block already running" else "Block now",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (expanded && !hasManualRunning) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DurationChip("30m") { onStart(30); expanded = false }
                DurationChip("60m") { onStart(60); expanded = false }
                DurationChip("90m") { onStart(90); expanded = false }
                DurationChip("Until I stop") { onStart(null); expanded = false }
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onEditApps,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Choose apps", fontSize = 14.sp)
        }
    }
}

@Composable
private fun RowScope.DurationChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}
