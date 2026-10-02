package com.focusblock.app.ui.activity

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.Requirement
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.AppCategory
import com.focusblock.app.policy.OverrideKind
import com.focusblock.app.policy.RecommendationEngine
import com.focusblock.app.ui.components.AppHeader
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.ChoiceChips
import com.focusblock.app.ui.components.DividerRow
import com.focusblock.app.ui.components.DotPill
import com.focusblock.app.ui.components.EmptyState
import com.focusblock.app.ui.components.FbCard
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.FbProgressBar
import com.focusblock.app.ui.components.FbSheet
import com.focusblock.app.ui.components.HourBarChart
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.ProblemBanner
import com.focusblock.app.ui.components.SecondaryButton
import com.focusblock.app.ui.components.SectionHeader
import com.focusblock.app.ui.components.SegmentedControl
import com.focusblock.app.ui.components.SkeletonLine
import com.focusblock.app.ui.components.StackedBarChart
import com.focusblock.app.ui.components.StatTile
import com.focusblock.app.ui.components.pluralRes
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType
import kotlin.math.abs

/** Category colours, in [AppCategory] order. */
private val categoryColors = listOf(Fb.distracting, Fb.neutral, Fb.productive)

private fun AppCategory.color(): Color = categoryColors[ordinal]

@Composable
private fun AppCategory.label(): String = stringResource(
    when (this) {
        AppCategory.DISTRACTING -> R.string.category_distracting
        AppCategory.NEUTRAL -> R.string.category_neutral
        AppCategory.PRODUCTIVE -> R.string.category_productive
    },
)

@Composable
fun ActivityScreen(state: ActivityUi, vm: ActivityViewModel, onSettings: () -> Unit) {
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader(onSettings)
        Spacer(Modifier.height(4.dp))
        SegmentedControl(
            listOf(
                Period.DAY to stringResource(R.string.period_day),
                Period.WEEK to stringResource(R.string.period_week),
                Period.TREND to stringResource(R.string.period_trend),
            ),
            state.period, vm::setPeriod,
        )
        Spacer(Modifier.height(8.dp))
        RangeNavigator(state, vm)
        if (state.loading) {
            Spacer(Modifier.height(12.dp))
            SkeletonLine(0.5f, 48.dp); SkeletonLine(0.95f, 190.dp); SkeletonLine(0.9f, 56.dp); SkeletonLine(0.9f, 56.dp)
        } else {
            ActivityContent(state, vm, onOpenApp = { openApp = it })
        }
        Spacer(Modifier.height(32.dp))
    }
    val excluded = state.excludedApps.firstOrNull { it.pkg == openApp }
    val app = state.apps.firstOrNull { it.pkg == openApp } ?: excluded
    if (app != null) {
        AppSheet(
            app, state, counted = excluded == null,
            onCategory = { vm.setCategory(app.pkg, it) },
            onCounted = { c -> vm.setCounted(app.pkg, app.label, c); if (!c || excluded != null) openApp = null },
            onDismiss = { openApp = null },
        )
    }
}

@Composable
private fun RangeNavigator(state: ActivityUi, vm: ActivityViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val canOlder = state.offset < state.maxOffset
        val canNewer = state.offset > 0
        IconButton(onClick = vm::older, enabled = canOlder) {
            Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.range_older), tint = if (canOlder) Fb.textPrimary else Fb.disabled)
        }
        Text(
            state.rangeTitle.uppercase(), style = FbType.label.copy(color = Fb.accent, fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = vm::newer, enabled = canNewer) {
            Icon(Icons.Outlined.ChevronRight, stringResource(R.string.range_newer), tint = if (canNewer) Fb.textPrimary else Fb.disabled)
        }
    }
}

@Composable
private fun ActivityContent(state: ActivityUi, vm: ActivityViewModel, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current

    if (!state.usageAccess) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.usage_missing), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))
        Spacer(Modifier.height(8.dp))
        ProblemBanner(stringResource(R.string.status_needs, stringResource(R.string.perm_usage)), stringResource(R.string.action_fix),
            { PermissionHealth.open(context, Requirement.USAGE) })
        Spacer(Modifier.height(8.dp))
    }
    if (state.screenTime != null) {
        Hero(state)
        Spacer(Modifier.height(12.dp))
        FbCard {
            StackedBarChart(
                values = state.chart,
                colors = categoryColors,
                xLabel = { i -> state.chartLabels.getOrNull(i) },
                yLabel = { v -> if (v == 0L) "0" else Fmt.duration(context, v) },
                description = state.chartDescription,
                average = state.chartAverage,
                highlight = null,
            )
            Spacer(Modifier.height(10.dp))
            Legend()
            if (state.hourlyMissing) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.history_no_hours), style = FbType.caption)
            }
        }

        // Most used apps.
        SectionHeader(stringResource(R.string.section_most_used))
        if (state.apps.isEmpty()) {
            FbCard { Text(stringResource(R.string.apps_no_usage), style = FbType.body.copy(color = Fb.textSecondary)) }
        } else {
            var expanded by rememberSaveable { mutableStateOf(false) }
            FbCard(contentPadding = 0.dp) {
                val shown = if (expanded) state.apps else state.apps.take(5)
                shown.forEachIndexed { i, a ->
                    if (i > 0) FbDivider()
                    AppRow(a, state.screenTime ?: 0L) { onOpenApp(a.pkg) }
                }
            }
            if (state.apps.size > 5) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Fb.surface).clickable { expanded = !expanded }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(if (expanded) R.string.action_less else R.string.action_more), style = FbType.label)
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Fb.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Apps the user does not count (e.g. a clock used as a stopwatch), with a way back.
        if (state.excludedApps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FbCard(contentPadding = 0.dp) {
                Text(stringResource(R.string.not_counted_title), style = FbType.label.copy(color = Fb.textSecondary),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                state.excludedApps.forEach { a ->
                    DividerRow(
                        title = a.label,
                        subtitle = if (a.millis > 0) stringResource(R.string.not_counted_time, Fmt.duration(context, a.millis)) else null,
                        leading = { AppIcon(a.pkg, null, 32.dp) },
                        trailing = { com.focusblock.app.ui.components.TextLink(stringResource(R.string.action_count_again), { vm.setCounted(a.pkg, a.label, true) }) },
                        onClick = { onOpenApp(a.pkg) },
                    )
                }
            }
        }

        // Habits.
        SectionHeader(stringResource(R.string.section_habits))
        BalanceCard(state)
        Spacer(Modifier.height(12.dp))
        PeakCard(state)
        Spacer(Modifier.height(12.dp))
        UsageSplitCard(state)

        // Focus.
        SectionHeader(stringResource(R.string.section_focus))
        if (state.detailMissing) {
            Text(stringResource(R.string.history_no_detail), style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Fmt.duration(context, state.longestFocus), stringResource(R.string.stat_longest_focus), Icons.Outlined.SelfImprovement, Fb.success, Modifier.weight(1f),
                sub = stringResource(R.string.stat_longest_focus_sub))
            StatTile(Fmt.duration(context, state.longestUse), stringResource(R.string.stat_continuous_use), Icons.Outlined.PhoneAndroid, Fb.accent, Modifier.weight(1f),
                sub = stringResource(R.string.stat_continuous_use_sub))
        }
    }

    // Distractions.
    SectionHeader(stringResource(R.string.section_distractions))
    Row(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val known = state.screenTime != null && !state.detailMissing
        val pickups = if (known) stringResource(R.string.times_count, state.pickups) else stringResource(R.string.data_unavailable)
        val perDay = if (known && state.daysInRange > 1) stringResource(R.string.per_day, state.pickups / state.daysInRange) else null
        StatTile(pickups, stringResource(R.string.stat_pickups), Icons.Outlined.TouchApp, Fb.accentAlt, Modifier.weight(1f), sub = perDay)
        StatTile(stringResource(R.string.times_count, state.totalAttempts), stringResource(R.string.stat_blocked_attempts), Icons.Outlined.Block, Fb.warning, Modifier.weight(1f))
    }
    if (state.totalAttempts > 0) {
        Spacer(Modifier.height(12.dp))
        FbCard {
            Text(stringResource(R.string.section_attempts_by_hour), style = FbType.label.copy(color = Fb.textSecondary))
            Spacer(Modifier.height(8.dp))
            val description = state.attemptsByHour.withIndex().filter { it.value > 0 }
                .joinToString(", ") { context.getString(R.string.chart_hour_count, Fmt.minuteOfDay(context, it.index * 60), it.value) }
            HourBarChart(state.attemptsByHour, { h -> Fmt.hourShort(context, h) }, stringResource(R.string.chart_cd, description))
        }
    }
    if (state.unlocks.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        FbCard(contentPadding = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LockOpen, null, tint = Fb.warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.section_unlocks), style = FbType.label, modifier = Modifier.weight(1f))
                Text(state.unlocks.size.toString(), style = FbType.label.copy(color = Fb.textSecondary))
            }
            state.unlocks.take(10).forEach { u ->
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
        }
    }

    // Blocks.
    SectionHeader(stringResource(R.string.section_blocks))
    FbCard {
        Row(Modifier.fillMaxWidth()) {
            OutcomeCell(state.outcomes.finished, R.string.outcome_finished, Fb.success, Modifier.weight(1f))
            OutcomeCell(state.outcomes.notFinished, R.string.outcome_not_finished, Fb.accent, Modifier.weight(1f))
            OutcomeCell(state.outcomes.extended, R.string.outcome_extended, Fb.accentAlt, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            OutcomeCell(state.outcomes.endedEarly, R.string.outcome_ended_early, Fb.warning, Modifier.weight(1f))
            OutcomeCell(state.outcomes.unanswered, R.string.outcome_unanswered, Fb.textSecondary, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }

    // Rules working / bypassed.
    if (state.rules.isNotEmpty()) {
        SectionHeader(stringResource(R.string.section_rules_working))
        FbCard(contentPadding = 0.dp) {
            state.rules.forEachIndexed { i, r ->
                if (i > 0) FbDivider()
                DividerRow(
                    title = r.name,
                    subtitle = if (r.oftenBypassed) stringResource(R.string.rule_often_bypassed, r.bypasses, r.attempts) else stringResource(R.string.rule_working),
                    value = pluralRes(R.plurals.attempts_short, r.attempts),
                )
            }
        }
    }

    if (state.period == Period.TREND && state.weeks.isNotEmpty()) {
        SectionHeader(stringResource(R.string.section_weeks))
        FbCard(contentPadding = 0.dp) {
            state.weeks.forEachIndexed { i, w ->
                if (i > 0) FbDivider()
                val change = w.change?.let { c ->
                    if (c <= 0) stringResource(R.string.change_down, abs(c)) else stringResource(R.string.change_up, c)
                }
                DividerRow(
                    title = stringResource(R.string.range_dates, Fmt.dayMonth(w.from), Fmt.dayMonth(w.to)),
                    subtitle = listOfNotNull(
                        if (w.daysWithData > 0) stringResource(R.string.per_day_duration, Fmt.duration(context, w.total / w.daysWithData)) else stringResource(R.string.data_unavailable),
                        change,
                    ).joinToString(" · "),
                    value = Fmt.duration(context, w.total),
                )
            }
        }
    }

    // One suggestion, with Apply / Not now.
    state.recommendation?.let { rec ->
        SectionHeader(stringResource(R.string.section_suggestion))
        Suggestion(rec, vm)
    }
    if (state.applied) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.sugg_applied), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))
    }
    if (!state.hasAnyData && state.usageAccess) {
        Spacer(Modifier.height(8.dp))
        EmptyState(stringResource(R.string.activity_empty))
    }
}

@Composable
private fun Hero(state: ActivityUi) {
    val context = LocalContext.current
    val screen = state.screenTime ?: 0L
    Column(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val big = if (state.period == Period.TREND) state.dailyAverage ?: 0L else screen
        Text(Fmt.duration(context, big), style = FbType.display, textAlign = TextAlign.Center)
        Text(
            stringResource(if (state.period == Period.TREND) R.string.overline_daily_average else R.string.overline_screen_time),
            style = FbType.overline, textAlign = TextAlign.Center,
        )
        val compare = state.compareTo
        val line: Pair<String, Color>? = when (state.period) {
            Period.DAY -> compare?.let { usual ->
                val diff = screen - usual
                val usualText = Fmt.duration(context, usual)
                when {
                    abs(diff) < 5 * 60_000 -> stringResource(R.string.compare_day_same, usualText) to Fb.textSecondary
                    diff < 0 -> stringResource(R.string.compare_day_below, Fmt.duration(context, -diff), usualText) to Fb.success
                    else -> stringResource(R.string.compare_day_above, Fmt.duration(context, diff), usualText) to Fb.warning
                }
            }
            Period.WEEK -> {
                val avg = if (state.daysInRange > 0) screen / state.daysInRange else 0L
                val change = compare?.let { com.focusblock.app.policy.Insights.changePercent(screen, it) }
                val base = stringResource(R.string.per_day_duration, Fmt.duration(context, avg))
                when {
                    change == null -> base to Fb.textSecondary
                    change <= 0 -> "$base · " + stringResource(R.string.change_down_vs_last_week, abs(change)) to Fb.success
                    else -> "$base · " + stringResource(R.string.change_up_vs_last_week, change) to Fb.warning
                }
            }
            Period.TREND -> state.weeks.firstOrNull()?.change?.let { c ->
                if (c <= 0) stringResource(R.string.change_down_vs_last_week, abs(c)) to Fb.success
                else stringResource(R.string.change_up_vs_last_week, c) to Fb.warning
            }
        }
        line?.let { (text, color) ->
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(text, style = FbType.label.copy(color = if (color == Fb.textSecondary) Fb.textSecondary else Fb.textPrimary), textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        AppCategory.values().forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.color()))
                Spacer(Modifier.width(6.dp))
                Text(c.label(), style = FbType.caption)
            }
        }
    }
}

@Composable
private fun AppRow(a: AppStat, total: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.pkg, null, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, style = FbType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotPill(a.category.label(), a.category.color())
                if (a.attempts > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(pluralRes(R.plurals.attempts_short, a.attempts), style = FbType.caption, maxLines = 1)
                }
            }
            if (total > 0) {
                Spacer(Modifier.height(6.dp))
                FbProgressBar(a.millis.toFloat() / total, a.category.color(), height = 4.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(Fmt.duration(context, a.millis), style = FbType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Icon(Icons.Outlined.KeyboardArrowRight, null, tint = Fb.textSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BalanceCard(state: ActivityUi) {
    val context = LocalContext.current
    FbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.habit_balance), style = FbType.heading, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.balance_percent, state.balancePercent), style = FbType.label.copy(color = Fb.accent, fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.height(14.dp))
        FbProgressBar(state.balancePercent / 100f, Fb.accent, height = 12.dp,
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Fb.accent, Fb.accentAlt)))
        Spacer(Modifier.height(12.dp))
        val perDay = (state.screenTime ?: 0L) / state.daysInRange.coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = Fb.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.daysInRange > 1) stringResource(R.string.per_day_duration, Fmt.duration(context, perDay)) else Fmt.duration(context, perDay),
                style = FbType.label, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.WbSunny, null, tint = Fb.warning, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.balance_awake), style = FbType.label)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.balance_explain), style = FbType.caption)
    }
}

@Composable
private fun PeakCard(state: ActivityUi) {
    val context = LocalContext.current
    FbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Whatshot, null, tint = Fb.peak, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.habit_peak), style = FbType.heading, modifier = Modifier.weight(1f))
            Text(
                state.peakHour?.let { h -> stringResource(R.string.time_range, Fmt.minuteOfDay(context, h * 60), Fmt.minuteOfDay(context, ((h + 1) % 24) * 60)) }
                    ?: stringResource(R.string.data_none),
                style = FbType.label.copy(color = Fb.accent, fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(Modifier.height(12.dp))
        StackedBarChart(
            values = state.hourTotals.map { longArrayOf(it) },
            colors = listOf(Fb.accent),
            xLabel = { i -> if (i % 6 == 0) Fmt.hourShort(context, i) else null },
            yLabel = { "" },
            description = state.peakHour?.let { stringResource(R.string.peak_cd, Fmt.minuteOfDay(context, it * 60)) } ?: stringResource(R.string.data_none),
            height = 96.dp,
            highlight = state.peakHour,
            showYAxis = false,
        )
    }
}

@Composable
private fun UsageSplitCard(state: ActivityUi) {
    val context = LocalContext.current
    FbCard {
        Text(stringResource(R.string.habit_usage), style = FbType.heading)
        Spacer(Modifier.height(6.dp))
        // Largest first, like the reference layout.
        AppCategory.values().sortedByDescending { state.byCategory[it.ordinal] }.forEach { c ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.color()))
                Spacer(Modifier.width(8.dp))
                Text(c.label(), style = FbType.label, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f)) { FbProgressBar(state.shares[c.ordinal] / 100f, c.color(), height = 8.dp) }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.percent, state.shares[c.ordinal]), style = FbType.label, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.usage_split_explain, Fmt.duration(context, state.byCategory[AppCategory.DISTRACTING.ordinal])),
            style = FbType.caption,
        )
    }
}

@Composable
private fun OutcomeCell(count: Int, label: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(count.toString(), style = FbType.title.copy(color = color))
        Text(stringResource(label), style = FbType.caption, maxLines = 2)
    }
}

@Composable
private fun AppSheet(app: AppStat, state: ActivityUi, counted: Boolean, onCategory: (AppCategory) -> Unit, onCounted: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    FbSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(Modifier.padding(horizontal = Fb.gutter), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.pkg, null, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(app.label, style = FbType.heading)
                    Text(stringResource(R.string.app_sheet_time, Fmt.duration(context, app.millis), state.rangeTitle.lowercase()), style = FbType.caption)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(app.opens.toString(), stringResource(R.string.stat_opens), Icons.Outlined.TouchApp, Fb.accent, Modifier.weight(1f))
                StatTile(app.attempts.toString(), stringResource(R.string.stat_blocked_attempts), Icons.Outlined.Block, Fb.warning, Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_sheet_category), style = FbType.label.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(8.dp))
            val options = AppCategory.values().toList()
            ChoiceChips(options.map { it.label() }, options.indexOf(app.category), { i -> onCategory(options[i]) })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.app_sheet_category_note), style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(16.dp))
            FbCard(contentPadding = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().clickable { onCounted(!counted) }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.count_in_screen_time), style = FbType.body)
                        Text(stringResource(R.string.count_in_screen_time_note), style = FbType.caption)
                    }
                    Spacer(Modifier.width(12.dp))
                    com.focusblock.app.ui.components.FbSwitch(counted, { onCounted(it) }, contentDescription = stringResource(R.string.count_in_screen_time))
                }
            }
        }
    }
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
    FbCard(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Fb.surfaceActive, Fb.surface))) {
        Text(evidence, style = FbType.body.copy(color = Fb.textSecondary))
        Spacer(Modifier.height(6.dp))
        Text(change, style = FbType.body.copy(fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.action_apply), vm::apply) }
            Column(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.action_not_now), vm::notNow) }
        }
    }
}
