package com.focusblock.app.core

import androidx.room.InvalidationTracker
import com.focusblock.app.blocking.ImportedRuleStore
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.BedtimeModeSettings
import com.focusblock.app.database.entity.BlockSessionEntity
import com.focusblock.app.database.entity.FocusCycle
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.policy.AppLimitInput
import com.focusblock.app.policy.BedtimeInput
import com.focusblock.app.policy.DailyLimitInput
import com.focusblock.app.policy.FocusCycleInput
import com.focusblock.app.policy.OverrideInput
import com.focusblock.app.policy.OverrideKind
import com.focusblock.app.policy.PolicySnapshot
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.RoutineInput
import com.focusblock.app.policy.Strength
import com.focusblock.app.policy.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Builds the engine's [PolicySnapshot] from persisted state. Room is the source of truth; nothing
 * here is held as UI state. Static parts are cached until a relevant table changes; usage is cached
 * by [UsageRepository]; the session's trusted clock is recomputed on every call.
 */
class PolicyRepository(
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
    private val sessions: SessionManager,
    private val usage: UsageRepository,
    private val apps: InstalledApps,
    private val safety: SafetyApps,
) {
    private data class Static(
        val at: Long,
        val session: BlockSessionEntity?,
        val schedules: List<Schedule>,
        val bedtime: BedtimeModeSettings?,
        val limits: List<com.focusblock.app.database.entity.AppLimitEntity>,
        val daily: com.focusblock.app.database.entity.GlobalDailyLimitSettings?,
        val cycle: FocusCycle?,
        val cyclePackages: Set<String>,
        val essentials: Set<String>,
        val imported: List<ImportedRuleStore.Rule>,
        val importedLock: Boolean,
    )

    private val mutex = Mutex()
    @Volatile private var dirty = true
    @Volatile private var cached: Static? = null

    init {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            arrayOf(
                "block_sessions", "schedules", "bedtime_mode_settings", "app_limits", "global_daily_limit_settings",
                "focus_cycles", "unlock_events", "essential_apps", "settings",
            ),
        ) {
            override fun onInvalidated(tables: Set<String>) { dirty = true }
        })
    }

    fun invalidate() { dirty = true }

    private suspend fun static(): Static = mutex.withLock {
        val now = clock.now()
        val current = cached
        // Imported v13–15 rules live outside Room's invalidation tracker: refresh them every 15 s.
        if (current != null && !dirty && now - current.at < 15_000) return@withLock current
        dirty = false
        val cycle = db.focusCycleDao().getActiveFocusCycleSync()
        val cyclePackages = when {
            cycle == null -> emptySet()
            cycle.selectedPackages.isNotBlank() && !cycle.useQuickBlockApps -> csv(cycle.selectedPackages).toSet()
            else -> (sessions.lastSetup()?.packages ?: csv(cycle.selectedPackages)).toSet()
        }
        val fresh = Static(
            at = now,
            session = sessions.active(),
            schedules = db.scheduleDao().getEnabledSchedulesSync(),
            bedtime = db.bedtimeModeSettingsDao().getSettingsSync(),
            limits = db.appLimitDao().all(),
            daily = db.globalDailyLimitSettingsDao().getSettingsSync(),
            cycle = cycle,
            cyclePackages = cyclePackages,
            essentials = db.essentialAppDao().packages().toSet(),
            imported = runCatching { ImportedRuleStore.rules(db) }.getOrDefault(emptyList()),
            importedLock = runCatching { ImportedRuleStore.configurationLocked(db) }.getOrDefault(false),
        )
        cached = fresh
        fresh
    }

    /** Safety exemptions plus the user's essential apps. */
    suspend fun exempt(): Set<String> = withContext(Dispatchers.IO) { safety.packages() + static().essentials }

    /**
     * The full snapshot. [freshUsage] forces a usage re-query; the service uses it when a limit is
     * predicted to run out while an app is open.
     */
    suspend fun snapshot(freshUsage: Boolean = false): PolicySnapshot = withContext(Dispatchers.IO) {
        val s = static()
        val now = clock.now()
        val zone = clock.zone()
        val exempt = safety.packages() + s.essentials
        val needsUsage = s.limits.any { it.isEnabled } || s.daily?.isEnabled == true ||
            s.imported.any { it.enabled && (it.usage || it.launches) }
        val today = if (needsUsage) usage.todayTotals(if (freshUsage) 0 else 30_000) else null
        val launchable by lazy { apps.packages() - exempt }

        val routines = ArrayList<RoutineInput>()
        s.schedules.forEach { sch ->
            val days = TimeWindow.parseDays(sch.daysOfWeek)
            if (days.isEmpty() || sch.startTimeMinutes !in 0..1439 || sch.endTimeMinutes !in 0..1439) return@forEach
            routines += RoutineInput(
                id = sch.id, name = sch.name, packages = csv(sch.blockedPackages).toSet(),
                window = TimeWindow(sch.startTimeMinutes, sch.endTimeMinutes, days),
                strength = if (sch.isStrictMode) Strength.STRICT else Strength.NORMAL, enabled = sch.isEnabled,
            )
        }
        s.imported.forEach { r -> importedRoutine(r, now, today)?.let(routines::add) }

        val bedtime = s.bedtime?.let { b ->
            val days = buildSet {
                if (b.monday) add(1); if (b.tuesday) add(2); if (b.wednesday) add(3); if (b.thursday) add(4)
                if (b.friday) add(5); if (b.saturday) add(6); if (b.sunday) add(7)
            }
            BedtimeInput(
                enabled = b.isEnabled && days.isNotEmpty(),
                window = TimeWindow((b.startHour * 60 + b.startMinute).coerceIn(0, 1439), (b.endHour * 60 + b.endMinute).coerceIn(0, 1439), days),
                strength = if (b.strength == Strength.STRICT.name) Strength.STRICT else Strength.NORMAL,
                scope = launchable,
            )
        }

        val limits = s.limits.map { l ->
            val packages = csv(l.packages).toSet()
            AppLimitInput(l.id, l.name, packages, l.minutesPerDay, packages.sumOf { today?.get(it) ?: 0L }, l.isEnabled && today != null)
        }

        val daily = s.daily?.let { d ->
            val counted = if (d.countsAllApps) launchable else csv(d.trackedPackages).toSet()
            DailyLimitInput(
                enabled = d.isEnabled && today != null,
                limitMinutes = d.dailyLimitMinutes,
                countedPackages = counted,
                usedMillisToday = counted.sumOf { today?.get(it) ?: 0L },
                lockedUntil = if (d.isHardModeEnabled) d.hardModeLockUntil else 0L,
            )
        }

        val cycles = s.cycle?.let { c ->
            val breakEnds = c.breakStartTime?.let { it + focusCycleMillis(c.breakDurationMinutes) }
            listOf(FocusCycleInput(c.id, c.name, s.cyclePackages, c.isEnabled, breakEnds))
        }.orEmpty()

        val overrides = db.unlockEventDao().activeOverrides(now).map {
            OverrideInput(it.packageName, if (it.kind == OverrideKind.EMERGENCY.name) OverrideKind.EMERGENCY else OverrideKind.OPEN_ANYWAY, it.expiresAt ?: 0L)
        }

        PolicySnapshot(
            session = s.session?.let(sessions::toInput),
            routines = routines,
            bedtime = bedtime,
            appLimits = limits,
            dailyLimit = daily,
            focusCycles = cycles,
            overrides = overrides,
            exempt = exempt,
        ).also { if (PolicyTime.localDate(now, zone) != PolicyTime.localDate(s.at, zone)) dirty = true }
    }

    /** Imported v13–15 rules: time windows, manual activation, and combined usage/launch conditions. */
    private suspend fun importedRoutine(r: ImportedRuleStore.Rule, now: Long, today: Map<String, Long>?): RoutineInput? {
        if (!r.enabled) return null
        val packages = csv(r.packages).toSet()
        if (packages.isEmpty()) return null
        val zone = clock.zone()
        val window = if (r.timed && !r.manual) {
            val days = TimeWindow.parseDays(r.days)
            if (days.isEmpty() || r.start !in 0..1439 || r.end !in 0..1439) return null
            TimeWindow(r.start, r.end, days)
        } else null
        var met = true
        var until: Long? = null
        if (r.manual) {
            met = r.manualActive && (r.until == null || now < r.until)
            until = r.until
        }
        if (r.usage) {
            val totals = if (r.hourly) usage.windowTotals(now - now % 3_600_000L, now) else today
            met = met && totals != null && packages.sumOf { totals[it] ?: 0L } >= r.minutes * 60_000L
            if (window == null && !r.manual) until = if (r.hourly) now - now % 3_600_000L + 3_600_000L else PolicyTime.nextMidnight(now, zone)
        }
        if (r.launches) {
            val from = if (r.launchHourly) now - now % 3_600_000L else PolicyTime.startOfDay(now, zone)
            val counts = usage.launches(from, now)
            met = met && counts != null && packages.sumOf { counts[it] ?: 0 } >= r.launchLimit
            if (window == null && !r.manual) until = if (r.launchHourly) from + 3_600_000L else PolicyTime.nextMidnight(now, zone)
        }
        return RoutineInput(
            id = IMPORTED_ID_OFFSET + r.id, name = r.name, packages = packages, window = window,
            strength = if (r.commitment.isNotBlank() && r.commitment != "OFF") Strength.STRICT else Strength.NORMAL,
            enabled = true, conditionsMet = met, activeUntil = until, imported = true,
        )
    }

    suspend fun configurationLocked(): Boolean = static().importedLock

    companion object {
        const val IMPORTED_ID_OFFSET = 1_000_000L

        /** Legacy Focus Cycle values: positive = minutes, negative = seconds (testing). */
        fun focusCycleMillis(value: Int): Long = if (value < 0) -value * 1000L else value * 60_000L
    }
}
