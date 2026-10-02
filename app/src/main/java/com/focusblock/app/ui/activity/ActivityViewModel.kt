package com.focusblock.app.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.R
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.SessionManager
import com.focusblock.app.core.UsageRepository
import com.focusblock.app.core.toReasonType
import com.focusblock.app.database.entity.ExclusionType
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.AppCategory
import com.focusblock.app.policy.Insights
import com.focusblock.app.policy.Metrics
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.RecommendationEngine
import com.focusblock.app.policy.SessionOutcome
import com.focusblock.app.policy.UsageCalculator
import com.focusblock.app.core.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Period { DAY, WEEK, TREND }

data class AppStat(
    val pkg: String,
    val label: String,
    val millis: Long,
    val category: AppCategory,
    val opens: Int,
    val attempts: Int,
)

data class UnlockRow(val pkg: String, val label: String, val kind: String, val status: String, val time: Long, val reason: String?)
data class RuleRowStat(val name: String, val attempts: Int, val bypasses: Int) {
    val oftenBypassed: Boolean get() = Metrics.RuleBypass(name, attempts, bypasses).oftenBypassed
}
data class WeekStat(val from: LocalDate, val to: LocalDate, val total: Long, val daysWithData: Int, val change: Int?)

data class ActivityUi(
    val loading: Boolean = true,
    val period: Period = Period.DAY,
    /** 0 = today / this week; 1 = the one before, and so on. */
    val offset: Int = 0,
    val maxOffset: Int = 0,
    val rangeTitle: String = "",
    val usageAccess: Boolean = true,
    /** Screen time in the range, or null when Usage access is missing (never shown as zero). */
    val screenTime: Long? = null,
    /** Day: usual screen time by this time of day (2-week average). Week: the week before. */
    val compareTo: Long? = null,
    /** Trend: average per day over days with data. */
    val dailyAverage: Long? = null,
    val chart: List<LongArray> = emptyList(),
    val chartLabels: List<String?> = emptyList(),
    val chartAverage: Long? = null,
    val chartDescription: String = "",
    val apps: List<AppStat> = emptyList(),
    val byCategory: LongArray = LongArray(3),
    val shares: IntArray = IntArray(3),
    val balancePercent: Int = 0,
    val daysInRange: Int = 1,
    val hourTotals: LongArray = LongArray(24),
    val peakHour: Int? = null,
    val longestFocus: Long = 0,
    val longestUse: Long = 0,
    val pickups: Int = 0,
    val weeks: List<WeekStat> = emptyList(),
    val outcomes: Metrics.OutcomeCounts = Metrics.OutcomeCounts(0, 0, 0, 0, 0),
    val attemptsByHour: IntArray = IntArray(24),
    val totalAttempts: Int = 0,
    val unlocks: List<UnlockRow> = emptyList(),
    val rules: List<RuleRowStat> = emptyList(),
    val recommendation: RecommendationEngine.Recommendation? = null,
    val labels: Map<String, String> = emptyMap(),
    val applied: Boolean = false,
    val hasAnyData: Boolean = false,
)

/**
 * Activity (spec 4.8): screen time and habits from Android's usage events, blocks from
 * block_sessions, attempts from block_logs and unlocks from unlock_events. Formulas are in
 * docs/metrics.md; the arithmetic lives in [Insights] and [UsageCalculator], which are unit-tested.
 */
class ActivityViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(ActivityUi())
    val state: StateFlow<ActivityUi> = mutable.asStateFlow()

    init { load() }

    fun setPeriod(p: Period) {
        if (p == mutable.value.period) return
        mutable.update { it.copy(period = p, offset = 0, loading = true) }
        load()
    }

    fun older() = shift(+1)
    fun newer() = shift(-1)

    private fun shift(by: Int) {
        val next = (mutable.value.offset + by).coerceIn(0, mutable.value.maxOffset)
        if (next == mutable.value.offset) return
        mutable.update { it.copy(offset = next, loading = true) }
        load()
    }

    fun setCategory(pkg: String, category: AppCategory) {
        viewModelScope.launch {
            graph.categories.set(pkg, category)
            load()
        }
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = graph.context
            val now = graph.clock.now()
            val zone = graph.clock.zone()
            val today = PolicyTime.localDate(now, zone)
            val period = mutable.value.period
            val maxOffset = when (period) { Period.DAY -> UsageRepository.INSIGHT_DAYS; Period.WEEK -> 3; Period.TREND -> 0 }
            val offset = mutable.value.offset.coerceIn(0, maxOffset)
            graph.categories.load()
            val categoryOf: (String) -> AppCategory = graph.categories::categoryOf

            // The range on screen.
            val dates: List<LocalDate> = when (period) {
                Period.DAY -> listOf(today.minusDays(offset.toLong()))
                Period.WEEK -> (6 downTo 0).map { today.minusDays(it + 7L * offset) }
                Period.TREND -> (UsageRepository.INSIGHT_DAYS downTo 0).map { today.minusDays(it.toLong()) }
            }
            val from = dates.first().atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(dates.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
            val title = when (period) {
                Period.DAY -> when (offset) {
                    0 -> ctx.getString(R.string.range_today)
                    1 -> ctx.getString(R.string.range_yesterday)
                    else -> Fmt.dateShort(dates.first())
                }
                Period.WEEK -> if (offset == 0) ctx.getString(R.string.range_last_7_days)
                    else ctx.getString(R.string.range_dates, Fmt.dayMonth(dates.first()), Fmt.dayMonth(dates.last()))
                Period.TREND -> ctx.getString(R.string.range_last_4_weeks)
            }

            // Screen time: every app except FocusBlock, home-screen launchers, System UI and apps the user excluded.
            val notCounted = graph.safety.launchers() + setOf(ctx.packageName, "com.android.systemui") +
                graph.db.excludedAppDao().getExcludedPackageNames(ExclusionType.SCREEN_TIME_REPORT)
            val counted: (String) -> Boolean = { it !in notCounted }
            val read = graph.usage.insightEvents()
            val access = read != null

            var screen: Long? = null
            var comparison: Long? = null
            var dailyAverage: Long? = null
            var chart: List<LongArray> = emptyList()
            var chartLabels: List<String?> = emptyList()
            var chartAverage: Long? = null
            var apps: List<AppStat> = emptyList()
            var byCategory = LongArray(3)
            var hourTotals = LongArray(24)
            var longestFocus = 0L
            var longestUse = 0L
            var pickups = 0
            var weeks: List<WeekStat> = emptyList()
            var daysInRange = dates.size

            if (read != null) {
                val (readAt, events) = read
                val intervals = UsageCalculator.intervals(events, readAt)
                val dayBuckets = Insights.buckets(intervals, Insights.dayBoundaries(dates, zone), categoryOf, counted)

                when (period) {
                    Period.DAY -> {
                        val date = dates.first()
                        // Fold the hour buckets into 24 local hours (DST days have 23 or 25).
                        val hours = List(24) { LongArray(3) }
                        Insights.buckets(intervals, Insights.hourBoundaries(date, zone), categoryOf, counted).forEach { b ->
                            val h = java.time.Instant.ofEpochMilli(b.start).atZone(zone).hour
                            b.byCategory.forEachIndexed { c, v -> hours[h][c] += v }
                        }
                        chart = hours
                        chartLabels = List(24) { h -> if (h % 6 == 0) Fmt.hourShort(ctx, h) else null }
                        // Usual screen time: today is compared at the same time of day; past days whole.
                        val cutoff = if (offset == 0) ((now - from) / 60_000L).toInt().coerceIn(0, 1439) else null
                        val history = (14 downTo 0).map { date.minusDays(it.toLong()) }
                        val totals = UsageCalculator.dailyTotals(intervals, history, zone, cutoff)
                            .mapValues { (_, m) -> m.filterKeys(counted).values.sum() }
                        comparison = Metrics.baseline(totals, date)
                        daysInRange = 1
                    }
                    Period.WEEK -> {
                        chart = dayBuckets.map { it.byCategory }
                        chartLabels = dates.map { if (it == today) ctx.getString(R.string.range_today_short) else Fmt.weekdayShort(it) }
                        val withData = dayBuckets.count { it.total > 0 }.coerceAtLeast(1)
                        chartAverage = dayBuckets.sumOf { it.total } / withData
                        val prevDates = dates.map { it.minusDays(7) }
                        comparison = Insights.buckets(intervals, Insights.dayBoundaries(prevDates, zone), categoryOf, counted).sumOf { it.total }
                            .takeIf { it > 0 }
                        daysInRange = dates.count { !it.isAfter(today) }
                    }
                    Period.TREND -> {
                        chart = dayBuckets.map { it.byCategory }
                        chartLabels = dates.mapIndexed { i, d -> if ((dates.size - 1 - i) % 7 == 0) Fmt.dayMonth(d) else null }
                        val withData = dayBuckets.filter { it.total > 0 }
                        dailyAverage = if (withData.isEmpty()) null else withData.sumOf { it.total } / withData.size
                        chartAverage = dailyAverage
                        // Four weeks, newest first, each compared with the week before it.
                        val weekTotals = (0 until 4).map { w ->
                            val slice = dayBuckets.subList(dayBuckets.size - 7 * (w + 1), dayBuckets.size - 7 * w)
                            Triple(dates[dates.size - 7 * (w + 1)], dates[dates.size - 1 - 7 * w], slice)
                        }
                        weeks = weekTotals.mapIndexed { i, (start, end, slice) ->
                            val total = slice.sumOf { it.total }
                            val prev = weekTotals.getOrNull(i + 1)?.third?.sumOf { it.total }
                            WeekStat(start, end, total, slice.count { it.total > 0 }, prev?.let { Insights.changePercent(total, it) })
                        }
                        daysInRange = withData.size.coerceAtLeast(1)
                    }
                }

                screen = dayBuckets.sumOf { it.total }
                byCategory = LongArray(3).also { sum -> dayBuckets.forEach { b -> b.byCategory.forEachIndexed { c, v -> sum[c] += v } } }
                hourTotals = Insights.hourOfDayTotals(intervals, from, to, zone, counted)
                pickups = Insights.pickups(events, from, to)
                val spans = Insights.spans(intervals)
                longestUse = Insights.longestUse(spans, from, to)
                longestFocus = dates.filter { !it.isAfter(today) }.maxOfOrNull { Insights.longestFocus(spans, it, zone, now) } ?: 0L

                val perApp = UsageCalculator.totals(intervals, from, to).filterKeys(counted)
                val opens = UsageCalculator.launches(events, from, to)
                val attemptsByApp = graph.db.attemptDao().since(from).filter { it.timestamp < to }.groupingBy { it.packageName }.eachCount()
                apps = perApp.entries.filter { it.value >= 60_000L }.sortedByDescending { it.value }.take(20).map { (pkg, ms) ->
                    AppStat(pkg, graph.apps.label(pkg), ms, categoryOf(pkg), opens[pkg] ?: 0, attemptsByApp[pkg] ?: 0)
                }
            }

            // Blocks, attempts, unlocks and rules in the same range.
            val sessions = graph.db.blockSessionDao().since(from)
                .filter { it.startedAt < to && !it.isActive && it.endReason != "LEGACY" && it.endReason != SessionManager.TEST }
            val outcomes = Metrics.outcomes(sessions.map { s -> SessionOutcome.values().firstOrNull { it.name == s.outcome } })
            val logs = graph.db.attemptDao().since(from).filter { it.timestamp < to }
            val unlockEvents = graph.db.unlockEventDao().since(from).filter { it.requestedAt < to && it.status != UnlockEventEntity.CANCELLED }
            val ruleAttempts = logs.groupBy { (it.blockedBy.toReasonType()?.name ?: "") + "|" + (it.scheduleId ?: 0) + "|" + (it.scheduleName ?: "") }
            val rules = ruleAttempts.map { (key, list) ->
                val (type, id, name) = key.split('|', limit = 3)
                val bypasses = unlockEvents.count { u -> u.status == UnlockEventEntity.GRANTED && u.reasonType == type && (u.ruleId ?: 0L) == (id.toLongOrNull() ?: 0L) }
                RuleRowStat(ruleName(type, name), list.size, bypasses)
            }.sortedByDescending { it.attempts }

            val rec = if (offset == 0) runCatching { graph.recommendations.current() }.getOrNull() else null
            val labels = HashMap<String, String>()
            (rec?.proposal as? RecommendationEngine.Proposal.AddRoutine)?.packages?.forEach { labels[it] = graph.apps.label(it) }
            (rec?.proposal as? RecommendationEngine.Proposal.AddAppLimit)?.pkg?.let { labels[it] = graph.apps.label(it) }

            val description = buildString {
                append(ctx.getString(R.string.chart_usage_cd, title, Fmt.duration(ctx, screen ?: 0L)))
                chart.forEachIndexed { i, b ->
                    val total = b.sum()
                    if (total >= 60_000L) {
                        val label = if (period == Period.DAY) Fmt.minuteOfDay(ctx, i * 60) else if (period == Period.WEEK) Fmt.dateShort(dates[i]) else Fmt.dayMonth(dates[i])
                        append(". ").append(label).append(": ").append(Fmt.duration(ctx, total))
                    }
                }
            }

            mutable.update {
                it.copy(
                    loading = false,
                    offset = offset,
                    maxOffset = maxOffset,
                    rangeTitle = title,
                    usageAccess = access,
                    screenTime = screen,
                    compareTo = comparison,
                    dailyAverage = dailyAverage,
                    chart = chart,
                    chartLabels = chartLabels,
                    chartAverage = chartAverage,
                    chartDescription = description,
                    apps = apps,
                    byCategory = byCategory,
                    shares = Insights.sharePercents(byCategory),
                    balancePercent = Insights.balancePercent(screen ?: 0L, daysInRange),
                    daysInRange = daysInRange,
                    hourTotals = hourTotals,
                    peakHour = Insights.peakHour(hourTotals),
                    longestFocus = longestFocus,
                    longestUse = longestUse,
                    pickups = pickups,
                    weeks = weeks,
                    outcomes = outcomes,
                    attemptsByHour = Metrics.attemptsByHour(logs.map { l -> l.timestamp }, zone),
                    totalAttempts = logs.size,
                    unlocks = unlockEvents.map { u -> UnlockRow(u.packageName, graph.apps.label(u.packageName), u.kind, u.status, u.grantedAt ?: u.requestedAt, u.reasonText) },
                    rules = rules,
                    recommendation = rec,
                    labels = labels,
                    hasAnyData = logs.isNotEmpty() || sessions.isNotEmpty() || (screen ?: 0L) > 0L,
                )
            }
        }
    }

    private fun ruleName(type: String, name: String): String = when (type) {
        "SESSION" -> graph.context.getString(R.string.name_your_block)
        "BEDTIME" -> graph.context.getString(R.string.name_bedtime)
        "DAILY_LIMIT" -> graph.context.getString(R.string.name_daily_limit)
        else -> name.ifBlank { graph.context.getString(R.string.type_routine) }
    }

    fun apply() {
        val rec = mutable.value.recommendation ?: return
        viewModelScope.launch {
            graph.recommendations.apply(rec)
            graph.requestRefresh()
            mutable.update { it.copy(recommendation = null, applied = true) }
        }
    }

    fun notNow() {
        val rec = mutable.value.recommendation ?: return
        viewModelScope.launch {
            graph.recommendations.dismiss(rec)
            mutable.update { it.copy(recommendation = null) }
        }
    }

    fun label(pkg: String) = mutable.value.labels[pkg] ?: graph.apps.label(pkg)
}
