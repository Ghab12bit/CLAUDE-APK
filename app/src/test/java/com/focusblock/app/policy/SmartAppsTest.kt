package com.focusblock.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SmartAppsTest {
    private val min = 60_000L
    private fun day(d: Int, vararg usage: Pair<String, Long>) =
        DayUsage(LocalDate.of(2026, 10, d), null, usage.toMap(), emptyMap(), null, null, null)

    private val categories = mapOf(
        "insta" to AppCategory.DISTRACTING, "youtube" to AppCategory.DISTRACTING,
        "docs" to AppCategory.PRODUCTIVE, "chrome" to AppCategory.NEUTRAL, "game" to AppCategory.DISTRACTING,
    )
    private val categoryOf: (String) -> AppCategory = { categories[it] ?: AppCategory.NEUTRAL }

    @Test fun ranksDistractingTimeFirstAndSkipsProductiveAndIneligible() {
        val days = listOf(
            day(1, "insta" to 90 * min, "youtube" to 40 * min, "docs" to 200 * min, "chrome" to 80 * min, "phone" to 120 * min),
            day(2, "insta" to 70 * min, "youtube" to 60 * min, "docs" to 180 * min, "chrome" to 80 * min, "phone" to 100 * min),
        )
        val ranked = SmartApps.rank(days, categoryOf, eligible = { it != "phone" })
        assertEquals(listOf("insta", "youtube", "chrome"), ranked.map { it.pkg }) // chrome: 80 × 0.6 = 48 < youtube 50
        assertEquals(80 * min, ranked.first().dailyAverage)
        assertTrue(ranked.none { it.pkg == "docs" || it.pkg == "phone" })
    }

    @Test fun lightUseIsIgnoredUnlessItWasBlockedOften() {
        val days = listOf(day(1, "game" to 4 * min), day(2, "game" to 2 * min))
        assertTrue(SmartApps.rank(days, categoryOf, { true }).isEmpty())
        val ranked = SmartApps.rank(days, categoryOf, { true }, attempts = mapOf("game" to 4))
        assertEquals("game", ranked.single().pkg)
        assertEquals(4, ranked.single().attempts)
    }

    @Test fun followsUsageAsItChanges() {
        val before = listOf(day(1, "insta" to 120 * min, "youtube" to 20 * min))
        val after = listOf(day(1, "insta" to 15 * min, "youtube" to 150 * min))
        assertEquals("insta", SmartApps.rank(before, categoryOf, { true }).first().pkg)
        assertEquals("youtube", SmartApps.rank(after, categoryOf, { true }).first().pkg)
    }
}
