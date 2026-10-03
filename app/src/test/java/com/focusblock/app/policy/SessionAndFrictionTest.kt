package com.focusblock.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SessionAndFrictionTest {
    private val min = SessionClock.MINUTE
    private val zone = ZoneId.of("UTC")
    private val start = 1_790_000_000_000L
    private val app = "com.example.video"

    private fun intervals() = SessionInput(1, setOf(app), SessionType.INTERVALS, Strength.NORMAL, start,
        start + SessionClock.plannedLengthMinutes(SessionType.INTERVALS, 0, 25, 5, 4)!! * min, 25, 5, 4)

    @Test fun plannedLengths() {
        assertEquals(45, SessionClock.plannedLengthMinutes(SessionType.TIMED, 45, 0, 0, 0))
        assertEquals(25 * 4 + 5 * 3, SessionClock.plannedLengthMinutes(SessionType.INTERVALS, 0, 25, 5, 4))
        assertNull(SessionClock.plannedLengthMinutes(SessionType.INDEFINITE, 0, 0, 0, 0))
    }

    @Test fun intervalPhasesFollowTheClock() {
        val s = intervals()
        SessionClock.state(s, start + 10 * min).let { assertEquals(SessionClock.Phase.FOCUS, it.phase); assertEquals(1, it.round); assertEquals(start + 25 * min, it.phaseEndsAt) }
        SessionClock.state(s, start + 26 * min).let { assertEquals(SessionClock.Phase.BREAK, it.phase); assertFalse(it.blocking); assertEquals(start + 30 * min, it.phaseEndsAt) }
        SessionClock.state(s, start + 31 * min).let { assertEquals(2, it.round); assertTrue(it.blocking) }
        SessionClock.state(s, start + 95 * min).let { assertEquals(4, it.round); assertEquals(SessionClock.Phase.FOCUS, it.phase) }
        assertTrue(SessionClock.state(s, start + 115 * min).ended)
    }

    @Test fun extensionAfterTheFinalRoundStaysBlocking() {
        val s = intervals()
        val extended = s.copy(plannedEndAt = SessionClock.extendedEnd(s, start + 114 * min))
        assertEquals(start + 130 * min, extended.plannedEndAt)
        assertTrue(SessionClock.state(extended, start + 120 * min).blocking)
    }

    @Test fun extendingAnEndedBlockStartsFromNow() {
        val s = SessionInput(1, setOf(app), SessionType.TIMED, Strength.NORMAL, start, start + 45 * min)
        assertEquals(start + 60 * min, SessionClock.extendedEnd(s, start + 45 * min))
        assertNull(SessionClock.extendedEnd(s.copy(type = SessionType.INDEFINITE, plannedEndAt = null), start))
    }

    @Test fun nextBoundaryUsesPhaseEnds() {
        assertEquals(start + 25 * min, SessionClock.nextBoundary(intervals(), start))
        val timed = SessionInput(1, setOf(app), SessionType.TIMED, Strength.NORMAL, start, start + 45 * min)
        assertEquals(start + 45 * min, SessionClock.nextBoundary(timed, start))
        assertNull(SessionClock.nextBoundary(timed, start + 46 * min))
    }

    @Test fun trustedClockAdjustsRemainingTime() {
        val s = SessionInput(1, setOf(app), SessionType.TIMED, Strength.STRICT, start, start + 45 * min, trustedNow = start + 5 * min)
        assertEquals(40 * min, SessionClock.remaining(s, start + 3 * 60 * min))
        assertFalse(SessionClock.clockTampered(start + 5 * min + 30_000, start + 5 * min))
    }

    // ---- Friction --------------------------------------------------------------------------------

    private fun decision(strength: Strength): BlockDecision {
        val s = PolicySnapshot(session = SessionInput(1, setOf(app), SessionType.TIMED, strength, start, start + 45 * min))
        return BlockPolicyEngine.evaluate(app, s, start + min, zone)
    }

    @Test fun openAnywayIsNoLongerOffered() {
        // Removed from the product: a Normal block offers only emergency access on its block screen.
        val d = decision(Strength.NORMAL)
        for (attempt in 1..5) {
            val offer = FrictionPolicy.offer(d, attempt)
            assertEquals(FrictionPolicy.OpenAnyway.NONE, offer.openAnyway)
            assertEquals(0, offer.waitSeconds)
            assertTrue(offer.emergency)
        }
    }

    @Test fun strictLockOffersOnlyEmergency() {
        val d = decision(Strength.STRICT)
        for (attempt in 1..4) {
            val offer = FrictionPolicy.offer(d, attempt)
            assertEquals(FrictionPolicy.OpenAnyway.NONE, offer.openAnyway)
            assertTrue(offer.emergency)
        }
    }

    @Test fun emergencyIsTimeBoxedAndNeedsAReason() {
        val e = FrictionPolicy.emergency(start)
        assertEquals(start + 10 * min, e.readyAt)
        assertEquals(5 * min, e.expiresAfterGrantMs)
        assertFalse(FrictionPolicy.emergencyUsable(e.readyAt, start + 9 * min))
        assertTrue(FrictionPolicy.emergencyUsable(e.readyAt, start + 10 * min))
        assertFalse(FrictionPolicy.emergencyUsable(e.readyAt, start + 71 * min))
        assertFalse(FrictionPolicy.emergencyReasonValid("  "))
        assertTrue(FrictionPolicy.emergencyReasonValid("Need the boarding pass"))
    }

    @Test fun attemptsAreCountedFromTheStartOfTheBlockOrWindow() {
        val s = PolicySnapshot(session = SessionInput(1, setOf(app), SessionType.TIMED, Strength.NORMAL, start, start + 45 * min))
        val d = BlockPolicyEngine.evaluate(app, s, start + min, zone)
        assertEquals(start, FrictionPolicy.attemptScopeStart(d.primary!!, s, start + min, zone))
        val r = PolicySnapshot(routines = listOf(RoutineInput(2, "Work", setOf(app), TimeWindow(0, 23 * 60, TimeWindow.EVERY_DAY), Strength.NORMAL)))
        val rd = BlockPolicyEngine.evaluate(app, r, start, zone)
        assertEquals(PolicyTime.startOfDay(start, zone), FrictionPolicy.attemptScopeStart(rd.primary!!, r, start, zone))
    }
}
