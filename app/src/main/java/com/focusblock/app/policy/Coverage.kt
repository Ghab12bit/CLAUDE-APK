package com.focusblock.app.policy

import java.time.LocalDate

/** The Rules tab's 24-hour strip and "Rules cover 6 h 15 min of today" line (spec 4.6). */
object Coverage {
    data class Segment(
        val startMinute: Int,
        /** Exclusive, up to 1440. */
        val endMinute: Int,
        val type: ReasonType,
        val ruleId: Long,
        val name: String,
    )

    /** Segments of [date] covered by enabled, time-based routines and bedtime. */
    fun segments(routines: List<RoutineInput>, bedtime: BedtimeInput?, date: LocalDate): List<Segment> {
        val out = ArrayList<Segment>()
        for (r in routines) {
            if (!r.enabled || r.window == null) continue
            r.window.segmentsOn(date).forEach { out += Segment(it.first, it.last + 1, ReasonType.ROUTINE, r.id, r.name) }
        }
        if (bedtime != null && bedtime.enabled) {
            bedtime.window.segmentsOn(date).forEach { out += Segment(it.first, it.last + 1, ReasonType.BEDTIME, 1, "") }
        }
        return out.sortedBy { it.startMinute }
    }

    /** Minutes covered by the union of [segments]. */
    fun coveredMinutes(segments: List<Segment>): Int {
        val covered = BooleanArray(1440)
        segments.forEach { s -> for (m in s.startMinute until s.endMinute.coerceAtMost(1440)) covered[m] = true }
        return covered.count { it }
    }

    /** Minutes of hour [hour] covered on [date]. */
    fun minutesCoveredInHour(segments: List<Segment>, hour: Int): Int {
        val start = hour * 60
        val end = start + 60
        val covered = BooleanArray(60)
        segments.forEach { s ->
            for (m in maxOf(s.startMinute, start) until minOf(s.endMinute, end)) covered[m - start] = true
        }
        return covered.count { it }
    }

    /** An hour counts as covered when at least half of it is covered by a rule. */
    fun hourCovered(segments: List<Segment>, hour: Int): Boolean = minutesCoveredInHour(segments, hour) >= 30
}
