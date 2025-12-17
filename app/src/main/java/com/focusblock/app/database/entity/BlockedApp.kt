package com.focusblock.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean = true,
    val isInAllowlist: Boolean = false,
    val blockedCount: Int = 0,
    val totalBlockedCount: Int = 0,
    val lastBlockedTime: Long = 0,
    val addedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val iconType: ScheduleIconType = ScheduleIconType.WORK,
    val colorHex: String = "#0A84FF",
    val isEnabled: Boolean = true,
    val startTimeMinutes: Int, // Minutes from midnight
    val endTimeMinutes: Int,   // Minutes from midnight
    val daysOfWeek: String = "1,2,3,4,5", // Comma-separated (1=Monday, 7=Sunday)
    val blockedPackages: String = "", // Comma-separated package names
    val isStrictMode: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ScheduleIconType {
    WORK, SLEEP, STUDY, FAMILY, SOCIAL, DETOX, FOCUS, WIND_DOWN, CUSTOM
}

@Entity(tableName = "quick_block_sessions")
data class QuickBlockSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long?, // null if indefinite
    val blockedPackages: String,
    val isActive: Boolean = true,
    val isPomodoroSession: Boolean = false,
    val pomodoroWorkMinutes: Int = 25,
    val pomodoroBreakMinutes: Int = 5
)

@Entity(tableName = "block_logs")
data class BlockLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val blockedBy: BlockedByType,
    val scheduleId: Long? = null,
    val scheduleName: String? = null
)

enum class BlockedByType {
    QUICK_BLOCK, SCHEDULE, STRICT_MODE, HARD_MODE
}

@Entity(tableName = "usage_stats")
data class UsageStat(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val date: String, // YYYY-MM-DD format
    val usageTimeMillis: Long = 0,
    val openCount: Int = 0,
    val blockedCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class AppSettings(
    @PrimaryKey
    val key: String,
    val value: String
) {
    companion object {
        const val KEY_STRICT_MODE_ENABLED = "strict_mode_enabled"
        const val KEY_HARD_MODE_ENABLED = "hard_mode_enabled"
        const val KEY_HARD_MODE_PIN = "hard_mode_pin"
        const val KEY_HARD_MODE_UNLOCK_TIME = "hard_mode_unlock_time"
        const val KEY_BLOCKING_SERVICE_ENABLED = "blocking_service_enabled"
        const val KEY_USE_ACCESSIBILITY_SERVICE = "use_accessibility_service"
        const val KEY_SHOW_NOTIFICATION = "show_notification"
        const val KEY_VIBRATE_ON_BLOCK = "vibrate_on_block"
        const val KEY_POMODORO_WORK_MINUTES = "pomodoro_work_minutes"
        const val KEY_POMODORO_SHORT_BREAK = "pomodoro_short_break"
        const val KEY_POMODORO_LONG_BREAK = "pomodoro_long_break"
        const val KEY_POMODORO_SESSIONS_UNTIL_LONG = "pomodoro_sessions_until_long"
    }
}

@Entity(tableName = "pomodoro_sessions")
data class PomodoroSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val sessionType: PomodoroSessionType,
    val durationMinutes: Int,
    val completed: Boolean = false,
    val date: String // YYYY-MM-DD format
)

enum class PomodoroSessionType {
    WORK, SHORT_BREAK, LONG_BREAK
}
