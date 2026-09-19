package com.focusblock.app.blocking

import com.focusblock.app.database.dao.FocusProfileDao
import com.focusblock.app.database.entity.FocusProfile
import com.focusblock.app.database.entity.Milestone
import com.focusblock.app.database.entity.MilestoneKind
import com.focusblock.app.database.entity.WindowOutcome
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps what the user has at stake.
 *
 * Deliberately NOT a scoring system. Points and levels lose their pull once the
 * reward becomes predictable, which is the documented shelf life of gamified
 * behaviour-change apps. What does not decay is loss aversion: something you
 * built and could lose. So everything here is framed as a stake -- a streak on
 * the line, evenings already protected -- and nothing is framed as a score, a
 * grade or a rank.
 *
 * Two design rules follow from the research and are enforced here:
 *
 *   - **One forgiven day a month.** A streak that shatters on the first miss
 *     gets abandoned rather than repaired; all-or-nothing framing is what makes
 *     streak mechanics brittle. Grace keeps the stake real without making one
 *     bad evening terminal.
 *
 *   - **The headline number is impulses passed, not hours blocked.** Hours
 *     blocked measures the software. Impulses passed measures the person, and
 *     it is the only number here that reflects something the user did.
 */
@Singleton
class StakeTracker @Inject constructor(
    private val profileDao: FocusProfileDao
) {

    /** The user reached for a blocked app, sat through the pause, and backed out. */
    suspend fun impulsePassed() {
        profileDao.require()
        profileDao.recordImpulsePassed()
        checkImpulseMilestones()
    }

    /** The user pushed through the pause. Recorded without comment or penalty. */
    suspend fun impulseFollowed() {
        profileDao.require()
        profileDao.recordImpulseFollowed()
    }

    /**
     * A protected window finished. Updates the streak, banks the minutes and
     * raises any milestone crossed.
     *
     * [clean] is false when the user pushed through the pause or used an
     * override during the window.
     */
    suspend fun windowFinished(
        ruleId: Long,
        ruleName: String,
        startedAt: Long,
        endedAt: Long,
        impulsesPassed: Int,
        impulsesFollowed: Int,
        clean: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        val profile = profileDao.require()
        val date = dayKey(startedAt)
        val minutes = ((endedAt - startedAt) / 60_000L).toInt().coerceAtLeast(0)

        profileDao.insertOutcome(
            WindowOutcome(
                ruleId = ruleId,
                ruleName = ruleName,
                date = date,
                startedAt = startedAt,
                endedAt = endedAt,
                minutesProtected = minutes,
                impulsesPassed = impulsesPassed,
                impulsesFollowed = impulsesFollowed,
                clean = clean
            )
        )

        if (clean) {
            val streak = nextStreak(profile, date)
            profileDao.recordWindowCompleted(streak, date, minutes, now)
            checkStreakMilestones(streak)
            checkVolumeMilestones(profile.totalProtectedMinutes + minutes)
            if (profile.windowsCompleted == 0) {
                raise(MilestoneKind.FIRST_WINDOW, 1)
            }
        } else {
            applyMissedDay(profile, date, now)
        }
    }

    /**
     * Continue the streak if yesterday was clean, otherwise start again at one.
     * Same-day repeats do not double-count.
     */
    private fun nextStreak(profile: FocusProfile, date: String): Int {
        if (profile.lastCleanDate == date) return profile.currentStreakDays.coerceAtLeast(1)
        return if (profile.lastCleanDate == previousDay(date)) {
            profile.currentStreakDays + 1
        } else {
            1
        }
    }

    /**
     * A missed evening. Spends the month's grace day if it is still available,
     * so the streak survives; otherwise the streak ends.
     */
    private suspend fun applyMissedDay(profile: FocusProfile, date: String, now: Long) {
        val month = date.substring(0, 7)
        if (profile.graceUsedMonth != month && profile.currentStreakDays >= 2) {
            profileDao.useGrace(month, now)
        } else {
            profileDao.breakStreak(now)
        }
    }

    // ------------------------------------------------------------------
    // Milestones
    // ------------------------------------------------------------------

    private suspend fun checkStreakMilestones(streak: Int) {
        if (streak in STREAK_MARKS) raise(MilestoneKind.STREAK, streak)
    }

    private suspend fun checkVolumeMilestones(totalMinutes: Long) {
        val hours = (totalMinutes / 60).toInt()
        HOUR_MARKS.lastOrNull { hours >= it }?.let { raise(MilestoneKind.PROTECTED_HOURS, it) }
    }

    private suspend fun checkImpulseMilestones() {
        val profile = profileDao.require()
        IMPULSE_MARKS.lastOrNull { profile.impulsesPassed >= it }
            ?.let { raise(MilestoneKind.IMPULSES_PASSED, it) }
    }

    /** Raised at most once per (kind, value), so a milestone never repeats. */
    private suspend fun raise(kind: MilestoneKind, value: Int) {
        if (profileDao.milestoneCount(kind.name, value) > 0) return
        profileDao.insertMilestone(Milestone(kind = kind, value = value))
    }

    suspend fun pendingMilestone(): Milestone? = profileDao.nextUnseenMilestone()

    suspend fun markSeen(id: Long) = profileDao.markMilestoneSeen(id)

    suspend fun markShared(id: Long) = profileDao.markMilestoneShared(id)

    // ------------------------------------------------------------------

    private fun dayKey(epoch: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epoch))

    private fun previousDay(date: String): String {
        val cal = Calendar.getInstance()
        cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return ""
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    companion object {
        val STREAK_MARKS = listOf(3, 7, 14, 30, 60, 100)
        val HOUR_MARKS = listOf(10, 25, 50, 100, 250)
        val IMPULSE_MARKS = listOf(10, 50, 100, 250)

        /**
         * Copy for a milestone. Written to be worth showing someone, because
         * telling another person is the external accountability the research
         * identifies as the strongest predictor of sticking with a blocker --
         * and it is the only growth mechanic here that serves the user rather
         * than the app.
         */
        fun headline(kind: MilestoneKind, value: Int): Pair<String, String> = when (kind) {
            MilestoneKind.FIRST_WINDOW ->
                "First evening protected" to "You finished a full window without reaching for a blocked app."
            MilestoneKind.STREAK ->
                "$value evenings in a row" to "Your streak is the thing you now have to lose."
            MilestoneKind.PROTECTED_HOURS ->
                "$value hours protected" to "Time that went to your own work instead of a feed."
            MilestoneKind.IMPULSES_PASSED ->
                "$value urges passed" to "Each one was a reach for an app that you didn't follow."
            MilestoneKind.SCREEN_TIME_DOWN ->
                "Screen time down $value%" to "Measured against your first week."
        }
    }
}
