package com.focusblock.app.blocking

import com.focusblock.app.database.entity.Schedule

/** Schedule evaluation with correct ownership of the after-midnight portion. */
object SchedulePolicy {
    fun isActive(schedule: Schedule, currentMinute: Int, currentDay: Int): Boolean {
        if (!schedule.isEnabled || currentMinute !in 0..1439 || currentDay !in 1..7) return false
        val days = schedule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

        return when {
            schedule.startTimeMinutes < schedule.endTimeMinutes ->
                currentDay in days && currentMinute in schedule.startTimeMinutes until schedule.endTimeMinutes
            schedule.startTimeMinutes > schedule.endTimeMinutes && currentMinute >= schedule.startTimeMinutes ->
                currentDay in days
            schedule.startTimeMinutes > schedule.endTimeMinutes && currentMinute < schedule.endTimeMinutes ->
                previousDay(currentDay) in days
            else -> false
        }
    }

    private fun previousDay(day: Int): Int = if (day == 1) 7 else day - 1
}
