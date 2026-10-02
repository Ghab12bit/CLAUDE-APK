package com.focusblock.app.policy

/**
 * Wall-clock session math. Nothing here counts down in memory: every answer is derived from the
 * persisted start and end timestamps, so it is identical after process death and reboot.
 */
object SessionClock {
    const val MINUTE = 60_000L
    const val EXTENSION_MINUTES = 15

    enum class Phase { BLOCKING, FOCUS, BREAK, ENDED }

    data class State(
        val phase: Phase,
        /** True while the session's apps are blocked by this session. */
        val blocking: Boolean,
        /** 1-based round for intervals; 1 otherwise. */
        val round: Int,
        val totalRounds: Int,
        /** When the current phase ends (session end for timed blocks). Null for open-ended blocks. */
        val phaseEndsAt: Long?,
        val sessionEndsAt: Long?,
    ) {
        val ended: Boolean get() = phase == Phase.ENDED
    }

    /** Total planned length for a new session, before any extension. Null for open-ended blocks. */
    fun plannedLengthMinutes(type: SessionType, minutes: Int, focus: Int, rest: Int, rounds: Int): Int? = when (type) {
        SessionType.TIMED -> minutes
        SessionType.INTERVALS -> focus * rounds + rest * (rounds - 1).coerceAtLeast(0)
        SessionType.INDEFINITE -> null
    }

    fun state(s: SessionInput, wallNow: Long): State {
        val now = s.trustedNow ?: wallNow
        val end = s.plannedEndAt
        if (s.type != SessionType.INDEFINITE && end != null && now >= end) {
            return State(Phase.ENDED, false, s.rounds.coerceAtLeast(1), s.rounds.coerceAtLeast(1), null, end)
        }
        if (s.type != SessionType.INTERVALS || s.focusMinutes <= 0 || s.rounds <= 1) {
            return State(Phase.BLOCKING, true, 1, 1, end, end)
        }
        val focus = s.focusMinutes * MINUTE
        val rest = s.breakMinutes.coerceAtLeast(1) * MINUTE
        val cycle = focus + rest
        val elapsed = (now - s.startedAt).coerceAtLeast(0)
        val round = (elapsed / cycle).toInt() + 1
        // The final round has no break; time added after it stays blocking.
        if (round >= s.rounds) return State(Phase.FOCUS, true, s.rounds, s.rounds, end, end)
        val within = elapsed % cycle
        val roundStart = s.startedAt + (round - 1) * cycle
        return if (within < focus) {
            State(Phase.FOCUS, true, round, s.rounds, roundStart + focus, end)
        } else {
            State(Phase.BREAK, false, round, s.rounds, roundStart + cycle, end)
        }
    }

    /** Remaining milliseconds until the session ends, measured with the trusted clock. */
    fun remaining(s: SessionInput, wallNow: Long): Long? {
        val end = s.plannedEndAt ?: return null
        return (end - (s.trustedNow ?: wallNow)).coerceAtLeast(0)
    }

    /** Next phase or end boundary after [wallNow], for scheduling. */
    fun nextBoundary(s: SessionInput, wallNow: Long): Long? {
        val st = state(s, wallNow)
        if (st.ended) return null
        val boundary = st.phaseEndsAt ?: return null
        // Translate a trusted-clock boundary back to wall time for alarms.
        val skew = (s.trustedNow ?: wallNow) - wallNow
        return boundary - skew
    }

    /** New deadline after "Add 15 minutes". Extending an open-ended block is not possible. */
    fun extendedEnd(s: SessionInput, wallNow: Long, minutes: Int = EXTENSION_MINUTES): Long? {
        val end = s.plannedEndAt ?: return null
        val now = s.trustedNow ?: wallNow
        return maxOf(end, now) + minutes * MINUTE
    }

    /**
     * Manual-clock tamper check: true when the wall clock disagrees with elapsed realtime by more
     * than [toleranceMs]. Callers log it as suspected tampering.
     */
    fun clockTampered(wallNow: Long, trustedNow: Long?, toleranceMs: Long = 2 * MINUTE): Boolean =
        trustedNow != null && kotlin.math.abs(wallNow - trustedNow) > toleranceMs
}
