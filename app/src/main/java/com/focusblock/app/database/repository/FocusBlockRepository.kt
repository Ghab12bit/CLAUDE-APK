package com.focusblock.app.database.repository

import com.focusblock.app.database.dao.*
import com.focusblock.app.database.entity.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FocusBlockRepository @Inject constructor(
    private val blockedAppDao: BlockedAppDao,
    private val scheduleDao: ScheduleDao,
    private val quickBlockSessionDao: QuickBlockSessionDao,
    private val blockLogDao: BlockLogDao,
    private val usageStatDao: UsageStatDao,
    private val settingsDao: SettingsDao,
    private val pomodoroSessionDao: PomodoroSessionDao,
    private val appTimeLimitDao: AppTimeLimitDao,
    private val dailyUsageDao: DailyUsageDao,
    private val excludedAppDao: ExcludedAppDao,
    private val focusCycleDao: FocusCycleDao,
    private val focusCycleOverrideDao: FocusCycleOverrideDao,
    private val appTimerSettingsDao: AppTimerSettingsDao,
    private val appTimerDailyUsageDao: AppTimerDailyUsageDao,
    private val globalDailyLimitSettingsDao: GlobalDailyLimitSettingsDao,
    private val globalDailyUsageDao: GlobalDailyUsageDao,
    private val dailyUsageSummaryDao: DailyUsageSummaryDao,
    private val smartSuggestionsSettingsDao: SmartSuggestionsSettingsDao,
    private val essentialAppWhitelistDao: EssentialAppWhitelistDao,
    private val suggestedBlockingAppDao: SuggestedBlockingAppDao
) {
    // Blocked Apps
    fun getAllBlockedApps(): Flow<List<BlockedApp>> = blockedAppDao.getAllBlockedApps()
    fun getActiveBlockedApps(): Flow<List<BlockedApp>> = blockedAppDao.getActiveBlockedApps()
    fun getAllowlistApps(): Flow<List<BlockedApp>> = blockedAppDao.getAllowlistApps()
    suspend fun getBlockedApp(packageName: String): BlockedApp? = blockedAppDao.getBlockedApp(packageName)
    suspend fun insertBlockedApp(app: BlockedApp) = blockedAppDao.insert(app)
    suspend fun insertBlockedApps(apps: List<BlockedApp>) = blockedAppDao.insertAll(apps)
    suspend fun updateBlockedApp(app: BlockedApp) = blockedAppDao.update(app)
    suspend fun deleteBlockedApp(app: BlockedApp) = blockedAppDao.delete(app)
    suspend fun setAppBlocked(packageName: String, isBlocked: Boolean) = blockedAppDao.setBlocked(packageName, isBlocked)
    suspend fun incrementBlockCount(packageName: String) = blockedAppDao.incrementBlockCount(packageName)
    fun getBlockedAppsCount(): Flow<Int> = blockedAppDao.getBlockedAppsCount()

    // Schedules
    fun getAllSchedules(): Flow<List<Schedule>> = scheduleDao.getAllSchedules()
    fun getEnabledSchedules(): Flow<List<Schedule>> = scheduleDao.getEnabledSchedules()
    suspend fun getSchedule(id: Long): Schedule? = scheduleDao.getSchedule(id)
    fun getScheduleFlow(id: Long): Flow<Schedule?> = scheduleDao.getScheduleFlow(id)
    suspend fun insertSchedule(schedule: Schedule): Long = scheduleDao.insert(schedule)
    suspend fun updateSchedule(schedule: Schedule) = scheduleDao.update(schedule)
    suspend fun deleteSchedule(schedule: Schedule) = scheduleDao.delete(schedule)
    suspend fun setScheduleEnabled(id: Long, enabled: Boolean) = scheduleDao.setEnabled(id, enabled)
    suspend fun getActiveSchedules(currentMinute: Int, dayOfWeek: String): List<Schedule> =
        scheduleDao.getActiveSchedules(currentMinute, dayOfWeek)

    // Quick Block Sessions
    fun getActiveQuickBlockSession(): Flow<QuickBlockSession?> = quickBlockSessionDao.getActiveSession()
    suspend fun getActiveQuickBlockSessionSync(): QuickBlockSession? = quickBlockSessionDao.getActiveSessionSync()
    fun getAllQuickBlockSessions(): Flow<List<QuickBlockSession>> = quickBlockSessionDao.getAllSessions()
    suspend fun insertQuickBlockSession(session: QuickBlockSession): Long = quickBlockSessionDao.insert(session)
    suspend fun updateQuickBlockSession(session: QuickBlockSession) = quickBlockSessionDao.update(session)
    suspend fun deactivateQuickBlockSession(id: Long) = quickBlockSessionDao.deactivate(id)
    suspend fun deactivateAllQuickBlockSessions() = quickBlockSessionDao.deactivateAll()

    // Block Logs
    fun getAllBlockLogs(): Flow<List<BlockLog>> = blockLogDao.getAllLogs()
    fun getBlockLogsForPeriod(startTime: Long, endTime: Long): Flow<List<BlockLog>> =
        blockLogDao.getLogsForPeriod(startTime, endTime)
    fun getBlockLogsSince(startTime: Long): Flow<List<BlockLog>> = blockLogDao.getLogsSince(startTime)
    suspend fun getMostBlockedApps(startTime: Long, limit: Int = 10): List<AppBlockCount> =
        blockLogDao.getMostBlockedApps(startTime, limit)
    suspend fun insertBlockLog(log: BlockLog) = blockLogDao.insert(log)
    fun getBlockCountSince(startTime: Long): Flow<Int> = blockLogDao.getBlockCountSince(startTime)

    // Usage Stats
    fun getStatsForDate(date: String): Flow<List<UsageStat>> = usageStatDao.getStatsForDate(date)
    fun getStatsForPeriod(startDate: String, endDate: String): Flow<List<UsageStat>> =
        usageStatDao.getStatsForPeriod(startDate, endDate)
    suspend fun getMostUsedApps(startDate: String, limit: Int = 10): List<AppUsageTime> =
        usageStatDao.getMostUsedApps(startDate, limit)
    fun getTotalBlockedCount(startDate: String): Flow<Int?> = usageStatDao.getTotalBlockedCount(startDate)
    suspend fun insertUsageStat(stat: UsageStat) = usageStatDao.insert(stat)
    suspend fun updateUsageStat(stat: UsageStat) = usageStatDao.update(stat)
    suspend fun getStatForAppAndDate(packageName: String, date: String): UsageStat? =
        usageStatDao.getStatForAppAndDate(packageName, date)

    // Settings
    suspend fun getSetting(key: String): String? = settingsDao.getValue(key)
    fun getSettingFlow(key: String): Flow<String?> = settingsDao.getValueFlow(key)
    suspend fun setSetting(key: String, value: String) = settingsDao.insert(AppSettings(key, value))

    // Convenience setting methods
    suspend fun isStrictModeEnabled(): Boolean = getSetting(AppSettings.KEY_STRICT_MODE_ENABLED)?.toBooleanStrictOrNull() ?: false
    suspend fun setStrictModeEnabled(enabled: Boolean) = setSetting(AppSettings.KEY_STRICT_MODE_ENABLED, enabled.toString())

    suspend fun getStrictModeEndTime(): Long? = getSetting(AppSettings.KEY_STRICT_MODE_END_TIME)?.toLongOrNull()
    suspend fun setStrictModeEndTime(time: Long) = setSetting(AppSettings.KEY_STRICT_MODE_END_TIME, time.toString())
    suspend fun clearStrictModeEndTime() = setSetting(AppSettings.KEY_STRICT_MODE_END_TIME, "0")

    fun getStrictModeEndTimeFlow(): Flow<Long?> =
        settingsDao.getValueFlow(AppSettings.KEY_STRICT_MODE_END_TIME).map { it?.toLongOrNull() }

    suspend fun isStrictModeLocked(): Boolean {
        val endTime = getStrictModeEndTime() ?: return false
        return endTime > System.currentTimeMillis()
    }

    // Legacy Hard Mode (PIN-based) - kept for backward compatibility
    suspend fun isLegacyHardModeEnabled(): Boolean = getSetting(AppSettings.KEY_HARD_MODE_ENABLED)?.toBooleanStrictOrNull() ?: false
    suspend fun setLegacyHardModeEnabled(enabled: Boolean) = setSetting(AppSettings.KEY_HARD_MODE_ENABLED, enabled.toString())

    suspend fun getHardModePin(): String? = getSetting(AppSettings.KEY_HARD_MODE_PIN)
    suspend fun setHardModePin(pin: String) = setSetting(AppSettings.KEY_HARD_MODE_PIN, pin)

    suspend fun getHardModeUnlockTime(): Long? = getSetting(AppSettings.KEY_HARD_MODE_UNLOCK_TIME)?.toLongOrNull()
    suspend fun setHardModeUnlockTime(time: Long) = setSetting(AppSettings.KEY_HARD_MODE_UNLOCK_TIME, time.toString())

    fun getStrictModeEnabledFlow(): Flow<Boolean> =
        settingsDao.getValueFlow(AppSettings.KEY_STRICT_MODE_ENABLED).map { it?.toBooleanStrictOrNull() ?: false }

    fun getLegacyHardModeEnabledFlow(): Flow<Boolean> =
        settingsDao.getValueFlow(AppSettings.KEY_HARD_MODE_ENABLED).map { it?.toBooleanStrictOrNull() ?: false }

    // Strict Mode Pause methods
    suspend fun isStrictModePaused(): Boolean = getSetting(AppSettings.KEY_STRICT_MODE_PAUSED)?.toBooleanStrictOrNull() ?: false
    suspend fun setStrictModePaused(paused: Boolean) = setSetting(AppSettings.KEY_STRICT_MODE_PAUSED, paused.toString())

    suspend fun getStrictModeRemainingOnPause(): Long? = getSetting(AppSettings.KEY_STRICT_MODE_REMAINING_ON_PAUSE)?.toLongOrNull()
    suspend fun setStrictModeRemainingOnPause(remainingMs: Long) = setSetting(AppSettings.KEY_STRICT_MODE_REMAINING_ON_PAUSE, remainingMs.toString())
    suspend fun clearStrictModeRemainingOnPause() = setSetting(AppSettings.KEY_STRICT_MODE_REMAINING_ON_PAUSE, "0")

    suspend fun setStrictModePauseReason(reason: String) = setSetting(AppSettings.KEY_STRICT_MODE_PAUSE_REASON, reason)
    suspend fun getStrictModePauseReason(): String? = getSetting(AppSettings.KEY_STRICT_MODE_PAUSE_REASON)

    fun getStrictModePausedFlow(): Flow<Boolean> =
        settingsDao.getValueFlow(AppSettings.KEY_STRICT_MODE_PAUSED).map { it?.toBooleanStrictOrNull() ?: false }

    // Strict Mode pause limit tracking (persists across restart/reboot)
    suspend fun getStrictModePauseCountToday(): Int {
        checkAndResetPauseCountIfNeeded()
        return getSetting(AppSettings.KEY_STRICT_MODE_PAUSE_COUNT_TODAY)?.toIntOrNull() ?: 0
    }

    suspend fun incrementStrictModePauseCount() {
        checkAndResetPauseCountIfNeeded()
        val current = getSetting(AppSettings.KEY_STRICT_MODE_PAUSE_COUNT_TODAY)?.toIntOrNull() ?: 0
        setSetting(AppSettings.KEY_STRICT_MODE_PAUSE_COUNT_TODAY, (current + 1).toString())
    }

    suspend fun getStrictModeMaxPausesPerDay(): Int =
        getSetting(AppSettings.KEY_STRICT_MODE_MAX_PAUSES_PER_DAY)?.toIntOrNull() ?: 1 // Default: 1 pause per day

    suspend fun setStrictModeMaxPausesPerDay(max: Int) =
        setSetting(AppSettings.KEY_STRICT_MODE_MAX_PAUSES_PER_DAY, max.toString())

    suspend fun canPauseStrictMode(): Boolean {
        checkAndResetPauseCountIfNeeded()
        val current = getSetting(AppSettings.KEY_STRICT_MODE_PAUSE_COUNT_TODAY)?.toIntOrNull() ?: 0
        val max = getStrictModeMaxPausesPerDay()
        return current < max
    }

    suspend fun getRemainingPausesToday(): Int {
        checkAndResetPauseCountIfNeeded()
        val current = getSetting(AppSettings.KEY_STRICT_MODE_PAUSE_COUNT_TODAY)?.toIntOrNull() ?: 0
        val max = getStrictModeMaxPausesPerDay()
        return maxOf(0, max - current)
    }

    /**
     * Check if we need to reset the pause count (midnight reset)
     * Resets at midnight local time
     */
    private suspend fun checkAndResetPauseCountIfNeeded() {
        val lastResetTime = getSetting(AppSettings.KEY_STRICT_MODE_PAUSE_RESET_TIME)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()

        // Get today's midnight timestamp
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val todayMidnight = calendar.timeInMillis

        // If last reset was before today's midnight, reset the count
        if (lastResetTime < todayMidnight) {
            setSetting(AppSettings.KEY_STRICT_MODE_PAUSE_COUNT_TODAY, "0")
            setSetting(AppSettings.KEY_STRICT_MODE_PAUSE_RESET_TIME, now.toString())
        }
    }

    // Get list of currently blocked packages
    suspend fun getBlockedPackageNames(): List<String> = blockedAppDao.getBlockedPackageNames()

    // Pomodoro
    fun getPomodoroSessionsForDate(date: String): Flow<List<PomodoroSession>> =
        pomodoroSessionDao.getSessionsForDate(date)
    fun getPomodoroSessionsSince(startDate: String): Flow<List<PomodoroSession>> =
        pomodoroSessionDao.getSessionsSince(startDate)
    fun getCompletedWorkSessionsToday(date: String): Flow<Int> =
        pomodoroSessionDao.getCompletedWorkSessionsToday(date)
    suspend fun insertPomodoroSession(session: PomodoroSession): Long = pomodoroSessionDao.insert(session)
    suspend fun updatePomodoroSession(session: PomodoroSession) = pomodoroSessionDao.update(session)
    suspend fun completePomodoroSession(id: Long, endTime: Long) = pomodoroSessionDao.completeSession(id, endTime)

    // App Time Limits
    fun getAllTimeLimits(): Flow<List<AppTimeLimit>> = appTimeLimitDao.getAllTimeLimits()
    fun getEnabledTimeLimits(): Flow<List<AppTimeLimit>> = appTimeLimitDao.getEnabledTimeLimits()
    suspend fun getTimeLimit(packageName: String): AppTimeLimit? = appTimeLimitDao.getTimeLimit(packageName)
    fun getTimeLimitFlow(packageName: String): Flow<AppTimeLimit?> = appTimeLimitDao.getTimeLimitFlow(packageName)
    suspend fun insertTimeLimit(timeLimit: AppTimeLimit) = appTimeLimitDao.insert(timeLimit)
    suspend fun insertTimeLimits(timeLimits: List<AppTimeLimit>) = appTimeLimitDao.insertAll(timeLimits)
    suspend fun updateTimeLimit(timeLimit: AppTimeLimit) = appTimeLimitDao.update(timeLimit)
    suspend fun deleteTimeLimit(timeLimit: AppTimeLimit) = appTimeLimitDao.delete(timeLimit)
    suspend fun deleteTimeLimitByPackage(packageName: String) = appTimeLimitDao.deleteByPackage(packageName)
    suspend fun setTimeLimitEnabled(packageName: String, enabled: Boolean) = appTimeLimitDao.setEnabled(packageName, enabled)

    // Daily Usage
    fun getUsageForDate(date: String): Flow<List<DailyUsage>> = dailyUsageDao.getUsageForDate(date)
    suspend fun getUsageForAppAndDate(packageName: String, date: String): DailyUsage? =
        dailyUsageDao.getUsageForAppAndDate(packageName, date)
    fun getUsageForAppAndDateFlow(packageName: String, date: String): Flow<DailyUsage?> =
        dailyUsageDao.getUsageForAppAndDateFlow(packageName, date)
    fun getUsageForPeriod(startDate: String, endDate: String): Flow<List<DailyUsage>> =
        dailyUsageDao.getUsageForPeriod(startDate, endDate)
    suspend fun getTotalUsageByApp(startDate: String): List<AppTotalUsage> =
        dailyUsageDao.getTotalUsageByApp(startDate)
    fun getTotalUsageForDate(date: String): Flow<Int?> = dailyUsageDao.getTotalUsageForDate(date)
    fun getTotalUsageForPeriod(startDate: String, endDate: String): Flow<Int?> =
        dailyUsageDao.getTotalUsageForPeriod(startDate, endDate)
    suspend fun insertDailyUsage(dailyUsage: DailyUsage) = dailyUsageDao.insert(dailyUsage)
    suspend fun updateDailyUsage(dailyUsage: DailyUsage) = dailyUsageDao.update(dailyUsage)
    suspend fun updateAppUsage(packageName: String, date: String, minutes: Int, limitReached: Boolean) =
        dailyUsageDao.updateUsage(packageName, date, minutes, limitReached)
    suspend fun deleteOldUsage(beforeDate: String) = dailyUsageDao.deleteOldUsage(beforeDate)

    // Excluded Apps
    fun getAllExcludedApps(): Flow<List<ExcludedApp>> = excludedAppDao.getAllExcludedApps()
    fun getExcludedAppsByType(exclusionType: ExclusionType): Flow<List<ExcludedApp>> =
        excludedAppDao.getExcludedAppsByType(exclusionType)
    suspend fun getExcludedPackageNames(exclusionType: ExclusionType): List<String> =
        excludedAppDao.getExcludedPackageNames(exclusionType)
    suspend fun getExcludedApp(packageName: String): ExcludedApp? = excludedAppDao.getExcludedApp(packageName)
    suspend fun isAppExcluded(packageName: String): Boolean = excludedAppDao.isExcluded(packageName)
    suspend fun insertExcludedApp(excludedApp: ExcludedApp) = excludedAppDao.insert(excludedApp)
    suspend fun deleteExcludedApp(excludedApp: ExcludedApp) = excludedAppDao.delete(excludedApp)
    suspend fun deleteExcludedAppByPackage(packageName: String) = excludedAppDao.deleteByPackage(packageName)

    // Focus Cycles
    fun getAllFocusCycles(): Flow<List<FocusCycle>> = focusCycleDao.getAllFocusCycles()
    fun getActiveFocusCycle(): Flow<FocusCycle?> = focusCycleDao.getActiveFocusCycle()
    suspend fun getActiveFocusCycleSync(): FocusCycle? = focusCycleDao.getActiveFocusCycleSync()
    suspend fun getFocusCycle(id: Long): FocusCycle? = focusCycleDao.getFocusCycle(id)
    suspend fun insertFocusCycle(focusCycle: FocusCycle): Long = focusCycleDao.insert(focusCycle)
    suspend fun updateFocusCycle(focusCycle: FocusCycle) = focusCycleDao.update(focusCycle)
    suspend fun deleteFocusCycle(focusCycle: FocusCycle) = focusCycleDao.delete(focusCycle)
    suspend fun setFocusCycleEnabled(id: Long, enabled: Boolean) = focusCycleDao.setEnabled(id, enabled)
    suspend fun setCycleActive(id: Long, active: Boolean, startTime: Long?) = focusCycleDao.setCycleActive(id, active, startTime)
    suspend fun setBreakStartTime(id: Long, breakStartTime: Long?) = focusCycleDao.setBreakStartTime(id, breakStartTime)
    suspend fun disableAllFocusCycles() = focusCycleDao.disableAll()

    // Focus Cycle Overrides
    fun getOverridesForCycle(cycleId: Long): Flow<List<FocusCycleOverride>> = focusCycleOverrideDao.getOverridesForCycle(cycleId)
    suspend fun getOverrideCountSince(cycleId: Long, since: Long): Int = focusCycleOverrideDao.getOverrideCountSince(cycleId, since)
    suspend fun insertFocusCycleOverride(override: FocusCycleOverride) = focusCycleOverrideDao.insert(override)
    suspend fun deleteOldOverrides(beforeTime: Long) = focusCycleOverrideDao.deleteOldOverrides(beforeTime)

    // App Timer (Shared Time Limit)
    fun getAppTimerSettings(): Flow<AppTimerSettings?> = appTimerSettingsDao.getSettings()
    suspend fun getAppTimerSettingsSync(): AppTimerSettings? = appTimerSettingsDao.getSettingsSync()
    suspend fun saveAppTimerSettings(settings: AppTimerSettings) = appTimerSettingsDao.insert(settings)
    suspend fun updateAppTimerSettings(settings: AppTimerSettings) = appTimerSettingsDao.update(settings)
    suspend fun setAppTimerEnabled(enabled: Boolean) = appTimerSettingsDao.setEnabled(enabled)
    suspend fun setAppTimerDailyLimit(minutes: Int) = appTimerSettingsDao.setDailyLimit(minutes)
    suspend fun setAppTimerApps(apps: String) = appTimerSettingsDao.setTimerApps(apps)

    // App Timer Daily Usage
    fun getAppTimerDailyUsage(date: String): Flow<AppTimerDailyUsage?> = appTimerDailyUsageDao.getUsageForDate(date)
    suspend fun getAppTimerDailyUsageSync(date: String): AppTimerDailyUsage? = appTimerDailyUsageDao.getUsageForDateSync(date)
    suspend fun insertAppTimerDailyUsage(usage: AppTimerDailyUsage) = appTimerDailyUsageDao.insert(usage)
    suspend fun updateAppTimerDailyUsage(date: String, minutes: Int) = appTimerDailyUsageDao.updateUsage(date, minutes)
    suspend fun markAppTimerLimitPopupShown(date: String) = appTimerDailyUsageDao.markLimitPopupShown(date)
    suspend fun markAppTimerEscalationPopupShown(date: String) = appTimerDailyUsageDao.markEscalationPopupShown(date)
    suspend fun markAppTimerAddedToQuickBlock(date: String) = appTimerDailyUsageDao.markAddedToQuickBlock(date)
    suspend fun deleteOldAppTimerUsage(beforeDate: String) = appTimerDailyUsageDao.deleteOldUsage(beforeDate)

    // Global Daily Limit Settings
    fun getGlobalDailyLimitSettings(): Flow<GlobalDailyLimitSettings?> = globalDailyLimitSettingsDao.getSettings()
    suspend fun getGlobalDailyLimitSettingsSync(): GlobalDailyLimitSettings? = globalDailyLimitSettingsDao.getSettingsSync()
    suspend fun saveGlobalDailyLimitSettings(settings: GlobalDailyLimitSettings) = globalDailyLimitSettingsDao.insert(settings)
    suspend fun updateGlobalDailyLimitSettings(settings: GlobalDailyLimitSettings) = globalDailyLimitSettingsDao.update(settings)
    suspend fun setGlobalDailyLimitEnabled(enabled: Boolean) = globalDailyLimitSettingsDao.setEnabled(enabled)
    suspend fun setGlobalDailyLimit(minutes: Int) = globalDailyLimitSettingsDao.setDailyLimit(minutes)

    // Hard Mode - Prevents easy bypass of limits
    suspend fun enableHardMode(lockDurationHours: Int, cooldownMinutes: Int = 15) {
        val lockUntil = System.currentTimeMillis() + (lockDurationHours * 60 * 60 * 1000L)
        globalDailyLimitSettingsDao.setHardMode(
            enabled = true,
            lockUntil = lockUntil,
            cooldownMinutes = cooldownMinutes
        )
    }

    suspend fun requestHardModeUnlock() = globalDailyLimitSettingsDao.requestHardModeUnlock()
    suspend fun disableHardMode() = globalDailyLimitSettingsDao.disableHardMode()
    suspend fun isHardModeEnabled(): Boolean = globalDailyLimitSettingsDao.isHardModeEnabled() ?: false
    suspend fun getHardModeLockUntil(): Long = globalDailyLimitSettingsDao.getHardModeLockUntil() ?: 0L
    suspend fun getHardModeUnlockRequestedAt(): Long = globalDailyLimitSettingsDao.getHardModeUnlockRequestedAt() ?: 0L

    suspend fun isHardModeLocked(): Boolean {
        val settings = getGlobalDailyLimitSettingsSync() ?: return false
        return settings.isHardModeEnabled && settings.hardModeLockUntil > System.currentTimeMillis()
    }

    suspend fun canDisableHardMode(): Pair<Boolean, Long> {
        val settings = getGlobalDailyLimitSettingsSync() ?: return Pair(true, 0L)
        if (!settings.isHardModeEnabled) return Pair(true, 0L)

        val now = System.currentTimeMillis()
        val unlockRequestedAt = settings.hardModeUnlockRequestedAt
        val cooldownMs = settings.hardModeCooldownMinutes * 60 * 1000L

        // If no unlock request, can't disable yet
        if (unlockRequestedAt == 0L) return Pair(false, cooldownMs)

        // Check if cooldown has passed
        val timeSinceRequest = now - unlockRequestedAt
        return if (timeSinceRequest >= cooldownMs) {
            Pair(true, 0L) // Cooldown passed, can disable
        } else {
            Pair(false, cooldownMs - timeSinceRequest) // Still in cooldown
        }
    }

    // Global Daily Usage
    fun getGlobalDailyUsage(date: String): Flow<GlobalDailyUsage?> = globalDailyUsageDao.getUsageForDate(date)
    suspend fun getGlobalDailyUsageSync(date: String): GlobalDailyUsage? = globalDailyUsageDao.getUsageForDateSync(date)
    suspend fun insertGlobalDailyUsage(usage: GlobalDailyUsage) = globalDailyUsageDao.insert(usage)
    suspend fun updateGlobalDailyUsage(date: String, minutes: Int) = globalDailyUsageDao.updateUsage(date, minutes)
    suspend fun markGlobalLimitReached(date: String) = globalDailyUsageDao.markLimitReached(date)
    suspend fun markGlobalWarningShown(date: String) = globalDailyUsageDao.markWarningShown(date)
    suspend fun activateGlobalOverride(date: String, overrideTime: Long, expiresAt: Long, cooldownUntil: Long) =
        globalDailyUsageDao.activateOverride(date, overrideTime, expiresAt, cooldownUntil)
    suspend fun clearGlobalOverride(date: String) = globalDailyUsageDao.clearOverride(date)
    suspend fun deleteOldGlobalUsage(beforeDate: String) = globalDailyUsageDao.deleteOldUsage(beforeDate)

    // Daily Usage Summary (for Today vs Yesterday comparison)
    fun getDailyUsageSummary(date: String): Flow<DailyUsageSummary?> = dailyUsageSummaryDao.getSummaryForDate(date)
    suspend fun getDailyUsageSummarySync(date: String): DailyUsageSummary? = dailyUsageSummaryDao.getSummaryForDateSync(date)
    suspend fun getRecentDailySummaries(limit: Int): List<DailyUsageSummary> = dailyUsageSummaryDao.getRecentSummaries(limit)
    suspend fun insertDailyUsageSummary(summary: DailyUsageSummary) = dailyUsageSummaryDao.insert(summary)
    suspend fun updateDailySummaryScreenTime(date: String, minutes: Int) = dailyUsageSummaryDao.updateScreenTime(date, minutes)
    suspend fun markComparisonSent(date: String, result: String) = dailyUsageSummaryDao.markComparisonSent(date, result)
    suspend fun deleteOldDailySummaries(beforeDate: String) = dailyUsageSummaryDao.deleteOldSummaries(beforeDate)

    // Smart Suggestions Settings
    fun getSmartSuggestionsSettings(): Flow<SmartSuggestionsSettings?> = smartSuggestionsSettingsDao.getSettings()
    suspend fun getSmartSuggestionsSettingsSync(): SmartSuggestionsSettings? = smartSuggestionsSettingsDao.getSettingsSync()
    suspend fun saveSmartSuggestionsSettings(settings: SmartSuggestionsSettings) = smartSuggestionsSettingsDao.insert(settings)
    suspend fun updateSmartSuggestionsSettings(settings: SmartSuggestionsSettings) = smartSuggestionsSettingsDao.update(settings)
    suspend fun markSmartOnboardingComplete() = smartSuggestionsSettingsDao.markOnboardingComplete()
    suspend fun updateSmartAnalysis(date: String, avgMinutes: Int, suggestedLimit: Int) =
        smartSuggestionsSettingsDao.updateAnalysis(date, avgMinutes, suggestedLimit)

    // Essential Apps Whitelist
    fun getAllWhitelistedApps(): Flow<List<EssentialAppWhitelist>> = essentialAppWhitelistDao.getAllWhitelistedApps()
    suspend fun getAllWhitelistedAppsSync(): List<EssentialAppWhitelist> = essentialAppWhitelistDao.getAllWhitelistedAppsSync()
    suspend fun getWhitelistedPackageNames(): List<String> = essentialAppWhitelistDao.getWhitelistedPackageNames()
    suspend fun isAppWhitelisted(packageName: String): Boolean = essentialAppWhitelistDao.isWhitelisted(packageName)
    suspend fun addToWhitelist(app: EssentialAppWhitelist) = essentialAppWhitelistDao.insert(app)
    suspend fun addAllToWhitelist(apps: List<EssentialAppWhitelist>) = essentialAppWhitelistDao.insertAll(apps)
    suspend fun removeFromWhitelist(packageName: String) = essentialAppWhitelistDao.deleteByPackage(packageName)

    // Suggested Blocking Apps
    fun getActiveSuggestions(): Flow<List<SuggestedBlockingApp>> = suggestedBlockingAppDao.getActiveSuggestions()
    suspend fun getActiveSuggestionsSync(): List<SuggestedBlockingApp> = suggestedBlockingAppDao.getActiveSuggestionsSync()
    suspend fun getAcceptedSuggestions(): List<SuggestedBlockingApp> = suggestedBlockingAppDao.getAcceptedSuggestions()
    suspend fun addSuggestion(suggestion: SuggestedBlockingApp) = suggestedBlockingAppDao.insert(suggestion)
    suspend fun addAllSuggestions(suggestions: List<SuggestedBlockingApp>) = suggestedBlockingAppDao.insertAll(suggestions)
    suspend fun acceptSuggestion(packageName: String) = suggestedBlockingAppDao.acceptSuggestion(packageName)
    suspend fun dismissSuggestion(packageName: String) = suggestedBlockingAppDao.dismissSuggestion(packageName)
    suspend fun clearAllSuggestions() = suggestedBlockingAppDao.clearAll()
}
