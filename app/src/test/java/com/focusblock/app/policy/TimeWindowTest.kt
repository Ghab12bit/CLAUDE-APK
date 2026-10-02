package com.focusblock.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TimeWindowTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val newYork = ZoneId.of("America/New_York")

    private fun t(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, m, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** Friday 2 Oct 2026 is day 5. */
    private val friOnly = setOf(5)

    @Test fun daytimeWindowIsHalfOpen() {
        val w = TimeWindow(9 * 60, 17 * 60, TimeWindow.WEEKDAYS)
        assertTrue(w.isActive(t(kolkata, 2026, 10, 2, 9, 0), kolkata))
        assertTrue(w.isActive(t(kolkata, 2026, 10, 2, 16, 59), kolkata))
        assertFalse(w.isActive(t(kolkata, 2026, 10, 2, 17, 0), kolkata))
        assertFalse(w.isActive(t(kolkata, 2026, 10, 3, 10, 0), kolkata)) // Saturday
    }

    @Test fun overnightWindowBelongsToTheDayItStarts() {
        val w = TimeWindow(23 * 60, 7 * 60, friOnly)
        assertTrue(w.isActive(t(kolkata, 2026, 10, 2, 23, 30), kolkata))
        // Saturday morning is covered because the window started on Friday.
        assertTrue(w.isActive(t(kolkata, 2026, 10, 3, 6, 59), kolkata))
        assertFalse(w.isActive(t(kolkata, 2026, 10, 3, 7, 0), kolkata))
        // Friday morning is not covered: Thursday was not selected.
        assertFalse(w.isActive(t(kolkata, 2026, 10, 2, 6, 0), kolkata))
        val o = w.occurrenceAt(t(kolkata, 2026, 10, 3, 1, 0), kolkata)!!
        assertEquals(t(kolkata, 2026, 10, 2, 23, 0), o.start)
        assertEquals(t(kolkata, 2026, 10, 3, 7, 0), o.end)
        assertEquals(8 * 60, w.durationMinutes)
    }

    @Test fun equalStartAndEndMeansAllDay() {
        val w = TimeWindow(0, 0, friOnly)
        assertTrue(w.allDay)
        assertTrue(w.isActive(t(kolkata, 2026, 10, 2, 0, 0), kolkata))
        assertTrue(w.isActive(t(kolkata, 2026, 10, 2, 23, 59), kolkata))
        assertFalse(w.isActive(t(kolkata, 2026, 10, 3, 0, 0), kolkata))
        assertEquals(1440, w.durationMinutes)
    }

    @Test fun nextBoundaryCrossesMidnightAndWeek() {
        val w = TimeWindow(23 * 60, 7 * 60, friOnly)
        assertEquals(t(kolkata, 2026, 10, 2, 23, 0), w.nextBoundaryAfter(t(kolkata, 2026, 10, 2, 22, 0), kolkata))
        assertEquals(t(kolkata, 2026, 10, 3, 7, 0), w.nextBoundaryAfter(t(kolkata, 2026, 10, 3, 1, 0), kolkata))
        assertEquals(t(kolkata, 2026, 10, 9, 23, 0), w.nextBoundaryAfter(t(kolkata, 2026, 10, 3, 8, 0), kolkata))
    }

    @Test fun springForwardKeepsLocalBoundaries() {
        // 8 Mar 2026: 02:00 → 03:00 in New York. A 01:00–04:00 window lasts two real hours that night.
        val w = TimeWindow(60, 4 * 60, TimeWindow.EVERY_DAY)
        val o = w.occurrenceStartingOn(LocalDate.of(2026, 3, 8), newYork)!!
        assertEquals(2 * 60 * SessionClock.MINUTE, o.end - o.start)
        assertTrue(w.isActive(t(newYork, 2026, 3, 8, 3, 30), newYork))
    }

    @Test fun fallBackKeepsLocalBoundaries() {
        // 1 Nov 2026: 02:00 → 01:00 in New York. A 00:00–03:00 window lasts four real hours.
        val w = TimeWindow(0, 3 * 60, TimeWindow.EVERY_DAY)
        val o = w.occurrenceStartingOn(LocalDate.of(2026, 11, 1), newYork)!!
        assertEquals(4 * 60 * SessionClock.MINUTE, o.end - o.start)
    }

    @Test fun timeZoneChangeIsReevaluatedInTheNewZone() {
        // 21:00 in Kolkata is 11:30 in New York; an evening routine is active in one zone and not the other.
        val w = TimeWindow(20 * 60, 22 * 60, TimeWindow.EVERY_DAY)
        val instant = t(kolkata, 2026, 10, 2, 21, 0)
        assertTrue(w.isActive(instant, kolkata))
        assertFalse(w.isActive(instant, newYork))
    }

    @Test fun segmentsIncludeTheOvernightTail() {
        val w = TimeWindow(23 * 60, 7 * 60, friOnly)
        assertEquals(listOf(23 * 60 until 1440), w.segmentsOn(LocalDate.of(2026, 10, 2)))
        assertEquals(listOf(0 until 7 * 60), w.segmentsOn(LocalDate.of(2026, 10, 3)))
        assertTrue(w.segmentsOn(LocalDate.of(2026, 10, 4)).isEmpty())
    }

    @Test fun parsesLegacyDayLists() {
        assertEquals(setOf(1, 2, 7), TimeWindow.parseDays("1, 2,x,7,9"))
        assertEquals("1,2,7", TimeWindow.formatDays(setOf(7, 1, 2)))
    }

    @Test fun midnightHelpers() {
        val now = t(kolkata, 2026, 10, 2, 21, 0)
        assertEquals(t(kolkata, 2026, 10, 3, 0, 0), PolicyTime.nextMidnight(now, kolkata))
        assertEquals(t(kolkata, 2026, 10, 2, 0, 0), PolicyTime.startOfDay(now, kolkata))
        assertNull(TimeWindow(60, 120, emptySet()).nextBoundaryAfter(now, kolkata))
    }
}
