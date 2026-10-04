package com.focusblock.app.policy

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Evidence-based suggestions (spec 8.6). Generated only from logged data, shown one at a time,
 * never applied without an explicit tap. Eligibility: at least three days of data, and a pattern
 * that repeats on three or more days.
 */
object RecommendationEngine {
    const val MIN_DATA_DAYS = 3
    const val MIN_PATTERN_DAYS = 3
    const val LOOKBACK_DAYS = 14L
    const val SNOOZE_DAYS = 7L
    const val HEAVY_USE_MINUTES = 60
    const val MAX_ROUTINE_HOURS = 3

    data class Attempt(val time: Long, val pkg: String)
    data class Unlock(val time: Long, val pkg: String, val kind: OverrideKind, val reasonType: ReasonType?, val ruleId: Long?)

    sealed class Proposal {
        data class AddRoutine(val startMinute: Int, val endMinute: Int, val days: Set<Int>, val packages: List<String>) : Proposal()
        data class AddAppLimit(val pkg: String, val minutes: Int) : Proposal()
        data class MakeRoutineStrict(val routineId: Long, val routineName: String) : Proposal()
    }

    /** Numbers the UI puts into the one-sentence evidence line. */
    data class Evidence(val days: Int, val count: Int = 0, val typicalMinutes: Int = 0)

    data class Recommendation(val signature: String, val proposal: Proposal, val evidence: Evidence)

    data class Input(
        val now: Long,
        val zone: ZoneId,
        /** Earliest day with any logged data (attempts, sessions or usage). */
        val firstDataDay: LocalDate?,
        val attempts: List<Attempt>,
        val unlocks: List<Unlock>,
        /** Full-day usage per app for previous days (today excluded). */
        val usageByDay: Map<LocalDate, Map<String, Long>>,
        val routines: List<RoutineInput>,
        val bedtime: BedtimeInput?,
        val appLimits: List<AppLimitInput>,
        val exempt: Set<String>,
        /** Signature → time until which it is hidden ("Not now" for 7 days). */
        val snoozedUntil: Map<String, Long>,
        /** Apps the user does not count in screen time: never suggested for a limit. */
        val notCounted: Set<String> = emptySet(),
    )

    fun eligible(firstDataDay: LocalDate?, today: LocalDate): Boolean =
        firstDataDay != null && !firstDataDay.isAfter(today.minusDays((MIN_DATA_DAYS - 1).toLong()))

    /** The single suggestion to show now, or null. */
    fun recommend(input: Input): Recommendation? {
        val today = PolicyTime.localDate(input.now, input.zone)
        if (!eligible(input.firstDataDay, today)) return null
        return listOfNotNull(gap(input), strictRoutine(input), appLimit(input))
            .firstOrNull { (input.snoozedUntil[it.signature] ?: 0) <= input.now }
    }

    /**
     * "Most blocked attempts happen 10–11 PM, which no rule covers." Hours where attempts happened
     * on 3+ distinct days and no routine or bedtime covers at least half the hour on those days.
     */
    fun gap(input: Input): Recommendation? {
        val today = PolicyTime.localDate(input.now, input.zone)
        val from = today.minusDays(LOOKBACK_DAYS)
        val byHour = HashMap<Int, MutableSet<LocalDate>>()
        val countByHour = IntArray(24)
        val appsByHour = HashMap<Int, MutableMap<String, Int>>()
        for (a in input.attempts) {
            val zdt = java.time.Instant.ofEpochMilli(a.time).atZone(input.zone)
            val date = zdt.toLocalDate()
            if (date.isBefore(from) || date.isAfter(today) || a.pkg in input.exempt) continue
            val hour = zdt.hour
            val segments = Coverage.segments(input.routines, input.bedtime, date)
            if (Coverage.hourCovered(segments, hour)) continue
            byHour.getOrPut(hour) { mutableSetOf() } += date
            countByHour[hour]++
            val apps = appsByHour.getOrPut(hour) { mutableMapOf() }
            apps[a.pkg] = (apps[a.pkg] ?: 0) + 1
        }
        val qualifying = byHour.filterValues { it.size >= MIN_PATTERN_DAYS }.keys
        val best = qualifying.maxWithOrNull(compareBy<Int>({ byHour[it]!!.size }, { countByHour[it] })) ?: return null
        var start = best
        var end = best + 1
        while (end - start < MAX_ROUTINE_HOURS) {
            val before = start - 1
            val after = end
            val canBefore = before >= 0 && before in qualifying
            val canAfter = after <= 23 && after in qualifying
            when {
                canAfter && (!canBefore || countByHour[after] >= countByHour[before]) -> end++
                canBefore -> start--
                else -> break
            }
        }
        val hours = start until end
        val dates = hours.flatMap { byHour[it].orEmpty() }.toSet()
        val attempts = hours.sumOf { countByHour[it] }
        val apps = hours.flatMap { h -> appsByHour[h].orEmpty().entries }
            .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }.take(5).map { it.key }
        val days = when {
            dates.all { it.dayOfWeek.value in TimeWindow.WEEKDAYS } -> TimeWindow.WEEKDAYS
            dates.all { it.dayOfWeek.value in TimeWindow.WEEKENDS } -> TimeWindow.WEEKENDS
            else -> TimeWindow.EVERY_DAY
        }
        val endMinute = (end * 60) % 1440
        return Recommendation(
            signature = "routine:${start * 60}-$endMinute:${TimeWindow.formatDays(days)}",
            proposal = Proposal.AddRoutine(start * 60, endMinute, days, apps),
            evidence = Evidence(days = dates.size, count = attempts),
        )
    }

    /** A Normal routine bypassed with "Open anyway" on 3+ days: suggest Strict Lock. */
    fun strictRoutine(input: Input): Recommendation? {
        val today = PolicyTime.localDate(input.now, input.zone)
        val from = today.minusDays(LOOKBACK_DAYS)
        val byRule = input.unlocks
            .filter { it.kind == OverrideKind.OPEN_ANYWAY && it.reasonType == ReasonType.ROUTINE && it.ruleId != null }
            .filter { !UsageCalculator.date(it.time, input.zone).isBefore(from) }
            .groupBy { it.ruleId!! }
        val best = byRule.mapNotNull { (id, unlocks) ->
            val routine = input.routines.firstOrNull { it.id == id && it.strength == Strength.NORMAL && !it.imported } ?: return@mapNotNull null
            val days = unlocks.map { UsageCalculator.date(it.time, input.zone) }.toSet().size
            if (days < MIN_PATTERN_DAYS) null else Triple(routine, days, unlocks.size)
        }.maxWithOrNull(compareBy({ it.second }, { it.third })) ?: return null
        return Recommendation(
            signature = "strict:${best.first.id}",
            proposal = Proposal.MakeRoutineStrict(best.first.id, best.first.name),
            evidence = Evidence(days = best.second, count = best.third),
        )
    }

    /** An app used 60+ minutes on 3+ of the last 7 days with no limit: suggest a limit below its typical use. */
    fun appLimit(input: Input): Recommendation? {
        val today = PolicyTime.localDate(input.now, input.zone)
        val recent = input.usageByDay.filterKeys { !it.isBefore(today.minusDays(7)) && it.isBefore(today) }
        val limited = input.appLimits.filter { it.enabled }.flatMap { it.packages }.toSet()
        val perApp = HashMap<String, MutableList<Long>>()
        recent.values.forEach { day -> day.forEach { (pkg, ms) -> perApp.getOrPut(pkg) { mutableListOf() } += ms } }
        val best = perApp.filterKeys { it !in input.exempt && it !in input.notCounted && it !in limited }.mapNotNull { (pkg, values) ->
            val heavyDays = values.count { it >= HEAVY_USE_MINUTES * SessionClock.MINUTE }
            if (heavyDays < MIN_PATTERN_DAYS) return@mapNotNull null
            val sorted = values.sorted()
            val median = sorted[sorted.size / 2]
            Triple(pkg, heavyDays, median)
        }.maxWithOrNull(compareBy({ it.second }, { it.third })) ?: return null
        val typical = (best.third / SessionClock.MINUTE).toInt()
        val proposed = maxOf(15, (typical * 3 / 4) / 5 * 5)
        return Recommendation(
            signature = "limit:${best.first}",
            proposal = Proposal.AddAppLimit(best.first, proposed),
            evidence = Evidence(days = best.second, typicalMinutes = typical),
        )
    }

    fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
}
