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
    val pomodoroBreakMinutes: Int = 5,
    // Track which apps were ALREADY blocked before Quick Block started
    // These apps should NOT be unblocked when Quick Block stops
    val previouslyBlockedPackages: String = ""
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
    QUICK_BLOCK, SCHEDULE, STRICT_MODE, HARD_MODE, FOCUS_CYCLE
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
        const val KEY_STRICT_MODE_END_TIME = "strict_mode_end_time"
        const val KEY_STRICT_MODE_PAUSED = "strict_mode_paused"
        const val KEY_STRICT_MODE_REMAINING_ON_PAUSE = "strict_mode_remaining_on_pause"
        const val KEY_STRICT_MODE_PAUSE_REASON = "strict_mode_pause_reason"
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

@Entity(tableName = "app_time_limits")
data class AppTimeLimit(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int, // Daily usage limit in minutes
    val isEnabled: Boolean = true,
    val warningThreshold: Int = 5, // Minutes before limit to show warning
    val resetTime: Int = 0, // Minutes from midnight (default midnight)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "daily_usage")
data class DailyUsage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val date: String, // YYYY-MM-DD format
    val usageMinutes: Int = 0,
    val limitReached: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "excluded_apps")
data class ExcludedApp(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val excludedFrom: ExclusionType = ExclusionType.SCREEN_TIME_REPORT,
    val addedAt: Long = System.currentTimeMillis()
)

enum class ExclusionType {
    SCREEN_TIME_REPORT,
    ALL_REPORTS
}

/**
 * Focus Cycle - Soft-nudge mode for mindful app usage
 * Prevents binge-switching while keeping control user-driven
 *
 * Lifecycle:
 * 1. User enables → isEnabled=true, isArmed=true (waiting for app open)
 * 2. User opens selected app → isArmed=false, cycleStartTime set, timer starts
 * 3. User switches to non-selected app → isPaused=true, timer pauses
 * 4. User returns to selected app → isPaused=false, timer resumes
 * 5. Usage window ends → breakStartTime set, break period begins
 * 6. Break ends → cycle resets, isArmed=true again
 */
@Entity(tableName = "focus_cycles")
data class FocusCycle(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "Focus Cycle",
    val usageWindowMinutes: Int = 10, // How long apps are allowed
    val breakDurationMinutes: Int = 30, // How long break lasts
    val isEnabled: Boolean = false,
    val isActive: Boolean = false, // Currently in an active cycle
    val isArmed: Boolean = true, // Waiting for first selected app open
    val isPaused: Boolean = false, // Timer paused (user on non-selected app)
    val selectedPackages: String = "", // Comma-separated, empty = use Quick Block apps
    val useQuickBlockApps: Boolean = true, // Reuse apps from Quick Block
    val cycleStartTime: Long? = null, // When current cycle started
    val breakStartTime: Long? = null, // When break started (null if in usage window)
    val accumulatedUsageMillis: Long = 0, // Accumulated usage time for pause/resume
    val lastActiveTime: Long? = null, // Last time user was on a selected app
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Tracks override events for Focus Cycle soft-nudge
 */
@Entity(tableName = "focus_cycle_overrides")
data class FocusCycleOverride(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val focusCycleId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String,
    val appName: String
)
