package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.focusblock.app.core.AlarmScheduler
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.Enforcer
import com.focusblock.app.core.ServiceHeartbeat
import com.focusblock.app.policy.BlockPolicyEngine
import com.focusblock.app.ui.intervention.InterventionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Primary enforcement path. It only tracks which app is in the foreground and asks the shared
 * [Enforcer] (and through it the single policy engine) whether that app may stay open. It holds no
 * policy of its own and keeps no timers in memory: re-checks are scheduled for the next moment a
 * decision can change (session/rule boundary, limit running out, override expiry).
 */
class FocusBlockAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private lateinit var graph: AppGraph

    @Volatile private var foreground: String? = null
    @Volatile private var foregroundSince: Long = 0L
    /** Start of the current stretch of use: the app change, or the screen coming back on. */
    @Volatile private var usageSince: Long = 0L
    private var remindersSent = 0

    private val recheckRunnable = Runnable { scope.launch { recheck(fresh = true) } }
    private val reminderRunnable = Runnable { scope.launch { usageReminder() } }

    /**
     * Locked time is not use: usage reminders and Focus Cycles stop counting while the screen is off.
     * Handler timers also stop in deep sleep, so screen-on re-checks the app in front (and a late
     * policy alarm) at once.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(reminderRunnable)
                    val pkg = foreground ?: return
                    scope.launch { mutex.withLock { runCatching { graph.focusCycles.onForeground(null, pkg) } } }
                }
                Intent.ACTION_SCREEN_ON -> {
                    usageSince = System.currentTimeMillis()
                    remindersSent = 0
                    recheckNow()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        graph = AppGraph.get(this)
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
        }
        ServiceHeartbeat.owner = this
        ServiceHeartbeat.connected = true
        ServiceHeartbeat.connectedAt = System.currentTimeMillis()
        ServiceHeartbeat.recheck = { handler.post { recheckNow() } }
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED) }
        scope.launch {
            graph.diagnostics.log("SERVICE_CONNECTED")
            graph.tick("service_connected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        ServiceHeartbeat.lastEventAt = System.currentTimeMillis()
        if (!::graph.isInitialized) return
        // Keyboards and system UI appear over the current app; they do not change what is open.
        if (pkg in graph.safety.transient()) return
        val previous = foreground
        if (pkg == previous) return
        foreground = pkg
        // Leaving an app ends its Open anyway (it covers one visit only).
        if (previous != null && previous != packageName) scope.launch { runCatching { graph.overrides.onLeft(previous) } }
        foregroundSince = System.currentTimeMillis()
        usageSince = foregroundSince
        remindersSent = 0
        handler.removeCallbacks(reminderRunnable)
        if (pkg == packageName) {
            // FocusBlock itself is never checked, but a Focus Cycle must still count the time in the app just left.
            if (previous != null) scope.launch { mutex.withLock { runCatching { graph.focusCycles.onForeground(null, previous) } } }
            return
        }
        scope.launch { handle(pkg, previous, fresh = false) }
    }

    private suspend fun handle(pkg: String, previous: String?, fresh: Boolean) = mutex.withLock {
        // With the screen off nothing is in use: the previous app's time ends, [pkg]'s starts at screen-on.
        val cycleRunsOut = runCatching { graph.focusCycles.onForeground(pkg.takeIf { interactive() }, previous) }.getOrNull()
        val blocked = runCatching { graph.enforcer.check(pkg, fresh) }.getOrNull()
        if (blocked != null && foreground == pkg) {
            withContext(Dispatchers.Main) { showIntervention(pkg, blocked) }
        } else if (blocked == null) {
            scheduleReminder(pkg)
        }
        scheduleRecheck(pkg, cycleRunsOut)
    }

    /** Re-checks the app in front now, replacing the pending timer. */
    private fun recheckNow() {
        handler.removeCallbacks(recheckRunnable)
        scope.launch { recheck(fresh = false) }
    }

    private suspend fun recheck(fresh: Boolean) {
        catchUpAlarm()
        val pkg = foreground ?: return scheduleRecheck(null, null)
        if (pkg == packageName || pkg in graph.safety.packages()) return scheduleRecheck(null, null)
        handle(pkg, pkg, fresh)
    }

    /**
     * Without exact-alarm access Android can deliver the policy alarm late, leaving session ends,
     * rule-start notices and the ongoing notification stale. Once it is overdue, run its tick here;
     * the tick re-registers the alarm and asks for another re-check.
     */
    private fun catchUpAlarm() {
        if (graph.alarms.claimLate(graph.clock.now())) graph.scope.launch { runCatching { graph.tick("alarm_late") } }
    }

    /**
     * Covers the blocked app with the block screen. No HOME action first: Android handles it
     * asynchronously, so the launcher could land on top of the block screen and hide it. The
     * block screen's own "Back to home screen" leaves the app. HOME is only the fallback when the
     * block screen cannot be started.
     */
    private fun showIntervention(pkg: String, blocked: Enforcer.Blocked) {
        val shown = runCatching { startActivity(InterventionActivity.intent(this, pkg, blocked.logId, blocked.attempt)) }.isSuccess
        if (!shown) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        // Android can refuse a background start without an error. If the blocked app is still in
        // front once the block screen should be up, leave it anyway.
        // Only if the block screen never came up: a slow start must not be covered by HOME.
        val since = foregroundSince
        val requested = android.os.SystemClock.elapsedRealtime()
        handler.postDelayed({
            val shown = ServiceHeartbeat.interventionShownAt >= requested
            if (!shown && foreground == pkg && foregroundSince == since) performGlobalAction(GLOBAL_ACTION_HOME)
        }, INTERVENTION_GRACE_MS)
    }

    /** Next re-check: the earliest policy change, or when an open app's limit or cycle runs out. */
    private suspend fun scheduleRecheck(pkg: String?, cycleRunsOut: Long?) {
        val snap = graph.policy.snapshot()
        val now = System.currentTimeMillis()
        val candidates = listOfNotNull(
            BlockPolicyEngine.nextChangeAt(snap, now, graph.clock.zone()),
            pkg?.let { BlockPolicyEngine.limitReachedAt(it, snap, now) },
            cycleRunsOut,
            // Shortly after the policy alarm is due, in case Android delivers it late (catchUpAlarm).
            graph.alarms.nextAt?.plus(AlarmScheduler.LATE_MS)?.takeIf { it > now },
        )
        // Usage data can lag; never wait more than 15 minutes while an app is open.
        val next = (candidates + (now + 15 * 60_000L)).minOrNull()!!
        val delay = (next - now).coerceIn(1_000L, 15 * 60_000L)
        handler.removeCallbacks(recheckRunnable)
        handler.postDelayed(recheckRunnable, delay)
    }

    /** Usage reminders after 30 and 60 minutes in one non-essential app (Settings › Notifications). */
    private suspend fun scheduleReminder(pkg: String) {
        if (pkg in graph.policy.exempt()) return
        val minutes = if (remindersSent == 0) 30 else 60
        val delay = usageSince + minutes * 60_000L - System.currentTimeMillis()
        handler.removeCallbacks(reminderRunnable)
        if (delay > 0) handler.postDelayed(reminderRunnable, delay)
    }

    private suspend fun usageReminder() {
        val pkg = foreground ?: return
        if (!interactive() || pkg == packageName) return
        val minutes = ((System.currentTimeMillis() - usageSince) / 60_000L).toInt()
        if (minutes < 30) return
        graph.notifier.showUsageReminder(pkg, if (minutes >= 60) 60 else 30)
        remindersSent++
        if (remindersSent < 2) scheduleReminder(pkg)
    }

    private fun interactive(): Boolean = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        // Only the instance that is currently connected may report the service as gone.
        if (ServiceHeartbeat.owner !== this || !ServiceHeartbeat.connected) return
        ServiceHeartbeat.owner = null
        ServiceHeartbeat.connected = false
        ServiceHeartbeat.recheck = null
        if (::graph.isInitialized) {
            graph.scope.launch {
                graph.diagnostics.log("SERVICE_DISCONNECTED")
                graph.refresh()
            }
        }
    }

    companion object {
        private const val INTERVENTION_GRACE_MS = 3_000L
        val isRunning: Boolean get() = ServiceHeartbeat.connected
    }
}
