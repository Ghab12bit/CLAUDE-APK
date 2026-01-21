package com.focusblock.app.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.BlockedApp
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.service.FocusBlockAccessibilityService
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val blockedApps: List<BlockedApp> = emptyList(),
    val blockedAppsCount: Int = 0,
    val allowlistApps: List<BlockedApp> = emptyList(),
    val allowlistCount: Int = 0,
    val isStrictModeEnabled: Boolean = false,
    val isStrictModeLocked: Boolean = false, // Time-locked, can't disable easily
    val strictModeEndTime: Long? = null, // When time lock expires
    val isHardModeEnabled: Boolean = false,
    val hardModeUnlockTime: Long? = null,
    // Emergency unlock tracking
    val emergencyUnlockAvailable: Boolean = true, // Once per day
    val emergencyUnlockUsedDate: String? = null,
    val permissionStatus: PermissionUtils.PermissionStatus = PermissionUtils.PermissionStatus(
        hasUsageStats = false,
        hasOverlay = false,
        hasAccessibility = false,
        hasNotification = false,
        isIgnoringBattery = false
    ),
    val pomodoroWorkMinutes: Int = 25,
    val pomodoroShortBreak: Int = 5,
    val pomodoroLongBreak: Int = 15,
    // Global Daily Limit settings
    val isGlobalDailyLimitEnabled: Boolean = false,
    val globalDailyLimitMinutes: Int = 120, // Default 2 hours
    val globalDailyLimitWarningMinutes: Int = 15,

    // 20% Usage Reduction Notification
    val isUsageReductionNotificationEnabled: Boolean = false
) {
    companion object {
        const val STRICT_MODE_MINIMUM_DURATION_MINUTES = 60 // Minimum 1 hour
        const val EMERGENCY_UNLOCK_COUNTDOWN_SECONDS = 30 // 30-second countdown friction
        const val EMERGENCY_UNLOCK_PHRASE = "I choose distraction over focus"
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val repository: FocusBlockRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val installedApps = MutableStateFlow<List<AppUtils.AppInfo>>(emptyList())

    init {
        loadSettings()
        loadInstalledApps()
        startPermissionMonitoring()
        loadGlobalDailyLimitSettings()
        loadUsageReductionNotificationSetting()
        loadEmergencyUnlockStatus()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            repository.getActiveBlockedApps().collect { apps ->
                _uiState.update { it.copy(
                    blockedApps = apps,
                    blockedAppsCount = apps.size
                )}
            }
        }

        viewModelScope.launch {
            repository.getAllowlistApps().collect { apps ->
                _uiState.update { it.copy(
                    allowlistApps = apps,
                    allowlistCount = apps.size
                )}
            }
        }

        viewModelScope.launch {
            repository.getStrictModeEnabledFlow().collect { enabled ->
                _uiState.update { it.copy(isStrictModeEnabled = enabled) }
            }
        }

        // Load strict mode lock status
        viewModelScope.launch {
            repository.getStrictModeEndTimeFlow().collect { endTime ->
                val now = System.currentTimeMillis()
                val isLocked = endTime != null && endTime > now
                _uiState.update { it.copy(
                    isStrictModeLocked = isLocked,
                    strictModeEndTime = endTime
                )}
            }
        }

        viewModelScope.launch {
            repository.getLegacyHardModeEnabledFlow().collect { enabled ->
                _uiState.update { it.copy(isHardModeEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            val workMinutes = repository.getSetting(AppSettings.KEY_POMODORO_WORK_MINUTES)?.toIntOrNull() ?: 25
            val shortBreak = repository.getSetting(AppSettings.KEY_POMODORO_SHORT_BREAK)?.toIntOrNull() ?: 5
            val longBreak = repository.getSetting(AppSettings.KEY_POMODORO_LONG_BREAK)?.toIntOrNull() ?: 15

            _uiState.update { it.copy(
                pomodoroWorkMinutes = workMinutes,
                pomodoroShortBreak = shortBreak,
                pomodoroLongBreak = longBreak
            )}
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = AppUtils.getInstalledApps(application, includeSystemApps = false)
            installedApps.value = apps
        }
    }

    private fun startPermissionMonitoring() {
        viewModelScope.launch {
            while (true) {
                val status = PermissionUtils.getPermissionStatus(application)
                _uiState.update { it.copy(permissionStatus = status) }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    fun getInstalledApps(): List<AppUtils.AppInfo> = installedApps.value

    fun updateBlockedApps(packageNames: List<String>) {
        viewModelScope.launch {
            // Remove apps no longer in the list
            val currentBlocked = _uiState.value.blockedApps
            currentBlocked.forEach { app ->
                if (!packageNames.contains(app.packageName)) {
                    repository.deleteBlockedApp(app)
                }
            }

            // Add new apps
            packageNames.forEach { packageName ->
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
                    repository.setAppBlocked(packageName, true)
                }
            }
        }
    }

    fun updateAllowlist(packageNames: List<String>) {
        viewModelScope.launch {
            // Clear current allowlist
            _uiState.value.allowlistApps.forEach { app ->
                repository.updateBlockedApp(app.copy(isInAllowlist = false))
            }

            // Set new allowlist
            packageNames.forEach { packageName ->
                val existingApp = repository.getBlockedApp(packageName)
                if (existingApp != null) {
                    repository.updateBlockedApp(existingApp.copy(isInAllowlist = true))
                } else {
                    val appName = AppUtils.getAppName(application, packageName)
                    repository.insertBlockedApp(
                        BlockedApp(
                            packageName = packageName,
                            appName = appName,
                            isBlocked = false,
                            isInAllowlist = true
                        )
                    )
                }
            }
        }
    }

    /**
     * Set Strict Mode with protection against easy bypass
     * @return StrictModeResult indicating if action was allowed
     */
    fun setStrictMode(enabled: Boolean): StrictModeResult {
        // Enabling now requires duration selection - signal UI to show duration picker
        if (enabled) {
            return StrictModeResult.NEEDS_DURATION
        }

        // Disabling requires checks
        val state = _uiState.value

        // If time-locked, cannot disable (emergency unlock is the only way)
        if (state.isStrictModeLocked) {
            return StrictModeResult.TIME_LOCKED
        }

        // If Hard Mode is enabled, require PIN
        if (state.isHardModeEnabled) {
            return StrictModeResult.NEEDS_PIN
        }

        // Otherwise allow disable
        viewModelScope.launch {
            repository.setStrictModeEnabled(false)
        }
        return StrictModeResult.SUCCESS
    }

    /**
     * Enable Strict Mode with a specific duration (minimum 60 minutes enforced)
     * @param durationMinutes Duration in minutes (will be enforced to minimum 60)
     */
    fun enableStrictModeWithDuration(durationMinutes: Int) {
        val actualDuration = maxOf(durationMinutes, SettingsUiState.STRICT_MODE_MINIMUM_DURATION_MINUTES)
        viewModelScope.launch {
            repository.setStrictModeEnabled(true)
            val endTime = System.currentTimeMillis() + actualDuration * 60 * 1000L
            repository.setStrictModeEndTime(endTime)
            _uiState.update { it.copy(
                isStrictModeEnabled = true,
                isStrictModeLocked = true,
                strictModeEndTime = endTime
            )}
            // Notify accessibility service
            notifyServiceToRefreshStrictModeCache()
        }
    }

    /**
     * Notify accessibility service to refresh Strict Mode cache
     */
    private fun notifyServiceToRefreshStrictModeCache() {
        val intent = Intent(FocusBlockAccessibilityService.ACTION_REFRESH_GLOBAL_LIMIT_CACHE)
        intent.`package` = application.packageName
        application.sendBroadcast(intent)
    }

    /**
     * Check if emergency unlock is available today
     */
    fun isEmergencyUnlockAvailable(): Boolean {
        return _uiState.value.emergencyUnlockAvailable
    }

    /**
     * Load emergency unlock status (resets at midnight)
     */
    private fun loadEmergencyUnlockStatus() {
        viewModelScope.launch {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            val usedDate = repository.getSetting(AppSettings.KEY_STRICT_MODE_EMERGENCY_UNLOCK_USED_DATE)
            val isAvailable = usedDate != today
            _uiState.update { it.copy(
                emergencyUnlockAvailable = isAvailable,
                emergencyUnlockUsedDate = usedDate
            )}
        }
    }

    /**
     * Perform emergency unlock - consumes today's unlock
     * Should only be called after UI friction (30s countdown + phrase typing)
     */
    fun performEmergencyUnlock(): Boolean {
        val state = _uiState.value
        if (!state.emergencyUnlockAvailable || !state.isStrictModeLocked) {
            return false
        }

        viewModelScope.launch {
            // Record that emergency unlock was used today
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            repository.setSetting(AppSettings.KEY_STRICT_MODE_EMERGENCY_UNLOCK_USED_DATE, today)

            // Disable Strict Mode
            repository.setStrictModeEnabled(false)
            repository.clearStrictModeEndTime()

            _uiState.update { it.copy(
                isStrictModeEnabled = false,
                isStrictModeLocked = false,
                strictModeEndTime = null,
                emergencyUnlockAvailable = false,
                emergencyUnlockUsedDate = today
            )}

            // Notify service
            notifyServiceToRefreshStrictModeCache()
        }
        return true
    }

    /**
     * Verify PIN and disable Strict Mode
     */
    fun verifyPinAndDisableStrictMode(pin: String): Boolean {
        val storedPin = kotlinx.coroutines.runBlocking { repository.getHardModePin() }
        return if (pin == storedPin) {
            viewModelScope.launch {
                repository.setStrictModeEnabled(false)
                repository.clearStrictModeEndTime()
                _uiState.update { it.copy(
                    isStrictModeEnabled = false,
                    isStrictModeLocked = false,
                    strictModeEndTime = null
                )}
            }
            true
        } else {
            false
        }
    }

    enum class StrictModeResult {
        SUCCESS,
        TIME_LOCKED,
        NEEDS_PIN,
        NEEDS_DURATION
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            repository.setHardModePin(pin)
        }
    }

    fun enableHardMode(pin: String, unlockMinutes: Int) {
        viewModelScope.launch {
            repository.setHardModePin(pin)
            repository.setLegacyHardModeEnabled(true)
            repository.setStrictModeEnabled(true)
            val unlockTime = System.currentTimeMillis() + unlockMinutes * 60 * 1000L
            repository.setHardModeUnlockTime(unlockTime)
        }
    }

    fun disableHardMode(pin: String): Boolean {
        // Verify PIN first
        viewModelScope.launch {
            val storedPin = repository.getHardModePin()
            if (storedPin == pin) {
                repository.setLegacyHardModeEnabled(false)
            }
        }
        return true // Simplified
    }

    fun setPomodoroSettings(workMinutes: Int, shortBreak: Int, longBreak: Int) {
        viewModelScope.launch {
            repository.setSetting(AppSettings.KEY_POMODORO_WORK_MINUTES, workMinutes.toString())
            repository.setSetting(AppSettings.KEY_POMODORO_SHORT_BREAK, shortBreak.toString())
            repository.setSetting(AppSettings.KEY_POMODORO_LONG_BREAK, longBreak.toString())

            _uiState.update { it.copy(
                pomodoroWorkMinutes = workMinutes,
                pomodoroShortBreak = shortBreak,
                pomodoroLongBreak = longBreak
            )}
        }
    }

    fun setPomodoroWorkMinutes(minutes: Int) {
        viewModelScope.launch {
            repository.setSetting(AppSettings.KEY_POMODORO_WORK_MINUTES, minutes.toString())
            _uiState.update { it.copy(pomodoroWorkMinutes = minutes) }
        }
    }

    fun setPomodoroShortBreak(minutes: Int) {
        viewModelScope.launch {
            repository.setSetting(AppSettings.KEY_POMODORO_SHORT_BREAK, minutes.toString())
            _uiState.update { it.copy(pomodoroShortBreak = minutes) }
        }
    }

    fun setPomodoroLongBreak(minutes: Int) {
        viewModelScope.launch {
            repository.setSetting(AppSettings.KEY_POMODORO_LONG_BREAK, minutes.toString())
            _uiState.update { it.copy(pomodoroLongBreak = minutes) }
        }
    }

    // ========== GLOBAL DAILY LIMIT SETTINGS ==========

    private fun loadGlobalDailyLimitSettings() {
        viewModelScope.launch {
            repository.getGlobalDailyLimitSettings().collect { settings ->
                if (settings != null) {
                    _uiState.update { it.copy(
                        isGlobalDailyLimitEnabled = settings.isEnabled,
                        globalDailyLimitMinutes = settings.dailyLimitMinutes,
                        globalDailyLimitWarningMinutes = settings.warningMinutesBefore
                    )}
                }
            }
        }
    }

    /**
     * Notify the Accessibility Service to refresh its cache immediately
     * This ensures settings changes take effect right away
     */
    private fun notifyServiceToRefreshGlobalLimitCache() {
        val intent = Intent(FocusBlockAccessibilityService.ACTION_REFRESH_GLOBAL_LIMIT_CACHE)
        intent.`package` = application.packageName
        application.sendBroadcast(intent)
    }

    fun setGlobalDailyLimitEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val currentSettings = repository.getGlobalDailyLimitSettingsSync()
            if (currentSettings != null) {
                repository.updateGlobalDailyLimitSettings(currentSettings.copy(
                    isEnabled = enabled,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                repository.saveGlobalDailyLimitSettings(
                    com.focusblock.app.database.entity.GlobalDailyLimitSettings(
                        isEnabled = enabled
                    )
                )
            }
            _uiState.update { it.copy(isGlobalDailyLimitEnabled = enabled) }
            // Notify service to refresh cache immediately
            notifyServiceToRefreshGlobalLimitCache()
        }
    }

    fun setGlobalDailyLimit(minutes: Int) {
        viewModelScope.launch {
            val currentSettings = repository.getGlobalDailyLimitSettingsSync()
            if (currentSettings != null) {
                repository.updateGlobalDailyLimitSettings(currentSettings.copy(
                    dailyLimitMinutes = minutes,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                repository.saveGlobalDailyLimitSettings(
                    com.focusblock.app.database.entity.GlobalDailyLimitSettings(
                        dailyLimitMinutes = minutes
                    )
                )
            }
            _uiState.update { it.copy(globalDailyLimitMinutes = minutes) }
            // Notify service to refresh cache immediately
            notifyServiceToRefreshGlobalLimitCache()
        }
    }

    fun setGlobalDailyLimitWarning(minutes: Int) {
        viewModelScope.launch {
            val currentSettings = repository.getGlobalDailyLimitSettingsSync()
            if (currentSettings != null) {
                repository.updateGlobalDailyLimitSettings(currentSettings.copy(
                    warningMinutesBefore = minutes,
                    updatedAt = System.currentTimeMillis()
                ))
            }
            _uiState.update { it.copy(globalDailyLimitWarningMinutes = minutes) }
            // Notify service to refresh cache immediately
            notifyServiceToRefreshGlobalLimitCache()
        }
    }

    // ========== 20% USAGE REDUCTION NOTIFICATION ==========

    private fun loadUsageReductionNotificationSetting() {
        viewModelScope.launch {
            val enabled = repository.getSetting(AppSettings.KEY_USAGE_REDUCTION_NOTIFICATION_ENABLED)?.toBooleanStrictOrNull() ?: false
            _uiState.update { it.copy(isUsageReductionNotificationEnabled = enabled) }
        }
    }

    fun setUsageReductionNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setSetting(AppSettings.KEY_USAGE_REDUCTION_NOTIFICATION_ENABLED, enabled.toString())
            _uiState.update { it.copy(isUsageReductionNotificationEnabled = enabled) }
            // Notify service to refresh its cache
            notifyServiceToRefreshGlobalLimitCache()
        }
    }
}
