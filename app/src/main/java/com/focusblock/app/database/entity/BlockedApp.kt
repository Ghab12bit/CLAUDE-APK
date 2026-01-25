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
    QUICK_BLOCK, SCHEDULE, STRICT_MODE, HARD_MODE, FOCUS_CYCLE, APP_TIMER, GLOBAL_LIMIT, BEDTIME
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
        // Pause limit tracking (persists across restart/reboot)
        const val KEY_STRICT_MODE_PAUSE_COUNT_TODAY = "strict_mode_pause_count_today"
        const val KEY_STRICT_MODE_PAUSE_RESET_TIME = "strict_mode_pause_reset_time"
        const val KEY_STRICT_MODE_MAX_PAUSES_PER_DAY = "strict_mode_max_pauses_per_day"
        // Emergency unlock tracking (once per day)
        const val KEY_STRICT_MODE_EMERGENCY_UNLOCK_USED_DATE = "strict_mode_emergency_unlock_used_date"
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

        // 20% Usage Reduction Notification
        const val KEY_USAGE_REDUCTION_NOTIFICATION_ENABLED = "usage_reduction_notification_enabled"
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

// ========== APP TIMER (Shared Time Limit) ==========

/**
 * App Timer Settings - tracks shared daily time limit across multiple apps
 * User sets a combined limit (e.g., 30 min) for all timer apps together
 */
@Entity(tableName = "app_timer_settings")
data class AppTimerSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton - only one settings record
    val isEnabled: Boolean = false,
    val dailyLimitMinutes: Int = 30, // Default 30 min combined limit
    val escalationThresholdMinutes: Int = 20, // Extra minutes after limit for escalation
    val timerApps: String = "", // Comma-separated package names
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Default dopamine/distraction apps
        val DEFAULT_TIMER_APPS = listOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.snapchat.android",
            "com.reddit.frontpage",
            "com.twitter.android",
            "com.facebook.katana",
            "com.facebook.orca" // Messenger
        )
    }
}

/**
 * App Timer Daily Usage - tracks combined usage per day
 * Resets at midnight
 */
@Entity(tableName = "app_timer_daily_usage")
data class AppTimerDailyUsage(
    @PrimaryKey
    val date: String, // YYYY-MM-DD format
    val totalUsageMinutes: Int = 0,
    val limitReachedPopupShown: Boolean = false,
    val escalationPopupShown: Boolean = false,
    val addedToQuickBlock: Boolean = false,
    val dailyOverrideUsed: Boolean = false, // User has used their one daily override
    val overrideExpiresAt: Long? = null, // When the current override expires (15 min window)
    val lastUpdated: Long = System.currentTimeMillis()
)

// ========== GLOBAL DAILY USAGE LIMIT ==========

/**
 * Global Daily Limit Settings - total phone usage limit independent of all modes
 * Works even when App Timer, Focus Cycle, and Quick Block are all OFF
 *
 * NEW APPROACH: Only tracks specific distracting apps (whitelist)
 * Instead of blocking everything except exclusions
 */
@Entity(tableName = "global_daily_limit_settings")
data class GlobalDailyLimitSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton - only one settings record
    val isEnabled: Boolean = true, // Enabled by default
    val dailyLimitMinutes: Int = 180, // Default 3 hours
    val warningMinutesBefore: Int = 15, // Show warning 15 min before limit
    // NEW: Apps to track (comma-separated package names)
    // Only these apps count toward the daily limit
    val trackedPackages: String = "", // Empty = use default distracting apps
    val useAppTimerApps: Boolean = true, // Share app list with App Timer

    // WHITELIST: Apps that are tracked but NOT blocked (e.g., WhatsApp for work)
    // These apps count toward usage but won't be blocked when limit is reached
    val whitelistedPackages: String = "", // Comma-separated package names

    // HARD MODE - Prevents easy bypass of limits
    val isHardModeEnabled: Boolean = false, // When true, adds friction to disable
    val hardModeLockUntil: Long = 0L, // Timestamp - can't disable until this time
    val hardModeUnlockRequestedAt: Long = 0L, // When user requested unlock (cooldown starts)
    val hardModeCooldownMinutes: Int = 15, // Default 15 min cooldown to unlock
    val hardModeRequirePhrase: Boolean = true, // Require typing phrase after cooldown
    val hardModeUnlockPhrase: String = "I choose distraction over my goals", // Phrase to type

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Default distracting apps to track
        // These are the apps that typically waste time
        val DEFAULT_DISTRACTING_APPS = listOf(
            // Social Media
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.orca", // Messenger
            "com.twitter.android",
            "com.snapchat.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.linkedin.android",
            "com.pinterest",
            // Video/Entertainment
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", // Prime Video
            "com.disney.disneyplus",
            "tv.twitch.android.app",
            "com.hulu.plus",
            "com.hotstar.android",
            // Reddit & Forums
            "com.reddit.frontpage",
            "com.rubenmayayo.reddit", // Boost for Reddit
            "com.andrewshu.android.reddit", // Reddit is Fun
            // Dating
            "com.tinder",
            "com.bumble.app",
            // News/Content
            "com.buzzfeed.android",
            "flipboard.app",
            // Games (common ones)
            "com.supercell.clashofclans",
            "com.supercell.clashroyale",
            "com.king.candycrushsaga",
            "com.pubg.imobile", // BGMI
            "com.tencent.ig" // PUBG Mobile
        )

        // System apps that are NEVER tracked (always excluded)
        val SYSTEM_APPS = listOf(
            // Android core
            "com.android.settings",
            "com.android.systemui",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.phone",
            "com.android.mms",
            "com.android.camera",
            "com.android.camera2",
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.vending", // Play Store
            // Google apps
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.gms", // Google Play Services
            "com.google.android.gsf", // Google Services Framework
            // Samsung utilities (NEVER block these)
            "com.samsung.android.dialer",
            "com.samsung.android.messaging",
            "com.samsung.android.contacts",
            "com.sec.android.app.camera",
            "com.sec.android.app.launcher",
            "com.samsung.android.app.cocktailbarservice", // Edge Panel
            "com.samsung.android.sidegesturepad", // Side gesture
            "com.samsung.android.onehandoperation", // One Hand Operation+
            "com.samsung.android.app.routines", // Bixby Routines
            "com.samsung.android.app.smartcapture", // Screenshot
            "com.samsung.android.app.clipboardedge", // Clipboard
            "com.samsung.android.honeyboard", // Samsung Keyboard
            "com.samsung.android.app.taskedge", // Task Edge
            "com.samsung.android.samsungpass", // Samsung Pass
            "com.samsung.android.authfw", // Auth Framework
            "com.samsung.android.app.galaxyfinder", // Finder
            "com.samsung.android.game.gametools", // Game Tools
            "com.samsung.android.goodlock", // Good Lock
            "com.sec.android.app.popupcalculator", // Calculator
            "com.sec.android.app.clockpackage", // Clock
            "com.samsung.android.calendar", // Calendar
            "com.samsung.android.app.notes", // Notes
            // Other OEM utilities
            "com.miui.home", // Xiaomi
            "com.oppo.launcher", // Oppo
            "com.huawei.android.launcher", // Huawei
            // Our own app
            "com.focusblock.app"
        )
    }
}

/**
 * Global Daily Usage Tracking - tracks total phone usage per day
 * Survives app restart, device reboot
 */
@Entity(tableName = "global_daily_usage")
data class GlobalDailyUsage(
    @PrimaryKey
    val date: String, // YYYY-MM-DD format
    val totalUsageMinutes: Int = 0,
    val limitReached: Boolean = false,
    val warningShown: Boolean = false, // 100% limit warning
    val warning75Shown: Boolean = false, // 75% threshold warning
    val limitNotificationShown: Boolean = false,

    // ========== ESCALATING EMERGENCY UNLOCK SYSTEM ==========
    // Each emergency unlock is progressively more costly:
    // 1st: 15 min, no penalty
    // 2nd: 10 min, -10 min tomorrow
    // 3rd: 5 min, -20 min tomorrow
    // 4th+: BLOCKED (no more unlocks today)
    val emergencyUnlockCount: Int = 0, // How many emergency unlocks used today
    val currentEmergencyUnlockExpiresAt: Long? = null, // When current unlock window ends
    val tomorrowLimitPenaltyMinutes: Int = 0, // Penalty to apply to tomorrow's limit

    // Legacy override tracking (kept for compatibility)
    val overrideCount: Int = 0, // Number of times user overrode today
    val lastOverrideTime: Long? = null, // When last override was activated
    val overrideExpiresAt: Long? = null, // When current override window ends
    val overrideCooldownUntil: Long? = null, // Prevent rapid repeated overrides

    // 3+ hours excessive usage notification tracking
    val excessiveUsageNotificationShown: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        // Emergency unlock durations (in minutes)
        const val EMERGENCY_UNLOCK_1_MINUTES = 15
        const val EMERGENCY_UNLOCK_2_MINUTES = 10
        const val EMERGENCY_UNLOCK_3_MINUTES = 5
        const val MAX_EMERGENCY_UNLOCKS_PER_DAY = 3

        // Penalties for tomorrow's limit (in minutes)
        const val PENALTY_UNLOCK_2_MINUTES = 10
        const val PENALTY_UNLOCK_3_MINUTES = 20

        /**
         * Get emergency unlock duration for the given attempt number
         * Returns 0 if no more unlocks allowed
         */
        fun getEmergencyUnlockDuration(attemptNumber: Int): Int {
            return when (attemptNumber) {
                1 -> EMERGENCY_UNLOCK_1_MINUTES
                2 -> EMERGENCY_UNLOCK_2_MINUTES
                3 -> EMERGENCY_UNLOCK_3_MINUTES
                else -> 0 // No more unlocks allowed
            }
        }

        /**
         * Get penalty for tomorrow's limit for the given attempt number
         */
        fun getTomorrowPenalty(attemptNumber: Int): Int {
            return when (attemptNumber) {
                1 -> 0 // No penalty for first unlock
                2 -> PENALTY_UNLOCK_2_MINUTES
                3 -> PENALTY_UNLOCK_3_MINUTES
                else -> 0
            }
        }
    }
}

// ========== DAILY USAGE COMPARISON (TODAY VS YESTERDAY) ==========

/**
 * Tracks daily comparison notifications to ensure once-per-day trigger
 * Also stores aggregate daily totals for comparison
 */
@Entity(tableName = "daily_usage_summary")
data class DailyUsageSummary(
    @PrimaryKey
    val date: String, // YYYY-MM-DD format
    val totalScreenTimeMinutes: Int = 0, // Total phone usage for this day
    val comparisonNotificationSent: Boolean = false, // Whether we sent today vs yesterday notification
    val comparisonResult: String? = null, // "higher", "lower", or null if not compared
    val lastUpdated: Long = System.currentTimeMillis()
)

// ========== SMART SUGGESTIONS ==========

/**
 * Smart Suggestions Settings - tracks user's smart onboarding preferences
 * and adaptive daily limits based on 7-day usage analysis
 */
@Entity(tableName = "smart_suggestions_settings")
data class SmartSuggestionsSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton
    val isEnabled: Boolean = true, // Smart suggestions enabled
    val hasCompletedOnboarding: Boolean = false, // User completed initial setup
    val lastAnalysisDate: String? = null, // Last time we analyzed usage (YYYY-MM-DD)
    val averageDailyUsageMinutes: Int = 0, // 7-day average usage
    val suggestedDailyLimitMinutes: Int = 0, // 80% of average (20% reduction goal)
    val autoAddToQuickBlock: Boolean = true, // Auto-add suggested apps to Quick Block
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Essential Apps Whitelist - apps that should NEVER be suggested for blocking
 * User can customize this list
 */
@Entity(tableName = "essential_apps_whitelist")
data class EssentialAppWhitelist(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isDefault: Boolean = false, // True if pre-populated, false if user-added
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Default essential apps that should never be blocked
        val DEFAULT_ESSENTIAL_APPS = listOf(
            // Navigation & Maps
            "com.google.android.apps.maps" to "Google Maps",
            "com.waze" to "Waze",
            // Communication (non-social)
            "com.google.android.dialer" to "Phone",
            "com.samsung.android.dialer" to "Phone",
            "com.android.phone" to "Phone",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages",
            // Utilities
            "com.google.android.deskclock" to "Clock",
            "com.sec.android.app.clockpackage" to "Clock",
            "com.android.deskclock" to "Clock",
            "com.google.android.calculator" to "Calculator",
            "com.sec.android.app.popupcalculator" to "Calculator",
            // Productivity
            "com.google.android.calendar" to "Calendar",
            "com.samsung.android.calendar" to "Calendar",
            "com.google.android.gm" to "Gmail",
            "com.microsoft.office.outlook" to "Outlook",
            // Camera
            "com.google.android.GoogleCamera" to "Camera",
            "com.sec.android.app.camera" to "Camera",
            // Health & Fitness
            "com.google.android.apps.fitness" to "Google Fit",
            "com.samsung.android.shealth" to "Samsung Health",
            // Banking/Finance (general patterns)
            "com.google.android.apps.walletnfcrel" to "Google Pay"
        )
    }
}

/**
 * Suggested App for Blocking - stores apps identified by smart analysis
 */
@Entity(tableName = "suggested_blocking_apps")
data class SuggestedBlockingApp(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val averageDailyMinutes: Int, // Average usage in last 7 days
    val category: String, // "social", "entertainment", "gaming", etc.
    val suggestionReason: String, // "Top time consumer", "Social media", etc.
    val isAccepted: Boolean = false, // User accepted this suggestion
    val isDismissed: Boolean = false, // User dismissed this suggestion
    val suggestedAt: Long = System.currentTimeMillis()
)

// ========== BEDTIME MODE ==========

/**
 * Bedtime Mode Settings - Block distractive apps during sleep hours
 * Helps users reduce late-night phone usage for better sleep
 */
@Entity(tableName = "bedtime_mode_settings")
data class BedtimeModeSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton
    val isEnabled: Boolean = false,
    val startHour: Int = 23, // 11 PM
    val startMinute: Int = 0,
    val endHour: Int = 7, // 7 AM
    val endMinute: Int = 0,
    // Days of week (true = active on that day)
    val monday: Boolean = true,
    val tuesday: Boolean = true,
    val wednesday: Boolean = true,
    val thursday: Boolean = true,
    val friday: Boolean = true,
    val saturday: Boolean = true,
    val sunday: Boolean = true,
    // Reminder settings
    val showWindDownReminder: Boolean = true, // Show reminder 15 min before bedtime
    val windDownMinutesBefore: Int = 15,
    // Grace period - allow emergency access
    val allowEmergencyOverride: Boolean = true,
    val overrideUsedToday: Boolean = false,
    val lastOverrideDate: String = "", // YYYY-MM-DD
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Check if bedtime is active right now
     */
    fun isCurrentlyBedtime(): Boolean {
        if (!isEnabled) return false

        val now = java.util.Calendar.getInstance()
        val dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK)

        // Check if enabled for today
        val enabledToday = when (dayOfWeek) {
            java.util.Calendar.MONDAY -> monday
            java.util.Calendar.TUESDAY -> tuesday
            java.util.Calendar.WEDNESDAY -> wednesday
            java.util.Calendar.THURSDAY -> thursday
            java.util.Calendar.FRIDAY -> friday
            java.util.Calendar.SATURDAY -> saturday
            java.util.Calendar.SUNDAY -> sunday
            else -> false
        }
        if (!enabledToday) return false

        val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(java.util.Calendar.MINUTE)
        val currentTimeMinutes = currentHour * 60 + currentMinute
        val startTimeMinutes = startHour * 60 + startMinute
        val endTimeMinutes = endHour * 60 + endMinute

        return if (startTimeMinutes <= endTimeMinutes) {
            // Same day (e.g., 22:00 - 23:30)
            currentTimeMinutes in startTimeMinutes until endTimeMinutes
        } else {
            // Crosses midnight (e.g., 23:00 - 07:00)
            currentTimeMinutes >= startTimeMinutes || currentTimeMinutes < endTimeMinutes
        }
    }

    /**
     * Format bedtime range as readable string
     */
    fun formatTimeRange(): String {
        val startFormatted = String.format("%02d:%02d", startHour, startMinute)
        val endFormatted = String.format("%02d:%02d", endHour, endMinute)
        return "$startFormatted - $endFormatted"
    }
}
