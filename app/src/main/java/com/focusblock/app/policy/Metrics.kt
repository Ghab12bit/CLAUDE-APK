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
     */
    fun baseline(dailyTotals: Map<LocalDate, Long>, today: LocalDate): Long? {
        val days = dailyTotals.filterKeys { it.isBefore(today) && !it.isBefore(today.minusDays(BASELINE_DAYS)) }
            .filterValues { it > 0 }
        if (days.size < MIN_BASELINE_DAYS) return null
        return days.values.sum() / days.size
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
