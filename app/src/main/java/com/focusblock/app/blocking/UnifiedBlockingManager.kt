package com.focusblock.app.blocking

import com.focusblock.app.database.entity.*
import com.focusblock.app.database.repository.FocusBlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified Blocking Manager - Consolidates all blocking modes into a single source of truth
 *
 * Instead of having 8+ separate blocking modes with overlapping logic, this manager
 * provides a unified interface for determining if an app should be blocked.
 *
 * Blocking Sources (in priority order):
 * 1. Hard Mode - Maximum commitment, cannot be bypassed
 * 2. Strict Mode - Time-locked, can be bypassed with emergency unlock
 * 3. Quick Block - User-initiated temporary blocking
 * 4. Schedules - Time-based automatic blocking
 * 5. App Timer - Daily time limit exceeded
 * 6. Focus Cycle - In break period
 * 7. Global Daily Limit - Total usage limit exceeded
 * 8. Bedtime Mode - Sleep hours blocking
 * 9. App Groups - Group-based blocking
 */
@Singleton
class UnifiedBlockingManager @Inject constructor(
    private val repository: FocusBlockRepository
) {
    /**
     * Data class representing the unified blocking state for an app
     */
    data class BlockingState(
        val isBlocked: Boolean,
        val primaryReason: BlockingReason,
        val activeReasons: Set<BlockingReason>,
        val canBypass: Boolean,
        val bypassCost: BypassCost?
    )

    enum class BlockingReason {
        NONE,
        HARD_MODE,
        STRICT_MODE,
        QUICK_BLOCK,
        SCHEDULE,
        APP_TIMER,
        FOCUS_CYCLE_BREAK,
        GLOBAL_LIMIT,
        BEDTIME,
        APP_GROUP
    }

    enum class BypassCost {
        FREE,           // No cost (emergency unlock available)
        TIME_PENALTY,   // Adds time to lock or reduces tomorrow's limit
        PIN_REQUIRED,   // Requires PIN entry
        COOLDOWN,       // Must wait before bypass
        PHRASE_REQUIRED, // Must type phrase to bypass
        IMPOSSIBLE      // Cannot be bypassed
    }

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Check if an app is currently blocked and why
     */
    suspend fun getBlockingState(packageName: String): BlockingState {
        val reasons = mutableSetOf<BlockingReason>()
        val todayDate = getTodayDate()

        // Check Hard Mode (highest priority - cannot be bypassed)
        val hardModeEnabled = repository.isHardModeEnabled()
        if (hardModeEnabled) {
            val blockedApps = repository.getBlockedPackageNames()
            if (packageName in blockedApps) {
                reasons.add(BlockingReason.HARD_MODE)
            }
        }

        // Check Strict Mode
        val strictModeEnabled = repository.isStrictModeEnabled()
        if (strictModeEnabled) {
            val blockedApps = repository.getBlockedPackageNames()
            if (packageName in blockedApps) {
                reasons.add(BlockingReason.STRICT_MODE)
            }
        }

        // Check Quick Block
        val quickBlockSession = repository.getActiveQuickBlockSessionSync()
        if (quickBlockSession != null) {
            val packages = quickBlockSession.blockedPackages.split(",")
            if (packageName in packages) {
                reasons.add(BlockingReason.QUICK_BLOCK)
            }
        }

        // Check active schedules
        val activeSchedules = repository.getActiveSchedulesSync()
        for (schedule in activeSchedules) {
            val packages = schedule.blockedPackages.split(",")
            if (packageName in packages) {
                reasons.add(BlockingReason.SCHEDULE)
                break
            }
        }

        // Check App Timer
        val appTimerSettings = repository.getAppTimerSettingsSync()
        if (appTimerSettings?.isEnabled == true) {
            val timerApps = appTimerSettings.timerApps.split(",")
            if (packageName in timerApps) {
                val usage = repository.getAppTimerDailyUsageSync(todayDate)
                if (usage != null && usage.totalUsageMinutes >= appTimerSettings.dailyLimitMinutes) {
                    reasons.add(BlockingReason.APP_TIMER)
                }
            }
        }

        // Check Focus Cycle (break period)
        val focusCycle = repository.getActiveFocusCycleSync()
        if (focusCycle?.isEnabled == true && focusCycle.breakStartTime != null) {
            val packages = focusCycle.selectedPackages.split(",")
            if (packageName in packages || (focusCycle.useQuickBlockApps && quickBlockSession != null)) {
                reasons.add(BlockingReason.FOCUS_CYCLE_BREAK)
            }
        }

        // Check Global Daily Limit
        val globalSettings = repository.getGlobalDailyLimitSettingsSync()
        if (globalSettings?.isEnabled == true) {
            val trackedApps = globalSettings.trackedPackages.split(",")
            val defaultApps = GlobalDailyLimitSettings::class.java.getDeclaredField("DEFAULT_DISTRACTING_APPS")
            if (packageName in trackedApps || (globalSettings.useAppTimerApps && appTimerSettings != null &&
                        packageName in appTimerSettings.timerApps.split(","))) {
                val usage = repository.getGlobalDailyUsageSync(todayDate)
                if (usage != null && usage.totalUsageMinutes >= globalSettings.dailyLimitMinutes) {
                    // Check whitelist (tracked but not blocked)
                    val whitelisted = globalSettings.whitelistedPackages.split(",")
                    if (packageName !in whitelisted) {
                        reasons.add(BlockingReason.GLOBAL_LIMIT)
                    }
                }
            }
        }

        // Check Bedtime Mode
        val bedtimeSettings = repository.getBedtimeModeSettingsSync()
        if (bedtimeSettings?.isCurrentlyBedtime() == true) {
            val blockedApps = repository.getBlockedPackageNames()
            if (packageName in blockedApps) {
                reasons.add(BlockingReason.BEDTIME)
            }
        }

        // Check App Groups
        val enabledGroups = repository.getEnabledAppGroupsSync()
        for (group in enabledGroups) {
            if (packageName in group.getPackageList()) {
                reasons.add(BlockingReason.APP_GROUP)
                break
            }
        }

        // Determine primary reason (highest priority)
        val primaryReason = when {
            BlockingReason.HARD_MODE in reasons -> BlockingReason.HARD_MODE
            BlockingReason.STRICT_MODE in reasons -> BlockingReason.STRICT_MODE
            BlockingReason.QUICK_BLOCK in reasons -> BlockingReason.QUICK_BLOCK
            BlockingReason.SCHEDULE in reasons -> BlockingReason.SCHEDULE
            BlockingReason.APP_TIMER in reasons -> BlockingReason.APP_TIMER
            BlockingReason.FOCUS_CYCLE_BREAK in reasons -> BlockingReason.FOCUS_CYCLE_BREAK
            BlockingReason.GLOBAL_LIMIT in reasons -> BlockingReason.GLOBAL_LIMIT
            BlockingReason.BEDTIME in reasons -> BlockingReason.BEDTIME
            BlockingReason.APP_GROUP in reasons -> BlockingReason.APP_GROUP
            else -> BlockingReason.NONE
        }

        // Determine bypass cost
        val (canBypass, bypassCost) = when (primaryReason) {
            BlockingReason.HARD_MODE -> false to BypassCost.IMPOSSIBLE
            BlockingReason.STRICT_MODE -> {
                val isLocked = repository.isStrictModeLocked()
                if (isLocked) {
                    val emergencyAvailable = repository.isEmergencyUnlockAvailable()
                    emergencyAvailable to BypassCost.TIME_PENALTY
                } else {
                    true to BypassCost.FREE
                }
            }
            BlockingReason.QUICK_BLOCK -> true to BypassCost.FREE
            BlockingReason.SCHEDULE -> true to BypassCost.FREE
            BlockingReason.APP_TIMER -> true to BypassCost.COOLDOWN
            BlockingReason.FOCUS_CYCLE_BREAK -> true to BypassCost.FREE
            BlockingReason.GLOBAL_LIMIT -> {
                if (globalSettings?.isHardModeEnabled == true) {
                    val cooldownRemaining = repository.getHardModeCooldownRemaining()
                    if (cooldownRemaining > 0) {
                        false to BypassCost.COOLDOWN
                    } else {
                        true to BypassCost.PHRASE_REQUIRED
                    }
                } else {
                    true to BypassCost.FREE
                }
            }
            BlockingReason.BEDTIME -> {
                val overrideAvailable = bedtimeSettings?.allowEmergencyOverride == true &&
                        !bedtimeSettings.overrideUsedToday
                overrideAvailable to BypassCost.TIME_PENALTY
            }
            BlockingReason.APP_GROUP -> true to BypassCost.FREE
            BlockingReason.NONE -> true to null
        }

        return BlockingState(
            isBlocked = reasons.isNotEmpty(),
            primaryReason = primaryReason,
            activeReasons = reasons,
            canBypass = canBypass,
            bypassCost = bypassCost
        )
    }

    /**
     * Get a human-readable message for why an app is blocked
     */
    fun getBlockingMessage(state: BlockingState): String {
        return when (state.primaryReason) {
            BlockingReason.HARD_MODE -> "Hard Mode is active. This app cannot be accessed."
            BlockingReason.STRICT_MODE -> "Strict Mode is protecting you from distractions."
            BlockingReason.QUICK_BLOCK -> "Quick Block is active. Stay focused!"
            BlockingReason.SCHEDULE -> "This app is blocked by your schedule."
            BlockingReason.APP_TIMER -> "You've reached your daily limit for this app."
            BlockingReason.FOCUS_CYCLE_BREAK -> "Take a break! The app will be available after your break period."
            BlockingReason.GLOBAL_LIMIT -> "You've reached your daily screen time limit."
            BlockingReason.BEDTIME -> "Bedtime Mode is active. Get some rest!"
            BlockingReason.APP_GROUP -> "This app is blocked as part of an app group."
            BlockingReason.NONE -> ""
        }
    }

    /**
     * Get the protection level based on active blocking modes
     */
    fun getProtectionLevel(activeReasons: Set<BlockingReason>): ProtectionLevel {
        return when {
            BlockingReason.HARD_MODE in activeReasons -> ProtectionLevel.MAXIMUM
            BlockingReason.STRICT_MODE in activeReasons -> ProtectionLevel.HIGH
            activeReasons.isNotEmpty() -> ProtectionLevel.ACTIVE
            else -> ProtectionLevel.NONE
        }
    }

    enum class ProtectionLevel {
        NONE,
        ACTIVE,
        HIGH,
        MAXIMUM
    }
}
