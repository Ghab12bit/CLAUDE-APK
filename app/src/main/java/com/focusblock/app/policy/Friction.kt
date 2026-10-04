package com.focusblock.app.policy

import java.time.ZoneId

/**
 * Progressive friction on the intervention (spec 4.5) and emergency access rules (spec 2.4).
 * Durations are open decision 12.3 #10; the defaults below are used until the owner confirms.
 */
object FrictionPolicy {
    /** Off: "Open anyway" was removed from the product (see [offer]). */
    const val OPEN_ANYWAY_ENABLED = false
    const val OPEN_ANYWAY_MINUTES = 5
    const val EMERGENCY_WAIT_MINUTES = 10
    const val EMERGENCY_UNLOCK_MINUTES = 5
    const val SECOND_ATTEMPT_WAIT_SECONDS = 10
    const val THIRD_ATTEMPT_WAIT_SECONDS = 30

    enum class OpenAnyway { NONE, IMMEDIATE, AFTER_WAIT, AFTER_WAIT_AND_CONFIRM }

    data class Offer(
        val openAnyway: OpenAnyway,
        /** Seconds the "Open anyway" link stays disabled. */
        val waitSeconds: Int,
        val emergency: Boolean,
    )

    /** [attempt] is 1-based: this app's attempt count in the current block/window, including this one. */
    fun offer(decision: BlockDecision, attempt: Int): Offer {
        if (!decision.blocked) return Offer(OpenAnyway.NONE, 0, false)
        // "Open anyway" is no longer offered (user decision, round 4): ending the block or emergency
        // access are the only ways in. The levels below are kept only if it is ever turned back on.
        if (!OPEN_ANYWAY_ENABLED || !decision.bypass.openAnyway) return Offer(OpenAnyway.NONE, 0, decision.bypass.emergency)
        return when {
            attempt <= 1 -> Offer(OpenAnyway.IMMEDIATE, 0, decision.bypass.emergency)
            attempt == 2 -> Offer(OpenAnyway.AFTER_WAIT, SECOND_ATTEMPT_WAIT_SECONDS, decision.bypass.emergency)
            else -> Offer(OpenAnyway.AFTER_WAIT_AND_CONFIRM, THIRD_ATTEMPT_WAIT_SECONDS, decision.bypass.emergency)
        }
    }

    /**
     * Start of the period in which attempts are counted for [reason]: the block's start for
     * sessions, the window's start for routines and bedtime, midnight for limits and the break
     * start for Focus Cycles. "3rd try in this block" counts from here.
     */
    fun attemptScopeStart(reason: BlockReason, snap: PolicySnapshot, now: Long, zone: ZoneId): Long = when (reason.type) {
        ReasonType.SESSION -> snap.session?.startedAt ?: PolicyTime.startOfDay(now, zone)
        ReasonType.ROUTINE -> snap.routines.firstOrNull { it.id == reason.ruleId }?.window
            ?.occurrenceAt(now, zone)?.start ?: PolicyTime.startOfDay(now, zone)
        ReasonType.BEDTIME -> snap.bedtime?.window?.occurrenceAt(now, zone)?.start ?: PolicyTime.startOfDay(now, zone)
        ReasonType.FOCUS_CYCLE -> PolicyTime.startOfDay(now, zone)
        ReasonType.APP_LIMIT, ReasonType.DAILY_LIMIT -> PolicyTime.startOfDay(now, zone)
    }

    /** Emergency access: requested now, usable after the wait, then open for a short time. */
    data class EmergencyTimes(val readyAt: Long, val expiresAfterGrantMs: Long)

    fun emergency(now: Long): EmergencyTimes = EmergencyTimes(
        readyAt = now + EMERGENCY_WAIT_MINUTES * SessionClock.MINUTE,
        expiresAfterGrantMs = EMERGENCY_UNLOCK_MINUTES * SessionClock.MINUTE,
    )

    /**
     * A ready emergency request stays usable until midnight after it became ready (at least an
     * hour), so a request the user forgot about is still there when they come back.
     */
    fun emergencyUsableUntil(readyAt: Long, zone: ZoneId): Long {
        val midnight = java.time.Instant.ofEpochMilli(readyAt).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return maxOf(midnight, readyAt + 60 * SessionClock.MINUTE)
    }

    fun emergencyUsable(readyAt: Long, now: Long, zone: ZoneId): Boolean = now >= readyAt && now < emergencyUsableUntil(readyAt, zone)

    /** Reasons must be at least a few words, so emergency access stays deliberate. */
    fun emergencyReasonValid(reason: String): Boolean = reason.trim().length >= 3
}
