package com.focusblock.app.policy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns raw foreground events (from UsageStatsManager.queryEvents) into per-app foreground time.
 *
 * Rules (documented in docs/metrics.md):
 *  - An app is in the foreground from its first ACTIVITY_RESUMED until it pauses or stops.
 *  - A new app resuming closes any other open app at that moment, so time is never double-counted
 *    (split-screen time is attributed to the most recently resumed app).
 *  - Screen off, keyguard and shutdown close everything.
 *  - Intervals are clipped to the requested window; open intervals are closed at the window end.
 */
object UsageCalculator {
    /** [SCREEN_ON] and [UNLOCK] do not open or close apps; they count pickups (see [Insights]). */
    enum class Type { RESUMED, PAUSED, STOPPED, SCREEN_OFF, SHUTDOWN, SCREEN_ON, UNLOCK }

    data class Event(val time: Long, val pkg: String?, val type: Type)
    data class Interval(val pkg: String, val start: Long, val end: Long) {
        val length: Long get() = end - start
    }

    /** Foreground intervals from time-ordered [events], closing open ones at [end]. */
    fun intervals(events: List<Event>, end: Long): List<Interval> {
        val out = ArrayList<Interval>()
        val open = LinkedHashMap<String, Long>()
        fun close(pkg: String, at: Long) {
            val start = open.remove(pkg) ?: return
            val stop = minOf(at, end)
            if (stop > start) out += Interval(pkg, start, stop)
        }
        fun closeAll(at: Long) = open.keys.toList().forEach { close(it, at) }
        for (e in events.sortedBy { it.time }) {
            if (e.time > end) break
            when (e.type) {
                Type.RESUMED -> {
                    val pkg = e.pkg ?: continue
                    open.keys.filter { it != pkg }.forEach { close(it, e.time) }
                    open.putIfAbsent(pkg, e.time)
                }
                Type.PAUSED, Type.STOPPED -> e.pkg?.let { close(it, e.time) }
                Type.SCREEN_OFF, Type.SHUTDOWN -> closeAll(e.time)
                Type.SCREEN_ON, Type.UNLOCK -> Unit
            }
        }
        closeAll(end)
        return out
    }

    /** Per-app totals within [start, end). */
    fun totals(intervals: List<Interval>, start: Long, end: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (i in intervals) {
            val s = maxOf(i.start, start)
            val e = minOf(i.end, end)
            if (e > s) out[i.pkg] = (out[i.pkg] ?: 0) + (e - s)
        }
        return out
    }

    /**
     * Per-day, per-app totals for each date in [dates]. When [cutoffMinute] is set, each day only
     * counts usage before that local time (used to compare "today so far" with earlier days fairly).
     * Intervals that span midnight are split between the two days.
     */
    fun dailyTotals(
        intervals: List<Interval>,
        dates: List<LocalDate>,
        zone: ZoneId,
        cutoffMinute: Int? = null,
    ): Map<LocalDate, Map<String, Long>> = dates.associateWith { date ->
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = if (cutoffMinute == null) dayEnd else minOf(dayEnd, start + cutoffMinute * SessionClock.MINUTE)
        totals(intervals, start, end)
    }

    /** Number of separate openings per app within [start, end) (resumes after another app). */
    fun launches(events: List<Event>, start: Long, end: Long): Map<String, Int> {
        val out = HashMap<String, Int>()
        var last: String? = null
        for (e in events.sortedBy { it.time }) {
            if (e.type == Type.SCREEN_OFF) { last = null; continue }
            if (e.type != Type.RESUMED || e.pkg == null) continue
            if (e.time in start until end && e.pkg != last) out[e.pkg] = (out[e.pkg] ?: 0) + 1
            last = e.pkg
        }
        return out
    }

    fun date(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
}
