package com.focusblock.app.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.database.entity.*
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import com.focusblock.app.utils.TimeUtils
import com.focusblock.app.worker.PeakTimeReminderWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isQuickBlockActive: Boolean = false,
    val quickBlockSession: QuickBlockSession? = null,
    val remainingTime: Long = 0,
    val blockedAppsCount: Int = 0,
    val blockedApps: List<BlockedApp> = emptyList(),
    val activeSchedules: List<Schedule> = emptyList(),
    val todayBlockCount: Int = 0,
    val weekBlockCount: Int = 0,
    val focusStreak: Int = 0,
    val isStrictModeEnabled: Boolean = false,
    val isStrictModeLocked: Boolean = false,
    val isStrictModePaused: Boolean = false,
    val strictModeEndTime: Long? = null,
    val strictModeRemainingTime: Long = 0,
    val isHardModeEnabled: Boolean = false,
    val permissionStatus: PermissionUtils.PermissionStatus = PermissionUtils.PermissionStatus(
        hasUsageStats = false,
        hasOverlay = false,
        hasAccessibility = false,
        hasNotification = false,
        isIgnoringBattery = false
    ),
    val isPomodoroMode: Boolean = false,
    val pomodoroState: PomodoroState = PomodoroState(),
    // Focus Cycles state
    val focusCycle: FocusCycle? = null,
    val isFocusCycleEnabled: Boolean = false,
    val isFocusCycleInBreak: Boolean = false,
    val focusCycleRemainingTime: Long = 0,
    val focusCyclePhase: FocusCyclePhase = FocusCyclePhase.INACTIVE
)

enum class FocusCyclePhase {
    INACTIVE,      // Focus Cycle not enabled
    ARMED,         // Waiting for user to open a selected app
    USAGE_WINDOW,  // During allowed usage time
    PAUSED,        // Timer paused (user on non-selected app)
    BREAK          // During break (apps blocked)
}

data class PomodoroState(
    val isActive: Boolean = false,
    val currentSessionType: PomodoroSessionType = PomodoroSessionType.WORK,
    val remainingMillis: Long = 25 * 60 * 1000L,
    val completedSessions: Int = 0,
    val workDurationMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val sessionsUntilLongBreak: Int = 4
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val repository: FocusBlockRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val installedApps = MutableStateFlow<List<AppUtils.AppInfo>>(emptyList())

    init {
        loadData()
        loadFocusCycleData()
        loadInstalledApps()
        startTimerUpdates()
    }

    private fun loadData() {
        viewModelScope.launch {
            // Load Quick Block session
            repository.getActiveQuickBlockSession().collect { session ->
                _uiState.update { it.copy(
                    isQuickBlockActive = session != null,
                    quickBlockSession = session,
                    isPomodoroMode = session?.isPomodoroSession == true
                )}
            }
        }

        viewModelScope.launch {
            // Load blocked apps count
            repository.getBlockedAppsCount().collect { count ->
                _uiState.update { it.copy(blockedAppsCount = count) }
            }
        }

        viewModelScope.launch {
            // Load blocked apps
            repository.getActiveBlockedApps().collect { apps ->
                _uiState.update { it.copy(blockedApps = apps) }
            }
        }

        viewModelScope.launch {
            // Load schedules
            repository.getAllSchedules().collect { schedules ->
                _uiState.update { it.copy(activeSchedules = schedules) }
            }
        }

        viewModelScope.launch {
            // Load today's block count
            val startOfDay = TimeUtils.getStartOfDay()
            repository.getBlockCountSince(startOfDay).collect { count ->
                _uiState.update { it.copy(todayBlockCount = count) }
            }
        }

        viewModelScope.launch {
            // Load week's block count
            val startOfWeek = TimeUtils.getStartOfDay() - (7 * 24 * 60 * 60 * 1000L)
            repository.getBlockCountSince(startOfWeek).collect { count ->
                _uiState.update { it.copy(weekBlockCount = count) }
            }
        }

        viewModelScope.launch {
            // Load strict mode status
            repository.getStrictModeEnabledFlow().collect { enabled ->
                _uiState.update { it.copy(isStrictModeEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            // Load strict mode end time
            repository.getStrictModeEndTimeFlow().collect { endTime ->
                val now = System.currentTimeMillis()
                val isLocked = endTime != null && endTime > now
                _uiState.update { it.copy(
                    strictModeEndTime = endTime,
                    isStrictModeLocked = isLocked,
                    strictModeRemainingTime = if (isLocked) (endTime!! - now) else 0
                )}
            }
        }

        viewModelScope.launch {
            // Load strict mode pause status
            repository.getStrictModePausedFlow().collect { paused ->
                _uiState.update { it.copy(isStrictModePaused = paused) }
            }
        }

        viewModelScope.launch {
            // Load hard mode status
            repository.getHardModeEnabledFlow().collect { enabled ->
                _uiState.update { it.copy(isHardModeEnabled = enabled) }
            }
        }
    }

    private fun loadFocusCycleData() {
        viewModelScope.launch {
            repository.getActiveFocusCycle().collect { cycle ->
                if (cycle != null) {
                    val phase = calculateFocusCyclePhase(cycle)
                    val remaining = calculateFocusCycleRemainingTime(cycle, phase)
                    _uiState.update { it.copy(
                        focusCycle = cycle,
                        isFocusCycleEnabled = cycle.isEnabled,
                        focusCyclePhase = phase,
                        isFocusCycleInBreak = phase == FocusCyclePhase.BREAK,
                        focusCycleRemainingTime = remaining
                    )}
                } else {
                    _uiState.update { it.copy(
                        focusCycle = null,
                        isFocusCycleEnabled = false,
                        focusCyclePhase = FocusCyclePhase.INACTIVE,
                        isFocusCycleInBreak = false,
                        focusCycleRemainingTime = 0
                    )}
                }
            }
        }
    }

    private fun calculateFocusCyclePhase(cycle: FocusCycle): FocusCyclePhase {
        if (!cycle.isEnabled) return FocusCyclePhase.INACTIVE

        // Check if armed (waiting for first app open)
        if (cycle.isArmed) return FocusCyclePhase.ARMED

        val now = System.currentTimeMillis()
        val breakStart = cycle.breakStartTime

        // Check if in break period
        if (breakStart != null) {
            val breakEnd = breakStart + (cycle.breakDurationMinutes * 60 * 1000L)
            return if (now >= breakEnd) {
                // Break is over - should be re-armed (handled by AccessibilityService)
                FocusCyclePhase.ARMED
            } else {
                FocusCyclePhase.BREAK
            }
        }

        // Check if cycle has started
        if (cycle.cycleStartTime == null) return FocusCyclePhase.ARMED

        // Check if usage window is exhausted based on accumulated time
        val usageWindowMillis = cycle.usageWindowMinutes * 60 * 1000L
        if (cycle.accumulatedUsageMillis >= usageWindowMillis) {
            return FocusCyclePhase.BREAK
        }

        // We're in usage window - check if paused
        return if (cycle.isPaused) {
            FocusCyclePhase.PAUSED
        } else {
            FocusCyclePhase.USAGE_WINDOW
        }
    }

    private fun calculateFocusCycleRemainingTime(cycle: FocusCycle, phase: FocusCyclePhase): Long {
        val now = System.currentTimeMillis()

        return when (phase) {
            FocusCyclePhase.INACTIVE -> 0L
            FocusCyclePhase.ARMED -> {
                // Full usage window available when armed
                cycle.usageWindowMinutes * 60 * 1000L
            }
            FocusCyclePhase.USAGE_WINDOW, FocusCyclePhase.PAUSED -> {
                // Calculate remaining based on accumulated usage
                val usageWindowMillis = cycle.usageWindowMinutes * 60 * 1000L
                maxOf(0, usageWindowMillis - cycle.accumulatedUsageMillis)
            }
            FocusCyclePhase.BREAK -> {
                val breakStart = cycle.breakStartTime ?: now
                val breakEnd = breakStart + (cycle.breakDurationMinutes * 60 * 1000L)
                maxOf(0, breakEnd - now)
            }
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = AppUtils.getInstalledApps(application, includeSystemApps = false)
            installedApps.value = apps
        }
    }

    private fun startTimerUpdates() {
        viewModelScope.launch {
            while (true) {
                updateRemainingTime()
                updateStrictModeRemainingTime()
                updateFocusCycleState()
                updatePermissionStatus()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun updateFocusCycleState() {
        val cycle = _uiState.value.focusCycle ?: return
        if (!cycle.isEnabled) return

        val phase = calculateFocusCyclePhase(cycle)
        val remaining = calculateFocusCycleRemainingTime(cycle, phase)

        // Phase transitions are now handled by AccessibilityService
        // ViewModel just reads the current state

        _uiState.update { it.copy(
            focusCyclePhase = phase,
            isFocusCycleInBreak = phase == FocusCyclePhase.BREAK,
            focusCycleRemainingTime = remaining
        )}
    }

    private fun updateRemainingTime() {
        val session = _uiState.value.quickBlockSession ?: return
        val endTime = session.endTime ?: return
        val remaining = endTime - System.currentTimeMillis()

        if (remaining <= 0) {
            viewModelScope.launch {
                stopQuickBlock(forceStop = true)
            }
        } else {
            _uiState.update { it.copy(remainingTime = remaining) }
        }
    }

    private fun updateStrictModeRemainingTime() {
        // Don't update if paused
        if (_uiState.value.isStrictModePaused) return

        val endTime = _uiState.value.strictModeEndTime ?: return
        val now = System.currentTimeMillis()
        val remaining = endTime - now

        if (remaining <= 0 && _uiState.value.isStrictModeLocked) {
            // Strict mode timer expired - auto disable
            viewModelScope.launch {
                repository.setStrictModeEnabled(false)
                repository.clearStrictModeEndTime()
                _uiState.update { it.copy(
                    isStrictModeLocked = false,
                    strictModeRemainingTime = 0,
                    isStrictModeEnabled = false
                )}
                showToast("Strict Mode completed!")
            }
        } else if (remaining > 0) {
            _uiState.update { it.copy(
                strictModeRemainingTime = remaining,
                isStrictModeLocked = true
            )}
        }
    }

    private fun updatePermissionStatus() {
        val status = PermissionUtils.getPermissionStatus(application)
        _uiState.update { it.copy(permissionStatus = status) }
    }

    fun getInstalledApps(): List<AppUtils.AppInfo> = installedApps.value

    fun startQuickBlock(selectedPackages: List<String>, durationMinutes: Int? = null) {
        viewModelScope.launch {
            // Get currently blocked packages BEFORE making any changes
            val previouslyBlocked = repository.getBlockedPackageNames().toSet()

            // First, ensure all selected apps are in the database and marked as blocked
            selectedPackages.forEach { packageName ->
                val existingApp = repository.getBlockedApp(packageName)
                if (existingApp == null) {
                    val appName = AppUtils.getAppName(application, packageName)
                    repository.insertBlockedApp(
                        BlockedApp(
                            packageName = packageName,
                            appName = appName,
                            isBlocked = true
                        )
                    )
                } else if (!existingApp.isBlocked) {
                    // App exists but is not blocked - update it
                    repository.setAppBlocked(packageName, true)
                }
            }

            // Create quick block session with previously blocked packages tracked
            val session = QuickBlockSession(
                startTime = System.currentTimeMillis(),
                endTime = durationMinutes?.let { System.currentTimeMillis() + it * 60 * 1000L },
                blockedPackages = selectedPackages.joinToString(","),
                isActive = true,
                isPomodoroSession = false,
                previouslyBlockedPackages = previouslyBlocked.intersect(selectedPackages.toSet()).joinToString(",")
            )
            repository.insertQuickBlockSession(session)

            // Start blocking service
            AppBlockingService.start(application)

            // Schedule peak-time reminder for mindful breaks
            PeakTimeReminderWorker.schedule(application)

            val newAppsCount = selectedPackages.count { !previouslyBlocked.contains(it) }
            showToast("Quick Block started. $newAppsCount apps blocked.")
        }
    }

    fun stopQuickBlock(forceStop: Boolean = false): StopQuickBlockResult {
        var result = StopQuickBlockResult.SUCCESS

        viewModelScope.launch {
            // Check if strict mode is time-locked - cannot be bypassed
            if (_uiState.value.isStrictModeLocked) {
                result = StopQuickBlockResult.STRICT_MODE_LOCKED
                return@launch
            }

            // Check if strict mode or hard mode prevents stopping (unless force stop with PIN)
            if (!forceStop && (_uiState.value.isStrictModeEnabled || _uiState.value.isHardModeEnabled)) {
                result = StopQuickBlockResult.NEEDS_PIN
                return@launch
            }

            val session = _uiState.value.quickBlockSession
            if (session != null) {
                // Get the apps that Quick Block added (not previously blocked)
                val blockedBySession = session.blockedPackages.split(",").filter { it.isNotBlank() }
                val previouslyBlocked = session.previouslyBlockedPackages.split(",").filter { it.isNotBlank() }.toSet()

                // Unblock only apps that were NOT previously blocked
                val appsToUnblock = blockedBySession.filter { !previouslyBlocked.contains(it) }
                var unblockCount = 0

                appsToUnblock.forEach { packageName ->
                    repository.setAppBlocked(packageName, false)
                    unblockCount++
                }

                repository.deactivateQuickBlockSession(session.id)
                showToast("Quick Block stopped. $unblockCount apps unblocked.")
            }

            repository.deactivateAllQuickBlockSessions()

            // Also update UI state immediately for responsive feedback
            _uiState.update { it.copy(
                isQuickBlockActive = false,
                quickBlockSession = null,
                remainingTime = 0,
                isPomodoroMode = false
            )}
        }

        return result
    }

    enum class StopQuickBlockResult {
        SUCCESS,
        STRICT_MODE_LOCKED,
        NEEDS_PIN
    }

    fun verifyPinAndStop(pin: String): Boolean {
        // If strict mode is time-locked, even PIN cannot bypass
        if (_uiState.value.isStrictModeLocked) {
            return false
        }

        val savedPin = kotlinx.coroutines.runBlocking { repository.getHardModePin() }
        return if (pin == savedPin) {
            stopQuickBlock(forceStop = true)
            viewModelScope.launch {
                repository.setHardModeEnabled(false)
                // Only disable strict mode if not time-locked
                if (!_uiState.value.isStrictModeLocked) {
                    repository.setStrictModeEnabled(false)
                }
            }
            true
        } else {
            false
        }
    }

    fun startPomodoroSession(selectedPackages: List<String>, workMinutes: Int = 25, breakMinutes: Int = 5) {
        viewModelScope.launch {
            // Get currently blocked packages BEFORE making any changes
            val previouslyBlocked = repository.getBlockedPackageNames().toSet()

            // Ensure all selected apps are in the database
            selectedPackages.forEach { packageName ->
                val existingApp = repository.getBlockedApp(packageName)
                if (existingApp == null) {
                    val appName = AppUtils.getAppName(application, packageName)
                    repository.insertBlockedApp(
                        BlockedApp(
                            packageName = packageName,
                            appName = appName,
                            isBlocked = true
                        )
                    )
                }
            }

            // Create pomodoro quick block session
            val session = QuickBlockSession(
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis() + workMinutes * 60 * 1000L,
                blockedPackages = selectedPackages.joinToString(","),
                isActive = true,
                isPomodoroSession = true,
                pomodoroWorkMinutes = workMinutes,
                pomodoroBreakMinutes = breakMinutes,
                previouslyBlockedPackages = previouslyBlocked.intersect(selectedPackages.toSet()).joinToString(",")
            )
            repository.insertQuickBlockSession(session)

            // Update pomodoro state
            _uiState.update { it.copy(
                isPomodoroMode = true,
                pomodoroState = it.pomodoroState.copy(
                    isActive = true,
                    currentSessionType = PomodoroSessionType.WORK,
                    remainingMillis = workMinutes * 60 * 1000L,
                    workDurationMinutes = workMinutes,
                    shortBreakMinutes = breakMinutes
                )
            )}

            // Start blocking service
            AppBlockingService.start(application)
        }
    }

    fun setStrictMode(enabled: Boolean, durationMinutes: Int? = null) {
        viewModelScope.launch {
            // If trying to disable, check if locked
            if (!enabled && _uiState.value.isStrictModeLocked) {
                // Cannot disable while locked - time must expire
                return@launch
            }

            repository.setStrictModeEnabled(enabled)

            if (enabled && durationMinutes != null && durationMinutes > 0) {
                // Set the end time for strict mode lock
                val endTime = System.currentTimeMillis() + durationMinutes * 60 * 1000L
                repository.setStrictModeEndTime(endTime)
                repository.setStrictModePaused(false) // Clear any pause state
                _uiState.update { it.copy(
                    isStrictModeLocked = true,
                    isStrictModePaused = false,
                    strictModeEndTime = endTime,
                    strictModeRemainingTime = durationMinutes * 60 * 1000L
                )}
                showToast("Strict Mode enabled for ${formatDuration(durationMinutes * 60 * 1000L)}")
            } else if (!enabled) {
                repository.clearStrictModeEndTime()
                repository.setStrictModePaused(false)
                _uiState.update { it.copy(
                    isStrictModeLocked = false,
                    isStrictModePaused = false,
                    strictModeEndTime = null,
                    strictModeRemainingTime = 0
                )}
            }
        }
    }

    /**
     * Add time to Strict Mode - extends the end timestamp
     */
    fun addStrictModeTime(additionalMinutes: Int) {
        viewModelScope.launch {
            val currentEndTime = _uiState.value.strictModeEndTime
            if (currentEndTime == null || !_uiState.value.isStrictModeLocked) {
                // If no current end time, start fresh with this duration
                setStrictMode(true, additionalMinutes)
                return@launch
            }

            // Extend the end time
            val newEndTime = currentEndTime + (additionalMinutes * 60 * 1000L)
            repository.setStrictModeEndTime(newEndTime)

            val now = System.currentTimeMillis()
            _uiState.update { it.copy(
                strictModeEndTime = newEndTime,
                strictModeRemainingTime = newEndTime - now
            )}

            showToast("Added ${additionalMinutes}min. Total: ${formatDuration(newEndTime - now)}")
        }
    }

    /**
     * Pause Strict Mode - stores remaining time for later resume
     * Returns false if cannot pause (not active), true if pause initiated
     */
    fun pauseStrictMode(): Boolean {
        if (!_uiState.value.isStrictModeLocked || _uiState.value.isStrictModePaused) {
            return false
        }

        viewModelScope.launch {
            val remainingTime = _uiState.value.strictModeRemainingTime
            repository.setStrictModeRemainingOnPause(remainingTime)
            repository.setStrictModePaused(true)
            repository.setStrictModePauseReason("user_paused")

            // Clear the end time while paused
            repository.clearStrictModeEndTime()

            _uiState.update { it.copy(
                isStrictModePaused = true,
                isStrictModeLocked = false
            )}

            showToast("Strict Mode paused. ${formatDuration(remainingTime)} remaining.")
        }

        return true
    }

    /**
     * Resume Strict Mode from pause
     */
    fun resumeStrictMode() {
        viewModelScope.launch {
            val remainingOnPause = repository.getStrictModeRemainingOnPause() ?: 0L
            if (remainingOnPause <= 0) {
                showToast("No paused session to resume")
                return@launch
            }

            // Set new end time based on remaining time
            val newEndTime = System.currentTimeMillis() + remainingOnPause
            repository.setStrictModeEndTime(newEndTime)
            repository.setStrictModePaused(false)
            repository.clearStrictModeRemainingOnPause()

            _uiState.update { it.copy(
                isStrictModePaused = false,
                isStrictModeLocked = true,
                strictModeEndTime = newEndTime,
                strictModeRemainingTime = remainingOnPause
            )}

            showToast("Strict Mode resumed. ${formatDuration(remainingOnPause)} remaining.")
        }
    }

    fun isStrictModeLocked(): Boolean = _uiState.value.isStrictModeLocked

    fun setHardMode(enabled: Boolean, pin: String? = null, unlockTimeMinutes: Int? = null) {
        viewModelScope.launch {
            repository.setHardModeEnabled(enabled)
            if (enabled && pin != null) {
                repository.setHardModePin(pin)
            }
            if (enabled && unlockTimeMinutes != null) {
                val unlockTime = System.currentTimeMillis() + unlockTimeMinutes * 60 * 1000L
                repository.setHardModeUnlockTime(unlockTime)
            }
        }
    }

    fun toggleAppBlocked(packageName: String, isBlocked: Boolean) {
        viewModelScope.launch {
            repository.setAppBlocked(packageName, isBlocked)
        }
    }

    fun refreshPermissions() {
        updatePermissionStatus()
    }

    private fun showToast(message: String) {
        Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = (millis / 60000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    // ========== Focus Cycle Methods ==========

    /**
     * Enable Focus Cycle with the specified settings
     * The cycle starts in ARMED state, waiting for user to open a tracked app
     */
    fun enableFocusCycle(
        usageWindowMinutes: Int,
        breakDurationMinutes: Int,
        selectedPackages: List<String>,
        useQuickBlockApps: Boolean
    ) {
        viewModelScope.launch {
            // Disable any existing focus cycles first
            repository.disableAllFocusCycles()

            val focusCycle = FocusCycle(
                usageWindowMinutes = usageWindowMinutes,
                breakDurationMinutes = breakDurationMinutes,
                isEnabled = true,
                isActive = true,
                isArmed = true,  // Start in armed state - waiting for app open
                isPaused = false,
                selectedPackages = selectedPackages.joinToString(","),
                useQuickBlockApps = useQuickBlockApps,
                cycleStartTime = null,  // Not started yet
                breakStartTime = null,
                accumulatedUsageMillis = 0,
                lastActiveTime = null
            )
            repository.insertFocusCycle(focusCycle)

            // Schedule peak-time reminder for mindful breaks
            PeakTimeReminderWorker.schedule(application)

            showToast("Focus Cycle armed. Open a tracked app to start.")
        }
    }

    /**
     * Disable Focus Cycle
     */
    fun disableFocusCycle() {
        viewModelScope.launch {
            val cycle = _uiState.value.focusCycle
            if (cycle != null) {
                repository.updateFocusCycle(cycle.copy(isEnabled = false, isActive = false))
            }
            showToast("Focus Cycle stopped")
        }
    }

    /**
     * Get packages that should be blocked by Focus Cycle
     */
    fun getFocusCycleBlockedPackages(): List<String> {
        val cycle = _uiState.value.focusCycle ?: return emptyList()

        return if (cycle.useQuickBlockApps) {
            // Use apps from current Quick Block session
            _uiState.value.quickBlockSession?.blockedPackages
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } else {
            cycle.selectedPackages.split(",").filter { it.isNotBlank() }
        }
    }

    /**
     * Record a Focus Cycle override (user chose to continue anyway)
     */
    fun recordFocusCycleOverride(packageName: String) {
        viewModelScope.launch {
            val cycle = _uiState.value.focusCycle ?: return@launch
            val appName = AppUtils.getAppName(application, packageName)
            repository.insertFocusCycleOverride(
                FocusCycleOverride(
                    focusCycleId = cycle.id,
                    packageName = packageName,
                    appName = appName
                )
            )
        }
    }

    /**
     * Check if Focus Cycle is currently blocking (in break phase)
     */
    fun isFocusCycleBlocking(): Boolean {
        return _uiState.value.focusCyclePhase == FocusCyclePhase.BREAK
    }
}
