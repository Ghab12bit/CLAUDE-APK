package com.focusblock.app.core

import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.policy.PolicyTime
import java.time.LocalDate

/**
 * Steps the daily limit down toward a target on its own (Rules › Daily limit › Step down).
 *
 * A 55% cut in one go fails; a taper sticks. Once a day (from the 9 PM worker) yesterday is
 * compared with the limit it had. [DAYS_PER_STEP] days under in a row lower the limit by one step;
 * a day over just holds, it never punishes. The limit never goes below the target, and the taper
 * stops there. State lives in the settings table, so no schema change.
 */
class LimitTaper(
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
    private val usage: UsageRepository,
    private val history: UsageHistory,
    private val exempt: suspend () -> Set<String>,
    private val notifier: Notifier,
) {
    data class Config(val targetMinutes: Int, val stepMinutes: Int) {
        val on: Boolean get() = targetMinutes > 0
    }

    suspend fun config(): Config {
        val s = db.settingsDao()
        return Config(
            targetMinutes = s.getValue(PrefKeys.TAPER_TARGET)?.toIntOrNull() ?: 0,
            stepMinutes = s.getValue(PrefKeys.TAPER_STEP)?.toIntOrNull()?.coerceIn(5, 60) ?: DEFAULT_STEP,
        )
    }

    /** Days under the current limit in a row, for the Rules screen. */
    suspend fun underStreak(): Int = db.settingsDao().getValue(PrefKeys.TAPER_UNDER_STREAK)?.toIntOrNull() ?: 0

    /**
     * Turn the taper on. The starting limit is 75% of the last 7 days' typical use (rounded to 5,
     * never below the target), or the current limit if that is already lower.
     */
    suspend fun start(targetMinutes: Int, stepMinutes: Int): Int? {
        val dao = db.globalDailyLimitSettingsDao()
        val d = dao.getSettingsSync() ?: return null
        val s = db.settingsDao()
        val target = targetMinutes.coerceIn(MIN_TARGET, 24 * 60)
        s.insert(AppSettings(PrefKeys.TAPER_TARGET, target.toString()))
        s.insert(AppSettings(PrefKeys.TAPER_STEP, stepMinutes.coerceIn(5, 60).toString()))
        s.insert(AppSettings(PrefKeys.TAPER_UNDER_STREAK, "0"))
        s.insert(AppSettings(PrefKeys.TAPER_LAST_DAY, ""))
        val typical = typicalMinutes(d)
        val fromUsage = typical?.let { maxOf(target, (it * 3 / 4) / 5 * 5) }
        val start = listOfNotNull(d.dailyLimitMinutes, fromUsage).min().coerceAtLeast(target)
        if (start != d.dailyLimitMinutes) dao.setDailyLimit(start, clock.now())
        if (!d.isEnabled) dao.setEnabled(true, clock.now())
        return start
    }

    suspend fun stop() {
        db.settingsDao().insert(AppSettings(PrefKeys.TAPER_TARGET, "0"))
    }

    suspend fun setStep(stepMinutes: Int) {
        db.settingsDao().insert(AppSettings(PrefKeys.TAPER_STEP, stepMinutes.coerceIn(5, 60).toString()))
    }

    /** Once a day: compare yesterday with the limit and step down when earned. Safe to call more often. */
    suspend fun evaluate(now: Long = clock.now()): Int? {
        val cfg = config()
        if (!cfg.on) return null
        val dao = db.globalDailyLimitSettingsDao()
        val d = dao.getSettingsSync()?.takeIf { it.isEnabled } ?: return null
        if (d.dailyLimitMinutes <= cfg.targetMinutes) return null
        val s = db.settingsDao()
        val zone = clock.zone()
        val yesterday = PolicyTime.localDate(now, zone).minusDays(1)
        if (s.getValue(PrefKeys.TAPER_LAST_DAY) == yesterday.toString()) return null
        val totals = usage.dailyTotals(1) ?: return null
        val day = totals[yesterday] ?: return null
        val usedMinutes = (countedMillis(d, day) / 60_000L).toInt()
        val streak = if (usedMinutes <= d.dailyLimitMinutes) underStreak() + 1 else 0
        s.insert(AppSettings(PrefKeys.TAPER_LAST_DAY, yesterday.toString()))
        if (streak < DAYS_PER_STEP) {
            s.insert(AppSettings(PrefKeys.TAPER_UNDER_STREAK, streak.toString()))
            return null
        }
        val next = maxOf(cfg.targetMinutes, d.dailyLimitMinutes - cfg.stepMinutes)
        dao.setDailyLimit(next, now)
        s.insert(AppSettings(PrefKeys.TAPER_UNDER_STREAK, "0"))
        notifier.showLimitStepped(next, cfg.targetMinutes, reached = next <= cfg.targetMinutes)
        return next
    }

    /** Median of the last 7 complete days' counted minutes; null without usage data. */
    private suspend fun typicalMinutes(d: com.focusblock.app.database.entity.GlobalDailyLimitSettings): Int? {
        val totals = usage.dailyTotals(7) ?: return null
        val today = PolicyTime.localDate(clock.now(), clock.zone())
        val days = totals.filterKeys { it.isBefore(today) }.values.map { (countedMillis(d, it) / 60_000L).toInt() }.filter { it > 0 }.sorted()
        if (days.isEmpty()) return null
        return days[days.size / 2]
    }

    /** The same apps the daily limit counts: everything but exempt and uncounted, or the chosen list. */
    private suspend fun countedMillis(d: com.focusblock.app.database.entity.GlobalDailyLimitSettings, day: Map<String, Long>): Long {
        val exempt = exempt()
        val notCounted = history.notCounted()
        return if (d.countsAllApps) day.filterKeys { it !in exempt && it !in notCounted }.values.sum()
        else {
            val tracked = d.trackedPackages.split(',').filter { it.isNotBlank() }.toSet() - exempt
            day.filterKeys { it in tracked }.values.sum()
        }
    }

    companion object {
        const val DEFAULT_STEP = 10
        const val DAYS_PER_STEP = 2
        const val MIN_TARGET = 30
    }
}
