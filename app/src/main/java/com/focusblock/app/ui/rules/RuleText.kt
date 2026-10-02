package com.focusblock.app.ui.rules

import android.content.Context
import com.focusblock.app.R
import com.focusblock.app.blocking.ImportedRuleStore
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.PolicyRepository
import com.focusblock.app.core.csv
import com.focusblock.app.database.entity.AppLimitEntity
import com.focusblock.app.database.entity.BedtimeModeSettings
import com.focusblock.app.database.entity.FocusCycle
import com.focusblock.app.database.entity.GlobalDailyLimitSettings
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.policy.TimeWindow
import com.focusblock.app.ui.components.ChipKind
import java.time.Instant
import java.time.ZoneId

/** Rule kinds that open the editor. */
enum class RuleKind { ROUTINE, APP_LIMIT, DAILY_LIMIT, BEDTIME, FOCUS_CYCLE, IMPORTED }

/** Summaries follow the spec 3.2 template: "Blocks 4 apps from 8:45 PM to 10:30 PM on weekdays". */
object RuleText {
    fun window(context: Context, appsPhrase: String, start: Int, end: Int, days: Set<Int>): String =
        if (start == end) context.getString(R.string.rule_summary_all_day, appsPhrase, Fmt.days(context, days))
        else context.getString(R.string.rule_summary_window, appsPhrase, Fmt.minuteOfDay(context, start), Fmt.minuteOfDay(context, end), Fmt.days(context, days))

    fun routine(context: Context, s: Schedule, label: (String) -> String): String =
        window(context, Fmt.apps(context, csv(s.blockedPackages), label), s.startTimeMinutes, s.endTimeMinutes, TimeWindow.parseDays(s.daysOfWeek))

    fun appLimit(context: Context, l: AppLimitEntity, label: (String) -> String): String =
        context.getString(R.string.limit_summary_app, Fmt.apps(context, csv(l.packages), label), Fmt.minutes(context, l.minutesPerDay))

    fun daily(context: Context, d: GlobalDailyLimitSettings, label: (String) -> String): String =
        if (d.countsAllApps) context.getString(R.string.limit_summary_daily_all, Fmt.minutes(context, d.dailyLimitMinutes))
        else context.getString(R.string.limit_summary_daily_list, Fmt.apps(context, csv(d.trackedPackages), label), Fmt.minutes(context, d.dailyLimitMinutes))

    fun bedtimeDays(b: BedtimeModeSettings): Set<Int> = buildSet {
        if (b.monday) add(1); if (b.tuesday) add(2); if (b.wednesday) add(3); if (b.thursday) add(4)
        if (b.friday) add(5); if (b.saturday) add(6); if (b.sunday) add(7)
    }

    fun bedtime(context: Context, b: BedtimeModeSettings): String = context.getString(
        R.string.bedtime_summary, Fmt.minuteOfDay(context, b.startHour * 60 + b.startMinute), Fmt.minuteOfDay(context, b.endHour * 60 + b.endMinute), Fmt.days(context, bedtimeDays(b)),
    )

    fun cycle(context: Context, c: FocusCycle, appsPhrase: String): String = context.getString(
        R.string.focus_cycle_summary,
        Fmt.duration(context, PolicyRepository.focusCycleMillis(c.usageWindowMinutes)),
        appsPhrase,
        Fmt.duration(context, PolicyRepository.focusCycleMillis(c.breakDurationMinutes)),
    )

    fun imported(context: Context, r: ImportedRuleStore.Rule, label: (String) -> String): String {
        val apps = Fmt.apps(context, csv(r.packages), label)
        val base = if (r.timed && !r.manual) window(context, apps, r.start, r.end, TimeWindow.parseDays(r.days)) else apps
        return context.getString(R.string.imported_summary, base)
    }

    /** "Next at 8:45 PM" or "Next Tue at 9:00 AM". */
    fun next(context: Context, at: Long): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return if (date == java.time.LocalDate.now(zone)) context.getString(R.string.state_next_at, Fmt.time(context, at))
        else context.getString(R.string.state_next_day_at, Fmt.dayShort(context, date.dayOfWeek.value), Fmt.time(context, at))
    }

    data class State(val kind: ChipKind, val text: String)

    fun windowState(context: Context, enabled: Boolean, window: TimeWindow?, now: Long, broken: String?): State {
        if (!enabled) return State(ChipKind.OFF, context.getString(R.string.state_off))
        if (broken != null) return State(ChipKind.BROKEN, context.getString(R.string.state_not_working, broken))
        val zone = ZoneId.systemDefault()
        window?.occurrenceAt(now, zone)?.let { return State(ChipKind.ACTIVE, context.getString(R.string.state_active_until, Fmt.time(context, it.end))) }
        val next = window?.nextStartAfter(now, zone) ?: return State(ChipKind.OFF, context.getString(R.string.state_off))
        return State(ChipKind.NEXT, next(context, next))
    }

    fun limitState(context: Context, enabled: Boolean, used: Long?, limitMinutes: Int, broken: String?): State {
        if (!enabled) return State(ChipKind.OFF, context.getString(R.string.state_off))
        if (broken != null || used == null) return State(ChipKind.BROKEN, context.getString(R.string.state_not_working, broken ?: context.getString(R.string.cause_usage_off)))
        if (used >= limitMinutes * 60_000L) return State(ChipKind.ACTIVE, context.getString(R.string.state_reached))
        return State(ChipKind.NEXT, context.getString(R.string.state_used, Fmt.duration(context, used), Fmt.minutes(context, limitMinutes)))
    }
}
