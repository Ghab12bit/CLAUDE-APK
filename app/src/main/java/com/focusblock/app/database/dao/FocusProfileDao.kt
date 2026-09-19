package com.focusblock.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.focusblock.app.database.entity.FocusProfile
import com.focusblock.app.database.entity.Milestone
import com.focusblock.app.database.entity.WindowOutcome
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusProfileDao {

    @Query("SELECT * FROM focus_profile WHERE id = 1")
    fun getProfile(): Flow<FocusProfile?>

    @Query("SELECT * FROM focus_profile WHERE id = 1")
    suspend fun getProfileSync(): FocusProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: FocusProfile)

    /** Always returns a profile, creating the default row on first access. */
    suspend fun require(): FocusProfile =
        getProfileSync() ?: FocusProfile().also { upsert(it) }

    @Query("UPDATE focus_profile SET reason = :reason, reasonDetail = :detail, updatedAt = :now WHERE id = 1")
    suspend fun setReason(reason: String, detail: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE focus_profile SET pauseSeconds = :seconds, updatedAt = :now WHERE id = 1")
    suspend fun setPauseSeconds(seconds: Int, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE focus_profile SET parkUntil = :until, parkStartedAt = :startedAt, updatedAt = :now " +
            "WHERE id = 1"
    )
    suspend fun setPark(until: Long?, startedAt: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE focus_profile SET impulsesPassed = impulsesPassed + 1, updatedAt = :now WHERE id = 1")
    suspend fun recordImpulsePassed(now: Long = System.currentTimeMillis())

    @Query("UPDATE focus_profile SET impulsesFollowed = impulsesFollowed + 1, updatedAt = :now WHERE id = 1")
    suspend fun recordImpulseFollowed(now: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE focus_profile SET
            currentStreakDays = :streak,
            longestStreakDays = MAX(longestStreakDays, :streak),
            lastCleanDate = :date,
            totalProtectedMinutes = totalProtectedMinutes + :minutes,
            windowsCompleted = windowsCompleted + 1,
            updatedAt = :now
        WHERE id = 1
        """
    )
    suspend fun recordWindowCompleted(
        streak: Int,
        date: String,
        minutes: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE focus_profile SET currentStreakDays = 0, updatedAt = :now WHERE id = 1")
    suspend fun breakStreak(now: Long = System.currentTimeMillis())

    @Query("UPDATE focus_profile SET graceUsedMonth = :month, updatedAt = :now WHERE id = 1")
    suspend fun useGrace(month: String, now: Long = System.currentTimeMillis())

    // ---------------- Window outcomes ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutcome(outcome: WindowOutcome)

    @Query("SELECT * FROM window_outcomes ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentOutcomes(limit: Int = 30): List<WindowOutcome>

    @Query("SELECT * FROM window_outcomes WHERE date = :date LIMIT 1")
    suspend fun outcomeForDate(date: String): WindowOutcome?

    @Query("SELECT COUNT(*) FROM window_outcomes WHERE clean = 1")
    suspend fun cleanWindowCount(): Int

    @Query("DELETE FROM window_outcomes WHERE startedAt < :cutoff")
    suspend fun pruneOutcomesBefore(cutoff: Long)

    // ---------------- Milestones ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMilestone(milestone: Milestone)

    @Query("SELECT * FROM milestones WHERE seen = 0 ORDER BY achievedAt DESC LIMIT 1")
    suspend fun nextUnseenMilestone(): Milestone?

    @Query("SELECT * FROM milestones ORDER BY achievedAt DESC")
    fun allMilestones(): Flow<List<Milestone>>

    @Query("SELECT COUNT(*) FROM milestones WHERE kind = :kind AND value = :value")
    suspend fun milestoneCount(kind: String, value: Int): Int

    @Query("UPDATE milestones SET seen = 1 WHERE id = :id")
    suspend fun markMilestoneSeen(id: Long)

    @Query("UPDATE milestones SET shared = 1 WHERE id = :id")
    suspend fun markMilestoneShared(id: Long)
}
