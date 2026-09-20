package com.focusblock.app.blocking

import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Tests for the time condition, including the day-boundary case the previous
 * SQL got wrong.
 */
class BlockRuleTimeConditionTest {

    private fun at(day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun rule(
        start: Int,
        end: Int,
        days: String = "1,2,3,4,5,6,7"
    ) = BlockRule(
        name = "test",
        kind = RuleKind.AUTOMATIC,
        hasTimeCondition = true,
        startMinute = start,
        endMinute = end,
        daysOfWeek = days
    )

    // ---------------- Ordinary same-day window ----------------

    @Test
    fun `evening block is active inside its window`() {
        // 20:45 -> 22:30, weekdays
        val r = rule(20 * 60 + 45, 22 * 60 + 30, "1,2,3,4,5")
        assertTrue(r.timeConditionMatches(at(Calendar.WEDNESDAY, 21, 0)))
    }

    @Test
    fun `evening block is inactive before it starts`() {
        val r = rule(20 * 60 + 45, 22 * 60 + 30, "1,2,3,4,5")
        assertFalse(r.timeConditionMatches(at(Calendar.WEDNESDAY, 20, 30)))
    }

    @Test
    fun `evening block is inactive after it ends`() {
        val r = rule(20 * 60 + 45, 22 * 60 + 30, "1,2,3,4,5")
        assertFalse(r.timeConditionMatches(at(Calendar.WEDNESDAY, 22, 31)))
    }

    @Test
    fun `end minute is exclusive`() {
        val r = rule(20 * 60, 22 * 60)
        assertFalse(r.timeConditionMatches(at(Calendar.WEDNESDAY, 22, 0)))
        assertTrue(r.timeConditionMatches(at(Calendar.WEDNESDAY, 21, 59)))
    }

    @Test
    fun `start minute is inclusive`() {
        val r = rule(20 * 60, 22 * 60)
        assertTrue(r.timeConditionMatches(at(Calendar.WEDNESDAY, 20, 0)))
    }

    @Test
    fun `inactive on a day not selected`() {
        val r = rule(20 * 60, 22 * 60, "1,2,3,4,5")
        assertFalse(r.timeConditionMatches(at(Calendar.SATURDAY, 21, 0)))
    }

    // ---------------- Overnight window ----------------

    @Test
    fun `overnight block is active in the evening part`() {
        // 22:00 -> 06:00
        val r = rule(22 * 60, 6 * 60)
        assertTrue(r.timeConditionMatches(at(Calendar.MONDAY, 23, 0)))
    }

    @Test
    fun `overnight block is active in the morning part`() {
        val r = rule(22 * 60, 6 * 60)
        assertTrue(r.timeConditionMatches(at(Calendar.MONDAY, 2, 0)))
    }

    @Test
    fun `overnight block is inactive in the middle of the day`() {
        val r = rule(22 * 60, 6 * 60)
        assertFalse(r.timeConditionMatches(at(Calendar.MONDAY, 12, 0)))
    }

    /**
     * REGRESSION: the old SQL required daysOfWeek to contain TODAY, so a
     * Mon-Fri 22:00-06:00 rule stopped blocking the instant the clock passed
     * midnight into Saturday. The 01:00 Saturday period belongs to FRIDAY's
     * window and must still be blocked.
     */
    @Test
    fun `overnight weekday block still holds after midnight into saturday`() {
        val r = rule(22 * 60, 6 * 60, "1,2,3,4,5")
        assertTrue(
            "Friday night's block must survive into Saturday morning",
            r.timeConditionMatches(at(Calendar.SATURDAY, 1, 0))
        )
    }

    /**
     * The mirror case: Saturday EVENING is not selected, so it must not block,
     * even though Saturday morning did (as the tail of Friday).
     */
    @Test
    fun `overnight weekday block does not start on saturday evening`() {
        val r = rule(22 * 60, 6 * 60, "1,2,3,4,5")
        assertFalse(
            "Saturday evening is not a selected day",
            r.timeConditionMatches(at(Calendar.SATURDAY, 23, 0))
        )
    }

    @Test
    fun `overnight monday only block does not leak into tuesday evening`() {
        val r = rule(22 * 60, 6 * 60, "1")
        assertTrue(r.timeConditionMatches(at(Calendar.MONDAY, 23, 0)))
        assertTrue(r.timeConditionMatches(at(Calendar.TUESDAY, 3, 0)))
        assertFalse(r.timeConditionMatches(at(Calendar.TUESDAY, 23, 0)))
    }

    @Test
    fun `sunday to monday wraparound is handled`() {
        // Sunday is day 7; the morning tail lands on Monday.
        val r = rule(22 * 60, 6 * 60, "7")
        assertTrue(r.timeConditionMatches(at(Calendar.SUNDAY, 23, 0)))
        assertTrue(r.timeConditionMatches(at(Calendar.MONDAY, 3, 0)))
    }

    @Test
    fun `monday morning tail requires sunday to be selected`() {
        val r = rule(22 * 60, 6 * 60, "1")
        assertFalse(
            "Monday 03:00 belongs to Sunday's window, which is not selected",
            r.timeConditionMatches(at(Calendar.MONDAY, 3, 0))
        )
    }

    @Test
    fun `no days selected never matches`() {
        val r = rule(20 * 60, 22 * 60, "")
        assertFalse(r.timeConditionMatches(at(Calendar.MONDAY, 21, 0)))
    }

    @Test
    fun `rule without time condition always matches`() {
        val r = BlockRule(name = "always", hasTimeCondition = false)
        assertTrue(r.timeConditionMatches(at(Calendar.MONDAY, 3, 0)))
    }

    // ---------------- Manual sessions ----------------

    @Test
    fun `manual session runs until its end time`() {
        val now = 1_000_000L
        val r = BlockRule(
            name = "Block now",
            kind = RuleKind.MANUAL,
            isManualActive = true,
            activeUntil = now + 60_000L
        )
        assertTrue(r.manualIsRunning(now))
        assertTrue(r.manualIsRunning(now + 59_999L))
    }

    /**
     * REGRESSION: an expired Quick Block used to stay isActive in the database
     * because expiry depended on an in-service Handler. Expiry must be a
     * property of the data, true regardless of which service is alive.
     */
    @Test
    fun `manual session is not running once its end time passes`() {
        val now = 1_000_000L
        val r = BlockRule(
            name = "Block now",
            kind = RuleKind.MANUAL,
            isManualActive = true,
            activeUntil = now + 60_000L
        )
        assertFalse(r.manualIsRunning(now + 60_000L))
        assertFalse(r.manualIsRunning(now + 10 * 60_000L))
    }

    @Test
    fun `indefinite manual session runs until stopped`() {
        val r = BlockRule(
            name = "Block now",
            kind = RuleKind.MANUAL,
            isManualActive = true,
            activeUntil = null
        )
        assertTrue(r.manualIsRunning(Long.MAX_VALUE / 2))
    }

    @Test
    fun `stopped manual session is not running`() {
        val r = BlockRule(name = "x", kind = RuleKind.MANUAL, isManualActive = false)
        assertFalse(r.manualIsRunning(0L))
    }

    @Test
    fun `automatic rule is never a running manual session`() {
        val r = BlockRule(name = "x", kind = RuleKind.AUTOMATIC, isManualActive = true)
        assertFalse(r.manualIsRunning(0L))
    }

    // ---------------- Package coverage ----------------

    @Test
    fun `covers matches whole package names only`() {
        val r = BlockRule(name = "x", packages = "com.instagram.android,com.reddit.frontpage")
        assertTrue(r.covers("com.instagram.android"))
        assertTrue(r.covers("com.reddit.frontpage"))
        assertFalse(r.covers("com.instagram"))
        assertFalse(r.covers("com.instagram.android.extra"))
    }

    @Test
    fun `empty package list covers nothing`() {
        val r = BlockRule(name = "x", packages = "")
        assertFalse(r.covers("com.instagram.android"))
    }

    @Test
    fun `package list tolerates whitespace and trailing commas`() {
        val r = BlockRule(name = "x", packages = "com.a, com.b ,,")
        assertEquals(listOf("com.a", "com.b"), r.packageList())
    }
}
