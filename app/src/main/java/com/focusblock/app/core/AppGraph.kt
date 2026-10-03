package com.focusblock.app.core

import android.content.Context
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.EssentialApp
import com.focusblock.app.policy.BlockPolicyEngine
import com.focusblock.app.policy.PolicySnapshot
import com.focusblock.app.policy.ReasonType
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.widget.FocusBlockWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Essential apps (spec 8.4): permanent, never blockable, seeded once with phone/messages/clock/camera/maps. */
class EssentialsRepository(private val context: Context, private val db: FocusBlockDatabase) {
    fun flow(): Flow<Set<String>> = db.essentialAppDao().allFlow().map { list -> list.map { it.packageName }.toSet() }

    suspend fun ensureSeeded() {
        if (db.settingsDao().getValue(PrefKeys.ESSENTIALS_SEEDED) == "true") return
        val defaults = DefaultEssentials.resolve(context).map { EssentialApp(it, isDefault = true) }
        db.essentialAppDao().insertIfMissing(defaults)
        db.settingsDao().insert(AppSettings(PrefKeys.ESSENTIALS_SEEDED, "true"))
    }

    suspend fun set(packages: Set<String>) {
        val current = db.essentialAppDao().packages().toSet()
        (current - packages).forEach { db.essentialAppDao().delete(it) }
        db.essentialAppDao().insertIfMissing((packages - current).map { EssentialApp(it) })
    }
}

/**
 * The app's single object graph. Services, receivers, the widget and ViewModels all use the same
 * instances, so every entry point goes through the same session API and policy engine.
 */
class AppGraph private constructor(appContext: Context) {
    val context: Context = appContext.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: FocusBlockDatabase = FocusBlockDatabase.getDatabase(context)
    val clock: AppClock = SystemAppClock(context)
    val apps = InstalledApps(context)
    val safety = SafetyApps(context)
    val usage = UsageRepository(context, clock)
    val categories = AppCategories(context, db)
    val history = UsageHistory(context, db, usage, clock, safety)
    val diagnostics = Diagnostics(db, clock)
    val notifier = Notifier(context, db, clock, apps)
    val alarms = AlarmScheduler(context)
    val essentials = EssentialsRepository(context, db)
    val sessions: SessionManager = SessionManager(db, clock, exempt = { policy.exempt() }, onChanged = { requestRefresh() })
    val policy: PolicyRepository = PolicyRepository(db, clock, sessions, usage, apps, safety)
    val enforcer = Enforcer(db, clock, policy, apps)
    val overrides = OverrideManager(db, clock, enforcer, apps) { requestRefresh() }
    val focusCycles = FocusCycleTracker(db, clock, policy)
    val smartApps by lazy { SmartAppsRepository(history, categories, policy, apps, db, clock) }
    val recommendations = RecommendationRepository(context, db, clock, usage, policy, apps)

    private val refreshMutex = Mutex()
    private var pendingRefresh: Job? = null
    @Volatile private var problemShown = false

    /** Coalesces bursts of changes into one refresh. */
    fun requestRefresh() {
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(200)
            refresh()
        }
    }

    /** Re-reads policy and updates the alarm, ongoing notification, widget, services and health warnings. */
    suspend fun refresh() = refreshMutex.withLock {
        policy.invalidate()
        val snap = policy.snapshot()
        val now = clock.now()
        val active = sessions.active()
        notifier.showActive(active, active?.let(sessions::toInput))
        val next = BlockPolicyEngine.nextChangeAt(snap, now, clock.zone())
        // A six-hour heartbeat re-registers the alarm even when nothing is scheduled.
        alarms.schedule(listOfNotNull(next, now + 6 * 3_600_000L).minOrNull())
        FocusBlockWidget.updateAll(context)
        ServiceHeartbeat.recheck?.invoke()
        val protecting = anythingProtecting(snap, now)
        ensureBackupBlocking(protecting)
        reportDegraded(protecting)
    }

    /** Alarm and recovery entry point: ends finished blocks, announces rule starts, then refreshes. */
    suspend fun tick(reason: String) {
        essentials.ensureSeeded()
        val ended = sessions.reconcile()
        if (ended != null) notifier.showEnded(ended)
        val snap = policy.snapshot()
        val now = clock.now()
        BlockPolicyEngine.activeRuleReasons(snap, now, clock.zone()).forEach { r ->
            val start = when (r.type) {
                ReasonType.ROUTINE -> snap.routines.firstOrNull { it.id == r.ruleId }?.window?.occurrenceAt(now, clock.zone())?.start
                ReasonType.BEDTIME -> snap.bedtime?.window?.occurrenceAt(now, clock.zone())?.start
                else -> null
            }
            if (start != null && now - start in 0..3 * SessionClock.MINUTE) {
                val name = if (r.type == ReasonType.BEDTIME) context.getString(R.string.name_bedtime) else r.name
                notifier.showRuleStarted(name, r.until)
            }
        }
        refresh()
    }

    /** TIME_SET: a manual clock change must not end a Strict block early; log suspected tampering. */
    suspend fun checkClockChange() {
        val active = sessions.active() ?: return
        val trusted = sessions.trustedNow(active)
        if (SessionClock.clockTampered(clock.now(), trusted)) {
            diagnostics.log("CLOCK_CHANGE_SUSPECTED", "wall=${clock.now()} trusted=$trusted session=${active.id} strength=${active.strength}")
        }
    }

    private fun anythingProtecting(snap: PolicySnapshot, now: Long): Boolean =
        (snap.session != null && !SessionClock.state(snap.session, now).ended) ||
            snap.routines.any { it.enabled } || snap.bedtime?.enabled == true ||
            snap.appLimits.any { it.enabled } || snap.dailyLimit?.enabled == true || snap.focusCycles.any { it.enabled }

    /** The foreground fallback runs only while Accessibility is not running and something needs enforcing. */
    private fun ensureBackupBlocking(protecting: Boolean) {
        val accessibilityOk = PermissionHealth.accessibilityState(context) == HealthState.OK && ServiceHeartbeat.connected
        if (protecting && !accessibilityOk && usage.hasAccess()) AppBlockingService.start(context)
        else if (accessibilityOk || !protecting) AppBlockingService.stop(context)
    }

    /** Degraded protection is always reported honestly (spec 4.11, 12.1). */
    private suspend fun reportDegraded(protecting: Boolean) {
        val broken = protecting && PermissionHealth.accessibilityState(context) != HealthState.OK
        if (broken && !problemShown) {
            problemShown = true
            notifier.showProblem(Requirement.ACCESSIBILITY)
            diagnostics.log("DEGRADED", "Accessibility not running while blocking is expected")
        } else if (!broken && problemShown) {
            problemShown = false
            notifier.cancelProblem()
        }
    }

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
