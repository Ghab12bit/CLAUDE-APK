package com.focusblock.app.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.blocking.ImportedRuleStore
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.HealthState
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.PolicyRepository
import com.focusblock.app.database.entity.AppLimitEntity
import com.focusblock.app.database.entity.BedtimeModeSettings
import com.focusblock.app.database.entity.FocusCycle
import com.focusblock.app.database.entity.GlobalDailyLimitSettings
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.policy.Coverage
import com.focusblock.app.policy.PolicySnapshot
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.RecommendationEngine
import com.focusblock.app.policy.Strength
import com.focusblock.app.policy.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RulesUi(
    val loading: Boolean = true,
    val schedules: List<Schedule> = emptyList(),
    val limits: List<AppLimitEntity> = emptyList(),
    val daily: GlobalDailyLimitSettings? = null,
    val bedtime: BedtimeModeSettings? = null,
    val cycle: FocusCycle? = null,
    val imported: List<ImportedRuleStore.Rule> = emptyList(),
    val importedLocked: Boolean = false,
    val essentialsCount: Int = 0,
    val snapshot: PolicySnapshot? = null,
    val usageToday: Map<String, Long>? = null,
    val usageAccess: Boolean = true,
    val accessibilityOk: Boolean = true,
    val segments: List<Coverage.Segment> = emptyList(),
    val coveredMinutes: Int = 0,
    val gap: RecommendationEngine.Recommendation? = null,
    val labels: Map<String, String> = emptyMap(),
    val now: Long = System.currentTimeMillis(),
    val message: Int? = null,
)

/** Something deleted that can be restored from the Undo snackbar. */
sealed class Deleted {
    data class Routine(val schedule: Schedule) : Deleted()
    data class Limit(val limit: AppLimitEntity) : Deleted()
}

class RulesViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(RulesUi())
    val state: StateFlow<RulesUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch { graph.db.scheduleDao().getAllSchedules().collect { s -> mutable.update { it.copy(schedules = s) }; refresh() } }
        viewModelScope.launch { graph.db.appLimitDao().allFlow().collect { l -> mutable.update { it.copy(limits = l) }; refresh() } }
        viewModelScope.launch { graph.db.globalDailyLimitSettingsDao().getSettings().collect { d -> mutable.update { it.copy(daily = d) }; refresh() } }
        viewModelScope.launch { graph.db.bedtimeModeSettingsDao().getSettings().collect { b -> mutable.update { it.copy(bedtime = b) }; refresh() } }
        viewModelScope.launch { graph.db.focusCycleDao().getAllFocusCycles().collect { c -> mutable.update { it.copy(cycle = c.firstOrNull { x -> x.isEnabled } ?: c.firstOrNull()) }; refresh() } }
        viewModelScope.launch { graph.essentials.flow().collect { e -> mutable.update { it.copy(essentialsCount = e.size) } } }
        viewModelScope.launch {
            while (isActive) { delay(30_000); refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = graph.clock.now()
            val zone = graph.clock.zone()
            val snap = graph.policy.snapshot()
            val today = PolicyTime.localDate(now, zone)
            val windowed = snap.routines.filter { it.window != null }
            val segments = Coverage.segments(windowed, snap.bedtime, today)
            val since = now - RecommendationEngine.LOOKBACK_DAYS * 24 * 3_600_000L
            val attempts = graph.db.attemptDao().since(since).map { RecommendationEngine.Attempt(it.timestamp, it.packageName) }
            val firstDay = graph.db.attemptDao().firstTimestamp()?.let { PolicyTime.localDate(it, zone) }
            val gap = RecommendationEngine.gap(
                RecommendationEngine.Input(now, zone, firstDay, attempts, emptyList(), emptyMap(), snap.routines, snap.bedtime, snap.appLimits, snap.exempt, emptyMap()),
            )
            val packages = mutable.value.schedules.flatMap { com.focusblock.app.core.csv(it.blockedPackages) } +
                mutable.value.limits.flatMap { com.focusblock.app.core.csv(it.packages) } + (gap?.proposal as? RecommendationEngine.Proposal.AddRoutine)?.packages.orEmpty()
            val labels = HashMap(mutable.value.labels)
            packages.forEach { if (it !in labels) labels[it] = graph.apps.label(it) }
            mutable.update {
                it.copy(
                    loading = false,
                    snapshot = snap,
                    imported = runCatching { ImportedRuleStore.rules(graph.db) }.getOrDefault(emptyList()),
                    importedLocked = graph.policy.configurationLocked(),
                    usageToday = graph.usage.todayTotals(),
                    usageAccess = graph.usage.hasAccess(),
                    accessibilityOk = PermissionHealth.accessibilityState(graph.context) == HealthState.OK,
                    segments = segments,
                    coveredMinutes = Coverage.coveredMinutes(segments),
                    gap = gap,
                    labels = labels,
                    now = now,
                )
            }
        }
    }

    fun label(pkg: String) = mutable.value.labels[pkg] ?: graph.apps.label(pkg)

    /** A Strict Lock rule can't be edited or turned off while its window is active (spec 4.7). */
    fun routineLocked(s: Schedule): Boolean {
        if (!s.isStrictMode || !s.isEnabled) return false
        val days = TimeWindow.parseDays(s.daysOfWeek)
        if (days.isEmpty()) return false
        return TimeWindow(s.startTimeMinutes, s.endTimeMinutes, days).isActive(graph.clock.now(), graph.clock.zone())
    }

    fun bedtimeLocked(b: BedtimeModeSettings): Boolean =
        b.isEnabled && b.strength == Strength.STRICT.name && mutable.value.snapshot?.bedtime?.window?.isActive(graph.clock.now(), graph.clock.zone()) == true

    fun dailyLockedUntil(d: GlobalDailyLimitSettings): Long? = if (d.isHardModeEnabled && d.hardModeLockUntil > graph.clock.now()) d.hardModeLockUntil else null

    fun setRoutineEnabled(s: Schedule, enabled: Boolean) {
        if (routineLocked(s)) { mutable.update { it.copy(message = com.focusblock.app.R.string.rule_locked_strict) }; return }
        viewModelScope.launch { graph.db.scheduleDao().setEnabled(s.id, enabled); graph.requestRefresh() }
    }

    fun deleteRoutine(s: Schedule): Deleted? {
        if (routineLocked(s)) { mutable.update { it.copy(message = com.focusblock.app.R.string.rule_locked_strict) }; return null }
        viewModelScope.launch { graph.db.scheduleDao().delete(s); graph.requestRefresh() }
        return Deleted.Routine(s)
    }

    fun setLimitEnabled(l: AppLimitEntity, enabled: Boolean) {
        viewModelScope.launch { graph.db.appLimitDao().setEnabled(l.id, enabled, graph.clock.now()); graph.requestRefresh() }
    }

    fun deleteLimit(l: AppLimitEntity): Deleted {
        viewModelScope.launch { graph.db.appLimitDao().delete(l.id); graph.requestRefresh() }
        return Deleted.Limit(l)
    }

    fun undo(d: Deleted) {
        viewModelScope.launch {
            when (d) {
                is Deleted.Routine -> graph.db.scheduleDao().insert(d.schedule)
                is Deleted.Limit -> graph.db.appLimitDao().upsert(d.limit)
            }
            graph.requestRefresh()
        }
    }

    fun setDailyEnabled(d: GlobalDailyLimitSettings, enabled: Boolean) {
        if (!enabled && dailyLockedUntil(d) != null) { mutable.update { it.copy(message = com.focusblock.app.R.string.rule_locked_strict) }; return }
        viewModelScope.launch { graph.db.globalDailyLimitSettingsDao().setEnabled(enabled); graph.requestRefresh() }
    }

    fun setBedtimeEnabled(b: BedtimeModeSettings, enabled: Boolean) {
        if (!enabled && bedtimeLocked(b)) { mutable.update { it.copy(message = com.focusblock.app.R.string.rule_locked_strict) }; return }
        viewModelScope.launch { graph.db.bedtimeModeSettingsDao().setEnabled(enabled); graph.requestRefresh() }
    }

    fun setCycleEnabled(c: FocusCycle, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) graph.db.focusCycleDao().disableAll()
            graph.db.focusCycleDao().update(c.copy(isEnabled = enabled, isArmed = true, isActive = false, breakStartTime = null, accumulatedUsageMillis = 0, lastActiveTime = null))
            graph.requestRefresh()
        }
    }

    fun toggleImported(r: ImportedRuleStore.Rule) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ImportedRuleStore.toggle(graph.db, r) }.onFailure { mutable.update { it.copy(message = com.focusblock.app.R.string.rule_locked_strict) } }
            graph.policy.invalidate()
            refresh()
            graph.requestRefresh()
        }
    }

    fun clearMessage() = mutable.update { it.copy(message = null) }

    companion object {
        const val IMPORTED_OFFSET = PolicyRepository.IMPORTED_ID_OFFSET
    }
}
