package com.focusblock.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focusblock.app.database.entity.AppLimitEntity
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.entity.BlockSessionEntity
import com.focusblock.app.database.entity.DiagnosticEvent
import com.focusblock.app.database.entity.EssentialApp
import com.focusblock.app.database.entity.RecommendationEntity
import com.focusblock.app.database.entity.UnlockEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockSessionDao {
    @Query("SELECT * FROM block_sessions WHERE isActive = 1 ORDER BY startedAt DESC LIMIT 1")
    fun activeFlow(): Flow<BlockSessionEntity?>

    @Query("SELECT * FROM block_sessions WHERE isActive = 1 ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): BlockSessionEntity?

    @Query("SELECT * FROM block_sessions WHERE isActive = 1")
    suspend fun allActive(): List<BlockSessionEntity>

    @Query("SELECT * FROM block_sessions WHERE id = :id")
    suspend fun get(id: Long): BlockSessionEntity?

    @Insert
    suspend fun insert(session: BlockSessionEntity): Long

    @Update
    suspend fun update(session: BlockSessionEntity)

    /** The most recent block that ended on its own and still needs "Did you finish?". */
    @Query("SELECT * FROM block_sessions WHERE isActive = 0 AND endReason = 'COMPLETED' AND outcome IS NULL ORDER BY endedAt DESC LIMIT 1")
    fun awaitingOutcomeFlow(): Flow<BlockSessionEntity?>

    @Query("SELECT * FROM block_sessions WHERE isActive = 0 AND endReason = 'COMPLETED' AND outcome IS NULL ORDER BY endedAt DESC LIMIT 1")
    suspend fun awaitingOutcome(): BlockSessionEntity?

    /** Older unanswered blocks become UNANSWERED once a newer one ends or starts. */
    @Query("UPDATE block_sessions SET outcome = 'UNANSWERED', outcomeAt = :now WHERE isActive = 0 AND outcome IS NULL AND endReason = 'COMPLETED' AND id != :keepId")
    suspend fun markOlderUnanswered(keepId: Long, now: Long)

    @Query("SELECT * FROM block_sessions WHERE startedAt >= :since ORDER BY startedAt DESC")
    fun sinceFlow(since: Long): Flow<List<BlockSessionEntity>>

    @Query("SELECT * FROM block_sessions WHERE startedAt >= :since ORDER BY startedAt DESC")
    suspend fun since(since: Long): List<BlockSessionEntity>

    @Query("SELECT * FROM block_sessions ORDER BY startedAt DESC")
    suspend fun all(): List<BlockSessionEntity>

    @Query("SELECT MIN(startedAt) FROM block_sessions")
    suspend fun firstStartedAt(): Long?

    @Query("DELETE FROM block_sessions WHERE isActive = 0")
    suspend fun deleteHistory()
}

@Dao
interface EssentialAppDao {
    @Query("SELECT * FROM essential_apps ORDER BY packageName")
    fun allFlow(): Flow<List<EssentialApp>>

    @Query("SELECT packageName FROM essential_apps")
    suspend fun packages(): List<String>

    @Query("SELECT COUNT(*) FROM essential_apps")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<EssentialApp>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(apps: List<EssentialApp>)

    @Query("DELETE FROM essential_apps WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    @Query("DELETE FROM essential_apps")
    suspend fun deleteAll()
}

@Dao
interface AppLimitDao {
    @Query("SELECT * FROM app_limits ORDER BY createdAt")
    fun allFlow(): Flow<List<AppLimitEntity>>

    @Query("SELECT * FROM app_limits ORDER BY createdAt")
    suspend fun all(): List<AppLimitEntity>

    @Query("SELECT * FROM app_limits WHERE id = :id")
    suspend fun get(id: Long): AppLimitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(limit: AppLimitEntity): Long

    @Query("UPDATE app_limits SET isEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("DELETE FROM app_limits WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface UnlockEventDao {
    @Insert
    suspend fun insert(event: UnlockEventEntity): Long

    @Update
    suspend fun update(event: UnlockEventEntity)

    @Query("SELECT * FROM unlock_events WHERE id = :id")
    suspend fun get(id: Long): UnlockEventEntity?

    @Query("SELECT * FROM unlock_events WHERE status = 'GRANTED' AND expiresAt > :now")
    suspend fun activeOverrides(now: Long): List<UnlockEventEntity>

    @Query("SELECT * FROM unlock_events WHERE status = 'GRANTED' AND expiresAt > :now")
    fun activeOverridesFlow(now: Long): Flow<List<UnlockEventEntity>>

    /** Latest emergency request for [pkg] that has not been used or cancelled. */
    @Query("SELECT * FROM unlock_events WHERE packageName = :pkg AND status = 'PENDING' ORDER BY requestedAt DESC LIMIT 1")
    suspend fun pending(pkg: String): UnlockEventEntity?

    @Query("SELECT * FROM unlock_events WHERE packageName = :pkg AND status = 'PENDING' ORDER BY requestedAt DESC LIMIT 1")
    fun pendingFlow(pkg: String): Flow<UnlockEventEntity?>

    @Query("SELECT * FROM unlock_events WHERE requestedAt >= :since ORDER BY requestedAt DESC")
    fun sinceFlow(since: Long): Flow<List<UnlockEventEntity>>

    @Query("SELECT * FROM unlock_events WHERE requestedAt >= :since ORDER BY requestedAt DESC")
    suspend fun since(since: Long): List<UnlockEventEntity>

    @Query("SELECT * FROM unlock_events ORDER BY requestedAt DESC")
    suspend fun all(): List<UnlockEventEntity>

    @Query("DELETE FROM unlock_events WHERE status != 'PENDING' AND (expiresAt IS NULL OR expiresAt < :now)")
    suspend fun deleteHistory(now: Long)
}

@Dao
interface RecommendationDao {
    @Query("SELECT * FROM recommendations")
    suspend fun all(): List<RecommendationEntity>

    @Query("SELECT * FROM recommendations")
    fun allFlow(): Flow<List<RecommendationEntity>>

    @Query("SELECT * FROM recommendations WHERE signature = :signature")
    suspend fun get(signature: String): RecommendationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rec: RecommendationEntity)

    @Query("DELETE FROM recommendations")
    suspend fun deleteAll()
}

@Dao
interface DiagnosticEventDao {
    @Insert
    suspend fun insert(event: DiagnosticEvent)

    @Query("SELECT * FROM diagnostic_events ORDER BY time DESC LIMIT :limit")
    fun recentFlow(limit: Int = 50): Flow<List<DiagnosticEvent>>

    @Query("SELECT * FROM diagnostic_events ORDER BY time DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<DiagnosticEvent>

    @Query("DELETE FROM diagnostic_events WHERE time < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM diagnostic_events")
    suspend fun deleteAll()
}

/** Structured block-attempt queries over the existing block_logs table. */
@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(log: BlockLog): Long

    @Query("UPDATE block_logs SET action = :action WHERE id = :id")
    suspend fun setAction(id: Long, action: String)

    @Query("SELECT COUNT(*) FROM block_logs WHERE packageName = :pkg AND timestamp >= :since")
    suspend fun countSince(pkg: String, since: Long): Int

    @Query("SELECT * FROM block_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun sinceFlow(since: Long): Flow<List<BlockLog>>

    @Query("SELECT * FROM block_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<BlockLog>

    @Query("SELECT * FROM block_logs ORDER BY timestamp DESC")
    suspend fun all(): List<BlockLog>

    @Query("SELECT MIN(timestamp) FROM block_logs")
    suspend fun firstTimestamp(): Long?

    @Query("SELECT DISTINCT packageName FROM block_logs WHERE timestamp >= :since")
    suspend fun packagesSince(since: Long): List<String>

    @Query("DELETE FROM block_logs")
    suspend fun deleteAll()
}
