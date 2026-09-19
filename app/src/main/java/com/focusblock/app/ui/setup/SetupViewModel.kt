package com.focusblock.app.ui.setup

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.blocking.BlockingEngine
import com.focusblock.app.blocking.RuleAlarmScheduler
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.database.dao.BlockRuleDao
import com.focusblock.app.database.dao.BlockedAppDao
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.BlockedApp
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class SetupStep { WHAT_IT_DOES, PERMISSIONS, CHOOSE_APPS, EVENING_BLOCK, DONE }

data class SetupApp(
    val packageName: String,
    val label: String,
    val minutesPerDay: Int,
    val selected: Boolean,
    /** Communication or utility apps: never pre-ticked, always labelled. */
    val essential: Boolean
)

data class SetupUiState(
    val step: SetupStep = SetupStep.WHAT_IT_DOES,
    val hasAccessibility: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val hasOverlay: Boolean = false,
    val apps: List<SetupApp> = emptyList(),
    val appsLoading: Boolean = false,
    val usageDataAvailable: Boolean = false,
    val startMinute: Int = 20 * 60 + 45,
    val endMinute: Int = 22 * 60 + 30,
    val daysOfWeek: String = "1,2,3,4,5",
    val commitment: CommitmentLevel = CommitmentLevel.LOCKED,
    val saving: Boolean = false,
    val message: String? = null
) {
    val selectedPackages: List<String> get() = apps.filter { it.selected }.map { it.packageName }

    /** Blocking cannot happen at all without these two. */
    val canEnforce: Boolean get() = hasAccessibility && hasUsageAccess
}

/**
 * First-run setup.
 *
 * The goal is narrow and measurable: get from a fresh install to one real
 * routine that will actually fire tonight, in about a minute, without the user
 * having to understand the app's internals first.
 *
 * It asks for exactly three things -- permission, which apps, what time --
 * and then it is finished. There is no goal to type, no intention to declare
 * and no tour.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    application: Application,
    private val ruleDao: BlockRuleDao,
    private val blockedAppDao: BlockedAppDao,
    private val engine: BlockingEngine
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val status = PermissionUtils.getPermissionStatus(getApplication())
        _uiState.update {
            it.copy(
                hasAccessibility = status.hasAccessibility,
                hasUsageAccess = status.hasUsageStats,
                hasOverlay = status.hasOverlay
            )
        }
    }

    fun goTo(step: SetupStep) {
        _uiState.update { it.copy(step = step) }
        if (step == SetupStep.CHOOSE_APPS && _uiState.value.apps.isEmpty()) loadApps()
    }

    fun next() {
        val state = _uiState.value
        val next = when (state.step) {
            SetupStep.WHAT_IT_DOES -> SetupStep.PERMISSIONS
            SetupStep.PERMISSIONS -> SetupStep.CHOOSE_APPS
            SetupStep.CHOOSE_APPS -> SetupStep.EVENING_BLOCK
            SetupStep.EVENING_BLOCK -> SetupStep.DONE
            SetupStep.DONE -> SetupStep.DONE
        }
        goTo(next)
    }

    fun back() {
        val state = _uiState.value
        val prev = when (state.step) {
            SetupStep.WHAT_IT_DOES -> SetupStep.WHAT_IT_DOES
            SetupStep.PERMISSIONS -> SetupStep.WHAT_IT_DOES
            SetupStep.CHOOSE_APPS -> SetupStep.PERMISSIONS
            SetupStep.EVENING_BLOCK -> SetupStep.CHOOSE_APPS
            SetupStep.DONE -> SetupStep.EVENING_BLOCK
        }
        goTo(prev)
    }

    /**
     * Build the app list, pre-ticking the user's actual biggest time sinks
     * rather than a hardcoded guess, when usage access allows it.
     *
     * Communication apps are never pre-ticked. The user needs WhatsApp for
     * clients, and an app that silently decides messaging is a distraction is
     * the reason the previous Strict Mode was unusable.
     */
    private fun loadApps() {
        _uiState.update { it.copy(appsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val usage = weeklyUsageMinutes(context)
            val installed = AppUtils.getInstalledApps(context)
                .filter { it.packageName !in BlockRule.NEVER_BLOCK }

            val rows = installed.map { app ->
                val essential = app.packageName in RuleTemplates.ESSENTIAL_NEVER_SUGGEST
                val minutes = usage[app.packageName] ?: 0
                SetupApp(
                    packageName = app.packageName,
                    label = app.appName,
                    minutesPerDay = minutes,
                    // Pre-tick a known distraction, or anything eating 20+ min a
                    // day, but never something the user may need to reach people.
                    selected = !essential && (
                        app.packageName in RuleTemplates.COMMON_DISTRACTIONS || minutes >= 20
                        ),
                    essential = essential
                )
            }.sortedWith(
                compareByDescending<SetupApp> { it.minutesPerDay }
                    .thenBy { it.label.lowercase() }
            )

            _uiState.update {
                it.copy(
                    apps = rows,
                    appsLoading = false,
                    usageDataAvailable = usage.isNotEmpty()
                )
            }
        }
    }

    /** Average daily minutes per package over the last week. Empty without permission. */
    private fun weeklyUsageMinutes(context: Context): Map<String, Int> {
        if (!PermissionUtils.hasUsageStatsPermission(context)) return emptyMap()
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - TimeUnit.DAYS.toMillis(7)
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                .filter { it.totalTimeInForeground > 0 }
                .groupBy { it.packageName }
                .mapValues { (_, stats) ->
                    val totalMs = stats.sumOf { it.totalTimeInForeground }
                    (totalMs / 7 / 60_000L).toInt()
                }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun toggleApp(packageName: String) {
        _uiState.update { state ->
            state.copy(apps = state.apps.map {
                if (it.packageName == packageName) it.copy(selected = !it.selected) else it
            })
        }
    }

    fun setTimes(start: Int, end: Int) =
        _uiState.update { it.copy(startMinute = start, endMinute = end) }

    fun setDays(days: String) = _uiState.update { it.copy(daysOfWeek = days) }

    fun setCommitment(level: CommitmentLevel) = _uiState.update { it.copy(commitment = level) }

    /**
     * Create the routine and finish.
     *
     * Also records the chosen apps as known apps, and puts the user's
     * communication apps on the real allowlist so that no rule -- including a
     * locked one -- can ever cut off a client.
     */
    fun finish(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.selectedPackages.isEmpty()) {
            _uiState.update { it.copy(message = "Choose at least one app to block.") }
            return
        }
        _uiState.update { it.copy(saving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                for (pkg in state.selectedPackages) {
                    blockedAppDao.insert(
                        BlockedApp(
                            packageName = pkg,
                            appName = AppUtils.getAppName(context, pkg),
                            isBlocked = true
                        )
                    )
                }

                // Anything the user did NOT pick that is a communication or
                // utility app becomes explicitly allowed, so it stays reachable
                // under every rule, including PIN-locked ones.
                for (app in state.apps) {
                    if (app.essential && !app.selected) {
                        blockedAppDao.insert(
                            BlockedApp(
                                packageName = app.packageName,
                                appName = app.label,
                                isBlocked = false,
                                isInAllowlist = true
                            )
                        )
                    }
                }

                val rule = RuleTemplates.EVENING_WORK.build(state.selectedPackages).copy(
                    startMinute = state.startMinute,
                    endMinute = state.endMinute,
                    daysOfWeek = state.daysOfWeek,
                    commitment = state.commitment
                )
                ruleDao.insert(rule)

                engine.invalidate()
                RuleAlarmScheduler.rescheduleAll(context, ruleDao.getAllRulesSync())

                _uiState.update { it.copy(saving = false) }
                onComplete()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(saving = false, message = "Couldn't save that. Please try again.")
                }
            }
        }
    }

    fun openAccessibilitySettings() =
        PermissionUtils.openAccessibilitySettings(getApplication())

    fun openUsageAccessSettings() =
        PermissionUtils.openUsageAccessSettings(getApplication())

    fun openOverlaySettings() =
        PermissionUtils.openOverlaySettings(getApplication())

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
