package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.focusblock.app.ui.overlay.BlockedAppActivity
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.TimeUtils
import kotlinx.coroutines.*

class FocusBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusBlockA11y"
        private const val BLOCK_COOLDOWN = 2000L
        private const val FOCUS_CYCLE_NOTIFICATION_ID = 3001
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
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedPackage: String? = null
    private var lastBlockTime = 0L
    private var isBlockingInProgress = false
    private val database by lazy { FocusBlockDatabase.getDatabase(applicationContext) }
    private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info

        isServiceRunning = true
        Log.i(TAG, "FocusBlock Accessibility Service is now running")

        // Show toast to confirm service is running
        mainHandler.post {
            android.widget.Toast.makeText(
                applicationContext,
                "FocusBlock Accessibility Service enabled",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Don't block our own app or system components
        if (shouldIgnorePackage(packageName)) return

        // Track Focus Cycle state transitions (always, before blocking check)
        serviceScope.launch {
            try {
                handleFocusCycleStateTransition(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error in handleFocusCycleStateTransition", e)
                mainHandler.post {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "Focus Cycle Error: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Don't process if we're already blocking
        if (isBlockingInProgress) {
            Log.d(TAG, "Block already in progress, ignoring: $packageName")
            return
        }

        // Debounce
        if (packageName == lastBlockedPackage &&
            System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN) {
            Log.d(TAG, "Cooldown active for: $packageName")
            return
        }

        serviceScope.launch {
            if (shouldBlockApp(packageName)) {
                Log.i(TAG, "Blocking app detected: $packageName at ${System.currentTimeMillis()}")
                lastBlockedPackage = packageName
                lastBlockTime = System.currentTimeMillis()
                blockApp(packageName)
            }
        }

        lastForegroundPackage = packageName
    }

    /**
     * Handle Focus Cycle state transitions based on foreground app
     * - If armed and selected app opens → start cycle
     * - If in usage window and non-selected app opens → pause timer
     * - If paused and selected app opens → resume timer
     */
    private suspend fun handleFocusCycleStateTransition(packageName: String) {
        val focusCycleDao = database.focusCycleDao()

        val activeFocusCycle = focusCycleDao.getActiveFocusCycleSync() ?: return
        if (!activeFocusCycle.isEnabled) return

        // Get list of apps in Focus Cycle - always use selectedPackages
        // This field is populated by the dialog with either Quick Block apps or custom selection
        val focusCyclePackages = activeFocusCycle.selectedPackages
            .split(",")
            .filter { it.isNotBlank() }

        Log.d(TAG, "Focus Cycle tracking packages: $focusCyclePackages")

        if (focusCyclePackages.isEmpty()) {
            Log.w(TAG, "Focus Cycle has no apps to track! selectedPackages is empty.")
            return
        }

        val isSelectedApp = focusCyclePackages.contains(packageName)
        val now = System.currentTimeMillis()

        Log.d(TAG, "Focus Cycle: package=$packageName, isSelectedApp=$isSelectedApp, isArmed=${activeFocusCycle.isArmed}, isPaused=${activeFocusCycle.isPaused}")

        when {
            // Case 1: Cycle is armed (waiting for first app open)
            activeFocusCycle.isArmed -> {
                if (isSelectedApp) {
                    // User opened a selected app - START the cycle
                    Log.i(TAG, "Focus Cycle: Starting cycle - user opened $packageName")
                    val updatedCycle = activeFocusCycle.copy(
                        isArmed = false,
                        isPaused = false,
                        cycleStartTime = now,
                        lastActiveTime = now,
                        accumulatedUsageMillis = 0,
                        breakStartTime = null
                    )
                    focusCycleDao.update(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)

                    // Show visible feedback that cycle started
                    mainHandler.post {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "Focus Cycle started! Timer running.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                // If not a selected app, stay armed - do nothing
            }

            // Case 2: In break period - check if break is over
            activeFocusCycle.breakStartTime != null -> {
                val breakEnd = activeFocusCycle.breakStartTime + timeToMillis(activeFocusCycle.breakDurationMinutes)
                if (now >= breakEnd) {
                    // Break is over - re-arm the cycle
                    Log.i(TAG, "Focus Cycle: Break ended - re-arming cycle")
                    val updatedCycle = activeFocusCycle.copy(
                        isArmed = true,
                        isPaused = false,
                        cycleStartTime = null,
                        breakStartTime = null,
                        accumulatedUsageMillis = 0,
                        lastActiveTime = null
                    )
                    focusCycleDao.update(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)
                }
                // During break - blocking is handled by shouldBlockApp
            }

            // Case 3: In usage window
            activeFocusCycle.cycleStartTime != null -> {
                // Calculate current accumulated time
                val previousAccumulated = activeFocusCycle.accumulatedUsageMillis
                val lastActive = activeFocusCycle.lastActiveTime ?: activeFocusCycle.cycleStartTime!!

                // Add time if we were on a selected app
                val wasOnSelectedApp = lastForegroundPackage?.let { focusCyclePackages.contains(it) } ?: false
                val additionalTime = if (wasOnSelectedApp && !activeFocusCycle.isPaused) {
                    now - lastActive
                } else {
                    0L
                }
                val totalAccumulated = previousAccumulated + additionalTime
                val usageWindowMillis = timeToMillis(activeFocusCycle.usageWindowMinutes)

                // Check if usage window is exhausted
                if (totalAccumulated >= usageWindowMillis) {
                    // Start break period
                    Log.i(TAG, "Focus Cycle: Usage window exhausted - starting break")
                    val updatedCycle = activeFocusCycle.copy(
                        isPaused = false,
                        breakStartTime = now,
                        accumulatedUsageMillis = totalAccumulated,
                        lastActiveTime = now
                    )
                    focusCycleDao.update(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)
                } else if (isSelectedApp) {
                    // User is on a selected app - resume/continue timer
                    if (activeFocusCycle.isPaused) {
                        Log.d(TAG, "Focus Cycle: Resuming timer - user returned to $packageName")
                    }
                    val updatedCycle = activeFocusCycle.copy(
                        isPaused = false,
                        accumulatedUsageMillis = totalAccumulated,
                        lastActiveTime = now
                    )
                    focusCycleDao.update(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)
                } else {
                    // User switched to non-selected app - pause timer
                    if (!activeFocusCycle.isPaused) {
                        Log.d(TAG, "Focus Cycle: Pausing timer - user left to $packageName")
                    }
                    val updatedCycle = activeFocusCycle.copy(
                        isPaused = true,
                        accumulatedUsageMillis = totalAccumulated,
                        lastActiveTime = now
                    )
                    focusCycleDao.update(updatedCycle)
                    updateFocusCycleNotification(updatedCycle)
                }
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
                val remainingMillis = maxOf(0L, usageWindowMillis - cycle.accumulatedUsageMillis)
                val remainingText = formatRemainingTime(remainingMillis)
                val pausedText = if (cycle.isPaused) " (Paused)" else ""
                Triple(
                    "Focus Cycle - Usage Window$pausedText",
                    "$remainingText remaining in usage window",
                    ((cycle.accumulatedUsageMillis * 100) / usageWindowMillis).toInt()
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
        serviceScope.cancel()
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
        //
        // If Strict Mode is active, it takes precedence
        // Focus Cycles cannot override or weaken Strict Mode enforcement

        val strictModeEnabled = settingsDao.getValue("strict_mode_enabled")?.toBooleanStrictOrNull() ?: false

        // Check Quick Block (always enforced if active)
        val quickBlockSession = quickBlockSessionDao.getActiveSessionSync()
        if (quickBlockSession != null) {
            val blockedPackages = quickBlockSession.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                return true
            }
        }

        // Check Focus Cycles (soft-nudge, only blocks during break phase)
        // Note: If Strict Mode is active, Focus Cycles cannot override it
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
                    // Check if this app is in the Focus Cycle's app list
                    val focusCyclePackages = activeFocusCycle.selectedPackages
                        .split(",")
                        .filter { it.isNotBlank() }

                    if (focusCyclePackages.contains(packageName)) {
                        // Focus Cycle blocking - this is soft-nudge, can be overridden
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

            // Determine block type
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

            // CRITICAL: First go to home screen to dismiss the blocked app
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Performing GLOBAL_ACTION_HOME")
                val homeSuccess = performGlobalAction(GLOBAL_ACTION_HOME)
                Log.d(TAG, "GLOBAL_ACTION_HOME result: $homeSuccess")
            }

            // Small delay to let the home action complete
            delay(150)

            // Now show blocking screen
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Launching BlockedAppActivity for: $appName")
                showBlockingScreen(packageName, appName, blockedByType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error blocking app: $packageName", e)
        } finally {
            // Reset blocking flag after a short delay
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

        // Check if Hard Mode is enabled (highest priority)
        val hardModeEnabled = settingsDao.getValue("hard_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (hardModeEnabled) {
            return BlockedByType.HARD_MODE
        }

        // Check if Strict Mode is enabled (second highest priority)
        val strictModeEnabled = settingsDao.getValue("strict_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (strictModeEnabled) {
            return BlockedByType.STRICT_MODE
        }

        // Check Focus Cycle (soft-nudge mode, third priority)
        val activeFocusCycle = focusCycleDao.getActiveFocusCycleSync()
        if (activeFocusCycle != null && activeFocusCycle.isEnabled) {
            val focusCyclePackages = activeFocusCycle.selectedPackages
                .split(",")
                .filter { it.isNotBlank() }

            if (focusCyclePackages.contains(packageName)) {
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
                    return BlockedByType.FOCUS_CYCLE
                }
            }
        }

        // Check Quick Block
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
                // Critical flags for launching from accessibility service
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
