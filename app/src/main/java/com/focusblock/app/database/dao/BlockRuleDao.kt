package com.focusblock.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.ProtectionLock
import com.focusblock.app.database.entity.RuleOverride
import com.focusblock.app.database.entity.RuleUsage
import kotlinx.coroutines.flow.Flow

/** Five weeks of daily history, two days of hourly. See [BlockRuleDao.pruneUsage]. */
private const val DAILY_RETENTION_MS = 35L * 24 * 60 * 60 * 1000
private const val HOURLY_RETENTION_MS = 48L * 60 * 60 * 1000

@Dao
interface BlockRuleDao {

    @Query("SELECT * FROM block_rules ORDER BY startMinute ASC, name ASC")
    fun getAllRules(): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules ORDER BY startMinute ASC, name ASC")
    suspend fun getAllRulesSync(): List<BlockRule>

    @Query("SELECT * FROM block_rules WHERE isEnabled = 1")
    fun getEnabledRules(): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules WHERE isEnabled = 1")
    suspend fun getEnabledRulesSync(): List<BlockRule>

    @Query("SELECT * FROM block_rules WHERE id = :id")
    suspend fun getRule(id: Long): BlockRule?

    @Query("SELECT * FROM block_rules WHERE id = :id")
    fun getRuleFlow(id: Long): Flow<BlockRule?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: BlockRule): Long

    @Update
    suspend fun update(rule: BlockRule)

    @Delete
    suspend fun delete(rule: BlockRule)

    @Query("UPDATE block_rules SET isEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE block_rules SET isManualActive = :active, activeUntil = :until, updatedAt = :now " +
            "WHERE id = :id"
    )
    suspend fun setManualActive(
        id: Long,
        active: Boolean,
        until: Long?,
        now: Long = System.currentTimeMillis()
    )

    // ---------------- Usage buckets ----------------

    @Query("SELECT usageSeconds FROM rule_usage WHERE ruleId = :ruleId AND bucket = :bucket")
    suspend fun getUsageSeconds(ruleId: Long, bucket: String): Long?

    @Query("SELECT * FROM rule_usage WHERE ruleId = :ruleId AND bucket = :bucket")
    suspend fun getUsage(ruleId: Long, bucket: String): RuleUsage?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUsage(usage: RuleUsage)

    @Query(
        "UPDATE rule_usage SET usageSeconds = usageSeconds + :seconds, lastUpdated = :now " +
            "WHERE ruleId = :ruleId AND bucket = :bucket"
    )
    suspend fun incrementUsage(
        ruleId: Long,
        bucket: String,
        seconds: Long,
        now: Long = System.currentTimeMillis()
    )

    /** Insert-then-increment so the first write of a bucket is not lost. */
    suspend fun addUsage(ruleId: Long, bucket: String, seconds: Long) {
        insertUsage(RuleUsage(ruleId = ruleId, bucket = bucket))
        incrementUsage(ruleId, bucket, seconds)
    }

    @Query(
        "UPDATE rule_usage SET launchCount = launchCount + 1, lastUpdated = :now " +
            "WHERE ruleId = :ruleId AND bucket = :bucket"
    )
    suspend fun incrementLaunch(ruleId: Long, bucket: String, now: Long = System.currentTimeMillis())

    suspend fun addLaunch(ruleId: Long, bucket: String) {
        insertUsage(RuleUsage(ruleId = ruleId, bucket = bucket))
        incrementLaunch(ruleId, bucket)
    }

    @Query("DELETE FROM rule_usage WHERE bucket < :oldestBucketToKeep AND bucket NOT LIKE '%T%'")
    suspend fun pruneDailyUsageBefore(oldestBucketToKeep: String)

    @Query("DELETE FROM rule_usage WHERE lastUpdated < :cutoff AND bucket LIKE '%T%'")
    suspend fun pruneHourlyUsageBefore(cutoff: Long)

    /**
     * Drop usage rows nothing will read again.
     *
     * Enforcement only ever asks for the current bucket, so older rows are
     * history and nothing else. Both prune queries existed and neither was
     * ever called: the table gained a row per rule per day and per rule per
     * hour, forever, in a database that sits on a phone for years. An hourly
     * sip rule alone is 8,760 rows a year.
     *
     * Daily buckets are kept for five weeks so a month of history stays
     * available to any screen that later wants it; hourly buckets for two
     * days, which is more than an hourly budget can ever consult.
     */
    suspend fun pruneUsage(now: Long = System.currentTimeMillis()) {
        val oldestDailyToKeep = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(now - DAILY_RETENTION_MS))
        pruneDailyUsageBefore(oldestDailyToKeep)
        pruneHourlyUsageBefore(now - HOURLY_RETENTION_MS)
    }

    // ---------------- Overrides ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverride(override: RuleOverride)

    @Query(
        "SELECT COUNT(*) > 0 FROM rule_overrides WHERE ruleId = :ruleId AND expiresAt > :now"
    )
    suspend fun hasActiveOverride(ruleId: Long, now: Long): Boolean

    @Query("SELECT * FROM rule_overrides WHERE ruleId = :ruleId AND expiresAt > :now LIMIT 1")
    suspend fun getActiveOverride(ruleId: Long, now: Long): RuleOverride?

    @Query("SELECT COUNT(*) FROM rule_overrides WHERE date = :date")
    suspend fun countOverridesOn(date: String): Int

    @Query("DELETE FROM rule_overrides WHERE expiresAt < :cutoff")
    suspend fun pruneOverridesBefore(cutoff: Long)

    @Query("DELETE FROM rule_overrides WHERE ruleId = :ruleId")
    suspend fun clearOverridesFor(ruleId: Long)
}

@Dao
interface ProtectionLockDao {

    @Query("SELECT * FROM protection_lock WHERE id = 1")
    fun getLock(): Flow<ProtectionLock?>

    @Query("SELECT * FROM protection_lock WHERE id = 1")
    suspend fun getLockSync(): ProtectionLock?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lock: ProtectionLock)

    @Query("DELETE FROM protection_lock")
    suspend fun clear()

}
