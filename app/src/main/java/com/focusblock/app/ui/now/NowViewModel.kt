package com.focusblock.app.ui.now

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.blocking.BlockingEngine
import com.focusblock.app.blocking.RuleAlarmScheduler
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.database.dao.BlockRuleDao
import com.focusblock.app.database.dao.BlockedAppDao
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.database.entity.RuleKind
import com.focusblock.app.service.FocusBlockAccessibilityService
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

/**
 * State for the home screen.
 *
 * The screen answers four questions in order, and this state is shaped to
 * answer them without the UI having to derive anything:
 *
 *   1. Is blocking actually working?      -> [protection]
 *   2. What's blocked, why, until when?   -> [blockedGroups]
 *   3. What starts next?                  -> [upcoming]
 *   4. How do I start or adjust?          -> [savedApps], the action row
 */
data class NowUiState(
    val loading: Boolean = true,
    val protection: ProtectionStatus = ProtectionStatus(),
    val blockedGroups: List<BlockedGroup> = emptyList(),
    val alwaysAllowed: List<AppLabel> = emptyList(),
    val upcoming: List<UpcomingRule> = emptyList(),
    val savedApps: List<String> = emptyList(),
    val manualRuleRunning: BlockRule? = null,
    val message: String? = null
)

/**
 * What the protection card reports.
 *
 * Deliberately NOT a single "Maximum Protection" badge derived from a boolean.
 * The old banner could read "Maximum Protection / 3 active" while Bedtime Mode
 * blocked nothing and the Hard Mode flag it was reading was not the one being
 * enforced. These are the signals that actually determine whether a block will
 * happen.
 */
data class ProtectionStatus(
    val accessibilityConnected: Boolean = false,
    val overlayGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val activeRuleCount: Int = 0
) {
    /** Blocking can happen at all. */
    val enforcing: Boolean get() = accessibilityConnected && usageAccessGranted

    /** Everything that makes blocking reliable is in place. */
    val fullyHealthy: Boolean get() = enforcing && overlayGranted && batteryUnrestricted

    val headline: String
        get() = when {
            !enforcing -> "Blocking is not working"
            !fullyHealthy -> "Blocking works, with gaps"
            activeRuleCount > 0 -> "Blocking on"
            else -> "Ready"
        }

    /** The single most useful thing to fix, or null when nothing is wrong. */
    val firstProblem: String?
        get() = when {
            !accessibilityConnected -> "Accessibility service is off — nothing can be blocked"
            !usageAccessGranted -> "Usage access is off — time budgets can't be counted"
            !overlayGranted -> "Display over apps is off — the block screen may not appear"
            !batteryUnrestricted -> "Battery optimisation may stop blocking in the background"
            else -> null
        }
}

/** One rule, and the apps it is currently blocking. */
data class BlockedGroup(
    val ruleId: Long,
    val ruleName: String,
    val reasonLine: String,
    val apps: List<AppLabel>,
    val commitment: CommitmentLevel,
    val endsAt: Long?,
    val canEndNow: Boolean
)

data class UpcomingRule(
    val ruleId: Long,
    val name: String,
    val startsAtLabel: String,
    val appCount: Int
)

data class AppLabel(val packageName: String, val label: String)

@HiltViewModel
class NowViewModel @Inject constructor(
    application: Application,
    private val ruleDao: BlockRuleDao,
    private val blockedAppDao: BlockedAppDao,
    private val engine: BlockingEngine
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NowUiState())
    val uiState: StateFlow<NowUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Refresh on a slow tick so countdowns stay honest without the
            // screen becoming a busy loop.
            while (true) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val now = System.currentTimeMillis()
                val rules = ruleDao.getAllRulesSync()

                // ---- 1. Protection ----------------------------------------
                val perms = PermissionUtils.getPermissionStatus(context)
                val protection = ProtectionStatus(
                    accessibilityConnected = FocusBlockAccessibilityService.isServiceRunning &&
                        perms.hasAccessibility,
                    overlayGranted = perms.hasOverlay,
                    usageAccessGranted = perms.hasUsageStats,
                    batteryUnrestricted = perms.isIgnoringBattery,
                    activeRuleCount = 0
                )

                // ---- 2. What is blocked, grouped by rule -------------------
                // Evaluate every package any rule covers, then invert the
                // result so the user sees rules, not a flat app list. Overlap
                // therefore stays visible instead of being hidden behind a
                // single "primary reason".
                val candidatePackages = rules.flatMap { it.packageList() }.distinct()
                val byRule = linkedMapOf<Long, MutableList<AppLabel>>()
                val reasonByRule = linkedMapOf<Long, BlockingEngine.ActiveReason>()

                for (pkg in candidatePackages) {
                    val decision = engine.evaluate(pkg, now)
                    for (reason in decision.reasons) {
                        byRule.getOrPut(reason.ruleId) { mutableListOf() }
                            .add(AppLabel(pkg, AppUtils.getAppName(context, pkg)))
                        reasonByRule.putIfAbsent(reason.ruleId, reason)
                    }
                }

                val groups = byRule.map { (ruleId, apps) ->
                    val reason = reasonByRule.getValue(ruleId)
                    BlockedGroup(
                        ruleId = ruleId,
                        ruleName = reason.ruleName,
                        reasonLine = describe(reason, now),
                        apps = apps.sortedBy { it.label },
                        commitment = reason.commitment,
                        endsAt = reason.endsAt,
                        canEndNow = reason.commitment == CommitmentLevel.OFF
                    )
                }.sortedByDescending { it.apps.size }

                // ---- 3. Always allowed ------------------------------------
                // Shown explicitly so the user can see at a glance that the
                // apps they need for work are reachable.
                val allowed = blockedAppDao.getAllowlistPackages().map {
                    AppLabel(it, AppUtils.getAppName(context, it))
                }

                // ---- 4. What starts next ----------------------------------
                val upcoming = rules
                    .asSequence()
                    .filter { it.isEnabled && it.kind == RuleKind.AUTOMATIC && it.hasTimeCondition }
                    .filter { it.id !in byRule.keys }
                    .mapNotNull { rule ->
                        RuleAlarmScheduler.nextBoundary(rule, now)?.let { at ->
                            UpcomingRule(
                                ruleId = rule.id,
                                name = rule.name,
                                startsAtLabel = relativeLabel(at, now),
                                appCount = rule.packageList().size
                            )
                        }
                    }
                    .sortedBy { it.startsAtLabel }
                    .take(3)
                    .toList()

                val manual = rules.firstOrNull { it.manualIsRunning(now) }

                _uiState.update {
                    it.copy(
                        loading = false,
                        protection = protection.copy(activeRuleCount = groups.size),
                        blockedGroups = groups,
                        alwaysAllowed = allowed.sortedBy { a -> a.label },
                        upcoming = upcoming,
                        manualRuleRunning = manual
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, message = "Couldn't read your rules.") }
            }
        }
    }

    /** Human-readable "why", e.g. "Evening work block · until 10:30 PM". */
    private fun describe(reason: BlockingEngine.ActiveReason, now: Long): String = when (reason.trigger) {
        BlockingEngine.Trigger.MANUAL ->
            reason.endsAt?.let { "Block now · until ${clock(it)}" } ?: "Block now · until you stop it"
        BlockingEngine.Trigger.SCHEDULE ->
            reason.endsAt?.let { "Scheduled · until ${clock(it)}" } ?: "Scheduled"
        BlockingEngine.Trigger.DAILY_BUDGET ->
            "Daily budget spent (${reason.usedMinutes}/${reason.limitMinutes}m) · until midnight"
        BlockingEngine.Trigger.HOURLY_BUDGET ->
            "This hour's ${reason.limitMinutes}m used · until ${reason.endsAt?.let { clock(it) } ?: "next hour"}"
    }

    private fun clock(epoch: Long): String =
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(epoch))

    private fun relativeLabel(at: Long, now: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
            cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        return if (sameDay) "today ${clock(at)}" else {
            val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(at))
            "$day ${clock(at)}"
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Start a one-off block. Null duration means "until I stop it". */
    fun startBlockNow(packages: List<String>, durationMinutes: Int?) {
        if (packages.isEmpty()) {
            _uiState.update { it.copy(message = "Pick at least one app to block.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val rule = RuleTemplates.blockNow(packages, durationMinutes)
            ruleDao.insert(rule)
            // The engine caches the rule set for a couple of seconds; drop it
            // so a block the user just started takes effect immediately.
            engine.invalidate()
            RuleAlarmScheduler.rescheduleAll(getApplication(), ruleDao.getAllRulesSync())
            _uiState.update {
                it.copy(message = "Blocking ${packages.size} apps.")
            }
            refresh()
        }
    }

    /**
     * End one rule. Reports which rules keep blocking afterwards, so the user
     * is never left wondering why an app is still unavailable.
     */
    fun endRule(ruleId: Long, pin: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = engine.requestEndRule(ruleId, pin)) {
                is BlockingEngine.EndAttempt.Ended -> {
                    val survivors = result.stillBlocked
                    val msg = if (survivors.isEmpty()) {
                        "Stopped."
                    } else {
                        "Stopped. Still blocked by ${survivors.joinToString(" and ")}."
                    }
                    _uiState.update { it.copy(message = msg) }
                }
                is BlockingEngine.EndAttempt.Refused ->
                    _uiState.update { it.copy(message = result.message) }
                is BlockingEngine.EndAttempt.NeedsPin -> {
                    val mins = (result.cooldownRemainingMillis / 60_000L) + 1
                    _uiState.update {
                        it.copy(message = "PIN required. Try again in about $mins min.")
                    }
                }
            }
            RuleAlarmScheduler.rescheduleAll(getApplication(), ruleDao.getAllRulesSync())
            refresh()
        }
    }

    fun setSavedApps(packages: List<String>) {
        _uiState.update { it.copy(savedApps = packages) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    suspend fun suggestedApps(): List<String> = withContext(Dispatchers.IO) {
        val known = blockedAppDao.getBlockedPackageNames()
        RuleTemplates.safeSuggestions(
            (known + RuleTemplates.COMMON_DISTRACTIONS).distinct()
        )
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 15_000L
    }
}
