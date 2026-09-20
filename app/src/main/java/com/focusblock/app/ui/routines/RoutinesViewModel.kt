package com.focusblock.app.ui.routines

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.blocking.BlockingEngine
import com.focusblock.app.blocking.RuleAlarmScheduler
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.database.dao.BlockRuleDao
import com.focusblock.app.database.dao.BlockedAppDao
import com.focusblock.app.database.dao.ProtectionLockDao
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.database.entity.RuleKind
import com.focusblock.app.database.entity.UsageWindow
import com.focusblock.app.utils.AppUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutineRow(
    val rule: BlockRule,
    val summary: String,
    val appCount: Int,
    val isActiveNow: Boolean
)

data class PickableApp(
    val packageName: String,
    val label: String,
    val selected: Boolean,
    /** True for apps that should not be blocked without a deliberate choice. */
    val essential: Boolean
)

data class RoutinesUiState(
    val loading: Boolean = true,
    val routines: List<RoutineRow> = emptyList(),
    val editing: BlockRule? = null,
    val pickerApps: List<PickableApp> = emptyList(),
    val pickerLoading: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class RoutinesViewModel @Inject constructor(
    application: Application,
    private val ruleDao: BlockRuleDao,
    private val blockedAppDao: BlockedAppDao,
    private val lockDao: ProtectionLockDao,
    private val engine: BlockingEngine
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RoutinesUiState())
    val uiState: StateFlow<RoutinesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val rows = ruleDao.getAllRulesSync().map { rule ->
                RoutineRow(
                    rule = rule,
                    summary = summarise(rule),
                    appCount = rule.packageList().size,
                    isActiveNow = rule.isEnabled && when (rule.kind) {
                        RuleKind.MANUAL -> rule.manualIsRunning(now)
                        RuleKind.AUTOMATIC -> rule.timeConditionMatches(cal)
                    }
                )
            }
            _uiState.update { it.copy(loading = false, routines = rows) }
        }
    }

    /**
     * One line describing a rule in the terms the user set it up in, not in
     * terms of the mode it used to belong to.
     */
    private fun summarise(rule: BlockRule): String {
        val parts = mutableListOf<String>()

        if (rule.hasTimeCondition) {
            parts += "${clock(rule.startMinute)} - ${clock(rule.endMinute)}"
            parts += days(rule.daysOfWeek)
        }
        if (rule.hasUsageCondition) {
            parts += when (rule.usageWindow) {
                UsageWindow.DAILY -> "${rule.usageLimitMinutes}m a day"
                UsageWindow.HOURLY -> "${rule.usageLimitMinutes}m an hour"
            }
        }
        if (rule.hasLaunchCondition) {
            parts += when (rule.launchWindow) {
                UsageWindow.DAILY -> "${rule.launchLimit} opens a day"
                UsageWindow.HOURLY -> "${rule.launchLimit} opens an hour"
            }
        }
        if (rule.kind == RuleKind.MANUAL) {
            parts += if (rule.isManualActive) "running now" else "not running"
        }
        if (rule.commitment != CommitmentLevel.OFF) {
            parts += when (rule.commitment) {
                CommitmentLevel.LOCKED -> "locked"
                CommitmentLevel.PIN_LOCKED -> "PIN locked"
                CommitmentLevel.OFF -> ""
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun clock(minuteOfDay: Int): String {
        val h24 = minuteOfDay / 60
        val m = minuteOfDay % 60
        val suffix = if (h24 < 12) "am" else "pm"
        val h = when {
            h24 == 0 -> 12
            h24 > 12 -> h24 - 12
            else -> h24
        }
        return if (m == 0) "$h$suffix" else String.format("%d:%02d%s", h, m, suffix)
    }

    private fun days(csv: String): String {
        val list = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted()
        return when {
            list.size == 7 -> "every day"
            list == listOf(1, 2, 3, 4, 5) -> "weekdays"
            list == listOf(6, 7) -> "weekends"
            else -> list.joinToString(" ") { NAMES[it - 1] }
        }
    }

    // ------------------------------------------------------------------
    // Creating and editing
    // ------------------------------------------------------------------

    /**
     * Start a new rule from a template. The rule is NOT saved yet: the user
     * sees the filled-in form first and confirms it. A template is a starting
     * point, never a behaviour applied behind their back.
     */
    fun beginFromTemplate(template: RuleTemplates.Template) {
        viewModelScope.launch(Dispatchers.IO) {
            val suggested = RuleTemplates.safeSuggestions(
                (blockedAppDao.getBlockedPackageNames() + RuleTemplates.COMMON_DISTRACTIONS).distinct()
            )
            val draft = template.build(suggested)
            _uiState.update { it.copy(editing = draft) }
            loadPicker(draft.packageList().toSet())
        }
    }

    fun beginEditing(rule: BlockRule) {
        _uiState.update { it.copy(editing = rule) }
        loadPicker(rule.packageList().toSet())
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editing = null, pickerApps = emptyList()) }
    }

    fun updateDraft(transform: (BlockRule) -> BlockRule) {
        _uiState.update { state ->
            state.copy(editing = state.editing?.let(transform))
        }
    }

    fun toggleApp(packageName: String) {
        _uiState.update { state ->
            val draft = state.editing ?: return@update state
            val current = draft.packageList().toMutableSet()
            if (!current.add(packageName)) current.remove(packageName)
            state.copy(
                editing = draft.copy(packages = current.joinToString(",")),
                pickerApps = state.pickerApps.map {
                    if (it.packageName == packageName) it.copy(selected = packageName in current) else it
                }
            )
        }
    }

    private fun loadPicker(selected: Set<String>) {
        _uiState.update { it.copy(pickerLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val apps = AppUtils.getInstalledApps(getApplication())
                .filter { it.packageName !in BlockRule.NEVER_BLOCK }
                .map {
                    PickableApp(
                        packageName = it.packageName,
                        label = it.appName,
                        selected = it.packageName in selected,
                        essential = it.packageName in RuleTemplates.ESSENTIAL_NEVER_SUGGEST
                    )
                }
                // Selected first, then essentials last so they are not picked by accident.
                .sortedWith(compareByDescending<PickableApp> { it.selected }
                    .thenBy { it.essential }
                    .thenBy { it.label.lowercase() })
            _uiState.update { it.copy(pickerApps = apps, pickerLoading = false) }
        }
    }

    /**
     * Save the draft.
     *
     * Refuses to save a rule with no apps rather than creating something that
     * silently does nothing -- one of the ways the old app left the user
     * believing they were protected when they were not.
     */
    fun saveDraft() {
        val draft = _uiState.value.editing ?: return
        if (draft.packageList().isEmpty()) {
            _uiState.update { it.copy(message = "Choose at least one app, or this routine won't do anything.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (draft.id == 0L) {
                ruleDao.insert(draft.copy(updatedAt = now))
            } else {
                ruleDao.update(draft.copy(updatedAt = now))
            }
            engine.invalidate()
            RuleAlarmScheduler.rescheduleAll(getApplication(), ruleDao.getAllRulesSync())
            _uiState.update { it.copy(editing = null, pickerApps = emptyList(), message = "Saved.") }
            refresh()
        }
    }

    /**
     * Enabling is always allowed. Disabling is refused while the protection
     * lock holds, because weakening your own rules is exactly what the lock
     * exists to prevent -- and it says so rather than failing silently.
     */
    fun setEnabled(rule: BlockRule, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!enabled) {
                val lock = lockDao.getLockSync()
                if (lock != null && lock.isLocked()) {
                    _uiState.update {
                        it.copy(message = "Protection is locked, so routines can't be turned off yet.")
                    }
                    return@launch
                }
            }
            ruleDao.setEnabled(rule.id, enabled)
            engine.invalidate()
            RuleAlarmScheduler.rescheduleAll(getApplication(), ruleDao.getAllRulesSync())
            refresh()
        }
    }

    fun delete(rule: BlockRule) {
        viewModelScope.launch(Dispatchers.IO) {
            val lock = lockDao.getLockSync()
            if (lock != null && lock.isLocked()) {
                _uiState.update {
                    it.copy(message = "Protection is locked, so routines can't be deleted yet.")
                }
                return@launch
            }
            ruleDao.delete(rule)
            ruleDao.clearOverridesFor(rule.id)
            engine.invalidate()
            RuleAlarmScheduler.rescheduleAll(getApplication(), ruleDao.getAllRulesSync())
            _uiState.update { it.copy(message = "Deleted ${rule.name}.") }
            refresh()
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        private val NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}
