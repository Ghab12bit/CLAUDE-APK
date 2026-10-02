package com.focusblock.app.service

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.Notifier
import com.focusblock.app.core.ServiceHeartbeat
import com.focusblock.app.ui.intervention.InterventionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup enforcement while the accessibility service is not running (spec 8.1, open decision #3:
 * resolved as "full decision parity, best-effort detection"). It asks the same [com.focusblock.app.core.Enforcer]
 * as the accessibility service, so it enforces the same policy set. Its limits, documented in the
 * implementation log: it notices a new app up to ~1 s late, only while the screen is on, and Android
 * lets it show the block screen from the background only with "Display over other apps".
 */
class AppBlockingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var current: String? = null
    /** Last app in front that was not a keyboard or system overlay. */
    private var lastReal: String? = null
    private var lastSameAppCheck = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = AppGraph.get(this)
        val notification = graph.notifier.backupNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifier.ID_BACKUP, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_BACKUP, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { poll(graph) }
        return START_STICKY
    }

    private suspend fun poll(graph: AppGraph) {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        while (scope.isActive) {
            if (ServiceHeartbeat.connected) { stopSelf(); return }
            if (power.isInteractive) {
                val now = System.currentTimeMillis()
                latestForeground(usage, now - 15_000, now)?.let { pkg ->
                    if (pkg != current) {
                        val previous = current
                        current = pkg
                        if (pkg !in graph.safety.transient() && pkg != lastReal) {
                            // Leaving an app ends its Open anyway (it covers one visit only).
                            lastReal?.takeIf { it != packageName }?.let { left -> runCatching { graph.overrides.onLeft(left) } }
                            lastReal = pkg
                        }
                        if (pkg != packageName && pkg !in graph.safety.transient()) {
                            graph.focusCycles.onForeground(pkg, previous)
                            graph.enforcer.check(pkg)?.let { blocked -> show(pkg, blocked.logId, blocked.attempt) }
                        }
                    } else if (pkg != packageName && now - lastSameAppCheck > 15_000) {
                        // Re-check the open app every 15 s so limits and rule starts apply without a new launch.
                        lastSameAppCheck = now
                        graph.enforcer.check(pkg, freshUsage = true)?.let { blocked -> show(pkg, blocked.logId, blocked.attempt) }
                    }
                }
            }
            delay(1_000)
        }
    }

    private fun latestForeground(usage: UsageStatsManager, from: Long, to: Long): String? {
        val events = runCatching { usage.queryEvents(from, to) }.getOrNull() ?: return current
        val e = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) latest = e.packageName
        }
        // No new events: the same app is still open.
        return latest ?: current
    }

    private suspend fun show(pkg: String, logId: Long, attempt: Int) = withContext(Dispatchers.Main) {
        val intent = InterventionActivity.intent(this@AppBlockingService, pkg, logId, attempt)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this@AppBlockingService)) {
            runCatching { startActivity(intent) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AppBlockingService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AppBlockingService::class.java)) }
        }
    }
}
