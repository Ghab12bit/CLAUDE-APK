package com.focusblock.app.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.toReasonType
import com.focusblock.app.database.entity.ExclusionType
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.Metrics
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.RecommendationEngine
import com.focusblock.app.policy.SessionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Period { TODAY, WEEK }

data class AppUsageRow(val pkg: String, val label: String, val millis: Long?, val attempts: Int)
data class UnlockRow(val pkg: String, val label: String, val kind: String, val status: String, val time: Long, val reason: String?)
data class RuleRowStat(val name: String, val attempts: Int, val bypasses: Int) {
    val oftenBypassed: Boolean get() = Metrics.RuleBypass(name, attempts, bypasses).oftenBypassed
}

data class ActivityUi(
    val loading: Boolean = true,
    val period: Period = Period.TODAY,
    val usageAccess: Boolean = true,
    /** Screen time in the period, or null when Usage access is missing (never shown as zero). */
    val screenTime: Long? = null,
    /** Same-time-of-day average over the previous 14 days with data (spec 4.8 baseline). */
    val baseline: Long? = null,
    val outcomes: Metrics.OutcomeCounts = Metrics.OutcomeCounts(0, 0, 0, 0, 0),
    val attemptsByHour: IntArray = IntArray(24),
    val totalAttempts: Int = 0,
    val apps: List<AppUsageRow> = emptyList(),
    val unlocks: List<UnlockRow> = emptyList(),
    val rules: List<RuleRowStat> = emptyList(),
    val recommendation: RecommendationEngine.Recommendation? = null,
    val labels: Map<String, String> = emptyMap(),
    val applied: Boolean = false,
    val hasAnyData: Boolean = false,
)

/**
 * Activity (spec 4.8). Every number comes from logged data, with formulas in docs/metrics.md:
 * screen time from UsageStats events, blocks from block_sessions, attempts from block_logs and
 * unlocks from unlock_events. No focus score; "time saved" is not shown.
 */
class ActivityViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(ActivityUi())
    val state: StateFlow<ActivityUi> = mutable.asStateFlow()

    init { load() }

    fun setPeriod(p: Period) {
        mutable.update { it.copy(period = p, loading = true) }
        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = graph.clock.now()
            val zone = graph.clock.zone()
            val period = mutable.value.period
            val todayStart = PolicyTime.startOfDay(now, zone)
            val from = if (period == Period.TODAY) todayStart else todayStart - 6 * DAY
            val today = PolicyTime.localDate(now, zone)

            // Screen time: foreground time of every app except FocusBlock, launchers, system UI and apps the user excluded.
            val notCounted = graph.safety.launchers() + setOf(graph.context.packageName, "com.android.systemui") +
                graph.db.excludedAppDao().getExcludedPackageNames(ExclusionType.SCREEN_TIME_REPORT)
            val access = graph.usage.hasAccess()
            var screen: Long? = null
            var baseline: Long? = null
            var perApp: Map<String, Long> = emptyMap()
            if (access) {
                val nowMinute = ((now - todayStart) / 60_000L).toInt().coerceIn(0, 1439)
                val days = graph.usage.dailyTotals(14, cutoffMinute = if (period == Period.TODAY) nowMinute else null)
                if (days != null) {
                    val totals = days.mapValues { (_, apps) -> apps.filterKeys { it !in notCounted }.values.sum() }
                    if (period == Period.TODAY) {
                        screen = totals[today] ?: 0L
                        baseline = Metrics.baseline(totals, today)
                        perApp = days[today].orEmpty()
                    } else {
                        val week = (0L..6L).map { today.minusDays(it) }
                        screen = week.sumOf { totals[it] ?: 0L }
                        perApp = week.flatMap { days[it].orEmpty().entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
                    }
                }
            }

            val sessions = graph.db.blockSessionDao().since(from).filter { !it.isActive && it.endReason != "LEGACY" }
            val outcomes = Metrics.outcomes(sessions.map { s -> SessionOutcome.values().firstOrNull { it.name == s.outcome } })
            val logs = graph.db.attemptDao().since(from)
            val attemptsByApp = logs.groupingBy { it.packageName }.eachCount()
            val unlockEvents = graph.db.unlockEventDao().since(from).filter { it.status != UnlockEventEntity.CANCELLED }

            val apps = (perApp.filterKeys { it !in notCounted }.entries.sortedByDescending { it.value }.take(8).map { it.key } +
                attemptsByApp.entries.sortedByDescending { it.value }.take(4).map { it.key }).distinct().take(10).map { pkg ->
                AppUsageRow(pkg, graph.apps.label(pkg), if (access) perApp[pkg] ?: 0L else null, attemptsByApp[pkg] ?: 0)
            }

            // Rules working / bypassed: attempts per rule against unlocks granted for that rule.
            val ruleAttempts = logs.groupBy { (it.blockedBy.toReasonType()?.name ?: "") + "|" + (it.scheduleId ?: 0) + "|" + (it.scheduleName ?: "") }
            val rules = ruleAttempts.map { (key, list) ->
                val (type, id, name) = key.split('|', limit = 3)
                val bypasses = unlockEvents.count { u -> u.status == UnlockEventEntity.GRANTED && u.reasonType == type && (u.ruleId ?: 0L) == (id.toLongOrNull() ?: 0L) }
                RuleRowStat(ruleName(type, name), list.size, bypasses)
            }.sortedByDescending { it.attempts }

            val rec = runCatching { graph.recommendations.current() }.getOrNull()
            val labels = HashMap<String, String>()
            (rec?.proposal as? RecommendationEngine.Proposal.AddRoutine)?.packages?.forEach { labels[it] = graph.apps.label(it) }
            (rec?.proposal as? RecommendationEngine.Proposal.AddAppLimit)?.pkg?.let { labels[it] = graph.apps.label(it) }

            mutable.update {
                it.copy(
                    loading = false,
                    usageAccess = access,
                    screenTime = screen,
                    baseline = baseline,
                    outcomes = outcomes,
                    attemptsByHour = Metrics.attemptsByHour(logs.map { l -> l.timestamp }, zone),
                    totalAttempts = logs.size,
                    apps = apps,
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
        "SESSION" -> graph.context.getString(com.focusblock.app.R.string.name_your_block)
        "BEDTIME" -> graph.context.getString(com.focusblock.app.R.string.name_bedtime)
        "DAILY_LIMIT" -> graph.context.getString(com.focusblock.app.R.string.name_daily_limit)
        else -> name.ifBlank { graph.context.getString(com.focusblock.app.R.string.type_routine) }
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

    companion object {
        private const val DAY = 24 * 3_600_000L
    }
}
