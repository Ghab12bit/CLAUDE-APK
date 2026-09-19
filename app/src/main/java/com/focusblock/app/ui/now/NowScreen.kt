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
import com.focusblock.app.ui.components.HeroState
import com.focusblock.app.ui.components.ScreenTitle
import com.focusblock.app.ui.components.SectionHeading
import com.focusblock.app.ui.components.StatusHero
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
    var showPicker by remember { mutableStateOf(false) }

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
            // The whole point of the screen, stated at a size you can read
            // without looking for it.
            item {
                val p = state.protection
                val blockedApps = state.blockedGroups.sumOf { it.apps.size }
                StatusHero(
                    state = when {
                        !p.enforcing -> HeroState.BROKEN
                        blockedApps > 0 -> HeroState.LIVE
                        else -> HeroState.IDLE
                    },
                    headline = when {
                        !p.enforcing -> "Blocking isn't running"
                        blockedApps > 0 -> "$blockedApps apps blocked"
                        else -> "Nothing blocked"
                    },
                    detail = when {
                        !p.enforcing -> p.firstProblem ?: "Check permissions in Setup."
                        blockedApps > 0 -> state.blockedGroups.firstOrNull()?.reasonLine.orEmpty()
                        state.upcoming.isNotEmpty() ->
                            "Next: ${state.upcoming.first().name}, ${state.upcoming.first().startsAtLabel}."
                        else -> "No routine is running. Start a block below."
                    },
                    trailing = if (!p.enforcing) {
                        {
                            Button(
                                onClick = onFixPermissions,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                            ) { Text("Fix this", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    } else null
                )
            }

            // The stake. Framed as what there is to lose, never as a score.
            if (state.streakDays > 0 || state.impulsesPassed > 0 || state.protectedHours > 0) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StakeTile("${state.streakDays}", "day streak", Modifier.weight(1f),
                            highlight = state.streakDays >= 2)
                        StakeTile("${state.impulsesPassed}", "urges passed", Modifier.weight(1f))
                        StakeTile("${state.protectedHours}h", "protected", Modifier.weight(1f))
                    }
                }
            }

            if (state.blockedGroups.isNotEmpty()) {
                item { SectionHeading("Blocked right now") }
            }

            if (state.blockedGroups.isEmpty()) {
                // The hero already says nothing is blocked; no second card.
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
                item { SectionHeading("Next up") }
                items(state.upcoming, key = { "next_${it.ruleId}" }) { next ->
                    UpcomingRow(next, onOpenRoutines)
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                ActionRow(
                    hasManualRunning = state.manualRuleRunning != null,
                    selectedCount = state.savedApps.size,
                    onStart = { minutes ->
                        // No apps chosen yet? Open the picker rather than
                        // refusing with an error and leaving nowhere to go.
                        if (state.savedApps.isEmpty()) {
                            viewModel.openPicker()
                            showPicker = true
                        } else {
                            viewModel.startBlockNow(state.savedApps, minutes)
                        }
                    },
                    onEditApps = {
                        viewModel.openPicker()
                        showPicker = true
                    }
                )
            }
        }
    }

    if (showPicker) {
        AppPickerSheet(
            apps = state.pickerApps,
            loading = state.pickerLoading,
            onToggle = viewModel::togglePickerApp,
            onDone = { showPicker = false }
        )
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
            tint = Signal,
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

/**
 * Start a block, or change which apps it covers. Two controls, no more: the
 * common case is one tap.
 */
@Composable
private fun ActionRow(
    hasManualRunning: Boolean,
    selectedCount: Int,
    onStart: (Int?) -> Unit,
    onEditApps: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = !expanded },
            enabled = !hasManualRunning,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Signal),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    hasManualRunning -> "Block already running"
                    selectedCount == 0 -> "Choose apps to block"
                    else -> "Block now"
                },
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
            Text(
                if (selectedCount == 0) "Choose apps"
                else "$selectedCount apps selected · change",
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Pick the apps a one-off block covers.
 *
 * Pre-filled from the apps already in the user's routines, so the common case
 * is open-and-go rather than ticking a list from scratch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    apps: List<PickableApp>,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onDone: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDone, containerColor = BackgroundDarkTertiary) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                "Block which apps?",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${apps.count { it.selected }} selected",
                color = Signal,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(14.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Signal, modifier = Modifier.size(24.dp))
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.selected,
                                onCheckedChange = { onToggle(app.packageName) },
                                colors = CheckboxDefaults.colors(checkedColor = Signal)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = TextPrimary, fontSize = 14.sp)
                                if (app.essential) {
                                    Text(
                                        "You may need this for calls or clients",
                                        color = AccentOrange,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDone,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Done", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * One number, large, with a quiet label. Restores the density the original app
 * had -- something to look at and take in at a glance -- while measuring things
 * that reflect what the person did rather than what the software did.
 */
@Composable
private fun StakeTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlight) SignalGlow else CardDark)
            .then(
                if (highlight) Modifier.border(1.dp, SignalBorder, RoundedCornerShape(16.dp))
                else Modifier
            )
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            color = if (highlight) Signal else TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextTertiary, fontSize = 11.sp)
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
