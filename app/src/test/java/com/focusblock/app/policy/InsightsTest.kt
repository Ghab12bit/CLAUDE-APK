package com.focusblock.app.policy

import com.focusblock.app.policy.UsageCalculator.Event
import com.focusblock.app.policy.UsageCalculator.Interval
import com.focusblock.app.policy.UsageCalculator.Type
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 2)
    private fun t(h: Int, m: Int = 0, d: Int = 2) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L
    private val categories = mapOf("social" to AppCategory.DISTRACTING, "docs" to AppCategory.PRODUCTIVE)
    private val categoryOf: (String) -> AppCategory = { categories[it] ?: AppCategory.NEUTRAL }

    @Test fun defaultsUseKnownAppsThenTheDeclaredCategory() {
        assertEquals(AppCategory.DISTRACTING, CategoryDefaults.forPackage("com.facebook.katana", null))
        assertEquals(AppCategory.DISTRACTING, CategoryDefaults.forPackage("com.whatsapp", CategoryDefaults.ANDROID_SOCIAL))
        assertEquals(AppCategory.PRODUCTIVE, CategoryDefaults.forPackage("com.notion.id", null))
        assertEquals(AppCategory.DISTRACTING, CategoryDefaults.forPackage("some.game", CategoryDefaults.ANDROID_GAME))
        assertEquals(AppCategory.PRODUCTIVE, CategoryDefaults.forPackage("some.office", CategoryDefaults.ANDROID_PRODUCTIVITY))
        assertEquals(AppCategory.NEUTRAL, CategoryDefaults.forPackage("com.android.chrome", null))
    }

    @Test fun hourlyBucketsSplitIntervalsAndSkipUncountedApps() {
        val intervals = listOf(
            Interval("social", t(9, 50), t(10, 20)), // 10 min at 9, 20 min at 10
            Interval("docs", t(10, 30), t(10, 40)),
            Interval("launcher", t(10, 40), t(10, 50)),
        )
        val b = Insights.buckets(intervals, Insights.hourBoundaries(day, zone), categoryOf) { it != "launcher" }
        assertEquals(24, b.size)
        assertEquals(10 * min, b[9].byCategory[AppCategory.DISTRACTING.ordinal])
        assertEquals(20 * min, b[10].byCategory[AppCategory.DISTRACTING.ordinal])
        assertEquals(10 * min, b[10].byCategory[AppCategory.PRODUCTIVE.ordinal])
        assertEquals(30 * min, b[10].total)
    }

    @Test fun peakHourIsTheBusiestHourOfDay() {
        val intervals = listOf(Interval("a", t(9), t(9, 20)), Interval("a", t(15), t(15, 45)), Interval("a", t(15, 10, 3), t(15, 20, 3)))
        val hours = Insights.hourOfDayTotals(intervals, t(0), t(0, 0, 4), zone) { true }
        assertEquals(20 * min, hours[9])
        assertEquals(55 * min, hours[15])
        assertEquals(15, Insights.peakHour(hours))
        assertNull(Insights.peakHour(LongArray(24)))
    }

    @Test fun spansMergeShortGapsAndLongestUseIsTheLongestSpan() {
        val intervals = listOf(
            Interval("a", t(9), t(9, 10)),
            Interval("launcher", t(9, 10), t(9, 10) + 20_000),
            Interval("b", t(9, 11), t(9, 30)), // 40 s gap: same stretch
            Interval("a", t(11), t(11, 5)),
        )
        val spans = Insights.spans(intervals)
        assertEquals(listOf(Insights.Span(t(9), t(9, 30)), Insights.Span(t(11), t(11, 5))), spans)
        assertEquals(30 * min, Insights.longestUse(spans, t(0), t(23, 59)))
        // Clipped to the window.
        assertEquals(15 * min, Insights.longestUse(spans, t(9, 15), t(23, 59)))
    }

    @Test fun longestFocusCountsAwakeGapsBetweenUseOnly() {
        val spans = listOf(
            Insights.Span(t(1), t(1, 30)), // night use: the gap until 08:00 is mostly sleep
            Insights.Span(t(8), t(8, 30)),
            Insights.Span(t(10), t(10, 10)), // 90 min focus
            Insights.Span(t(11), t(11, 5)),
        )
        val past = t(12, 0, 5)
        // 01:30–08:00 is clipped to 07:00–08:00 = 60 min; 08:30–10:00 = 90 min wins.
        assertEquals(90 * min, Insights.longestFocus(spans, day, zone, past))
        // Today at 14:00: 3 h since the last use counts.
        assertEquals(175 * min, Insights.longestFocus(spans, day, zone, t(14)))
        assertEquals(0, Insights.longestFocus(emptyList(), day, zone, past))
    }

    @Test fun pickupsPreferUnlocksAndFallBackToScreenOn() {
        val withLock = listOf(Event(t(9), null, Type.SCREEN_ON), Event(t(9), null, Type.UNLOCK), Event(t(10), null, Type.SCREEN_ON))
        assertEquals(1, Insights.pickups(withLock, t(0), t(23)))
        val noLock = listOf(Event(t(9), null, Type.SCREEN_ON), Event(t(10), null, Type.SCREEN_ON), Event(t(1, 0, 3), null, Type.SCREEN_ON))
        assertEquals(2, Insights.pickups(noLock, t(0), t(23, 59)))
    }

    @Test fun sharesAddUpToOneHundred() {
        assertArrayEquals(intArrayOf(33, 33, 34).sortedArray(), Insights.sharePercents(longArrayOf(1, 1, 1)).sortedArray())
        assertEquals(100, Insights.sharePercents(longArrayOf(41, 57, 1)).sum())
        assertArrayEquals(intArrayOf(0, 0, 0), Insights.sharePercents(longArrayOf(0, 0, 0)))
    }

    @Test fun balanceAndChange() {
        assertEquals(24, Insights.balancePercent(230 * min, 1)) // 3 h 50 min of 16 h
        assertEquals(12, Insights.balancePercent(230 * min, 2))
        assertEquals(-25, Insights.changePercent(75, 100))
        assertNull(Insights.changePercent(10, 0))
    }

    @Test fun dstDayHasTheRightNumberOfHours() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(24, Insights.hourBoundaries(LocalDate.of(2026, 3, 29), berlin).size) // 23 hours + end
        assertEquals(25, Insights.hourBoundaries(LocalDate.of(2026, 10, 2), berlin).size)
    }

    @Test fun dayUsageSplitsHoursAndLeavesUncountedAppsOutOfSpans() {
        val intervals = listOf(
            Interval("social", t(9, 50), t(10, 20)),
            Interval("clock", t(10, 20), t(12, 0)), // a stopwatch left running
            Interval("docs", t(12, 0), t(12, 10)),
        )
        val events = listOf(Event(t(9, 49), null, Type.UNLOCK), Event(t(9, 50), "social", Type.RESUMED), Event(t(12, 0), "docs", Type.RESUMED))
        val all = Insights.dayUsage(intervals, events, day, zone, t(9, 0, 3))
        assertEquals(10 * min, all.hourly!!["social"]!![9])
        assertEquals(20 * min, all.hourly!!["social"]!![10])
        assertEquals(30 * min, all.totals["social"])
        assertEquals(1, all.pickups)
        assertEquals(140 * min, all.longestUse) // one stretch 09:50–12:10
        val noClock = Insights.dayUsage(intervals, events, day, zone, t(9, 0, 3), notInSpans = setOf("clock"))
        assertEquals(30 * min, noClock.longestUse)
        assertEquals(100 * min, noClock.longestFocus) // 10:20–12:00 without the phone
        // Stacks and totals skip uncounted apps.
        val counted: (String) -> Boolean = { it != "clock" }
        assertEquals(40 * min, all.total(counted))
        val stack = Insights.hourStack(all, categoryOf, counted)
        assertEquals(30 * min, stack[9].sum() + stack[10].sum())
        assertEquals(10 * min, Insights.dayStack(all, categoryOf, counted)[AppCategory.PRODUCTIVE.ordinal])
        assertEquals(10 * min, Insights.hourOfDay(listOf(all), counted)[12])
    }

    @Test fun totalBeforeUsesHoursAndScalesDaysWithoutDetail() {
        val d = DayUsage(day, mapOf("a" to LongArray(24).also { it[9] = 60 * min; it[10] = 60 * min }), mapOf("a" to 120 * min), emptyMap(), null, null, null)
        assertEquals(90 * min, Insights.totalBefore(d, 10 * 60 + 30) { true })
        val legacy = DayUsage(day, null, mapOf("a" to 120 * min), emptyMap(), null, null, null)
        assertEquals(60 * min, Insights.totalBefore(legacy, 720) { true })
    }
}
