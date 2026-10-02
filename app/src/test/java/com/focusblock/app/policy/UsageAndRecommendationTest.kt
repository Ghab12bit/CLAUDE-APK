package com.focusblock.app.policy

import com.focusblock.app.policy.UsageCalculator.Event
import com.focusblock.app.policy.UsageCalculator.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class UsageAndRecommendationTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val min = SessionClock.MINUTE
    private fun t(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    // ---- Usage --------------------------------------------------------------------------------

    @Test fun foregroundTimeIsNeverDoubleCounted() {
        val events = listOf(
            Event(t(2, 10), "a", Type.RESUMED),
            Event(t(2, 10, 10), "b", Type.RESUMED), // a never reported a pause
            Event(t(2, 10, 20), "b", Type.PAUSED),
            Event(t(2, 10, 30), "a", Type.RESUMED),
            Event(t(2, 10, 40), null, Type.SCREEN_OFF),
        )
        val totals = UsageCalculator.totals(UsageCalculator.intervals(events, t(2, 12)), t(2, 0), t(3, 0))
        assertEquals(20 * min, totals["a"])
        assertEquals(10 * min, totals["b"])
    }

    @Test fun sessionsSpanningMidnightAreSplitBetweenDays() {
        val events = listOf(Event(t(2, 23, 30), "a", Type.RESUMED), Event(t(3, 0, 45), "a", Type.PAUSED))
        val intervals = UsageCalculator.intervals(events, t(3, 12))
        val daily = UsageCalculator.dailyTotals(intervals, listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3)), zone)
        assertEquals(30 * min, daily[LocalDate.of(2026, 10, 2)]!!["a"])
        assertEquals(45 * min, daily[LocalDate.of(2026, 10, 3)]!!["a"])
    }

    @Test fun openIntervalsCloseAtTheQueryEnd() {
        val intervals = UsageCalculator.intervals(listOf(Event(t(2, 9), "a", Type.RESUMED)), t(2, 9, 15))
        assertEquals(15 * min, UsageCalculator.totals(intervals, t(2, 0), t(3, 0))["a"])
    }

    @Test fun cutoffComparesTheSameTimeOfDay() {
        val events = listOf(Event(t(1, 9), "a", Type.RESUMED), Event(t(1, 11), "a", Type.PAUSED))
        val daily = UsageCalculator.dailyTotals(UsageCalculator.intervals(events, t(2, 0)), listOf(LocalDate.of(2026, 10, 1)), zone, cutoffMinute = 10 * 60)
        assertEquals(60 * min, daily[LocalDate.of(2026, 10, 1)]!!["a"])
    }

    @Test fun launchesCountSeparateOpenings() {
        val events = listOf(
            Event(t(2, 9), "a", Type.RESUMED), Event(t(2, 9, 1), "a", Type.RESUMED),
            Event(t(2, 9, 2), "b", Type.RESUMED), Event(t(2, 9, 3), "a", Type.RESUMED),
        )
        assertEquals(mapOf("a" to 2, "b" to 1), UsageCalculator.launches(events, t(2, 0), t(3, 0)))
    }

    // ---- Coverage -----------------------------------------------------------------------------

    @Test fun coverageIsTheUnionOfRulesAndBedtime() {
        val routines = listOf(
            RoutineInput(1, "Evening", setOf("a"), TimeWindow(20 * 60 + 45, 22 * 60 + 30, TimeWindow.EVERY_DAY), Strength.NORMAL),
            RoutineInput(2, "Overlap", setOf("a"), TimeWindow(22 * 60, 23 * 60, TimeWindow.EVERY_DAY), Strength.NORMAL),
            RoutineInput(3, "Off", setOf("a"), TimeWindow(9 * 60, 10 * 60, TimeWindow.EVERY_DAY), Strength.NORMAL, enabled = false),
        )
        val bed = BedtimeInput(true, TimeWindow(23 * 60, 7 * 60, TimeWindow.EVERY_DAY))
        val segments = Coverage.segments(routines, bed, LocalDate.of(2026, 10, 2))
        assertEquals(7 * 60 + 135 + 60, Coverage.coveredMinutes(segments)) // 0–7, 20:45–23:00, 23:00–24:00
        assertTrue(Coverage.hourCovered(segments, 21))
        assertTrue(!Coverage.hourCovered(segments, 9))
    }

    // ---- Metrics ------------------------------------------------------------------------------

    @Test fun baselineNeedsThreeDaysAndExcludesToday() {
        val today = LocalDate.of(2026, 10, 10)
        val two = mapOf(today.minusDays(1) to 100L, today.minusDays(2) to 200L, today to 999L)
        assertNull(Metrics.baseline(two, today))
        val three = two + (today.minusDays(3) to 300L) + (today.minusDays(20) to 9999L)
        assertEquals(200L, Metrics.baseline(three, today))
    }

    @Test fun attemptsByHourAndOutcomes() {
        assertEquals(2, Metrics.attemptsByHour(listOf(t(2, 22), t(3, 22, 30), t(3, 9)), zone)[22])
        val c = Metrics.outcomes(listOf(SessionOutcome.FINISHED, SessionOutcome.FINISHED, SessionOutcome.UNANSWERED, null))
        assertEquals(2, c.finished); assertEquals(1, c.unanswered); assertEquals(0, c.endedEarly)
        assertTrue(Metrics.RuleBypass("r", 6, 3).oftenBypassed)
        assertTrue(!Metrics.RuleBypass("r", 20, 3).oftenBypassed)
    }

    // ---- Recommendations -------------------------------------------------------------------------

    private fun input(attempts: List<RecommendationEngine.Attempt> = emptyList(), unlocks: List<RecommendationEngine.Unlock> = emptyList(),
                      usage: Map<LocalDate, Map<String, Long>> = emptyMap(), routines: List<RoutineInput> = emptyList(),
                      first: LocalDate? = LocalDate.of(2026, 9, 20), snoozed: Map<String, Long> = emptyMap()) =
        RecommendationEngine.Input(t(10, 12), zone, first, attempts, unlocks, usage, routines, null, emptyList(), emptySet(), snoozed)

    private val lateNight = (5..8).map { RecommendationEngine.Attempt(t(it, 22, 15), "com.instagram.android") } +
        (5..8).map { RecommendationEngine.Attempt(t(it, 22, 40), "com.reddit.frontpage") }

    @Test fun noSuggestionBeforeThreeDaysOfData() {
        assertNull(RecommendationEngine.recommend(input(lateNight, first = LocalDate.of(2026, 10, 9))))
        assertTrue(RecommendationEngine.eligible(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10)))
    }

    @Test fun repeatedUncoveredAttemptsSuggestARoutine() {
        val r = RecommendationEngine.recommend(input(lateNight))!!
        val p = r.proposal as RecommendationEngine.Proposal.AddRoutine
        assertEquals(22 * 60, p.startMinute)
        assertEquals(23 * 60, p.endMinute)
        assertEquals(4, r.evidence.days)
        assertEquals(8, r.evidence.count)
        assertEquals(listOf("com.instagram.android", "com.reddit.frontpage"), p.packages)
    }

    @Test fun patternsOnFewerThanThreeDaysAreIgnored() {
        assertNull(RecommendationEngine.gap(input(lateNight.filter { it.time < t(7, 0) })))
    }

    @Test fun coveredHoursAreNotGaps() {
        val covering = RoutineInput(1, "Night", setOf("x"), TimeWindow(21 * 60, 23 * 60, TimeWindow.EVERY_DAY), Strength.NORMAL)
        assertNull(RecommendationEngine.gap(input(lateNight, routines = listOf(covering))))
    }

    @Test fun notNowHidesASuggestion() {
        val sig = RecommendationEngine.recommend(input(lateNight))!!.signature
        assertNull(RecommendationEngine.recommend(input(lateNight, snoozed = mapOf(sig to t(17, 12)))))
    }

    @Test fun oftenBypassedRoutineSuggestsStrictLock() {
        val routine = RoutineInput(9, "Evening", setOf("a"), TimeWindow(20 * 60, 22 * 60, TimeWindow.EVERY_DAY), Strength.NORMAL)
        val unlocks = (5..7).map { RecommendationEngine.Unlock(t(it, 21), "a", OverrideKind.OPEN_ANYWAY, ReasonType.ROUTINE, 9) }
        val r = RecommendationEngine.recommend(input(unlocks = unlocks, routines = listOf(routine)))!!
        assertEquals(RecommendationEngine.Proposal.MakeRoutineStrict(9, "Evening"), r.proposal)
    }

    @Test fun heavyDailyUseSuggestsAnAppLimitBelowTypicalUse() {
        val usage = (3..9).associate { LocalDate.of(2026, 10, it) to mapOf("com.youtube" to 100 * min) }
        val r = RecommendationEngine.recommend(input(usage = usage))!!
        assertEquals(RecommendationEngine.Proposal.AddAppLimit("com.youtube", 75), r.proposal)
        assertEquals(100, r.evidence.typicalMinutes)
    }
}
