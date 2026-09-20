package com.focusblock.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar

/**
 * ============================================================================
 * THE UNIFIED BLOCKING MODEL
 * ============================================================================
 *
 * FocusBlock previously had nine parallel blocking sources (Quick Block,
 * Schedules, App Timer, Global Daily Limit, Focus Cycle, Bedtime, Strict Mode,
 * Hard Mode, App Groups). Each carried its own app list, its own detection
 * logic and its own bypass rules. They could not be reasoned about together,
 * several of them silently disabled each other, and two of them never ran at
 * all.
 *
 * They are all replaced by ONE object: a [BlockRule].
 *
 *     A rule = a set of apps  ×  a set of conditions
 *
 * Every condition attached to a rule must be satisfied for the rule to be
 * active (conditions are ANDed). A rule with no conditions is a manual block:
 * on until you turn it off.
 *
 *     Old feature            Becomes
 *     ------------------     -------------------------------------------
 *     Quick Block            manual rule, optional expiry
 *     Schedule               rule + Time condition
 *     Bedtime Mode           rule + Time condition crossing midnight
 *     Daily App Timer        rule + Usage condition (DAILY)
 *     Daily Screen Limit     rule + Usage condition (DAILY), wider app set
 *     Focus Cycle            rule + Usage condition (HOURLY)
 *     App Groups             a saved app selection, not a blocking source
 *     Strict / Hard Mode     ProtectionLock — a lock on configuration
 *
 * Two invariants hold for the whole engine and must never be broken:
 *
 *   1. Rules are ADDITIVE. A rule can only ever add a block. Nothing may
 *      un-block an app that another active rule is blocking. (The old
 *      Pomodoro path returned "allow" early and silently suppressed active
 *      schedules — that class of bug is impossible in this model.)
 *
 *   2. The ALLOWLIST wins over everything, including a PIN-locked rule.
 *      The phone must stay usable and reachable.
 */

enum class RuleKind {
    /** On until turned off, or until [BlockRule.activeUntil]. */
    MANUAL,

    /** Driven by conditions (time and/or usage). Always evaluated. */
    AUTOMATIC
}

enum class UsageWindow {
    /** Budget resets at midnight. "60 minutes per day." */
    DAILY,

    /** Budget resets every hour. "10 minutes per hour" — the old Focus Cycle. */
    HOURLY
}

/**
 * How hard it is to end a rule early.
 *
 * Every level is chosen by the user with its terms shown BEFORE it arms, and
 * no level is ever raised automatically. A level never overrides the allowlist.
 */
enum class CommitmentLevel {
    /** Stop it whenever you like. */
    OFF,

    /** Cannot stop before the rule's natural end. One emergency unlock per day. */
    LOCKED,

    /** Cannot stop without the PIN, after a cooldown. */
    PIN_LOCKED
}

/**
 * A single blocking rule. This is the ONLY thing that blocks an app.
 */
@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val kind: RuleKind = RuleKind.AUTOMATIC,
    val isEnabled: Boolean = true,

    /** Comma-separated package names this rule covers. */
    val packages: String = "",

    val iconType: ScheduleIconType = ScheduleIconType.FOCUS,
    val colorHex: String = "#0A84FF",

    /**
     * Optional free-text note, e.g. "Client outreach". Purely a label: it is
     * never required, never prompted for, and never gates the blocker.
     */
    val note: String = "",

    // ---------------- Time condition ----------------
    val hasTimeCondition: Boolean = false,
    /** Minutes from midnight. */
    val startMinute: Int = 0,
    /** Minutes from midnight. May be <= [startMinute] to cross midnight. */
    val endMinute: Int = 0,
    /** Comma-separated, 1 = Monday .. 7 = Sunday. */
    val daysOfWeek: String = "1,2,3,4,5,6,7",

    // ---------------- Usage condition ----------------
    val hasUsageCondition: Boolean = false,
    /** Combined budget across all of this rule's apps, in minutes. */
    val usageLimitMinutes: Int = 0,
    val usageWindow: UsageWindow = UsageWindow.DAILY,

    // ---------------- Launch condition ----------------

    /**
     * A cap on how many times the rule's apps may be OPENED, rather than on
     * how long they are used.
     *
     * These measure different pathologies and a time budget does not catch the
     * one this app exists for. Thirty-four pickups against two hours of screen
     * time is not a binge -- it is a four-minute check, repeated all day, and
     * a sixty-minute budget never trips on it. Capping opens hits the reach
     * itself, which is the behaviour worth interrupting.
     */
    val hasLaunchCondition: Boolean = false,
    /** Combined opens across all of this rule's apps before it starts blocking. */
    val launchLimit: Int = 0,
    val launchWindow: UsageWindow = UsageWindow.DAILY,

    // ---------------- Manual session state ----------------
    val isManualActive: Boolean = false,
    /** Epoch millis when a manual session ends; null = until turned off. */
    val activeUntil: Long? = null,

    // ---------------- Commitment ----------------
    val commitment: CommitmentLevel = CommitmentLevel.OFF,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {

    fun packageList(): List<String> =
        packages.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun covers(packageName: String): Boolean =
        packageList().contains(packageName)

    fun dayList(): List<Int> =
        daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }

    /**
     * Whether the time condition matches [calendar].
     *
     * Correctly handles a window crossing midnight, including the day-boundary
     * case the old SQL got wrong: a Mon-Fri 22:00-06:00 rule must keep blocking
     * at 01:00 on Saturday, because that period belongs to FRIDAY's window.
     */
    fun timeConditionMatches(calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!hasTimeCondition) return true

        val days = dayList()
        if (days.isEmpty()) return false

        val nowMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val today = isoDayOfWeek(calendar)

        return if (startMinute < endMinute) {
            // Ordinary same-day window, e.g. 20:45 -> 22:30
            today in days && nowMinute >= startMinute && nowMinute < endMinute
        } else {
            // Window crosses midnight, e.g. 22:00 -> 06:00.
            // Either we are in the evening part of TODAY's window...
            val eveningPart = today in days && nowMinute >= startMinute
            // ...or in the morning part of YESTERDAY's window.
            val yesterday = if (today == 1) 7 else today - 1
            val morningPart = yesterday in days && nowMinute < endMinute
            eveningPart || morningPart
        }
    }

    /** Whether a manual rule is currently running. */
    fun manualIsRunning(now: Long = System.currentTimeMillis()): Boolean {
        if (kind != RuleKind.MANUAL) return false
        if (!isManualActive) return false
        val end = activeUntil ?: return true
        return now < end
    }

    /**
     * When this rule stops blocking, or null if it has no definite end
     * (indefinite manual session, or a usage budget that resets at midnight
     * but whose end the caller computes separately).
     */
    fun endsAt(now: Long = System.currentTimeMillis()): Long? {
        if (kind == RuleKind.MANUAL) return activeUntil
        if (!hasTimeCondition) return null

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val minutesUntilEnd = if (endMinute > nowMinute) {
            endMinute - nowMinute
        } else {
            (24 * 60) - nowMinute + endMinute
        }
        return now + minutesUntilEnd * 60_000L
    }

    companion object {
        fun isoDayOfWeek(calendar: Calendar): Int = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }

        /** Apps that are never suggested and never blocked by a generated rule. */
        val NEVER_BLOCK = setOf(
            "com.focusblock.app"
        )
    }
}

/**
 * Accumulated usage for one rule in one time bucket.
 *
 * bucket is "yyyy-MM-dd" for a DAILY window and "yyyy-MM-dd'T'HH" for HOURLY.
 * Storing usage per RULE rather than per app is what makes a combined budget
 * ("60 minutes across Instagram + Reddit + YouTube") work correctly.
 */
@Entity(tableName = "rule_usage", primaryKeys = ["ruleId", "bucket"])
data class RuleUsage(
    val ruleId: Long,
    val bucket: String,
    val usageSeconds: Long = 0,
    val launchCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * A deliberate, logged early exit from one rule.
 *
 * Scoped to a single rule by design: ending one routine must never disable
 * another. The old Hard Mode PIN screen disabled Strict Mode AND killed every
 * Quick Block session in one handler; that is what this prevents.
 */
@Entity(tableName = "rule_overrides")
data class RuleOverride(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ruleId: Long,
    /** yyyy-MM-dd, for counting overrides per day. */
    val date: String,
    val expiresAt: Long,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * The single commitment layer, replacing Strict Mode and both Hard Modes.
 *
 * Modelled on AppBlock: this is a lock on your CONFIGURATION, not a separate
 * blocking source. While it holds you cannot weaken your rules — you cannot
 * delete them, remove apps from them, shorten them or disable protection.
 * You can always make them stronger, and you can always reach allowlisted apps.
 */
@Entity(tableName = "protection_lock")
data class ProtectionLock(
    @PrimaryKey
    val id: Int = 1,

    val level: CommitmentLevel = CommitmentLevel.OFF,

    /** Epoch millis; configuration is frozen until this moment. */
    val lockedUntil: Long = 0L,

    /** Minutes you must wait after requesting an unlock. AppBlock uses 2-10. */
    val cooldownMinutes: Int = 5,

    /** When the user asked to unlock; 0 = no request pending. */
    val unlockRequestedAt: Long = 0L,

    /** Salted hash; never the PIN itself. */
    val pinHash: String = "",
    val pinSalt: String = "",

    /** Emergency unlock budget, reset daily. */
    val emergencyDate: String = "",
    val emergencyUsedToday: Int = 0,
    val maxEmergencyPerDay: Int = 1,
    val emergencyDurationMinutes: Int = 15,

    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isLocked(now: Long = System.currentTimeMillis()): Boolean =
        level != CommitmentLevel.OFF && lockedUntil > now

    fun cooldownRemainingMillis(now: Long = System.currentTimeMillis()): Long {
        if (unlockRequestedAt == 0L) return cooldownMinutes * 60_000L
        val elapsed = now - unlockRequestedAt
        return (cooldownMinutes * 60_000L - elapsed).coerceAtLeast(0L)
    }
}
