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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
/** One week of Trend: its total, average per day ([Metrics.dailyAverage]) and change from the week before. */
data class WeekStat(val from: LocalDate, val to: LocalDate, val total: Long, val average: Long?, val change: Int?)

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
    /** Day: usual screen time by this time of day (2-week average). */
    val compareTo: Long? = null,
    /** Week and Trend: average per day over days with data ([Metrics.dailyAverage]). */
    val dailyAverage: Long? = null,
    /** Week: change in average per day from the week before. Trend: the latest week's change ([Metrics.averageChange]). */
    val change: Int? = null,
    val chart: List<LongArray> = emptyList(),
    val chartLabels: List<String?> = emptyList(),
    val chartAverage: Long? = null,
    val chartDescription: String = "",
    val apps: List<AppStat> = emptyList(),
    val byCategory: LongArray = LongArray(3),
    val shares: IntArray = IntArray(3),
    val balancePercent: Int = 0,
    /** Days the per-day averages are taken over (1 for Day). */
    val daysInRange: Int = 1,
    val hourTotals: LongArray = LongArray(24),
    val peakHour: Int? = null,
    val longestFocus: Long = 0,
    val longestUse: Long = 0,
    /** Pickups in the range, over the days that recorded them. */
    val pickups: Int = 0,
    /** Week and Trend: average pickups per day over the days that recorded them. */
    val pickupsPerDay: Int? = null,
    val weeks: List<WeekStat> = emptyList(),
    /** Apps the user chose not to count, with their time in the range. */
    val excludedApps: List<AppStat> = emptyList(),
    /** The day on screen only has daily totals (from an earlier version), no hourly detail. */
    val hourlyMissing: Boolean = false,
    /** Pickups, continuous use and focus are unknown for this range (no saved detail). */
    val detailMissing: Boolean = false,
    /** Some days in the range (from an earlier version) have no pickups, continuous use or focus. */
    val detailPartial: Boolean = false,
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

    /** The load in progress. A newer one cancels it, so an older range never lands last. */
    private var loadJob: Job? = null

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

    /** Count or stop counting an app in screen time (e.g. a clock used as a stopwatch). */
    fun setCounted(pkg: String, label: String, counted: Boolean) {
        viewModelScope.launch {
            graph.history.setCounted(pkg, label, counted)
            // Limits stop (or start) counting this app: re-check the open app, notification and widget now.
            graph.requestRefresh()
            load()
        }
    }

    fun setCategory(pkg: String, category: AppCategory) {
        viewModelScope.launch {
            graph.categories.set(pkg, category)
            load()
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val ctx = graph.context
            val now = graph.clock.now()
            val zone = graph.clock.zone()
            val today = PolicyTime.localDate(now, zone)
            val period = mutable.value.period
            // History reaches back as far as any source has data (up to about a year).
            val earliest = graph.history.earliestDate() ?: today
            val historyDays = java.time.temporal.ChronoUnit.DAYS.between(earliest, today).toInt().coerceIn(0, 365)
            val maxOffset = when (period) { Period.DAY -> historyDays; Period.WEEK -> historyDays / 7; Period.TREND -> historyDays / 28 }
            val offset = mutable.value.offset.coerceIn(0, maxOffset)
            graph.categories.load()
            val categoryOf: (String) -> AppCategory = graph.categories::categoryOf

            // The range on screen.
            val dates: List<LocalDate> = when (period) {
                Period.DAY -> listOf(today.minusDays(offset.toLong()))
                Period.WEEK -> (6 downTo 0).map { today.minusDays(it + 7L * offset) }
                Period.TREND -> (27 downTo 0).map { today.minusDays(it + 28L * offset) }
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
                Period.TREND -> if (offset == 0) ctx.getString(R.string.range_last_4_weeks)
                    else ctx.getString(R.string.range_dates, Fmt.dayMonth(dates.first()), Fmt.dayMonth(dates.last()))
            }

            // Screen time: every app except FocusBlock, home-screen launchers, System UI and apps the user excluded.
            val notCounted = graph.history.notCounted()
            val userExcluded = graph.history.userExcluded()
            val counted: (String) -> Boolean = { it !in notCounted }
            val access = graph.usage.hasAccess()
            // Days used for comparisons: the 14 days before (Day) or the week before (Week).
            val extra = when (period) {
                Period.DAY -> (14 downTo 1).map { dates.first().minusDays(it.toLong()) }
                Period.WEEK -> dates.map { it.minusDays(7) }
                Period.TREND -> emptyList()
            }
            val byDate = graph.history.days(dates + extra)
            val inRange = dates.mapNotNull { byDate[it] }

            var screen: Long? = null
            var comparison: Long? = null
            var dailyAverage: Long? = null
            var change: Int? = null
            var chart: List<LongArray> = emptyList()
            var chartLabels: List<String?> = emptyList()
            var chartAverage: Long? = null
            var apps: List<AppStat> = emptyList()
            var excludedApps: List<AppStat> = emptyList()
            var byCategory = LongArray(3)
            var hourTotals = LongArray(24)
            var longestFocus = 0L
            var longestUse = 0L
            var pickups = 0
            var pickupsPerDay: Int? = null
            var weeks: List<WeekStat> = emptyList()
            var daysInRange = dates.size
            var hourlyMissing = false

            if (access || inRange.isNotEmpty()) {
                val dayTotals = dates.map { d -> byDate[d]?.total(counted) ?: 0L }
                val rangeTotals = dates.zip(dayTotals).toMap()
                when (period) {
                    Period.DAY -> {
                        val day = byDate[dates.first()]
                        chart = day?.let { Insights.hourStack(it, categoryOf, counted) } ?: List(24) { LongArray(3) }
                        hourlyMissing = day != null && day.hourly == null
                        chartLabels = List(24) { h -> if (h % 6 == 0) Fmt.hourShort(ctx, h) else null }
                        // Usual screen time: today is compared at the same time of day; past days whole.
                        // Whether a day has data is decided from its whole day, so days with no use yet by now count as zero.
                        val cutoff = if (offset == 0) ((now - from) / 60_000L).toInt().coerceIn(0, 1439) else null
                        val window = (14 downTo 0).map { dates.first().minusDays(it.toLong()) }
                        val full = window.associateWith { d -> byDate[d]?.total(counted) ?: 0L }
                        val totals = if (cutoff == null) full else window.associateWith { d -> byDate[d]?.let { Insights.totalBefore(it, cutoff, counted) } ?: 0L }
                        comparison = Metrics.baseline(totals, dates.first(), full)
                        daysInRange = 1
                    }
                    Period.WEEK -> {
                        chart = dates.map { d -> byDate[d]?.let { Insights.dayStack(it, categoryOf, counted) } ?: LongArray(3) }
                        chartLabels = dates.map { if (it == today) ctx.getString(R.string.range_today_short) else Fmt.weekdayShort(it) }
                        // One average per day for the hero, the dashed line and Balance: days with data, today only when alone.
                        dailyAverage = Metrics.dailyAverage(rangeTotals, today)
                        chartAverage = dailyAverage
                        change = Metrics.averageChange(rangeTotals, extra.associateWith { byDate[it]?.total(counted) ?: 0L }, today)
                        daysInRange = Metrics.averagedDays(rangeTotals, today).size.coerceAtLeast(1)
                    }
                    Period.TREND -> {
                        chart = dates.map { d -> byDate[d]?.let { Insights.dayStack(it, categoryOf, counted) } ?: LongArray(3) }
                        chartLabels = dates.mapIndexed { i, d -> if ((dates.size - 1 - i) % 7 == 0) Fmt.dayMonth(d) else null }
                        dailyAverage = Metrics.dailyAverage(rangeTotals, today)
                        chartAverage = dailyAverage
                        // Four weeks, newest first, each compared with the week before it (average per day).
                        val weekSlices = (0 until 4).map { w ->
                            dates.subList(dates.size - 7 * (w + 1), dates.size - 7 * w).associateWith { rangeTotals[it] ?: 0L }
                        }
                        weeks = weekSlices.mapIndexed { i, slice ->
                            val prev = weekSlices.getOrNull(i + 1)
                            WeekStat(slice.keys.first(), slice.keys.last(), slice.values.sum(), Metrics.dailyAverage(slice, today),
                                prev?.let { Metrics.averageChange(slice, it, today) })
                        }
                        change = weeks.firstOrNull()?.change
                        daysInRange = Metrics.averagedDays(rangeTotals, today).size.coerceAtLeast(1)
                    }
                }

                screen = dayTotals.sum()
                byCategory = LongArray(3).also { sum -> inRange.forEach { d -> Insights.dayStack(d, categoryOf, counted).forEachIndexed { c, v -> sum[c] += v } } }
                hourTotals = Insights.hourOfDay(inRange, counted)
                // Older days (from an earlier version) have no pickups: total and average cover the days that do.
                val recorded = inRange.mapNotNull { d -> d.pickups?.let { d.date to it.toLong() } }.toMap()
                pickups = recorded.values.sum().toInt()
                pickupsPerDay = Metrics.dailyAverage(recorded, today)?.toInt()
                // Continuous use spans every foreground app (the launcher and FocusBlock too, so switching
                // apps does not break a stretch) and gaps up to a minute, so on a day of little counted use
                // it could exceed screen time. Each day's value is capped at that day's screen time.
                longestUse = inRange.mapNotNull { d -> d.longestUse?.let { minOf(it, d.total(counted)) } }.maxOrNull() ?: 0L
                longestFocus = inRange.mapNotNull { it.longestFocus }.maxOrNull() ?: 0L

                val perApp = HashMap<String, Long>()
                val opens = HashMap<String, Int>()
                inRange.forEach { d ->
                    d.totals.forEach { (pkg, ms) -> perApp[pkg] = (perApp[pkg] ?: 0L) + ms }
                    d.opens.forEach { (pkg, n) -> opens[pkg] = (opens[pkg] ?: 0) + n }
                }
                val attemptsByApp = graph.db.attemptDao().since(from).filter { it.timestamp < to }.groupingBy { it.packageName }.eachCount()
                apps = perApp.entries.filter { counted(it.key) && it.value >= 60_000L }.sortedByDescending { it.value }.take(20).map { (pkg, ms) ->
                    AppStat(pkg, graph.apps.label(pkg), ms, categoryOf(pkg), opens[pkg] ?: 0, attemptsByApp[pkg] ?: 0)
                }
                excludedApps = userExcluded.map { pkg ->
                    AppStat(pkg, graph.apps.label(pkg), perApp[pkg] ?: 0L, categoryOf(pkg), opens[pkg] ?: 0, attemptsByApp[pkg] ?: 0)
                }.sortedByDescending { it.millis }
            }
            val detailMissing = inRange.isNotEmpty() && inRange.all { it.pickups == null }
            val detailPartial = !detailMissing && inRange.any { it.pickups == null }

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

            // A peak needs at least a minute of use; seconds would name an hour next to "0 min" of screen time.
            val peakHour = Insights.peakHour(hourTotals)?.takeIf { hourTotals[it] >= 60_000L }

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
            val unlocks = unlockEvents.map { u -> UnlockRow(u.packageName, graph.apps.label(u.packageName), u.kind, u.status, u.grantedAt ?: u.requestedAt, u.reasonText) }
            // Balance is per day: the day's screen time, or the average per day of a longer range.
            val balance = Insights.balancePercent(if (period == Period.DAY) screen ?: 0L else dailyAverage ?: 0L, 1)

            val next: (ActivityUi) -> ActivityUi = {
                it.copy(
                    loading = false,
                    offset = offset,
                    maxOffset = maxOffset,
                    rangeTitle = title,
                    usageAccess = access,
                    screenTime = screen,
                    compareTo = comparison,
                    dailyAverage = dailyAverage,
                    change = change,
                    chart = chart,
                    chartLabels = chartLabels,
                    chartAverage = chartAverage,
                    chartDescription = description,
                    apps = apps,
                    byCategory = byCategory,
                    shares = Insights.sharePercents(byCategory),
                    balancePercent = balance,
                    daysInRange = daysInRange,
                    hourTotals = hourTotals,
                    peakHour = peakHour,
                    longestFocus = longestFocus,
                    longestUse = longestUse,
                    pickups = pickups,
                    pickupsPerDay = pickupsPerDay,
                    weeks = weeks,
                    excludedApps = excludedApps,
                    hourlyMissing = hourlyMissing,
                    detailMissing = detailMissing,
                    detailPartial = detailPartial,
                    outcomes = outcomes,
                    attemptsByHour = Metrics.attemptsByHour(logs.map { l -> l.timestamp }, zone),
                    totalAttempts = logs.size,
                    unlocks = unlocks,
                    rules = rules,
                    recommendation = rec,
                    labels = labels,
                    hasAnyData = logs.isNotEmpty() || sessions.isNotEmpty() || (screen ?: 0L) > 0L,
                )
            }
            // Written on the main thread, where a newer load cancels this one, so a cancelled load never writes.
            withContext(Dispatchers.Main) { mutable.update(next) }
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
