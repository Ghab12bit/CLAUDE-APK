package com.focusblock.app.viewmodel

import android.app.Application
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.ui.statistics.AppUsageInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val dateLabel: String = "Today",
    val totalScreenTime: String = "0m",
    val screenTimeChange: String = "No data available",
    val isChangePositive: Boolean = true,
    val hourlyUsage: Map<Int, Triple<Int, Int, Int>> = emptyMap(),
    val mostUsedApps: List<AppUsageInfo> = emptyList(),
    val appsExpanded: Boolean = false,
    val awakeTime: String = "16h",
    val balancePercentage: Int = 0,
    val peakTimeRange: String = "No data",
    val distractivePercent: Int = 0,
    val neutralPercent: Int = 0,
    val productivePercent: Int = 0,
    val longestFocus: String = "0m",
    val longestContinuousUse: String = "0m",
    val pickupCount: Int = 0,
    val isPickupEstimated: Boolean = true
)

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

    // Keywords to identify distractive apps (matched against package name OR app name)
    private val distractiveKeywords = setOf(
        "youtube", "instagram", "facebook", "twitter", "tiktok", "snapchat",
        "reddit", "pinterest", "tumblr", "whatsapp", "telegram", "discord",
        "spotify", "netflix", "twitch", "hulu", "disney", "prime video",
        "messenger", "wechat", "line", "viber", "tinder", "bumble",
        "musically", "reels", "shorts", "mxplayer", "mx player", "vlc",
        "game", "gaming", "candy", "clash", "pubg", "fortnite", "roblox",
        "revanced", "vanced", "newpipe" // YouTube alternatives
    )

    // Keywords to identify productive apps
    private val productiveKeywords = setOf(
        "docs", "sheets", "slides", "word", "excel", "powerpoint",
        "outlook", "gmail", "mail", "email", "calendar", "notion",
        "todoist", "trello", "asana", "slack", "teams", "zoom",
        "meet", "drive", "dropbox", "onedrive", "evernote", "notes",
        "calculator", "clock", "alarm", "reminder", "task", "work",
        "office", "pdf", "reader", "editor", "code", "studio"
    )

    init {
        loadUsageData()
    }

    fun setTab(tab: InsightsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        loadUsageData()
    }

    fun toggleAppsExpanded() {
        _uiState.update { it.copy(appsExpanded = !it.appsExpanded) }
    }

    private fun loadUsageData() {
        viewModelScope.launch {
            val tab = _uiState.value.selectedTab
            val calendar = Calendar.getInstance()

            val (startTime, endTime, dateLabel) = when (tab) {
                InsightsTab.DAY -> {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    Triple(calendar.timeInMillis, System.currentTimeMillis(), "Today")
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

            try {
                val usageStats = usageStatsManager?.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime
                ) ?: emptyList()

                processUsageStats(usageStats, dateLabel, startTime, endTime)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        dateLabel = dateLabel,
                        screenTimeChange = "Grant usage access permission"
                    )
                }
            }
        }
    }

    private fun processUsageStats(
        stats: List<UsageStats>,
        dateLabel: String,
        startTime: Long,
        endTime: Long
    ) {
        // IMPORTANT: Aggregate by package name to prevent duplicates
        val aggregatedStats = stats
            .filter { it.totalTimeInForeground > 0 }
            .groupBy { it.packageName }
            .mapValues { (_, statsList) ->
                statsList.sumOf { it.totalTimeInForeground }
            }
            .filter { (packageName, timeMs) ->
                timeMs > 60000 && // At least 1 minute
                !isSystemApp(packageName) &&
                packageName != application.packageName &&
                !isLauncherOrSystemUI(packageName)
            }

        // Calculate total screen time
        val totalTimeMs = aggregatedStats.values.sum()
        val totalMinutes = (totalTimeMs / 60000).toInt()
        val totalScreenTime = formatDuration(totalMinutes)

        // Get most used apps (sorted by usage, no duplicates)
        val appUsageList = aggregatedStats
            .toList()
            .sortedByDescending { it.second }
            .take(15)
            .map { (packageName, timeMs) ->
                val appName = getAppName(packageName)
                val minutes = (timeMs / 60000).toInt()
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
        val categoryMinutes = mutableMapOf(
            AppCategory.DISTRACTIVE to 0L,
            AppCategory.NEUTRAL to 0L,
            AppCategory.PRODUCTIVE to 0L
        )

        aggregatedStats.forEach { (packageName, timeMs) ->
            val appName = getAppName(packageName)
            val category = categorizeApp(packageName, appName)
            categoryMinutes[category] = categoryMinutes[category]!! + timeMs
        }

        val totalCategoryMs = categoryMinutes.values.sum().coerceAtLeast(1)
        val distractivePercent = ((categoryMinutes[AppCategory.DISTRACTIVE]!! * 100) / totalCategoryMs).toInt()
        val neutralPercent = ((categoryMinutes[AppCategory.NEUTRAL]!! * 100) / totalCategoryMs).toInt()
        val productivePercent = ((categoryMinutes[AppCategory.PRODUCTIVE]!! * 100) / totalCategoryMs).toInt()

        // Calculate hourly usage distribution
        val hourlyUsage = estimateHourlyUsage(aggregatedStats, startTime)

        // Calculate peak time
        val peakHour = hourlyUsage.maxByOrNull { (_, v) -> v.first + v.second + v.third }?.key ?: 12
        val peakTimeRange = "${formatHour(peakHour)} - ${formatHour(peakHour + 1)}"

        // Calculate balance percentage (phone time / awake time, assuming 16h awake)
        val awakeMinutes = 16 * 60
        val balancePercentage = ((totalMinutes * 100) / awakeMinutes).coerceIn(0, 100)

        // Estimate pickups based on number of apps used
        val pickupCount = (aggregatedStats.size * 3).coerceAtLeast(totalMinutes / 20)

        // Calculate screen time change (vs yesterday)
        val changeText = calculateChangeText(totalMinutes, startTime)

        // Calculate focus metrics
        val longestAppMinutes = aggregatedStats.values.maxOrNull()?.let { (it / 60000).toInt() } ?: 0
        val distractiveMinutes = (categoryMinutes[AppCategory.DISTRACTIVE]!! / 60000).toInt()
        val focusEstimate = when {
            totalMinutes == 0 -> 0
            distractiveMinutes == 0 -> 120
            distractiveMinutes < totalMinutes / 4 -> 60
            distractiveMinutes < totalMinutes / 2 -> 30
            else -> 15
        }

        _uiState.update {
            it.copy(
                dateLabel = dateLabel,
                totalScreenTime = totalScreenTime,
                screenTimeChange = changeText,
                isChangePositive = changeText.contains("less") || changeText.contains("-"),
                hourlyUsage = hourlyUsage,
                mostUsedApps = appUsageList,
                balancePercentage = balancePercentage,
                peakTimeRange = peakTimeRange,
                distractivePercent = distractivePercent,
                neutralPercent = neutralPercent,
                productivePercent = productivePercent,
                pickupCount = pickupCount,
                isPickupEstimated = true,
                longestFocus = formatDuration(focusEstimate),
                longestContinuousUse = formatDuration(longestAppMinutes)
            )
        }
    }

    private fun estimateHourlyUsage(
        aggregatedStats: Map<String, Long>,
        startTime: Long
    ): Map<Int, Triple<Int, Int, Int>> {
        val hourlyMap = mutableMapOf<Int, Triple<Int, Int, Int>>()

        // Initialize all hours
        for (hour in 0..23) {
            hourlyMap[hour] = Triple(0, 0, 0)
        }

        // Group aggregated stats by category
        var distractiveTotal = 0L
        var neutralTotal = 0L
        var productiveTotal = 0L

        aggregatedStats.forEach { (packageName, timeMs) ->
            val appName = getAppName(packageName)
            when (categorizeApp(packageName, appName)) {
                AppCategory.DISTRACTIVE -> distractiveTotal += timeMs
                AppCategory.NEUTRAL -> neutralTotal += timeMs
                AppCategory.PRODUCTIVE -> productiveTotal += timeMs
            }
        }

        val distractiveMinutes = (distractiveTotal / 60000).toInt()
        val neutralMinutes = (neutralTotal / 60000).toInt()
        val productiveMinutes = (productiveTotal / 60000).toInt()

        if (distractiveMinutes + neutralMinutes + productiveMinutes == 0) return hourlyMap

        // Distribute across typical usage hours (weighted by time of day)
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val weights = mutableMapOf<Int, Float>()

        // More weight to hours that have passed today
        for (hour in 0..23) {
            weights[hour] = when {
                hour > currentHour -> 0.01f // Future hours get minimal weight
                hour in 0..5 -> 0.02f // Late night/early morning
                hour in 6..8 -> 0.05f // Morning
                hour in 9..12 -> 0.08f // Late morning
                hour in 13..17 -> 0.07f // Afternoon
                hour in 18..21 -> 0.08f // Evening (peak usage)
                else -> 0.04f // Late evening
            }
        }

        // Normalize weights for hours up to current hour
        val totalWeight = weights.filter { it.key <= currentHour }.values.sum()

        for (hour in 0..currentHour) {
            val normalizedWeight = if (totalWeight > 0) weights[hour]!! / totalWeight else 0f
            hourlyMap[hour] = Triple(
                (distractiveMinutes * normalizedWeight).toInt(),
                (neutralMinutes * normalizedWeight).toInt(),
                (productiveMinutes * normalizedWeight).toInt()
            )
        }

        return hourlyMap
    }

    private fun calculateChangeText(currentMinutes: Int, periodStart: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = periodStart
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val previousStart = calendar.timeInMillis
        val previousEnd = periodStart

        try {
            val previousStats = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                previousStart,
                previousEnd
            ) ?: emptyList()

            // Aggregate previous day stats
            val previousMinutes = previousStats
                .filter { !isSystemApp(it.packageName) && !isLauncherOrSystemUI(it.packageName) }
                .groupBy { it.packageName }
                .mapValues { (_, list) -> list.sumOf { it.totalTimeInForeground } }
                .values.sum()
                .let { (it / 60000).toInt() }

            val diff = currentMinutes - previousMinutes
            return when {
                diff > 0 -> "${formatDuration(diff)} more than yesterday"
                diff < 0 -> "${formatDuration(-diff)} less than yesterday"
                else -> "Same as yesterday"
            }
        } catch (e: Exception) {
            return "vs yesterday unavailable"
        }
    }

    private fun categorizeApp(packageName: String, appName: String): AppCategory {
        val lowerPackage = packageName.lowercase()
        val lowerName = appName.lowercase()

        // Check for distractive keywords in package name or app name
        if (distractiveKeywords.any { keyword ->
            lowerPackage.contains(keyword) || lowerName.contains(keyword)
        }) {
            return AppCategory.DISTRACTIVE
        }

        // Check for productive keywords
        if (productiveKeywords.any { keyword ->
            lowerPackage.contains(keyword) || lowerName.contains(keyword)
        }) {
            return AppCategory.PRODUCTIVE
        }

        // Default to neutral
        return AppCategory.NEUTRAL
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val appInfo = application.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            true
        }
    }

    private fun isLauncherOrSystemUI(packageName: String): Boolean {
        val systemPackages = setOf(
            "com.android.launcher",
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.samsung.android",
            "com.sec.android",
            "com.android.providers",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "com.samsung.android.honeyboard",
            "com.google.android.permissioncontroller"
        )
        return systemPackages.any { packageName.startsWith(it) }
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
}
