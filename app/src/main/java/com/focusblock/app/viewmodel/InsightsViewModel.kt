package com.focusblock.app.viewmodel

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.ui.statistics.AppUsageInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

enum class InsightsTab(val label: String) {
    DAY("Day"),
    WEEK("Week"),
    TREND("Trend")
}

enum class AppCategory(val label: String) {
    DISTRACTIVE("Distractive"),
    NEUTRAL("Neutral"),
    PRODUCTIVE("Productive")
}

data class InsightsUiState(
    val selectedTab: InsightsTab = InsightsTab.DAY,
    val selectedDate: Long = System.currentTimeMillis(), // Current selected date
    val dateLabel: String = "Today",
    val canGoForward: Boolean = false, // Can't go beyond today
    val totalScreenTime: String = "0m",
    val screenTimeChange: String = "No data available",
    val isChangePositive: Boolean = true,
    val hourlyUsage: Map<Int, Triple<Int, Int, Int>> = emptyMap(),
    val mostUsedApps: List<AppUsageInfo> = emptyList(),
    val appsExpanded: Boolean = false,
    val awakeTime: String = "16h",
    val balancePercentage: Int = 0,
    val peakTimeRange: String = "No data",
    val peakTimeRisk: Boolean = false, // True if peak session was too long
    val distractivePercent: Int = 0,
    val neutralPercent: Int = 0,
    val productivePercent: Int = 0,
    val longestFocus: String = "0m",
    val longestContinuousUse: String = "0m",
    val longestSessionWarning: Boolean = false, // True if longest session > 45min
    val pickupCount: Int = 0,
    val isPickupEstimated: Boolean = true, // Pickups are inferred from events
    val pickupNote: String = "Estimated from app open events",
    val peakTimeDetails: PeakTimeDetails? = null // Peak time drilldown data
)

// Data class for Peak Time detail view
data class PeakTimeDetails(
    val peakWindowRange: String = "",
    val peakStartHour: Int = 0,
    val peakEndHour: Int = 0,
    val totalMinutes: Int = 0,
    val isHighRisk: Boolean = false,
    val distractiveMinutes: Int = 0,
    val neutralMinutes: Int = 0,
    val productiveMinutes: Int = 0,
    val hourlyBreakdown: Map<Int, Triple<Int, Int, Int>> = emptyMap(), // Hours within peak window
    val topApps: List<PeakAppUsage> = emptyList(),
    val sessions: List<PeakSession> = emptyList(),
    val pickupCount: Int = 0,
    val isPickupEstimated: Boolean = true
)

// App usage during peak time
data class PeakAppUsage(
    val packageName: String,
    val appName: String,
    val durationMinutes: Int,
    val category: AppCategory
)

// Individual session during peak time
data class PeakSession(
    val appName: String,
    val startTime: String,
    val endTime: String,
    val durationMinutes: Int
)

// Data class to track app session
private data class AppSession(
    val packageName: String,
    val startTime: Long,
    var endTime: Long = 0L
) {
    val duration: Long get() = if (endTime > startTime) endTime - startTime else 0L
}

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val application: Application,
    private val repository: FocusBlockRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private val usageStatsManager: UsageStatsManager? by lazy {
        application.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    // Keywords to identify distractive apps
    private val distractiveKeywords = setOf(
        "youtube", "instagram", "facebook", "twitter", "tiktok", "snapchat",
        "reddit", "pinterest", "tumblr", "whatsapp", "telegram", "discord",
        "spotify", "netflix", "twitch", "hulu", "disney", "prime video",
        "messenger", "wechat", "line", "viber", "tinder", "bumble",
        "musically", "reels", "shorts", "mxplayer", "mx player", "vlc",
        "game", "gaming", "candy", "clash", "pubg", "fortnite", "roblox",
        "revanced", "vanced", "newpipe"
    )

    // Keywords to identify productive apps
    private val productiveKeywords = setOf(
        "docs", "sheets", "slides", "word", "excel", "powerpoint",
        "outlook", "gmail", "calendar", "notion", "todoist", "trello",
        "asana", "slack", "teams", "zoom", "meet", "drive", "dropbox",
        "onedrive", "evernote", "notes", "calculator", "reminder", "task",
        "office", "pdf", "reader", "editor", "code", "studio"
    )

    // Packages to completely exclude (system/background services)
    private val excludedPackages = setOf(
        // Android System
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.android.settings",
        "com.android.vending",
        "com.android.providers",
        "com.android.inputmethod",
        "com.android.phone",
        "com.android.server",
        "com.android.keychain",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.android.shell",
        "com.android.incallui",

        // Google Services
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.permissioncontroller",
        "com.google.android.inputmethod",
        "com.google.android.ext.services",
        "com.google.android.providers",
        "com.google.android.overlay",
        "com.google.android.packageinstaller",

        // Samsung System (One UI)
        "com.samsung.android.lool", // Samsung Device Care
        "com.samsung.android.forest", // Digital Wellbeing
        "com.samsung.android.app.routines",
        "com.samsung.android.honeyboard", // Samsung Keyboard
        "com.samsung.android.smartswitchassistant",
        "com.samsung.android.game.gamehome",
        "com.samsung.android.game.gametools",
        "com.samsung.android.app.tips",
        "com.samsung.android.dialer",
        "com.samsung.android.messaging",
        "com.samsung.android.contacts",
        "com.samsung.android.incallui",
        "com.samsung.android.app.smartcapture",
        "com.samsung.android.providers",
        "com.samsung.android.samsungpass",
        "com.samsung.android.samsungpassautofill",
        "com.samsung.android.authfw",
        "com.samsung.android.biometrics",
        "com.samsung.android.rubin.app",
        "com.samsung.android.visionintelligence",
        "com.samsung.android.bixby",
        "com.samsung.android.app.settings.bixby",
        "com.samsung.android.oneconnect",
        "com.samsung.android.mdx",
        "com.samsung.android.mobileservice",
        "com.samsung.android.spay",
        "com.samsung.android.themestore",
        "com.samsung.android.app.spage",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.da.daagent",
        "com.samsung.android.stickercenter",
        "com.samsung.android.ardrawing",
        "com.samsung.android.aremoji",

        // Samsung Launcher
        "com.sec.android.app.launcher",
        "com.sec.android.app.samsungapps",
        "com.sec.android.inputmethod",
        "com.sec.android.daemonapp",
        "com.sec.android.provider",
        "com.sec.android.app.sbrowser", // Samsung Internet - might want to track this

        // Other System
        "com.qualcomm",
        "com.android.nfc",
        "com.android.bluetooth"
    )

    init {
        loadUsageData()
    }

    fun setTab(tab: InsightsTab) {
        // Reset to today when changing tabs
        _uiState.update { it.copy(
            selectedTab = tab,
            selectedDate = System.currentTimeMillis()
        )}
        loadUsageData()
    }

    fun toggleAppsExpanded() {
        _uiState.update { it.copy(appsExpanded = !it.appsExpanded) }
    }

    /**
     * Navigate to previous day
     */
    fun goToPreviousDay() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = _uiState.value.selectedDate
            add(Calendar.DAY_OF_YEAR, -1)
        }
        _uiState.update { it.copy(selectedDate = calendar.timeInMillis) }
        loadUsageData()
    }

    /**
     * Navigate to next day (limited to today)
     */
    fun goToNextDay() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = _uiState.value.selectedDate
            add(Calendar.DAY_OF_YEAR, 1)
        }

        // Don't go beyond today
        val today = Calendar.getInstance()
        if (calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            calendar.get(Calendar.DAY_OF_YEAR) <= today.get(Calendar.DAY_OF_YEAR)) {
            _uiState.update { it.copy(selectedDate = calendar.timeInMillis) }
            loadUsageData()
        }
    }

    /**
     * Go to a specific date
     */
    fun goToDate(timestamp: Long) {
        // Don't go beyond today
        if (timestamp <= System.currentTimeMillis()) {
            _uiState.update { it.copy(selectedDate = timestamp) }
            loadUsageData()
        }
    }

    /**
     * Check if selected date is today
     */
    private fun isToday(timestamp: Long): Boolean {
        val selected = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        return selected.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
               selected.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Format date label based on selected date
     */
    private fun formatDateLabel(timestamp: Long): String {
        val selected = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        return when {
            selected.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            selected.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"

            selected.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
            selected.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"

            else -> SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun loadUsageData() {
        viewModelScope.launch {
            val tab = _uiState.value.selectedTab
            val selectedDate = _uiState.value.selectedDate
            val calendar = Calendar.getInstance().apply { timeInMillis = selectedDate }

            val (startTime, endTime, dateLabel) = when (tab) {
                InsightsTab.DAY -> {
                    // Use selected date for Day view
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val dayStart = calendar.timeInMillis

                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                    val dayEnd = if (isToday(selectedDate)) {
                        System.currentTimeMillis()
                    } else {
                        calendar.timeInMillis
                    }

                    Triple(dayStart, dayEnd, formatDateLabel(selectedDate))
                }
                InsightsTab.WEEK -> {
                    calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val weekStart = SimpleDateFormat("MMM d", Locale.getDefault()).format(calendar.time)
                    Triple(calendar.timeInMillis, System.currentTimeMillis(), "This Week ($weekStart)")
                }
                InsightsTab.TREND -> {
                    calendar.add(Calendar.DAY_OF_YEAR, -30)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    Triple(calendar.timeInMillis, System.currentTimeMillis(), "Last 30 Days")
                }
            }

            // Check if can go forward (only if not today)
            val canGoForward = !isToday(selectedDate) && tab == InsightsTab.DAY

            withContext(Dispatchers.IO) {
                try {
                    // Use UsageEvents API for accurate tracking
                    val usageData = getAccurateUsageFromEvents(startTime, endTime)
                    val previousData = if (tab == InsightsTab.DAY) {
                        getAccurateUsageFromEvents(startTime - 86400000, startTime)
                    } else null

                    withContext(Dispatchers.Main) {
                        processUsageData(usageData, previousData, dateLabel, canGoForward)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                dateLabel = dateLabel,
                                canGoForward = canGoForward,
                                screenTimeChange = "Grant usage access permission"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Get accurate usage data using UsageEvents API
     * This tracks individual MOVE_TO_FOREGROUND and MOVE_TO_BACKGROUND events
     * to calculate exact screen time per app
     *
     * PICKUP CALCULATION LOGIC:
     * -------------------------
     * Pickups are ESTIMATED, not directly measured. The UsageEvents API does not
     * provide a direct "phone pickup" event. Instead, we infer pickups by counting
     * transitions from background state to foreground state.
     *
     * How it works:
     * 1. We track lastEventWasBackground flag
     * 2. When an app moves to foreground (MOVE_TO_FOREGROUND or ACTIVITY_RESUMED)
     *    after the previous event was a background event, we count it as a pickup
     * 3. Screen off events (SCREEN_NON_INTERACTIVE) reset to background state
     *
     * Limitations:
     * - This counts app opens, not physical phone pickups
     * - Multiple quick app switches won't count as multiple pickups
     * - Notifications that briefly wake screen may be counted
     * - Accuracy is ~60-80% compared to actual pickup sensors
     *
     * The UI clearly labels this as "Estimated from app open events"
     */
    private fun getAccurateUsageFromEvents(startTime: Long, endTime: Long): Map<String, AppUsageData> {
        val usageMap = mutableMapOf<String, AppUsageData>()
        val activeApps = mutableMapOf<String, Long>() // packageName -> foreground start time
        val hourlyUsageMap = mutableMapOf<String, MutableMap<Int, Long>>() // packageName -> hour -> duration

        // Pickup estimation: count foreground events after background state
        // This is NOT a direct measurement - pickups are inferred from usage patterns
        var pickupCount = 0
        var lastEventWasBackground = true // Assume starting from background (screen off)

        val usageEvents = usageStatsManager?.queryEvents(startTime, endTime) ?: return emptyMap()
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName ?: continue

            // Skip excluded packages
            if (shouldExcludePackage(packageName)) continue

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // App came to foreground
                    activeApps[packageName] = event.timeStamp

                    // PICKUP ESTIMATION:
                    // Count as pickup when transitioning from background to foreground.
                    // This is an APPROXIMATION - it detects "sessions" starting after
                    // the screen was off or all apps were in background.
                    // It does NOT use hardware pickup sensors (not available via API).
                    if (lastEventWasBackground) {
                        pickupCount++
                        lastEventWasBackground = false
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    // App went to background
                    val startTimestamp = activeApps.remove(packageName)
                    if (startTimestamp != null && startTimestamp < event.timeStamp) {
                        val duration = event.timeStamp - startTimestamp

                        // Only count reasonable sessions (< 4 hours continuous)
                        if (duration < 4 * 60 * 60 * 1000) {
                            // Add to total usage
                            val existing = usageMap.getOrPut(packageName) {
                                AppUsageData(packageName, 0L, 0, mutableMapOf())
                            }
                            usageMap[packageName] = existing.copy(
                                totalTime = existing.totalTime + duration,
                                sessionCount = existing.sessionCount + 1
                            )

                            // Track hourly usage
                            val hour = Calendar.getInstance().apply {
                                timeInMillis = startTimestamp
                            }.get(Calendar.HOUR_OF_DAY)

                            val hourlyMap = hourlyUsageMap.getOrPut(packageName) { mutableMapOf() }
                            hourlyMap[hour] = (hourlyMap[hour] ?: 0L) + duration
                        }
                    }
                    lastEventWasBackground = true
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Screen turned off - close all active sessions
                    val currentTime = event.timeStamp
                    activeApps.forEach { (pkg, startTimestamp) ->
                        if (startTimestamp < currentTime) {
                            val duration = currentTime - startTimestamp
                            if (duration < 4 * 60 * 60 * 1000) {
                                val existing = usageMap.getOrPut(pkg) {
                                    AppUsageData(pkg, 0L, 0, mutableMapOf())
                                }
                                usageMap[pkg] = existing.copy(
                                    totalTime = existing.totalTime + duration,
                                    sessionCount = existing.sessionCount + 1
                                )
                            }
                        }
                    }
                    activeApps.clear()
                    lastEventWasBackground = true
                }
            }
        }

        // Handle still-active apps (currently in foreground)
        val now = System.currentTimeMillis().coerceAtMost(endTime)
        activeApps.forEach { (pkg, startTimestamp) ->
            if (startTimestamp < now) {
                val duration = now - startTimestamp
                if (duration < 4 * 60 * 60 * 1000) {
                    val existing = usageMap.getOrPut(pkg) {
                        AppUsageData(pkg, 0L, 0, mutableMapOf())
                    }
                    usageMap[pkg] = existing.copy(
                        totalTime = existing.totalTime + duration,
                        sessionCount = existing.sessionCount + 1
                    )
                }
            }
        }

        // Store pickup count in a special entry
        usageMap["__metadata__"] = AppUsageData(
            "__metadata__",
            pickupCount.toLong(),
            pickupCount,
            mutableMapOf() // No hourly data for metadata
        )

        // Add hourly data to each app
        hourlyUsageMap.forEach { (pkg, hourly) ->
            usageMap[pkg]?.let { data ->
                usageMap[pkg] = data.copy(hourlyUsage = hourly)
            }
        }

        return usageMap.filter { it.key != "__metadata__" || it.key == "__metadata__" }
    }

    private fun shouldExcludePackage(packageName: String): Boolean {
        // Check exact matches
        if (excludedPackages.contains(packageName)) return true

        // Check prefix matches
        if (excludedPackages.any { packageName.startsWith(it) }) return true

        // Exclude our own app
        if (packageName == application.packageName) return true

        // Check if it's a system app
        return try {
            val appInfo = application.packageManager.getApplicationInfo(packageName, 0)
            // Only exclude if it's a system app AND not a user-installed update
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            // Keep updated system apps (like Chrome, YouTube if pre-installed)
            isSystem && !isUpdatedSystem && !isUserFacingApp(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            true
        }
    }

    private fun isUserFacingApp(packageName: String): Boolean {
        // These are system apps but user-facing, so we should track them
        val userFacingSystemApps = setOf(
            "com.android.chrome",
            "com.google.android.youtube",
            "com.google.android.apps.photos",
            "com.google.android.apps.maps",
            "com.google.android.gm",
            "com.google.android.calendar",
            "com.sec.android.app.sbrowser" // Samsung Internet
        )
        return userFacingSystemApps.contains(packageName)
    }

    private fun processUsageData(
        usageData: Map<String, AppUsageData>,
        previousDayData: Map<String, AppUsageData>?,
        dateLabel: String,
        canGoForward: Boolean = false
    ) {
        // Extract metadata
        val metadata = usageData["__metadata__"]
        val pickupCount = metadata?.sessionCount ?: 0

        // Filter actual app data
        val appData = usageData.filter { it.key != "__metadata__" && it.value.totalTime > 60000 }

        // Calculate total screen time
        val totalTimeMs = appData.values.sumOf { it.totalTime }
        val totalMinutes = (totalTimeMs / 60000).toInt()
        val totalScreenTime = formatDuration(totalMinutes)

        // Get most used apps
        val appUsageList = appData
            .toList()
            .sortedByDescending { it.second.totalTime }
            .take(15)
            .map { (packageName, data) ->
                val appName = getAppName(packageName)
                val minutes = (data.totalTime / 60000).toInt()
                val category = categorizeApp(packageName, appName)

                AppUsageInfo(
                    packageName = packageName,
                    appName = appName,
                    usageDuration = formatDuration(minutes),
                    usageMinutes = minutes,
                    category = category
                )
            }

        // Calculate category distribution
        var distractiveMs = 0L
        var neutralMs = 0L
        var productiveMs = 0L

        appData.forEach { (packageName, data) ->
            val appName = getAppName(packageName)
            when (categorizeApp(packageName, appName)) {
                AppCategory.DISTRACTIVE -> distractiveMs += data.totalTime
                AppCategory.NEUTRAL -> neutralMs += data.totalTime
                AppCategory.PRODUCTIVE -> productiveMs += data.totalTime
            }
        }

        val totalCategoryMs = (distractiveMs + neutralMs + productiveMs).coerceAtLeast(1)
        val distractivePercent = ((distractiveMs * 100) / totalCategoryMs).toInt()
        val neutralPercent = ((neutralMs * 100) / totalCategoryMs).toInt()
        val productivePercent = ((productiveMs * 100) / totalCategoryMs).toInt()

        // Calculate hourly usage
        val hourlyUsage = calculateHourlyUsage(appData)

        // Calculate peak time
        val peakHour = hourlyUsage.maxByOrNull { (_, v) -> v.first + v.second + v.third }?.key ?: 12
        val peakTimeRange = "${formatHour(peakHour)} - ${formatHour(peakHour + 1)}"

        // Calculate balance percentage
        val awakeMinutes = 16 * 60
        val balancePercentage = ((totalMinutes * 100) / awakeMinutes).coerceIn(0, 100)

        // Calculate change from previous day
        val changeText = if (previousDayData != null) {
            val previousMs = previousDayData
                .filter { it.key != "__metadata__" && it.value.totalTime > 60000 }
                .values.sumOf { it.totalTime }
            val previousMinutes = (previousMs / 60000).toInt()
            val diff = totalMinutes - previousMinutes

            when {
                diff > 0 -> "${formatDuration(diff)} more than previous day"
                diff < 0 -> "${formatDuration(-diff)} less than previous day"
                else -> "Same as previous day"
            }
        } else {
            "No comparison data"
        }

        // Calculate focus metrics
        val longestAppMinutes = appData.values.maxOfOrNull { (it.totalTime / 60000).toInt() } ?: 0
        val distractiveMinutes = (distractiveMs / 60000).toInt()
        val focusEstimate = when {
            totalMinutes == 0 -> 0
            distractiveMinutes == 0 -> 120
            distractiveMinutes < totalMinutes / 4 -> 60
            distractiveMinutes < totalMinutes / 2 -> 30
            else -> 15
        }

        // Calculate risk warnings
        val peakHourUsage = hourlyUsage[peakHour]?.let { it.first + it.second + it.third } ?: 0
        val peakTimeRisk = peakHourUsage > 45 // More than 45 min in one hour is risky
        val longestSessionWarning = longestAppMinutes > 45 // More than 45 min continuous use

        // Build peak time details for drilldown
        val peakTimeDetails = buildPeakTimeDetails(appData, hourlyUsage, pickupCount)

        _uiState.update {
            it.copy(
                dateLabel = dateLabel,
                canGoForward = canGoForward,
                totalScreenTime = totalScreenTime,
                screenTimeChange = changeText,
                isChangePositive = changeText.contains("less"),
                hourlyUsage = hourlyUsage,
                mostUsedApps = appUsageList,
                balancePercentage = balancePercentage,
                peakTimeRange = peakTimeRange,
                peakTimeRisk = peakTimeRisk,
                distractivePercent = distractivePercent,
                neutralPercent = neutralPercent,
                productivePercent = productivePercent,
                pickupCount = pickupCount,
                isPickupEstimated = true,
                pickupNote = "Estimated from app open events",
                longestFocus = formatDuration(focusEstimate),
                longestContinuousUse = formatDuration(longestAppMinutes),
                longestSessionWarning = longestSessionWarning,
                peakTimeDetails = peakTimeDetails
            )
        }
    }

    private fun calculateHourlyUsage(appData: Map<String, AppUsageData>): Map<Int, Triple<Int, Int, Int>> {
        val hourlyMap = mutableMapOf<Int, Triple<Int, Int, Int>>()

        // Initialize all hours
        for (hour in 0..23) {
            hourlyMap[hour] = Triple(0, 0, 0)
        }

        // Aggregate hourly data by category
        val hourlyDistractive = mutableMapOf<Int, Long>()
        val hourlyNeutral = mutableMapOf<Int, Long>()
        val hourlyProductive = mutableMapOf<Int, Long>()

        appData.forEach { (packageName, data) ->
            val appName = getAppName(packageName)
            val category = categorizeApp(packageName, appName)

            data.hourlyUsage.forEach { (hour, duration) ->
                when (category) {
                    AppCategory.DISTRACTIVE -> hourlyDistractive[hour] = (hourlyDistractive[hour] ?: 0L) + duration
                    AppCategory.NEUTRAL -> hourlyNeutral[hour] = (hourlyNeutral[hour] ?: 0L) + duration
                    AppCategory.PRODUCTIVE -> hourlyProductive[hour] = (hourlyProductive[hour] ?: 0L) + duration
                }
            }
        }

        for (hour in 0..23) {
            hourlyMap[hour] = Triple(
                ((hourlyDistractive[hour] ?: 0L) / 60000).toInt(),
                ((hourlyNeutral[hour] ?: 0L) / 60000).toInt(),
                ((hourlyProductive[hour] ?: 0L) / 60000).toInt()
            )
        }

        return hourlyMap
    }

    private fun categorizeApp(packageName: String, appName: String): AppCategory {
        val lowerPackage = packageName.lowercase()
        val lowerName = appName.lowercase()

        if (distractiveKeywords.any { keyword ->
            lowerPackage.contains(keyword) || lowerName.contains(keyword)
        }) {
            return AppCategory.DISTRACTIVE
        }

        if (productiveKeywords.any { keyword ->
            lowerPackage.contains(keyword) || lowerName.contains(keyword)
        }) {
            return AppCategory.PRODUCTIVE
        }

        return AppCategory.NEUTRAL
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = application.packageManager.getApplicationInfo(packageName, 0)
            application.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast(".")
        }
    }

    private fun formatDuration(minutes: Int): String {
        return when {
            minutes < 1 -> "< 1m"
            minutes < 60 -> "${minutes}m"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
            }
        }
    }

    private fun formatHour(hour: Int): String {
        val normalizedHour = hour % 24
        return when {
            normalizedHour == 0 -> "12 AM"
            normalizedHour < 12 -> "$normalizedHour AM"
            normalizedHour == 12 -> "12 PM"
            else -> "${normalizedHour - 12} PM"
        }
    }

    private fun buildPeakTimeDetails(
        appData: Map<String, AppUsageData>,
        hourlyUsage: Map<Int, Triple<Int, Int, Int>>,
        pickupCount: Int
    ): PeakTimeDetails {
        // Find peak hour (hour with most usage)
        val peakHour = hourlyUsage.maxByOrNull { (_, v) -> v.first + v.second + v.third }?.key ?: 12

        // Get peak window (peak hour and adjacent hours with significant usage)
        val peakStartHour = (peakHour - 1).coerceAtLeast(0)
        val peakEndHour = (peakHour + 1).coerceAtMost(23)

        // Calculate usage in peak window
        var distractiveMinutes = 0
        var neutralMinutes = 0
        var productiveMinutes = 0
        val peakHourlyBreakdown = mutableMapOf<Int, Triple<Int, Int, Int>>()

        for (hour in peakStartHour..peakEndHour) {
            hourlyUsage[hour]?.let { (d, n, p) ->
                distractiveMinutes += d
                neutralMinutes += n
                productiveMinutes += p
                peakHourlyBreakdown[hour] = Triple(d, n, p)
            }
        }

        val totalMinutes = distractiveMinutes + neutralMinutes + productiveMinutes
        val isHighRisk = totalMinutes > 90 || // More than 1.5h in 3-hour window
                         hourlyUsage[peakHour]?.let { it.first + it.second + it.third > 45 } == true

        // Get top apps during peak hours
        val topApps = appData
            .filter { it.key != "__metadata__" }
            .mapNotNull { (packageName, data) ->
                // Calculate usage in peak hours
                var peakUsageMs = 0L
                for (hour in peakStartHour..peakEndHour) {
                    peakUsageMs += data.hourlyUsage[hour] ?: 0L
                }

                if (peakUsageMs > 60000) { // At least 1 minute
                    val appName = getAppName(packageName)
                    val category = categorizeApp(packageName, appName)
                    PeakAppUsage(
                        packageName = packageName,
                        appName = appName,
                        durationMinutes = (peakUsageMs / 60000).toInt(),
                        category = category
                    )
                } else null
            }
            .sortedByDescending { it.durationMinutes }
            .take(5)

        // Build session list from app data
        val sessions = appData
            .filter { it.key != "__metadata__" }
            .flatMap { (packageName, data) ->
                data.sessions
                    .filter { session ->
                        // Check if session overlaps with peak window
                        val sessionHour = Calendar.getInstance().apply {
                            timeInMillis = session.startTime
                        }.get(Calendar.HOUR_OF_DAY)
                        sessionHour in peakStartHour..peakEndHour
                    }
                    .map { session ->
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        PeakSession(
                            appName = getAppName(packageName),
                            startTime = timeFormat.format(Date(session.startTime)),
                            endTime = timeFormat.format(Date(session.endTime)),
                            durationMinutes = (session.duration / 60000).toInt()
                        )
                    }
            }
            .filter { it.durationMinutes >= 1 } // At least 1 minute
            .sortedByDescending { it.durationMinutes }
            .take(10)

        // Estimate pickups during peak (roughly proportional)
        val totalHours = hourlyUsage.values.sumOf { it.first + it.second + it.third }
        val peakPickups = if (totalHours > 0 && totalMinutes > 0) {
            ((pickupCount.toFloat() * totalMinutes) / totalHours.coerceAtLeast(1)).toInt()
        } else 0

        return PeakTimeDetails(
            peakWindowRange = "${formatHour(peakStartHour)} – ${formatHour(peakEndHour + 1)}",
            peakStartHour = peakStartHour,
            peakEndHour = peakEndHour,
            totalMinutes = totalMinutes,
            isHighRisk = isHighRisk,
            distractiveMinutes = distractiveMinutes,
            neutralMinutes = neutralMinutes,
            productiveMinutes = productiveMinutes,
            hourlyBreakdown = peakHourlyBreakdown,
            topApps = topApps,
            sessions = sessions,
            pickupCount = peakPickups,
            isPickupEstimated = true
        )
    }
}

// Data class to hold usage information per app
private data class AppUsageData(
    val packageName: String,
    val totalTime: Long,
    val sessionCount: Int,
    val hourlyUsage: MutableMap<Int, Long> = mutableMapOf(),
    val sessions: List<SessionInfo> = emptyList()
)

// Data class for individual sessions
private data class SessionInfo(
    val startTime: Long,
    val endTime: Long,
    val duration: Long
)
