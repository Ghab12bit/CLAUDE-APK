package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.focusblock.app.FocusBlockApp
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.entity.BlockedByType
import com.focusblock.app.database.entity.FocusCycle
import com.focusblock.app.ui.MainActivity
import com.focusblock.app.ui.overlay.AppTimerReflectionActivity
import com.focusblock.app.ui.overlay.BlockedAppActivity
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.TimeUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class FocusBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusBlockA11y"
        private const val BLOCK_COOLDOWN = 2000L
        private const val FOCUS_CYCLE_NOTIFICATION_ID = 3001
        private const val CACHE_REFRESH_INTERVAL_MS = 5000L // Refresh cache every 5 seconds

        // ========== MINDFUL REMINDER THRESHOLDS ==========
        private const val GENTLE_REMINDER_THRESHOLD_MINUTES = 30 // First gentle nudge at 30 min
        private const val FIRM_REMINDER_THRESHOLD_MINUTES = 60 // Firm reminder at 60 min
        private const val SESSION_CHECK_INTERVAL_MS = 60_000L // Check every minute
        private const val GENTLE_REMINDER_NOTIFICATION_ID = 4001
        private const val FIRM_REMINDER_NOTIFICATION_ID = 4002

        // ========== APP TIMER (Shared Time Limit) ==========
        private const val APP_TIMER_CHECK_INTERVAL_MS = 30_000L // Check every 30 seconds

        var isServiceRunning = false
            private set

        /**
         * Wrapper for TimeUtils.focusCycleTimeToMillis
         */
        private fun timeToMillis(value: Int): Long = TimeUtils.focusCycleTimeToMillis(value)

        /**
         * Format remaining time for display
         * Shows seconds if under 1 minute, otherwise minutes
         */
        private fun formatRemainingTime(millis: Long): String {
            val seconds = (millis / 1000).toInt()
            return if (seconds < 60) {
                "$seconds sec"
            } else {
                "${seconds / 60} min"
            }
        }

        /**
         * Compute LIVE elapsed usage time from timestamps
         * This is the single source of truth for timing
         * @param cycle The Focus Cycle data
         * @param now Current timestamp
         * @return Total elapsed usage time in milliseconds
         */
        fun computeElapsedUsageMillis(cycle: FocusCycle, now: Long): Long {
            // If paused or no active session, return accumulated only
            if (cycle.isPaused || cycle.lastActiveTime == null) {
                return cycle.accumulatedUsageMillis
            }
            // LIVE calculation: accumulated + current session time
            val currentSessionMillis = now - cycle.lastActiveTime
            return cycle.accumulatedUsageMillis + currentSessionMillis
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val immediateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedPackage: String? = null
    private var lastBlockTime = 0L
    private var isBlockingInProgress = false
    private val database by lazy { FocusBlockDatabase.getDatabase(applicationContext) }
    private var lastForegroundPackage: String? = null

    // ========== QUICK BLOCK TIMER ENFORCEMENT ==========
    private val quickBlockTimerHandler = Handler(Looper.getMainLooper())
    private var quickBlockTimerRunnable: Runnable? = null
    private var cachedQuickBlockEndTime: Long? = null
    private var cachedQuickBlockPackages: Set<String> = emptySet()
    private val QUICK_BLOCK_CHECK_INTERVAL = 1000L // Check every second

    // ========== CACHED STATE FOR INSTANT TRIGGERING ==========
    @Volatile private var cachedFocusCycle: FocusCycle? = null
    @Volatile private var cachedFocusCyclePackages: Set<String> = emptySet()
    @Volatile private var lastCacheRefresh: Long = 0L
    private val isCacheRefreshing = AtomicBoolean(false)

    // ========== MINDFUL SESSION TRACKING ==========
    // Track continuous session duration for distracting apps (apps that get blocked)
    private data class AppSession(
        val packageName: String,
        val startTime: Long,
        var gentleReminderShown: Boolean = false,
        var firmReminderShown: Boolean = false
    )
    private val activeSessions = ConcurrentHashMap<String, AppSession>()
    private val sessionCheckHandler = Handler(Looper.getMainLooper())
    private var sessionCheckRunnable: Runnable? = null
    private var cachedDistractingApps: Set<String> = emptySet() // Apps that are typically blocked/tracked

    // ========== APP TIMER TRACKING ==========
    private val appTimerHandler = Handler(Looper.getMainLooper())
    private var appTimerRunnable: Runnable? = null
    @Volatile private var cachedTimerApps: Set<String> = emptySet()
    @Volatile private var cachedTimerLimitMinutes: Int = 30
    @Volatile private var cachedTimerEscalationMinutes: Int = 20
    @Volatile private var cachedTimerEnabled: Boolean = false
    private var lastAppTimerUsageMinutes: Int = 0
    private var currentTimerAppStartTime: Long? = null // When current timer app session started

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // INSTANT TRIGGER: Set to 0 for immediate event delivery
            notificationTimeout = 0
        }
        serviceInfo = info

        isServiceRunning = true
        Log.i(TAG, "FocusBlock Accessibility Service is now running")

        // Initialize cache immediately
        refreshFocusCycleCache()
        refreshDistractingAppsCache()
        refreshAppTimerCache()

        // Start Quick Block timer enforcement
        startQuickBlockTimerCheck()

        // Start session duration monitoring for mindful reminders
        startSessionDurationCheck()

        // Start App Timer usage monitoring
        startAppTimerCheck()

        // Show toast to confirm service is running
        mainHandler.post {
            android.widget.Toast.makeText(
                applicationContext,
                "FocusBlock Accessibility Service enabled",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Start periodic Quick Block timer check
     * This ensures the blocking screen appears IMMEDIATELY when timer ends,
     * even if the user stays in a blocked app
     */
    private fun startQuickBlockTimerCheck() {
        stopQuickBlockTimerCheck() // Clear any existing timer

        quickBlockTimerRunnable = object : Runnable {
            override fun run() {
                checkQuickBlockTimerExpiration()
                quickBlockTimerHandler.postDelayed(this, QUICK_BLOCK_CHECK_INTERVAL)
            }
        }
        quickBlockTimerHandler.post(quickBlockTimerRunnable!!)
        Log.d(TAG, "Quick Block timer enforcement started")
    }

    private fun stopQuickBlockTimerCheck() {
        quickBlockTimerRunnable?.let {
            quickBlockTimerHandler.removeCallbacks(it)
        }
        quickBlockTimerRunnable = null
    }

    /**
     * Check if Quick Focus/Block session timer has expired and enforce blocking
     * This runs every second to ensure immediate blocking when timer ends
     *
     * Quick Focus model:
     * - During focus/work period: apps are ALLOWED (user is using the app)
     * - When timer ends: BREAK period starts, apps should be BLOCKED immediately
     */
    private fun checkQuickBlockTimerExpiration() {
        immediateScope.launch {
            try {
                val session = database.quickBlockSessionDao().getActiveSessionSync()

                if (session != null && session.endTime != null) {
                    val now = System.currentTimeMillis()

                    // Cache the session data for quick access
                    cachedQuickBlockEndTime = session.endTime
                    cachedQuickBlockPackages = session.blockedPackages
                        .split(",")
                        .filter { it.isNotBlank() }
                        .toSet()

                    // Check if timer has just expired (work period ended, break should start)
                    if (now >= session.endTime) {
                        Log.i(TAG, "Quick Focus timer EXPIRED at $now (endTime: ${session.endTime})")
                        Log.i(TAG, "Focus period ended - BREAK period starting, apps should be blocked")

                        // Check if user is currently on a tracked app
                        val currentPackage = lastForegroundPackage
                        if (currentPackage != null && cachedQuickBlockPackages.contains(currentPackage)) {
                            Log.i(TAG, "User is on tracked app $currentPackage when focus timer expired - showing block screen IMMEDIATELY")

                            // Show blocking screen immediately - user must take a break now
                            val appName = AppUtils.getAppName(applicationContext, currentPackage)
                            mainHandler.post {
                                showBlockingScreen(currentPackage, appName, BlockedByType.QUICK_BLOCK)
                            }

                            // Vibrate to alert user (longer vibration for timer expiration)
                            vibrateForTimerExpiration()
                        }

                        // For Pomodoro sessions, transition to break period instead of deactivating
                        if (session.isPomodoroSession && session.pomodoroBreakMinutes > 0) {
                            // Update session to represent break period
                            // The break period uses the same blocked packages but enforces blocking
                            val breakEndTime = now + session.pomodoroBreakMinutes * 60 * 1000L
                            val updatedSession = session.copy(
                                startTime = now,
                                endTime = breakEndTime,
                                isPomodoroSession = false // Mark as break period (blocking mode)
                            )
                            database.quickBlockSessionDao().update(updatedSession)
                            Log.i(TAG, "Pomodoro: Transitioned to break period. Break ends at $breakEndTime")

                            // Show toast notification
                            mainHandler.post {
                                android.widget.Toast.makeText(
                                    applicationContext,
                                    "Focus time complete! Take a ${session.pomodoroBreakMinutes} minute break.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            // Non-Pomodoro session or break period ended - deactivate
                            database.quickBlockSessionDao().deactivate(session.id)
                            Log.i(TAG, "Quick Block session deactivated after timer expiration")

                            // Clear cached data
                            cachedQuickBlockEndTime = null
                            cachedQuickBlockPackages = emptySet()
                        }
                    }
                } else {
                    // No active timed session
                    cachedQuickBlockEndTime = null
                    cachedQuickBlockPackages = emptySet()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking Quick Block timer", e)
            }
        }
    }

    /**
     * Vibrate device to alert user of timer expiration (longer vibration)
     */
    private fun vibrateForTimerExpiration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate", e)
        }
    }

    /**
     * Refresh the Focus Cycle cache from database
     * Called periodically and on-demand
     */
    private fun refreshFocusCycleCache() {
        if (!isCacheRefreshing.compareAndSet(false, true)) {
            return // Already refreshing
        }

        immediateScope.launch {
            try {
                val focusCycleDao = database.focusCycleDao()
                val cycle = focusCycleDao.getActiveFocusCycleSync()

                cachedFocusCycle = cycle
                cachedFocusCyclePackages = cycle?.selectedPackages
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toSet() ?: emptySet()
                lastCacheRefresh = System.currentTimeMillis()

                Log.d(TAG, "Cache refreshed: cycle=${cycle?.isEnabled}, packages=${cachedFocusCyclePackages.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh cache", e)
            } finally {
                isCacheRefreshing.set(false)
            }
        }
    }

    // ========== MINDFUL REMINDER SESSION TRACKING ==========

    /**
     * Refresh the list of distracting apps (apps that are blocked or tracked)
     * These are apps we want to monitor for long session reminders
     */
    private fun refreshDistractingAppsCache() {
        immediateScope.launch {
            try {
                val blockedApps = database.blockedAppDao().getBlockedPackageNames().toSet()

                val focusCycleApps = cachedFocusCyclePackages

                val quickBlockApps = database.quickBlockSessionDao().getActiveSessionSync()
                    ?.blockedPackages
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toSet() ?: emptySet()

                cachedDistractingApps = blockedApps + focusCycleApps + quickBlockApps
                Log.d(TAG, "Distracting apps cache refreshed: ${cachedDistractingApps.size} apps")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh distracting apps cache", e)
            }
        }
    }

    /**
     * Start periodic session duration check for mindful reminders
     * Runs every minute to check if any app session has exceeded thresholds
     */
    private fun startSessionDurationCheck() {
        stopSessionDurationCheck()

        sessionCheckRunnable = object : Runnable {
            override fun run() {
                checkSessionDurations()
                sessionCheckHandler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
            }
        }
        sessionCheckHandler.post(sessionCheckRunnable!!)
        Log.d(TAG, "Session duration monitoring started")
    }

    private fun stopSessionDurationCheck() {
        sessionCheckRunnable?.let {
            sessionCheckHandler.removeCallbacks(it)
        }
        sessionCheckRunnable = null
    }

    /**
     * Track app session start/switch
     * Called when the foreground app changes
     */
    private fun trackAppSession(packageName: String, eventTime: Long) {
        // End previous session for other apps
        val previousPackage = lastForegroundPackage
        if (previousPackage != null && previousPackage != packageName) {
            activeSessions.remove(previousPackage)
            Log.d(TAG, "Session ended for: $previousPackage")
        }

        // Check if this is a distracting app we should track
        if (!cachedDistractingApps.contains(packageName)) {
            return
        }

        // Start or continue session for this app
        if (!activeSessions.containsKey(packageName)) {
            activeSessions[packageName] = AppSession(
                packageName = packageName,
                startTime = eventTime
            )
            Log.d(TAG, "Session started for distracting app: $packageName")
        }
    }

    /**
     * Check session durations and show reminders if thresholds exceeded
     */
    private fun checkSessionDurations() {
        val now = System.currentTimeMillis()
        val currentPackage = lastForegroundPackage ?: return

        // Only check if user is currently on a distracting app
        val session = activeSessions[currentPackage] ?: return

        val sessionDurationMinutes = (now - session.startTime) / 60_000

        Log.v(TAG, "Session check: $currentPackage duration=${sessionDurationMinutes}min, gentle=${session.gentleReminderShown}, firm=${session.firmReminderShown}")

        when {
            // Firm reminder at 60+ minutes (if gentle already shown)
            sessionDurationMinutes >= FIRM_REMINDER_THRESHOLD_MINUTES &&
                    session.gentleReminderShown && !session.firmReminderShown -> {
                showFirmReminder(currentPackage, sessionDurationMinutes.toInt())
                session.firmReminderShown = true
                activeSessions[currentPackage] = session
            }

            // Gentle reminder at 30+ minutes
            sessionDurationMinutes >= GENTLE_REMINDER_THRESHOLD_MINUTES &&
                    !session.gentleReminderShown -> {
                showGentleReminder(currentPackage, sessionDurationMinutes.toInt())
                session.gentleReminderShown = true
                activeSessions[currentPackage] = session
            }
        }
    }

    /**
     * Show gentle supportive reminder notification at 30-min threshold
     */
    private fun showGentleReminder(packageName: String, durationMinutes: Int) {
        Log.i(TAG, "Showing gentle reminder for $packageName after $durationMinutes minutes")

        val appName = AppUtils.getAppName(applicationContext, packageName)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, FocusBlockApp.CHANNEL_MINDFUL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("You've been on $appName for $durationMinutes minutes")
            .setContentText("Take a moment to check in with yourself. Is this how you want to spend your time?")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You've been scrolling for a while. A quick stretch or a glass of water might feel good right now. You're doing great by being mindful about your screen time!"))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(GENTLE_REMINDER_NOTIFICATION_ID, notification)

        // Gentle vibration
        vibrateGently()
    }

    /**
     * Show firm productivity-focused reminder at 60-min threshold
     */
    private fun showFirmReminder(packageName: String, durationMinutes: Int) {
        Log.i(TAG, "Showing firm reminder for $packageName after $durationMinutes minutes")

        val appName = AppUtils.getAppName(applicationContext, packageName)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, FocusBlockApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Extended session: $durationMinutes minutes on $appName")
            .setContentText("This is a long session. Consider taking a break to stay productive.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You've been using $appName for over an hour. Extended screen time can affect your focus and energy. Now might be a good time to step away, take care of something important, or simply rest your eyes."))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(FIRM_REMINDER_NOTIFICATION_ID, notification)

        // Stronger vibration
        vibrateDevice()
    }

    /**
     * Gentle vibration for mindful reminders
     */
    private fun vibrateGently() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Short, gentle double-tap vibration pattern
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate gently", e)
        }
    }

    // ========== APP TIMER (Shared Time Limit) METHODS ==========

    /**
     * Refresh App Timer settings cache from database
     */
    private fun refreshAppTimerCache() {
        immediateScope.launch {
            try {
                val settings = database.appTimerSettingsDao().getSettingsSync()
                if (settings != null) {
                    cachedTimerEnabled = settings.isEnabled
                    cachedTimerLimitMinutes = settings.dailyLimitMinutes
                    cachedTimerEscalationMinutes = settings.escalationThresholdMinutes
                    cachedTimerApps = settings.timerApps
                        .split(",")
                        .filter { it.isNotBlank() }
                        .toSet()
                    Log.d(TAG, "App Timer cache refreshed: enabled=${cachedTimerEnabled}, limit=${cachedTimerLimitMinutes}min, apps=${cachedTimerApps.size}")
                } else {
                    cachedTimerEnabled = false
                    cachedTimerApps = emptySet()
                    Log.d(TAG, "App Timer not configured")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh App Timer cache", e)
            }
        }
    }

    /**
     * Start periodic App Timer usage check
     */
    private fun startAppTimerCheck() {
        stopAppTimerCheck()

        appTimerRunnable = object : Runnable {
            override fun run() {
                checkAppTimerUsage()
                appTimerHandler.postDelayed(this, APP_TIMER_CHECK_INTERVAL_MS)
            }
        }
        appTimerHandler.post(appTimerRunnable!!)
        Log.d(TAG, "App Timer usage monitoring started")
    }

    private fun stopAppTimerCheck() {
        appTimerRunnable?.let {
            appTimerHandler.removeCallbacks(it)
        }
        appTimerRunnable = null
    }

    /**
     * Track timer app session - called on app switch
     */
    private fun trackTimerAppSession(packageName: String, eventTime: Long) {
        if (!cachedTimerEnabled || cachedTimerApps.isEmpty()) return

        val wasOnTimerApp = lastForegroundPackage?.let { cachedTimerApps.contains(it) } ?: false
        val isNowOnTimerApp = cachedTimerApps.contains(packageName)

        when {
            // Switched TO a timer app
            isNowOnTimerApp && !wasOnTimerApp -> {
                currentTimerAppStartTime = eventTime
                Log.d(TAG, "App Timer: Started session on timer app $packageName")
            }
            // Switched FROM a timer app to non-timer app
            wasOnTimerApp && !isNowOnTimerApp -> {
                currentTimerAppStartTime = null
                Log.d(TAG, "App Timer: Ended session on timer app (switched to $packageName)")
            }
            // Still on timer app (different timer app) - keep the session time
            isNowOnTimerApp && wasOnTimerApp && lastForegroundPackage != packageName -> {
                // Keep currentTimerAppStartTime as-is
                Log.d(TAG, "App Timer: Switched between timer apps $packageName")
            }
        }
    }

    /**
     * Check App Timer usage and show reflection popup if limit exceeded
     */
    private fun checkAppTimerUsage() {
        if (!cachedTimerEnabled || cachedTimerApps.isEmpty()) return

        immediateScope.launch {
            try {
                // Get today's date
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())

                // Get current total usage from UsageStats
                val totalUsageMinutes = calculateTimerAppsUsage()

                // Update daily usage in database
                var dailyUsage = database.appTimerDailyUsageDao().getUsageForDateSync(today)
                if (dailyUsage == null) {
                    dailyUsage = com.focusblock.app.database.entity.AppTimerDailyUsage(
                        date = today,
                        totalUsageMinutes = totalUsageMinutes
                    )
                    database.appTimerDailyUsageDao().insert(dailyUsage)
                } else {
                    database.appTimerDailyUsageDao().updateUsage(today, totalUsageMinutes)
                    dailyUsage = dailyUsage.copy(totalUsageMinutes = totalUsageMinutes)
                }

                lastAppTimerUsageMinutes = totalUsageMinutes

                // Check if we need to show popups
                val limitMinutes = cachedTimerLimitMinutes
                val escalationMinutes = limitMinutes + cachedTimerEscalationMinutes

                // Check if user is currently on a timer app
                val currentPackage = lastForegroundPackage
                val isOnTimerApp = currentPackage != null && cachedTimerApps.contains(currentPackage)

                when {
                    // Escalation popup (limit + extra time exceeded)
                    totalUsageMinutes >= escalationMinutes && !dailyUsage.escalationPopupShown && isOnTimerApp -> {
                        Log.i(TAG, "App Timer: Escalation threshold reached ($totalUsageMinutes >= $escalationMinutes min)")
                        database.appTimerDailyUsageDao().markEscalationPopupShown(today)
                        mainHandler.post {
                            showAppTimerReflection(totalUsageMinutes, limitMinutes, isEscalation = true)
                        }
                    }
                    // First limit popup
                    totalUsageMinutes >= limitMinutes && !dailyUsage.limitReachedPopupShown && isOnTimerApp -> {
                        Log.i(TAG, "App Timer: Limit reached ($totalUsageMinutes >= $limitMinutes min)")
                        database.appTimerDailyUsageDao().markLimitPopupShown(today)
                        mainHandler.post {
                            showAppTimerReflection(totalUsageMinutes, limitMinutes, isEscalation = false)
                        }
                    }
                }

                Log.v(TAG, "App Timer check: usage=$totalUsageMinutes min, limit=$limitMinutes min, onTimerApp=$isOnTimerApp")

            } catch (e: Exception) {
                Log.e(TAG, "Error checking App Timer usage", e)
            }
        }
    }

    /**
     * Calculate total usage of timer apps today using UsageEvents API
     * This is more accurate than queryUsageStats as it tracks individual
     * foreground/background events and properly resets at midnight
     */
    private fun calculateTimerAppsUsage(): Int {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            // Get today's start time (midnight)
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            // Query events for accurate tracking
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            // Track foreground start times for each app
            val activeApps = mutableMapOf<String, Long>()
            val appUsageMillis = mutableMapOf<String, Long>()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val packageName = event.packageName ?: continue

                // Only track timer apps
                if (!cachedTimerApps.contains(packageName)) continue

                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // App came to foreground - record start time
                        activeApps[packageName] = event.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // App went to background - calculate duration
                        val foregroundStart = activeApps.remove(packageName)
                        if (foregroundStart != null && foregroundStart < event.timeStamp) {
                            val duration = event.timeStamp - foregroundStart
                            // Only count reasonable sessions (< 4 hours continuous)
                            if (duration < 4 * 60 * 60 * 1000) {
                                appUsageMillis[packageName] = (appUsageMillis[packageName] ?: 0L) + duration
                            }
                        }
                    }
                }
            }

            // Add time for apps still in foreground (currently active)
            val now = System.currentTimeMillis()
            for ((packageName, foregroundStart) in activeApps) {
                val duration = now - foregroundStart
                if (duration > 0 && duration < 4 * 60 * 60 * 1000) {
                    appUsageMillis[packageName] = (appUsageMillis[packageName] ?: 0L) + duration
                }
            }

            // Sum up total usage across all timer apps
            val totalMillis = appUsageMillis.values.sum()
            val totalMinutes = (totalMillis / 60_000).toInt()

            Log.d(TAG, "App Timer usage calculated: ${totalMinutes}m (apps: ${appUsageMillis.keys.joinToString()})")

            return totalMinutes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate timer apps usage", e)
            return 0
        }
    }

    /**
     * Show the App Timer reflection full-screen popup
     */
    private fun showAppTimerReflection(totalUsageMinutes: Int, limitMinutes: Int, isEscalation: Boolean) {
        Log.i(TAG, "Showing App Timer reflection popup: usage=$totalUsageMinutes, limit=$limitMinutes, escalation=$isEscalation")

        // Vibrate to get attention
        vibrateDevice()

        try {
            val timerApps = cachedTimerApps.joinToString(",")
            val intent = AppTimerReflectionActivity.createIntent(
                context = applicationContext,
                totalUsageMinutes = totalUsageMinutes,
                limitMinutes = limitMinutes,
                isEscalation = isEscalation,
                timerApps = timerApps
            )
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show App Timer reflection", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val eventTime = System.currentTimeMillis()

        // Don't block our own app or system components
        if (shouldIgnorePackage(packageName)) return

        // Log for instant trigger verification
        Log.d(TAG, "cycleTriggeredOnAppOpen($packageName, $eventTime)")

        // ========== MINDFUL SESSION TRACKING ==========
        // Track app sessions for gentle/firm reminders
        trackAppSession(packageName, eventTime)

        // ========== APP TIMER TRACKING ==========
        // Track timer app sessions for shared time limit
        trackTimerAppSession(packageName, eventTime)

        // ========== INSTANT FOCUS CYCLE HANDLING ==========
        // Use cached state for immediate response, then persist async
        handleFocusCycleInstant(packageName, eventTime)

        // Refresh cache periodically in background
        if (eventTime - lastCacheRefresh > CACHE_REFRESH_INTERVAL_MS) {
            refreshFocusCycleCache()
            refreshDistractingAppsCache() // Also refresh distracting apps list
            refreshAppTimerCache() // Also refresh app timer settings
        }

        // Don't process blocking if we're already blocking
        if (isBlockingInProgress) {
            Log.d(TAG, "Block already in progress, ignoring: $packageName")
            return
        }

        // Debounce blocking
        if (packageName == lastBlockedPackage &&
            eventTime - lastBlockTime < BLOCK_COOLDOWN) {
            Log.d(TAG, "Cooldown active for: $packageName")
            return
        }

        serviceScope.launch {
            if (shouldBlockApp(packageName)) {
                Log.i(TAG, "Blocking app detected: $packageName at $eventTime")
                lastBlockedPackage = packageName
                lastBlockTime = eventTime
                blockApp(packageName)
            }
        }

        lastForegroundPackage = packageName
    }

    /**
     * Handle Focus Cycle state transitions INSTANTLY using cached state
     * Database updates happen asynchronously
     */
    private fun handleFocusCycleInstant(packageName: String, eventTime: Long) {
        val cycle = cachedFocusCycle ?: return
        if (!cycle.isEnabled) return

        val packages = cachedFocusCyclePackages
        if (packages.isEmpty()) {
            Log.w(TAG, "Focus Cycle has no apps to track!")
            return
        }

        val isSelectedApp = packages.contains(packageName)
        Log.d(TAG, "Focus Cycle INSTANT: package=$packageName, isSelectedApp=$isSelectedApp, isArmed=${cycle.isArmed}, isPaused=${cycle.isPaused}, time=$eventTime")

        when {
            // Case 1: Cycle is armed (waiting for first app open)
            cycle.isArmed -> {
                if (isSelectedApp) {
                    // INSTANT START - User opened a selected app
                    Log.i(TAG, "Focus Cycle: INSTANT START - user opened $packageName at $eventTime")

                    val updatedCycle = cycle.copy(
                        isArmed = false,
                        isPaused = false,
                        cycleStartTime = eventTime,
                        lastActiveTime = eventTime,
                        accumulatedUsageMillis = 0,
                        breakStartTime = null
                    )

                    // Update cache immediately for instant UI response
                    cachedFocusCycle = updatedCycle

                    // Persist to database async
                    persistFocusCycleUpdate(updatedCycle)

                    // Update notification/overlay immediately
                    updateFocusCycleNotification(updatedCycle)

                    // Show floating overlay when on tracked app
                    FocusCycleOverlayService.show(applicationContext)

                    // Show visible feedback
                    mainHandler.post {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "Focus Cycle started! Timer running.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // Case 2: In break period - check if break is over
            cycle.breakStartTime != null -> {
                val breakEnd = cycle.breakStartTime + timeToMillis(cycle.breakDurationMinutes)
                if (eventTime >= breakEnd) {
                    // Break is over - re-arm the cycle
                    Log.i(TAG, "Focus Cycle: Break ended - re-arming cycle at $eventTime")
                    val updatedCycle = cycle.copy(
                        isArmed = true,
                        isPaused = false,
                        cycleStartTime = null,
                        breakStartTime = null,
                        accumulatedUsageMillis = 0,
                        lastActiveTime = null
                    )
                    cachedFocusCycle = updatedCycle
                    persistFocusCycleUpdate(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)
                }
            }

            // Case 3: In usage window
            cycle.cycleStartTime != null -> {
                // Calculate current accumulated time
                val previousAccumulated = cycle.accumulatedUsageMillis
                val lastActive = cycle.lastActiveTime ?: cycle.cycleStartTime!!

                // Add time if we were on a selected app
                val wasOnSelectedApp = lastForegroundPackage?.let { packages.contains(it) } ?: false
                val additionalTime = if (wasOnSelectedApp && !cycle.isPaused) {
                    eventTime - lastActive
                } else {
                    0L
                }
                val totalAccumulated = previousAccumulated + additionalTime
                val usageWindowMillis = timeToMillis(cycle.usageWindowMinutes)

                // Check if usage window is exhausted
                if (totalAccumulated >= usageWindowMillis) {
                    // Start break period
                    Log.i(TAG, "Focus Cycle: Usage window exhausted - starting break at $eventTime")
                    val updatedCycle = cycle.copy(
                        isPaused = false,
                        breakStartTime = eventTime,
                        accumulatedUsageMillis = totalAccumulated,
                        lastActiveTime = eventTime
                    )
                    cachedFocusCycle = updatedCycle
                    persistFocusCycleUpdate(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)

                    // Hide overlay during break
                    FocusCycleOverlayService.hide(applicationContext)
                } else if (isSelectedApp) {
                    // INSTANT RESUME - User is on a selected app
                    if (cycle.isPaused) {
                        Log.d(TAG, "Focus Cycle: INSTANT RESUME - user returned to $packageName at $eventTime")
                    }
                    val updatedCycle = cycle.copy(
                        isPaused = false,
                        accumulatedUsageMillis = totalAccumulated,
                        lastActiveTime = eventTime
                    )
                    cachedFocusCycle = updatedCycle
                    persistFocusCycleUpdate(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)

                    // Show overlay when on tracked app
                    FocusCycleOverlayService.show(applicationContext)
                } else {
                    // INSTANT PAUSE - User switched to non-selected app
                    if (!cycle.isPaused) {
                        Log.d(TAG, "Focus Cycle: INSTANT PAUSE - user left to $packageName at $eventTime")
                    }
                    val updatedCycle = cycle.copy(
                        isPaused = true,
                        accumulatedUsageMillis = totalAccumulated,
                        lastActiveTime = eventTime
                    )
                    cachedFocusCycle = updatedCycle
                    persistFocusCycleUpdate(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)

                    // Hide overlay when on non-tracked app
                    FocusCycleOverlayService.hide(applicationContext)
                }
            }
        }
    }

    /**
     * Persist Focus Cycle update to database asynchronously
     * This doesn't block the main flow
     */
    private fun persistFocusCycleUpdate(cycle: FocusCycle) {
        immediateScope.launch {
            try {
                database.focusCycleDao().update(cycle)
                Log.d(TAG, "Focus Cycle persisted to database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist Focus Cycle", e)
            }
        }
    }

    /**
     * Update the persistent Focus Cycle notification
     */
    private fun updateFocusCycleNotification(cycle: FocusCycle) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!cycle.isEnabled) {
            notificationManager.cancel(FOCUS_CYCLE_NOTIFICATION_ID)
            return
        }

        val now = System.currentTimeMillis()
        val (title, content, progress) = when {
            cycle.isArmed -> {
                Triple(
                    "Focus Cycle - Ready",
                    "Open a tracked app to start your usage window",
                    -1 // No progress bar
                )
            }
            cycle.breakStartTime != null -> {
                val breakDurationMillis = timeToMillis(cycle.breakDurationMinutes)
                val breakEnd = cycle.breakStartTime + breakDurationMillis
                val remainingMillis = maxOf(0L, breakEnd - now)
                val remainingText = formatRemainingTime(remainingMillis)
                Triple(
                    "Break Time",
                    "$remainingText remaining before apps unlock",
                    ((now - cycle.breakStartTime) * 100 / breakDurationMillis).toInt()
                )
            }
            cycle.cycleStartTime != null -> {
                val usageWindowMillis = timeToMillis(cycle.usageWindowMinutes)
                // LIVE timestamp calculation - compute elapsed from timestamps
                val elapsedMillis = computeElapsedUsageMillis(cycle, now)
                val remainingMillis = maxOf(0L, usageWindowMillis - elapsedMillis)
                val remainingText = formatRemainingTime(remainingMillis)
                val pausedText = if (cycle.isPaused) " (Paused)" else ""
                Triple(
                    "Focus Cycle - Usage Window$pausedText",
                    "$remainingText remaining in usage window",
                    ((elapsedMillis * 100) / usageWindowMillis).toInt()
                )
            }
            else -> return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, FocusBlockApp.CHANNEL_MINDFUL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)

        if (progress >= 0) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }

        notificationManager.notify(FOCUS_CYCLE_NOTIFICATION_ID, builder.build())
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopQuickBlockTimerCheck() // Clean up Quick Block timer
        stopSessionDurationCheck() // Clean up session duration timer
        stopAppTimerCheck() // Clean up App Timer check
        activeSessions.clear() // Clear session tracking
        serviceScope.cancel()
        immediateScope.cancel()
        super.onDestroy()
    }

    private fun shouldIgnorePackage(packageName: String): Boolean {
        return packageName == this.packageName ||
                packageName == "com.android.systemui" ||
                packageName.startsWith("com.android.launcher") ||
                packageName.startsWith("com.sec.android.app.launcher") ||
                packageName.startsWith("com.google.android.apps.nexuslauncher") ||
                packageName == "com.android.settings" ||
                packageName.startsWith("com.samsung.android.app.routines")
    }

    private suspend fun shouldBlockApp(packageName: String): Boolean {
        val blockedAppDao = database.blockedAppDao()
        val scheduleDao = database.scheduleDao()
        val quickBlockSessionDao = database.quickBlockSessionDao()
        val settingsDao = database.settingsDao()
        val focusCycleDao = database.focusCycleDao()

        // Check if in allowlist
        val blockedApp = blockedAppDao.getBlockedApp(packageName)
        if (blockedApp?.isInAllowlist == true) {
            return false
        }

        // ============ MODE PRIORITY RULES ============
        // Priority: Strict Mode > Focus Cycles > Quick Block
        val strictModeEnabled = settingsDao.getValue("strict_mode_enabled")?.toBooleanStrictOrNull() ?: false

        // Check Quick Block session
        val quickBlockSession = quickBlockSessionDao.getActiveSessionSync()
        if (quickBlockSession != null) {
            val blockedPackages = quickBlockSession.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                val now = System.currentTimeMillis()

                // For Pomodoro/Focus sessions with a timer:
                // - During focus period (before endTime): apps are ALLOWED
                // - After focus period ends (timer expired): apps are BLOCKED (break time)
                if (quickBlockSession.isPomodoroSession && quickBlockSession.endTime != null) {
                    // Focus/work period - apps are allowed until timer ends
                    if (now < quickBlockSession.endTime) {
                        Log.d(TAG, "Focus period active - allowing $packageName (${(quickBlockSession.endTime - now)/1000}s remaining)")
                        return false // Allow during focus period
                    }
                    // Timer expired - should block (break period)
                    Log.d(TAG, "Focus period ended - blocking $packageName for break")
                    return true
                }

                // Non-Pomodoro Quick Block or break period - block normally
                return true
            }
        }

        // Check Focus Cycles (soft-nudge, only blocks during break phase)
        if (!strictModeEnabled) {
            val activeFocusCycle = focusCycleDao.getActiveFocusCycleSync()
            if (activeFocusCycle != null && activeFocusCycle.isEnabled) {
                val now = System.currentTimeMillis()
                val breakStart = activeFocusCycle.breakStartTime
                val cycleStart = activeFocusCycle.cycleStartTime

                // Check if we're in break phase
                val isInBreak = if (breakStart != null) {
                    val breakEnd = breakStart + timeToMillis(activeFocusCycle.breakDurationMinutes)
                    now < breakEnd
                } else if (cycleStart != null) {
                    val usageEnd = cycleStart + timeToMillis(activeFocusCycle.usageWindowMinutes)
                    now >= usageEnd
                } else {
                    false
                }

                if (isInBreak) {
                    val focusCyclePackages = activeFocusCycle.selectedPackages
                        .split(",")
                        .filter { it.isNotBlank() }

                    if (focusCyclePackages.contains(packageName)) {
                        Log.d(TAG, "Focus Cycle blocking (soft-nudge): $packageName")
                        return true
                    }
                }
            }
        }

        // Check schedules
        val currentMinute = TimeUtils.getCurrentMinuteOfDay()
        val dayOfWeek = TimeUtils.getCurrentDayOfWeek()
        val activeSchedules = scheduleDao.getActiveSchedules(currentMinute, dayOfWeek)

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
        isBlockingInProgress = true
        Log.d(TAG, "blockApp() called for: $packageName")

        try {
            val appName = AppUtils.getAppName(this, packageName)
            val blockedByType = determineBlockedByType(packageName)
            Log.d(TAG, "Block type: $blockedByType")

            // Log the block
            database.blockLogDao().insert(
                BlockLog(
                    packageName = packageName,
                    appName = appName,
                    blockedBy = blockedByType
                )
            )

            // Update block count
            database.blockedAppDao().incrementBlockCount(packageName)

            // Vibrate to give feedback
            vibrateDevice()

            // Go to home screen first
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Performing GLOBAL_ACTION_HOME")
                val homeSuccess = performGlobalAction(GLOBAL_ACTION_HOME)
                Log.d(TAG, "GLOBAL_ACTION_HOME result: $homeSuccess")
            }

            delay(150)

            // Show blocking screen
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Launching BlockedAppActivity for: $appName")
                showBlockingScreen(packageName, appName, blockedByType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error blocking app: $packageName", e)
        } finally {
            delay(500)
            isBlockingInProgress = false
        }
    }

    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private suspend fun determineBlockedByType(packageName: String): BlockedByType {
        val settingsDao = database.settingsDao()
        val quickBlockSessionDao = database.quickBlockSessionDao()
        val focusCycleDao = database.focusCycleDao()

        val hardModeEnabled = settingsDao.getValue("hard_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (hardModeEnabled) {
            return BlockedByType.HARD_MODE
        }

        val strictModeEnabled = settingsDao.getValue("strict_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (strictModeEnabled) {
            return BlockedByType.STRICT_MODE
        }

        val activeFocusCycle = focusCycleDao.getActiveFocusCycleSync()
        if (activeFocusCycle != null && activeFocusCycle.isEnabled) {
            val focusCyclePackages = activeFocusCycle.selectedPackages
                .split(",")
                .filter { it.isNotBlank() }

            if (focusCyclePackages.contains(packageName)) {
                val now = System.currentTimeMillis()
                val breakStart = activeFocusCycle.breakStartTime
                val cycleStart = activeFocusCycle.cycleStartTime

                val isInBreak = if (breakStart != null) {
                    val breakEnd = breakStart + timeToMillis(activeFocusCycle.breakDurationMinutes)
                    now < breakEnd
                } else if (cycleStart != null) {
                    val usageEnd = cycleStart + timeToMillis(activeFocusCycle.usageWindowMinutes)
                    now >= usageEnd
                } else {
                    false
                }

                if (isInBreak) {
                    return BlockedByType.FOCUS_CYCLE
                }
            }
        }

        val quickBlockSession = quickBlockSessionDao.getActiveSessionSync()
        if (quickBlockSession != null) {
            val blockedPackages = quickBlockSession.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                return BlockedByType.QUICK_BLOCK
            }
        }

        return BlockedByType.SCHEDULE
    }

    private fun showBlockingScreen(packageName: String, appName: String, blockedByType: BlockedByType) {
        try {
            val intent = Intent(this, BlockedAppActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(BlockedAppActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(BlockedAppActivity.EXTRA_APP_NAME, appName)
                putExtra(BlockedAppActivity.EXTRA_BLOCKED_BY, blockedByType.name)
            }
            Log.d(TAG, "Starting BlockedAppActivity with intent: $intent")
            startActivity(intent)
            Log.i(TAG, "BlockedAppActivity started successfully for: $appName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BlockedAppActivity", e)
        }
    }
}
