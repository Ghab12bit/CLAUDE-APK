package com.focusblock.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.core.Fmt
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.FbCard
import com.focusblock.app.ui.components.FbProgressBar
import com.focusblock.app.ui.components.StackedBarChart
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType

/** "35 min of 50 min used today", a progress bar and what happens next. */
@Composable
fun LimitTodayCard(insight: LimitInsight?, allowanceMinutes: Int, enabled: Boolean, usageAccess: Boolean) {
    val context = LocalContext.current
    val allowance = allowanceMinutes * 60_000L
    val used = insight?.usedToday
    FbCard(brush = Brush.linearGradient(listOf(Fb.surfaceActive, Fb.surface))) {
        Text(stringResource(R.string.limit_today), style = FbType.overline)
        Spacer(Modifier.height(6.dp))
        if (!usageAccess || used == null) {
            Text(stringResource(R.string.limit_needs_usage), style = FbType.body.copy(color = Fb.warning))
        } else {
            val reached = used >= allowance
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.duration(context, used), style = FbType.display.copy(fontSize = FbType.title.fontSize * 1.2f))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.limit_of_allowance, Fmt.minutes(context, allowanceMinutes)), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            val fraction = if (allowance > 0) used.toFloat() / allowance else 0f
            val color = when {
                reached -> Fb.warning
                fraction >= 0.8f -> Fb.peak
                else -> Fb.accent
            }
            FbProgressBar(fraction, color, height = 12.dp, brush = if (reached) null else Brush.horizontalGradient(listOf(Fb.accent, Fb.accentAlt)))
            Spacer(Modifier.height(10.dp))
            val line = when {
                !enabled -> stringResource(R.string.limit_status_off)
                reached -> stringResource(R.string.limit_status_reached)
                else -> stringResource(R.string.limit_status_left, Fmt.duration(context, allowance - used))
            }
            Text(line, style = FbType.label.copy(color = if (reached && enabled) Fb.warning else Fb.textPrimary))
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.limit_resets), style = FbType.caption)
        }
    }
}

/** Plain-language explanation, different for App limit and Daily limit. */
@Composable
fun LimitHowItWorksCard(kind: RuleKind, countsAll: Boolean, appCount: Int, allowanceMinutes: Int) {
    val context = LocalContext.current
    val allowance = Fmt.minutes(context, allowanceMinutes)
    FbCard {
        Text(stringResource(R.string.limit_how_title), style = FbType.heading)
        Spacer(Modifier.height(10.dp))
        if (kind == RuleKind.APP_LIMIT) {
            HowRow(Icons.Outlined.Timer, stringResource(R.string.limit_how_app_1, appCount))
            HowRow(Icons.Outlined.Block, stringResource(R.string.limit_how_app_2, allowance))
        } else {
            HowRow(Icons.Outlined.Timer, if (countsAll) stringResource(R.string.limit_how_daily_all_1) else stringResource(R.string.limit_how_daily_chosen_1, appCount))
            HowRow(Icons.Outlined.Block, stringResource(R.string.limit_how_daily_2, allowance))
        }
        HowRow(Icons.Outlined.Schedule, stringResource(R.string.limit_how_reset))
        HowRow(Icons.Outlined.Call, stringResource(R.string.limit_how_essentials))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(if (kind == RuleKind.APP_LIMIT) R.string.limit_how_vs_daily else R.string.limit_how_vs_app), style = FbType.caption)
    }
}

@Composable
private fun HowRow(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Fb.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Fb.accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = FbType.body, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Which counted apps used the allowance today. */
@Composable
fun LimitTodayByAppCard(insight: LimitInsight, label: (String) -> String) {
    val context = LocalContext.current
    val total = insight.usedToday ?: return
    FbCard {
        Text(stringResource(R.string.limit_by_app), style = FbType.heading)
        Spacer(Modifier.height(6.dp))
        if (insight.perApp.isEmpty()) {
            Text(stringResource(R.string.limit_by_app_none), style = FbType.body.copy(color = Fb.textSecondary))
        } else {
            insight.perApp.take(8).forEach { (pkg, ms) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, null, 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label(pkg), style = FbType.body, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        FbProgressBar(if (total > 0) ms.toFloat() / total else 0f, Fb.accentAlt, height = 4.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(Fmt.duration(context, ms), style = FbType.body.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** The last 7 days against the allowance (dashed line). */
@Composable
fun LimitWeekCard(insight: LimitInsight, allowanceMinutes: Int) {
    val context = LocalContext.current
    val allowance = allowanceMinutes * 60_000L
    FbCard {
        Text(stringResource(R.string.limit_week), style = FbType.heading)
        Spacer(Modifier.height(10.dp))
        StackedBarChart(
            values = insight.week.map { longArrayOf(it.second) },
            colors = listOf(Fb.accent),
            xLabel = { i -> insight.week.getOrNull(i)?.first?.let { Fmt.weekdayShort(it) } },
            yLabel = { v -> if (v == 0L) "0" else Fmt.duration(context, v) },
            description = stringResource(R.string.limit_week_cd, insight.overDays),
            height = 150.dp,
            average = allowance,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.limit_over_days, insight.overDays), style = FbType.label.copy(color = if (insight.overDays > 0) Fb.warning else Fb.success))
                Text(stringResource(R.string.limit_line_note), style = FbType.caption)
            }
            insight.average?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.duration(context, it), style = FbType.label)
                    Text(stringResource(R.string.limit_average), style = FbType.caption)
                }
            }
        }
    }
}
