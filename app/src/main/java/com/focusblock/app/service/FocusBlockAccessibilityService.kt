package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.entity.BlockedApp
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
        private const val GENTLE_REMINDER_THRESHOLD_MINUTES = 20 // First gentle nudge at 20 min
        private const val FIRM_REMINDER_THRESHOLD_MINUTES = 40 // Firm reminder at 40 min
        private const val SESSION_CHECK_INTERVAL_MS = 60_000L // Check every minute
        private const val GENTLE_REMINDER_NOTIFICATION_ID = 4001
        private const val FIRM_REMINDER_NOTIFICATION_ID = 4002

        // ========== APP TIMER (Shared Time Limit) ==========
        private const val APP_TIMER_CHECK_INTERVAL_MS = 10_000L // Check every 10 seconds for responsive tracking
        private const val APP_TIMER_NOTIFICATION_ID = 4003
        const val ACTION_EXTEND_APP_TIMER = "com.focusblock.app.ACTION_EXTEND_APP_TIMER"
        const val ACTION_REFRESH_GLOBAL_LIMIT_CACHE = "com.focusblock.app.ACTION_REFRESH_GLOBAL_LIMIT_CACHE"

        // ========== SCHEDULE ENFORCEMENT ==========
        private const val SCHEDULE_CHECK_INTERVAL_MS = 60_000L // Check every minute

        // ========== GLOBAL DAILY USAGE LIMIT ==========
        private const val GLOBAL_LIMIT_CHECK_INTERVAL_MS = 30_000L // Check every 30 seconds
        private const val GLOBAL_LIMIT_WARNING_NOTIFICATION_ID = 4004
        private const val GLOBAL_LIMIT_REACHED_NOTIFICATION_ID = 4005
        private const val OVERRIDE_DURATION_MS = 5 * 60 * 1000L // 5 minutes emergency override
        private const val OVERRIDE_COOLDOWN_MS = 15 * 60 * 1000L // 15 minutes between overrides

        // ========== DAILY USAGE COMPARISON (TODAY VS YESTERDAY) ==========
        private const val USAGE_COMPARISON_CHECK_INTERVAL_MS = 5 * 60 * 1000L // Check every 5 minutes
        private const val USAGE_COMPARISON_NOTIFICATION_ID = 4006
        private const val MEANINGFUL_USAGE_THRESHOLD_MINUTES = 30 // Min usage to compare
        private const val SIGNIFICANT_DIFFERENCE_PERCENT = 15 // 15% difference to trigger notification

        // ========== EXCESSIVE USAGE NOTIFICATION (3+ HOURS) ==========
        private const val EXCESSIVE_USAGE_THRESHOLD_MINUTES = 180 // 3 hours
        private const val EXCESSIVE_USAGE_NOTIFICATION_ID = 4007

        // ========== AUTO-BLOCK ON EXCESS SOCIAL MEDIA USAGE ==========
        private const val AUTO_BLOCK_NOTIFICATION_ID = 4008
        // Social media apps that should be auto-blocked when exceeding yesterday's usage
        // Note: WhatsApp and Telegram excluded (work apps)
        private val SOCIAL_MEDIA_PACKAGES = setOf(
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.orca", // Messenger
            "com.twitter.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.ss.android.ugc.trill", // TikTok (alternate)
            "com.snapchat.android",
            "com.reddit.frontpage",
            "com.pinterest",
            "com.tumblr",
            "com.linkedin.android",
            "com.discord",
            "com.viber.voip",
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "tv.twitch.android.app"
        )

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
    // FIXED: Only tracks ACTIVE foreground usage, not idle/lock screen time
    private data class AppSession(
        val packageName: String,
        val startTime: Long,
        var lastActiveTime: Long = startTime, // Last time we confirmed user activity
        var accumulatedActiveMillis: Long = 0, // Accumulated active time (for pause/resume)
        var gentleReminderShown: Boolean = false,
        var firmReminderShown: Boolean = false
    )
    private val activeSessions = ConcurrentHashMap<String, AppSession>()
    private val sessionCheckHandler = Handler(Looper.getMainLooper())
    private var sessionCheckRunnable: Runnable? = null
    private var cachedDistractingApps: Set<String> = emptySet() // Apps that are typically blocked/tracked

    // Session detection debounce - prevent micro-events from resetting session
    private var lastAppSwitchTime: Long = 0
    private var pendingSessionEnd: String? = null // Package that might end session (with debounce)
    private val SESSION_SWITCH_DEBOUNCE_MS = 3000L // 3 seconds debounce for app switches
    private val SESSION_IDLE_TIMEOUT_MS = 60_000L // 1 minute of no activity = session ends

    // ========== AUTO-BLOCK ON EXCESS USAGE ==========
    // Track if auto-blocking has been triggered today (resets at midnight)
    @Volatile private var autoBlockTriggeredDate: String? = null
    @Volatile private var autoBlockedPackages: MutableSet<String> = mutableSetOf()

    // ========== APP TIMER TRACKING ==========
    private val appTimerHandler = Handler(Looper.getMainLooper())
    private var appTimerRunnable: Runnable? = null
    @Volatile private var cachedTimerApps: Set<String> = emptySet()
    @Volatile private var cachedTimerLimitMinutes: Int = 30
    @Volatile private var cachedTimerEscalationMinutes: Int = 20
    @Volatile private var cachedTimerEnabled: Boolean = false
    private var lastAppTimerUsageMinutes: Int = 0
    private var currentTimerAppStartTime: Long? = null // When current timer app session started

    // ========== SCHEDULE ENFORCEMENT ==========
    private val scheduleCheckHandler = Handler(Looper.getMainLooper())
    private var scheduleCheckRunnable: Runnable? = null
    private var lastScheduleCheck: Long = 0L

    // ========== GLOBAL DAILY USAGE LIMIT ==========
    private val globalLimitHandler = Handler(Looper.getMainLooper())
    private var globalLimitRunnable: Runnable? = null
    @Volatile private var cachedGlobalLimitEnabled: Boolean = false
    @Volatile private var cachedGlobalLimitMinutes: Int = 120
    @Volatile private var cachedGlobalLimitWarningMinutes: Int = 15
    // NEW: Whitelist approach - only track these specific apps
    @Volatile private var cachedGlobalLimitTrackedPackages: Set<String> = emptySet()
    @Volatile private var cachedGlobalLimitUseAppTimerApps: Boolean = true
    private var lastGlobalUsageMinutes: Int = 0
    private var globalLimitEnforcementActive: Boolean = false

    // ========== DAILY USAGE COMPARISON ==========
    private val usageComparisonHandler = Handler(Looper.getMainLooper())
    private var usageComparisonRunnable: Runnable? = null

    // ========== SETTINGS CHANGE RECEIVER ==========
    private val settingsChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REFRESH_GLOBAL_LIMIT_CACHE -> {
                    Log.i(TAG, "Received broadcast to refresh Global Limit cache")
                    refreshGlobalLimitCache()
                    // Also trigger an immediate check
                    checkGlobalDailyLimit()
                }
            }
        }
    }

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

        // Start schedule enforcement check
        startScheduleCheck()

        // Start Global Daily Limit monitoring
        refreshGlobalLimitCache()
        startGlobalLimitCheck()

        // Start Daily Usage Comparison check
        startUsageComparisonCheck()

        // Register broadcast receiver for settings changes
        val filter = IntentFilter(ACTION_REFRESH_GLOBAL_LIMIT_CACHE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsChangeReceiver, filter)
        }
        Log.d(TAG, "Settings change receiver registered")

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

                // Determine packages to track
                var packages = cycle?.selectedPackages
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toSet() ?: emptySet()

                // If useQuickBlockApps is true and no explicit packages, use Quick Block apps
                if (packages.isEmpty() && cycle?.useQuickBlockApps == true) {
                    val quickBlockSession = database.quickBlockSessionDao().getActiveSessionSync()
                    packages = quickBlockSession?.blockedPackages
                        ?.split(",")
                        ?.filter { it.isNotBlank() }
                        ?.toSet() ?: emptySet()
                    Log.d(TAG, "Focus Cycle using Quick Block apps: ${packages.size}")
                }

                cachedFocusCyclePackages = packages
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
     * Track app session start/switch with proper continuous-use detection
     * Called when the foreground app changes
     *
     * FIXED: Properly handles:
     * - Debouncing micro-events (brief app switches don't reset session)
     * - Launcher/home screen = session ends
     * - System apps/settings = session pauses, not ends
     * - Idle detection via lastActiveTime
     */
    private fun trackAppSession(packageName: String, eventTime: Long) {
        val now = System.currentTimeMillis()
        val previousPackage = lastForegroundPackage

        // Check if switching to launcher/home screen - this ends ALL sessions
        if (isLauncherOrHome(packageName)) {
            // Clear all active sessions when user goes home
            if (activeSessions.isNotEmpty()) {
                Log.d(TAG, "Session tracking: User went to home screen, clearing ${activeSessions.size} sessions")
                activeSessions.clear()
            }
            pendingSessionEnd = null
            return
        }

        // Check if switching to system settings - pause session but don't end
        if (isSystemApp(packageName)) {
            // Just update last switch time, don't end session yet
            lastAppSwitchTime = now
            Log.v(TAG, "Session tracking: Switched to system app $packageName, sessions paused")
            return
        }

        // End previous session for other apps (with debounce)
        if (previousPackage != null && previousPackage != packageName) {
            val timeSinceLastSwitch = now - lastAppSwitchTime

            // If returning to the same distracting app within debounce window, continue session
            val existingSession = activeSessions[packageName]
            if (existingSession != null && pendingSessionEnd == packageName &&
                timeSinceLastSwitch < SESSION_SWITCH_DEBOUNCE_MS) {
                // User returned quickly - continue the session
                Log.d(TAG, "Session tracking: User returned to $packageName within debounce, continuing session")
                pendingSessionEnd = null
                existingSession.lastActiveTime = now
                activeSessions[packageName] = existingSession
            } else {
                // Different app - end the previous session
                val prevSession = activeSessions.remove(previousPackage)
                if (prevSession != null) {
                    // Calculate final accumulated time for the ended session
                    val finalActiveTime = prevSession.accumulatedActiveMillis +
                        (now - prevSession.lastActiveTime).coerceAtLeast(0)
                    Log.d(TAG, "Session ended for: $previousPackage (total active: ${finalActiveTime/60000}min)")
                }
            }
        }

        // Update switch time
        lastAppSwitchTime = now

        // Check if this is a distracting app we should track
        if (!cachedDistractingApps.contains(packageName)) {
            pendingSessionEnd = null
            return
        }

        // Start or continue session for this distracting app
        val existingSession = activeSessions[packageName]
        if (existingSession != null) {
            // Continue existing session - update last active time
            existingSession.lastActiveTime = now
            activeSessions[packageName] = existingSession
            Log.v(TAG, "Session tracking: Continuing session for $packageName")
        } else {
            // Start new session
            activeSessions[packageName] = AppSession(
                packageName = packageName,
                startTime = now,
                lastActiveTime = now,
                accumulatedActiveMillis = 0
            )
            Log.d(TAG, "Session tracking: Started new session for distracting app: $packageName")
        }

        pendingSessionEnd = null
    }

    /**
     * Check if package is launcher/home screen
     */
    private fun isLauncherOrHome(packageName: String): Boolean {
        return packageName.contains("launcher") ||
               packageName.contains("home") ||
               packageName == "com.google.android.apps.nexuslauncher" ||
               packageName == "com.sec.android.app.launcher" || // Samsung
               packageName == "com.huawei.android.launcher" || // Huawei
               packageName == "com.miui.home" || // Xiaomi
               packageName == "com.oppo.launcher" || // Oppo
               packageName == "com.android.launcher3" ||
               packageName == "com.android.launcher"
    }

    /**
     * Check if package is a system app (settings, systemui, etc.)
     * These pause the session but don't end it
     */
    private fun isSystemApp(packageName: String): Boolean {
        return packageName == "com.android.settings" ||
               packageName == "com.android.systemui" ||
               packageName.startsWith("com.samsung.android.app.") ||
               packageName.startsWith("com.google.android.gms") ||
               packageName.startsWith("com.android.vending") || // Play Store
               packageName == "com.android.packageinstaller" ||
               packageName == "com.google.android.packageinstaller"
    }

    /**
     * Check session durations and show reminders if thresholds exceeded
     *
     * FIXED: Uses accumulated ACTIVE time, not raw elapsed time since start.
     * This prevents false triggers when:
     * - Screen is locked/off
     * - User is idle (no interaction events)
     * - User briefly switched to another app
     */
    private fun checkSessionDurations() {
        val now = System.currentTimeMillis()
        val currentPackage = lastForegroundPackage ?: return

        // Check if screen is off/locked - clear all sessions
        if (isScreenOff()) {
            if (activeSessions.isNotEmpty()) {
                Log.d(TAG, "Session check: Screen is off, clearing ${activeSessions.size} sessions")
                activeSessions.clear()
            }
            return
        }

        // Only check if user is currently on a distracting app
        val session = activeSessions[currentPackage] ?: return

        // Check for idle timeout - if no activity update for too long, end session
        val timeSinceLastActive = now - session.lastActiveTime
        if (timeSinceLastActive > SESSION_IDLE_TIMEOUT_MS) {
            Log.d(TAG, "Session check: Idle timeout for $currentPackage (${timeSinceLastActive/1000}s since last activity)")
            activeSessions.remove(currentPackage)
            return
        }

        // Calculate ACTUAL active usage time
        // = accumulated time from previous periods + current active period
        val currentPeriodTime = (now - session.lastActiveTime).coerceIn(0, SESSION_IDLE_TIMEOUT_MS)
        val totalActiveMillis = session.accumulatedActiveMillis + currentPeriodTime
        val sessionDurationMinutes = totalActiveMillis / 60_000

        // Update session with current accumulated time (for next check)
        session.accumulatedActiveMillis = totalActiveMillis
        session.lastActiveTime = now
        activeSessions[currentPackage] = session

        Log.v(TAG, "Session check: $currentPackage activeTime=${sessionDurationMinutes}min, gentle=${session.gentleReminderShown}, firm=${session.firmReminderShown}")

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
     * Check if screen is off or locked
     */
    private fun isScreenOff(): Boolean {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return !powerManager.isInteractive
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check screen state", e)
            return false
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

                // Immediately show notification with current usage
                val appName = try {
                    val pm = packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (e: Exception) {
                    null
                }
                showAppTimerNotification(lastAppTimerUsageMinutes, cachedTimerLimitMinutes, appName)
            }
            // Switched FROM a timer app to non-timer app
            wasOnTimerApp && !isNowOnTimerApp -> {
                currentTimerAppStartTime = null
                Log.d(TAG, "App Timer: Ended session on timer app (switched to $packageName)")

                // Immediately dismiss notification
                dismissAppTimerNotification()
            }
            // Still on timer app (different timer app) - keep the session time
            isNowOnTimerApp && wasOnTimerApp && lastForegroundPackage != packageName -> {
                // Keep currentTimerAppStartTime as-is
                Log.d(TAG, "App Timer: Switched between timer apps $packageName")

                // Update notification with new app name
                val appName = try {
                    val pm = packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (e: Exception) {
                    null
                }
                showAppTimerNotification(lastAppTimerUsageMinutes, cachedTimerLimitMinutes, appName)
            }
        }
    }

    /**
     * Check App Timer usage and enforce limit
     *
     * INTEGRATION WITH FOCUS CYCLE:
     * If the current app is in both App Timer AND Focus Cycle, when App Timer limit
     * is reached, we trigger Focus Cycle break instead of showing a separate popup.
     * This makes the systems work together: App Timer triggers Focus Cycle enforcement.
     *
     * Priority: Focus Cycle enforcement > App Timer standalone
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

                // Check if we need to trigger enforcement
                val limitMinutes = cachedTimerLimitMinutes
                val escalationMinutes = limitMinutes + cachedTimerEscalationMinutes

                // Check if user is currently on a timer app
                val currentPackage = lastForegroundPackage
                val isOnTimerApp = currentPackage != null && cachedTimerApps.contains(currentPackage)

                // FOCUS CYCLE INTEGRATION:
                // Check if the current app is also in an active Focus Cycle
                val activeFocusCycle = cachedFocusCycle
                val focusCyclePackages = cachedFocusCyclePackages
                val isInFocusCycle = currentPackage != null &&
                    activeFocusCycle != null &&
                    activeFocusCycle.isEnabled &&
                    focusCyclePackages.contains(currentPackage)

                // When limit reached, check Focus Cycle integration
                if (totalUsageMinutes >= limitMinutes && isOnTimerApp) {
                    if (isInFocusCycle && activeFocusCycle != null) {
                        // INTEGRATION: Trigger Focus Cycle break instead of separate popup
                        val isAlreadyInBreak = activeFocusCycle.breakStartTime != null
                        if (!isAlreadyInBreak) {
                            Log.i(TAG, "App Timer + Focus Cycle: Triggering break for $currentPackage (usage: $totalUsageMinutes >= limit: $limitMinutes)")
                            // Force Focus Cycle into break phase
                            val now = System.currentTimeMillis()
                            database.focusCycleDao().update(
                                activeFocusCycle.copy(
                                    breakStartTime = now,
                                    accumulatedUsageMillis = 0,
                                    lastActiveTime = null,
                                    isPaused = false
                                )
                            )
                            // Update cache immediately
                            cachedFocusCycle = cachedFocusCycle?.copy(
                                breakStartTime = now,
                                accumulatedUsageMillis = 0,
                                lastActiveTime = null,
                                isPaused = false
                            )
                            // Mark popup shown to prevent duplicate triggers
                            if (!dailyUsage.limitReachedPopupShown) {
                                database.appTimerDailyUsageDao().markLimitPopupShown(today)
                            }
                        }
                    } else {
                        // No Focus Cycle - show standalone popups
                        when {
                            // Escalation popup (limit + extra time exceeded)
                            totalUsageMinutes >= escalationMinutes && !dailyUsage.escalationPopupShown -> {
                                Log.i(TAG, "App Timer: Escalation threshold reached ($totalUsageMinutes >= $escalationMinutes min)")
                                database.appTimerDailyUsageDao().markEscalationPopupShown(today)
                                mainHandler.post {
                                    showAppTimerReflection(totalUsageMinutes, limitMinutes, isEscalation = true)
                                }
                            }
                            // First limit popup
                            !dailyUsage.limitReachedPopupShown -> {
                                Log.i(TAG, "App Timer: Limit reached ($totalUsageMinutes >= $limitMinutes min)")
                                database.appTimerDailyUsageDao().markLimitPopupShown(today)
                                mainHandler.post {
                                    showAppTimerReflection(totalUsageMinutes, limitMinutes, isEscalation = false)
                                }
                            }
                        }
                    }
                }

                Log.v(TAG, "App Timer check: usage=$totalUsageMinutes min, limit=$limitMinutes min, onTimerApp=$isOnTimerApp, inFocusCycle=$isInFocusCycle")

                // PROACTIVE ENFORCEMENT: When limit is reached and on timer app, BLOCK immediately
                // This ensures the app is blocked even if no new accessibility events are triggered
                if (totalUsageMinutes >= limitMinutes && isOnTimerApp && currentPackage != null) {
                    // Check if override is active
                    val overrideExpires = dailyUsage.overrideExpiresAt
                    val now = System.currentTimeMillis()
                    val hasActiveOverride = overrideExpires != null && now < overrideExpires

                    if (!hasActiveOverride && !isBlockingInProgress) {
                        Log.i(TAG, "App Timer: Proactive enforcement - blocking $currentPackage (usage: $totalUsageMinutes >= limit: $limitMinutes)")
                        // Block the app using the service scope (blockApp is a suspend function)
                        blockApp(currentPackage)
                    }
                }

                // Show/hide App Timer notification based on current state
                if (isOnTimerApp) {
                    // Get app name for notification
                    val appName = try {
                        val pm = packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(currentPackage!!, 0)).toString()
                    } catch (e: Exception) {
                        null
                    }

                    mainHandler.post {
                        showAppTimerNotification(totalUsageMinutes, limitMinutes, appName)
                    }
                } else {
                    // Not on a timer app - dismiss notification
                    mainHandler.post {
                        dismissAppTimerNotification()
                    }
                }

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

            // IMPORTANT: Supplement with our own real-time tracking
            // The UsageEvents API may not update in real-time during continuous app usage
            // (e.g., scrolling in the same activity without opening new screens)
            // Use our accessibility-tracked currentTimerAppStartTime as a supplement
            val currentForeground = lastForegroundPackage
            val sessionStart = currentTimerAppStartTime
            if (currentForeground != null && sessionStart != null && cachedTimerApps.contains(currentForeground)) {
                val accessibilityTrackedSession = now - sessionStart
                val usageEventsTracked = appUsageMillis[currentForeground] ?: 0L

                // If our accessibility-based tracking shows more time than UsageEvents,
                // use our tracking as it's more real-time
                if (accessibilityTrackedSession > usageEventsTracked) {
                    val additionalTime = accessibilityTrackedSession - usageEventsTracked
                    if (additionalTime > 0 && additionalTime < 4 * 60 * 60 * 1000) {
                        appUsageMillis[currentForeground] = usageEventsTracked + additionalTime
                        Log.d(TAG, "App Timer: Supplemented with accessibility tracking (+${additionalTime/1000}s for $currentForeground)")
                    }
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

    // ========== SCHEDULE ENFORCEMENT METHODS ==========

    /**
     * Start periodic schedule enforcement check.
     * This ensures schedules are enforced even if the user is already in an app
     * when the schedule becomes active.
     */
    private fun startScheduleCheck() {
        stopScheduleCheck()

        scheduleCheckRunnable = object : Runnable {
            override fun run() {
                checkScheduleEnforcement()
                scheduleCheckHandler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS)
            }
        }
        scheduleCheckHandler.post(scheduleCheckRunnable!!)
        Log.d(TAG, "Schedule enforcement check started")
    }

    private fun stopScheduleCheck() {
        scheduleCheckRunnable?.let {
            scheduleCheckHandler.removeCallbacks(it)
        }
        scheduleCheckRunnable = null
    }

    /**
     * Periodically check if the current foreground app should be blocked due to an active schedule.
     * This catches cases where the user was already in an app when a schedule became active.
     */
    private fun checkScheduleEnforcement() {
        val currentPackage = lastForegroundPackage ?: return
        val now = System.currentTimeMillis()

        // Don't check too frequently (redundant with event-based checking)
        if (now - lastScheduleCheck < 30_000L) return
        lastScheduleCheck = now

        // Don't block our own app or system components
        if (shouldIgnorePackage(currentPackage)) return

        // Check if blocking is already in progress
        if (isBlockingInProgress) return

        immediateScope.launch {
            try {
                val scheduleDao = database.scheduleDao()
                val blockedAppDao = database.blockedAppDao()

                // Check if in allowlist
                val blockedApp = blockedAppDao.getBlockedApp(currentPackage)
                if (blockedApp?.isInAllowlist == true) return@launch

                // Check schedules
                val currentMinute = TimeUtils.getCurrentMinuteOfDay()
                val dayOfWeek = TimeUtils.getCurrentDayOfWeek().toString()
                val activeSchedules = scheduleDao.getActiveSchedules(currentMinute, dayOfWeek)

                for (schedule in activeSchedules) {
                    val blockedPackages = schedule.blockedPackages.split(",")
                    if (blockedPackages.contains(currentPackage)) {
                        Log.i(TAG, "Schedule enforcement: Blocking $currentPackage (schedule: ${schedule.name})")

                        // Block the app
                        val appName = AppUtils.getAppName(applicationContext, currentPackage)

                        mainHandler.post {
                            // Vibrate to alert
                            vibrateDevice()
                            // Go home first
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }

                        // Small delay then show blocking screen
                        kotlinx.coroutines.delay(150)

                        mainHandler.post {
                            showBlockingScreen(currentPackage, appName, BlockedByType.SCHEDULE)
                        }

                        // Log the block
                        database.blockLogDao().insert(
                            BlockLog(
                                packageName = currentPackage,
                                appName = appName,
                                blockedBy = BlockedByType.SCHEDULE
                            )
                        )
                        database.blockedAppDao().incrementBlockCount(currentPackage)

                        break // Only block once
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in schedule enforcement check", e)
            }
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

    /**
     * Show/update the App Timer notification with remaining time
     */
    private fun showAppTimerNotification(usedMinutes: Int, limitMinutes: Int, currentAppName: String?) {
        val remainingMinutes = (limitMinutes - usedMinutes).coerceAtLeast(0)
        val remainingHours = remainingMinutes / 60
        val remainingMins = remainingMinutes % 60

        val remainingText = when {
            remainingMinutes <= 0 -> "Limit reached!"
            remainingHours > 0 -> "${remainingHours}h ${remainingMins}m left"
            else -> "${remainingMins}m left"
        }

        val progress = ((usedMinutes * 100) / limitMinutes).coerceIn(0, 100)

        val appContext = currentAppName?.let { " - Using $it" } ?: ""
        val title = "App Timer$appContext"
        val content = "$remainingText of ${limitMinutes}m daily limit"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        // Add action to extend timer (add 15 minutes)
        val extendIntent = Intent(ACTION_EXTEND_APP_TIMER).apply {
            setPackage(packageName)
        }
        val extendPendingIntent = PendingIntent.getBroadcast(
            this, 1, extendIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, FocusBlockApp.CHANNEL_APP_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .addAction(
                R.drawable.ic_notification,
                "+15 min",
                extendPendingIntent
            )

        // Add warning color when close to limit
        if (remainingMinutes <= 5) {
            builder.setColorized(true)
            builder.color = android.graphics.Color.parseColor("#F85149") // Red warning
        } else if (remainingMinutes <= 15) {
            builder.setColorized(true)
            builder.color = android.graphics.Color.parseColor("#F0883E") // Orange warning
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(APP_TIMER_NOTIFICATION_ID, builder.build())
    }

    /**
     * Dismiss the App Timer notification when user leaves timer apps
     */
    private fun dismissAppTimerNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(APP_TIMER_NOTIFICATION_ID)
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopQuickBlockTimerCheck() // Clean up Quick Block timer
        stopSessionDurationCheck() // Clean up session duration timer
        stopAppTimerCheck() // Clean up App Timer check
        stopScheduleCheck() // Clean up schedule enforcement check
        stopGlobalLimitCheck() // Clean up Global Limit check
        stopUsageComparisonCheck() // Clean up Usage Comparison check
        dismissAppTimerNotification() // Dismiss App Timer notification
        activeSessions.clear() // Clear session tracking
        try {
            unregisterReceiver(settingsChangeReceiver) // Unregister settings change receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister settings change receiver", e)
        }
        serviceScope.cancel()
        immediateScope.cancel()
        super.onDestroy()
    }

    private fun shouldIgnorePackage(packageName: String): Boolean {
        // Always ignore our own app
        if (packageName == this.packageName) return true

        // Ignore Android system UI and settings
        if (packageName == "com.android.systemui") return true
        if (packageName == "com.android.settings") return true

        // Ignore all launchers
        if (packageName.contains("launcher")) return true

        // Ignore Samsung system utilities (One Hand Operation+, Edge Panel, Routines, etc.)
        if (packageName.startsWith("com.samsung.android.")) {
            // Only track Samsung browser - it can be distracting
            if (packageName == "com.samsung.android.app.sbrowser") return false
            return true // Ignore all other Samsung utilities
        }
        if (packageName.startsWith("com.sec.android.")) return true

        // Ignore Google Play Services
        if (packageName == "com.google.android.gms") return true
        if (packageName == "com.google.android.gsf") return true

        return false
    }

    private suspend fun shouldBlockApp(packageName: String): Boolean {
        Log.d(TAG, "shouldBlockApp() checking: $packageName")

        val blockedAppDao = database.blockedAppDao()
        val scheduleDao = database.scheduleDao()
        val quickBlockSessionDao = database.quickBlockSessionDao()
        val settingsDao = database.settingsDao()
        val focusCycleDao = database.focusCycleDao()
        val appTimerSettingsDao = database.appTimerSettingsDao()
        val appTimerDailyUsageDao = database.appTimerDailyUsageDao()

        // Check if in allowlist - always allow these apps
        val blockedApp = blockedAppDao.getBlockedApp(packageName)
        if (blockedApp?.isInAllowlist == true) {
            Log.d(TAG, "App is in allowlist, allowing: $packageName")
            return false
        }

        // ============ GLOBAL DAILY LIMIT ENFORCEMENT (HIGHEST PRIORITY) ============
        // Check if this app should be blocked due to Global Daily Limit
        // This enforces even when all other modes are OFF
        if (cachedGlobalLimitEnabled && !isExcludedFromGlobalLimit(packageName)) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            val globalDailyUsage = database.globalDailyUsageDao().getUsageForDateSync(today)
            // Use cached value for speed, but calculate fresh if cache is stale (0)
            val currentUsage = if (lastGlobalUsageMinutes == 0) {
                val freshUsage = calculateTotalScreenTime()
                lastGlobalUsageMinutes = freshUsage
                Log.d(TAG, "Global Limit: Calculated fresh usage on demand: $freshUsage min")
                freshUsage
            } else {
                lastGlobalUsageMinutes
            }
            val limitMinutes = cachedGlobalLimitMinutes

            if (currentUsage >= limitMinutes) {
                // Check if override is active and not expired
                val overrideExpires = globalDailyUsage?.overrideExpiresAt
                val now = System.currentTimeMillis()

                if (overrideExpires != null && now < overrideExpires) {
                    // Override is active - allow for now
                    Log.d(TAG, "Global Limit: Override active for $packageName (expires in ${(overrideExpires - now)/1000}s)")
                    // Don't return false yet - let other modes check too
                } else {
                    // No active override - block the app
                    Log.i(TAG, "Global Limit blocking: $packageName (usage: $currentUsage min >= limit: $limitMinutes min)")
                    return true
                }
            }
        }

        // ============ APP TIMER ENFORCEMENT ============
        // Check if this app should be blocked due to App Timer limit
        val timerSettings = appTimerSettingsDao.getSettingsSync()
        if (timerSettings != null && timerSettings.isEnabled) {
            val timerApps = timerSettings.timerApps.split(",").filter { it.isNotBlank() }
            if (timerApps.contains(packageName)) {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val dailyUsage = appTimerDailyUsageDao.getUsageForDateSync(today)
                val currentUsage = lastAppTimerUsageMinutes // Use cached value for speed
                val limitMinutes = timerSettings.dailyLimitMinutes

                if (currentUsage >= limitMinutes) {
                    // Check if override is active and not expired
                    val overrideExpires = dailyUsage?.overrideExpiresAt
                    val now = System.currentTimeMillis()

                    if (overrideExpires != null && now < overrideExpires) {
                        // Override is active - allow for now
                        Log.d(TAG, "App Timer: Override active for $packageName (expires in ${(overrideExpires - now)/1000}s)")
                        return false
                    }

                    // No active override - block the app
                    Log.i(TAG, "App Timer blocking: $packageName (usage: $currentUsage min >= limit: $limitMinutes min)")
                    return true
                }
            }
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
                Log.i(TAG, "Quick Block blocking: $packageName (session active)")
                return true
            }
        }

        // Check Focus Cycles (soft-nudge, only blocks during break phase)
        if (!strictModeEnabled) {
            val activeFocusCycle = focusCycleDao.getActiveFocusCycleSync()
            if (activeFocusCycle != null && activeFocusCycle.isEnabled) {
                val now = System.currentTimeMillis()
                val breakStart = activeFocusCycle.breakStartTime

                // Check if we're in break phase
                // IMPORTANT: Only check breakStartTime, not simple time calculation
                // The usage window exhaustion is determined by accumulatedUsageMillis in handleFocusCycleInstant
                val isInBreak = if (breakStart != null) {
                    val breakEnd = breakStart + timeToMillis(activeFocusCycle.breakDurationMinutes)
                    now < breakEnd
                } else {
                    // Not in break - usage window is still active or cycle hasn't started
                    false
                }

                if (isInBreak) {
                    // Get packages to check (consider useQuickBlockApps)
                    var focusCyclePackages = activeFocusCycle.selectedPackages
                        .split(",")
                        .filter { it.isNotBlank() }

                    // If no explicit packages and useQuickBlockApps is true, use Quick Block apps
                    if (focusCyclePackages.isEmpty() && activeFocusCycle.useQuickBlockApps) {
                        val quickBlockSession = quickBlockSessionDao.getActiveSessionSync()
                        focusCyclePackages = quickBlockSession?.blockedPackages
                            ?.split(",")
                            ?.filter { it.isNotBlank() } ?: emptyList()
                    }

                    if (focusCyclePackages.contains(packageName)) {
                        Log.i(TAG, "Focus Cycle blocking: $packageName (break phase until ${activeFocusCycle.breakStartTime?.let { it + timeToMillis(activeFocusCycle.breakDurationMinutes) }})")
                        return true
                    }
                }
            }
        }

        // Check schedules
        val currentMinute = TimeUtils.getCurrentMinuteOfDay()
        val dayOfWeek = TimeUtils.getCurrentDayOfWeek().toString()
        val activeSchedules = scheduleDao.getActiveSchedules(currentMinute, dayOfWeek)

        for (schedule in activeSchedules) {
            val blockedPackages = schedule.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                Log.d(TAG, "Schedule blocking: $packageName (schedule: ${schedule.name})")
                return true
            }
        }

        // IMPORTANT: We do NOT check isBlocked = true here anymore
        // The isBlocked flag was being set by Quick Block sessions but not cleared on timer expiry
        // This caused apps to remain blocked even after all blocking modes were inactive
        //
        // Apps are only blocked when an ACTIVE blocking policy is in effect:
        // - Active Quick Block session (checked above)
        // - Active Focus Cycle break (checked above)
        // - Active Schedule (checked above)
        // - App Timer limit reached (checked above)

        Log.d(TAG, "No active blocking policy for: $packageName")
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
        val appTimerSettingsDao = database.appTimerSettingsDao()

        val hardModeEnabled = settingsDao.getValue("hard_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (hardModeEnabled) {
            return BlockedByType.HARD_MODE
        }

        // Check Global Daily Limit (highest priority after hard mode)
        if (cachedGlobalLimitEnabled && !isExcludedFromGlobalLimit(packageName)) {
            if (lastGlobalUsageMinutes >= cachedGlobalLimitMinutes) {
                return BlockedByType.GLOBAL_LIMIT
            }
        }

        val strictModeEnabled = settingsDao.getValue("strict_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (strictModeEnabled) {
            return BlockedByType.STRICT_MODE
        }

        // Check App Timer first (it's an enforcement limit)
        val timerSettings = appTimerSettingsDao.getSettingsSync()
        if (timerSettings != null && timerSettings.isEnabled) {
            val timerApps = timerSettings.timerApps.split(",").filter { it.isNotBlank() }
            if (timerApps.contains(packageName)) {
                val currentUsage = lastAppTimerUsageMinutes
                if (currentUsage >= timerSettings.dailyLimitMinutes) {
                    return BlockedByType.APP_TIMER
                }
            }
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

    // ========== GLOBAL DAILY USAGE LIMIT METHODS ==========

    /**
     * Refresh Global Daily Limit settings cache from database
     */
    private fun refreshGlobalLimitCache() {
        immediateScope.launch {
            try {
                var settings = database.globalDailyLimitSettingsDao().getSettingsSync()

                // Auto-create default settings if none exist (enabled by default with 3h limit)
                if (settings == null) {
                    settings = com.focusblock.app.database.entity.GlobalDailyLimitSettings()
                    database.globalDailyLimitSettingsDao().insert(settings)
                    Log.i(TAG, "Global Limit: Created default settings (enabled=true, limit=180min)")
                }

                cachedGlobalLimitEnabled = settings.isEnabled
                cachedGlobalLimitMinutes = settings.dailyLimitMinutes
                cachedGlobalLimitWarningMinutes = settings.warningMinutesBefore
                cachedGlobalLimitUseAppTimerApps = settings.useAppTimerApps

                // Build the list of apps to track (only USER-configured apps)
                val trackedAppsSet = mutableSetOf<String>()

                // Add user-defined tracked packages
                val userTracked = settings.trackedPackages
                    .split(",")
                    .filter { it.isNotBlank() }
                if (userTracked.isNotEmpty()) {
                    trackedAppsSet.addAll(userTracked)
                }

                // If sharing with App Timer, add those apps too
                if (settings.useAppTimerApps) {
                    val appTimerSettings = database.appTimerSettingsDao().getSettingsSync()
                    if (appTimerSettings != null && appTimerSettings.timerApps.isNotBlank()) {
                        trackedAppsSet.addAll(appTimerSettings.timerApps.split(",").filter { it.isNotBlank() })
                    }
                }

                // NOTE: We no longer add DEFAULT_DISTRACTING_APPS here
                // If trackedAppsSet is empty, isExcludedFromGlobalLimit() will use dynamic detection

                cachedGlobalLimitTrackedPackages = trackedAppsSet
                Log.d(TAG, "Global Limit: User tracked ${trackedAppsSet.size} apps, will use dynamic detection if empty")

                // Also calculate current screen time and update database immediately
                if (cachedGlobalLimitEnabled) {
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    val totalUsageMinutes = calculateTotalScreenTime()
                    lastGlobalUsageMinutes = totalUsageMinutes

                    // Update database immediately so UI reflects correct value
                    var dailyUsage = database.globalDailyUsageDao().getUsageForDateSync(today)
                    if (dailyUsage == null) {
                        dailyUsage = com.focusblock.app.database.entity.GlobalDailyUsage(
                            date = today,
                            totalUsageMinutes = totalUsageMinutes
                        )
                        database.globalDailyUsageDao().insert(dailyUsage)
                    } else {
                        database.globalDailyUsageDao().updateUsage(today, totalUsageMinutes)
                    }

                    Log.d(TAG, "Global Limit cache refreshed: enabled=true, limit=$cachedGlobalLimitMinutes min, currentUsage=$totalUsageMinutes min, tracking=${trackedAppsSet.size} apps (dynamic=${trackedAppsSet.isEmpty()})")
                } else {
                    Log.d(TAG, "Global Limit cache refreshed: enabled=false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh Global Limit cache", e)
            }
        }
    }

    /**
     * Start periodic Global Daily Limit check
     */
    private fun startGlobalLimitCheck() {
        stopGlobalLimitCheck()

        globalLimitRunnable = object : Runnable {
            override fun run() {
                checkGlobalDailyLimit()
                globalLimitHandler.postDelayed(this, GLOBAL_LIMIT_CHECK_INTERVAL_MS)
            }
        }
        globalLimitHandler.post(globalLimitRunnable!!)
        Log.d(TAG, "Global Daily Limit monitoring started")
    }

    private fun stopGlobalLimitCheck() {
        globalLimitRunnable?.let {
            globalLimitHandler.removeCallbacks(it)
        }
        globalLimitRunnable = null
    }

    // Counter for periodic cache refresh (every 2 cycles = ~1 minute)
    private var globalLimitCacheRefreshCounter = 0

    /**
     * Check global daily usage and enforce limit
     * This tracks TOTAL phone usage, not per-app
     */
    private fun checkGlobalDailyLimit() {
        // Periodically refresh cache to pick up settings changes (fallback in case broadcast fails)
        globalLimitCacheRefreshCounter++
        if (globalLimitCacheRefreshCounter >= 2) {
            globalLimitCacheRefreshCounter = 0
            refreshGlobalLimitCache()
        }

        if (!cachedGlobalLimitEnabled) return

        immediateScope.launch {
            try {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())

                // Calculate total screen time today
                val totalUsageMinutes = calculateTotalScreenTime()
                lastGlobalUsageMinutes = totalUsageMinutes

                // Get or create daily usage record
                var dailyUsage = database.globalDailyUsageDao().getUsageForDateSync(today)
                if (dailyUsage == null) {
                    dailyUsage = com.focusblock.app.database.entity.GlobalDailyUsage(
                        date = today,
                        totalUsageMinutes = totalUsageMinutes
                    )
                    database.globalDailyUsageDao().insert(dailyUsage)
                } else {
                    database.globalDailyUsageDao().updateUsage(today, totalUsageMinutes)
                    dailyUsage = dailyUsage.copy(totalUsageMinutes = totalUsageMinutes)
                }

                // Also update the daily summary for comparison feature
                updateDailyUsageSummary(today, totalUsageMinutes)

                val limitMinutes = cachedGlobalLimitMinutes
                val warningMinutes = limitMinutes - cachedGlobalLimitWarningMinutes
                val now = System.currentTimeMillis()

                // Check if override is active
                val overrideExpires = dailyUsage.overrideExpiresAt
                val hasActiveOverride = overrideExpires != null && now < overrideExpires

                if (hasActiveOverride) {
                    globalLimitEnforcementActive = false
                    Log.d(TAG, "Global Limit: Override active until ${java.util.Date(overrideExpires!!)}")
                    return@launch
                }

                // Check and enforce limit
                when {
                    // Limit reached - enforce blocking
                    totalUsageMinutes >= limitMinutes -> {
                        if (!dailyUsage.limitNotificationShown) {
                            database.globalDailyUsageDao().markLimitReached(today)
                            showGlobalLimitReachedNotification(totalUsageMinutes, limitMinutes)
                        }
                        globalLimitEnforcementActive = true

                        // Block current non-excluded app if user is actively using one
                        val currentPackage = lastForegroundPackage
                        if (currentPackage != null && !isExcludedFromGlobalLimit(currentPackage)) {
                            Log.i(TAG, "Global Limit: Enforcing block on $currentPackage (usage: $totalUsageMinutes >= limit: $limitMinutes)")
                            blockApp(currentPackage)
                        }
                    }
                    // Warning threshold - show notification
                    totalUsageMinutes >= warningMinutes && !dailyUsage.warningShown -> {
                        database.globalDailyUsageDao().markWarningShown(today)
                        showGlobalLimitWarningNotification(totalUsageMinutes, limitMinutes)
                        globalLimitEnforcementActive = false
                    }
                    else -> {
                        globalLimitEnforcementActive = false
                    }
                }

                // Check for 3+ hours excessive usage notification (independent of global limit)
                if (totalUsageMinutes >= EXCESSIVE_USAGE_THRESHOLD_MINUTES &&
                    !dailyUsage.excessiveUsageNotificationShown) {
                    database.globalDailyUsageDao().markExcessiveUsageNotificationShown(today)
                    showExcessiveUsageNotification(totalUsageMinutes)
                }

                Log.v(TAG, "Global Limit check: usage=$totalUsageMinutes min, limit=$limitMinutes min, enforcing=$globalLimitEnforcementActive")

            } catch (e: Exception) {
                Log.e(TAG, "Error checking Global Daily Limit", e)
            }
        }
    }

    /**
     * Calculate total screen time today using UsageEvents API
     * Excludes system apps, productive apps, and user-excluded packages based on settings
     */
    private fun calculateTotalScreenTime(): Int {
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

                // Skip excluded packages
                if (isExcludedFromGlobalLimit(packageName)) continue

                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        activeApps[packageName] = event.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
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

            // Add time for apps still in foreground
            val now = System.currentTimeMillis()
            for ((packageName, foregroundStart) in activeApps) {
                if (isExcludedFromGlobalLimit(packageName)) continue
                val duration = now - foregroundStart
                if (duration > 0 && duration < 4 * 60 * 60 * 1000) {
                    appUsageMillis[packageName] = (appUsageMillis[packageName] ?: 0L) + duration
                }
            }

            val totalMillis = appUsageMillis.values.sum()
            val totalMinutes = (totalMillis / 60_000).toInt()

            Log.d(TAG, "Total screen time calculated: ${totalMinutes}m")
            return totalMinutes

        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate total screen time", e)
            return 0
        }
    }

    /**
     * Check if a package is excluded from global limit tracking
     * NEW: Uses dynamic detection for distractive apps (social media, entertainment, games)
     */
    private fun isExcludedFromGlobalLimit(packageName: String): Boolean {
        // Always exclude our own app
        if (packageName == "com.focusblock.app") return true

        // Always exclude system apps (settings, dialer, camera, launcher, etc.)
        if (com.focusblock.app.database.entity.GlobalDailyLimitSettings.SYSTEM_APPS.any {
            packageName == it || packageName.startsWith("$it.")
        }) return true

        // If user has custom tracked apps configured, use that list
        if (cachedGlobalLimitTrackedPackages.isNotEmpty()) {
            return !cachedGlobalLimitTrackedPackages.contains(packageName)
        }

        // Otherwise, use dynamic detection for distractive apps
        return !isDistractiveApp(packageName)
    }

    /**
     * Dynamically detect if an app is distractive (social media, entertainment, games)
     * This matches the categorization used by the Insights page
     */
    private fun isDistractiveApp(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()

        // Get app name for additional matching
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString().lowercase()
        } catch (e: Exception) {
            ""
        }

        // Social media keywords
        val socialKeywords = setOf(
            "instagram", "facebook", "twitter", "tiktok", "snapchat",
            "reddit", "pinterest", "tumblr", "discord", "messenger",
            "wechat", "line", "viber", "telegram", "linkedin",
            "threads", "mastodon", "bluesky", "x.com"
        )

        // Entertainment/Video keywords
        val entertainmentKeywords = setOf(
            "youtube", "netflix", "twitch", "hulu", "disney", "spotify",
            "prime video", "hotstar", "voot", "zee5", "sonyliv",
            "player", "video", "movie", "stream", "kuku", "revanced"
        )

        // Gaming keywords
        val gameKeywords = setOf(
            "game", "gaming", "clash", "pubg", "bgmi", "freefire",
            "candy", "roblox", "minecraft", "fortnite", "cod", "mobile legends",
            "epic games", "steam", "play games"
        )

        // Check if matches any distractive category
        if (socialKeywords.any { pkgLower.contains(it) || appName.contains(it) }) return true
        if (entertainmentKeywords.any { pkgLower.contains(it) || appName.contains(it) }) return true
        if (gameKeywords.any { pkgLower.contains(it) || appName.contains(it) }) return true

        // Also check against the default list for exact matches
        if (com.focusblock.app.database.entity.GlobalDailyLimitSettings.DEFAULT_DISTRACTING_APPS.contains(packageName)) {
            return true
        }

        return false
    }

    /**
     * Show warning notification when approaching global limit
     */
    private fun showGlobalLimitWarningNotification(currentMinutes: Int, limitMinutes: Int) {
        val remainingMinutes = limitMinutes - currentMinutes
        Log.i(TAG, "Global Limit: Showing warning notification - $remainingMinutes min remaining")

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Approaching Daily Limit")
            .setContentText("$remainingMinutes minutes remaining. Consider wrapping up.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You have $remainingMinutes minutes of screen time remaining today. Consider finishing up and taking a break."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(GLOBAL_LIMIT_WARNING_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification when global limit is reached
     */
    private fun showGlobalLimitReachedNotification(currentMinutes: Int, limitMinutes: Int) {
        Log.i(TAG, "Global Limit: Showing limit reached notification")

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Daily Usage Goal Reached")
            .setContentText("You've reached your $limitMinutes minute daily goal.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You've reached your daily usage goal of $limitMinutes minutes. Great job being mindful of your screen time! Distracting apps will be blocked for the rest of the day."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(GLOBAL_LIMIT_REACHED_NOTIFICATION_ID, notification)
        vibrateDevice()
    }

    /**
     * Show notification when user exceeds 3 hours of screen time
     * This is a strict warning independent of the global limit setting
     */
    private fun showExcessiveUsageNotification(currentMinutes: Int) {
        val hours = currentMinutes / 60
        val mins = currentMinutes % 60
        val timeString = if (mins > 0) "${hours}h ${mins}m" else "${hours}h"

        Log.i(TAG, "Excessive Usage: Showing 3+ hours notification - $timeString total")

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("3+ Hours of Screen Time Today")
            .setContentText("You've spent $timeString on your phone. Consider taking a break.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You've spent $timeString on your phone today. Extended screen time can affect your focus and wellbeing. Consider putting your phone down and doing something offline."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(EXCESSIVE_USAGE_NOTIFICATION_ID, notification)
        vibrateDevice()
    }

    /**
     * Check if global limit enforcement should block current app
     * Called from onAccessibilityEvent to enforce blocking
     */
    private fun shouldBlockForGlobalLimit(packageName: String): Boolean {
        if (!cachedGlobalLimitEnabled || !globalLimitEnforcementActive) return false
        if (isExcludedFromGlobalLimit(packageName)) return false
        return lastGlobalUsageMinutes >= cachedGlobalLimitMinutes
    }

    /**
     * Activate emergency override for Global Daily Limit
     * Called from the blocking screen or app to allow temporary access
     *
     * Override behavior:
     * - Requires confirmation (friction)
     * - Lasts for 5 minutes
     * - 15-minute cooldown between overrides
     * - Doesn't permanently disable the limit
     *
     * @return Pair<Boolean, String> - (success, message)
     */
    fun activateGlobalLimitOverride(): Pair<Boolean, String> {
        if (!cachedGlobalLimitEnabled) {
            return Pair(false, "Global limit is not enabled")
        }

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val now = System.currentTimeMillis()

        // Launch in scope to handle database operations
        immediateScope.launch {
            try {
                var dailyUsage = database.globalDailyUsageDao().getUsageForDateSync(today)
                if (dailyUsage == null) {
                    dailyUsage = com.focusblock.app.database.entity.GlobalDailyUsage(date = today)
                    database.globalDailyUsageDao().insert(dailyUsage)
                }

                // Check cooldown - prevent rapid repeated overrides
                val cooldownUntil = dailyUsage.overrideCooldownUntil
                if (cooldownUntil != null && now < cooldownUntil) {
                    val remainingSeconds = (cooldownUntil - now) / 1000
                    Log.w(TAG, "Global Limit: Override on cooldown for ${remainingSeconds}s more")
                    return@launch
                }

                // Activate override
                val expiresAt = now + OVERRIDE_DURATION_MS
                val newCooldownUntil = now + OVERRIDE_COOLDOWN_MS

                database.globalDailyUsageDao().activateOverride(
                    date = today,
                    overrideTime = now,
                    expiresAt = expiresAt,
                    cooldownUntil = newCooldownUntil
                )

                globalLimitEnforcementActive = false
                Log.i(TAG, "Global Limit: Emergency override activated (expires in ${OVERRIDE_DURATION_MS/1000}s)")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to activate Global Limit override", e)
            }
        }

        return Pair(true, "Override activated for ${OVERRIDE_DURATION_MS / 60000} minutes")
    }

    /**
     * Check if Global Limit override is on cooldown
     */
    suspend fun isGlobalLimitOverrideOnCooldown(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val dailyUsage = database.globalDailyUsageDao().getUsageForDateSync(today)
        val cooldownUntil = dailyUsage?.overrideCooldownUntil ?: return false
        return System.currentTimeMillis() < cooldownUntil
    }

    /**
     * Get remaining cooldown time in seconds
     */
    suspend fun getGlobalLimitOverrideCooldownSeconds(): Long {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val dailyUsage = database.globalDailyUsageDao().getUsageForDateSync(today)
        val cooldownUntil = dailyUsage?.overrideCooldownUntil ?: return 0
        val remaining = cooldownUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000 else 0
    }

    /**
     * Get current global usage minutes
     */
    fun getCurrentGlobalUsageMinutes(): Int = lastGlobalUsageMinutes

    /**
     * Get global limit minutes
     */
    fun getGlobalLimitMinutes(): Int = cachedGlobalLimitMinutes

    /**
     * Check if global limit is enabled
     */
    fun isGlobalLimitEnabled(): Boolean = cachedGlobalLimitEnabled

    // ========== DAILY USAGE COMPARISON (TODAY VS YESTERDAY) METHODS ==========

    /**
     * Start periodic usage comparison check
     */
    private fun startUsageComparisonCheck() {
        stopUsageComparisonCheck()

        usageComparisonRunnable = object : Runnable {
            override fun run() {
                checkUsageComparison()
                usageComparisonHandler.postDelayed(this, USAGE_COMPARISON_CHECK_INTERVAL_MS)
            }
        }
        // Delay first check by 1 minute to let other systems initialize
        usageComparisonHandler.postDelayed(usageComparisonRunnable!!, 60_000L)
        Log.d(TAG, "Daily Usage Comparison monitoring started")
    }

    private fun stopUsageComparisonCheck() {
        usageComparisonRunnable?.let {
            usageComparisonHandler.removeCallbacks(it)
        }
        usageComparisonRunnable = null
    }

    /**
     * Update daily usage summary for comparison feature
     */
    private suspend fun updateDailyUsageSummary(date: String, totalMinutes: Int) {
        try {
            var summary = database.dailyUsageSummaryDao().getSummaryForDateSync(date)
            if (summary == null) {
                summary = com.focusblock.app.database.entity.DailyUsageSummary(
                    date = date,
                    totalScreenTimeMinutes = totalMinutes
                )
                database.dailyUsageSummaryDao().insert(summary)
            } else {
                database.dailyUsageSummaryDao().updateScreenTime(date, totalMinutes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating daily usage summary", e)
        }
    }

    /**
     * Check today vs yesterday usage and send notification if significant difference
     * Also triggers AUTO-BLOCKING if today exceeds yesterday due to social media usage
     */
    private fun checkUsageComparison() {
        immediateScope.launch {
            try {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val today = dateFormat.format(java.util.Date())

                // Reset auto-block state at midnight
                if (autoBlockTriggeredDate != today) {
                    autoBlockTriggeredDate = null
                    autoBlockedPackages.clear()
                }

                // Get today's summary
                val todaySummary = database.dailyUsageSummaryDao().getSummaryForDateSync(today)

                // Calculate yesterday's date
                val calendar = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
                val yesterday = dateFormat.format(calendar.time)

                // Get yesterday's summary
                val yesterdaySummary = database.dailyUsageSummaryDao().getSummaryForDateSync(yesterday)

                val todayMinutes = todaySummary?.totalScreenTimeMinutes ?: 0
                val yesterdayMinutes = yesterdaySummary?.totalScreenTimeMinutes ?: 0

                // ========== AUTO-BLOCK CHECK (runs continuously) ==========
                // If today exceeds yesterday AND auto-block hasn't triggered yet today
                if (yesterdayMinutes > 0 && todayMinutes > yesterdayMinutes && autoBlockTriggeredDate != today) {
                    checkAndAutoBlockSocialApps(today, todayMinutes, yesterdayMinutes)
                }

                // ========== DAILY COMPARISON NOTIFICATION (once per day after 6 PM) ==========
                // Skip if notification already sent today
                if (todaySummary?.comparisonNotificationSent == true) {
                    return@launch
                }

                // Get current hour - only check after 6 PM for meaningful daily comparison
                val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if (currentHour < 18) {
                    return@launch
                }

                // Skip if yesterday's data is not meaningful
                if (yesterdayMinutes < MEANINGFUL_USAGE_THRESHOLD_MINUTES) {
                    return@launch
                }

                // Skip if today's usage is not meaningful yet
                if (todayMinutes < MEANINGFUL_USAGE_THRESHOLD_MINUTES) {
                    return@launch
                }

                // Calculate percentage difference
                val difference = todayMinutes - yesterdayMinutes
                val percentDiff = (difference.toFloat() / yesterdayMinutes * 100).toInt()

                // Only notify if difference is significant
                if (kotlin.math.abs(percentDiff) < SIGNIFICANT_DIFFERENCE_PERCENT) {
                    return@launch
                }

                // Determine result and send notification
                val result = if (difference > 0) "higher" else "lower"
                database.dailyUsageSummaryDao().markComparisonSent(today, result)

                mainHandler.post {
                    showUsageComparisonNotification(todayMinutes, yesterdayMinutes, result)
                }

                Log.i(TAG, "Usage comparison: Sent $result notification (today: $todayMinutes, yesterday: $yesterdayMinutes, diff: $percentDiff%)")

            } catch (e: Exception) {
                Log.e(TAG, "Error checking usage comparison", e)
            }
        }
    }

    /**
     * Check if excess usage is from social media apps and auto-block them
     * Also activates Hard Mode when triggered
     */
    private suspend fun checkAndAutoBlockSocialApps(today: String, todayMinutes: Int, yesterdayMinutes: Int) {
        try {
            val excessMinutes = todayMinutes - yesterdayMinutes
            if (excessMinutes <= 0) return

            Log.i(TAG, "Auto-block check: Today ($todayMinutes) exceeds yesterday ($yesterdayMinutes) by $excessMinutes min")

            // Get per-app usage for today to identify which apps caused the excess
            val socialAppUsage = getSocialAppUsageToday()

            // Calculate total social media usage today
            val totalSocialMinutes = socialAppUsage.values.sum()

            Log.i(TAG, "Auto-block check: Social media usage today = $totalSocialMinutes min")

            // If social media accounts for the excess (or more), trigger auto-block
            if (totalSocialMinutes >= excessMinutes) {
                Log.w(TAG, "AUTO-BLOCK TRIGGERED: Social media ($totalSocialMinutes min) accounts for excess usage ($excessMinutes min)")

                // Get social apps that were used today
                val usedSocialApps = socialAppUsage.filter { it.value > 5 }.keys // Apps used >5 min

                if (usedSocialApps.isEmpty()) {
                    Log.i(TAG, "Auto-block: No significant social media usage to block")
                    return
                }

                // Add to blocked apps
                for (packageName in usedSocialApps) {
                    val appName = try {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(packageName, 0)
                        ).toString()
                    } catch (e: Exception) {
                        packageName.substringAfterLast(".")
                    }

                    // Insert as blocked app
                    database.blockedAppDao().insert(
                        BlockedApp(
                            packageName = packageName,
                            appName = appName,
                            isBlocked = true
                        )
                    )

                    autoBlockedPackages.add(packageName)
                    Log.i(TAG, "Auto-blocked: $appName ($packageName)")
                }

                // Mark auto-block as triggered for today
                autoBlockTriggeredDate = today

                // Activate Hard Mode automatically
                activateHardModeAutomatically()

                // Show notification
                mainHandler.post {
                    showAutoBlockNotification(usedSocialApps.size, excessMinutes, totalSocialMinutes)
                }

            } else {
                Log.i(TAG, "Auto-block: Excess is from non-social apps (social: $totalSocialMinutes min < excess: $excessMinutes min)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in auto-block check", e)
        }
    }

    /**
     * Get today's usage per social media app
     */
    private fun getSocialAppUsageToday(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()

        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return result

            // Get today's start time
            val todayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val now = System.currentTimeMillis()

            // Query usage stats
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                todayStart,
                now
            )

            for (stats in usageStats) {
                if (SOCIAL_MEDIA_PACKAGES.contains(stats.packageName)) {
                    val minutes = (stats.totalTimeInForeground / 60000).toInt()
                    if (minutes > 0) {
                        result[stats.packageName] = minutes
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting social app usage", e)
        }

        return result
    }

    /**
     * Activate Hard Mode automatically when excess social media usage detected
     */
    private suspend fun activateHardModeAutomatically() {
        try {
            // Set Hard Mode enabled in settings
            database.settingsDao().insert(
                AppSettings(
                    key = AppSettings.KEY_HARD_MODE_ENABLED,
                    value = "true"
                )
            )

            // Also enable Strict Mode with 2 hour duration
            val twoHoursMs = 2 * 60 * 60 * 1000L
            val endTime = System.currentTimeMillis() + twoHoursMs

            database.settingsDao().insert(
                AppSettings(
                    key = AppSettings.KEY_STRICT_MODE_ENABLED,
                    value = "true"
                )
            )
            database.settingsDao().insert(
                AppSettings(
                    key = AppSettings.KEY_STRICT_MODE_END_TIME,
                    value = endTime.toString()
                )
            )

            Log.w(TAG, "AUTO: Hard Mode and Strict Mode (2h) activated due to excess social media usage")

        } catch (e: Exception) {
            Log.e(TAG, "Error activating Hard Mode automatically", e)
        }
    }

    /**
     * Show notification about auto-blocking
     */
    private fun showAutoBlockNotification(appsBlocked: Int, excessMinutes: Int, socialMinutes: Int) {
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Auto-Block Activated")
            .setContentText("$appsBlocked social apps blocked. Hard Mode enabled.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You exceeded yesterday's usage by ${formatMinutesNicely(excessMinutes)}. " +
                        "Social media apps (${formatMinutesNicely(socialMinutes)} today) have been auto-blocked and Hard Mode activated."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(AUTO_BLOCK_NOTIFICATION_ID, notification)
        vibrateDevice()
    }

    /**
     * Show notification comparing today's usage to yesterday's
     */
    private fun showUsageComparisonNotification(todayMinutes: Int, yesterdayMinutes: Int, result: String) {
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

        val (title, message) = if (result == "higher") {
            val diff = todayMinutes - yesterdayMinutes
            "Screen Time Higher Today" to
                "Your screen time today (${formatMinutesNicely(todayMinutes)}) is ${formatMinutesNicely(diff)} more than yesterday. Consider winding down."
        } else {
            val diff = yesterdayMinutes - todayMinutes
            "Great Progress! 🎉" to
                "You've used your phone ${formatMinutesNicely(diff)} less today compared to yesterday. Keep it up!"
        }

        val notification = NotificationCompat.Builder(this, FocusBlockApp.CHANNEL_MINDFUL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(USAGE_COMPARISON_NOTIFICATION_ID, notification)
    }

    /**
     * Format minutes nicely for display (e.g., "2h 30m" or "45m")
     */
    private fun formatMinutesNicely(minutes: Int): String {
        return when {
            minutes >= 60 -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
            }
            else -> "${minutes}m"
        }
    }
}
