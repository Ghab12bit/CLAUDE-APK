package com.focusblock.app.blocking

import com.focusblock.app.database.entity.Schedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePolicyTest {
    private fun schedule(start: Int, end: Int, days: String = "1") = Schedule(
        name = "Test", startTimeMinutes = start, endTimeMinutes = end, daysOfWeek = days
    )

    @Test
    fun `normal schedule is active only inside selected day window`() {
        val schedule = schedule(9 * 60, 17 * 60)
        assertTrue(SchedulePolicy.isActive(schedule, 10 * 60, 1))
        assertFalse(SchedulePolicy.isActive(schedule, 18 * 60, 1))
        assertFalse(SchedulePolicy.isActive(schedule, 10 * 60, 2))
    }

    @Test
    fun `overnight tail belongs to previous selected day`() {
        val schedule = schedule(23 * 60, 7 * 60, days = "1")
        assertTrue(SchedulePolicy.isActive(schedule, 23 * 60 + 30, 1))
        assertTrue(SchedulePolicy.isActive(schedule, 2 * 60, 2))
        assertFalse(SchedulePolicy.isActive(schedule, 8 * 60, 2))
        assertFalse(SchedulePolicy.isActive(schedule, 2 * 60, 1))
    }
}
