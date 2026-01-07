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
    val isHardModeEnabled: Boolean = false,
    val hardModeUnlockTime: Long? = null,
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
    val globalDailyLimitWarningMinutes: Int = 15
)

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

        viewModelScope.launch {
            repository.getHardModeEnabledFlow().collect { enabled ->
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

    fun setStrictMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setStrictModeEnabled(enabled)
        }
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            repository.setHardModePin(pin)
        }
    }

    fun enableHardMode(pin: String, unlockMinutes: Int) {
        viewModelScope.launch {
            repository.setHardModePin(pin)
            repository.setHardModeEnabled(true)
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
                repository.setHardModeEnabled(false)
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
}
