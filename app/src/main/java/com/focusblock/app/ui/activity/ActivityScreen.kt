package com.focusblock.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.Requirement
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.OverrideKind
import com.focusblock.app.policy.RecommendationEngine
import com.focusblock.app.ui.components.AppHeader
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.DividerRow
import com.focusblock.app.ui.components.EmptyState
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.HourBarChart
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.ProblemBanner
import com.focusblock.app.ui.components.ScreenTitle
import com.focusblock.app.ui.components.SecondaryButton
import com.focusblock.app.ui.components.SectionGap
import com.focusblock.app.ui.components.SectionLabel
import com.focusblock.app.ui.components.SegmentedControl
import com.focusblock.app.ui.components.SkeletonLine
import com.focusblock.app.ui.components.pluralRes
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType

@Composable
fun ActivityScreen(state: ActivityUi, vm: ActivityViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader(onSettings)
        Spacer(Modifier.height(12.dp))
        ScreenTitle(stringResource(R.string.activity_title))
        Spacer(Modifier.height(12.dp))
        SegmentedControl(
            listOf(Period.TODAY to stringResource(R.string.period_today), Period.WEEK to stringResource(R.string.period_week)),
            state.period, vm::setPeriod,
        )
        Spacer(Modifier.height(20.dp))
        if (state.loading) {
            SkeletonLine(0.9f, 24.dp); SkeletonLine(0.7f); SkeletonLine(0.95f, 140.dp); SkeletonLine(0.8f, 40.dp)
            return@Column
        }

        // Lead sentence from data, or an honest explanation (never zeros as if real).
        if (!state.usageAccess) {
            Text(stringResource(R.string.usage_missing), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(8.dp))
            ProblemBanner(stringResource(R.string.status_needs, stringResource(R.string.perm_usage)), stringResource(R.string.action_fix),
                { PermissionHealth.open(context, Requirement.USAGE) })
        } else if (!state.hasAnyData) {
            EmptyState(stringResource(R.string.activity_empty))
        } else {
            val screen = state.screenTime ?: 0L
            val lead = when {
                state.period == Period.WEEK -> stringResource(R.string.screen_time_week, Fmt.duration(context, screen), Fmt.duration(context, screen / 7))
                state.baseline == null -> stringResource(R.string.screen_time_no_baseline, Fmt.duration(context, screen))
                kotlin.math.abs(screen - state.baseline) < 5 * 60_000 -> stringResource(R.string.screen_time_same, Fmt.duration(context, screen))
                screen < state.baseline -> stringResource(R.string.screen_time_below, Fmt.duration(context, screen), Fmt.duration(context, state.baseline - screen))
                else -> stringResource(R.string.screen_time_above, Fmt.duration(context, screen), Fmt.duration(context, screen - state.baseline))
            }
            Text(lead, style = FbType.heading, modifier = Modifier.padding(horizontal = Fb.gutter))
        }

        // Blocks.
        SectionGap()
        SectionLabel(stringResource(R.string.section_blocks))
        FbDivider()
        OutcomeRow(R.string.outcome_finished, state.outcomes.finished)
        OutcomeRow(R.string.outcome_not_finished, state.outcomes.notFinished)
        OutcomeRow(R.string.outcome_extended, state.outcomes.extended)
        OutcomeRow(R.string.outcome_unanswered, state.outcomes.unanswered)
        OutcomeRow(R.string.outcome_ended_early, state.outcomes.endedEarly)
        FbDivider()

        // Blocked attempts by hour.
        SectionGap()
        SectionLabel(stringResource(R.string.section_attempts_by_hour)) {
            Text(pluralRes(R.plurals.attempts_count, state.totalAttempts), style = FbType.caption)
        }
        if (state.totalAttempts == 0) {
            Text(stringResource(R.string.chart_empty), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        } else {
            val description = state.attemptsByHour.withIndex().filter { it.value > 0 }
                .joinToString(", ") { context.getString(R.string.chart_hour_count, Fmt.minuteOfDay(context, it.index * 60), it.value) }
            HourBarChart(state.attemptsByHour, { h -> Fmt.minuteOfDay(context, h * 60) }, stringResource(R.string.chart_cd, description))
        }

        // Most-used apps.
        if (state.apps.isNotEmpty()) {
            SectionGap()
            SectionLabel(stringResource(R.string.section_most_used))
            state.apps.forEach { a ->
                FbDivider()
                DividerRow(
                    title = a.label,
                    subtitle = pluralRes(R.plurals.attempts_short, a.attempts),
                    value = a.millis?.let { Fmt.duration(context, it) } ?: stringResource(R.string.data_unavailable),
                    leading = { AppIcon(a.pkg, null, 32.dp) },
                )
            }
            FbDivider()
        }

        // Emergency and Open anyway unlocks.
        SectionGap()
        SectionLabel(stringResource(R.string.section_unlocks)) { Text(state.unlocks.size.toString(), style = FbType.caption) }
        if (state.unlocks.isEmpty()) {
            Text(stringResource(R.string.unlocks_none), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        } else {
            state.unlocks.take(10).forEach { u ->
                FbDivider()
                val kind = when {
                    u.status == UnlockEventEntity.PENDING -> stringResource(R.string.unlock_pending)
                    u.kind == OverrideKind.EMERGENCY.name -> stringResource(R.string.unlock_emergency)
                    else -> stringResource(R.string.unlock_open_anyway)
                }
                DividerRow(
                    title = u.label,
                    subtitle = listOfNotNull(kind, u.reason).joinToString(" · "),
                    value = Fmt.time(context, u.time),
                    leading = { AppIcon(u.pkg, null, 32.dp) },
                )
            }
            FbDivider()
        }

        // Rules working / bypassed.
        SectionGap()
        SectionLabel(stringResource(R.string.section_rules_working))
        if (state.rules.isEmpty()) {
            Text(stringResource(R.string.rules_none_active), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        } else {
            state.rules.forEach { r ->
                FbDivider()
                DividerRow(
                    title = r.name,
                    subtitle = if (r.oftenBypassed) stringResource(R.string.rule_often_bypassed, r.bypasses, r.attempts) else stringResource(R.string.rule_working),
                    value = pluralRes(R.plurals.attempts_short, r.attempts),
                )
            }
            FbDivider()
        }

        // One suggestion, with Apply / Not now.
        state.recommendation?.let { rec ->
            SectionGap()
            SectionLabel(stringResource(R.string.section_suggestion))
            Suggestion(rec, vm)
        }
        if (state.applied) {
            SectionGap()
            Text(stringResource(R.string.sugg_applied), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun OutcomeRow(label: Int, count: Int) {
    DividerRow(title = stringResource(label), value = count.toString())
}

@Composable
private fun Suggestion(rec: RecommendationEngine.Recommendation, vm: ActivityViewModel) {
    val context = LocalContext.current
    val (evidence, change) = when (val p = rec.proposal) {
        is RecommendationEngine.Proposal.AddRoutine -> {
            val start = Fmt.minuteOfDay(context, p.startMinute)
            val end = Fmt.minuteOfDay(context, p.endMinute)
            stringResource(R.string.sugg_routine_evidence, rec.evidence.days, start, end) to
                stringResource(R.string.sugg_routine_change, Fmt.apps(context, p.packages, vm::label), start, end, Fmt.days(context, p.days))
        }
        is RecommendationEngine.Proposal.MakeRoutineStrict ->
            stringResource(R.string.sugg_strict_evidence, p.routineName, rec.evidence.days) to stringResource(R.string.sugg_strict_change, p.routineName)
        is RecommendationEngine.Proposal.AddAppLimit -> {
            val name = vm.label(p.pkg)
            stringResource(R.string.sugg_limit_evidence, name, rec.evidence.days, Fmt.minutes(context, rec.evidence.typicalMinutes)) to
                stringResource(R.string.sugg_limit_change, name, Fmt.minutes(context, p.minutes))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter)) {
        Text(evidence, style = FbType.body.copy(color = Fb.textSecondary))
        Spacer(Modifier.height(6.dp))
        Text(change, style = FbType.body)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.action_apply), vm::apply) }
            Column(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.action_not_now), vm::notNow) }
        }
    }
    Spacer(Modifier.width(0.dp))
}
