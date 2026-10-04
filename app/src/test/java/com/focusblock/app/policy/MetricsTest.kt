package com.focusblock.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MetricsTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val min = 60_000L
    private fun day(ago: Int): LocalDate = today.minusDays(ago.toLong())

    @Test fun usualByNowCountsDaysWithNoUseYetAsZero() {
        // 7:30 AM: 14 days of about 2 h each, but only 3 of them had any use before 7:30.
        val full = (1..14).associate { day(it) to 120 * min }
        val byNow = (1..14).associate { day(it) to if (it <= 3) 25 * min else 0L }
        assertEquals(75 * min / 14, Metrics.baseline(byNow, today, full))
        // Without the whole-day totals, a day with nothing by now looks like a day without data.
        assertEquals(25 * min, Metrics.baseline(byNow, today))
    }

    @Test fun usualByNowStillNeedsThreeDaysWithData() {
        val full = mapOf(day(1) to 60 * min, day(2) to 60 * min, day(3) to 0L)
        val byNow = mapOf(day(1) to 0L, day(2) to 0L, day(3) to 0L)
        assertNull(Metrics.baseline(byNow, today, full))
        // Whole-day totals decide, so three days used later in the day give a usual of zero by now.
        assertEquals(0L, Metrics.baseline(byNow, today, full + (day(4) to 30 * min)))
    }

    @Test fun dailyAverageSkipsDaysWithoutDataAndTodayWhileItIsGoing() {
        val week = mapOf(day(6) to 0L, day(5) to 0L, day(4) to 60 * min, day(3) to 120 * min, day(2) to 0L, day(1) to 90 * min, day(0) to 5 * min)
        assertEquals(90 * min, Metrics.dailyAverage(week, today))
        assertEquals(3, Metrics.averagedDays(week, today).size)
        // Only today has data: its time so far is all there is.
        assertEquals(5 * min, Metrics.dailyAverage(mapOf(day(1) to 0L, day(0) to 5 * min), today))
        assertNull(Metrics.dailyAverage(mapOf(day(1) to 0L, day(0) to 0L), today))
        // A past range has no "today" in it.
        assertEquals(60 * min, Metrics.dailyAverage(mapOf(day(9) to 60 * min, day(8) to 0L), today))
    }

    @Test fun changeComparesAveragePerDayOfWholeDays() {
        val previous = (7..13).associate { day(it) to 120 * min }
        // Six whole days of 90 min and an hour into today: 25% less, not more because today is short.
        val current = (1..6).associate { day(it) to 90 * min } + (day(0) to 10 * min)
        assertEquals(-25, Metrics.averageChange(current, previous, today))
        // Days without data are left out on both sides.
        val sparse = previous.mapValues { (d, v) -> if (d == day(7) || d == day(8)) 0L else v }
        assertEquals(-25, Metrics.averageChange(current, sparse, today))
    }

    @Test fun changeIsHiddenWithoutEnoughHistory() {
        val current = (1..6).associate { day(it) to 120 * min }
        // Start of history: one day of data in the week before would read "+500%".
        val previous = (7..13).associate { day(it) to if (it == 7) 20 * min else 0L }
        assertNull(Metrics.averageChange(current, previous, today))
        val three = (7..9).associate { day(it) to 60 * min }
        assertEquals(100, Metrics.averageChange(current, three, today))
        // Only today so far in the current range: nothing whole to compare.
        assertNull(Metrics.averageChange(mapOf(day(0) to 30 * min), three, today))
    }
}
