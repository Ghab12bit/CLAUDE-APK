package com.focusblock.app.utils

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object TimeUtils {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val timeFormat24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.getDefault())

    fun getCurrentDateString(): String = dateFormat.format(Date())

    fun getDateString(timestamp: Long): String = dateFormat.format(Date(timestamp))

    fun getTimeString(timestamp: Long): String = timeFormat.format(Date(timestamp))

    fun getDateTimeString(timestamp: Long): String = dateTimeFormat.format(Date(timestamp))

    fun getCurrentMinuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    fun getCurrentDayOfWeek(): Int {
        val calendar = Calendar.getInstance()
        // Convert to Monday=1, Sunday=7 format
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }

    fun minutesToTimeString(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        val isPM = hours >= 12
        val displayHour = when {
            hours == 0 -> 12
            hours > 12 -> hours - 12
            else -> hours
        }
        val period = if (isPM) "PM" else "AM"
        return String.format(Locale.getDefault(), "%d:%02d %s", displayHour, mins, period)
    }

    fun minutesToTimeString24(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, mins)
    }

    fun timeStringToMinutes(timeString: String): Int {
        val parts = timeString.split(":")
        if (parts.size != 2) return 0
        return parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: 0
    }

    fun getStartOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun getStartOfWeek(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun getStartOfMonth(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun formatDurationShort(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun formatTimer(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    fun formatTimerWithHours(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun getDaysOfWeekString(daysString: String): String {
        val days = daysString.split(",").mapNotNull { it.trim().toIntOrNull() }
        val dayNames = mapOf(
            1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
            5 to "Fri", 6 to "Sat", 7 to "Sun"
        )
        return if (days.size == 7) {
            "Every day"
        } else if (days.containsAll(listOf(1, 2, 3, 4, 5)) && days.size == 5) {
            "Weekdays"
        } else if (days.containsAll(listOf(6, 7)) && days.size == 2) {
            "Weekends"
        } else {
            days.mapNotNull { dayNames[it] }.joinToString(", ")
        }
    }

    fun parseDaysOfWeek(daysString: String): List<Int> {
        return daysString.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun daysOfWeekToString(days: List<Int>): String {
        return days.sorted().joinToString(",")
    }
}
