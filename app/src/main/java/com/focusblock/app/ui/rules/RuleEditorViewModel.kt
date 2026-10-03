package com.focusblock.app.ui.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.R
import com.focusblock.app.blocking.ImportedRuleStore
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.csv
import com.focusblock.app.database.entity.AppLimitEntity
import com.focusblock.app.database.entity.BedtimeModeSettings
import com.focusblock.app.database.entity.FocusCycle
import com.focusblock.app.database.entity.GlobalDailyLimitSettings
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.policy.Strength
import com.focusblock.app.policy.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleDraft(
    val name: String = "",
    val start: Int = 21 * 60,
    val end: Int = 22 * 60 + 30,
    val allDay: Boolean = false,
    val days: Set<Int> = TimeWindow.EVERY_DAY,
    val apps: List<String> = emptyList(),
    val strict: Boolean = false,
    val minutes: Int = 30,
    val countsAll: Boolean = true,
    val windowMinutes: Int = 10,
    val breakMinutes: Int = 30,
)

/** Live picture of a limit: today's use, which apps use it, and the last 7 days (App and Daily limits). */
data class LimitInsight(
    /** Counted use today, or null without Usage access. */
    val usedToday: Long?,
    /** Counted apps used today, most first. */
    val perApp: List<Pair<String, Long>>,
    /** The last 7 days, oldest first, today last; null while history is still loading. */
    val week: List<Pair<java.time.LocalDate, Long>>?,
    /** Days in [week] whose use reached the allowance. */
    val overDays: Int,
    /** Average per day over days with data. */
    val average: Long?,
)

data class EditorUi(
    val loading: Boolean = true,
    val kind: RuleKind = RuleKind.ROUTINE,
    val isNew: Boolean = true,
    val draft: RuleDraft = RuleDraft(),
    /** Strict rule active now, or a legacy/imported lock: cannot be edited (spec 4.7). */
    val lockedUntil: Long? = null,
    val error: Int? = null,
    val saved: Boolean = false,
    val labels: Map<String, String> = emptyMap(),
    val usageAccess: Boolean = true,
    /** On/off for limits (shown as a switch in the editor). */
    val enabled: Boolean = true,
    val insight: LimitInsight? = null,
    /** Allowance as saved (null for a new limit), so the page never says "blocked" for an unsaved change. */
    val savedMinutes: Int? = null,
    /** False for an imported rule without a time window (usage, launches or manual only). */
    val importedTimed: Boolean = true,
    /** A save is running; further taps are ignored. */
    val saving: Boolean = false,
)

/**
 * Editor for every rule type. The draft lives in [SavedStateHandle] (spec 4.7 "draft survives
 * process death"); navigation arguments carry the kind, id and any template pre-fill.
 */
class RuleEditorViewModel(private val graph: AppGraph, private val saved: SavedStateHandle) : ViewModel() {
    val kind: RuleKind = RuleKind.valueOf(saved.get<String>("kind") ?: RuleKind.ROUTINE.name)
    private val id: Long = saved.get<String>("id")?.toLongOrNull() ?: -1L
    private val mutable = MutableStateFlow(EditorUi(kind = kind))
    val state: StateFlow<EditorUi> = mutable.asStateFlow()

    private var schedule: Schedule? = null
    private var limit: AppLimitEntity? = null
    private var daily: GlobalDailyLimitSettings? = null
    private var bedtime: BedtimeModeSettings? = null
    private var cycle: FocusCycle? = null
    private var imported: ImportedRuleStore.Rule? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() {
        val db = graph.db
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        var draft = RuleDraft()
        var isNew = true
        var locked: Long? = null
        when (kind) {
            RuleKind.ROUTINE -> {
                schedule = if (id > 0) db.scheduleDao().getSchedule(id) else null
                schedule?.let { s ->
                    isNew = false
                    draft = RuleDraft(name = s.name, start = s.startTimeMinutes, end = s.endTimeMinutes, allDay = s.startTimeMinutes == s.endTimeMinutes,
                        days = TimeWindow.parseDays(s.daysOfWeek), apps = csv(s.blockedPackages), strict = s.isStrictMode)
                    if (s.isStrictMode && s.isEnabled) {
                        TimeWindow.parseDays(s.daysOfWeek).takeIf { it.isNotEmpty() }?.let { TimeWindow(s.startTimeMinutes, s.endTimeMinutes, it) }
                            ?.occurrenceAt(now, zone)?.let { locked = it.end }
                    }
                } ?: run { draft = template(draft) }
            }
            RuleKind.APP_LIMIT -> {
                limit = if (id > 0) db.appLimitDao().get(id) else null
                limit?.let { l -> isNew = false; draft = RuleDraft(name = l.name, apps = csv(l.packages), minutes = l.minutesPerDay) }
                    ?: run { draft = RuleDraft(name = graph.context.getString(R.string.default_limit_name), minutes = 30) }
            }
            RuleKind.DAILY_LIMIT -> {
                daily = db.globalDailyLimitSettingsDao().getSettingsSync()
                daily?.let { d ->
                    isNew = false
                    draft = RuleDraft(minutes = d.dailyLimitMinutes, countsAll = d.countsAllApps, apps = csv(d.trackedPackages))
                    if (d.isHardModeEnabled && d.hardModeLockUntil > now) locked = d.hardModeLockUntil
                } ?: run { draft = RuleDraft(minutes = 180, countsAll = true) }
            }
            RuleKind.BEDTIME -> {
                bedtime = db.bedtimeModeSettingsDao().getSettingsSync()
                val b = bedtime ?: BedtimeModeSettings()
                isNew = bedtime == null
                draft = RuleDraft(start = b.startHour * 60 + b.startMinute, end = b.endHour * 60 + b.endMinute, days = RuleText.bedtimeDays(b), strict = b.strength == Strength.STRICT.name)
                if (bedtime?.isEnabled == true && b.strength == Strength.STRICT.name) {
                    TimeWindow(draft.start, draft.end, draft.days).occurrenceAt(now, zone)?.let { locked = it.end }
                }
            }
            RuleKind.FOCUS_CYCLE -> {
                cycle = if (id > 0) db.focusCycleDao().getFocusCycle(id) else db.focusCycleDao().getActiveFocusCycleSync()
                val c = cycle
                if (c != null) {
                    isNew = false
                    draft = RuleDraft(name = c.name, windowMinutes = c.usageWindowMinutes.coerceAtLeast(1), breakMinutes = c.breakDurationMinutes.coerceAtLeast(1),
                        apps = csv(c.selectedPackages).ifEmpty { graph.sessions.lastSetup()?.packages ?: emptyList() })
                } else {
                    draft = RuleDraft(name = graph.context.getString(R.string.type_focus_cycle), windowMinutes = 10, breakMinutes = 30,
                        apps = graph.sessions.lastSetup()?.packages ?: emptyList())
                }
            }
            RuleKind.IMPORTED -> {
                imported = runCatching { ImportedRuleStore.rules(db) }.getOrDefault(emptyList()).firstOrNull { it.id == id }
                imported?.let { r ->
                    isNew = false
                    draft = RuleDraft(name = r.name, start = r.start, end = r.end, allDay = r.start == r.end, days = TimeWindow.parseDays(r.days),
                        apps = csv(r.packages), strict = r.commitment.isNotBlank() && r.commitment != "OFF", minutes = r.minutes)
                    if (r.enabled && draft.strict && r.inWindow(now)) locked = now + 60_000
                }
                if (graph.policy.configurationLocked()) locked = now + 60_000
            }
        }
        val restored = restore() ?: draft
        persist(restored)
        val labels = restored.apps.associateWith { graph.apps.label(it) }
        val enabled = when (kind) {
            RuleKind.APP_LIMIT -> limit?.isEnabled ?: true
            RuleKind.DAILY_LIMIT -> daily?.isEnabled ?: true
            else -> true
        }
        val savedMinutes = when (kind) {
            RuleKind.APP_LIMIT -> limit?.minutesPerDay
            RuleKind.DAILY_LIMIT -> daily?.dailyLimitMinutes
            else -> null
        }
        mutable.update {
            it.copy(loading = false, isNew = isNew, draft = restored, lockedUntil = locked, labels = labels, usageAccess = graph.usage.hasAccess(),
                enabled = enabled, savedMinutes = savedMinutes, importedTimed = imported?.let { r -> r.timed && !r.manual } ?: true)
        }
        refreshInsight()
        // Keep "used today" current while the page stays open.
        if (kind == RuleKind.APP_LIMIT || kind == RuleKind.DAILY_LIMIT) {
            viewModelScope.launch {
                while (true) { kotlinx.coroutines.delay(60_000); refreshInsight() }
            }
        }
    }

    /** Turns an existing limit on or off right away (a new one is saved with this state). */
    fun setEnabled(on: Boolean) {
        if (mutable.value.lockedUntil != null) { mutable.update { it.copy(error = R.string.rule_locked_strict) }; return }
        mutable.update { it.copy(enabled = on, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            when (kind) {
                RuleKind.APP_LIMIT -> limit?.let { graph.db.appLimitDao().setEnabled(it.id, on, graph.clock.now()); limit = it.copy(isEnabled = on) }
                RuleKind.DAILY_LIMIT -> daily?.let { graph.db.globalDailyLimitSettingsDao().setEnabled(on); daily = it.copy(isEnabled = on) }
                else -> Unit
            }
            graph.requestRefresh()
        }
    }

    private var insightJob: kotlinx.coroutines.Job? = null

    /**
     * Recomputes the limit's live picture for the apps and scope currently in the draft. Today is
     * published first (one quick query); the 7 days follow once the saved history is read.
     */
    private fun refreshInsight() {
        if (kind != RuleKind.APP_LIMIT && kind != RuleKind.DAILY_LIMIT) return
        insightJob?.cancel()
        insightJob = viewModelScope.launch(Dispatchers.IO) {
            val d = mutable.value.draft
            // Essential apps are never counted (the same set enforcement uses).
            val exempt = graph.policy.exempt()
            val counted: Set<String> = (if (kind == RuleKind.DAILY_LIMIT && d.countsAll) graph.apps.packages() else d.apps.toSet()) - exempt
            val zone = graph.clock.zone()
            val today = com.focusblock.app.policy.PolicyTime.localDate(graph.clock.now(), zone)
            val todayTotals = graph.usage.todayTotals()
            val usedToday = todayTotals?.let { t -> counted.sumOf { t[it] ?: 0L } }
            val perApp = todayTotals?.filterKeys { it in counted }?.filterValues { it >= 30_000L }?.entries
                ?.sortedByDescending { it.value }?.map { it.key to it.value }.orEmpty()
            val missing = perApp.map { it.first }.filter { it !in mutable.value.labels }
            if (missing.isNotEmpty()) {
                val extra = missing.associateWith { graph.apps.label(it) }
                mutable.update { s -> s.copy(labels = s.labels + extra) }
            }
            val previousWeek = mutable.value.insight?.week
            mutable.update { it.copy(insight = LimitInsight(usedToday, perApp, previousWeek, 0, null).withWeek(previousWeek, d.minutes)) }

            val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val history = runCatching { graph.history.days(dates) }.getOrDefault(emptyMap())
            val week = dates.map { date ->
                val used = if (date == today) usedToday
                    else history[date]?.let { u -> counted.sumOf { u.totals[it] ?: 0L } }
                date to (used ?: -1L)
            }
            mutable.update { s -> s.copy(insight = s.insight?.withWeek(week, d.minutes)) }
        }
    }

    /** Adds the 7 days ([week] values below zero mean "no data") with days over and the average. */
    private fun LimitInsight.withWeek(week: List<Pair<java.time.LocalDate, Long>>?, minutes: Int): LimitInsight {
        if (week == null) return copy(week = null)
        val known = week.filter { it.second >= 0 }
        return copy(
            week = week.map { it.first to it.second.coerceAtLeast(0) },
            overDays = known.count { it.second >= minutes * 60_000L },
            average = known.filter { it.second > 0 }.takeIf { it.isNotEmpty() }?.let { k -> k.sumOf { it.second } / k.size },
        )
    }

    /** Templates (Add a rule → Start from a template) and suggestion pre-fill. */
    private fun template(base: RuleDraft): RuleDraft {
        val ctx = graph.context
        val apps = csv(saved.get<String>("apps")).ifEmpty { emptyList() }
        return when (saved.get<String>("template").orEmpty()) {
            "work" -> base.copy(name = ctx.getString(R.string.template_work), start = 9 * 60, end = 17 * 60, days = TimeWindow.WEEKDAYS)
            "evening" -> base.copy(name = ctx.getString(R.string.template_evening), start = 20 * 60 + 45, end = 22 * 60 + 30, days = TimeWindow.EVERY_DAY)
            "morning" -> base.copy(name = ctx.getString(R.string.template_morning), start = 6 * 60, end = 9 * 60, days = TimeWindow.EVERY_DAY)
            "detox" -> base.copy(name = ctx.getString(R.string.template_detox), start = 0, end = 0, allDay = true, days = TimeWindow.WEEKENDS)
            "gap" -> base.copy(
                name = saved.get<String>("name").orEmpty().ifBlank { ctx.getString(R.string.default_routine_name) },
                start = saved.get<String>("start")?.toIntOrNull() ?: base.start,
                end = saved.get<String>("end")?.toIntOrNull() ?: base.end,
                days = TimeWindow.parseDays(saved.get<String>("days").orEmpty()).ifEmpty { TimeWindow.EVERY_DAY },
                apps = apps,
            )
            else -> base.copy(name = ctx.getString(R.string.default_routine_name))
        }.let { d -> if (d.apps.isEmpty()) d.copy(apps = apps) else d }
    }

    fun edit(block: (RuleDraft) -> RuleDraft) {
        val next = block(mutable.value.draft)
        persist(next)
        val labels = HashMap(mutable.value.labels)
        next.apps.forEach { if (it !in labels) labels[it] = graph.apps.label(it) }
        val before = mutable.value.draft
        mutable.update { it.copy(draft = next, error = null, labels = labels) }
        if (before.apps != next.apps || before.countsAll != next.countsAll || before.minutes != next.minutes) refreshInsight()
    }

    fun label(pkg: String) = mutable.value.labels[pkg] ?: graph.apps.label(pkg)

    fun save() {
        val s = mutable.value
        if (s.saving || s.saved) return
        val d = s.draft
        if (s.lockedUntil != null) { mutable.update { it.copy(error = R.string.rule_locked_strict) }; return }
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            // Essential apps are removed first, so a rule of only essential apps is caught below.
            val exempt = graph.policy.exempt()
            val apps = d.apps.filter { it !in exempt }
            val timed = kind != RuleKind.IMPORTED || s.importedTimed
            val error = when {
                (kind == RuleKind.ROUTINE || kind == RuleKind.APP_LIMIT || kind == RuleKind.IMPORTED) && d.name.isBlank() -> R.string.error_name
                (kind == RuleKind.ROUTINE || kind == RuleKind.BEDTIME || kind == RuleKind.IMPORTED) && timed && d.days.isEmpty() -> R.string.error_days
                (kind == RuleKind.ROUTINE || kind == RuleKind.APP_LIMIT || kind == RuleKind.FOCUS_CYCLE || kind == RuleKind.IMPORTED) && apps.isEmpty() ->
                    if (d.apps.isEmpty()) R.string.error_apps else R.string.error_apps_essential
                kind == RuleKind.DAILY_LIMIT && !d.countsAll && apps.isEmpty() -> if (d.apps.isEmpty()) R.string.error_apps else R.string.error_apps_essential
                (kind == RuleKind.APP_LIMIT || kind == RuleKind.DAILY_LIMIT) && d.minutes !in 1..720 -> R.string.error_minutes
                else -> null
            }
            if (error != null) { mutable.update { it.copy(error = error, saving = false) }; return@launch }
            val now = graph.clock.now()
            val start = if (d.allDay) 0 else d.start
            val end = if (d.allDay) 0 else d.end
            when (kind) {
                RuleKind.ROUTINE -> {
                    val base = schedule ?: Schedule(name = d.name, startTimeMinutes = start, endTimeMinutes = end)
                    graph.db.scheduleDao().insert(base.copy(name = d.name.trim(), startTimeMinutes = start, endTimeMinutes = end,
                        daysOfWeek = TimeWindow.formatDays(d.days), blockedPackages = apps.joinToString(","), isStrictMode = d.strict))
                }
                RuleKind.APP_LIMIT -> graph.db.appLimitDao().upsert(
                    (limit ?: AppLimitEntity(name = d.name, packages = "", minutesPerDay = d.minutes))
                        .copy(name = d.name.trim(), packages = apps.joinToString(","), minutesPerDay = d.minutes, isEnabled = s.enabled, updatedAt = now),
                )
                RuleKind.DAILY_LIMIT -> graph.db.globalDailyLimitSettingsDao().insert(
                    (daily ?: GlobalDailyLimitSettings()).copy(isEnabled = s.enabled, dailyLimitMinutes = d.minutes,
                        countsAllApps = d.countsAll, trackedPackages = if (d.countsAll) daily?.trackedPackages.orEmpty() else apps.joinToString(","),
                        useAppTimerApps = false, updatedAt = now),
                )
                RuleKind.BEDTIME -> graph.db.bedtimeModeSettingsDao().insert(
                    (bedtime ?: BedtimeModeSettings()).copy(
                        isEnabled = bedtime?.isEnabled ?: true,
                        startHour = d.start / 60, startMinute = d.start % 60, endHour = d.end / 60, endMinute = d.end % 60,
                        monday = 1 in d.days, tuesday = 2 in d.days, wednesday = 3 in d.days, thursday = 4 in d.days,
                        friday = 5 in d.days, saturday = 6 in d.days, sunday = 7 in d.days,
                        strength = if (d.strict) Strength.STRICT.name else Strength.NORMAL.name, updatedAt = now,
                    ),
                )
                RuleKind.FOCUS_CYCLE -> {
                    val c = cycle
                    if (c == null || !c.isEnabled) graph.db.focusCycleDao().disableAll()
                    val updated = (c ?: FocusCycle()).copy(
                        name = d.name.trim().ifBlank { graph.context.getString(R.string.type_focus_cycle) },
                        usageWindowMinutes = d.windowMinutes, breakDurationMinutes = d.breakMinutes,
                        selectedPackages = apps.joinToString(","), useQuickBlockApps = false, isEnabled = true,
                    )
                    if (c == null) graph.db.focusCycleDao().insert(updated) else graph.db.focusCycleDao().update(updated)
                }
                RuleKind.IMPORTED -> imported?.let { r ->
                    // Rules without a time window keep their stored times and days.
                    val result = runCatching {
                        ImportedRuleStore.save(graph.db, r.copy(name = d.name.trim(), packages = apps.joinToString(","),
                            start = if (timed) start else r.start, end = if (timed) end else r.end,
                            days = if (timed) TimeWindow.formatDays(d.days) else r.days))
                    }
                    result.exceptionOrNull()?.let { e ->
                        // check() failures are locks; require() failures are invalid values.
                        val message = if (e is IllegalStateException) R.string.rule_locked_strict else R.string.error_imported_save
                        mutable.update { it.copy(error = message, saving = false) }
                        return@launch
                    }
                }
            }
            graph.policy.invalidate()
            graph.requestRefresh()
            saved.remove<Any>(KEY)
            mutable.update { it.copy(saved = true, saving = false) }
        }
    }

    fun delete() {
        if (mutable.value.lockedUntil != null) { mutable.update { it.copy(error = R.string.rule_locked_strict) }; return }
        viewModelScope.launch(Dispatchers.IO) {
            when (kind) {
                RuleKind.ROUTINE -> schedule?.let { graph.db.scheduleDao().delete(it) }
                RuleKind.APP_LIMIT -> limit?.let { graph.db.appLimitDao().delete(it.id) }
                RuleKind.DAILY_LIMIT -> daily?.let { graph.db.globalDailyLimitSettingsDao().setEnabled(false) }
                RuleKind.BEDTIME -> bedtime?.let { graph.db.bedtimeModeSettingsDao().setEnabled(false) }
                RuleKind.FOCUS_CYCLE -> cycle?.let { graph.db.focusCycleDao().setEnabled(it.id, false) }
                RuleKind.IMPORTED -> Unit
            }
            graph.requestRefresh()
            saved.remove<Any>(KEY)
            mutable.update { it.copy(saved = true) }
        }
    }

    // Draft persistence (process death).
    private fun persist(d: RuleDraft) {
        saved[KEY] = arrayListOf(d.name, d.start.toString(), d.end.toString(), d.allDay.toString(), TimeWindow.formatDays(d.days),
            d.apps.joinToString(","), d.strict.toString(), d.minutes.toString(), d.countsAll.toString(), d.windowMinutes.toString(), d.breakMinutes.toString())
    }

    private fun restore(): RuleDraft? {
        val v = saved.get<ArrayList<String>>(KEY) ?: return null
        if (v.size < 11) return null
        return RuleDraft(v[0], v[1].toIntOrNull() ?: 0, v[2].toIntOrNull() ?: 0, v[3].toBoolean(), TimeWindow.parseDays(v[4]), csv(v[5]),
            v[6].toBoolean(), v[7].toIntOrNull() ?: 30, v[8].toBoolean(), v[9].toIntOrNull() ?: 10, v[10].toIntOrNull() ?: 30)
    }

    companion object { private const val KEY = "rule_draft" }
}
