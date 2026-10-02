package com.focusblock.app.ui.block

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.HealthState
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.policy.SessionOutcome
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import com.focusblock.app.ui.components.ActiveBlockVisual
import com.focusblock.app.ui.components.AppHeader
import com.focusblock.app.ui.components.AppIconRow
import com.focusblock.app.ui.components.ChoiceChips
import com.focusblock.app.ui.components.DividerRow
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.FbSheet
import com.focusblock.app.ui.components.FbSwitch
import com.focusblock.app.ui.components.LabeledField
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.ProblemBanner
import com.focusblock.app.ui.components.ScreenTitle
import com.focusblock.app.ui.components.SecondaryButton
import com.focusblock.app.ui.components.SectionGap
import com.focusblock.app.ui.components.SectionLabel
import com.focusblock.app.ui.components.SegmentedControl
import com.focusblock.app.ui.components.SkeletonLine
import com.focusblock.app.ui.components.StatusKind
import com.focusblock.app.ui.components.StatusLine
import com.focusblock.app.ui.components.Stepper
import com.focusblock.app.ui.components.StrengthLabel
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.components.VisualState
import com.focusblock.app.ui.components.pluralRes
import com.focusblock.app.ui.picker.AppPickerSheet
import com.focusblock.app.ui.picker.PickerContext
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType

@Composable
fun BlockScreen(
    state: BlockUi,
    vm: BlockViewModel,
    onSettings: () -> Unit,
    onEssentials: () -> Unit,
    onHealth: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) }
    }
    val input = state.input
    val running = input != null && !SessionClock.state(input, now).ended

    // Announce block state changes for TalkBack (spec 11.5).
    val view = LocalView.current
    var wasRunning by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(running) {
        val previous = wasRunning
        wasRunning = running
        if (previous != null && previous != running) {
            val text = if (running) {
                input?.plannedEndAt?.let { context.getString(R.string.announce_started, Fmt.time(context, it)) } ?: context.getString(R.string.announce_started_open)
            } else context.getString(R.string.announce_ended)
            view.announceForAccessibility(text)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader(onSettings)
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> LoadingSkeleton()
            running -> ActiveBlock(state, vm, now, onEssentials)
            else -> IdleBlock(state, vm, onEssentials, onHealth)
        }
        Spacer(Modifier.height(32.dp))
    }

    state.awaitingOutcome?.let { ended ->
        if (!running) EndSheet(ended.intention, onOutcome = vm::outcome)
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
private fun LoadingSkeleton() {
    SkeletonLine(0.7f, 32.dp)
    SkeletonLine(0.5f)
    Spacer(Modifier.height(24.dp))
    SkeletonLine(0.9f, 48.dp)
    SkeletonLine(0.9f, 48.dp)
    SkeletonLine(0.6f)
}

// ---- Idle -----------------------------------------------------------------------------------

@Composable
private fun IdleBlock(state: BlockUi, vm: BlockViewModel, onEssentials: () -> Unit, onHealth: () -> Unit) {
    val context = LocalContext.current
    val d = state.draft
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }

    ScreenTitle(stringResource(R.string.block_title_idle))
    Spacer(Modifier.height(8.dp))
    BlockStatus(state, onHealth)

    // Repeat last block: whole row tappable, play icon at the right edge.
    state.last?.let { last ->
        Spacer(Modifier.height(12.dp))
        FbDivider()
        val length = when (last.type) {
            SessionType.TIMED -> Fmt.minutes(context, last.minutes)
            SessionType.INTERVALS -> stringResource(R.string.session_intervals)
            SessionType.INDEFINITE -> stringResource(R.string.session_until_stop)
        }
        val strength = stringResource(if (last.strength == Strength.STRICT) R.string.strength_strict else R.string.strength_normal)
        DividerRow(
            title = stringResource(R.string.repeat_last_title),
            subtitle = stringResource(R.string.summary_dot3, length, pluralRes(R.plurals.apps_count, last.packages.size), strength),
            leading = { Icon(Icons.Outlined.Replay, null, tint = Fb.textSecondary, modifier = Modifier.size(20.dp)) },
            trailing = { Icon(Icons.Outlined.PlayArrow, null, tint = Fb.accent, modifier = Modifier.size(24.dp)) },
            onClick = if (state.missing == null && !state.busy) vm::repeatLast else null,
            modifier = Modifier.semantics { contentDescription = context.getString(R.string.repeat_last_start) },
        )
        FbDivider()
    }

    SectionGap()
    LabeledField(
        label = stringResource(R.string.intention_label),
        value = d.intention,
        onValueChange = vm::setIntention,
        placeholder = stringResource(R.string.intention_placeholder),
        maxLength = 80,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )

    SectionGap()
    SectionLabel(stringResource(R.string.apps_to_block, d.packages.size)) {
        TextLink(stringResource(R.string.action_edit), { showPicker = true })
    }
    if (d.packages.isEmpty()) {
        DividerRow(title = stringResource(R.string.apps_none_chosen), onClick = { showPicker = true })
    } else {
        Spacer(Modifier.height(4.dp))
        AppIconRow(d.packages, vm::label, onMore = { showPicker = true }, modifier = Modifier.clickable { showPicker = true })
    }

    SectionGap()
    SegmentedControl(
        options = listOf(
            SessionType.TIMED to stringResource(R.string.session_timed),
            SessionType.INTERVALS to stringResource(R.string.session_intervals),
            SessionType.INDEFINITE to stringResource(R.string.session_until_stop),
        ),
        selected = d.type,
        onSelect = vm::setType,
    )
    Spacer(Modifier.height(16.dp))
    when (d.type) {
        SessionType.TIMED -> {
            val presets = BlockViewModel.PRESETS
            val index = presets.indexOf(d.minutes).let { if (it < 0) 3 else it }
            val custom = if (index == 3) Fmt.minutes(context, d.minutes) else stringResource(R.string.duration_custom)
            ChoiceChips(
                options = presets.map { stringResource(R.string.duration_min, it) } + custom,
                selectedIndex = index,
                onSelect = { i -> if (i < 3) vm.setMinutes(presets[i]) else showCustom = true },
            )
        }
        SessionType.INTERVALS -> {
            Stepper(stringResource(R.string.intervals_focus), Fmt.minutes(context, d.focus), { vm.setFocus(d.focus - 5) }, { vm.setFocus(d.focus + 5) })
            Stepper(stringResource(R.string.intervals_break), Fmt.minutes(context, d.rest), { vm.setRest(d.rest - 1) }, { vm.setRest(d.rest + 1) })
            Stepper(stringResource(R.string.intervals_rounds), d.rounds.toString(), { vm.setRounds(d.rounds - 1) }, { vm.setRounds(d.rounds + 1) })
            val total = SessionClock.plannedLengthMinutes(SessionType.INTERVALS, 0, d.focus, d.rest, d.rounds) ?: 0
            Text(
                stringResource(R.string.intervals_total, d.rounds, Fmt.time(context, System.currentTimeMillis() + total * SessionClock.MINUTE)),
                style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter),
            )
        }
        SessionType.INDEFINITE -> Unit
    }

    SectionGap()
    FbDivider()
    StrictToggle(d, vm)
    FbDivider()

    Spacer(Modifier.height(20.dp))
    val startLabel = when (d.type) {
        SessionType.TIMED -> stringResource(R.string.start_timed, d.minutes)
        SessionType.INTERVALS -> stringResource(R.string.start_intervals)
        SessionType.INDEFINITE -> stringResource(R.string.start_block)
    }
    val disabledReason = when {
        state.missing != null -> stringResource(R.string.status_needs, stringResource(state.missing.label))
        d.packages.isEmpty() -> stringResource(R.string.start_no_apps)
        else -> null
    }
    Column(Modifier.padding(horizontal = Fb.gutter)) {
        PrimaryButton(startLabel, vm::start, enabled = disabledReason == null && !state.busy, trailingIcon = Icons.Outlined.ArrowForward)
        if (disabledReason != null) {
            Spacer(Modifier.height(6.dp))
            Text(disabledReason, style = FbType.caption, modifier = Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().heightIn(min = Fb.touch).clickable(role = Role.Button, onClick = onEssentials).padding(horizontal = Fb.gutter),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Call, null, tint = Fb.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.footnote_essentials), style = FbType.caption)
    }

    state.nextRule?.let { next ->
        Spacer(Modifier.height(8.dp))
        FbDivider()
        val name = next.name.ifBlank { stringResource(R.string.name_bedtime) }
        DividerRow(title = nextRuleText(name, next.at))
    }

    if (showPicker) {
        AppPickerSheet(
            context = PickerContext.BLOCK,
            initial = d.packages,
            onDismiss = { showPicker = false },
            onDone = { vm.setPackages(it); showPicker = false },
        )
    }
    if (showCustom) {
        CustomMinutesDialog(d.minutes, onDismiss = { showCustom = false }, onDone = { vm.setMinutes(it); showCustom = false })
    }
}

@Composable
fun nextRuleText(name: String, at: Long): String {
    val context = LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val date = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return if (date == today) stringResource(R.string.next_rule, name, Fmt.time(context, at))
    else stringResource(R.string.next_rule_day, name, Fmt.dayShort(context, date.dayOfWeek.value), Fmt.time(context, at))
}

@Composable
private fun BlockStatus(state: BlockUi, onHealth: () -> Unit) {
    val context = LocalContext.current
    when {
        state.accessibility == HealthState.NOT_RUNNING ->
            StatusLine(stringResource(R.string.status_not_running), StatusKind.WARN, actionLabel = stringResource(R.string.health_repair), onAction = onHealth)
        state.missing != null ->
            StatusLine(stringResource(R.string.status_needs, stringResource(state.missing.label)), StatusKind.WARN, actionLabel = stringResource(R.string.action_fix),
                onAction = { PermissionHealth.open(context, state.missing) })
        state.activeRule != null -> {
            val r = state.activeRule
            val name = if (r.name.isBlank()) stringResource(R.string.name_bedtime) else r.name
            val text = r.until?.let { stringResource(R.string.status_rule_blocking, name, Fmt.time(context, it)) } ?: stringResource(R.string.status_rule_blocking_open, name)
            StatusLine(text, StatusKind.INFO)
        }
        else -> StatusLine(stringResource(R.string.status_ready), StatusKind.OK)
    }
}

@Composable
private fun StrictToggle(d: Draft, vm: BlockViewModel) {
    val allowed = d.type != SessionType.INDEFINITE
    Row(
        Modifier.fillMaxWidth().clickable(enabled = allowed, role = Role.Switch) { vm.setStrict(!d.strict) }
            .heightIn(min = 72.dp).padding(horizontal = Fb.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, null, tint = if (d.strict) Fb.accent else Fb.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.strength_strict), style = FbType.body)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(if (allowed) R.string.strict_lock_consequence else R.string.strict_lock_needs_end),
                style = FbType.caption.copy(fontSize = FbType.label.fontSize, lineHeight = FbType.label.lineHeight),
            )
        }
        Spacer(Modifier.width(12.dp))
        FbSwitch(d.strict, { vm.setStrict(it) }, enabled = allowed, contentDescription = stringResource(R.string.strength_strict))
    }
}

@Composable
private fun CustomMinutesDialog(current: Int, onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1..720
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Fb.surface,
        title = { Text(stringResource(R.string.custom_minutes_title), style = FbType.heading) },
        text = {
            LabeledField(
                label = stringResource(R.string.custom_minutes_label), value = text, onValueChange = { t -> text = t.filter(Char::isDigit).take(3) },
                placeholder = "45", horizontalPadding = 0.dp,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { if (valid) onDone(value!!) }, enabled = valid) { Text(stringResource(R.string.action_done), color = if (valid) Fb.accent else Fb.disabled) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Fb.textSecondary) } },
    )
}

// ---- Active ---------------------------------------------------------------------------------

@Composable
private fun ActiveBlock(state: BlockUi, vm: BlockViewModel, now: Long, onEssentials: () -> Unit) {
    val context = LocalContext.current
    val input = state.input ?: return
    val st = SessionClock.state(input, now)
    var confirmEnd by rememberSaveable { mutableStateOf(false) }

    val title = when {
        input.type == SessionType.INTERVALS && st.phase == SessionClock.Phase.BREAK ->
            stringResource(R.string.active_title_break, Fmt.time(context, st.phaseEndsAt ?: now))
        input.plannedEndAt != null -> stringResource(R.string.active_title_until, Fmt.time(context, input.plannedEndAt))
        else -> stringResource(R.string.active_title_open)
    }
    ScreenTitle(title)
    // Degraded protection mid-block is reported plainly (spec 4.11).
    if (state.accessibility != HealthState.OK) {
        Spacer(Modifier.height(12.dp))
        val cause = stringResource(R.string.cause_accessibility_off)
        ProblemBanner(stringResource(R.string.degraded_banner, cause), stringResource(R.string.action_fix),
            { PermissionHealth.open(context, com.focusblock.app.core.Requirement.ACCESSIBILITY) })
    }
    input.intention?.let {
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.intention_quoted, it), style = FbType.body.copy(color = Fb.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Fb.gutter))
    }
    Spacer(Modifier.height(20.dp))

    // Signature visual: the one elevated element on this screen.
    val remaining = input.plannedEndAt?.let { (it - now).coerceAtLeast(0) }
    val total = input.plannedEndAt?.let { it - input.startedAt }
    val progress = if (remaining != null && total != null && total > 0) 1f - remaining.toFloat() / total else null
    val visualState = when {
        input.type == SessionType.INTERVALS && st.phase == SessionClock.Phase.BREAK -> VisualState.BREAK
        remaining != null && remaining <= 5 * SessionClock.MINUTE -> VisualState.ENDING_SOON
        now - input.startedAt < 60_000 -> VisualState.STARTING
        else -> VisualState.ACTIVE
    }
    val headline = when {
        input.type == SessionType.INTERVALS && st.phase == SessionClock.Phase.BREAK -> stringResource(R.string.phase_break, Fmt.minutes(context, (((st.phaseEndsAt ?: now) - now + 59_999) / 60_000).toInt()))
        remaining != null -> Fmt.left(context, remaining)
        else -> stringResource(R.string.session_until_stop)
    }
    val caption = when {
        input.type == SessionType.INTERVALS && st.phase != SessionClock.Phase.BREAK -> stringResource(R.string.phase_focus, st.round, st.totalRounds)
        input.type == SessionType.INTERVALS -> stringResource(R.string.phase_focus, (st.round + 1).coerceAtMost(st.totalRounds), st.totalRounds)
        else -> null
    }
    ActiveBlockVisual(visualState, progress, headline, caption)

    Spacer(Modifier.height(16.dp))
    StrengthLabel(input.strength, Modifier.padding(horizontal = Fb.gutter))

    SectionGap()
    SectionLabel(pluralRes(R.plurals.apps_blocked_count, input.packages.size)) {
        if (input.strength == Strength.STRICT) Text(stringResource(R.string.selection_locked), style = FbType.caption)
    }
    Spacer(Modifier.height(4.dp))
    AppIconRow(input.packages.toList(), vm::label)

    SectionGap()
    FbDivider()
    DividerRow(title = stringResource(R.string.row_blocked_openings), value = state.blockedOpenings.toString())
    FbDivider()
    DividerRow(title = stringResource(R.string.row_essential_apps), value = stringResource(R.string.row_available), titleMaxLines = 1, onClick = onEssentials)
    if (state.alsoBlockedBy.isNotEmpty()) {
        FbDivider()
        val text = state.alsoBlockedBy.joinToString(", ") { entry ->
            val (name, start) = entry.split('|').let { it[0] to it[1].toLong() }
            val label = name.ifBlank { context.getString(R.string.name_bedtime) }
            if (start <= now) label else context.getString(R.string.status_rule_starts, label, Fmt.time(context, start))
        }
        DividerRow(title = stringResource(R.string.row_also_blocked_by), subtitle = text)
    }
    FbDivider()

    Spacer(Modifier.height(24.dp))
    Column(Modifier.padding(horizontal = Fb.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (input.strength == Strength.NORMAL) {
            PrimaryButton(stringResource(R.string.action_end_block), { confirmEnd = true })
        }
        if (input.plannedEndAt != null) {
            SecondaryButton(stringResource(R.string.action_add_15), vm::extend)
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            containerColor = Fb.surface,
            title = { Text(stringResource(R.string.end_confirm_title), style = FbType.heading) },
            text = { Text(stringResource(R.string.end_confirm_body), style = FbType.body) },
            confirmButton = { TextButton(onClick = { confirmEnd = false; vm.end() }) { Text(stringResource(R.string.action_end_block), color = Fb.textPrimary) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.action_cancel), color = Fb.accent) } },
        )
    }
}

/** "Did you finish?" (spec 4.4). Dismissing records UNANSWERED. */
@Composable
private fun EndSheet(intention: String?, onOutcome: (SessionOutcome) -> Unit) {
    FbSheet(onDismiss = { onOutcome(SessionOutcome.UNANSWERED) }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(stringResource(R.string.end_sheet_title), style = FbType.title, modifier = Modifier.padding(horizontal = Fb.gutter))
            if (intention != null) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.intention_quoted, intention), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = Fb.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.end_sheet_finished), { onOutcome(SessionOutcome.FINISHED) })
                SecondaryButton(stringResource(R.string.end_sheet_not_yet), { onOutcome(SessionOutcome.NOT_FINISHED) })
                SecondaryButton(stringResource(R.string.end_sheet_add_15), { onOutcome(SessionOutcome.EXTENDED) })
            }
        }
    }
}
