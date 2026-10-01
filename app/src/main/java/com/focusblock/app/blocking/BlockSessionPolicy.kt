package com.focusblock.app.blocking

/** Wall-clock policy: identical after process death and reboot; no UI timer owns enforcement. */
object BlockSessionPolicy {
    data class Phase(val blocking: Boolean, val remaining: Long, val round: Int)

    fun phase(start: Long, end: Long?, now: Long, focusMinutes: Int, breakMinutes: Int, rounds: Int): Phase {
        if (end != null && now >= end) return Phase(false, 0, rounds.coerceAtLeast(1))
        if (focusMinutes <= 0 || rounds <= 1) return Phase(true, end?.minus(now)?.coerceAtLeast(0) ?: 0, 1)
        val focus = focusMinutes * 60_000L
        val rest = breakMinutes.coerceAtLeast(1) * 60_000L
        val elapsed = (now - start).coerceAtLeast(0)
        val round = (elapsed / (focus + rest)).toInt() + 1
        // Added time after the final interval remains blocking, never creates an extra break.
        if (round >= rounds) return Phase(true, end?.minus(now)?.coerceAtLeast(0) ?: 0, rounds)
        val within = elapsed % (focus + rest)
        return Phase(within < focus, if (within < focus) focus - within else focus + rest - within, round)
    }

    fun exceptionEnd(now: Long, sessionEnd: Long?): Long = minOf(now + 120_000L, sessionEnd ?: Long.MAX_VALUE)
    fun exceptionApplies(requested: String, allowed: String, until: Long, now: Long): Boolean =
        requested == allowed && now < until
}
