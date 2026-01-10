package com.focusblock.app.viewmodel

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.FocusBlockApp
import com.focusblock.app.R
import com.focusblock.app.database.entity.*
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.service.FocusBlockAccessibilityService
import com.focusblock.app.service.FocusCycleOverlayService
import com.focusblock.app.ui.MainActivity
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import com.focusblock.app.utils.TimeUtils
import com.focusblock.app.worker.PeakTimeReminderWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ========== INSIGHTS DATA MODELS ==========
data class DistractionInsight(
    val packageName: String,
    val appName: String,
    val blockCount: Int,
    val peakHour: Int? = null, // 0-23 hour when most blocks occurred
    val peakHourCount: Int = 0
)

data class HourlyInsight(
    val bestFocusHour: Int?, // Hour with fewest blocks (0-23)
    val bestFocusHourBlocks: Int = 0,
    val worstHour: Int?, // Hour with most blocks (0-23)
    val worstHourBlocks: Int = 0
)

data class LongSessionRisk(
    val packageName: String,
    val appName: String,
    val durationMinutes: Int,
    val startHour: Int, // 0-23
    val endHour: Int
)

/**
 * Context-aware action suggestion based on usage patterns
 */
data class ActionSuggestion(
    val type: ActionType,
    val title: String,
    val description: String,
    val targetPackage: String? = null,
    val targetAppName: String? = null,
    val peakStartHour: Int? = null,
    val peakEndHour: Int? = null
)

enum class ActionType {
    FOCUS_CYCLE_FOR_PEAK_TIME,  // "You binge most between X-Y PM. Start Focus Cycle?"
    QUICK_BLOCK_DISTRACTION,     // "Block [app] now?"
    SET_TIME_LIMIT              // "Set daily limit for [app]?"
}

data class InsightsState(
    val biggestDistraction: DistractionInsight? = null,
    val hourlyInsight: HourlyInsight? = null,
    val longSessionRisks: List<LongSessionRisk> = emptyList(),
    val focusStreakDays: Int = 0,
    val weeklyTimeSavedMinutes: Int = 0, // Estimated based on blocks
    val hasEnoughData: Boolean = false,
    val actionSuggestions: List<ActionSuggestion> = emptyList() // Context-aware suggestions
)

// Unified blocked app with source information
data class UnifiedBlockedApp(
    val packageName: String,
    val appName: String,
    val isQuickBlockOnly: Boolean, // True if only blocked by Quick Block (temporary)
    val isPermanent: Boolean, // True if in permanent blocked list
    val isFromSchedule: Boolean = false, // True if blocked by active schedule
    val isFromAppTimer: Boolean = false // True if in App Timer list
)

data class HomeUiState(
    val isQuickBlockActive: Boolean = false,
    val quickBlockSession: QuickBlockSession? = null,
    val remainingTime: Long = 0,
    val blockedAppsCount: Int = 0,
    val blockedApps: List<BlockedApp> = emptyList(),
    val unifiedBlockedApps: List<UnifiedBlockedApp> = emptyList(), // All blocked apps with source
    val activeSchedules: List<Schedule> = emptyList(),
    val todayBlockCount: Int = 0,
    val weekBlockCount: Int = 0,
    val focusStreak: Int = 0,
    // Insights
    val insights: InsightsState = InsightsState(),
    val isStrictModeEnabled: Boolean = false,
    val isStrictModeLocked: Boolean = false,
    val isStrictModePaused: Boolean = false,
    val strictModeEndTime: Long? = null,
    val strictModeRemainingTime: Long = 0,
    val strictModeRemainingPausesToday: Int = 1, // Pauses remaining today
    val strictModeMaxPausesPerDay: Int = 1, // Configurable max pauses
    val isEmergencyUnlockAvailable: Boolean = true, // One-time daily emergency unlock
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
    val focusCyclePhase: FocusCyclePhase = FocusCyclePhase.INACTIVE,
    // App Timer state (shared daily time limit)
    val appTimerSettings: AppTimerSettings? = null,
    val isAppTimerEnabled: Boolean = false,
    val appTimerLimitMinutes: Int = 30,
    val appTimerUsageMinutes: Int = 0,
    val appTimerAppsCount: Int = 0,
    // Smart Suggestions state
    val smartSuggestionsEnabled: Boolean = true,
    val hasCompletedOnboarding: Boolean = false,
    val averageDailyUsageMinutes: Int = 0,
    val suggestedDailyLimitMinutes: Int = 0,
    val suggestedApps: List<SuggestedBlockingApp> = emptyList(),
    val showSmartSuggestionsCard: Boolean = false,
    // Global Daily Limit (moved to homepage for better visibility)
    val isGlobalDailyLimitEnabled: Boolean = false,
    val globalDailyLimitMinutes: Int = 180,
    val currentDailyUsageMinutes: Int = 0,
    val dailyLimitProgress: Float = 0f // 0.0 to 1.0
)

enum class FocusCyclePhase {
    INACTIVE,      // Focus Cycle not enabled
    ARMED,         // Waiting for user to open a selected app
    USAGE_WINDOW,  // During allowed usage time
    PAUSED,        // Timer paused (user on non-selected app)
    BREAK          // During break (apps blocked)
}

enum class StrictModePauseResult {
    SUCCESS,       // Pause successful
    NOT_ACTIVE,    // Strict mode not active or already paused
    LIMIT_REACHED  // Daily pause limit reached
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

    companion object {
        private const val FOCUS_CYCLE_NOTIFICATION_ID = 3001
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val installedApps = MutableStateFlow<List<AppUtils.AppInfo>>(emptyList())

    init {
        loadData()
        loadFocusCycleData()
        loadAppTimerData()
        loadInstalledApps()
        loadStrictModePauseLimits()
        loadInsights()
        startTimerUpdates()
        refreshEmergencyUnlockStatus()
        loadSmartSuggestions()
        loadGlobalDailyLimitForHome()
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
            // Load unified blocked apps list showing ALL blocked apps with their source
            kotlinx.coroutines.flow.combine(
                repository.getActiveBlockedApps(),
                repository.getActiveQuickBlockSession(),
                repository.getAllSchedules(),
                repository.getAppTimerSettings()
            ) { apps, session, schedules, timerSettings ->
                val permanentPackages = apps.map { it.packageName }.toSet()

                // Get Quick Block packages
                val quickBlockPackages = session?.blockedPackages?.split(",")
                    ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                val previouslyBlocked = session?.previouslyBlockedPackages?.split(",")
                    ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                val quickBlockOnlyPackages = quickBlockPackages - previouslyBlocked

                // Get active schedule packages
                val activeSchedulePackages = schedules
                    .filter { it.isEnabled }
                    .flatMap { it.blockedPackages.split(",").filter { pkg -> pkg.isNotBlank() } }
                    .toSet()

                // Get App Timer packages
                val timerPackages = timerSettings?.timerApps?.split(",")
                    ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

                // Build unified list - all currently blocked apps
                val allPackages = permanentPackages + quickBlockPackages
                allPackages.map { packageName ->
                    val blockedApp = apps.find { it.packageName == packageName }
                    UnifiedBlockedApp(
                        packageName = packageName,
                        appName = blockedApp?.appName ?: AppUtils.getAppName(application, packageName),
                        isQuickBlockOnly = packageName in quickBlockOnlyPackages,
                        isPermanent = packageName in permanentPackages && packageName !in quickBlockOnlyPackages,
                        isFromSchedule = packageName in activeSchedulePackages,
                        isFromAppTimer = packageName in timerPackages
                    )
                }.sortedBy { it.appName }
            }.collect { unifiedApps ->
                _uiState.update {
                    it.copy(
                        unifiedBlockedApps = unifiedApps,
                        blockedAppsCount = unifiedApps.size
                    )
                }
            }
        }

        // Keep legacy blockedApps for backward compatibility
        viewModelScope.launch {
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

    private fun loadStrictModePauseLimits() {
        viewModelScope.launch {
            val remaining = repository.getRemainingPausesToday()
            val max = repository.getStrictModeMaxPausesPerDay()
            _uiState.update { it.copy(
                strictModeRemainingPausesToday = remaining,
                strictModeMaxPausesPerDay = max
            )}
        }
    }

    /**
     * Load insights from block logs data
     * Computes: biggest distraction, peak hours, long sessions, streak
     */
    private fun loadInsights() {
        viewModelScope.launch {
            try {
                // Get today's block logs
                val startOfDay = TimeUtils.getStartOfDay()
                val startOfWeek = TimeUtils.getStartOfWeek()

                repository.getBlockLogsSince(startOfWeek).collect { logs ->
                    try {
                        if (logs.isEmpty()) {
                            _uiState.update { it.copy(
                                insights = InsightsState(hasEnoughData = false)
                            )}
                            return@collect
                        }

                // Filter to today's logs for daily insights
                val todayLogs = logs.filter { it.timestamp >= startOfDay }

                // ===== BIGGEST DISTRACTION OF THE DAY =====
                val appBlockCounts = todayLogs.groupBy { it.packageName }
                    .mapValues { (_, blocks) -> blocks.size }
                    .toList()
                    .sortedByDescending { it.second }

                val biggestDistraction = if (appBlockCounts.isNotEmpty()) {
                    val (packageName, count) = appBlockCounts.first()
                    val appName = todayLogs.first { it.packageName == packageName }.appName

                    // Find peak hour for this app
                    val hourCounts = todayLogs.filter { it.packageName == packageName }
                        .groupBy { java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(java.util.Calendar.HOUR_OF_DAY) }
                        .mapValues { it.value.size }
                    val peakHour = hourCounts.maxByOrNull { it.value }

                    DistractionInsight(
                        packageName = packageName,
                        appName = appName,
                        blockCount = count,
                        peakHour = peakHour?.key,
                        peakHourCount = peakHour?.value ?: 0
                    )
                } else null

                // ===== HOURLY INSIGHT: Best Focus vs Worst Hour =====
                val hourlyBlocks = todayLogs.groupBy {
                    java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(java.util.Calendar.HOUR_OF_DAY)
                }.mapValues { it.value.size }

                // Only consider hours with some activity (6am-midnight)
                val activeHours = (6..23).filter { hour ->
                    hourlyBlocks.containsKey(hour) || hourlyBlocks.isNotEmpty()
                }

                val hourlyInsight = if (hourlyBlocks.isNotEmpty()) {
                    val worstHourEntry = hourlyBlocks.maxByOrNull { it.value }
                    val bestHourEntry = hourlyBlocks.minByOrNull { it.value }
                    HourlyInsight(
                        bestFocusHour = bestHourEntry?.key,
                        bestFocusHourBlocks = bestHourEntry?.value ?: 0,
                        worstHour = worstHourEntry?.key,
                        worstHourBlocks = worstHourEntry?.value ?: 0
                    )
                } else null

                // ===== LONG SESSION RISK: Detect sessions > 40 minutes =====
                // Approximate session duration by gaps between consecutive blocks for same app
                val longSessions = mutableListOf<LongSessionRisk>()
                val sortedLogs = todayLogs.sortedBy { it.timestamp }

                // Group consecutive blocks by app to detect sessions
                var sessionStart: Long? = null
                var currentApp: String? = null
                var currentAppName: String? = null

                for (log in sortedLogs) {
                    if (currentApp == log.packageName && sessionStart != null) {
                        // Check if this is a long session (if gap is small, it's same session)
                        val lastTimestamp = sortedLogs.filter { it.timestamp < log.timestamp && it.packageName == log.packageName }.maxOfOrNull { it.timestamp }
                        val gap = if (lastTimestamp != null) log.timestamp - lastTimestamp else null
                        if (gap != null && gap < 5 * 60 * 1000) { // 5 min gap tolerance
                            val duration = (log.timestamp - sessionStart) / (60 * 1000)
                            if (duration >= 40) {
                                val cal = java.util.Calendar.getInstance()
                                cal.timeInMillis = sessionStart
                                val startHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                                cal.timeInMillis = log.timestamp
                                val endHour = cal.get(java.util.Calendar.HOUR_OF_DAY)

                                longSessions.add(LongSessionRisk(
                                    packageName = log.packageName,
                                    appName = log.appName,
                                    durationMinutes = duration.toInt(),
                                    startHour = startHour,
                                    endHour = endHour
                                ))
                            }
                        }
                    } else {
                        // New session
                        sessionStart = log.timestamp
                        currentApp = log.packageName
                        currentAppName = log.appName
                    }
                }

                // ===== STREAK & TIME SAVED =====
                // Calculate focus streak (days with blocks indicating focus attempts)
                val daysCounts = logs.groupBy {
                    java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(java.util.Calendar.DAY_OF_YEAR)
                }
                val focusStreak = daysCounts.size // Simple streak = days with any block activity

                // Estimate time saved: assume each block saves ~2 minutes of distraction
                val weeklyTimeSaved = logs.size * 2

                // ===== CONTEXT-AWARE ACTION SUGGESTIONS =====
                val actionSuggestions = mutableListOf<ActionSuggestion>()

                // Suggestion 1: Focus Cycle for peak distraction time
                hourlyInsight?.worstHour?.let { peakHour ->
                    if (hourlyBlocks.isNotEmpty() && hourlyBlocks[peakHour] ?: 0 >= 3) {
                        // Find the peak window (typically 2-3 hours around the worst hour)
                        val peakStart = maxOf(0, peakHour - 1)
                        val peakEnd = minOf(23, peakHour + 1)
                        val totalPeakBlocks = (peakStart..peakEnd).sumOf { hourlyBlocks[it] ?: 0 }

                        if (totalPeakBlocks >= 4) {
                            actionSuggestions.add(ActionSuggestion(
                                type = ActionType.FOCUS_CYCLE_FOR_PEAK_TIME,
                                title = "Peak distraction window detected",
                                description = "You get distracted most between ${formatHourShort(peakStart)}-${formatHourShort(peakEnd)}. Set up a Focus Cycle?",
                                peakStartHour = peakStart,
                                peakEndHour = peakEnd
                            ))
                        }
                    }
                }

                // Suggestion 2: Quick Block for biggest distraction
                biggestDistraction?.let { distraction ->
                    if (distraction.blockCount >= 5) {
                        actionSuggestions.add(ActionSuggestion(
                            type = ActionType.QUICK_BLOCK_DISTRACTION,
                            title = "${distraction.appName} is your top distraction today",
                            description = "Block it now to stay focused?",
                            targetPackage = distraction.packageName,
                            targetAppName = distraction.appName
                        ))
                    }
                }

                _uiState.update { it.copy(
                    insights = InsightsState(
                        biggestDistraction = biggestDistraction,
                        hourlyInsight = hourlyInsight,
                        longSessionRisks = longSessions.take(3), // Top 3 long sessions
                        focusStreakDays = focusStreak,
                        weeklyTimeSavedMinutes = weeklyTimeSaved,
                        hasEnoughData = todayLogs.size >= 3, // Need at least 3 blocks for meaningful insights
                        actionSuggestions = actionSuggestions
                    )
                )}
                    } catch (e: Exception) {
                        // Log error and show empty insights
                        android.util.Log.e("HomeViewModel", "Error processing insights", e)
                        _uiState.update { it.copy(
                            insights = InsightsState(hasEnoughData = false)
                        )}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error loading insights", e)
                _uiState.update { it.copy(
                    insights = InsightsState(hasEnoughData = false)
                )}
            }
        }
    }

    /**
     * Format hour for display in suggestions (e.g., "2 PM")
     */
    private fun formatHourShort(hour: Int): String {
        return when {
            hour == 0 -> "12 AM"
            hour < 12 -> "$hour AM"
            hour == 12 -> "12 PM"
            else -> "${hour - 12} PM"
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

    private fun loadAppTimerData() {
        viewModelScope.launch {
            repository.getAppTimerSettings().collect { settings ->
                if (settings != null) {
                    val appsCount = settings.timerApps
                        .split(",")
                        .filter { it.isNotBlank() }
                        .size
                    _uiState.update { it.copy(
                        appTimerSettings = settings,
                        isAppTimerEnabled = settings.isEnabled,
                        appTimerLimitMinutes = settings.dailyLimitMinutes,
                        appTimerAppsCount = appsCount
                    )}
                } else {
                    _uiState.update { it.copy(
                        appTimerSettings = null,
                        isAppTimerEnabled = false,
                        appTimerAppsCount = 0
                    )}
                }
            }
        }

        // Load today's usage
        viewModelScope.launch {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            repository.getAppTimerDailyUsage(today).collect { usage ->
                _uiState.update { it.copy(
                    appTimerUsageMinutes = usage?.totalUsageMinutes ?: 0
                )}
            }
        }
    }

    fun enableAppTimer(limitMinutes: Int, selectedPackages: List<String>) {
        viewModelScope.launch {
            val settings = AppTimerSettings(
                id = 1,
                isEnabled = true,
                dailyLimitMinutes = limitMinutes,
                timerApps = selectedPackages.joinToString(","),
                updatedAt = System.currentTimeMillis()
            )
            repository.saveAppTimerSettings(settings)
        }
    }

    fun disableAppTimer() {
        viewModelScope.launch {
            val currentSettings = _uiState.value.appTimerSettings
            if (currentSettings != null) {
                repository.saveAppTimerSettings(currentSettings.copy(isEnabled = false))
            }
        }
    }

    fun updateAppTimerLimit(limitMinutes: Int) {
        viewModelScope.launch {
            val currentSettings = _uiState.value.appTimerSettings
            if (currentSettings != null) {
                repository.saveAppTimerSettings(currentSettings.copy(
                    dailyLimitMinutes = limitMinutes,
                    updatedAt = System.currentTimeMillis()
                ))
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
            val breakEnd = breakStart + TimeUtils.focusCycleTimeToMillis(cycle.breakDurationMinutes)
            return if (now >= breakEnd) {
                // Break is over - should be re-armed (handled by AccessibilityService)
                FocusCyclePhase.ARMED
            } else {
                FocusCyclePhase.BREAK
            }
        }

        // Check if cycle has started
        if (cycle.cycleStartTime == null) return FocusCyclePhase.ARMED

        // Check if usage window is exhausted using LIVE timestamp calculation
        val usageWindowMillis = TimeUtils.focusCycleTimeToMillis(cycle.usageWindowMinutes)
        val elapsedMillis = computeElapsedUsageMillis(cycle, now)
        if (elapsedMillis >= usageWindowMillis) {
            return FocusCyclePhase.BREAK
        }

        // We're in usage window - check if paused
        return if (cycle.isPaused) {
            FocusCyclePhase.PAUSED
        } else {
            FocusCyclePhase.USAGE_WINDOW
        }
    }

    /**
     * Compute LIVE elapsed usage time from timestamps
     * This is the single source of truth for timing
     */
    private fun computeElapsedUsageMillis(cycle: FocusCycle, now: Long = System.currentTimeMillis()): Long {
        // If paused or no active session, return accumulated only
        if (cycle.isPaused || cycle.lastActiveTime == null) {
            return cycle.accumulatedUsageMillis
        }
        // LIVE calculation: accumulated + current session time
        val currentSessionMillis = now - cycle.lastActiveTime
        return cycle.accumulatedUsageMillis + currentSessionMillis
    }

    private fun calculateFocusCycleRemainingTime(cycle: FocusCycle, phase: FocusCyclePhase): Long {
        val now = System.currentTimeMillis()

        return when (phase) {
            FocusCyclePhase.INACTIVE -> 0L
            FocusCyclePhase.ARMED -> {
                // Full usage window available when armed
                TimeUtils.focusCycleTimeToMillis(cycle.usageWindowMinutes)
            }
            FocusCyclePhase.USAGE_WINDOW, FocusCyclePhase.PAUSED -> {
                // LIVE timestamp calculation
                val usageWindowMillis = TimeUtils.focusCycleTimeToMillis(cycle.usageWindowMinutes)
                val elapsedMillis = computeElapsedUsageMillis(cycle, now)
                maxOf(0, usageWindowMillis - elapsedMillis)
            }
            FocusCyclePhase.BREAK -> {
                val breakStart = cycle.breakStartTime ?: now
                val breakEnd = breakStart + TimeUtils.focusCycleTimeToMillis(cycle.breakDurationMinutes)
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
     * Returns result indicating if pause was successful or why it failed
     */
    fun pauseStrictMode(): StrictModePauseResult {
        if (!_uiState.value.isStrictModeLocked || _uiState.value.isStrictModePaused) {
            return StrictModePauseResult.NOT_ACTIVE
        }

        // Check pause limit synchronously from cached state
        if (_uiState.value.strictModeRemainingPausesToday <= 0) {
            return StrictModePauseResult.LIMIT_REACHED
        }

        viewModelScope.launch {
            // Double-check limit from repository
            if (!repository.canPauseStrictMode()) {
                showToast("You have already used your daily pause exception.")
                refreshPauseLimitState()
                return@launch
            }

            val remainingTime = _uiState.value.strictModeRemainingTime
            repository.setStrictModeRemainingOnPause(remainingTime)
            repository.setStrictModePaused(true)
            repository.setStrictModePauseReason("user_paused")

            // Increment pause counter
            repository.incrementStrictModePauseCount()

            // Clear the end time while paused
            repository.clearStrictModeEndTime()

            // Refresh pause limit state
            val remaining = repository.getRemainingPausesToday()

            _uiState.update { it.copy(
                isStrictModePaused = true,
                isStrictModeLocked = false,
                strictModeRemainingPausesToday = remaining
            )}

            showToast("Strict Mode paused. ${formatDuration(remainingTime)} remaining. ($remaining pause(s) left today)")
        }

        return StrictModePauseResult.SUCCESS
    }

    /**
     * Refresh pause limit state from repository
     */
    private fun refreshPauseLimitState() {
        viewModelScope.launch {
            val remaining = repository.getRemainingPausesToday()
            val max = repository.getStrictModeMaxPausesPerDay()
            _uiState.update { it.copy(
                strictModeRemainingPausesToday = remaining,
                strictModeMaxPausesPerDay = max
            )}
        }
    }

    /**
     * Set the maximum number of pauses allowed per day
     */
    fun setStrictModeMaxPausesPerDay(max: Int) {
        viewModelScope.launch {
            repository.setStrictModeMaxPausesPerDay(max)
            refreshPauseLimitState()
        }
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

    /**
     * Check if emergency unlock is available today
     */
    suspend fun isEmergencyUnlockAvailable(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val usedDate = repository.getSetting(AppSettings.KEY_STRICT_MODE_EMERGENCY_UNLOCK_USED_DATE)
        return usedDate != today
    }

    /**
     * Emergency unlock - pauses Strict Mode (once per day)
     * Returns true if successful, false if already used today
     */
    fun emergencyUnlock(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())

        // Check if already used today (synchronous check from cached state)
        if (!_uiState.value.isEmergencyUnlockAvailable) {
            showToast("Emergency unlock already used today")
            return false
        }

        viewModelScope.launch {
            // Double-check from repository
            val usedDate = repository.getSetting(AppSettings.KEY_STRICT_MODE_EMERGENCY_UNLOCK_USED_DATE)
            if (usedDate == today) {
                showToast("Emergency unlock already used today")
                _uiState.update { it.copy(isEmergencyUnlockAvailable = false) }
                return@launch
            }

            // Mark as used for today
            repository.setSetting(AppSettings.KEY_STRICT_MODE_EMERGENCY_UNLOCK_USED_DATE, today)

            // Pause strict mode (stores remaining time for later)
            val remainingTime = _uiState.value.strictModeRemainingTime
            repository.setStrictModeRemainingOnPause(remainingTime)
            repository.setStrictModePaused(true)
            repository.setStrictModePauseReason("emergency_unlock")
            repository.clearStrictModeEndTime()

            _uiState.update { it.copy(
                isStrictModePaused = true,
                isStrictModeLocked = false,
                isEmergencyUnlockAvailable = false,
                strictModeEndTime = null
            )}

            showToast("Emergency unlock used. Strict Mode paused.")
        }
        return true
    }

    /**
     * Refresh emergency unlock availability (called on app start/day change)
     */
    fun refreshEmergencyUnlockStatus() {
        viewModelScope.launch {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            val usedDate = repository.getSetting(AppSettings.KEY_STRICT_MODE_EMERGENCY_UNLOCK_USED_DATE)
            val available = usedDate != today
            _uiState.update { it.copy(isEmergencyUnlockAvailable = available) }
        }
    }

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
            // Validate that there are apps to track
            val hasSelectedApps = selectedPackages.isNotEmpty()
            val hasQuickBlockApps = if (useQuickBlockApps) {
                val session = repository.getActiveQuickBlockSessionSync()
                session?.blockedPackages?.split(",")?.filter { it.isNotBlank() }?.isNotEmpty() ?: false
            } else false

            if (!hasSelectedApps && !hasQuickBlockApps) {
                showToast("Please select apps to track or start a Quick Block session first")
                return@launch
            }

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

            // Show the initial notification
            showFocusCycleNotification(focusCycle)

            // Start floating overlay timer
            FocusCycleOverlayService.start(application)

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
            // Cancel the notification
            cancelFocusCycleNotification()

            // Stop floating overlay timer
            FocusCycleOverlayService.stop(application)

            showToast("Focus Cycle stopped")
        }
    }

    /**
     * Format remaining time for display
     * Shows seconds if under 1 minute, otherwise minutes
     */
    private fun formatRemainingTimeText(millis: Long): String {
        val seconds = (millis / 1000).toInt()
        return if (seconds < 60) {
            "$seconds sec"
        } else {
            "${seconds / 60} min"
        }
    }

    /**
     * Show Focus Cycle notification
     */
    private fun showFocusCycleNotification(cycle: FocusCycle) {
        val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val (title, content) = when {
            cycle.isArmed -> {
                val windowText = TimeUtils.formatFocusCycleTime(cycle.usageWindowMinutes)
                Pair(
                    "Focus Cycle - Ready",
                    "Open a tracked app to start your $windowText usage window"
                )
            }
            cycle.breakStartTime != null -> {
                val now = System.currentTimeMillis()
                val breakEnd = cycle.breakStartTime + TimeUtils.focusCycleTimeToMillis(cycle.breakDurationMinutes)
                val remainingMillis = maxOf(0, breakEnd - now)
                val remainingText = formatRemainingTimeText(remainingMillis)
                Pair(
                    "Break Time",
                    "$remainingText remaining before apps unlock"
                )
            }
            cycle.cycleStartTime != null -> {
                val now = System.currentTimeMillis()
                val usageWindowMillis = TimeUtils.focusCycleTimeToMillis(cycle.usageWindowMinutes)
                // LIVE timestamp calculation
                val elapsedMillis = computeElapsedUsageMillis(cycle, now)
                val remainingMillis = maxOf(0, usageWindowMillis - elapsedMillis)
                val remainingText = formatRemainingTimeText(remainingMillis)
                val pausedText = if (cycle.isPaused) " (Paused)" else ""
                Pair(
                    "Focus Cycle - Usage Window$pausedText",
                    "$remainingText remaining in usage window"
                )
            }
            else -> return
        }

        val intent = Intent(application, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(application, 0, intent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(application, FocusBlockApp.CHANNEL_MINDFUL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)

        notificationManager.notify(FOCUS_CYCLE_NOTIFICATION_ID, builder.build())
    }

    /**
     * Cancel Focus Cycle notification
     */
    private fun cancelFocusCycleNotification() {
        val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(FOCUS_CYCLE_NOTIFICATION_ID)
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

    // ========== SMART SUGGESTIONS ==========

    /**
     * Load smart suggestions settings and analyze usage
     */
    private fun loadSmartSuggestions() {
        viewModelScope.launch {
            // Initialize default whitelist if needed
            initializeEssentialAppsWhitelist()

            // Load settings
            repository.getSmartSuggestionsSettings().collect { settings ->
                if (settings != null) {
                    _uiState.update { it.copy(
                        smartSuggestionsEnabled = settings.isEnabled,
                        hasCompletedOnboarding = settings.hasCompletedOnboarding,
                        averageDailyUsageMinutes = settings.averageDailyUsageMinutes,
                        suggestedDailyLimitMinutes = settings.suggestedDailyLimitMinutes
                    )}
                } else {
                    // Initialize with defaults
                    repository.saveSmartSuggestionsSettings(SmartSuggestionsSettings())
                }
            }
        }

        viewModelScope.launch {
            // Load active suggestions
            repository.getActiveSuggestions().collect { suggestions ->
                _uiState.update { it.copy(
                    suggestedApps = suggestions,
                    showSmartSuggestionsCard = suggestions.isNotEmpty()
                )}
            }
        }
    }

    /**
     * Initialize essential apps whitelist with defaults
     */
    private suspend fun initializeEssentialAppsWhitelist() {
        val existingWhitelist = repository.getAllWhitelistedAppsSync()
        if (existingWhitelist.isEmpty()) {
            val defaultApps = EssentialAppWhitelist.DEFAULT_ESSENTIAL_APPS.map { (pkg, name) ->
                EssentialAppWhitelist(
                    packageName = pkg,
                    appName = name,
                    isDefault = true
                )
            }
            repository.addAllToWhitelist(defaultApps)
        }
    }

    /**
     * Analyze last 7 days of usage and generate smart suggestions
     * This calculates average usage and suggests a 20% reduction goal
     * with a minimum daily target of 60-120 minutes (1-2 hours)
     */
    fun analyzeUsageAndGenerateSuggestions() {
        viewModelScope.launch {
            try {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())

                // Get last 7 days summaries
                val summaries = repository.getRecentDailySummaries(7)
                if (summaries.isEmpty()) {
                    showToast("Not enough usage data. Use your phone for a few days first.")
                    return@launch
                }

                // Calculate average daily usage
                val totalMinutes = summaries.sumOf { it.totalScreenTimeMinutes }
                val averageMinutes = totalMinutes / summaries.size

                // Calculate suggested limit (80% of average = 20% reduction)
                // Minimum target: 60 minutes (1 hour)
                // Maximum target: 120 minutes (2 hours) if user is already low
                val MINIMUM_DAILY_LIMIT = 60 // 1 hour minimum
                val TARGET_DAILY_LIMIT = 120 // 2 hours is ideal target

                val calculatedLimit = (averageMinutes * 0.8).toInt()
                val suggestedLimit = when {
                    calculatedLimit < MINIMUM_DAILY_LIMIT -> MINIMUM_DAILY_LIMIT
                    calculatedLimit > TARGET_DAILY_LIMIT && averageMinutes > TARGET_DAILY_LIMIT * 1.5 -> TARGET_DAILY_LIMIT
                    else -> calculatedLimit
                }.coerceIn(MINIMUM_DAILY_LIMIT, averageMinutes.coerceAtLeast(MINIMUM_DAILY_LIMIT))

                // Update settings
                repository.updateSmartAnalysis(today, averageMinutes, suggestedLimit)

                // Generate app suggestions based on usage patterns
                generateAppSuggestions()

                _uiState.update { it.copy(
                    averageDailyUsageMinutes = averageMinutes,
                    suggestedDailyLimitMinutes = suggestedLimit
                )}

                val hours = suggestedLimit / 60
                val mins = suggestedLimit % 60
                val goalText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                showToast("Goal set: $goalText daily (target: 1-2 hours)")

            } catch (e: Exception) {
                showToast("Error analyzing usage: ${e.message}")
            }
        }
    }

    /**
     * Generate app suggestions based on usage patterns
     * Identifies top time-consuming apps, especially social/entertainment
     */
    private suspend fun generateAppSuggestions() {
        try {
            // Get whitelist to exclude essential apps
            val whitelistedPackages = repository.getWhitelistedPackageNames().toSet()

            // Get installed apps with usage
            val apps = installedApps.value
            if (apps.isEmpty()) return

            // Social/Entertainment categories (keywords to identify distracting apps)
            val socialKeywords = setOf(
                "instagram", "facebook", "twitter", "tiktok", "snapchat",
                "reddit", "pinterest", "tumblr", "whatsapp", "telegram",
                "discord", "messenger", "wechat", "line", "viber"
            )
            val entertainmentKeywords = setOf(
                "youtube", "netflix", "twitch", "hulu", "disney", "spotify",
                "prime video", "game", "gaming", "vlc", "player"
            )

            // Filter apps that are likely distracting
            val distractingApps = apps.filter { app ->
                val pkgLower = app.packageName.lowercase()
                val nameLower = app.appName.lowercase()

                // Skip whitelisted and system apps
                if (whitelistedPackages.contains(app.packageName)) return@filter false
                if (app.packageName.startsWith("com.android.") ||
                    app.packageName.startsWith("com.google.android.gms") ||
                    app.packageName.startsWith("com.samsung.android.")) return@filter false

                // Check if it's a social or entertainment app
                socialKeywords.any { pkgLower.contains(it) || nameLower.contains(it) } ||
                entertainmentKeywords.any { pkgLower.contains(it) || nameLower.contains(it) }
            }

            // Create suggestions (max 5 apps)
            val suggestions = distractingApps.take(5).map { app ->
                val category = when {
                    socialKeywords.any { app.packageName.lowercase().contains(it) } -> "social"
                    entertainmentKeywords.any { app.packageName.lowercase().contains(it) } -> "entertainment"
                    else -> "other"
                }

                SuggestedBlockingApp(
                    packageName = app.packageName,
                    appName = app.appName,
                    averageDailyMinutes = 0, // Would need usage stats to calculate
                    category = category,
                    suggestionReason = when (category) {
                        "social" -> "Social media apps are top time wasters"
                        "entertainment" -> "Entertainment apps reduce productivity"
                        else -> "Identified as potentially distracting"
                    }
                )
            }

            // Clear old and add new suggestions
            repository.clearAllSuggestions()
            repository.addAllSuggestions(suggestions)

        } catch (e: Exception) {
            // Silently fail - suggestions are optional
        }
    }

    /**
     * Accept a suggestion and add app to Quick Block
     */
    fun acceptSuggestion(packageName: String) {
        viewModelScope.launch {
            repository.acceptSuggestion(packageName)

            // Add to blocked apps list
            val appName = AppUtils.getAppName(application, packageName)
            repository.insertBlockedApp(BlockedApp(
                packageName = packageName,
                appName = appName,
                isBlocked = true
            ))

            showToast("$appName added to block list")
        }
    }

    /**
     * Dismiss a suggestion
     */
    fun dismissSuggestion(packageName: String) {
        viewModelScope.launch {
            repository.dismissSuggestion(packageName)
        }
    }

    /**
     * Accept all suggestions and add to Quick Block
     */
    fun acceptAllSuggestions() {
        viewModelScope.launch {
            val suggestions = repository.getActiveSuggestionsSync()

            // Add all suggested apps to blocked list
            val blockedApps = suggestions.map { suggestion ->
                repository.acceptSuggestion(suggestion.packageName)
                BlockedApp(
                    packageName = suggestion.packageName,
                    appName = suggestion.appName,
                    isBlocked = true
                )
            }

            repository.insertBlockedApps(blockedApps)
            showToast("${suggestions.size} apps added to block list")
        }
    }

    /**
     * Apply suggested daily limit to Global Daily Limit
     */
    fun applySuggestedDailyLimit() {
        viewModelScope.launch {
            val suggestedLimit = _uiState.value.suggestedDailyLimitMinutes
            if (suggestedLimit <= 0) {
                showToast("Run analysis first to get a suggested limit")
                return@launch
            }

            // Update Global Daily Limit settings
            val currentSettings = repository.getGlobalDailyLimitSettingsSync()
            if (currentSettings != null) {
                repository.updateGlobalDailyLimitSettings(currentSettings.copy(
                    isEnabled = true,
                    dailyLimitMinutes = suggestedLimit,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                repository.saveGlobalDailyLimitSettings(
                    GlobalDailyLimitSettings(
                        isEnabled = true,
                        dailyLimitMinutes = suggestedLimit
                    )
                )
            }

            _uiState.update { it.copy(
                isGlobalDailyLimitEnabled = true,
                globalDailyLimitMinutes = suggestedLimit
            )}

            // Notify service
            val intent = Intent(FocusBlockAccessibilityService.ACTION_REFRESH_GLOBAL_LIMIT_CACHE)
            intent.`package` = application.packageName
            application.sendBroadcast(intent)

            showToast("Daily limit set to ${suggestedLimit} minutes (20% reduction)")
        }
    }

    // ========== GLOBAL DAILY LIMIT (HOMEPAGE) ==========

    /**
     * Load Global Daily Limit settings for homepage display
     */
    private fun loadGlobalDailyLimitForHome() {
        viewModelScope.launch {
            repository.getGlobalDailyLimitSettings().collect { settings ->
                if (settings != null) {
                    _uiState.update { it.copy(
                        isGlobalDailyLimitEnabled = settings.isEnabled,
                        globalDailyLimitMinutes = settings.dailyLimitMinutes
                    )}
                }
            }
        }

        // Load current usage
        viewModelScope.launch {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())

            repository.getGlobalDailyUsage(today).collect { usage ->
                val currentMinutes = usage?.totalUsageMinutes ?: 0
                val limitMinutes = _uiState.value.globalDailyLimitMinutes
                val progress = if (limitMinutes > 0) {
                    (currentMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f)
                } else 0f

                _uiState.update { it.copy(
                    currentDailyUsageMinutes = currentMinutes,
                    dailyLimitProgress = progress
                )}
            }
        }
    }

    /**
     * Set Global Daily Limit enabled/disabled from homepage
     */
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
                    GlobalDailyLimitSettings(isEnabled = enabled)
                )
            }
            _uiState.update { it.copy(isGlobalDailyLimitEnabled = enabled) }

            // Notify service
            val intent = Intent(FocusBlockAccessibilityService.ACTION_REFRESH_GLOBAL_LIMIT_CACHE)
            intent.`package` = application.packageName
            application.sendBroadcast(intent)
        }
    }

    /**
     * Set Global Daily Limit minutes from homepage
     */
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
                    GlobalDailyLimitSettings(dailyLimitMinutes = minutes)
                )
            }
            _uiState.update { it.copy(globalDailyLimitMinutes = minutes) }

            // Notify service
            val intent = Intent(FocusBlockAccessibilityService.ACTION_REFRESH_GLOBAL_LIMIT_CACHE)
            intent.`package` = application.packageName
            application.sendBroadcast(intent)
        }
    }

    /**
     * Add app to essential whitelist (never suggest blocking)
     */
    fun addToEssentialWhitelist(packageName: String, appName: String) {
        viewModelScope.launch {
            repository.addToWhitelist(EssentialAppWhitelist(
                packageName = packageName,
                appName = appName,
                isDefault = false
            ))
            showToast("$appName added to essential apps")
        }
    }

    /**
     * Remove app from essential whitelist
     */
    fun removeFromEssentialWhitelist(packageName: String) {
        viewModelScope.launch {
            repository.removeFromWhitelist(packageName)
        }
    }
}
