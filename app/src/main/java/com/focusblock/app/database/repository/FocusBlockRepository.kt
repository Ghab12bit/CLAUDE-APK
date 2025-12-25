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
    private val focusCycleOverrideDao: FocusCycleOverrideDao
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
    suspend fun getActiveSchedules(currentMinute: Int, dayOfWeek: Int): List<Schedule> =
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

    suspend fun isHardModeEnabled(): Boolean = getSetting(AppSettings.KEY_HARD_MODE_ENABLED)?.toBooleanStrictOrNull() ?: false
    suspend fun setHardModeEnabled(enabled: Boolean) = setSetting(AppSettings.KEY_HARD_MODE_ENABLED, enabled.toString())

    suspend fun getHardModePin(): String? = getSetting(AppSettings.KEY_HARD_MODE_PIN)
    suspend fun setHardModePin(pin: String) = setSetting(AppSettings.KEY_HARD_MODE_PIN, pin)

    suspend fun getHardModeUnlockTime(): Long? = getSetting(AppSettings.KEY_HARD_MODE_UNLOCK_TIME)?.toLongOrNull()
    suspend fun setHardModeUnlockTime(time: Long) = setSetting(AppSettings.KEY_HARD_MODE_UNLOCK_TIME, time.toString())

    fun getStrictModeEnabledFlow(): Flow<Boolean> =
        settingsDao.getValueFlow(AppSettings.KEY_STRICT_MODE_ENABLED).map { it?.toBooleanStrictOrNull() ?: false }

    fun getHardModeEnabledFlow(): Flow<Boolean> =
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
}
