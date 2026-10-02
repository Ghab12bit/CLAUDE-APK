package com.focusblock.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BlockPolicyEngineTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val app = "com.instagram.android"
    private val min = SessionClock.MINUTE

    /** Friday 2 Oct 2026, 21:00 local. */
    private val now = at(2026, 10, 2, 21, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, m, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun session(strength: Strength = Strength.NORMAL, end: Long? = now + 45 * min, type: SessionType = SessionType.TIMED) =
        SessionInput(7, setOf(app), type, strength, now - 5 * min, end)

    private fun routine(strength: Strength = Strength.NORMAL, id: Long = 3) =
        RoutineInput(id, "Evening routine", setOf(app), TimeWindow(20 * 60 + 45, 22 * 60 + 30, TimeWindow.EVERY_DAY), strength)

    private val bedtime = BedtimeInput(true, TimeWindow(20 * 60, 23 * 60, TimeWindow.EVERY_DAY))
    private val appLimit = AppLimitInput(4, "Social", setOf(app), 30, 31 * min)
    private val dailyLimit = DailyLimitInput(true, 60, null, 61 * min)
    private val focusCycle = FocusCycleInput(5, "Focus Cycle", setOf(app), true, now + 10 * min)

    private fun decide(s: PolicySnapshot) = BlockPolicyEngine.evaluate(app, s, now, zone)

    // ---- Every overlap pair in spec 8.2 ----------------------------------------------------

    private val ladder: List<Pair<String, PolicySnapshot>> = listOf(
        "strict session" to PolicySnapshot(session = session(Strength.STRICT)),
        "strict routine" to PolicySnapshot(routines = listOf(routine(Strength.STRICT))),
        "bedtime" to PolicySnapshot(bedtime = bedtime),
        "routine" to PolicySnapshot(routines = listOf(routine())),
        "app limit" to PolicySnapshot(appLimits = listOf(appLimit)),
        "daily limit" to PolicySnapshot(dailyLimit = dailyLimit),
        "focus cycle" to PolicySnapshot(focusCycles = listOf(focusCycle)),
        "normal session" to PolicySnapshot(session = session()),
    )

    private fun merge(a: PolicySnapshot, b: PolicySnapshot) = PolicySnapshot(
        session = a.session ?: b.session,
        routines = a.routines + b.routines.map { it.copy(id = it.id + 100) },
        bedtime = a.bedtime ?: b.bedtime,
        appLimits = a.appLimits + b.appLimits,
        dailyLimit = a.dailyLimit ?: b.dailyLimit,
        focusCycles = a.focusCycles + b.focusCycles,
    )

    @Test fun everyOverlapPairResolvesToTheHigherPriorityReason() {
        for (i in ladder.indices) for (j in ladder.indices) {
            if (i >= j) continue
            val (highName, high) = ladder[i]
            val (lowName, low) = ladder[j]
            if (high.session != null && low.session != null) continue // only one immediate session exists
            val d = decide(merge(high, low))
            val expected = decide(high).primary!!
            assertTrue("$highName vs $lowName must block", d.blocked)
            assertEquals("$highName should outrank $lowName", expected.type, d.primary!!.type)
            assertEquals(expected.strength, d.primary!!.strength)
            assertEquals("$lowName must be listed as also blocking", 1, d.others.size)
        }
    }

    @Test fun eachReasonAloneBlocks() {
        ladder.forEach { (name, snap) -> assertTrue(name, decide(snap).blocked) }
        assertFalse(decide(PolicySnapshot()).blocked)
    }

    // ---- Exemptions -------------------------------------------------------------------------

    @Test fun essentialAndSafetyAppsAreNeverBlocked() {
        val everything = PolicySnapshot(
            session = session(Strength.STRICT), routines = listOf(routine(Strength.STRICT)), bedtime = bedtime,
            appLimits = listOf(appLimit), dailyLimit = dailyLimit, exempt = setOf(app),
        )
        val d = decide(everything)
        assertFalse(d.blocked)
        assertTrue(d.exempt)
        assertTrue(BlockPolicyEngine.reasons(app, everything, now, zone).isEmpty())
    }

    @Test fun bedtimeBlocksEveryNonExemptAppButNotEssentials() {
        val s = PolicySnapshot(bedtime = bedtime, exempt = setOf("com.android.dialer"))
        assertTrue(BlockPolicyEngine.evaluate("any.other.app", s, now, zone).blocked)
        assertFalse(BlockPolicyEngine.evaluate("com.android.dialer", s, now, zone).blocked)
    }

    // ---- Overrides ----------------------------------------------------------------------------

    @Test fun openAnywayUnlocksNormalReasonsForThatAppOnly() {
        val s = PolicySnapshot(routines = listOf(routine()), overrides = listOf(OverrideInput(app, OverrideKind.OPEN_ANYWAY, now + 5 * min)))
        val d = decide(s)
        assertFalse(d.blocked)
        assertEquals(OverrideKind.OPEN_ANYWAY, d.activeOverride!!.kind)
        val other = RoutineInput(3, "Evening routine", setOf(app, "com.other"), routine().window, Strength.NORMAL)
        assertTrue(BlockPolicyEngine.evaluate("com.other", s.copy(routines = listOf(other)), now, zone).blocked)
    }

    @Test fun openAnywayNeverUnlocksAnAppAlsoCoveredByStrict() {
        val s = PolicySnapshot(
            session = session(Strength.STRICT), routines = listOf(routine()),
            overrides = listOf(OverrideInput(app, OverrideKind.OPEN_ANYWAY, now + 5 * min)),
        )
        val d = decide(s)
        assertTrue(d.blocked)
        assertFalse(d.bypass.openAnyway)
        assertTrue(d.bypass.emergency)
    }

    @Test fun emergencyOverrideOpensStrictForThatAppUntilItExpires() {
        val s = PolicySnapshot(session = session(Strength.STRICT), overrides = listOf(OverrideInput(app, OverrideKind.EMERGENCY, now + 5 * min)))
        assertFalse(decide(s).blocked)
        assertTrue(BlockPolicyEngine.evaluate(app, s, now + 5 * min, zone).blocked)
        assertTrue(BlockPolicyEngine.evaluate("com.other", s.copy(session = s.session!!.copy(packages = setOf(app, "com.other"))), now, zone).blocked)
    }

    @Test fun expiredOverridesAreIgnored() {
        val s = PolicySnapshot(routines = listOf(routine()), overrides = listOf(OverrideInput(app, OverrideKind.OPEN_ANYWAY, now - 1)))
        assertTrue(decide(s).blocked)
    }

    @Test fun strictLockAllowsOnlyEmergencyBypass() {
        val d = decide(PolicySnapshot(session = session(Strength.STRICT)))
        assertEquals(Strength.STRICT, d.strength)
        assertEquals(AllowedBypass(openAnyway = false, emergency = true), d.bypass)
        val n = decide(PolicySnapshot(session = session()))
        assertEquals(AllowedBypass(openAnyway = true, emergency = true), n.bypass)
    }

    // ---- Sessions ------------------------------------------------------------------------------

    @Test fun sessionStopsBlockingAtItsEnd() {
        val s = PolicySnapshot(session = session(end = now + min))
        assertTrue(decide(s).blocked)
        assertFalse(BlockPolicyEngine.evaluate(app, s, now + min, zone).blocked)
    }

    @Test fun indefiniteSessionBlocksUntilEnded() {
        val s = PolicySnapshot(session = session(end = null, type = SessionType.INDEFINITE))
        val d = BlockPolicyEngine.evaluate(app, s, now + 1000 * min, zone)
        assertTrue(d.blocked)
        assertNull(d.primary!!.until)
        assertNull(d.availableAt)
    }

    @Test fun manualClockChangeDoesNotEndAStrictBlockEarly() {
        // The wall clock jumped two hours ahead; elapsed realtime says only ten minutes passed.
        val tampered = session(Strength.STRICT).copy(trustedNow = now + 10 * min)
        val d = BlockPolicyEngine.evaluate(app, PolicySnapshot(session = tampered), now + 120 * min, zone)
        assertTrue(d.blocked)
        assertTrue(SessionClock.clockTampered(now + 120 * min, now + 10 * min))
    }

    @Test fun intervalsUnblockDuringBreaksUnlessARuleAlsoCovers() {
        val s = SessionInput(9, setOf(app), SessionType.INTERVALS, Strength.NORMAL, now, now + (25 * 4 + 5 * 3) * min, 25, 5, 4)
        val inBreak = now + 27 * min
        assertFalse(BlockPolicyEngine.evaluate(app, PolicySnapshot(session = s), inBreak, zone).blocked)
        assertTrue(BlockPolicyEngine.evaluate(app, PolicySnapshot(session = s, routines = listOf(routine())), inBreak, zone).blocked)
        assertTrue(BlockPolicyEngine.evaluate(app, PolicySnapshot(session = s), now + 31 * min, zone).blocked)
    }

    // ---- Limits ----------------------------------------------------------------------------------

    @Test fun appLimitBlocksOnlyItsAppsOnceTheAllowanceIsUsed() {
        val under = appLimit.copy(usedMillisToday = 29 * min)
        assertFalse(decide(PolicySnapshot(appLimits = listOf(under))).blocked)
        assertTrue(decide(PolicySnapshot(appLimits = listOf(appLimit))).blocked)
        assertFalse(BlockPolicyEngine.evaluate("com.other", PolicySnapshot(appLimits = listOf(appLimit)), now, zone).blocked)
    }

    @Test fun limitsResetAtLocalMidnight() {
        val d = decide(PolicySnapshot(appLimits = listOf(appLimit)))
        assertEquals(at(2026, 10, 3, 0, 0), d.primary!!.until)
    }

    @Test fun dailyLimitCountsOnlyCountedAppsWhenAListIsGiven() {
        val limited = dailyLimit.copy(countedPackages = setOf("com.youtube"))
        assertFalse(decide(PolicySnapshot(dailyLimit = limited)).blocked)
        assertTrue(BlockPolicyEngine.evaluate("com.youtube", PolicySnapshot(dailyLimit = limited), now, zone).blocked)
        assertTrue(decide(PolicySnapshot(dailyLimit = dailyLimit)).blocked)
    }

    @Test fun legacyHardModeLockMakesTheDailyLimitStrictUntilItExpires() {
        val locked = dailyLimit.copy(lockedUntil = now + 60 * min)
        assertEquals(Strength.STRICT, decide(PolicySnapshot(dailyLimit = locked)).strength)
        assertEquals(Strength.NORMAL, BlockPolicyEngine.evaluate(app, PolicySnapshot(dailyLimit = locked), now + 61 * min, zone).strength)
    }

    @Test fun limitReachedAtPredictsTheRecheck() {
        val s = PolicySnapshot(appLimits = listOf(appLimit.copy(usedMillisToday = 20 * min)))
        assertEquals(now + 10 * min, BlockPolicyEngine.limitReachedAt(app, s, now))
        assertNull(BlockPolicyEngine.limitReachedAt("com.other", s, now))
    }

    // ---- Imported rules --------------------------------------------------------------------------

    @Test fun importedConditionOnlyRulesRespectTheirConditionAndDeadline() {
        val imported = RoutineInput(50, "Imported", setOf(app), null, Strength.NORMAL, conditionsMet = true, activeUntil = now + 10 * min, imported = true)
        assertTrue(decide(PolicySnapshot(routines = listOf(imported))).blocked)
        assertFalse(decide(PolicySnapshot(routines = listOf(imported.copy(conditionsMet = false)))).blocked)
        assertFalse(BlockPolicyEngine.evaluate(app, PolicySnapshot(routines = listOf(imported)), now + 10 * min, zone).blocked)
    }

    // ---- Next change -----------------------------------------------------------------------------

    @Test fun nextChangeIsTheEarliestBoundary() {
        val s = PolicySnapshot(
            session = session(end = now + 45 * min),
            routines = listOf(routine()),
            overrides = listOf(OverrideInput(app, OverrideKind.OPEN_ANYWAY, now + 3 * min)),
        )
        assertEquals(now + 3 * min, BlockPolicyEngine.nextChangeAt(s, now, zone))
        assertEquals(now + 45 * min, BlockPolicyEngine.nextChangeAt(s.copy(overrides = emptyList()), now, zone))
        assertEquals(at(2026, 10, 3, 0, 0), BlockPolicyEngine.nextChangeAt(PolicySnapshot(appLimits = listOf(appLimit)), now, zone))
    }

    @Test fun availableAtIsTheLatestEndAcrossReasons() {
        val d = decide(PolicySnapshot(session = session(end = now + 20 * min), routines = listOf(routine())))
        assertEquals(at(2026, 10, 2, 22, 30), d.availableAt)
    }
}
