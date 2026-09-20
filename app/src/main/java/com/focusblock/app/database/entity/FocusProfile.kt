package com.focusblock.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * THE PERSON USING THIS, AND WHAT THEY HAVE AT STAKE
 * ============================================================================
 *
 * Everything here exists because of one finding that runs through the research
 * on why blockers get abandoned (78% inside two weeks, ~90% bypassed in the
 * first week):
 *
 *     People bypass a blocker because the current urge is CONCRETE
 *     and the earlier goal is ABSTRACT.
 *
 * At 9pm "scroll Reddit" is vivid and "get clients" is a word. A wall does not
 * fix that asymmetry -- it just provokes reactance, which makes the restricted
 * thing more attractive. So this app does three things a plain blocker does
 * not:
 *
 *   1. [reason] -- the user's own words for what the evening is for, captured
 *      ONCE at setup and shown back at the exact moment of the urge. Never
 *      typed again, never prompted for. This is not journaling; it is moving
 *      the abstract thing to where the concrete thing is.
 *
 *   2. [pauseSeconds] -- impulse friction. Opening a blocked app does not hit a
 *      wall, it hits a short enforced pause. Friction at the moment of impulse
 *      outperforms prohibition, and it does not trigger reactance because the
 *      user was slowed rather than forbidden.
 *
 *   3. Stake, not score. Streaks and totals here are framed as something to
 *      LOSE, not points to collect. Gamification by points decays once the
 *      reward becomes predictable; loss aversion does not. This is why Forest
 *      works -- a tree you planted dies -- and why a leaderboard would not.
 */
@Entity(tableName = "focus_profile")
data class FocusProfile(
    @PrimaryKey
    val id: Int = 1,

    // ---------------- The reason (set once, shown at the urge) ----------------

    /**
     * What the protected time is for, in the user's own words.
     * Example: "Send 3 client messages". Captured at setup, editable in Setup,
     * never prompted for again.
     */
    val reason: String = "",

    /** Shown on the block screen under the reason. Optional, one line. */
    val reasonDetail: String = "",

    // ---------------- Impulse friction ----------------

    /**
     * Seconds the block screen holds before offering any choice.
     *
     * Long enough for the impulse to pass, short enough not to be punitive.
     * Zero disables the pause and makes this a plain wall.
     */
    val pauseSeconds: Int = 8,

    /** Whether a "let me in for 5 minutes" option appears after the pause. */
    val allowBreathThrough: Boolean = true,

    // ---------------- Park the phone ----------------

    /**
     * The strongest intervention in the literature is not software: putting the
     * phone in another room beat blocking apps by ~40% on focus in Stanford's
     * work. Park mode is the software version -- commit the phone for a set
     * period, and everything not allowlisted is blocked for its duration.
     */
    val parkUntil: Long? = null,
    val parkStartedAt: Long? = null,

    // ---------------- Stake ----------------

    /** Consecutive days a protected window completed without a bypass. */
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,

    /** yyyy-MM-dd of the last day a protected window was completed cleanly. */
    val lastCleanDate: String = "",

    /**
     * One forgiven day per calendar month.
     *
     * A streak that shatters on the first miss is abandoned, not repaired --
     * the all-or-nothing framing is what makes streak mechanics brittle. One
     * grace day keeps the stake real without making a single bad evening
     * terminal.
     */
    val graceUsedMonth: String = "",

    /** Minutes spent inside protected windows without reaching for a blocked app. */
    val totalProtectedMinutes: Long = 0,

    /** Completed protected windows, all time. */
    val windowsCompleted: Int = 0,

    /** Times the pause was shown and the user backed out. The real win metric. */
    val impulsesPassed: Int = 0,

    /** Times the user pushed through the pause anyway. Kept honestly. */
    val impulsesFollowed: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {

    fun isParked(now: Long = System.currentTimeMillis()): Boolean =
        parkUntil != null && parkUntil > now

    /** Ratio of urges that passed. Reported as a count, never as a grade. */
    fun impulseTotal(): Int = impulsesPassed + impulsesFollowed

    /**
     * What the user stands to lose right now, phrased as stake rather than
     * achievement. Returns null when there is nothing meaningful at risk yet,
     * so the app does not manufacture pressure on day one.
     */
    fun stakeLine(): String? = when {
        currentStreakDays >= 2 -> "$currentStreakDays day streak on the line"
        windowsCompleted >= 3 -> "$windowsCompleted evenings protected so far"
        else -> null
    }

    fun hasReason(): Boolean = reason.isNotBlank()
}

/**
 * One completed (or broken) protected window. The raw material for streaks and
 * for the honest version of "did this work?".
 */
@Entity(tableName = "window_outcomes")
data class WindowOutcome(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ruleId: Long,
    val ruleName: String,
    /** yyyy-MM-dd of the day the window started. */
    val date: String,
    val startedAt: Long,
    val endedAt: Long,
    val minutesProtected: Int,
    /** Pauses shown during this window. */
    val impulsesPassed: Int = 0,
    val impulsesFollowed: Int = 0,
    /** True when the user never pushed through and never used an override. */
    val clean: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A milestone worth marking, and the only thing in the app designed to leave it.
 *
 * Sharing is the one growth mechanic that does not fight the product: a person
 * telling someone what they are doing creates the external accountability the
 * research identifies as the strongest predictor of sticking with a blocker.
 * The app never shares anything by itself and never asks twice.
 */
@Entity(tableName = "milestones")
data class Milestone(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val kind: MilestoneKind,
    val value: Int,
    val achievedAt: Long = System.currentTimeMillis(),
    val seen: Boolean = false,
    val shared: Boolean = false
)

enum class MilestoneKind {
    /** First protected window ever completed. */
    FIRST_WINDOW,

    /** Streak reached 3, 7, 14, 30, 60, 100 days. */
    STREAK,

    /** Cumulative protected hours crossed 10, 25, 50, 100. */
    PROTECTED_HOURS,

    /** Impulses passed crossed 10, 50, 100. */
    IMPULSES_PASSED,

    /** Screen time down a meaningful amount against the first week's baseline. */
    SCREEN_TIME_DOWN
}
