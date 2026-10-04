package com.focusblock.app.core

import androidx.room.withTransaction
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.BlockSessionEntity
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.policy.SessionInput
import com.focusblock.app.policy.SessionOutcome
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The setup a user picked on the Block tab; also powers "Repeat last block" and the widget. */
data class BlockSetup(
    val packages: List<String>,
    val type: SessionType = SessionType.TIMED,
    val minutes: Int = 45,
    val focusMinutes: Int = 25,
    val breakMinutes: Int = 5,
    val rounds: Int = 4,
    val strength: Strength = Strength.NORMAL,
) {
    fun toJson(): String = JSONObject()
        .put("apps", packages.joinToString(","))
        .put("type", type.name)
        .put("minutes", minutes)
        .put("focus", focusMinutes)
        .put("rest", breakMinutes)
        .put("rounds", rounds)
        .put("strength", strength.name)
        // Recovery-branch keys, so an older build reading this value still understands it.
        .put("mode", when (type) { SessionType.TIMED -> "timed"; SessionType.INTERVALS -> "cycles"; SessionType.INDEFINITE -> "manual" })
        .put("strict", strength == Strength.STRICT)
        .toString()

    companion object {
        /** Reads both the v17 format and the recovery branch's {apps, mode, minutes, rest, rounds, strict}. */
        fun parse(json: String?): BlockSetup? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val j = JSONObject(json)
                val apps = csv(j.optString("apps"))
                val type = j.optString("type").let { t -> SessionType.values().firstOrNull { it.name == t } }
                    ?: when (j.optString("mode")) { "cycles" -> SessionType.INTERVALS; "manual" -> SessionType.INDEFINITE; else -> SessionType.TIMED }
                val strength = j.optString("strength").let { s -> Strength.values().firstOrNull { it.name == s } }
                    ?: if (j.optBoolean("strict")) Strength.STRICT else Strength.NORMAL
                val minutes = j.optInt("minutes", 45)
                // The recovery branch stored the focus length in "minutes" for cycles.
                val focus = if (j.has("focus")) j.optInt("focus", 25) else if (type == SessionType.INTERVALS) minutes else 25
                BlockSetup(apps, type, minutes, focus, j.optInt("rest", 5), j.optInt("rounds", 4), strength)
            }.getOrNull()?.takeIf { it.packages.isNotEmpty() }
        }
    }
}

data class StartRequest(
    val setup: BlockSetup,
    val intention: String? = null,
    /** The 1-minute test from first-run setup: it does not become "Repeat last block" or the saved selection. */
    val test: Boolean = false,
)

sealed class StartResult {
    data class Started(val id: Long) : StartResult()
    data class Rejected(val reason: Reject) : StartResult()
}

enum class Reject { NO_APPS, ALREADY_RUNNING, STRICT_NEEDS_END, INVALID_LENGTH }

enum class EndResult { ENDED, STRICT_LOCKED, NOTHING_RUNNING }

enum class EmergencyStopResult { STOPPED, USED_TODAY, NOT_STRICT, NOTHING_RUNNING }

class SessionManager(
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
    private val exempt: suspend () -> Set<String>,
    private val onChanged: () -> Unit,
) {
    private val dao get() = db.blockSessionDao()

    fun activeFlow(): Flow<BlockSessionEntity?> = dao.activeFlow()
    fun awaitingOutcomeFlow(): Flow<BlockSessionEntity?> = dao.awaitingOutcomeFlow()
    fun lastSetupFlow(): Flow<BlockSetup?> = db.settingsDao().getValueFlow(PrefKeys.LAST_SETUP).map { BlockSetup.parse(it) }

    suspend fun active(): BlockSessionEntity? = dao.active()
    suspend fun lastSetup(): BlockSetup? = BlockSetup.parse(db.settingsDao().getValue(PrefKeys.LAST_SETUP))

    /** Elapsed-realtime clock for sessions started in this boot; null when it cannot be trusted. */
    fun trustedNow(e: BlockSessionEntity): Long? {
        val boot = clock.bootCount()
        if (e.bootCount < 0 || boot < 0 || e.bootCount != boot || e.startElapsedRealtime <= 0) return null
        return e.startedAt + (clock.elapsed() - e.startElapsedRealtime)
    }

    fun toInput(e: BlockSessionEntity): SessionInput = SessionInput(
        id = e.id,
        packages = csv(e.packages).toSet(),
        type = SessionType.values().firstOrNull { it.name == e.sessionType } ?: SessionType.TIMED,
        strength = Strength.values().firstOrNull { it.name == e.strength } ?: Strength.NORMAL,
        startedAt = e.startedAt,
        plannedEndAt = e.plannedEndAt,
        focusMinutes = e.focusMinutes,
        breakMinutes = e.breakMinutes,
        rounds = e.rounds,
        intention = e.intention,
        trustedNow = trustedNow(e),
    )

    suspend fun start(request: StartRequest): StartResult = withContext(Dispatchers.IO) {
        val s = request.setup
        val blocked = exempt()
        val packages = s.packages.map { it.trim() }.filter { it.isNotEmpty() && it !in blocked }.distinct()
        if (packages.isEmpty()) return@withContext StartResult.Rejected(Reject.NO_APPS)
        if (s.strength == Strength.STRICT && s.type == SessionType.INDEFINITE) return@withContext StartResult.Rejected(Reject.STRICT_NEEDS_END)
        val valid = when (s.type) {
            SessionType.TIMED -> s.minutes in 1..720
            SessionType.INTERVALS -> s.focusMinutes in 1..180 && s.breakMinutes in 1..60 && s.rounds in 2..12
            SessionType.INDEFINITE -> true
        }
        if (!valid) return@withContext StartResult.Rejected(Reject.INVALID_LENGTH)
        val length = SessionClock.plannedLengthMinutes(s.type, s.minutes, s.focusMinutes, s.breakMinutes, s.rounds)
        val intention = request.intention?.trim()?.take(MAX_INTENTION)?.takeIf { it.isNotEmpty() }

        val result = db.withTransaction {
            reconcileLocked()
            if (dao.allActive().isNotEmpty()) return@withTransaction StartResult.Rejected(Reject.ALREADY_RUNNING)
            val now = clock.now()
            val id = dao.insert(
                BlockSessionEntity(
                    packages = packages.joinToString(","),
                    sessionType = s.type.name,
                    strength = s.strength.name,
                    intention = intention,
                    startedAt = now,
                    plannedEndAt = length?.let { now + it * SessionClock.MINUTE },
                    focusMinutes = if (s.type == SessionType.INTERVALS) s.focusMinutes else 0,
                    breakMinutes = if (s.type == SessionType.INTERVALS) s.breakMinutes else 0,
                    rounds = if (s.type == SessionType.INTERVALS) s.rounds else 1,
                    startElapsedRealtime = clock.elapsed(),
                    bootCount = clock.bootCount(),
                ),
            )
            // Starting a new block means an earlier "Did you finish?" went unanswered.
            dao.markOlderUnanswered(keepId = -1, now = now)
            if (!request.test) {
                db.settingsDao().insert(AppSettings(PrefKeys.LAST_SETUP, s.copy(packages = packages).toJson()))
                db.settingsDao().insert(AppSettings(PrefKeys.SAVED_APPS, packages.joinToString(",")))
            }
            StartResult.Started(id)
        }
        if (result is StartResult.Started) onChanged()
        result
    }

    suspend fun repeatLast(): StartResult {
        val last = lastSetup() ?: return StartResult.Rejected(Reject.NO_APPS)
        return start(StartRequest(last))
    }

    /** "End block". Strict Lock blocks cannot be ended early, from any entry point. */
    suspend fun end(): EndResult = withContext(Dispatchers.IO) {
        val result = db.withTransaction {
            val e = dao.active() ?: return@withTransaction EndResult.NOTHING_RUNNING
            val input = toInput(e)
            val state = SessionClock.state(input, clock.now())
            if (state.ended) {
                finishCompleted(e)
                return@withTransaction EndResult.ENDED
            }
            if (input.strength == Strength.STRICT) return@withTransaction EndResult.STRICT_LOCKED
            // A quick block has no end time: stopping it is how it completes, so it is not "ended early".
            if (input.type == SessionType.INDEFINITE) {
                finishCompleted(e)
                return@withTransaction EndResult.ENDED
            }
            val now = clock.now()
            dao.update(e.copy(isActive = false, endedAt = now, endReason = END_EARLY, outcome = SessionOutcome.ENDED_EARLY.name, outcomeAt = now))
            EndResult.ENDED
        }
        if (result == EndResult.ENDED) onChanged()
        result
    }

    /** "Make Strict": the running Normal block (quick or timed) becomes Strict until it ends. Cannot be undone. */
    suspend fun makeStrict(): Boolean = withContext(Dispatchers.IO) {
        val ok = db.withTransaction {
            val e = dao.active() ?: return@withTransaction false
            if (e.strength == Strength.STRICT.name || SessionClock.state(toInput(e), clock.now()).ended) return@withTransaction false
            dao.update(e.copy(strength = Strength.STRICT.name))
            true
        }
        if (ok) onChanged()
        ok
    }

    private fun today(): String = PolicyTime.localDate(clock.now(), clock.zone()).toString()

    /** True when today's Strict emergency stop has already been used. */
    suspend fun emergencyStopUsedToday(): Boolean = withContext(Dispatchers.IO) {
        db.settingsDao().getValue(PrefKeys.STRICT_EMERGENCY_STOP_DATE) == today()
    }

    /**
     * Strict emergency stop: once a day, a Strict block can be ended at once. Offered only on the
     * Block tab (never on the block screen of a blocked app). Recorded as ended early.
     */
    suspend fun emergencyStop(): EmergencyStopResult = withContext(Dispatchers.IO) {
        val result = db.withTransaction {
            val e = dao.active() ?: return@withTransaction EmergencyStopResult.NOTHING_RUNNING
            if (SessionClock.state(toInput(e), clock.now()).ended) {
                finishCompleted(e)
                return@withTransaction EmergencyStopResult.STOPPED
            }
            if (e.strength != Strength.STRICT.name) return@withTransaction EmergencyStopResult.NOT_STRICT
            val today = today()
            if (db.settingsDao().getValue(PrefKeys.STRICT_EMERGENCY_STOP_DATE) == today) return@withTransaction EmergencyStopResult.USED_TODAY
            val now = clock.now()
            dao.update(e.copy(isActive = false, endedAt = now, endReason = END_EARLY, outcome = SessionOutcome.ENDED_EARLY.name, outcomeAt = now))
            db.settingsDao().insert(AppSettings(PrefKeys.STRICT_EMERGENCY_STOP_DATE, today))
            EmergencyStopResult.STOPPED
        }
        if (result == EmergencyStopResult.STOPPED) onChanged()
        result
    }

    /**
     * Adds apps to the running block. Allowed in both strengths, because it only makes the block
     * stricter; apps already in it, essential and safety apps are skipped. Returns how many were added.
     */
    suspend fun addApps(packages: List<String>): Int = withContext(Dispatchers.IO) {
        val skip = exempt()
        val added = db.withTransaction {
            val e = dao.active() ?: return@withTransaction 0
            if (SessionClock.state(toInput(e), clock.now()).ended) return@withTransaction 0
            val current = csv(e.packages)
            val extra = packages.map { it.trim() }.filter { it.isNotEmpty() && it !in skip && it !in current }.distinct()
            if (extra.isEmpty()) return@withTransaction 0
            dao.update(e.copy(packages = (current + extra).joinToString(",")))
            extra.size
        }
        if (added > 0) onChanged()
        added
    }

    /**
     * Ends the first-run test block wherever it is (running or already over) without asking "Did you
     * finish?" and without counting it in Activity.
     */
    suspend fun discardTest(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val e = dao.get(id) ?: return@withTransaction
            dao.update(e.copy(isActive = false, endedAt = e.endedAt ?: clock.now(), endReason = TEST))
        }
        onChanged()
    }

    /** "Add 15 minutes". Allowed in both strengths; impossible for Until I stop. */
    suspend fun extend(minutes: Int = SessionClock.EXTENSION_MINUTES): Boolean = withContext(Dispatchers.IO) {
        val ok = db.withTransaction {
            val e = dao.active() ?: return@withTransaction false
            val newEnd = SessionClock.extendedEnd(toInput(e), clock.now(), minutes) ?: return@withTransaction false
            // Keep the trusted clock consistent: extend relative to the same start.
            dao.update(e.copy(plannedEndAt = newEnd, extendedMinutes = e.extendedMinutes + minutes))
            true
        }
        if (ok) onChanged()
        ok
    }

    /**
     * Marks blocks whose time is up as completed. Called from the alarm, both services, app start
     * and recovery receivers. Returns the block that just completed, if any.
     */
    suspend fun reconcile(): BlockSessionEntity? = withContext(Dispatchers.IO) {
        val ended = db.withTransaction { reconcileLocked() }
        if (ended != null) onChanged()
        ended
    }

    private suspend fun reconcileLocked(): BlockSessionEntity? {
        val active = dao.allActive().sortedByDescending { it.startedAt }
        var completed: BlockSessionEntity? = null
        active.drop(1).forEach { dao.update(it.copy(isActive = false, endedAt = clock.now(), endReason = END_EARLY, outcome = SessionOutcome.ENDED_EARLY.name)) }
        val newest = active.firstOrNull() ?: return null
        if (SessionClock.state(toInput(newest), clock.now()).ended) completed = finishCompleted(newest)
        return completed
    }

    private suspend fun finishCompleted(e: BlockSessionEntity): BlockSessionEntity {
        val done = e.copy(isActive = false, endedAt = e.plannedEndAt ?: clock.now(), endReason = COMPLETED)
        dao.update(done)
        dao.markOlderUnanswered(keepId = e.id, now = clock.now())
        return done
    }

    /** Session end sheet: Finished / Not yet / dismissed (UNANSWERED). */
    suspend fun recordOutcome(id: Long, outcome: SessionOutcome) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext
        if (e.outcome != null && outcome == SessionOutcome.UNANSWERED) return@withContext
        dao.update(e.copy(outcome = outcome.name, outcomeAt = clock.now()))
        onChanged()
    }

    /** End sheet "Add 15 minutes": records EXTENDED and starts a 15-minute block with the same apps, strength and intention. */
    suspend fun extendFinished(id: Long): StartResult = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext StartResult.Rejected(Reject.NO_APPS)
        dao.update(e.copy(outcome = SessionOutcome.EXTENDED.name, outcomeAt = clock.now()))
        val setup = BlockSetup(csv(e.packages), SessionType.TIMED, SessionClock.EXTENSION_MINUTES,
            strength = Strength.values().firstOrNull { it.name == e.strength } ?: Strength.NORMAL)
        val keep = lastSetup()
        val result = start(StartRequest(setup, e.intention))
        // Keep the user's real last setup for "Repeat last block", not the 15-minute extension.
        if (result is StartResult.Started && keep != null) db.settingsDao().insert(AppSettings(PrefKeys.LAST_SETUP, keep.toJson()))
        result
    }

    /**
     * Saves the picker selection without starting anything. Never written into a permanent blocklist,
     * nor into "Repeat last block", which only a started block sets.
     */
    suspend fun saveSelection(packages: List<String>) {
        db.settingsDao().insert(AppSettings(PrefKeys.SAVED_APPS, packages.joinToString(",")))
    }

    suspend fun savedSelection(): List<String> = csv(db.settingsDao().getValue(PrefKeys.SAVED_APPS))

    companion object {
        const val COMPLETED = "COMPLETED"
        const val END_EARLY = "ENDED_EARLY"
        const val TEST = "TEST"
        const val MAX_INTENTION = 80
    }
}

fun csv(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
