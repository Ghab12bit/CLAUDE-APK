package com.focusblock.app.policy

/**
 * Pure policy model shared by both enforcement services, the UI, the widget and notifications.
 * Nothing in this package touches Android, so every rule can be unit-tested on the JVM.
 */

/** The two strengths users see (spec 2.4). Legacy Strict Mode and both Hard Mode models map onto these. */
enum class Strength { NORMAL, STRICT }

/** Every source of blocking. Order of declaration is not priority; see [PolicyPriority]. */
enum class ReasonType { SESSION, ROUTINE, BEDTIME, APP_LIMIT, DAILY_LIMIT, FOCUS_CYCLE }

/** Session types on the Block tab (spec 2.3). */
enum class SessionType { TIMED, INTERVALS, INDEFINITE }

/** Outcome recorded for every immediate session (spec 8.4). */
enum class SessionOutcome { FINISHED, NOT_FINISHED, EXTENDED, ENDED_EARLY, UNANSWERED }

/** Time-boxed, per-app overrides. Both are logged. */
enum class OverrideKind { OPEN_ANYWAY, EMERGENCY }

/** One reason an app is blocked right now. */
data class BlockReason(
    val type: ReasonType,
    val ruleId: Long,
    val name: String,
    val strength: Strength,
    /** When this reason stops blocking the app. Null means "until you end this block". */
    val until: Long?,
)

data class SessionInput(
    val id: Long,
    val packages: Set<String>,
    val type: SessionType,
    val strength: Strength,
    val startedAt: Long,
    /** Absolute deadline including extensions. Null only for [SessionType.INDEFINITE]. */
    val plannedEndAt: Long?,
    val focusMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val rounds: Int = 1,
    val intention: String? = null,
    /**
     * Clock reading that the session trusts more than the wall clock (derived from elapsed realtime
     * in the same boot). Prevents a manual clock change from ending a block early (spec 9.3).
     */
    val trustedNow: Long? = null,
)

/**
 * A scheduled routine, or an imported v13–15 rule. [window] null means "always" (an imported rule
 * whose only conditions are usage or launches). [conditionsMet] is precomputed by the snapshot
 * builder for imported usage/launch conditions; routines always pass true.
 */
data class RoutineInput(
    val id: Long,
    val name: String,
    val packages: Set<String>,
    val window: TimeWindow?,
    val strength: Strength,
    val enabled: Boolean = true,
    val conditionsMet: Boolean = true,
    /**
     * Only used when [window] is null (imported manual or condition-only rules): the rule blocks
     * until this time. Null means until it is stopped.
     */
    val activeUntil: Long? = null,
    val imported: Boolean = false,
)

/** Bedtime is an allow-only window: every app except exempt (safety + essential) apps is blocked. */
data class BedtimeInput(
    val enabled: Boolean,
    val window: TimeWindow,
    val strength: Strength = Strength.NORMAL,
)

/** Shared daily allowance for chosen apps (spec 4.6 "App limit"). */
data class AppLimitInput(
    val id: Long,
    val name: String,
    val packages: Set<String>,
    val limitMinutes: Int,
    val usedMillisToday: Long,
    val enabled: Boolean = true,
)

/**
 * Total daily allowance across counted apps (spec 4.6 "Daily limit"). [countedPackages] null means
 * every app that is not exempt. [lockedUntil] carries a legacy Hard Mode lock: while it is in the
 * future the limit behaves as Strict Lock.
 */
data class DailyLimitInput(
    val enabled: Boolean,
    val limitMinutes: Int,
    val countedPackages: Set<String>?,
    val usedMillisToday: Long,
    val lockedUntil: Long = 0,
)

/** Focus Cycles guardrail: after a usage window, the chosen apps are blocked until the break ends. */
data class FocusCycleInput(
    val id: Long,
    val name: String,
    val packages: Set<String>,
    val enabled: Boolean,
    val breakEndsAt: Long?,
)

data class OverrideInput(
    val packageName: String,
    val kind: OverrideKind,
    val expiresAt: Long,
)

data class PolicySnapshot(
    val session: SessionInput? = null,
    val routines: List<RoutineInput> = emptyList(),
    val bedtime: BedtimeInput? = null,
    val appLimits: List<AppLimitInput> = emptyList(),
    val dailyLimit: DailyLimitInput? = null,
    val focusCycles: List<FocusCycleInput> = emptyList(),
    val overrides: List<OverrideInput> = emptyList(),
    /** Safety exemptions plus essential apps. These are never blocked (spec 8.2 level 1). */
    val exempt: Set<String> = emptySet(),
)

/** Which bypasses the intervention may offer for a decision (spec 2.4, 2.5, 4.5). */
data class AllowedBypass(
    /** "Open anyway" is only available when every applicable reason is Normal. */
    val openAnyway: Boolean,
    /** Emergency access is available in both strengths. */
    val emergency: Boolean,
)

data class BlockDecision(
    val packageName: String,
    val blocked: Boolean,
    /** Highest-priority reason; decides copy and bypasses. Null when nothing applies. */
    val primary: BlockReason?,
    /** Lower-priority reasons, shown as "Also blocked by…". */
    val others: List<BlockReason>,
    /** Strongest strength across all reasons. */
    val strength: Strength,
    val bypass: AllowedBypass,
    /** The override currently letting the app open, if any. */
    val activeOverride: OverrideInput?,
    val exempt: Boolean,
) {
    val reasons: List<BlockReason> get() = listOfNotNull(primary) + others

    /** When the app becomes available on its own: the latest end among reasons, or null if any is open-ended. */
    val availableAt: Long?
        get() = if (reasons.isEmpty() || reasons.any { it.until == null }) null else reasons.maxOf { it.until!! }

    companion object {
        fun open(pkg: String, exempt: Boolean = false) = BlockDecision(
            pkg, false, null, emptyList(), Strength.NORMAL, AllowedBypass(false, false), null, exempt,
        )
    }
}
