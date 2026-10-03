package com.focusblock.app.ui.rules

import com.focusblock.app.ui.components.SecondaryButton
import com.focusblock.app.ui.components.SectionHeader
import com.focusblock.app.ui.components.FbCard
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.core.Fmt
import com.focusblock.app.ui.components.AppIconRow
import com.focusblock.app.ui.components.BackHeader
import com.focusblock.app.ui.components.DividerRow
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.FbSwitch
import com.focusblock.app.ui.components.LabeledField
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.SectionGap
import com.focusblock.app.ui.components.SectionLabel
import com.focusblock.app.ui.components.SegmentedControl
import com.focusblock.app.ui.components.SkeletonLine
import com.focusblock.app.ui.components.Stepper
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.picker.AppPickerSheet
import com.focusblock.app.ui.picker.PickerContext
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType

@Composable
fun RuleEditorScreen(state: EditorUi, vm: RuleEditorViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(state.saved) { if (state.saved) onClose() }
    val d = state.draft
    val kind = state.kind
    var picker by rememberSaveable { mutableStateOf(false) }
    val typeName = stringResource(
        when (kind) {
            RuleKind.ROUTINE, RuleKind.IMPORTED -> R.string.type_routine
            RuleKind.APP_LIMIT -> R.string.type_app_limit
            RuleKind.DAILY_LIMIT -> R.string.type_daily_limit
            RuleKind.BEDTIME -> R.string.type_bedtime
            RuleKind.FOCUS_CYCLE -> R.string.type_focus_cycle
        },
    )
    val title = if (state.isNew) stringResource(R.string.editor_new, typeName.lowercase()) else stringResource(R.string.editor_edit, d.name.ifBlank { typeName })
    val locked = state.lockedUntil != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackHeader(title, onClose)
        if (state.loading) { SkeletonLine(0.8f, 40.dp); SkeletonLine(0.6f, 40.dp) } else {
        if (locked) {
            Text(stringResource(R.string.error_locked, Fmt.time(context, state.lockedUntil!!)), style = FbType.body.copy(color = Fb.warning), modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(12.dp))
        }

        // Limits: what is happening today, how the rule works, and the last 7 days.
        val isLimit = kind == RuleKind.APP_LIMIT || kind == RuleKind.DAILY_LIMIT
        if (isLimit) {
            Spacer(Modifier.height(8.dp))
            val noApps = d.apps.isEmpty() && !(kind == RuleKind.DAILY_LIMIT && d.countsAll)
            LimitTodayCard(state.insight, d.minutes, state.savedMinutes, state.enabled, state.usageAccess, noApps)
            Spacer(Modifier.height(12.dp))
            LimitHowItWorksCard(kind, d.countsAll, d.apps.size, d.minutes)
            state.insight?.takeIf { it.usedToday != null && !noApps }?.let { insight ->
                Spacer(Modifier.height(12.dp))
                LimitTodayByAppCard(insight, vm::label)
                Spacer(Modifier.height(12.dp))
                LimitWeekCard(insight, d.minutes)
            }
            SectionHeader(stringResource(R.string.limit_settings))
            if (!state.isNew) {
                FbCard(contentPadding = 0.dp) {
                    DividerRow(
                        title = stringResource(R.string.limit_is_on),
                        subtitle = stringResource(if (state.enabled) R.string.limit_is_on_yes else R.string.limit_is_on_no),
                        trailing = { FbSwitch(state.enabled, vm::setEnabled, enabled = !locked, contentDescription = stringResource(R.string.limit_is_on)) },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Name.
        if (kind == RuleKind.ROUTINE || kind == RuleKind.APP_LIMIT || kind == RuleKind.FOCUS_CYCLE || kind == RuleKind.IMPORTED) {
            Spacer(Modifier.height(8.dp))
            LabeledField(stringResource(R.string.field_name), d.name, { v -> vm.edit { it.copy(name = v) } }, typeName, maxLength = 40, enabled = !locked)
            SectionGap()
        }

        // Time.
        if (kind == RuleKind.ROUTINE || kind == RuleKind.BEDTIME || (kind == RuleKind.IMPORTED && state.importedTimed)) {
            SectionLabel(stringResource(R.string.field_time))
            FbDivider()
            if (kind == RuleKind.ROUTINE) {
                DividerRow(
                    title = stringResource(R.string.field_all_day),
                    trailing = { FbSwitch(d.allDay, { v -> vm.edit { it.copy(allDay = v) } }, enabled = !locked) },
                )
                FbDivider()
            }
            if (!d.allDay) {
                TimeRow(stringResource(R.string.field_start), d.start, !locked) { m -> vm.edit { it.copy(start = m) } }
                FbDivider()
                TimeRow(stringResource(R.string.field_end), d.end, !locked) { m -> vm.edit { it.copy(end = m) } }
                if (d.end < d.start) Text(stringResource(R.string.overnight_note), style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter))
                FbDivider()
            }
            SectionGap()
            SectionLabel(stringResource(R.string.field_days))
            DaysSelector(d.days, !locked) { day -> vm.edit { it.copy(days = if (day in it.days) it.days - day else it.days + day) } }
            SectionGap()
        }

        // Daily limit scope.
        if (kind == RuleKind.DAILY_LIMIT) {
            SectionLabel(stringResource(R.string.field_counts))
            SegmentedControl(
                listOf(true to stringResource(R.string.counts_all), false to stringResource(R.string.counts_chosen)),
                d.countsAll, { v -> vm.edit { it.copy(countsAll = v) } }, enabled = !locked,
            )
            SectionGap()
        }

        // Apps.
        val needsApps = kind != RuleKind.BEDTIME && !(kind == RuleKind.DAILY_LIMIT && d.countsAll)
        if (needsApps) {
            SectionLabel(stringResource(R.string.field_apps) + " (" + d.apps.size + ")") {
                TextLink(stringResource(R.string.action_edit), { picker = true }, enabled = !locked)
            }
            if (d.apps.isEmpty()) DividerRow(title = stringResource(R.string.editor_choose_apps), onClick = if (locked) null else ({ picker = true }))
            else AppIconRow(d.apps, vm::label)
            SectionGap()
        }

        // Allowance.
        if (kind == RuleKind.APP_LIMIT || kind == RuleKind.DAILY_LIMIT) {
            val step = if (kind == RuleKind.APP_LIMIT) 5 else 15
            Stepper(stringResource(R.string.field_minutes_per_day), Fmt.minutes(context, d.minutes),
                { vm.edit { it.copy(minutes = (it.minutes - step).coerceAtLeast(step)) } },
                { vm.edit { it.copy(minutes = (it.minutes + step).coerceAtMost(720)) } }, enabled = !locked)
            if (!state.usageAccess) Text(stringResource(R.string.limit_needs_usage), style = FbType.caption.copy(color = Fb.warning), modifier = Modifier.padding(horizontal = Fb.gutter))
            SectionGap()
        }

        // Focus Cycle lengths.
        if (kind == RuleKind.FOCUS_CYCLE) {
            Stepper(stringResource(R.string.field_usage_window), Fmt.minutes(context, d.windowMinutes),
                { vm.edit { it.copy(windowMinutes = (it.windowMinutes - 5).coerceAtLeast(5)) } }, { vm.edit { it.copy(windowMinutes = (it.windowMinutes + 5).coerceAtMost(180)) } })
            Stepper(stringResource(R.string.field_break_length), Fmt.minutes(context, d.breakMinutes),
                { vm.edit { it.copy(breakMinutes = (it.breakMinutes - 5).coerceAtLeast(5)) } }, { vm.edit { it.copy(breakMinutes = (it.breakMinutes + 5).coerceAtMost(240)) } })
            SectionGap()
        }

        // Strength.
        if (kind == RuleKind.ROUTINE || kind == RuleKind.BEDTIME) {
            SectionLabel(stringResource(R.string.field_strength))
            SegmentedControl(
                listOf(false to stringResource(R.string.strength_normal), true to stringResource(R.string.strength_strict)),
                d.strict, { v -> vm.edit { it.copy(strict = v) } }, enabled = !locked,
            )
            if (d.strict) Text(stringResource(R.string.editor_strict_note), style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter, vertical = 8.dp))
            SectionGap()
        }

        // Notes and live summary.
        val note = when (kind) {
            RuleKind.BEDTIME -> R.string.bedtime_editor_note
            RuleKind.DAILY_LIMIT -> R.string.daily_limit_editor_note
            RuleKind.APP_LIMIT -> R.string.app_limit_editor_note
            RuleKind.FOCUS_CYCLE -> R.string.focus_cycle_editor_note
            else -> null
        }
        note?.let { Text(stringResource(it), style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter)); SectionGap() }
        SectionLabel(stringResource(R.string.editor_summary))
        Text(summaryText(state, vm), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(it), style = FbType.body.copy(color = Fb.warning), modifier = Modifier.padding(horizontal = Fb.gutter))
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = Fb.gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.action_save), vm::save, enabled = !locked && !state.saving)
            if (!state.isNew && kind != RuleKind.IMPORTED && !locked) {
                when (kind) {
                    // The switch above turns the daily limit off; there is only one, so nothing to delete.
                    RuleKind.DAILY_LIMIT -> Unit
                    RuleKind.APP_LIMIT -> SecondaryButton(stringResource(R.string.limit_delete), vm::delete)
                    RuleKind.ROUTINE -> TextLink(stringResource(R.string.action_delete), vm::delete, accent = false)
                    else -> TextLink(stringResource(R.string.state_off), vm::delete, accent = false)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        }
    }

    if (picker) {
        val ctx = when (kind) {
            RuleKind.APP_LIMIT -> PickerContext.APP_LIMIT
            RuleKind.DAILY_LIMIT -> PickerContext.DAILY_LIMIT
            else -> PickerContext.RULE
        }
        AppPickerSheet(ctx, d.apps, onDismiss = { picker = false }, onDone = { apps -> vm.edit { it.copy(apps = apps) }; picker = false }, ruleName = d.name.ifBlank { typeName })
    }
}

@Composable
private fun summaryText(state: EditorUi, vm: RuleEditorViewModel): String {
    val context = LocalContext.current
    val d = state.draft
    val apps = Fmt.apps(context, d.apps, vm::label)
    return when (state.kind) {
        RuleKind.ROUTINE, RuleKind.IMPORTED -> RuleText.window(context, apps, if (d.allDay) 0 else d.start, if (d.allDay) 0 else d.end, d.days)
        RuleKind.APP_LIMIT -> context.getString(R.string.limit_summary_app, apps, Fmt.minutes(context, d.minutes))
        RuleKind.DAILY_LIMIT -> if (d.countsAll) context.getString(R.string.limit_summary_daily_all, Fmt.minutes(context, d.minutes))
            else context.getString(R.string.limit_summary_daily_list, apps, Fmt.minutes(context, d.minutes))
        RuleKind.BEDTIME -> context.getString(R.string.bedtime_summary, Fmt.minuteOfDay(context, d.start), Fmt.minuteOfDay(context, d.end), Fmt.days(context, d.days))
        RuleKind.FOCUS_CYCLE -> context.getString(R.string.focus_cycle_summary, Fmt.minutes(context, d.windowMinutes), apps, Fmt.minutes(context, d.breakMinutes))
    } + if (d.strict && (state.kind == RuleKind.ROUTINE || state.kind == RuleKind.BEDTIME)) " · " + context.getString(R.string.strength_strict) else ""
}

@Composable
private fun TimeRow(label: String, minute: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    DividerRow(
        title = label,
        value = Fmt.minuteOfDay(context, minute),
        onClick = if (!enabled) null else ({
            TimePickerDialog(context, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, m -> onPick(h * 60 + m) },
                minute / 60, minute % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
        }),
    )
}

@Composable
private fun DaysSelector(days: Set<Int>, enabled: Boolean, onToggle: (Int) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..7).forEach { day ->
            val on = day in days
            val full = Fmt.dayFull(context, day)
            Box(
                Modifier.weight(1f).heightIn(min = Fb.touch).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Fb.buttonPrimaryBg else Color.Transparent)
                    .border(1.dp, if (on) Fb.buttonPrimaryBg else Fb.textPrimary.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled, role = Role.Checkbox) { onToggle(day) }
                    .semantics { contentDescription = full; stateDescription = if (on) "On" else "Off" },
                contentAlignment = Alignment.Center,
            ) {
                Text(Fmt.dayShort(context, day).take(2), style = FbType.label.copy(color = if (on) Fb.buttonPrimaryText else Fb.textPrimary))
            }
        }
    }
}
