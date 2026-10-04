package com.focusblock.app.core

import androidx.room.withTransaction
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.entity.BlockedByType
import com.focusblock.app.database.entity.DiagnosticEvent
import com.focusblock.app.database.entity.FocusCycle
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.BlockDecision
import com.focusblock.app.policy.BlockPolicyEngine
import com.focusblock.app.policy.BlockReason
import com.focusblock.app.policy.FrictionPolicy
import com.focusblock.app.policy.OverrideKind
import com.focusblock.app.policy.PolicySnapshot
import com.focusblock.app.policy.ReasonType
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.policy.Strength
import com.focusblock.app.worker.EmergencyReadyWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Attempt actions recorded on block_logs.action. */
object AttemptAction {
    const val RETURNED = "RETURNED"
    const val OPEN_ANYWAY = "OPEN_ANYWAY"
    const val EMERGENCY = "EMERGENCY"
}

fun ReasonType.toBlockedBy(): BlockedByType = when (this) {
    ReasonType.SESSION -> BlockedByType.QUICK_BLOCK
    ReasonType.ROUTINE -> BlockedByType.SCHEDULE
    ReasonType.BEDTIME -> BlockedByType.BEDTIME
    ReasonType.APP_LIMIT -> BlockedByType.APP_TIMER
    ReasonType.DAILY_LIMIT -> BlockedByType.GLOBAL_LIMIT
    ReasonType.FOCUS_CYCLE -> BlockedByType.FOCUS_CYCLE
}

fun BlockedByType.toReasonType(): ReasonType? = when (this) {
    BlockedByType.QUICK_BLOCK, BlockedByType.STRICT_MODE, BlockedByType.HARD_MODE -> ReasonType.SESSION
    BlockedByType.SCHEDULE -> ReasonType.ROUTINE
    BlockedByType.BEDTIME -> ReasonType.BEDTIME
    BlockedByType.APP_TIMER -> ReasonType.APP_LIMIT
    BlockedByType.GLOBAL_LIMIT -> ReasonType.DAILY_LIMIT
    BlockedByType.FOCUS_CYCLE -> ReasonType.FOCUS_CYCLE
}

/**
 * Decides and logs blocked openings. Both enforcement services call [check]; neither has its own
 * policy. Every blocked opening is logged with its attempt number, reason and strength.
 */
class Enforcer(
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
    private val policy: PolicyRepository,
    private val apps: InstalledApps,
) {
    data class Blocked(val decision: BlockDecision, val attempt: Int, val logId: Long)

    private val mutex = Mutex()
    private var lastLogged: Triple<String, Long, Blocked>? = null

    suspend fun decide(pkg: String, freshUsage: Boolean = false): Pair<BlockDecision, PolicySnapshot> = withContext(Dispatchers.IO) {
        val snap = policy.snapshot(freshUsage)
        BlockPolicyEngine.evaluate(pkg, snap, clock.now(), clock.zone()) to snap
    }

    /** Returns a [Blocked] result (already logged) when [pkg] must not open, else null. */
    suspend fun check(pkg: String, freshUsage: Boolean = false): Blocked? = withContext(Dispatchers.IO) {
        val (decision, snap) = decide(pkg, freshUsage)
        if (!decision.blocked) return@withContext null
        val reason = decision.primary ?: return@withContext null
        mutex.withLock {
            val now = clock.now()
            // Rapid repeated window events for the same opening count once.
            lastLogged?.let { (p, t, previous) -> if (p == pkg && now - t < DEBOUNCE_MS) return@withContext previous.copy(decision = decision) }
            val scopeStart = FrictionPolicy.attemptScopeStart(reason, snap, now, clock.zone())
            val attempt = db.attemptDao().countSince(pkg, scopeStart) + 1
            val blocked = Blocked(decision, attempt, log(pkg, decision, reason, snap, attempt))
            lastLogged = Triple(pkg, now, blocked)
            blocked
        }
    }

    private suspend fun log(pkg: String, decision: BlockDecision, reason: BlockReason, snap: PolicySnapshot, attempt: Int): Long =
        db.attemptDao().insert(
            BlockLog(
                packageName = pkg,
                appName = apps.label(pkg),
                timestamp = clock.now(),
                blockedBy = reason.type.toBlockedBy(),
                scheduleId = reason.ruleId.takeIf { reason.type != ReasonType.SESSION && reason.type != ReasonType.BEDTIME && reason.type != ReasonType.DAILY_LIMIT },
                scheduleName = reason.name.takeIf { it.isNotBlank() },
                attemptNumber = attempt,
                sessionId = snap.session?.id?.takeIf { reason.type == ReasonType.SESSION },
                strength = decision.strength.name,
            ),
        )

    suspend fun setAction(logId: Long, action: String) = withContext(Dispatchers.IO) {
        if (logId > 0) db.attemptDao().setAction(logId, action)
    }

    companion object { const val DEBOUNCE_MS = 1_500L }
}

/**
 * "Open anyway" and emergency access (spec 2.4, 4.5). Both are per app, time-boxed and logged, and
 * both re-check the policy at the moment they are granted.
 */
class OverrideManager(
    private val context: android.content.Context,
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
    private val enforcer: Enforcer,
    private val apps: InstalledApps,
    private val onChanged: () -> Unit,
) {
    sealed class Result {
        data class Granted(val until: Long) : Result()
        data class Waiting(val readyAt: Long) : Result()
        object NotAllowed : Result()
        object NotNeeded : Result()
    }

    /** Normal reasons only. Refused when any Strict reason applies, whatever the UI showed. */
    /**
     * "Open anyway" was removed: a blocked app no longer opens without ending the block or using
     * emergency access. Kept so older callers are refused rather than granted.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun openAnyway(pkg: String, logId: Long): Result = Result.NotAllowed

    /**
     * Open anyway covers one visit: leaving the app ends it (at the latest after five minutes), so
     * the next opening shows the block screen again as the next try. Emergency access keeps its full
     * five minutes, because it already cost a reason and a 10-minute wait.
     */
    suspend fun onLeft(pkg: String) = withContext(Dispatchers.IO) {
        if (db.unlockEventDao().endOpenAnyway(pkg, clock.now()) > 0) onChanged()
    }

    /** Starts the 10-minute wait. A reason is required. Re-requesting keeps the earlier wait. */
    suspend fun requestEmergency(pkg: String, reasonText: String, logId: Long): Result = withContext(Dispatchers.IO) {
        if (!FrictionPolicy.emergencyReasonValid(reasonText)) return@withContext Result.NotAllowed
        val (decision, snap) = enforcer.decide(pkg)
        if (!decision.blocked) return@withContext Result.NotNeeded
        val result = db.withTransaction {
            val existing = db.unlockEventDao().pending(pkg)
            if (existing != null && clock.now() < FrictionPolicy.emergencyUsableUntil(existing.readyAt, clock.zone())) return@withTransaction Result.Waiting(existing.readyAt)
            existing?.let { db.unlockEventDao().update(it.copy(status = UnlockEventEntity.CANCELLED)) }
            val now = clock.now()
            val times = FrictionPolicy.emergency(now)
            val reason = decision.primary
            db.unlockEventDao().insert(
                UnlockEventEntity(
                    packageName = pkg, appName = apps.label(pkg), kind = OverrideKind.EMERGENCY.name,
                    status = UnlockEventEntity.PENDING, requestedAt = now, readyAt = times.readyAt,
                    reasonText = reasonText.trim().take(200), strength = decision.strength.name,
                    reasonType = reason?.type?.name, ruleId = reason?.ruleId, ruleName = reason?.name,
                    sessionId = snap.session?.id,
                ),
            )
            Result.Waiting(times.readyAt)
        }
        enforcer.setAction(logId, AttemptAction.EMERGENCY)
        if (result is Result.Waiting) EmergencyReadyWorker.schedule(context, pkg, result.readyAt)
        result
    }

    /**
     * The emergency request for [pkg] that is waiting or ready to use, or null. A request past its
     * last usable moment (midnight) is cancelled here, so the screen never offers a dead button.
     */
    suspend fun pendingEmergency(pkg: String): UnlockEventEntity? = withContext(Dispatchers.IO) {
        val pending = db.unlockEventDao().pending(pkg) ?: return@withContext null
        if (clock.now() >= FrictionPolicy.emergencyUsableUntil(pending.readyAt, clock.zone())) {
            db.unlockEventDao().update(pending.copy(status = UnlockEventEntity.CANCELLED))
            null
        } else pending
    }

    /** After the wait: opens the app for five minutes. */
    suspend fun useEmergency(pkg: String): Result = withContext(Dispatchers.IO) {
        val result = db.withTransaction {
            val pending = db.unlockEventDao().pending(pkg) ?: return@withTransaction Result.NotAllowed
            val now = clock.now()
            if (now < pending.readyAt) return@withTransaction Result.Waiting(pending.readyAt)
            if (!FrictionPolicy.emergencyUsable(pending.readyAt, now, clock.zone())) {
                db.unlockEventDao().update(pending.copy(status = UnlockEventEntity.CANCELLED))
                return@withTransaction Result.NotAllowed
            }
            val until = now + FrictionPolicy.EMERGENCY_UNLOCK_MINUTES * SessionClock.MINUTE
            db.unlockEventDao().update(pending.copy(status = UnlockEventEntity.GRANTED, grantedAt = now, expiresAt = until))
            Result.Granted(until)
        }
        if (result is Result.Granted) { onChanged(); EmergencyReadyWorker.cancel(context, pkg) }
        result
    }

    suspend fun cancelEmergency(pkg: String) = withContext(Dispatchers.IO) {
        db.unlockEventDao().pending(pkg)?.let { db.unlockEventDao().update(it.copy(status = UnlockEventEntity.CANCELLED)) }
        EmergencyReadyWorker.cancel(context, pkg)
    }

    private suspend fun insertGrant(
        pkg: String, kind: OverrideKind, reason: BlockReason?, strength: Strength,
        requested: Long, granted: Long, until: Long, text: String?,
    ) {
        db.unlockEventDao().insert(
            UnlockEventEntity(
                packageName = pkg, appName = apps.label(pkg), kind = kind.name, status = UnlockEventEntity.GRANTED,
                requestedAt = requested, readyAt = granted, grantedAt = granted, expiresAt = until, reasonText = text,
                strength = strength.name, reasonType = reason?.type?.name, ruleId = reason?.ruleId, ruleName = reason?.name,
            ),
        )
    }
}

/**
 * Focus Cycles guardrail: counts foreground time on the cycle's apps and starts a break when the
 * usage window is used up. State is persisted only on transitions, never every second.
 */
class FocusCycleTracker(private val db: FocusBlockDatabase, private val clock: AppClock, private val policy: PolicyRepository) {
    private val mutex = Mutex()

    /** Called on every foreground change. Returns when the current usage window would run out, if tracking. */
    suspend fun onForeground(pkg: String?, previous: String?): Long? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cycle = db.focusCycleDao().getActiveFocusCycleSync() ?: return@withLock null
            val snapshot = policy.snapshot()
            val packages = snapshot.focusCycles.firstOrNull { it.id == cycle.id }?.packages.orEmpty()
            if (packages.isEmpty()) return@withLock null
            val now = clock.now()
            var c: FocusCycle = cycle
            val window = PolicyRepository.focusCycleMillis(c.usageWindowMinutes)
            val pause = PolicyRepository.focusCycleMillis(c.breakDurationMinutes)

            // Break over: re-arm.
            c.breakStartTime?.let { start ->
                if (now >= start + pause) c = c.copy(isArmed = true, isActive = false, isPaused = false, breakStartTime = null, accumulatedUsageMillis = 0, cycleStartTime = null, lastActiveTime = null)
                else { if (c != cycle) db.focusCycleDao().update(c); return@withLock null }
            }
            // Leaving a cycle app: add the time spent.
            if (previous != null && previous in packages && c.lastActiveTime != null && !c.isPaused) {
                c = c.copy(accumulatedUsageMillis = c.accumulatedUsageMillis + (now - c.lastActiveTime!!).coerceAtLeast(0), isPaused = true, lastActiveTime = null)
            }
            var runsOutAt: Long? = null
            if (pkg != null && pkg in packages) {
                c = if (c.isArmed) c.copy(isArmed = false, isActive = true, isPaused = false, cycleStartTime = now, lastActiveTime = now, accumulatedUsageMillis = 0)
                else c.copy(isPaused = false, lastActiveTime = now)
                val left = window - c.accumulatedUsageMillis
                if (left <= 0) {
                    c = c.copy(breakStartTime = now, isActive = false, lastActiveTime = null)
                } else runsOutAt = now + left
            }
            if (c.accumulatedUsageMillis >= window && c.breakStartTime == null) c = c.copy(breakStartTime = now, isActive = false, lastActiveTime = null)
            if (c != cycle) db.focusCycleDao().update(c)
            runsOutAt
        }
    }
}

/** Troubleshooting log helper. */
class Diagnostics(private val db: FocusBlockDatabase, private val clock: AppClock) {
    suspend fun log(kind: String, detail: String = "") = withContext(Dispatchers.IO) {
        runCatching {
            db.diagnosticEventDao().insert(DiagnosticEvent(time = clock.now(), kind = kind, detail = detail.take(500)))
            db.diagnosticEventDao().deleteOlderThan(clock.now() - 30L * 24 * 3_600_000)
        }
    }
}
