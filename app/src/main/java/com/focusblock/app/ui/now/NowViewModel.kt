package com.focusblock.app.ui.now

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.blocking.BlockingEngine
import com.focusblock.app.blocking.RuleAlarmScheduler
import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.blocking.StakeTracker
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
import java.util.concurrent.TimeUnit
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
    val pickerApps: List<PickableApp> = emptyList(),
    val pickerLoading: Boolean = false,
    val manualRuleRunning: BlockRule? = null,
    /** What the user has at stake right now. Null until there is something real. */
    val streakDays: Int = 0,
    val impulsesPassed: Int = 0,
    val protectedHours: Int = 0,
    val reason: String = "",
    /** Today so far, from the usage data the phone already keeps. */
    val today: TodayGlance? = null,
    /**
     * A milestone reached and not yet acknowledged.
     *
     * Shown once, dismissed for good. The only thing in the app that
     * interrupts, and only because a mark that is never marked is not a
     * stake -- it is a number in a database.
     */
    val milestone: MilestoneCard? = null,
    val message: String? = null
)

/**
 * Today, at a glance.
 *
 * The home screen had a hero, a row of allowed apps, one upcoming card and a
 * button -- and then four hundred empty pixels. Emptiness reads as an app that
 * has not been finished, and a blocker that shows you nothing about today has
 * no claim on your attention when nothing happens to be blocked.
 *
 * Everything here comes from usage access, which is already granted, and none
 * of it is a score or a grade. Pickups lead because that is the number this
 * app can actually move.
 */
data class TodayGlance(
    val screenMinutes: Int,
    val pickups: Int,
    val topApps: List<AppMinutes>,
    /** Yesterday's total, for the only comparison worth making. */
    val yesterdayMinutes: Int
) {
    val changeVsYesterday: Int get() = screenMinutes - yesterdayMinutes
}

data class AppMinutes(val packageName: String, val label: String, val minutes: Int)

/** A milestone ready to show, with its copy already resolved. */
data class MilestoneCard(
    val id: Long,
    val title: String,
    val body: String,
    val shareText: String
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
    /** When it opens, as epoch millis. Kept so these can be ordered properly. */
    val startsAt: Long,
    /** "today 20:45" -- when, on the clock. */
    val startsAtLabel: String,
    /** "in 6h 12m" -- how far off, which is the part that lands. */
    val countdownLabel: String,
    val appCount: Int
)

data class AppLabel(val packageName: String, val label: String)

data class PickableApp(
    val packageName: String,
    val label: String,
    val selected: Boolean,
    /** Communication or utility apps: labelled, never pre-selected. */
    val essential: Boolean
)

@HiltViewModel
class NowViewModel @Inject constructor(
    application: Application,
    private val ruleDao: BlockRuleDao,
    private val blockedAppDao: BlockedAppDao,
    private val engine: BlockingEngine,
    private val profileDao: com.focusblock.app.database.dao.FocusProfileDao,
    private val stake: StakeTracker
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
                                startsAt = at,
                                startsAtLabel = relativeLabel(at, now),
                                countdownLabel = countdownLabel(at, now),
                                appCount = rule.packageList().size
                            )
                        }
                    }
                    .sortedBy { it.startsAt }
                    .take(3)
                    .toList()

                val manual = rules.firstOrNull { it.manualIsRunning(now) }

                // Settle the books before reading them. Otherwise an evening
                // that ended at 22:30 shows no streak until the next alarm
                // fires, which can be the following day -- the user would open
                // the app right after finishing a window and see nothing
                // happened.
                stake.reconcileWindows(
                    rules = rules,
                    countOverrides = { ruleId, from, to ->
                        ruleDao.countOverridesBetween(ruleId, from, to)
                    },
                    now = now
                )

                val profile = profileDao.require()

                // ---- 4. Today, from the phone's own usage data -------------
                val today = readToday(context)

                // ---- 5. A milestone waiting to be acknowledged -------------
                val pendingCard = stake.pendingMilestone()?.let { m ->
                    val (title, body) = StakeTracker.headline(m.kind, m.value)
                    MilestoneCard(
                        id = m.id,
                        title = title,
                        body = body,
                        shareText = "$title — $body\n\nKeeping my evenings for my own work, " +
                            "with FocusBlock."
                    )
                }

                // Seed the one-off block's app list from the routines the user
                // has already built, so "Block now" is a single tap instead of
                // a picker they must fill in from scratch every time.
                val seeded = _uiState.value.savedApps.ifEmpty {
                    rules.filter { it.isEnabled }.flatMap { it.packageList() }.distinct()
                }

                _uiState.update {
                    it.copy(
                        loading = false,
                        protection = protection.copy(activeRuleCount = groups.size),
                        blockedGroups = groups,
                        alwaysAllowed = allowed.sortedBy { a -> a.label },
                        upcoming = upcoming,
                        manualRuleRunning = manual,
                        savedApps = seeded,
                        streakDays = profile.currentStreakDays,
                        impulsesPassed = profile.impulsesPassed,
                        protectedHours = (profile.totalProtectedMinutes / 60).toInt(),
                        reason = profile.reason,
                        today = today,
                        milestone = it.milestone ?: pendingCard
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, message = "Couldn't read your rules.") }
            }
        }
    }

    /**
     * Today's screen time, pickups and biggest apps.
     *
     * Pickups are counted from ACTIVITY_RESUMED with a five-second floor
     * between them: without that, a single unlock that bounces through a
     * launcher and two activities reads as three pickups, and the number
     * inflates to the point of being useless.
     */
    private fun readToday(context: Context): TodayGlance? {
        if (!PermissionUtils.hasUsageStatsPermission(context)) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val startOfToday = startOfDay(now)
            val startOfYesterday = startOfToday - TimeUnit.DAYS.toMillis(1)

            val todayTotals = foregroundMinutes(usm, startOfToday, now)
            val yesterdayTotals = foregroundMinutes(usm, startOfYesterday, startOfToday)

            var pickups = 0
            var lastResume = 0L
            val events = usm.queryEvents(startOfToday, now)
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) continue
                if (event.packageName == context.packageName) continue
                if (event.timeStamp - lastResume < 5_000L) continue
                lastResume = event.timeStamp
                pickups++
            }

            TodayGlance(
                screenMinutes = todayTotals.values.sum(),
                pickups = pickups,
                topApps = todayTotals.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .map { AppMinutes(it.key, AppUtils.getAppName(context, it.key), it.value) },
                yesterdayMinutes = yesterdayTotals.values.sum()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Minutes in the foreground per package over a span, ignoring trivia. */
    private fun foregroundMinutes(
        usm: UsageStatsManager,
        from: Long,
        to: Long
    ): Map<String, Int> =
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
            .filter { it.totalTimeInForeground > 60_000L && it.lastTimeUsed in from..to }
            .groupBy { it.packageName }
            .mapValues { (_, rows) -> (rows.sumOf { it.totalTimeInForeground } / 60_000L).toInt() }
            .filterValues { it > 0 }

    private fun startOfDay(epoch: Long): Long = Calendar.getInstance().apply {
        timeInMillis = epoch
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Acknowledge a milestone. Marked seen whether or not it was shared, so it
     * never appears twice.
     */
    fun dismissMilestone(id: Long) {
        _uiState.update { it.copy(milestone = null) }
        viewModelScope.launch(Dispatchers.IO) { stake.markSeen(id) }
    }

    /** Record that the user chose to tell someone. Sharing itself is the UI's job. */
    fun milestoneShared(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { stake.markShared(id) }
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
        BlockingEngine.Trigger.LAUNCH_LIMIT ->
            "Opened ${reason.usedMinutes} times (limit ${reason.limitMinutes}) · until " +
                (reason.endsAt?.let { clock(it) } ?: "the window resets")
    }

    private fun clock(epoch: Long): String =
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(epoch))

    /**
     * How long until it starts, in the units a person waits in.
     *
     * "today 20:45" is a fact you have to subtract from to use. "in 6h 12m" is
     * the thing you actually wanted to know, and it is what makes an idle home
     * screen feel like something is coming rather than like nothing is on.
     */
    private fun countdownLabel(at: Long, now: Long): String {
        val totalMinutes = ((at - now).coerceAtLeast(0L) / 60_000L).toInt()
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            totalMinutes < 1 -> "any moment"
            days > 0 && hours > 0 -> "in ${days}d ${hours}h"
            days > 0 -> "in ${days}d"
            hours > 0 -> "in ${hours}h ${minutes}m"
            else -> "in ${minutes}m"
        }
    }

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

    /**
     * Load the app picker for a one-off block.
     *
     * The home screen previously offered "Block now" and "Choose apps" with no
     * picker behind either: savedApps was never populated, so the primary
     * action always refused, and "Choose apps" opened the allowlist instead.
     */
    fun openPicker() {
        _uiState.update { it.copy(pickerLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val selected = _uiState.value.savedApps.toSet()
            val apps = AppUtils.getInstalledApps(context)
                .filter { it.packageName !in BlockRule.NEVER_BLOCK }
                .map {
                    PickableApp(
                        packageName = it.packageName,
                        label = it.appName,
                        selected = it.packageName in selected,
                        essential = it.packageName in RuleTemplates.ESSENTIAL_NEVER_SUGGEST
                    )
                }
                .sortedWith(
                    compareByDescending<PickableApp> { it.selected }
                        .thenBy { it.essential }
                        .thenBy { it.label.lowercase() }
                )
            _uiState.update { it.copy(pickerApps = apps, pickerLoading = false) }
        }
    }

    fun togglePickerApp(packageName: String) {
        _uiState.update { state ->
            val apps = state.pickerApps.map {
                if (it.packageName == packageName) it.copy(selected = !it.selected) else it
            }
            state.copy(
                pickerApps = apps,
                savedApps = apps.filter { it.selected }.map { it.packageName }
            )
        }
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
