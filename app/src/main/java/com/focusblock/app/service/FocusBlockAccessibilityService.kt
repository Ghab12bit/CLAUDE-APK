package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.entity.BlockedByType
import com.focusblock.app.ui.overlay.BlockedAppActivity
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.TimeUtils
import kotlinx.coroutines.*

class FocusBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusBlockA11y"
        private const val BLOCK_COOLDOWN = 2000L
        var isServiceRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedPackage: String? = null
    private var lastBlockTime = 0L
    private var isBlockingInProgress = false
    private val database by lazy { FocusBlockDatabase.getDatabase(applicationContext) }

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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Don't block our own app or system components
        if (shouldIgnorePackage(packageName)) return

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

        // Check if in allowlist
        val blockedApp = blockedAppDao.getBlockedApp(packageName)
        if (blockedApp?.isInAllowlist == true) {
            return false
        }

        // Check Quick Block
        val quickBlockSession = quickBlockSessionDao.getActiveSessionSync()
        if (quickBlockSession != null) {
            val blockedPackages = quickBlockSession.blockedPackages.split(",")
            if (blockedPackages.contains(packageName)) {
                return true
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

        // Check if Hard Mode is enabled
        val hardModeEnabled = settingsDao.getValue("hard_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (hardModeEnabled) {
            return BlockedByType.HARD_MODE
        }

        // Check if Strict Mode is enabled
        val strictModeEnabled = settingsDao.getValue("strict_mode_enabled")?.toBooleanStrictOrNull() ?: false
        if (strictModeEnabled) {
            return BlockedByType.STRICT_MODE
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
