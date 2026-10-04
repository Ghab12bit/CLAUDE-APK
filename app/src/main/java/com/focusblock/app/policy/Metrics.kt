package com.focusblock.app.policy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Activity metrics. Every formula here is documented in docs/metrics.md. */
object Metrics {
    const val BASELINE_DAYS = 14L
    const val MIN_BASELINE_DAYS = 3

    /**
     * The user's own trailing average: mean of daily totals over the [BASELINE_DAYS] days before
     * [today] that have usage data. Null when fewer than [MIN_BASELINE_DAYS] such days exist, so the
     * comparison is never shown from too little data.
     *
     * Whether a day has data is decided from its whole day ([fullDayTotals]). When [dailyTotals] only
     * count up to the current time of day ("usual by now"), a day with no use yet by that time still
     * counts, as zero, so an early hour is not compared with only the days that started early.
     */
    fun baseline(dailyTotals: Map<LocalDate, Long>, today: LocalDate, fullDayTotals: Map<LocalDate, Long> = dailyTotals): Long? {
        val days = fullDayTotals.filter { (d, total) -> total > 0 && d.isBefore(today) && !d.isBefore(today.minusDays(BASELINE_DAYS)) }.keys
        if (days.size < MIN_BASELINE_DAYS) return null
        return days.sumOf { dailyTotals[it] ?: 0L } / days.size
    }

    /**
     * The days of a range that its average per day is taken over: days with data (more than zero).
     * [today] is still under way, so it only counts when no earlier day of the range has data.
     */
    fun averagedDays(totals: Map<LocalDate, Long>, today: LocalDate): Map<LocalDate, Long> {
        val withData = totals.filter { (d, v) -> v > 0 && !d.isAfter(today) }
        val whole = withData.filterKeys { it.isBefore(today) }
        return if (whole.isEmpty()) withData else whole
    }

    /** Average per day over [averagedDays], or null when no day of the range has data. */
    fun dailyAverage(totals: Map<LocalDate, Long>, today: LocalDate): Long? {
        val days = averagedDays(totals, today)
        return if (days.isEmpty()) null else days.values.sum() / days.size
    }

    /**
     * Change in average per day from the [previous] range to the [current] one, in whole percent.
     * Only whole days with data are compared (today is left out), each range over its own such days.
     * Null when the previous range has fewer than [MIN_BASELINE_DAYS] of them (e.g. at the start of
     * history) or the current range has none.
     */
    fun averageChange(current: Map<LocalDate, Long>, previous: Map<LocalDate, Long>, today: LocalDate): Int? {
        val now = current.filter { (d, v) -> v > 0 && d.isBefore(today) }
        val before = previous.filter { (d, v) -> v > 0 && d.isBefore(today) }
        if (now.isEmpty() || before.size < MIN_BASELINE_DAYS) return null
        return Insights.changePercent(now.values.sum() / now.size, before.values.sum() / before.size)
    }

    data class OutcomeCounts(val finished: Int, val notFinished: Int, val unanswered: Int, val endedEarly: Int, val extended: Int)

    fun outcomes(values: List<SessionOutcome?>): OutcomeCounts = OutcomeCounts(
        finished = values.count { it == SessionOutcome.FINISHED },
        notFinished = values.count { it == SessionOutcome.NOT_FINISHED },
        unanswered = values.count { it == SessionOutcome.UNANSWERED },
        endedEarly = values.count { it == SessionOutcome.ENDED_EARLY },
        extended = values.count { it == SessionOutcome.EXTENDED },
    )

    /** Blocked attempts per local hour of day. */
    fun attemptsByHour(times: List<Long>, zone: ZoneId): IntArray {
        val out = IntArray(24)
        times.forEach { out[Instant.ofEpochMilli(it).atZone(zone).hour]++ }
        return out
    }

    data class RuleBypass(val ruleKey: String, val attempts: Int, val bypasses: Int) {
        /** A rule is "often bypassed" when at least a third of its attempts end in an unlock (min 3). */
        val oftenBypassed: Boolean get() = bypasses >= 3 && bypasses * 3 >= attempts
    }
}
