package com.focusblock.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.blocking.BlockingEngine
import com.focusblock.app.blocking.PinHasher
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.database.dao.BlockRuleDao
import com.focusblock.app.database.dao.BlockedAppDao
import com.focusblock.app.database.dao.ProtectionLockDao
import com.focusblock.app.database.entity.BlockedApp
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.database.entity.ProtectionLock
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AllowlistApp(
    val packageName: String,
    val label: String,
    val allowed: Boolean,
    val suggestedEssential: Boolean
)

data class ProfileUiState(
    val loading: Boolean = true,
    val lock: ProtectionLock = ProtectionLock(),
    val ruleCount: Int = 0,
    val allowlistCount: Int = 0,
    val apps: List<AllowlistApp> = emptyList(),
    val appsLoading: Boolean = false,
    val hasAccessibility: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val hasOverlay: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val canScheduleExactAlarms: Boolean = true,
    val message: String? = null
)

/**
 * Settings, reduced to what still exists.
 *
 * The old screen offered a Daily Usage Limit toggle, 20% Reduction Alerts,
 * Strict Mode, Hard Mode, Change PIN and three Pomodoro durations. Most of
 * those no longer drive anything: limits are rules now, the alerts are gone,
 * and Strict/Hard Mode are one ProtectionLock. Leaving dead toggles on screen
 * is worse than removing them -- a switch that does nothing is exactly what
 * made the old app impossible to trust.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    application: Application,
    private val ruleDao: BlockRuleDao,
    private val blockedAppDao: BlockedAppDao,
    private val lockDao: ProtectionLockDao,
    private val engine: BlockingEngine
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val perms = PermissionUtils.getPermissionStatus(context)
            val lock = lockDao.getLockSync() ?: ProtectionLock()
            _uiState.update {
                it.copy(
                    loading = false,
                    lock = lock,
                    ruleCount = ruleDao.getAllRulesSync().count { r -> r.isEnabled },
                    allowlistCount = blockedAppDao.getAllowlistPackages().size,
                    hasAccessibility = perms.hasAccessibility,
                    hasUsageAccess = perms.hasUsageStats,
                    hasOverlay = perms.hasOverlay,
                    batteryUnrestricted = perms.isIgnoringBattery,
                    canScheduleExactAlarms = exactAlarmsAllowed()
                )
            }
        }
    }

    /**
     * On Android 14+ SCHEDULE_EXACT_ALARM is not granted automatically. Without
     * it a rule boundary fires in an inexact window, so a block can start or
     * lift up to a minute late. The scheduler degrades rather than crashing,
     * but the user is told rather than left guessing.
     */
    private fun exactAlarmsAllowed(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return try {
            val am = getApplication<Application>()
                .getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            true
        }
    }

    fun openExactAlarmSettings() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:${getApplication<Application>().packageName}")
            ).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK }
            getApplication<Application>().startActivity(intent)
        }
    }

    // ------------------------------------------------------------------
    // Allowlist
    // ------------------------------------------------------------------

    fun loadApps() {
        _uiState.update { it.copy(appsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val allowed = blockedAppDao.getAllowlistPackages().toSet()
            val apps = AppUtils.getInstalledApps(context)
                .map {
                    AllowlistApp(
                        packageName = it.packageName,
                        label = it.appName,
                        allowed = it.packageName in allowed,
                        suggestedEssential = it.packageName in RuleTemplates.ESSENTIAL_NEVER_SUGGEST
                    )
                }
                .sortedWith(
                    compareByDescending<AllowlistApp> { it.allowed }
                        .thenByDescending { it.suggestedEssential }
                        .thenBy { it.label.lowercase() }
                )
            _uiState.update { it.copy(apps = apps, appsLoading = false) }
        }
    }

    /**
     * Allowlisting is always permitted, even while protection is locked.
     *
     * The lock exists to stop you weakening your *blocking*, not to strand you
     * without a phone. Making an app reachable is a safety valve and must never
     * be something a commitment can take away.
     */
    fun toggleAllowlist(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val existing = blockedAppDao.getBlockedApp(packageName)
            val nowAllowed = !(existing?.isInAllowlist ?: false)

            if (existing == null) {
                blockedAppDao.insert(
                    BlockedApp(
                        packageName = packageName,
                        appName = AppUtils.getAppName(context, packageName),
                        isBlocked = false,
                        isInAllowlist = nowAllowed
                    )
                )
            } else {
                blockedAppDao.setAllowlisted(packageName, nowAllowed)
            }

            engine.invalidate()
            _uiState.update { state ->
                state.copy(
                    apps = state.apps.map {
                        if (it.packageName == packageName) it.copy(allowed = nowAllowed) else it
                    },
                    allowlistCount = state.allowlistCount + if (nowAllowed) 1 else -1
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Protection lock
    // ------------------------------------------------------------------

    /**
     * Set a PIN. Stored salted and hashed; the PIN itself is never persisted.
     */
    fun setPin(pin: String) {
        if (pin.length < 4) {
            _uiState.update { it.copy(message = "Use at least 4 digits.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val current = lockDao.getLockSync() ?: ProtectionLock()
            if (current.isLocked() && current.pinHash.isNotEmpty()) {
                _uiState.update { it.copy(message = "Protection is locked; the PIN can't be changed yet.") }
                return@launch
            }
            val salt = PinHasher.newSalt()
            lockDao.upsert(
                current.copy(
                    pinHash = PinHasher.hash(pin, salt),
                    pinSalt = salt,
                    updatedAt = System.currentTimeMillis()
                )
            )
            _uiState.update { it.copy(message = "PIN saved.") }
            refresh()
        }
    }

    /**
     * Freeze configuration for a period. Extending is always allowed;
     * shortening or clearing while it holds is not, which is the entire point.
     */
    fun lockConfiguration(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val current = lockDao.getLockSync() ?: ProtectionLock()
            val proposed = now + hours * 3_600_000L
            if (current.isLocked(now) && proposed < current.lockedUntil) {
                _uiState.update {
                    it.copy(message = "Protection is already locked for longer. You can only extend it.")
                }
                return@launch
            }
            lockDao.upsert(
                current.copy(
                    level = if (current.pinHash.isNotEmpty()) CommitmentLevel.PIN_LOCKED
                    else CommitmentLevel.LOCKED,
                    lockedUntil = proposed,
                    unlockRequestedAt = 0L,
                    updatedAt = now
                )
            )
            engine.invalidate()
            _uiState.update { it.copy(message = "Protection locked for ${hours}h.") }
            refresh()
        }
    }

    fun unlockConfiguration() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = lockDao.getLockSync() ?: return@launch
            if (current.isLocked()) {
                _uiState.update {
                    it.copy(message = "Still locked. It clears on its own when the time is up.")
                }
                return@launch
            }
            lockDao.upsert(
                current.copy(
                    level = CommitmentLevel.OFF,
                    lockedUntil = 0L,
                    unlockRequestedAt = 0L,
                    updatedAt = System.currentTimeMillis()
                )
            )
            engine.invalidate()
            refresh()
        }
    }

    fun openAccessibilitySettings() = PermissionUtils.openAccessibilitySettings(getApplication())
    fun openUsageAccessSettings() = PermissionUtils.openUsageAccessSettings(getApplication())
    fun openOverlaySettings() = PermissionUtils.openOverlaySettings(getApplication())
    fun openBatterySettings() = PermissionUtils.requestIgnoreBatteryOptimizations(getApplication())

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
