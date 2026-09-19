package com.focusblock.app.blocking

import com.focusblock.app.database.dao.BlockRuleDao
import com.focusblock.app.database.dao.BlockedAppDao
import com.focusblock.app.database.dao.ProtectionLockDao
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.database.entity.ProtectionLock
import com.focusblock.app.database.entity.RuleKind
import com.focusblock.app.database.entity.UsageWindow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE single source of truth for "is this app blocked, and why?".
 *
 * Both [com.focusblock.app.service.FocusBlockAccessibilityService] and
 * [com.focusblock.app.service.AppBlockingService] call this and nothing else.
 * Previously each service carried its own divergent copy of the rules, so the
 * answer depended on which service happened to be alive. There is now one
 * answer.
 *
 * The engine NEVER un-blocks. It gathers every rule that currently covers an
 * app and returns all of them. A caller cannot ask it for permission to
 * ignore a rule.
 */
@Singleton
class BlockingEngine @Inject constructor(
    private val ruleDao: BlockRuleDao,
    private val blockedAppDao: BlockedAppDao,
    private val lockDao: ProtectionLockDao
) {

    /**
     * Why one particular rule is currently blocking an app. This is what the
     * block screen and the home screen show the user, so it carries everything
     * needed to explain the situation without a second lookup.
     */
    data class ActiveReason(
        val ruleId: Long,
        val ruleName: String,
        val trigger: Trigger,
        /** When this particular rule stops. Null = no definite end. */
        val endsAt: Long?,
        val commitment: CommitmentLevel,
        /** For usage rules: minutes used and the budget, for "45/60m used". */
        val usedMinutes: Int = 0,
        val limitMinutes: Int = 0
    )

    enum class Trigger {
        /** A manual block the user started. */
        MANUAL,

        /** Inside the rule's scheduled window. */
        SCHEDULE,

        /** The rule's daily budget is spent. */
        DAILY_BUDGET,

        /** The rule's hourly budget is spent. */
        HOURLY_BUDGET
    }

    /**
     * The full answer for one package.
     *
     * [reasons] is every rule currently blocking it — not just the first. That
     * is what makes overlap explainable: the user can see that ending their
     * evening block would still leave the daily budget in force.
     */
    data class Decision(
        val packageName: String,
        val isBlocked: Boolean,
        val reasons: List<ActiveReason>,
        val allowlisted: Boolean
    ) {
        /** The reason to lead with: the one that lasts longest / binds hardest. */
        val primary: ActiveReason?
            get() = reasons.maxWithOrNull(
                compareBy<ActiveReason> { it.commitment.ordinal }
                    .thenBy { it.endsAt ?: Long.MAX_VALUE }
            )

        /** Rules that would STILL block this app if [ruleId] ended right now. */
        fun survivorsWithout(ruleId: Long): List<ActiveReason> =
            reasons.filter { it.ruleId != ruleId }
    }

    // ------------------------------------------------------------------
    // Caching
    //
    // evaluate() runs on the blocking path: every time an app comes to the
    // foreground, before the user can see its content. A database round trip
    // per window change adds latency exactly where it is most visible, so the
    // rule set and allowlist are cached for a short interval.
    //
    // The TTL is short enough that an edit takes effect within a couple of
    // seconds, and [invalidate] makes it immediate when the app changes a rule
    // itself. Usage counters are never cached -- a spent budget must block on
    // the very next app open.
    // ------------------------------------------------------------------

    @Volatile private var cachedRules: List<BlockRule>? = null
    @Volatile private var cachedAllowlist: Set<String>? = null
    @Volatile private var cacheLoadedAt: Long = 0L

    /** Drop the cache so the next evaluation reflects a change immediately. */
    fun invalidate() {
        cachedRules = null
        cachedAllowlist = null
        cacheLoadedAt = 0L
    }

    private suspend fun enabledRules(now: Long): List<BlockRule> {
        refreshCacheIfStale(now)
        return cachedRules ?: ruleDao.getEnabledRulesSync().also { cachedRules = it }
    }

    private suspend fun allowlist(now: Long): Set<String> {
        refreshCacheIfStale(now)
        return cachedAllowlist ?: blockedAppDao.getAllowlistPackages().toSet()
            .also { cachedAllowlist = it }
    }

    private suspend fun refreshCacheIfStale(now: Long) {
        if (cachedRules != null && cachedAllowlist != null &&
            now - cacheLoadedAt < CACHE_TTL_MS
        ) return
        cachedRules = ruleDao.getEnabledRulesSync()
        cachedAllowlist = blockedAppDao.getAllowlistPackages().toSet()
        cacheLoadedAt = now
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Decide whether [packageName] may run right now.
     *
     * Order matters only for the allowlist, which short-circuits. Everything
     * else is gathered, never short-circuited, because the user is owed the
     * complete picture.
     */
    suspend fun evaluate(
        packageName: String,
        now: Long = System.currentTimeMillis()
    ): Decision {
        // INVARIANT 1: the allowlist wins over everything, including a
        // PIN-locked rule. The phone stays usable and the user stays reachable.
        if (allowlist(now).contains(packageName)) {
            return Decision(packageName, isBlocked = false, reasons = emptyList(), allowlisted = true)
        }

        // Never block ourselves; the user must always be able to reach settings.
        if (packageName in BlockRule.NEVER_BLOCK) {
            return Decision(packageName, isBlocked = false, reasons = emptyList(), allowlisted = true)
        }

        val reasons = mutableListOf<ActiveReason>()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }

        for (rule in enabledRules(now)) {
            if (!rule.covers(packageName)) continue

            // An active override temporarily suspends this ONE rule. It cannot
            // touch any other rule — that is the whole point of scoping it.
            if (ruleDao.hasActiveOverride(rule.id, now)) continue

            val reason = evaluateRule(rule, calendar, now) ?: continue
            reasons.add(reason)
        }

        return Decision(
            packageName = packageName,
            isBlocked = reasons.isNotEmpty(),
            reasons = reasons,
            allowlisted = false
        )
    }

    /**
     * Evaluate one rule against the current moment. Returns null when the rule
     * is not currently blocking.
     *
     * Conditions are ANDed, matching AppBlock: a rule with both a time window
     * and a usage budget blocks only inside the window AND once the budget is
     * spent.
     */
    private suspend fun evaluateRule(
        rule: BlockRule,
        calendar: Calendar,
        now: Long
    ): ActiveReason? {

        if (rule.kind == RuleKind.MANUAL) {
            if (!rule.manualIsRunning(now)) return null
            return ActiveReason(
                ruleId = rule.id,
                ruleName = rule.name,
                trigger = Trigger.MANUAL,
                endsAt = rule.activeUntil,
                commitment = rule.commitment
            )
        }

        // AUTOMATIC: every attached condition must hold.
        if (!rule.timeConditionMatches(calendar)) return null

        if (!rule.hasUsageCondition) {
            // Pure schedule.
            if (!rule.hasTimeCondition) return null // no conditions at all -> inert
            return ActiveReason(
                ruleId = rule.id,
                ruleName = rule.name,
                trigger = Trigger.SCHEDULE,
                endsAt = rule.endsAt(now),
                commitment = rule.commitment
            )
        }

        // Usage condition: is the budget spent?
        val bucket = bucketFor(rule.usageWindow, now)
        val usedSeconds = ruleDao.getUsageSeconds(rule.id, bucket) ?: 0L
        val usedMinutes = (usedSeconds / 60).toInt()
        if (usedMinutes < rule.usageLimitMinutes) return null

        return ActiveReason(
            ruleId = rule.id,
            ruleName = rule.name,
            trigger = if (rule.usageWindow == UsageWindow.DAILY)
                Trigger.DAILY_BUDGET else Trigger.HOURLY_BUDGET,
            endsAt = bucketEndsAt(rule.usageWindow, now),
            commitment = rule.commitment,
            usedMinutes = usedMinutes,
            limitMinutes = rule.usageLimitMinutes
        )
    }

    // ------------------------------------------------------------------
    // Usage accounting
    // ------------------------------------------------------------------

    /**
     * Credit [seconds] of foreground time for [packageName] to every rule that
     * covers it and carries a usage budget.
     *
     * Called by the foreground tracker. Both the daily and hourly buckets are
     * written so a rule can switch window without losing history.
     */
    suspend fun recordUsage(
        packageName: String,
        seconds: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (seconds <= 0) return
        if (allowlist(now).contains(packageName)) return

        for (rule in enabledRules(now)) {
            if (!rule.hasUsageCondition) continue
            if (!rule.covers(packageName)) continue
            ruleDao.addUsage(rule.id, bucketFor(UsageWindow.DAILY, now), seconds)
            ruleDao.addUsage(rule.id, bucketFor(UsageWindow.HOURLY, now), seconds)
        }
    }

    /** Credit one app launch, for rules that count launches rather than time. */
    suspend fun recordLaunch(packageName: String, now: Long = System.currentTimeMillis()) {
        if (allowlist(now).contains(packageName)) return
        for (rule in enabledRules(now)) {
            if (!rule.covers(packageName)) continue
            ruleDao.addLaunch(rule.id, bucketFor(UsageWindow.DAILY, now))
            ruleDao.addLaunch(rule.id, bucketFor(UsageWindow.HOURLY, now))
        }
    }

    // ------------------------------------------------------------------
    // Ending a rule early
    // ------------------------------------------------------------------

    sealed class EndAttempt {
        /** The rule ended. [stillBlocked] lists rules that continue to block. */
        data class Ended(val stillBlocked: List<String>) : EndAttempt()

        /** Blocked by commitment; [message] explains the exact terms. */
        data class Refused(val message: String, val retryAfterMillis: Long = 0L) : EndAttempt()

        /** Needs the PIN before it can proceed. */
        data class NeedsPin(val cooldownRemainingMillis: Long) : EndAttempt()
    }

    /**
     * Attempt to end ONE rule early.
     *
     * INVARIANT: this touches exactly one rule. Every other active rule keeps
     * running, and the result names them so the user is never surprised by an
     * app that is still blocked.
     */
    suspend fun requestEndRule(
        ruleId: Long,
        pin: String? = null,
        now: Long = System.currentTimeMillis()
    ): EndAttempt {
        val rule = ruleDao.getRule(ruleId) ?: return EndAttempt.Refused("That rule no longer exists.")

        when (rule.commitment) {
            CommitmentLevel.OFF -> Unit

            CommitmentLevel.LOCKED -> {
                val lock = lockDao.getLockSync() ?: ProtectionLock()
                val today = dayKey(now)
                val usedToday = if (lock.emergencyDate == today) lock.emergencyUsedToday else 0
                if (usedToday >= lock.maxEmergencyPerDay) {
                    return EndAttempt.Refused(
                        "\"${rule.name}\" is locked until ${formatTime(rule.endsAt(now))}. " +
                            "You've already used today's emergency unlock."
                    )
                }
                // Spend the emergency unlock and grant a bounded window.
                lockDao.upsert(
                    lock.copy(
                        emergencyDate = today,
                        emergencyUsedToday = usedToday + 1,
                        updatedAt = now
                    )
                )
                ruleDao.insertOverride(
                    com.focusblock.app.database.entity.RuleOverride(
                        ruleId = ruleId,
                        date = today,
                        expiresAt = now + lock.emergencyDurationMinutes * 60_000L,
                        reason = "emergency"
                    )
                )
                invalidate()
                return EndAttempt.Ended(stillBlockedNames(ruleId, rule, now))
            }

            CommitmentLevel.PIN_LOCKED -> {
                val lock = lockDao.getLockSync() ?: ProtectionLock()
                if (lock.unlockRequestedAt == 0L) {
                    lockDao.upsert(lock.copy(unlockRequestedAt = now, updatedAt = now))
                    return EndAttempt.NeedsPin(lock.cooldownMinutes * 60_000L)
                }
                val remaining = lock.cooldownRemainingMillis(now)
                if (remaining > 0) return EndAttempt.NeedsPin(remaining)
                if (pin == null || !PinHasher.verify(pin, lock.pinSalt, lock.pinHash)) {
                    return EndAttempt.Refused("That PIN doesn't match.")
                }
                lockDao.upsert(lock.copy(unlockRequestedAt = 0L, updatedAt = now))
            }
        }

        // Actually end this one rule.
        invalidate()
        if (rule.kind == RuleKind.MANUAL) {
            ruleDao.update(rule.copy(isManualActive = false, activeUntil = null, updatedAt = now))
        } else {
            ruleDao.insertOverride(
                com.focusblock.app.database.entity.RuleOverride(
                    ruleId = ruleId,
                    date = dayKey(now),
                    expiresAt = rule.endsAt(now) ?: (now + 60 * 60_000L),
                    reason = "ended early"
                )
            )
        }
        return EndAttempt.Ended(stillBlockedNames(ruleId, rule, now))
    }

    /**
     * The names of rules that keep blocking this rule's apps after it ends.
     * Shown to the user so "I stopped it but it's still blocked" never happens
     * without an explanation.
     */
    private suspend fun stillBlockedNames(
        endingRuleId: Long,
        ending: BlockRule,
        now: Long
    ): List<String> {
        val names = linkedSetOf<String>()
        for (pkg in ending.packageList()) {
            evaluate(pkg, now).survivorsWithout(endingRuleId).forEach { names.add(it.ruleName) }
        }
        return names.toList()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun bucketFor(window: UsageWindow, now: Long): String {
        val pattern = if (window == UsageWindow.DAILY) "yyyy-MM-dd" else "yyyy-MM-dd'T'HH"
        return SimpleDateFormat(pattern, Locale.US).format(Date(now))
    }

    private fun bucketEndsAt(window: UsageWindow, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MILLISECOND, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MINUTE, 0)
            if (window == UsageWindow.DAILY) {
                set(Calendar.HOUR_OF_DAY, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            } else {
                add(Calendar.HOUR_OF_DAY, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun dayKey(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    private fun formatTime(epoch: Long?): String {
        if (epoch == null) return "you turn it off"
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epoch))
    }

    companion object {
        private const val CACHE_TTL_MS = 2_000L
    }
}
