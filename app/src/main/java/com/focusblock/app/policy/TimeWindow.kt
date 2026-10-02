package com.focusblock.app.policy

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A recurring local-time window, e.g. 20:45–22:30 on weekdays.
 *
 * Rules (documented in docs/implementation-log.md):
 *  - [days] use ISO numbering, 1 = Monday … 7 = Sunday (same as the legacy `schedules` table).
 *  - An overnight window (start > end, e.g. 23:00–07:00) belongs to the day it starts on, so
 *    "Friday 23:00–07:00" covers Saturday 00:00–07:00 even when Saturday is not selected.
 *  - start == end means a full 24-hour window starting at that time on each selected day
 *    (so 00:00–00:00 is "all day"). Open decision 12.3 #4, resolved this way and logged.
 *  - Boundaries are local wall-clock times, so they follow time-zone and DST changes.
 */
data class TimeWindow(val startMinute: Int, val endMinute: Int, val days: Set<Int>) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439) { "Minutes must be within a day" }
    }

    val allDay: Boolean get() = startMinute == endMinute
    val overnight: Boolean get() = startMinute > endMinute
    val durationMinutes: Int
        get() = when {
            allDay -> 1440
            overnight -> 1440 - startMinute + endMinute
            else -> endMinute - startMinute
        }

    data class Occurrence(val start: Long, val end: Long)

    /** The occurrence that started on [date], if [date] is a selected day. */
    fun occurrenceStartingOn(date: LocalDate, zone: ZoneId): Occurrence? {
        if (date.dayOfWeek.value !in days) return null
        val start = ZonedDateTime.of(date, minuteToTime(startMinute), zone)
        val endDate = if (allDay || overnight) date.plusDays(1) else date
        val end = ZonedDateTime.of(endDate, minuteToTime(endMinute), zone)
        return Occurrence(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
    }

    /** The occurrence covering [now], or null when the window is not active. */
    fun occurrenceAt(now: Long, zone: ZoneId): Occurrence? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (date in listOf(today, today.minusDays(1))) {
            val o = occurrenceStartingOn(date, zone) ?: continue
            if (now >= o.start && now < o.end) return o
        }
        return null
    }

    fun isActive(now: Long, zone: ZoneId): Boolean = occurrenceAt(now, zone) != null

    /** Next start or end strictly after [now], looking up to eight days ahead. */
    fun nextBoundaryAfter(now: Long, zone: ZoneId): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var best: Long? = null
        for (offset in -1L..8L) {
            val o = occurrenceStartingOn(today.plusDays(offset), zone) ?: continue
            for (t in longArrayOf(o.start, o.end)) if (t > now && (best == null || t < best)) best = t
        }
        return best
    }

    /** Next occurrence start strictly after [now]. */
    fun nextStartAfter(now: Long, zone: ZoneId): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (offset in 0L..8L) {
            val o = occurrenceStartingOn(today.plusDays(offset), zone) ?: continue
            if (o.start > now) return o.start
        }
        return null
    }

    /**
     * Minutes of [date] covered by this window, as half-open ranges within 0..1440. Includes the
     * after-midnight tail of an overnight window that started the previous day.
     */
    fun segmentsOn(date: LocalDate): List<IntRange> {
        val out = mutableListOf<IntRange>()
        val yesterday = date.minusDays(1)
        if ((overnight || allDay) && yesterday.dayOfWeek.value in days && endMinute > 0) {
            out += 0 until endMinute
        }
        if (date.dayOfWeek.value in days) {
            val end = if (overnight || allDay) 1440 else endMinute
            if (end > startMinute) out += startMinute until end
        }
        return out
    }

    companion object {
        val EVERY_DAY = (1..7).toSet()
        val WEEKDAYS = (1..5).toSet()
        val WEEKENDS = setOf(6, 7)

        fun minuteToTime(minute: Int): LocalTime = LocalTime.of(minute / 60, minute % 60)

        /** Parses the legacy comma-separated "1,2,3" day list. Invalid entries are dropped. */
        fun parseDays(csv: String): Set<Int> =
            csv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

        fun formatDays(days: Set<Int>): String = days.sorted().joinToString(",")
    }
}

/** Calendar helpers used across the policy core. */
object PolicyTime {
    fun startOfDay(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun nextMidnight(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun localDate(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
