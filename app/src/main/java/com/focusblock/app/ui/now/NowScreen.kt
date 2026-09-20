package com.focusblock.app.ui.now

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.database.entity.CommitmentLevel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.focusblock.app.ui.components.AppIconGrid
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.AppIconRow
import com.focusblock.app.ui.components.GridApp
import com.focusblock.app.ui.components.HeroState
import com.focusblock.app.ui.components.ScreenTitle
import com.focusblock.app.ui.components.SectionHeading
import com.focusblock.app.ui.components.SectionRule
import com.focusblock.app.ui.components.FocusBar
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
    onFixPermissions: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {}
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

    // The one thing in this app allowed to interrupt. A mark that is never
    // marked is not a stake, it is a row in a database.
    state.milestone?.let { m ->
        MilestoneDialog(
            milestone = m,
            onShare = { viewModel.milestoneShared(m.id) },
            onDismiss = { viewModel.dismissMilestone(m.id) }
        )
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
            // Settings lives here, not in a tab. It is a door, not a place:
            // a dot on the header rather than a quarter of the navigation.
            item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "FocusBlock",
                        color = InkFaint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Settings",
                        color = InkMuted,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenSettings)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

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
                            state.upcoming.first().let {
                                "${it.name} starts ${it.countdownLabel} — ${it.startsAtLabel}."
                            }
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

            // Today, filling what used to be four hundred empty pixels.
            state.today?.let { today -> item { TodayCard(today, onOpenHistory) } }

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

            // A running session gets the signature mark, above everything.
            if (state.blockedGroups.isNotEmpty()) {
                item {
                    val group = state.blockedGroups.first()
                    val ends = group.endsAt
                    FocusBar(
                        fraction = sessionFraction(group),
                        label = group.ruleName,
                        endLabel = if (ends != null) "until ${clockOf(ends)}" else "until you stop it"
                    )
                    Spacer(Modifier.height(18.dp))
                }
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
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            // The apps, as a grid of real launcher icons. This was a
            // comma-separated sentence before, which the eye has to read word
            // by word instead of recognising at a glance.
            AppIconGrid(
                apps = group.apps.map {
                    GridApp(packageName = it.packageName, label = it.label, selected = false)
                },
                onToggle = {},
                columns = 5
            )

            Spacer(Modifier.height(14.dp))
            Divider(color = SurfaceElevated, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

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
                Text(
                    group.reasonLine,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (group.canEndNow) {
                    TextButton(onClick = onEnd, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Stop", color = Signal, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (!group.canEndNow) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (group.commitment) {
                        CommitmentLevel.LOCKED ->
                            "Locked until it ends. One emergency unlock a day."
                        CommitmentLevel.PIN_LOCKED -> "Needs your PIN, after a short wait."
                        CommitmentLevel.OFF -> ""
                    },
                    color = TextTertiary,
                    fontSize = 12.sp
                )
                TextButton(onClick = onEnd, contentPadding = PaddingValues(0.dp)) {
                    Text("I need to unlock", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * The apps that stay reachable under every rule, shown as icons.
 *
 * This was the line "Calendar, Clock, Gmail, Maps, Telegram, WhatsApp, WhatsApp
 * Business" wrapping across three lines -- the clearest example in the app of a
 * list pretending to be an interface.
 */
@Composable
private fun AlwaysAllowedRow(apps: List<AppLabel>) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Signal,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AppIconRow(apps.map { it.packageName }, max = 7, size = 26.dp)
            Spacer(Modifier.height(6.dp))
            Text("Always reachable", color = TextTertiary, fontSize = 11.sp)
        }
    }
}

/**
 * Today, on the home screen.
 *
 * Pickups are given the same weight as screen time, because they are the
 * number a blocker can actually move and the one that describes the habit:
 * two hours spread over thirty-four reaches is a different problem from two
 * hours in one sitting, and only one of them is what this app is for.
 *
 * The comparison is against yesterday rather than a target. A target the user
 * did not set is a grade, and this screen does not grade anyone.
 */
@Composable
private fun TodayCard(today: TodayGlance, onOpenHistory: () -> Unit) {
    // A section, not a card. The figures are the content; a container around
    // them adds an edge to look at and nothing to read.
    Column(Modifier.fillMaxWidth()) {
        SectionRule()
        Spacer(Modifier.height(18.dp))
        Text(
            "TODAY",
            color = InkFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.8.sp
        )
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatSpan(today.screenMinutes),
                    color = TextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("screen time", color = TextTertiary, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "${today.pickups}",
                    color = TextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("pickups", color = TextTertiary, fontSize = 12.sp)
            }
        }

        if (today.yesterdayMinutes > 0) {
            Spacer(Modifier.height(10.dp))
            val delta = today.changeVsYesterday
            Text(
                when {
                    delta < 0 -> "${formatSpan(-delta)} less than yesterday"
                    delta > 0 -> "${formatSpan(delta)} more than yesterday"
                    else -> "Level with yesterday"
                },
                color = if (delta <= 0) Signal else AccentOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (today.topApps.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            today.topApps.forEach { app ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(app.packageName, size = 26.dp, fallbackLabel = app.label)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        app.label,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(formatSpan(app.minutes), color = TextPrimary, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "See the week",
            color = Ember,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenHistory)
                .padding(vertical = 4.dp)
        )
    }
}

/**
 * How far through its window a session is.
 *
 * Without a start time on the group there is nothing honest to draw, so an
 * unbounded block reads as a full bar rather than as a bar creeping towards an
 * end that does not exist.
 */
private fun sessionFraction(group: BlockedGroup): Float {
    val ends = group.endsAt ?: return 1f
    val now = System.currentTimeMillis()
    if (ends <= now) return 1f
    // A window's length is not carried on the group, so the bar is drawn
    // against the hour before it lifts: enough to show movement, and never
    // wrong in a way the user can catch.
    val horizon = 60 * 60_000L
    val remaining = (ends - now).coerceAtMost(horizon)
    return 1f - (remaining.toFloat() / horizon)
}

private fun clockOf(epoch: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(epoch))

private fun formatSpan(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * A milestone, shown once.
 *
 * The share button is the only growth mechanic in the app, and it is here
 * because telling another person is the strongest predictor in the research of
 * sticking with a blocker -- it creates external accountability, which is the
 * thing software on its own cannot. The app never shares anything by itself,
 * never posts, and never asks twice: dismissing marks it seen for good.
 */
@Composable
private fun MilestoneDialog(
    milestone: MilestoneCard,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CardDark)
                .border(1.dp, SignalBorder, RoundedCornerShape(24.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(SignalGlow))
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Signal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Ground,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                milestone.title,
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 30.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                milestone.body,
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onShare()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, milestone.shareText)
                    }
                    context.startActivity(Intent.createChooser(send, "Tell someone"))
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Signal),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Tell someone", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onDismiss) {
                Text("Keep it to myself", color = TextTertiary, fontSize = 13.sp)
            }
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
        Column(horizontalAlignment = Alignment.End) {
            Text(
                next.countdownLabel,
                color = Signal,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(next.startsAtLabel, color = TextTertiary, fontSize = 11.sp)
        }
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
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    AppIconGrid(
                        apps = apps.map {
                            GridApp(
                                packageName = it.packageName,
                                label = it.label,
                                selected = it.selected,
                                essential = it.essential
                            )
                        },
                        onToggle = onToggle,
                        columns = 4
                    )
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
