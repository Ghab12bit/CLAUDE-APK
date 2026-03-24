package com.focusblock.app.database.dao

import androidx.room.*
import com.focusblock.app.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {
    @Query("SELECT * FROM blocked_apps ORDER BY appName ASC")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps WHERE isBlocked = 1 ORDER BY appName ASC")
    fun getActiveBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps WHERE isInAllowlist = 1 ORDER BY appName ASC")
    fun getAllowlistApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName")
    suspend fun getBlockedApp(packageName: String): BlockedApp?

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName")
    fun getBlockedAppFlow(packageName: String): Flow<BlockedApp?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blockedApp: BlockedApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(blockedApps: List<BlockedApp>)

    @Update
    suspend fun update(blockedApp: BlockedApp)

    @Delete
    suspend fun delete(blockedApp: BlockedApp)

    @Query("UPDATE blocked_apps SET isBlocked = :isBlocked WHERE packageName = :packageName")
    suspend fun setBlocked(packageName: String, isBlocked: Boolean)

    @Query("UPDATE blocked_apps SET blockedCount = blockedCount + 1, totalBlockedCount = totalBlockedCount + 1, lastBlockedTime = :time WHERE packageName = :packageName")
    suspend fun incrementBlockCount(packageName: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE blocked_apps SET blockedCount = 0")
    suspend fun resetDailyBlockCounts()

    @Query("SELECT COUNT(*) FROM blocked_apps WHERE isBlocked = 1")
    fun getBlockedAppsCount(): Flow<Int>

    @Query("SELECT packageName FROM blocked_apps WHERE isBlocked = 1")
    suspend fun getBlockedPackageNames(): List<String>
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY startTimeMinutes ASC")
    fun getAllSchedules(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE isEnabled = 1 ORDER BY startTimeMinutes ASC")
    fun getEnabledSchedules(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getSchedule(id: Long): Schedule?

    @Query("SELECT * FROM schedules WHERE id = :id")
    fun getScheduleFlow(id: Long): Flow<Schedule?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: Schedule): Long

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)

    @Query("UPDATE schedules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    // Fixed query to handle:
    // 1. Normal schedules (start < end): e.g., 9 AM to 5 PM
    // 2. Overnight schedules (start > end): e.g., 10 PM to 6 AM
    // 3. Proper day matching using comma-separated pattern
    @Query("""
        SELECT * FROM schedules WHERE isEnabled = 1
        AND (
            (startTimeMinutes < endTimeMinutes AND :currentMinute >= startTimeMinutes AND :currentMinute < endTimeMinutes)
            OR
            (startTimeMinutes > endTimeMinutes AND (:currentMinute >= startTimeMinutes OR :currentMinute < endTimeMinutes))
        )
        AND (
            daysOfWeek LIKE :dayOfWeek || ',%'
            OR daysOfWeek LIKE '%,' || :dayOfWeek || ',%'
            OR daysOfWeek LIKE '%,' || :dayOfWeek
            OR daysOfWeek = :dayOfWeek
        )
    """)
    suspend fun getActiveSchedules(currentMinute: Int, dayOfWeek: String): List<Schedule>
}

@Dao
interface QuickBlockSessionDao {
    @Query("SELECT * FROM quick_block_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    fun getActiveSession(): Flow<QuickBlockSession?>

    @Query("SELECT * FROM quick_block_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSessionSync(): QuickBlockSession?

    @Query("SELECT * FROM quick_block_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<QuickBlockSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: QuickBlockSession): Long

    @Update
    suspend fun update(session: QuickBlockSession)

    @Query("UPDATE quick_block_sessions SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Query("UPDATE quick_block_sessions SET isActive = 0")
    suspend fun deactivateAll()
}

@Dao
interface BlockLogDao {
    @Query("SELECT * FROM block_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<BlockLog>>

    @Query("SELECT * FROM block_logs WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp DESC")
    fun getLogsForPeriod(startTime: Long, endTime: Long): Flow<List<BlockLog>>

    @Query("SELECT * FROM block_logs WHERE timestamp >= :startTime ORDER BY timestamp DESC")
    fun getLogsSince(startTime: Long): Flow<List<BlockLog>>

    @Query("SELECT packageName, COUNT(*) as count FROM block_logs WHERE timestamp >= :startTime GROUP BY packageName ORDER BY count DESC LIMIT :limit")
    suspend fun getMostBlockedApps(startTime: Long, limit: Int = 10): List<AppBlockCount>

    @Insert
    suspend fun insert(blockLog: BlockLog)

    @Query("DELETE FROM block_logs WHERE timestamp < :beforeTime")
    suspend fun deleteOldLogs(beforeTime: Long)

    @Query("SELECT COUNT(*) FROM block_logs WHERE timestamp >= :startTime")
    fun getBlockCountSince(startTime: Long): Flow<Int>
}

data class AppBlockCount(
    val packageName: String,
    val count: Int
)

@Dao
interface UsageStatDao {
    @Query("SELECT * FROM usage_stats WHERE date = :date ORDER BY usageTimeMillis DESC")
    fun getStatsForDate(date: String): Flow<List<UsageStat>>

    @Query("SELECT * FROM usage_stats WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC, usageTimeMillis DESC")
    fun getStatsForPeriod(startDate: String, endDate: String): Flow<List<UsageStat>>

    @Query("SELECT packageName, SUM(usageTimeMillis) as totalTime FROM usage_stats WHERE date >= :startDate GROUP BY packageName ORDER BY totalTime DESC LIMIT :limit")
    suspend fun getMostUsedApps(startDate: String, limit: Int = 10): List<AppUsageTime>

    @Query("SELECT SUM(blockedCount) FROM usage_stats WHERE date >= :startDate")
    fun getTotalBlockedCount(startDate: String): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usageStat: UsageStat)

    @Update
    suspend fun update(usageStat: UsageStat)

    @Query("SELECT * FROM usage_stats WHERE packageName = :packageName AND date = :date")
    suspend fun getStatForAppAndDate(packageName: String, date: String): UsageStat?
}

data class AppUsageTime(
    val packageName: String,
    val totalTime: Long
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun getSetting(key: String): AppSettings?

    @Query("SELECT * FROM settings WHERE `key` = :key")
    fun getSettingFlow(key: String): Flow<AppSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: AppSettings)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun getValueFlow(key: String): Flow<String?>
}

@Dao
interface PomodoroSessionDao {
    @Query("SELECT * FROM pomodoro_sessions WHERE date = :date ORDER BY startTime DESC")
    fun getSessionsForDate(date: String): Flow<List<PomodoroSession>>

    @Query("SELECT * FROM pomodoro_sessions WHERE date >= :startDate ORDER BY startTime DESC")
    fun getSessionsSince(startDate: String): Flow<List<PomodoroSession>>

    @Query("SELECT COUNT(*) FROM pomodoro_sessions WHERE date = :date AND sessionType = 'WORK' AND completed = 1")
    fun getCompletedWorkSessionsToday(date: String): Flow<Int>

    @Insert
    suspend fun insert(session: PomodoroSession): Long

    @Update
    suspend fun update(session: PomodoroSession)

    @Query("UPDATE pomodoro_sessions SET completed = 1, endTime = :endTime WHERE id = :id")
    suspend fun completeSession(id: Long, endTime: Long)
}

@Dao
interface AppTimeLimitDao {
    @Query("SELECT * FROM app_time_limits ORDER BY appName ASC")
    fun getAllTimeLimits(): Flow<List<AppTimeLimit>>

    @Query("SELECT * FROM app_time_limits WHERE isEnabled = 1 ORDER BY appName ASC")
    fun getEnabledTimeLimits(): Flow<List<AppTimeLimit>>

    @Query("SELECT * FROM app_time_limits WHERE packageName = :packageName")
    suspend fun getTimeLimit(packageName: String): AppTimeLimit?

    @Query("SELECT * FROM app_time_limits WHERE packageName = :packageName")
    fun getTimeLimitFlow(packageName: String): Flow<AppTimeLimit?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(timeLimit: AppTimeLimit)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(timeLimits: List<AppTimeLimit>)

    @Update
    suspend fun update(timeLimit: AppTimeLimit)

    @Delete
    suspend fun delete(timeLimit: AppTimeLimit)

    @Query("DELETE FROM app_time_limits WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("UPDATE app_time_limits SET isEnabled = :enabled WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)
}

@Dao
interface DailyUsageDao {
    @Query("SELECT * FROM daily_usage WHERE date = :date ORDER BY usageMinutes DESC")
    fun getUsageForDate(date: String): Flow<List<DailyUsage>>

    @Query("SELECT * FROM daily_usage WHERE packageName = :packageName AND date = :date")
    suspend fun getUsageForAppAndDate(packageName: String, date: String): DailyUsage?

    @Query("SELECT * FROM daily_usage WHERE packageName = :packageName AND date = :date")
    fun getUsageForAppAndDateFlow(packageName: String, date: String): Flow<DailyUsage?>

    @Query("SELECT * FROM daily_usage WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC, usageMinutes DESC")
    fun getUsageForPeriod(startDate: String, endDate: String): Flow<List<DailyUsage>>

    @Query("SELECT packageName, SUM(usageMinutes) as totalMinutes FROM daily_usage WHERE date >= :startDate GROUP BY packageName ORDER BY totalMinutes DESC")
    suspend fun getTotalUsageByApp(startDate: String): List<AppTotalUsage>

    @Query("SELECT SUM(usageMinutes) FROM daily_usage WHERE date = :date")
    fun getTotalUsageForDate(date: String): Flow<Int?>

    @Query("SELECT SUM(usageMinutes) FROM daily_usage WHERE date >= :startDate AND date <= :endDate")
    fun getTotalUsageForPeriod(startDate: String, endDate: String): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dailyUsage: DailyUsage)

    @Update
    suspend fun update(dailyUsage: DailyUsage)

    @Query("UPDATE daily_usage SET usageMinutes = :minutes, lastUpdated = :timestamp, limitReached = :limitReached WHERE packageName = :packageName AND date = :date")
    suspend fun updateUsage(packageName: String, date: String, minutes: Int, limitReached: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM daily_usage WHERE date < :beforeDate")
    suspend fun deleteOldUsage(beforeDate: String)
}

data class AppTotalUsage(
    val packageName: String,
    val totalMinutes: Int
)

@Dao
interface ExcludedAppDao {
    @Query("SELECT * FROM excluded_apps ORDER BY appName ASC")
    fun getAllExcludedApps(): Flow<List<ExcludedApp>>

    @Query("SELECT * FROM excluded_apps WHERE excludedFrom = :exclusionType ORDER BY appName ASC")
    fun getExcludedAppsByType(exclusionType: ExclusionType): Flow<List<ExcludedApp>>

    @Query("SELECT packageName FROM excluded_apps WHERE excludedFrom = :exclusionType")
    suspend fun getExcludedPackageNames(exclusionType: ExclusionType): List<String>

    @Query("SELECT * FROM excluded_apps WHERE packageName = :packageName")
    suspend fun getExcludedApp(packageName: String): ExcludedApp?

    @Query("SELECT EXISTS(SELECT 1 FROM excluded_apps WHERE packageName = :packageName)")
    suspend fun isExcluded(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(excludedApp: ExcludedApp)

    @Delete
    suspend fun delete(excludedApp: ExcludedApp)

    @Query("DELETE FROM excluded_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Dao
interface FocusCycleDao {
    @Query("SELECT * FROM focus_cycles ORDER BY createdAt DESC")
    fun getAllFocusCycles(): Flow<List<FocusCycle>>

    @Query("SELECT * FROM focus_cycles WHERE isEnabled = 1 ORDER BY createdAt DESC LIMIT 1")
    fun getActiveFocusCycle(): Flow<FocusCycle?>

    @Query("SELECT * FROM focus_cycles WHERE isEnabled = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveFocusCycleSync(): FocusCycle?

    @Query("SELECT * FROM focus_cycles WHERE id = :id")
    suspend fun getFocusCycle(id: Long): FocusCycle?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(focusCycle: FocusCycle): Long

    @Update
    suspend fun update(focusCycle: FocusCycle)

    @Delete
    suspend fun delete(focusCycle: FocusCycle)

    @Query("UPDATE focus_cycles SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE focus_cycles SET isActive = :active, cycleStartTime = :startTime WHERE id = :id")
    suspend fun setCycleActive(id: Long, active: Boolean, startTime: Long?)

    @Query("UPDATE focus_cycles SET breakStartTime = :breakStartTime WHERE id = :id")
    suspend fun setBreakStartTime(id: Long, breakStartTime: Long?)

    @Query("UPDATE focus_cycles SET isEnabled = 0")
    suspend fun disableAll()
}

@Dao
interface FocusCycleOverrideDao {
    @Query("SELECT * FROM focus_cycle_overrides WHERE focusCycleId = :cycleId ORDER BY timestamp DESC")
    fun getOverridesForCycle(cycleId: Long): Flow<List<FocusCycleOverride>>

    @Query("SELECT COUNT(*) FROM focus_cycle_overrides WHERE focusCycleId = :cycleId AND timestamp >= :since")
    suspend fun getOverrideCountSince(cycleId: Long, since: Long): Int

    @Insert
    suspend fun insert(override: FocusCycleOverride)

    @Query("DELETE FROM focus_cycle_overrides WHERE timestamp < :beforeTime")
    suspend fun deleteOldOverrides(beforeTime: Long)
}

// ========== APP TIMER DAOs ==========

@Dao
interface AppTimerSettingsDao {
    @Query("SELECT * FROM app_timer_settings WHERE id = 1")
    fun getSettings(): Flow<AppTimerSettings?>

    @Query("SELECT * FROM app_timer_settings WHERE id = 1")
    suspend fun getSettingsSync(): AppTimerSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: AppTimerSettings)

    @Update
    suspend fun update(settings: AppTimerSettings)

    @Query("UPDATE app_timer_settings SET isEnabled = :enabled WHERE id = 1")
    suspend fun setEnabled(enabled: Boolean)

    @Query("UPDATE app_timer_settings SET dailyLimitMinutes = :minutes WHERE id = 1")
    suspend fun setDailyLimit(minutes: Int)

    @Query("UPDATE app_timer_settings SET timerApps = :apps WHERE id = 1")
    suspend fun setTimerApps(apps: String)
}

@Dao
interface AppTimerDailyUsageDao {
    @Query("SELECT * FROM app_timer_daily_usage WHERE date = :date")
    fun getUsageForDate(date: String): Flow<AppTimerDailyUsage?>

    @Query("SELECT * FROM app_timer_daily_usage WHERE date = :date")
    suspend fun getUsageForDateSync(date: String): AppTimerDailyUsage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: AppTimerDailyUsage)

    @Update
    suspend fun update(usage: AppTimerDailyUsage)

    @Query("UPDATE app_timer_daily_usage SET totalUsageMinutes = :minutes, lastUpdated = :timestamp WHERE date = :date")
    suspend fun updateUsage(date: String, minutes: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE app_timer_daily_usage SET limitReachedPopupShown = 1 WHERE date = :date")
    suspend fun markLimitPopupShown(date: String)

    @Query("UPDATE app_timer_daily_usage SET escalationPopupShown = 1 WHERE date = :date")
    suspend fun markEscalationPopupShown(date: String)

    @Query("UPDATE app_timer_daily_usage SET addedToQuickBlock = 1 WHERE date = :date")
    suspend fun markAddedToQuickBlock(date: String)

    @Query("UPDATE app_timer_daily_usage SET dailyOverrideUsed = 1, overrideExpiresAt = :expiresAt WHERE date = :date")
    suspend fun activateOverride(date: String, expiresAt: Long)

    @Query("UPDATE app_timer_daily_usage SET overrideExpiresAt = NULL WHERE date = :date")
    suspend fun clearOverride(date: String)

    @Query("DELETE FROM app_timer_daily_usage WHERE date < :beforeDate")
    suspend fun deleteOldUsage(beforeDate: String)
}

// ========== GLOBAL DAILY LIMIT DAOs ==========

@Dao
interface GlobalDailyLimitSettingsDao {
    @Query("SELECT * FROM global_daily_limit_settings WHERE id = 1")
    fun getSettings(): Flow<GlobalDailyLimitSettings?>

    @Query("SELECT * FROM global_daily_limit_settings WHERE id = 1")
    suspend fun getSettingsSync(): GlobalDailyLimitSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: GlobalDailyLimitSettings)

    @Update
    suspend fun update(settings: GlobalDailyLimitSettings)

    @Query("UPDATE global_daily_limit_settings SET isEnabled = :enabled, updatedAt = :timestamp WHERE id = 1")
    suspend fun setEnabled(enabled: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE global_daily_limit_settings SET dailyLimitMinutes = :minutes, updatedAt = :timestamp WHERE id = 1")
    suspend fun setDailyLimit(minutes: Int, timestamp: Long = System.currentTimeMillis())

    // ========== WHITELIST QUERIES ==========
    // Apps that are tracked but NOT blocked (e.g., WhatsApp for work)

    @Query("UPDATE global_daily_limit_settings SET whitelistedPackages = :packages, updatedAt = :timestamp WHERE id = 1")
    suspend fun setWhitelistedPackages(packages: String, timestamp: Long = System.currentTimeMillis())

    // ========== HARD MODE QUERIES ==========

    @Query("""
        UPDATE global_daily_limit_settings SET
            isHardModeEnabled = :enabled,
            hardModeLockUntil = :lockUntil,
            hardModeCooldownMinutes = :cooldownMinutes,
            updatedAt = :timestamp
        WHERE id = 1
    """)
    suspend fun setHardMode(
        enabled: Boolean,
        lockUntil: Long,
        cooldownMinutes: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE global_daily_limit_settings SET
            hardModeUnlockRequestedAt = :requestedAt,
            updatedAt = :timestamp
        WHERE id = 1
    """)
    suspend fun requestHardModeUnlock(
        requestedAt: Long = System.currentTimeMillis(),
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE global_daily_limit_settings SET
            isHardModeEnabled = 0,
            hardModeLockUntil = 0,
            hardModeUnlockRequestedAt = 0,
            updatedAt = :timestamp
        WHERE id = 1
    """)
    suspend fun disableHardMode(timestamp: Long = System.currentTimeMillis())

    @Query("SELECT hardModeLockUntil FROM global_daily_limit_settings WHERE id = 1")
    suspend fun getHardModeLockUntil(): Long?

    @Query("SELECT hardModeUnlockRequestedAt FROM global_daily_limit_settings WHERE id = 1")
    suspend fun getHardModeUnlockRequestedAt(): Long?

    @Query("SELECT isHardModeEnabled FROM global_daily_limit_settings WHERE id = 1")
    suspend fun isHardModeEnabled(): Boolean?
}

@Dao
interface GlobalDailyUsageDao {
    @Query("SELECT * FROM global_daily_usage WHERE date = :date")
    fun getUsageForDate(date: String): Flow<GlobalDailyUsage?>

    @Query("SELECT * FROM global_daily_usage WHERE date = :date")
    suspend fun getUsageForDateSync(date: String): GlobalDailyUsage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: GlobalDailyUsage)

    @Update
    suspend fun update(usage: GlobalDailyUsage)

    @Query("UPDATE global_daily_usage SET totalUsageMinutes = :minutes, lastUpdated = :timestamp WHERE date = :date")
    suspend fun updateUsage(date: String, minutes: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE global_daily_usage SET limitReached = 1, limitNotificationShown = 1, lastUpdated = :timestamp WHERE date = :date")
    suspend fun markLimitReached(date: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE global_daily_usage SET warningShown = 1, lastUpdated = :timestamp WHERE date = :date")
    suspend fun markWarningShown(date: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE global_daily_usage SET excessiveUsageNotificationShown = 1, lastUpdated = :timestamp WHERE date = :date")
    suspend fun markExcessiveUsageNotificationShown(date: String, timestamp: Long = System.currentTimeMillis())

    @Query("""
        UPDATE global_daily_usage SET
            overrideCount = overrideCount + 1,
            lastOverrideTime = :overrideTime,
            overrideExpiresAt = :expiresAt,
            overrideCooldownUntil = :cooldownUntil,
            lastUpdated = :timestamp
        WHERE date = :date
    """)
    suspend fun activateOverride(
        date: String,
        overrideTime: Long,
        expiresAt: Long,
        cooldownUntil: Long,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE global_daily_usage SET overrideExpiresAt = NULL, lastUpdated = :timestamp WHERE date = :date")
    suspend fun clearOverride(date: String, timestamp: Long = System.currentTimeMillis())

    // ========== ESCALATING EMERGENCY UNLOCK METHODS ==========

    @Query("UPDATE global_daily_usage SET warning75Shown = 1, lastUpdated = :timestamp WHERE date = :date")
    suspend fun markWarning75Shown(date: String, timestamp: Long = System.currentTimeMillis())

    @Query("""
        UPDATE global_daily_usage SET
            emergencyUnlockCount = emergencyUnlockCount + 1,
            currentEmergencyUnlockExpiresAt = :expiresAt,
            tomorrowLimitPenaltyMinutes = tomorrowLimitPenaltyMinutes + :penalty,
            lastUpdated = :timestamp
        WHERE date = :date
    """)
    suspend fun activateEmergencyUnlock(
        date: String,
        expiresAt: Long,
        penalty: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE global_daily_usage SET currentEmergencyUnlockExpiresAt = NULL, lastUpdated = :timestamp WHERE date = :date")
    suspend fun clearEmergencyUnlock(date: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT tomorrowLimitPenaltyMinutes FROM global_daily_usage WHERE date = :date")
    suspend fun getTomorrowPenalty(date: String): Int?

    @Query("DELETE FROM global_daily_usage WHERE date < :beforeDate")
    suspend fun deleteOldUsage(beforeDate: String)
}

// ========== DAILY USAGE SUMMARY DAO (for Today vs Yesterday comparison) ==========

@Dao
interface DailyUsageSummaryDao {
    @Query("SELECT * FROM daily_usage_summary WHERE date = :date")
    fun getSummaryForDate(date: String): Flow<DailyUsageSummary?>

    @Query("SELECT * FROM daily_usage_summary WHERE date = :date")
    suspend fun getSummaryForDateSync(date: String): DailyUsageSummary?

    @Query("SELECT * FROM daily_usage_summary ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentSummaries(limit: Int): List<DailyUsageSummary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: DailyUsageSummary)

    @Update
    suspend fun update(summary: DailyUsageSummary)

    @Query("UPDATE daily_usage_summary SET totalScreenTimeMinutes = :minutes, lastUpdated = :timestamp WHERE date = :date")
    suspend fun updateScreenTime(date: String, minutes: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE daily_usage_summary SET comparisonNotificationSent = 1, comparisonResult = :result, lastUpdated = :timestamp WHERE date = :date")
    suspend fun markComparisonSent(date: String, result: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM daily_usage_summary WHERE date < :beforeDate")
    suspend fun deleteOldSummaries(beforeDate: String)
}

// ========== SMART SUGGESTIONS DAOs ==========

@Dao
interface SmartSuggestionsSettingsDao {
    @Query("SELECT * FROM smart_suggestions_settings WHERE id = 1")
    fun getSettings(): Flow<SmartSuggestionsSettings?>

    @Query("SELECT * FROM smart_suggestions_settings WHERE id = 1")
    suspend fun getSettingsSync(): SmartSuggestionsSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: SmartSuggestionsSettings)

    @Update
    suspend fun update(settings: SmartSuggestionsSettings)

    @Query("UPDATE smart_suggestions_settings SET hasCompletedOnboarding = 1 WHERE id = 1")
    suspend fun markOnboardingComplete()

    @Query("""
        UPDATE smart_suggestions_settings SET
            lastAnalysisDate = :date,
            averageDailyUsageMinutes = :avgMinutes,
            suggestedDailyLimitMinutes = :suggestedLimit,
            lastUpdated = :timestamp
        WHERE id = 1
    """)
    suspend fun updateAnalysis(date: String, avgMinutes: Int, suggestedLimit: Int, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface EssentialAppWhitelistDao {
    @Query("SELECT * FROM essential_apps_whitelist ORDER BY appName ASC")
    fun getAllWhitelistedApps(): Flow<List<EssentialAppWhitelist>>

    @Query("SELECT * FROM essential_apps_whitelist ORDER BY appName ASC")
    suspend fun getAllWhitelistedAppsSync(): List<EssentialAppWhitelist>

    @Query("SELECT packageName FROM essential_apps_whitelist")
    suspend fun getWhitelistedPackageNames(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM essential_apps_whitelist WHERE packageName = :packageName)")
    suspend fun isWhitelisted(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: EssentialAppWhitelist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<EssentialAppWhitelist>)

    @Delete
    suspend fun delete(app: EssentialAppWhitelist)

    @Query("DELETE FROM essential_apps_whitelist WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Dao
interface SuggestedBlockingAppDao {
    @Query("SELECT * FROM suggested_blocking_apps WHERE isDismissed = 0 AND isAccepted = 0 ORDER BY averageDailyMinutes DESC")
    fun getActiveSuggestions(): Flow<List<SuggestedBlockingApp>>

    @Query("SELECT * FROM suggested_blocking_apps WHERE isDismissed = 0 AND isAccepted = 0 ORDER BY averageDailyMinutes DESC")
    suspend fun getActiveSuggestionsSync(): List<SuggestedBlockingApp>

    @Query("SELECT * FROM suggested_blocking_apps WHERE isAccepted = 1")
    suspend fun getAcceptedSuggestions(): List<SuggestedBlockingApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(suggestion: SuggestedBlockingApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(suggestions: List<SuggestedBlockingApp>)

    @Query("UPDATE suggested_blocking_apps SET isAccepted = 1 WHERE packageName = :packageName")
    suspend fun acceptSuggestion(packageName: String)

    @Query("UPDATE suggested_blocking_apps SET isDismissed = 1 WHERE packageName = :packageName")
    suspend fun dismissSuggestion(packageName: String)

    @Query("DELETE FROM suggested_blocking_apps")
    suspend fun clearAll()

    @Query("DELETE FROM suggested_blocking_apps WHERE suggestedAt < :beforeTime")
    suspend fun deleteOldSuggestions(beforeTime: Long)
}

// ========== BEDTIME MODE ==========

@Dao
interface BedtimeModeSettingsDao {
    @Query("SELECT * FROM bedtime_mode_settings WHERE id = 1")
    fun getSettings(): Flow<BedtimeModeSettings?>

    @Query("SELECT * FROM bedtime_mode_settings WHERE id = 1")
    suspend fun getSettingsSync(): BedtimeModeSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: BedtimeModeSettings)

    @Update
    suspend fun update(settings: BedtimeModeSettings)

    @Query("UPDATE bedtime_mode_settings SET isEnabled = :enabled, updatedAt = :timestamp WHERE id = 1")
    suspend fun setEnabled(enabled: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("""
        UPDATE bedtime_mode_settings SET
            startHour = :startHour,
            startMinute = :startMinute,
            endHour = :endHour,
            endMinute = :endMinute,
            updatedAt = :timestamp
        WHERE id = 1
    """)
    suspend fun setTimes(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE bedtime_mode_settings SET
            monday = :mon, tuesday = :tue, wednesday = :wed,
            thursday = :thu, friday = :fri, saturday = :sat, sunday = :sun,
            updatedAt = :timestamp
        WHERE id = 1
    """)
    suspend fun setDays(
        mon: Boolean, tue: Boolean, wed: Boolean,
        thu: Boolean, fri: Boolean, sat: Boolean, sun: Boolean,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE bedtime_mode_settings SET overrideUsedToday = :used, lastOverrideDate = :date WHERE id = 1")
    suspend fun setOverrideUsed(used: Boolean, date: String)
}

// ========== APP GROUPS ==========

@Dao
interface AppGroupDao {
    @Query("SELECT * FROM app_groups ORDER BY name ASC")
    fun getAllGroups(): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_groups WHERE isEnabled = 1 ORDER BY name ASC")
    fun getEnabledGroups(): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_groups WHERE isEnabled = 1 ORDER BY name ASC")
    suspend fun getEnabledGroupsSync(): List<AppGroup>

    @Query("SELECT * FROM app_groups WHERE id = :id")
    suspend fun getGroup(id: Long): AppGroup?

    @Query("SELECT * FROM app_groups WHERE id = :id")
    fun getGroupFlow(id: Long): Flow<AppGroup?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: AppGroup): Long

    @Update
    suspend fun update(group: AppGroup)

    @Delete
    suspend fun delete(group: AppGroup)

    @Query("UPDATE app_groups SET isEnabled = :enabled, updatedAt = :timestamp WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE app_groups SET packages = :packages, updatedAt = :timestamp WHERE id = :id")
    suspend fun updatePackages(id: Long, packages: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM app_groups WHERE packages LIKE '%' || :packageName || '%'")
    suspend fun getGroupsContainingApp(packageName: String): List<AppGroup>
}

@Dao
interface AppGroupMembershipDao {
    @Query("SELECT * FROM app_group_membership WHERE groupId = :groupId")
    fun getMembersForGroup(groupId: Long): Flow<List<AppGroupMembership>>

    @Query("SELECT * FROM app_group_membership WHERE packageName = :packageName")
    suspend fun getGroupsForApp(packageName: String): List<AppGroupMembership>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(membership: AppGroupMembership)

    @Delete
    suspend fun delete(membership: AppGroupMembership)

    @Query("DELETE FROM app_group_membership WHERE groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: Long)

    @Query("DELETE FROM app_group_membership WHERE packageName = :packageName")
    suspend fun deleteAppFromAllGroups(packageName: String)
}

// ========== ONBOARDING ==========

@Dao
interface OnboardingDao {
    @Query("SELECT * FROM onboarding_state WHERE id = 1")
    fun getOnboardingState(): Flow<OnboardingState?>

    @Query("SELECT * FROM onboarding_state WHERE id = 1")
    suspend fun getOnboardingStateSync(): OnboardingState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: OnboardingState)

    @Update
    suspend fun update(state: OnboardingState)

    @Query("UPDATE onboarding_state SET hasCompletedOnboarding = 1, completedAt = :timestamp WHERE id = 1")
    suspend fun completeOnboarding(timestamp: Long = System.currentTimeMillis())

    @Query("SELECT hasCompletedOnboarding FROM onboarding_state WHERE id = 1")
    suspend fun hasCompletedOnboarding(): Boolean?
}

// ========== COMBINED DAO FOR WIDGET/SERVICES ==========

@Dao
interface FocusBlockDao {
    // Quick block for widget
    @Query("SELECT * FROM quick_block_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveQuickBlockSessionDirect(): QuickBlockSession?

    @Query("SELECT * FROM blocked_apps WHERE isBlocked = 1")
    suspend fun getActiveBlockedAppsDirect(): List<BlockedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuickBlockSession(session: QuickBlockSession): Long

    @Query("UPDATE quick_block_sessions SET isActive = 0 WHERE id = :id")
    suspend fun endQuickBlockSession(id: Long)

    // Onboarding state
    @Query("SELECT hasCompletedOnboarding FROM onboarding_state WHERE id = 1")
    suspend fun hasCompletedOnboarding(): Boolean?

    @Query("UPDATE onboarding_state SET hasCompletedOnboarding = 1, completedAt = :timestamp WHERE id = 1")
    suspend fun completeOnboarding(timestamp: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOnboardingState(state: OnboardingState)

    // App groups
    @Query("SELECT * FROM app_groups ORDER BY name ASC")
    fun getAllAppGroups(): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_groups WHERE isEnabled = 1")
    suspend fun getEnabledAppGroupsDirect(): List<AppGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppGroup(group: AppGroup): Long

    @Query("UPDATE app_groups SET isEnabled = :enabled WHERE id = :id")
    suspend fun setAppGroupEnabled(id: Long, enabled: Boolean)
}
