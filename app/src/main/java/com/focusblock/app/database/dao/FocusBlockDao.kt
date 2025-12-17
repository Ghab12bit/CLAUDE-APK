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
