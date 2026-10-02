package com.focusblock.app.policy

import java.time.ZoneId

/**
 * Final policy priority (spec 8.2), highest first:
 *
 *  1. Safety exemptions and essential apps — never blocked (handled before any reason is built).
 *  2. A valid emergency override for this app (time-boxed) — opens the app whatever the strength.
 *  3. Strict Lock reasons: Strict block, Strict routine, then any other reason that is Strict
 *     (a Strict bedtime, or a daily limit under a legacy Hard Mode lock).
 *  4. Bedtime (allow-only mode).
 *  5. Scheduled routine (including imported v13–15 rules).
 *  6. App limit.
 *  7. Daily limit.
 *  8. Focus Cycles break (separate guardrail until the merge decision; placed below limits).
 *  9. Normal immediate block.
 * 10. Not blocked.
 *
 * An "Open anyway" override (step 2 for Normal reasons only) never unlocks an app that any Strict
 * reason also covers. The highest-ranked reason decides the intervention copy and bypasses; the rest
 * are shown as "Also blocked by…".
 */
object PolicyPriority {
    fun rank(r: BlockReason): Int = if (r.strength == Strength.STRICT) {
        when (r.type) {
            ReasonType.SESSION -> 0
            ReasonType.ROUTINE -> 1
            ReasonType.BEDTIME -> 2
            ReasonType.APP_LIMIT -> 3
            ReasonType.DAILY_LIMIT -> 4
            ReasonType.FOCUS_CYCLE -> 5
        }
    } else {
        10 + when (r.type) {
            ReasonType.BEDTIME -> 0
            ReasonType.ROUTINE -> 1
            ReasonType.APP_LIMIT -> 2
            ReasonType.DAILY_LIMIT -> 3
            ReasonType.FOCUS_CYCLE -> 4
            ReasonType.SESSION -> 5
        }
    }

    /** Ties break towards the reason that lasts longer (open-ended first), then the lower id. */
    val comparator: Comparator<BlockReason> = compareBy<BlockReason> { rank(it) }
        .thenByDescending { it.until ?: Long.MAX_VALUE }
        .thenBy { it.ruleId }
}

object BlockPolicyEngine {

    /** Every reason that blocks [pkg] right now, highest priority first. Ignores overrides. */
    fun reasons(pkg: String, snap: PolicySnapshot, now: Long, zone: ZoneId): List<BlockReason> {
        if (pkg in snap.exempt) return emptyList()
        val out = ArrayList<BlockReason>(4)

        snap.session?.let { s ->
            if (pkg in s.packages) {
                val st = SessionClock.state(s, now)
                if (st.blocking) {
                    val until = if (s.type == SessionType.INTERVALS) st.phaseEndsAt else s.plannedEndAt
                    out += BlockReason(ReasonType.SESSION, s.id, "", s.strength, until)
                }
            }
        }

        for (r in snap.routines) {
            if (!r.enabled || !r.conditionsMet || pkg !in r.packages) continue
            val until: Long? = if (r.window == null) {
                if (r.activeUntil != null && now >= r.activeUntil) continue
                r.activeUntil
            } else {
                val occurrence = r.window.occurrenceAt(now, zone) ?: continue
                occurrence.end
            }
            out += BlockReason(ReasonType.ROUTINE, r.id, r.name, r.strength, until)
        }

        snap.bedtime?.let { b ->
            if (b.enabled) {
                b.window.occurrenceAt(now, zone)?.let { o ->
                    out += BlockReason(ReasonType.BEDTIME, 1, "", b.strength, o.end)
                }
            }
        }

        val midnight by lazy { PolicyTime.nextMidnight(now, zone) }
        for (l in snap.appLimits) {
            if (l.enabled && pkg in l.packages && l.usedMillisToday >= l.limitMinutes * SessionClock.MINUTE) {
                out += BlockReason(ReasonType.APP_LIMIT, l.id, l.name, Strength.NORMAL, midnight)
            }
        }

        snap.dailyLimit?.let { d ->
            val counted = d.countedPackages?.contains(pkg) ?: true
            if (d.enabled && counted && d.usedMillisToday >= d.limitMinutes * SessionClock.MINUTE) {
                val strength = if (d.lockedUntil > now) Strength.STRICT else Strength.NORMAL
                out += BlockReason(ReasonType.DAILY_LIMIT, 1, "", strength, midnight)
            }
        }

        for (c in snap.focusCycles) {
            val ends = c.breakEndsAt ?: continue
            if (c.enabled && pkg in c.packages && now < ends) {
                out += BlockReason(ReasonType.FOCUS_CYCLE, c.id, c.name, Strength.NORMAL, ends)
            }
        }

        out.sortWith(PolicyPriority.comparator)
        return out
    }

    fun evaluate(pkg: String, snap: PolicySnapshot, now: Long, zone: ZoneId): BlockDecision {
        if (pkg in snap.exempt) return BlockDecision.open(pkg, exempt = true)
        val reasons = reasons(pkg, snap, now, zone)
        if (reasons.isEmpty()) return BlockDecision.open(pkg)

        val strict = reasons.any { it.strength == Strength.STRICT }
        val bypass = AllowedBypass(openAnyway = !strict, emergency = true)
        val active = snap.overrides.filter { it.packageName == pkg && it.expiresAt > now }
        val override = active.firstOrNull { it.kind == OverrideKind.EMERGENCY }
            ?: active.firstOrNull { it.kind == OverrideKind.OPEN_ANYWAY && !strict }

        return BlockDecision(
            packageName = pkg,
            blocked = override == null,
            primary = reasons.first(),
            others = reasons.drop(1),
            strength = if (strict) Strength.STRICT else Strength.NORMAL,
            bypass = bypass,
            activeOverride = override,
            exempt = false,
        )
    }

    /**
     * Earliest moment after [now] at which any decision could change without new usage: session
     * phase/end, routine and bedtime boundaries, override and break expiry, legacy lock expiry and
     * midnight (limit reset). Used for the single next-change alarm and the service's re-check.
     */
    fun nextChangeAt(snap: PolicySnapshot, now: Long, zone: ZoneId): Long? {
        val candidates = ArrayList<Long>()
        snap.session?.let { s -> SessionClock.nextBoundary(s, now)?.let(candidates::add) }
        for (r in snap.routines) {
            if (!r.enabled) continue
            if (r.window != null) r.window.nextBoundaryAfter(now, zone)?.let(candidates::add)
            else r.activeUntil?.let(candidates::add)
        }
        snap.bedtime?.takeIf { it.enabled }?.window?.nextBoundaryAfter(now, zone)?.let(candidates::add)
        snap.overrides.forEach { candidates += it.expiresAt }
        snap.focusCycles.forEach { c -> c.breakEndsAt?.let(candidates::add) }
        snap.dailyLimit?.let { d -> if (d.lockedUntil > now) candidates += d.lockedUntil }
        if (snap.appLimits.any { it.enabled } || snap.dailyLimit?.enabled == true) {
            candidates += PolicyTime.nextMidnight(now, zone)
        }
        return candidates.filter { it > now }.minOrNull()
    }

    /**
     * If [pkg] stays in the foreground, the moment an app or daily limit would be reached. The
     * service schedules a re-check at this time instead of polling.
     */
    fun limitReachedAt(pkg: String, snap: PolicySnapshot, now: Long): Long? {
        if (pkg in snap.exempt) return null
        val times = ArrayList<Long>()
        for (l in snap.appLimits) {
            if (!l.enabled || pkg !in l.packages) continue
            val left = l.limitMinutes * SessionClock.MINUTE - l.usedMillisToday
            if (left > 0) times += now + left
        }
        snap.dailyLimit?.let { d ->
            if (d.enabled && (d.countedPackages?.contains(pkg) != false)) {
                val left = d.limitMinutes * SessionClock.MINUTE - d.usedMillisToday
                if (left > 0) times += now + left
            }
        }
        return times.minOrNull()
    }

    /** Reasons active right now for any package: used for the status line and coverage. */
    fun activeRuleReasons(snap: PolicySnapshot, now: Long, zone: ZoneId): List<BlockReason> {
        val out = ArrayList<BlockReason>()
        for (r in snap.routines) {
            if (!r.enabled || !r.conditionsMet) continue
            if (r.window == null) {
                if (r.activeUntil == null || now < r.activeUntil) out += BlockReason(ReasonType.ROUTINE, r.id, r.name, r.strength, r.activeUntil)
            } else {
                r.window.occurrenceAt(now, zone)?.let { out += BlockReason(ReasonType.ROUTINE, r.id, r.name, r.strength, it.end) }
            }
        }
        snap.bedtime?.takeIf { it.enabled }?.window?.occurrenceAt(now, zone)?.let {
            out += BlockReason(ReasonType.BEDTIME, 1, "", snap.bedtime.strength, it.end)
        }
        out.sortWith(PolicyPriority.comparator)
        return out
    }
}
