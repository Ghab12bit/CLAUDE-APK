package com.focusblock.app.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focusblock.app.FocusBlockApp
import com.focusblock.app.R
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private const val TAG = "AppBlockingService"

/**
 * Default blocking host. Needs only Usage Access + "Display over other apps".
 *
 * Runs [BlockingEngine] (every blocking mode) and feeds it the foreground app polled from
 * UsageStatsManager, so FocusBlock works fully with Accessibility turned OFF. That matters
 * because banking and payment apps refuse to run while a third-party Accessibility service
 * is enabled. When the optional Accessibility add-on is connected it reports apps itself,
 * and this host stops polling to avoid duplicate events.
 */
@AndroidEntryPoint
class AppBlockingService : Service() {

    @Inject
    lateinit var repository: FocusBlockRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isMonitoring = false
    private var monitorJob: Job? = null

    private var lastReportedPackage: String? = null
    private var lastReportTime = 0L
    private var currentForeground: String? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // null = sticky restart after the system killed the process: resume enforcement
            ACTION_START, null -> startBlocking()
            ACTION_STOP -> stopBlocking()
            ACTION_UPDATE -> if (isMonitoring) updateNotification() else startBlocking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopBlocking()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startBlocking() {
        // Always (re)enter the foreground promptly after startForegroundService()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground start from the background (e.g. alarm-driven
            // update). The app restarts us next time it opens or on boot.
            Log.w(TAG, "Could not enter foreground", e)
            if (!isMonitoring) stopSelf()
            return
        }
        if (isMonitoring) return
        isMonitoring = true
        isRunning = true

        BlockingEngine.get(this).start()
        startMonitoring()
        Log.i(TAG, "Usage Access blocking started")
    }

    private fun stopBlocking() {
        isMonitoring = false
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
        // Keep enforcing if the optional Accessibility add-on still hosts the engine
        if (!FocusBlockAccessibilityService.isServiceRunning) {
            BlockingEngine.get(this).stop()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            var elapsed = 0L
            while (isActive && isMonitoring) {
                val interactive = powerManager.isInteractive
                try {
                    // The Accessibility add-on reports apps itself when connected
                    if (interactive && !FocusBlockAccessibilityService.isServiceRunning) {
                        pollForegroundApp()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Foreground poll failed", e)
                }
                val interval = if (interactive) CHECK_INTERVAL else SCREEN_OFF_INTERVAL
                delay(interval)
                // Re-acquire wake lock every 5 minutes to prevent timeout
                elapsed += interval
                if (elapsed >= 5 * 60 * 1000L) {
                    elapsed = 0L
                    reacquireWakeLock()
                }
            }
        }
    }

    private fun pollForegroundApp() {
        val pkg = getCurrentForegroundApp() ?: currentForeground ?: return
        currentForeground = pkg
        val now = System.currentTimeMillis()
        val changed = pkg != lastReportedPackage
        // Re-report an unchanged app periodically so an app reopened from Recents during
        // the engine's block cooldown still gets caught.
        if (changed || now - lastReportTime >= REFEED_INTERVAL) {
            lastReportedPackage = pkg
            lastReportTime = now
            BlockingEngine.get(this).onForegroundApp(pkg)
        }
    }

    private fun getCurrentForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val usageEvents = usageStatsManager.queryEvents(endTime - LOOKBACK_MS, endTime)
        var lastPackage: String? = null
        var lastTime = 0L
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp >= lastTime) {
                    lastTime = event.timeStamp
                    lastPackage = event.packageName
                }
            }
        }
        return lastPackage
    }

    private fun reacquireWakeLock() {
        releaseWakeLock()
        acquireWakeLock()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FocusBlockApp.CHANNEL_BLOCKING)
            .setContentTitle(getString(R.string.notification_blocking_active))
            .setContentText(getString(R.string.notification_apps_blocked, getBlockedAppsCountSync()))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getBlockedAppsCountSync(): Int {
        // Use runBlocking with Dispatchers.IO to avoid blocking main thread
        return runBlocking(Dispatchers.IO) {
            try {
                repository.getBlockedAppsCount().first()
            } catch (e: Exception) {
                0
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FocusBlock::BlockingServiceWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    companion object {
        /** True while this Usage Access host is running. */
        @Volatile
        var isRunning = false
            private set

        const val ACTION_START = "com.focusblock.app.action.START"
        const val ACTION_STOP = "com.focusblock.app.action.STOP"
        const val ACTION_UPDATE = "com.focusblock.app.action.UPDATE"
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL = 500L // Poll foreground app every 500ms while screen is on
        private const val SCREEN_OFF_INTERVAL = 2000L // Back off while the screen is off
        private const val REFEED_INTERVAL = 3000L // Re-report an unchanged foreground app (beats the engine's 2s cooldown)
        private const val LOOKBACK_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, AppBlockingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AppBlockingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun update(context: Context) {
            val intent = Intent(context, AppBlockingService::class.java).apply {
                action = ACTION_UPDATE
            }
            context.startService(intent)
        }
    }
}
