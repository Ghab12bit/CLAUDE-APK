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

    @Query("SELECT * FROM schedules WHERE isEnabled = 1 AND :currentMinute >= startTimeMinutes AND :currentMinute < endTimeMinutes AND daysOfWeek LIKE '%' || :dayOfWeek || '%'")
    suspend fun getActiveSchedules(currentMinute: Int, dayOfWeek: Int): List<Schedule>
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
