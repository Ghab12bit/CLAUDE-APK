package com.focusblock.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.database.entity.*
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import com.focusblock.app.utils.TimeUtils
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
    val pomodoroState: PomodoroState = PomodoroState()
)

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
            // Load hard mode status
            repository.getHardModeEnabledFlow().collect { enabled ->
                _uiState.update { it.copy(isHardModeEnabled = enabled) }
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
                updatePermissionStatus()
                kotlinx.coroutines.delay(1000)
            }
        }
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

            // Create quick block session
            val session = QuickBlockSession(
                startTime = System.currentTimeMillis(),
                endTime = durationMinutes?.let { System.currentTimeMillis() + it * 60 * 1000L },
                blockedPackages = selectedPackages.joinToString(","),
                isActive = true,
                isPomodoroSession = false
            )
            repository.insertQuickBlockSession(session)

            // Start blocking service
            AppBlockingService.start(application)
        }
    }

    fun stopQuickBlock(forceStop: Boolean = false) {
        viewModelScope.launch {
            // Check if strict mode is time-locked - cannot be bypassed
            if (_uiState.value.isStrictModeLocked) {
                // Strict mode is time-locked - absolutely cannot stop until timer expires
                return@launch
            }

            // Check if strict mode or hard mode prevents stopping (unless force stop with PIN)
            if (!forceStop && (_uiState.value.isStrictModeEnabled || _uiState.value.isHardModeEnabled)) {
                // Can't stop - needs PIN or wait for timer
                return@launch
            }

            val session = _uiState.value.quickBlockSession
            if (session != null) {
                repository.deactivateQuickBlockSession(session.id)
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
                pomodoroBreakMinutes = breakMinutes
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
                _uiState.update { it.copy(
                    isStrictModeLocked = true,
                    strictModeEndTime = endTime,
                    strictModeRemainingTime = durationMinutes * 60 * 1000L
                )}
            } else if (!enabled) {
                repository.clearStrictModeEndTime()
                _uiState.update { it.copy(
                    isStrictModeLocked = false,
                    strictModeEndTime = null,
                    strictModeRemainingTime = 0
                )}
            }
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
}
