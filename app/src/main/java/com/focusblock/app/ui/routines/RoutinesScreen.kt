package com.focusblock.app.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.CommitmentLevel
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Work
import com.focusblock.app.ui.components.AppIconGrid
import com.focusblock.app.ui.components.GridApp
import com.focusblock.app.ui.components.AppIconRow
import com.focusblock.app.ui.components.TimeWindowBar
import com.focusblock.app.ui.components.HeroState
import com.focusblock.app.ui.components.ScreenTitle
import com.focusblock.app.ui.components.SectionHeading
import com.focusblock.app.ui.components.SectionRule
import com.focusblock.app.ui.components.StatusHero
import com.focusblock.app.ui.theme.*

/**
 * Routines: the rules that run without you deciding anything in the moment.
 *
 * This is where the evening work block lives. Everything a rule does is on one
 * screen and editable -- times, days, apps and how hard it is to stop -- so
 * there is nothing to discover in a settings tree later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(viewModel: RoutinesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showTemplates by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTemplates = true },
                containerColor = Signal
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New routine")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BackgroundDark),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenTitle("Routines", "Blocking that happens without you deciding")
            }

            item {
                val active = state.routines.count { it.isActiveNow }
                val enabled = state.routines.count { it.rule.isEnabled }
                StatusHero(
                    state = if (active > 0) HeroState.LIVE else HeroState.IDLE,
                    headline = when {
                        active > 0 -> if (active == 1) "1 routine running" else "$active routines running"
                        enabled > 0 -> "Nothing running yet"
                        else -> "No routines"
                    },
                    detail = when {
                        active > 0 -> state.routines.firstOrNull { it.isActiveNow }?.summary.orEmpty()
                        enabled == 1 -> "One routine set up, waiting for its window."
                        enabled > 0 -> "$enabled set up, waiting for their windows."
                        else -> "Add one so blocking happens without you deciding."
                    }
                )
            }

            if (state.routines.isEmpty() && !state.loading) {
                item { EmptyRoutines { showTemplates = true } }
            }

            items(state.routines, key = { it.rule.id }) { row ->
                RoutineCard(
                    row = row,
                    onToggle = { viewModel.setEnabled(row.rule, it) },
                    onEdit = { viewModel.beginEditing(row.rule) },
                    onDelete = { viewModel.delete(row.rule) }
                )
            }

            // Templates, on the screen rather than behind the button.
            //
            // They were rendered only when the user had NO routines, so anyone
            // who made one never saw them again -- and the four kinds of
            // routine this app can build stayed invisible behind a "+" that
            // gives no clue what is on the other side. A blocker whose main
            // screen is two cards and empty space looks like it has nothing
            // left to offer.
            val existing = state.routines.map { it.rule.name }.toSet()
            val unused = RuleTemplates.ALL.filter { it.title !in existing && it.key != "block_now" }
            if (unused.isNotEmpty() && !state.loading) {
                item { SectionHeading("Add another") }
                items(unused, key = { "tpl_${it.key}" }) { template ->
                    TemplateCard(template) { viewModel.beginFromTemplate(template) }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showTemplates) {
        TemplateSheet(
            onDismiss = { showTemplates = false },
            onPick = {
                showTemplates = false
                viewModel.beginFromTemplate(it)
            }
        )
    }

    // Drawn after the Scaffold, so it covers it: the editor is a place you go,
    // not a drawer over the place you were.
    state.editing?.let { draft ->
        RuleEditorScreen(
            draft = draft,
            apps = state.pickerApps,
            appsLoading = state.pickerLoading,
            onChange = viewModel::updateDraft,
            onToggleApp = viewModel::toggleApp,
            onSave = viewModel::saveDraft,
            onDismiss = viewModel::cancelEditing
        )
    }
}

/**
 * One template, as a card on the screen.
 *
 * Carries the same coloured mark as a real routine card, so the list reads as
 * "routines you have" followed by "routines you could have" rather than as two
 * unrelated things.
 */
@Composable
private fun TemplateCard(template: RuleTemplates.Template, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                template.title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(template.summary, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SignalGlow),
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = Signal, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun EmptyRoutines(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(18.dp)
    ) {
        Text("No routines yet", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "A routine blocks the apps you choose at the times you choose, " +
                "so you don't have to decide in the moment.",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Signal)
        ) {
            Text("Set one up")
        }
    }
}

@Composable
private fun RoutineCard(
    row: RoutineRow,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val nowMinute = remember {
        val c = java.util.Calendar.getInstance()
        c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    // A row on the page, not a card. Every routine having its own container
    // is what produced the stack of tiles; a hairline and space separate them
    // just as clearly and leave the routine itself as the only thing drawn.
    //
    // A running routine is marked by an Ember rail down its left edge -- a
    // position and a shape, not only a colour, so the state survives being
    // read without colour vision.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        SectionRule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(if (row.isActiveNow) Ember else Color.Transparent)
            )
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 18.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.rule.name,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (row.rule.commitment != CommitmentLevel.OFF) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Locked",
                                tint = AccentOrange,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (row.isActiveNow) "Running now" else row.summary,
                        color = if (row.isActiveNow) Ember else InkFaint,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = row.rule.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            if (row.rule.hasTimeCondition) {
                Spacer(Modifier.height(16.dp))
                // The window as a shape on a 24-hour track, with a marker for
                // where the day currently is. Faster to read than the sentence
                // "8:45pm - 10:30pm".
                TimeWindowBar(
                    startMinute = row.rule.startMinute,
                    endMinute = row.rule.endMinute,
                    nowMinute = nowMinute,
                    active = row.isActiveNow
                )
            }

            if (row.appCount > 0) {
                Spacer(Modifier.height(14.dp))
                AppIconRow(
                    packages = row.rule.packageList(),
                    max = 7,
                    size = 26.dp,
                    dimmed = !row.rule.isEnabled
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(dayLabel(row.rule.daysOfWeek), color = InkFaint, fontSize = 11.sp)
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete routine",
                        tint = InkFaint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            }
        }
    }
}

private fun iconFor(type: com.focusblock.app.database.entity.ScheduleIconType) = when (type) {
    com.focusblock.app.database.entity.ScheduleIconType.SLEEP -> Icons.Filled.Bedtime
    com.focusblock.app.database.entity.ScheduleIconType.WORK -> Icons.Filled.Work
    com.focusblock.app.database.entity.ScheduleIconType.STUDY -> Icons.Filled.School
    com.focusblock.app.database.entity.ScheduleIconType.DETOX -> Icons.Filled.Spa
    else -> Icons.Filled.Shield
}

private fun dayLabel(csv: String): String {
    val days = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted()
    return when {
        days.size == 7 -> "Every day"
        days == listOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == listOf(6, 7) -> "Weekends"
        else -> days.joinToString(" ") { listOf("M", "T", "W", "T", "F", "S", "S")[it - 1] }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(
    onDismiss: () -> Unit,
    onPick: (RuleTemplates.Template) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BackgroundDarkTertiary) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Start from", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Everything is editable afterwards.",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))

            RuleTemplates.ALL.forEach { template ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceDark)
                        .clickable { onPick(template) }
                        .padding(16.dp)
                ) {
                    Text(
                        template.title,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(template.summary, color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * The setup form.
 *
 * Commitment is the last thing on the sheet and always shows its exact terms
 * before it is armed -- what you cannot do, and how to get out if you must.
 * The old app let you turn on a mode and only discover the restrictions when
 * you hit them.
 */
/**
 * The routine editor, as a screen.
 *
 * It was a bottom sheet capped at 620dp with an app grid scrolling inside its
 * own 260dp box -- a scroll area inside a scroll area inside a sheet, on the
 * screen where the user does the most consequential thing in the app. Setting
 * up a routine is the point of the product; it does not belong in a drawer
 * that covers two thirds of the display and has to be dismissed to see what
 * you were editing.
 *
 * Full screen, one scroll, and a save bar that never leaves.
 */
@Composable
private fun RuleEditorScreen(
    draft: BlockRule,
    apps: List<PickableApp>,
    appsLoading: Boolean,
    onChange: ((BlockRule) -> BlockRule) -> Unit,
    onToggleApp: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ground)) {
        // Its own way out, since there is no sheet scrim to tap.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Cancel",
                color = InkMuted,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(draft.name, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            if (draft.hasTimeCondition) {
                FieldLabel("Time")
                TimeRow(
                    startMinute = draft.startMinute,
                    endMinute = draft.endMinute,
                    onStart = { m -> onChange { it.copy(startMinute = m) } },
                    onEnd = { m -> onChange { it.copy(endMinute = m) } }
                )
                Spacer(Modifier.height(16.dp))

                FieldLabel("Days")
                DayPicker(draft.daysOfWeek) { days -> onChange { it.copy(daysOfWeek = days) } }
                Spacer(Modifier.height(16.dp))
            }

            if (draft.hasUsageCondition) {
                FieldLabel(
                    if (draft.usageWindow == com.focusblock.app.database.entity.UsageWindow.DAILY)
                        "Minutes a day" else "Minutes an hour"
                )
                BudgetRow(draft.usageLimitMinutes) { m -> onChange { it.copy(usageLimitMinutes = m) } }
                Spacer(Modifier.height(16.dp))
            }

            if (draft.hasLaunchCondition) {
                FieldLabel(
                    if (draft.launchWindow == com.focusblock.app.database.entity.UsageWindow.DAILY)
                        "Opens a day" else "Opens an hour"
                )
                OpenLimitRow(draft.launchLimit) { n -> onChange { it.copy(launchLimit = n) } }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Counts every time you open one of these apps. Attempts that " +
                        "get blocked don't count.",
                    color = TextTertiary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
            }

            FieldLabel("Apps to block (${draft.packageList().size})")
            if (appsLoading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Signal, modifier = Modifier.size(22.dp))
                }
            } else {
                // No nested scroll: the page scrolls, the grid just lays out.
                Box(Modifier.fillMaxWidth()) {
                    AppIconGrid(
                        apps = apps.map {
                            GridApp(
                                packageName = it.packageName,
                                label = it.label,
                                selected = it.selected,
                                essential = it.essential
                            )
                        },
                        onToggle = onToggleApp,
                        columns = 4
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            FieldLabel("How hard to stop")
            CommitmentPicker(draft.commitment) { level -> onChange { it.copy(commitment = level) } }
            Spacer(Modifier.height(32.dp))
        }

        // Always visible, so a long app list never hides the way to finish.
        Column(Modifier.fillMaxWidth().background(Ground).padding(20.dp)) {
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ember),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Save routine", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun TimeRow(
    startMinute: Int,
    endMinute: Int,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stepper("Starts", startMinute, Modifier.weight(1f), onStart)
        Stepper("Ends", endMinute, Modifier.weight(1f), onEnd)
    }
}

/** 15-minute steps: fine enough for a real routine, fast enough to set by thumb. */
@Composable
private fun Stepper(label: String, minuteOfDay: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(12.dp)
    ) {
        Text(label, color = TextTertiary, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("-") { onChange(((minuteOfDay - 15) + 1440) % 1440) }
            Text(
                formatClock(minuteOfDay),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            StepButton("+") { onChange((minuteOfDay + 15) % 1440) }
        }
    }
}

@Composable
private fun BudgetRow(minutes: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton("-") { onChange((minutes - 5).coerceAtLeast(5)) }
        Text(
            "$minutes min",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        StepButton("+") { onChange((minutes + 5).coerceAtMost(720)) }
    }
}

/**
 * Opens step one at a time, not five: the useful range is small. Going from
 * ten opens a day to five is a real change; from sixty minutes to fifty-five
 * is not, which is why the minutes row steps in fives and this one does not.
 */
@Composable
private fun OpenLimitRow(opens: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton("-") { onChange((opens - 1).coerceAtLeast(1)) }
        Text(
            if (opens == 1) "1 open" else "$opens opens",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        StepButton("+") { onChange((opens + 1).coerceAtMost(100)) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DayPicker(csv: String, onChange: (String) -> Unit) {
    val selected = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LABELS.forEachIndexed { index, label ->
            val day = index + 1
            val on = day in selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) SignalGlow else SurfaceDark)
                    .clickable {
                        val next = selected.toMutableSet()
                        if (!next.add(day)) next.remove(day)
                        onChange(next.sorted().joinToString(","))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Signal else TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Commitment, with the terms stated before it arms rather than discovered when
 * it refuses.
 */
@Composable
private fun CommitmentPicker(current: CommitmentLevel, onChange: (CommitmentLevel) -> Unit) {
    val options = listOf(
        Triple(
            CommitmentLevel.OFF,
            "Off",
            "Stop it whenever you want."
        ),
        Triple(
            CommitmentLevel.LOCKED,
            "Locked",
            "You can't stop it early. One emergency unlock a day, 15 minutes, " +
                "and it's recorded. Allowed apps stay reachable."
        ),
        Triple(
            CommitmentLevel.PIN_LOCKED,
            "PIN locked",
            "Needs your PIN plus a short wait before it will stop. " +
                "Allowed apps stay reachable."
        )
    )

    Column {
        options.forEach { (level, title, terms) ->
            val on = current == level
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) SignalGlow else SurfaceDark)
                    .then(
                        if (on) Modifier.border(1.dp, SignalBorder, RoundedCornerShape(14.dp))
                        else Modifier
                    )
                    .clickable { onChange(level) }
                    .padding(14.dp)
            ) {
                Text(
                    title,
                    color = if (on) Signal else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(terms, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

private fun formatClock(minuteOfDay: Int): String {
    val h24 = minuteOfDay / 60
    val m = minuteOfDay % 60
    val suffix = if (h24 < 12) "am" else "pm"
    val h = when {
        h24 == 0 -> 12
        h24 > 12 -> h24 - 12
        else -> h24
    }
    return String.format("%d:%02d%s", h, m, suffix)
}

private val DAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")
