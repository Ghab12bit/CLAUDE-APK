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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.CommitmentLevel
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
                containerColor = Primary
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
                Column {
                    Text("Routines", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Blocking that happens without you deciding",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
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

    state.editing?.let { draft ->
        RuleEditorSheet(
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
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
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
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (row.isActiveNow)
                    Modifier.border(1.dp, AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                else Modifier
            )
            .clickable(onClick = onEdit)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(row.summary, color = TextSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = row.rule.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = Primary)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (row.isActiveNow) "Active now · ${row.appCount} apps"
                    else "${row.appCount} apps",
                    color = if (row.isActiveNow) AccentGreen else TextTertiary,
                    fontSize = 12.sp
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete routine",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    draft: BlockRule,
    apps: List<PickableApp>,
    appsLoading: Boolean,
    onChange: ((BlockRule) -> BlockRule) -> Unit,
    onToggleApp: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BackgroundDarkTertiary) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(draft.name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

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

            FieldLabel("Apps to block (${draft.packageList().size})")
            if (appsLoading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, modifier = Modifier.size(22.dp))
                }
            } else {
                Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                    apps.forEach { app -> AppRow(app) { onToggleApp(app.packageName) } }
                }
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("How hard to stop")
            CommitmentPicker(draft.commitment) { level -> onChange { it.copy(commitment = level) } }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Save routine", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                    .background(if (on) Primary.copy(alpha = 0.25f) else SurfaceDark)
                    .clickable {
                        val next = selected.toMutableSet()
                        if (!next.add(day)) next.remove(day)
                        onChange(next.sorted().joinToString(","))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Primary else TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: PickableApp, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = app.selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Primary)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, fontSize = 14.sp)
            if (app.essential) {
                // Named rather than hidden: blocking these is the user's call,
                // but it should be a deliberate one. Keyword-matching messaging
                // apps as distractions is what made the old Strict Mode cut off
                // the user's client conversations.
                Text(
                    "You may need this for calls or clients",
                    color = AccentOrange,
                    fontSize = 11.sp
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
                    .background(if (on) Primary.copy(alpha = 0.12f) else SurfaceDark)
                    .then(
                        if (on) Modifier.border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        else Modifier
                    )
                    .clickable { onChange(level) }
                    .padding(14.dp)
            ) {
                Text(
                    title,
                    color = if (on) Primary else TextPrimary,
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
