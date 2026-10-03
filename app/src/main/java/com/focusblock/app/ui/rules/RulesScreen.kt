package com.focusblock.app.ui.rules

import androidx.compose.foundation.clickable
import com.focusblock.app.ui.components.SectionHeader
import com.focusblock.app.ui.components.FbCard
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.blocking.ImportedRuleStore
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.csv
import com.focusblock.app.policy.RecommendationEngine
import com.focusblock.app.policy.Strength
import com.focusblock.app.policy.TimeWindow
import com.focusblock.app.ui.components.AppHeader
import com.focusblock.app.ui.components.ChipKind
import com.focusblock.app.ui.components.CoverageStrip
import com.focusblock.app.ui.components.DividerRow
import com.focusblock.app.ui.components.EmptyState
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.FbSheet
import com.focusblock.app.ui.components.FbSwitch
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.ScreenTitle
import com.focusblock.app.ui.components.SectionGap
import com.focusblock.app.ui.components.SectionLabel
import com.focusblock.app.ui.components.SkeletonLine
import com.focusblock.app.ui.components.StateChip
import com.focusblock.app.ui.components.StripSegment
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.components.pluralRes
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType
import kotlinx.coroutines.launch

/** Opens the editor: kind, existing id (or -1), and optional pre-fill for templates and suggestions. */
data class EditorRequest(
    val kind: RuleKind,
    val id: Long = -1,
    val template: String = "",
    val start: Int = -1,
    val end: Int = -1,
    val days: String = "",
    val apps: String = "",
    val name: String = "",
)

@Composable
fun RulesScreen(state: RulesUi, vm: RulesViewModel, onSettings: () -> Unit, onEdit: (EditorRequest) -> Unit, onEssentials: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addOpen by rememberSaveable { mutableStateOf(false) }

    fun deleted(name: String, undo: com.focusblock.app.ui.rules.Deleted?) {
        if (undo == null) return
        scope.launch {
            val r = snackbar.showSnackbar(context.getString(R.string.rule_deleted, name), context.getString(R.string.action_undo), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.undo(undo)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AppHeader(onSettings)
            Spacer(Modifier.height(12.dp))
            ScreenTitle(stringResource(R.string.rules_title), subtitle = stringResource(R.string.rules_subtitle))
            Spacer(Modifier.height(20.dp))

            if (state.loading) {
                SkeletonLine(0.6f); SkeletonLine(0.95f, 20.dp); SkeletonLine(0.9f, 48.dp); SkeletonLine(0.9f, 48.dp)
            } else {

            // Coverage line + 24-hour strip (spec 4.6).
            val nowMinute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            FbCard {
            Text(
                if (state.coveredMinutes > 0) stringResource(R.string.coverage_line, Fmt.minutes(context, state.coveredMinutes)) else stringResource(R.string.coverage_none),
                style = FbType.heading,
            )
            Spacer(Modifier.height(12.dp))
            val stripDescription = state.segments.joinToString("; ") { s ->
                val name = if (s.type == com.focusblock.app.policy.ReasonType.BEDTIME) context.getString(R.string.name_bedtime) else s.name
                "$name ${context.getString(R.string.time_range, Fmt.minuteOfDay(context, s.startMinute), Fmt.minuteOfDay(context, s.endMinute % 1440))}"
            }.ifEmpty { context.getString(R.string.coverage_none) }
            CoverageStrip(
                segments = state.segments.map { StripSegment(it.startMinute, it.endMinute, if (it.type == com.focusblock.app.policy.ReasonType.BEDTIME) "bedtime" else "routine:${it.ruleId}") },
                nowMinute = nowMinute,
                hourLabel = { h -> Fmt.minuteOfDay(context, h * 60) },
                description = stringResource(R.string.coverage_cd, stripDescription),
                onSegment = { key ->
                    when {
                        key == "bedtime" -> onEdit(EditorRequest(RuleKind.BEDTIME))
                        key.startsWith("routine:") -> {
                            val id = key.removePrefix("routine:").toLong()
                            if (id >= RulesViewModel.IMPORTED_OFFSET) onEdit(EditorRequest(RuleKind.IMPORTED, id - RulesViewModel.IMPORTED_OFFSET))
                            else onEdit(EditorRequest(RuleKind.ROUTINE, id))
                        }
                    }
                },
            )
            }

            // Gap line, only when evidence supports it.
            (state.gap?.proposal as? RecommendationEngine.Proposal.AddRoutine)?.let { p ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gap_line, Fmt.minuteOfDay(context, p.startMinute), Fmt.minuteOfDay(context, p.endMinute)),
                    style = FbType.label.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter),
                )
                TextLink(stringResource(R.string.add_a_rule), {
                    onEdit(EditorRequest(RuleKind.ROUTINE, template = "gap", start = p.startMinute, end = p.endMinute,
                        days = TimeWindow.formatDays(p.days), apps = p.packages.joinToString(","), name = context.getString(R.string.sugg_routine_name)))
                }, modifier = Modifier.padding(start = Fb.gutter - 8.dp))
            }

            val nothing = state.schedules.isEmpty() && state.limits.isEmpty() && state.daily == null && state.bedtime == null &&
                state.cycle == null && state.imported.isEmpty()
            SectionGap()
            if (nothing) {
                // The Add a rule button below is the one action; no second button here.
                EmptyState(stringResource(R.string.rules_empty))
            }

            // Routines.
            if (state.schedules.isNotEmpty()) {
                SectionHeader(stringResource(R.string.section_routines))
                state.schedules.forEach { s ->
                    Spacer(Modifier.height(10.dp))
                    val window = TimeWindow.parseDays(s.daysOfWeek).takeIf { it.isNotEmpty() }?.let { TimeWindow(s.startTimeMinutes, s.endTimeMinutes, it) }
                    val broken = when {
                        csv(s.blockedPackages).isEmpty() -> context.getString(R.string.cause_no_apps)
                        !state.accessibilityOk -> context.getString(R.string.cause_accessibility_off)
                        else -> null
                    }
                    val chip = RuleText.windowState(context, s.isEnabled, window, state.now, broken)
                    val locked = vm.routineLocked(s)
                    RuleRow(
                        name = s.name,
                        summary = RuleText.routine(context, s, vm::label),
                        chip = chip,
                        extra = if (s.isStrictMode) stringResource(if (locked) R.string.rule_locked_strict else R.string.strength_strict) else null,
                        enabled = s.isEnabled,
                        toggleEnabled = !locked,
                        onToggle = { vm.setRoutineEnabled(s, it) },
                        onClick = { onEdit(EditorRequest(RuleKind.ROUTINE, s.id)) },
                        onDelete = if (locked) null else ({ deleted(s.name, vm.deleteRoutine(s)) }),
                    )
                }
                Spacer(Modifier.height(10.dp))
                SectionGap()
            }

            // Imported rules from the previous version.
            if (state.imported.isNotEmpty()) {
                SectionHeader(stringResource(R.string.section_imported))
                state.imported.forEach { r ->
                    Spacer(Modifier.height(10.dp))
                    ImportedRow(r, state, vm, onEdit)
                }
                Spacer(Modifier.height(10.dp))
                SectionGap()
            }

            // Limits.
            if (state.limits.isNotEmpty() || state.daily != null) {
                SectionHeader(stringResource(R.string.section_limits))
                state.limits.forEach { l ->
                    Spacer(Modifier.height(10.dp))
                    val packages = csv(l.packages)
                    val used = state.usageToday?.let { u -> packages.sumOf { u[it] ?: 0L } }
                    RuleRow(
                        name = l.name,
                        summary = RuleText.appLimit(context, l, vm::label),
                        chip = RuleText.limitState(context, l.isEnabled, used, l.minutesPerDay, if (!state.usageAccess) context.getString(R.string.cause_usage_off) else null),
                        progress = used?.takeIf { l.isEnabled }?.let { it.toFloat() / (l.minutesPerDay * 60_000L).coerceAtLeast(1L) },
                        enabled = l.isEnabled,
                        onToggle = { vm.setLimitEnabled(l, it) },
                        onClick = { onEdit(EditorRequest(RuleKind.APP_LIMIT, l.id)) },
                        onDelete = { deleted(l.name, vm.deleteLimit(l)) },
                    )
                }
                state.daily?.let { d ->
                    Spacer(Modifier.height(10.dp))
                    val counted = state.snapshot?.dailyLimit
                    val lockedUntil = vm.dailyLockedUntil(d)
                    RuleRow(
                        name = stringResource(R.string.type_daily_limit),
                        summary = RuleText.daily(context, d, vm::label),
                        chip = RuleText.limitState(context, d.isEnabled, counted?.usedMillisToday?.takeIf { state.usageAccess }, d.dailyLimitMinutes,
                            if (!state.usageAccess) context.getString(R.string.cause_usage_off) else null),
                        extra = lockedUntil?.let { stringResource(R.string.state_locked, Fmt.time(context, it)) },
                        progress = counted?.usedMillisToday?.takeIf { state.usageAccess && d.isEnabled }?.let { it.toFloat() / (d.dailyLimitMinutes * 60_000L).coerceAtLeast(1L) },
                        enabled = d.isEnabled,
                        toggleEnabled = !(d.isEnabled && lockedUntil != null),
                        onToggle = { vm.setDailyEnabled(d, it) },
                        onClick = { onEdit(EditorRequest(RuleKind.DAILY_LIMIT)) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                SectionGap()
            }

            // Bedtime.
            state.bedtime?.let { b ->
                SectionHeader(stringResource(R.string.section_bedtime))
                Spacer(Modifier.height(10.dp))
                val window = state.snapshot?.bedtime?.window
                val locked = vm.bedtimeLocked(b)
                RuleRow(
                    name = stringResource(R.string.name_bedtime),
                    summary = RuleText.bedtime(context, b),
                    chip = RuleText.windowState(context, b.isEnabled, window, state.now, if (!state.accessibilityOk) context.getString(R.string.cause_accessibility_off) else null),
                    extra = if (b.strength == Strength.STRICT.name) stringResource(if (locked) R.string.rule_locked_strict else R.string.strength_strict) else null,
                    enabled = b.isEnabled,
                    toggleEnabled = !locked,
                    onToggle = { vm.setBedtimeEnabled(b, it) },
                    onClick = { onEdit(EditorRequest(RuleKind.BEDTIME)) },
                )
                Spacer(Modifier.height(10.dp))
                SectionGap()
            }

            // Focus Cycles (separate until the merge decision).
            state.cycle?.let { c ->
                SectionHeader(stringResource(R.string.section_focus_cycles))
                Spacer(Modifier.height(10.dp))
                val packages = state.snapshot?.focusCycles?.firstOrNull()?.packages ?: csv(c.selectedPackages).toSet()
                val breakEnds = state.snapshot?.focusCycles?.firstOrNull()?.breakEndsAt
                val chip = when {
                    !c.isEnabled -> RuleText.State(ChipKind.OFF, stringResource(R.string.state_off))
                    breakEnds != null && breakEnds > state.now -> RuleText.State(ChipKind.ACTIVE, stringResource(R.string.state_on_break, Fmt.time(context, breakEnds)))
                    else -> RuleText.State(ChipKind.NEXT, stringResource(R.string.state_on))
                }
                RuleRow(
                    name = c.name,
                    summary = RuleText.cycle(context, c, Fmt.apps(context, packages, vm::label)),
                    chip = chip,
                    enabled = c.isEnabled,
                    onToggle = { vm.setCycleEnabled(c, it) },
                    onClick = { onEdit(EditorRequest(RuleKind.FOCUS_CYCLE, c.id)) },
                )
                Spacer(Modifier.height(10.dp))
                SectionGap()
            }

            // Essential apps.
            SectionHeader(stringResource(R.string.section_essentials))
            Spacer(Modifier.height(10.dp))
            FbCard(contentPadding = 0.dp) {
                DividerRow(
                    title = stringResource(R.string.section_essentials),
                    subtitle = pluralRes(R.plurals.essentials_count, state.essentialsCount),
                    onClick = onEssentials,
                )
            }
            Spacer(Modifier.height(10.dp))

            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = Fb.gutter)) {
                PrimaryButton(stringResource(R.string.add_a_rule), { addOpen = true }, leadingIcon = Icons.Outlined.Add)
            }
            Spacer(Modifier.height(32.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (addOpen) {
        AddRuleSheet(
            state = state,
            onDismiss = { addOpen = false },
            onPick = { req -> addOpen = false; onEdit(req) },
        )
    }

    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            containerColor = Fb.surface,
            text = { Text(stringResource(msg), style = FbType.body) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text(stringResource(R.string.action_close), color = Fb.accent) } },
        )
    }
}

@Composable
private fun ImportedRow(r: ImportedRuleStore.Rule, state: RulesUi, vm: RulesViewModel, onEdit: (EditorRequest) -> Unit) {
    val context = LocalContext.current
    val window = if (r.timed && !r.manual) TimeWindow.parseDays(r.days).takeIf { it.isNotEmpty() }?.let { TimeWindow(r.start, r.end, it) } else null
    val active = state.snapshot?.routines?.firstOrNull { it.id == RulesViewModel.IMPORTED_OFFSET + r.id }
    val chip = when {
        !r.enabled -> RuleText.State(ChipKind.OFF, stringResource(R.string.state_off))
        window != null -> RuleText.windowState(context, true, window, state.now, null)
        active?.conditionsMet == true -> RuleText.State(ChipKind.ACTIVE, stringResource(R.string.state_active))
        else -> RuleText.State(ChipKind.NEXT, stringResource(R.string.state_on))
    }
    val locked = state.importedLocked || (r.enabled && r.commitment.isNotBlank() && r.commitment != "OFF" && r.inWindow(state.now))
    RuleRow(
        name = r.name,
        summary = RuleText.imported(context, r, vm::label),
        chip = chip,
        extra = if (locked) stringResource(R.string.rule_locked_strict) else null,
        enabled = r.enabled,
        toggleEnabled = !locked,
        onToggle = { vm.toggleImported(r) },
        onClick = { onEdit(EditorRequest(RuleKind.IMPORTED, r.id)) },
    )
}

/** One rule: name · summary · state chip, switch, overflow menu with Delete (spec 4.6). */
@Composable
fun RuleRow(
    name: String,
    summary: String,
    chip: RuleText.State,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    extra: String? = null,
    toggleEnabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    /** Share of a limit used today (0–1+), drawn as a bar; null for rules without an allowance. */
    progress: Float? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Fb.gutter).clip(RoundedCornerShape(Fb.cardRadius)).background(Fb.surface)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 72.dp).padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = FbType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text(summary, style = FbType.caption.copy(fontSize = FbType.label.fontSize, lineHeight = FbType.label.lineHeight))
            Spacer(Modifier.height(6.dp))
            StateChip(chip.text, chip.kind)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                com.focusblock.app.ui.components.FbProgressBar(
                    progress, if (progress >= 1f) Fb.warning else Fb.accent, height = 6.dp,
                    brush = if (progress >= 1f) null else androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Fb.accent, Fb.accentAlt)),
                )
            }
            if (extra != null) {
                Spacer(Modifier.height(4.dp))
                Text(extra, style = FbType.caption)
            }
        }
        Spacer(Modifier.width(8.dp))
        FbSwitch(enabled, if (toggleEnabled) onToggle else null, enabled = toggleEnabled, contentDescription = LocalContext.current.getString(R.string.rule_toggle_cd, name))
        if (onDelete != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options), tint = Fb.textSecondary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; onClick() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** "Add a rule" → type picker (spec 4.6). Templates live inside creation, not on the main screen. */
@Composable
private fun AddRuleSheet(state: RulesUi, onDismiss: () -> Unit, onPick: (EditorRequest) -> Unit) {
    FbSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.add_rule_title), style = FbType.heading, modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(12.dp))
            FbDivider()
            DividerRow(title = stringResource(R.string.type_routine), subtitle = stringResource(R.string.type_routine_desc), onClick = { onPick(EditorRequest(RuleKind.ROUTINE)) })
            FbDivider()
            DividerRow(title = stringResource(R.string.type_app_limit), subtitle = stringResource(R.string.type_app_limit_desc), onClick = { onPick(EditorRequest(RuleKind.APP_LIMIT)) })
            FbDivider()
            DividerRow(
                title = stringResource(R.string.type_daily_limit),
                subtitle = stringResource(if (state.daily != null) R.string.daily_limit_exists else R.string.type_daily_limit_desc),
                onClick = { onPick(EditorRequest(RuleKind.DAILY_LIMIT)) },
            )
            FbDivider()
            DividerRow(
                title = stringResource(R.string.type_bedtime),
                subtitle = stringResource(if (state.bedtime != null) R.string.bedtime_exists else R.string.type_bedtime_desc),
                onClick = { onPick(EditorRequest(RuleKind.BEDTIME)) },
            )
            FbDivider()
            DividerRow(
                title = stringResource(R.string.type_focus_cycle),
                subtitle = stringResource(if (state.cycle != null) R.string.focus_cycle_exists else R.string.type_focus_cycle_desc),
                onClick = { onPick(EditorRequest(RuleKind.FOCUS_CYCLE, state.cycle?.id ?: -1)) },
            )
            FbDivider()
            SectionGap()
            SectionLabel(stringResource(R.string.templates_title))
            listOf(
                Triple(R.string.template_work, R.string.template_work_desc, "work"),
                Triple(R.string.template_evening, R.string.template_evening_desc, "evening"),
                Triple(R.string.template_morning, R.string.template_morning_desc, "morning"),
                Triple(R.string.template_detox, R.string.template_detox_desc, "detox"),
            ).forEach { (title, desc, key) ->
                FbDivider()
                DividerRow(title = stringResource(title), subtitle = stringResource(desc), onClick = { onPick(EditorRequest(RuleKind.ROUTINE, template = key)) })
            }
            FbDivider()
        }
    }
}
