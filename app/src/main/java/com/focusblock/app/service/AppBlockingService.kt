package com.focusblock.app.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.focusblock.app.FocusBlockApp
import com.focusblock.app.R
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.entity.BlockedByType
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.ui.MainActivity
import com.focusblock.app.ui.overlay.BlockedAppActivity
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.TimeUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class AppBlockingService : Service() {

    @Inject
    lateinit var repository: FocusBlockRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isMonitoring = false
    private var lastCheckedPackage: String? = null
    private var lastBlockTime = 0L

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBlocking()
            ACTION_STOP -> stopBlocking()
            ACTION_UPDATE -> updateNotification()
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
        if (isMonitoring) return
        isMonitoring = true

        startForeground(NOTIFICATION_ID, createNotification())
        startMonitoring()
    }

    private fun stopBlocking() {
        isMonitoring = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startMonitoring() {
        serviceScope.launch {
            var checkCount = 0
            while (isMonitoring) {
                try {
                    checkCurrentApp()
                    // Re-acquire wake lock every 5 minutes to prevent timeout
                    checkCount++
                    if (checkCount >= 600) { // 600 * 500ms = 5 minutes
                        checkCount = 0
                        reacquireWakeLock()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(CHECK_INTERVAL)
            }
        }
    }

    private fun reacquireWakeLock() {
        releaseWakeLock()
        acquireWakeLock()
    }

    private suspend fun checkCurrentApp() {
        val currentPackage = getCurrentForegroundApp() ?: return

        // Don't block our own app or system UI
        if (currentPackage == packageName ||
            currentPackage == "com.android.systemui" ||
            currentPackage == "com.android.launcher" ||
            currentPackage.startsWith("com.android.launcher") ||
            currentPackage.startsWith("com.sec.android.app.launcher") ||
            currentPackage.startsWith("com.google.android.apps.nexuslauncher")) {
            return
        }

        // Debounce - don't block same app within a short time
        if (currentPackage == lastCheckedPackage &&
            System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN) {
            return
        }

        if (shouldBlockApp(currentPackage)) {
            lastCheckedPackage = currentPackage
            lastBlockTime = System.currentTimeMillis()
            blockApp(currentPackage)
        }
    }

    private fun getCurrentForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        var lastPackage: String? = null
        var lastTime = 0L

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp > lastTime) {
                    lastTime = event.timeStamp
                    lastPackage = event.packageName
                }
            }
        }

        return lastPackage
    }

    private suspend fun shouldBlockApp(packageName: String): Boolean {
        // Check if in allowlist
        val blockedApp = repository.getBlockedApp(packageName)
        if (blockedApp?.isInAllowlist == true) {
            return false
        }

        // Check Quick Block
        val quickBlockSession = repository.getActiveQuickBlockSessionSync()
        if (quickBlockSession != null) {
            val blockedPackages = quickBlockSession.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                return true
            }
        }

        // Check schedules
        val currentMinute = TimeUtils.getCurrentMinuteOfDay()
        val dayOfWeek = TimeUtils.getCurrentDayOfWeek()
        val activeSchedules = repository.getActiveSchedules(currentMinute, dayOfWeek)

        for (schedule in activeSchedules) {
            val blockedPackages = schedule.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                return true
            }
        }

        // Check if individually blocked
        if (blockedApp?.isBlocked == true) {
            return true
        }

        return false
    }

    private suspend fun blockApp(packageName: String) {
        val appName = AppUtils.getAppName(this, packageName)

        // Log the block
        val blockedByType = determineBlockedByType(packageName)
        repository.insertBlockLog(
            BlockLog(
                packageName = packageName,
                appName = appName,
                blockedBy = blockedByType
            )
        )

        // Update block count
        repository.incrementBlockCount(packageName)

        // Vibrate
        vibrateDevice()

        // Show blocking screen
        showBlockingScreen(packageName, appName, blockedByType)
    }

    private suspend fun determineBlockedByType(packageName: String): BlockedByType {
        // Check if Hard Mode is enabled
        if (repository.isHardModeEnabled()) {
            return BlockedByType.HARD_MODE
        }

        // Check if Strict Mode is enabled
        if (repository.isStrictModeEnabled()) {
            return BlockedByType.STRICT_MODE
        }

        // Check Quick Block
        val quickBlockSession = repository.getActiveQuickBlockSessionSync()
        if (quickBlockSession != null) {
            val blockedPackages = quickBlockSession.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                return BlockedByType.QUICK_BLOCK
            }
        }

        return BlockedByType.SCHEDULE
    }

    private fun showBlockingScreen(packageName: String, appName: String, blockedByType: BlockedByType) {
        val intent = Intent(this, BlockedAppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BlockedAppActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(BlockedAppActivity.EXTRA_APP_NAME, appName)
            putExtra(BlockedAppActivity.EXTRA_BLOCKED_BY, blockedByType.name)
        }
        startActivity(intent)
    }

    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        const val ACTION_START = "com.focusblock.app.action.START"
        const val ACTION_STOP = "com.focusblock.app.action.STOP"
        const val ACTION_UPDATE = "com.focusblock.app.action.UPDATE"
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL = 500L // Check every 500ms
        private const val BLOCK_COOLDOWN = 2000L // Don't block same app within 2 seconds

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
