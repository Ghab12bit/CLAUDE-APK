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
import com.focusblock.app.utils.AppUtils
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
    val hourlyUsage: Map<Int, Triple<Int, Int, Int>> = emptyMap(), // Hour -> (Distractive, Neutral, Productive) minutes
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

    // Known distractive app categories/packages
    private val distractiveApps = setOf(
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.twitter.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.snapchat.android",
        "com.pinterest",
        "com.reddit.frontpage",
        "com.tumblr",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord",
        "com.spotify.music",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.google.android.youtube",
        "tv.twitch.android.app"
    )

    private val productiveApps = setOf(
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.microsoft.office.outlook",
        "com.google.android.gm",
        "com.slack",
        "com.microsoft.teams",
        "com.notion.id",
        "com.todoist",
        "com.anydo",
        "com.ticktick.task",
        "com.evernote",
        "com.google.android.calendar",
        "com.android.vending", // Play Store
        "com.google.android.apps.maps",
        "com.android.chrome",
        "org.mozilla.firefox"
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
                    UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    endTime
                ) ?: emptyList()

                processUsageStats(usageStats, dateLabel, startTime, endTime)
            } catch (e: Exception) {
                // No permission or error
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
        // Filter out system apps and aggregate
        val filteredStats = stats.filter { stat ->
            stat.totalTimeInForeground > 60000 && // At least 1 minute
            !isSystemApp(stat.packageName) &&
            stat.packageName != application.packageName
        }

        // Calculate total screen time
        val totalTimeMs = filteredStats.sumOf { it.totalTimeInForeground }
        val totalMinutes = (totalTimeMs / 60000).toInt()
        val totalScreenTime = formatDuration(totalMinutes)

        // Calculate hourly usage distribution (simplified estimation)
        val hourlyUsage = estimateHourlyUsage(filteredStats, startTime, endTime)

        // Get most used apps
        val appUsageList = filteredStats
            .sortedByDescending { it.totalTimeInForeground }
            .take(10)
            .map { stat ->
                val appName = getAppName(stat.packageName)
                val minutes = (stat.totalTimeInForeground / 60000).toInt()
                val category = categorizeApp(stat.packageName)

                AppUsageInfo(
                    packageName = stat.packageName,
                    appName = appName,
                    usageDuration = formatDuration(minutes),
                    usageMinutes = minutes,
                    category = category
                )
            }

        // Calculate category distribution
        val categoryMinutes = mutableMapOf(
            AppCategory.DISTRACTIVE to 0,
            AppCategory.NEUTRAL to 0,
            AppCategory.PRODUCTIVE to 0
        )

        filteredStats.forEach { stat ->
            val category = categorizeApp(stat.packageName)
            val minutes = (stat.totalTimeInForeground / 60000).toInt()
            categoryMinutes[category] = categoryMinutes[category]!! + minutes
        }

        val total = categoryMinutes.values.sum().coerceAtLeast(1)
        val distractivePercent = (categoryMinutes[AppCategory.DISTRACTIVE]!! * 100) / total
        val neutralPercent = (categoryMinutes[AppCategory.NEUTRAL]!! * 100) / total
        val productivePercent = (categoryMinutes[AppCategory.PRODUCTIVE]!! * 100) / total

        // Calculate peak time
        val peakHour = hourlyUsage.maxByOrNull { (_, v) -> v.first + v.second + v.third }?.key ?: 12
        val peakTimeRange = "${formatHour(peakHour)} - ${formatHour(peakHour + 1)}"

        // Calculate balance percentage (phone time / awake time, assuming 16h awake)
        val awakeMinutes = 16 * 60
        val balancePercentage = ((totalMinutes * 100) / awakeMinutes).coerceIn(0, 100)

        // Estimate pickups (one per app session, roughly)
        val pickupCount = filteredStats.sumOf {
            // Rough estimate: one pickup per 15 minutes of use minimum 1
            ((it.totalTimeInForeground / (15 * 60 * 1000)) + 1).toInt()
        }

        // Calculate screen time change (vs yesterday for day tab)
        val changeText = calculateChangeText(totalMinutes, startTime)

        // Estimate longest focus and continuous use
        val longestFocus = formatDuration(calculateLongestFocusPeriod(filteredStats))
        val longestContinuousUse = formatDuration(calculateLongestContinuousUse(filteredStats))

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
                longestFocus = longestFocus,
                longestContinuousUse = longestContinuousUse
            )
        }
    }

    private fun estimateHourlyUsage(
        stats: List<UsageStats>,
        startTime: Long,
        endTime: Long
    ): Map<Int, Triple<Int, Int, Int>> {
        val hourlyMap = mutableMapOf<Int, Triple<Int, Int, Int>>()

        // Initialize all hours
        for (hour in 0..23) {
            hourlyMap[hour] = Triple(0, 0, 0)
        }

        // Distribute usage across hours (simplified estimation)
        val totalMinutes = stats.sumOf { it.totalTimeInForeground / 60000 }.toInt()
        if (totalMinutes == 0) return hourlyMap

        // Group by category
        val categoryUsage = stats.groupBy { categorizeApp(it.packageName) }
            .mapValues { (_, v) -> v.sumOf { it.totalTimeInForeground / 60000 }.toInt() }

        val distractiveTotal = categoryUsage[AppCategory.DISTRACTIVE] ?: 0
        val neutralTotal = categoryUsage[AppCategory.NEUTRAL] ?: 0
        val productiveTotal = categoryUsage[AppCategory.PRODUCTIVE] ?: 0

        // Distribute across typical usage hours (8 AM - 11 PM more heavily)
        val weights = mapOf(
            0 to 0.02f, 1 to 0.01f, 2 to 0.01f, 3 to 0.01f,
            4 to 0.01f, 5 to 0.01f, 6 to 0.02f, 7 to 0.03f,
            8 to 0.05f, 9 to 0.06f, 10 to 0.07f, 11 to 0.07f,
            12 to 0.08f, 13 to 0.07f, 14 to 0.06f, 15 to 0.06f,
            16 to 0.06f, 17 to 0.06f, 18 to 0.06f, 19 to 0.06f,
            20 to 0.06f, 21 to 0.04f, 22 to 0.03f, 23 to 0.02f
        )

        for (hour in 0..23) {
            val weight = weights[hour] ?: 0.04f
            hourlyMap[hour] = Triple(
                (distractiveTotal * weight).toInt(),
                (neutralTotal * weight).toInt(),
                (productiveTotal * weight).toInt()
            )
        }

        return hourlyMap
    }

    private fun calculateChangeText(currentMinutes: Int, periodStart: Long): String {
        // Get previous period's usage
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = periodStart
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val previousStart = calendar.timeInMillis

        calendar.timeInMillis = periodStart
        val previousEnd = periodStart

        try {
            val previousStats = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                previousStart,
                previousEnd
            ) ?: emptyList()

            val previousMinutes = previousStats
                .filter { !isSystemApp(it.packageName) && it.totalTimeInForeground > 60000 }
                .sumOf { it.totalTimeInForeground / 60000 }.toInt()

            val diff = currentMinutes - previousMinutes
            return if (diff > 0) {
                "${formatDuration(diff)} more than yesterday"
            } else if (diff < 0) {
                "${formatDuration(-diff)} less than yesterday"
            } else {
                "Same as yesterday"
            }
        } catch (e: Exception) {
            return "vs yesterday unavailable"
        }
    }

    private fun calculateLongestFocusPeriod(stats: List<UsageStats>): Int {
        // Estimate: longest gap between distractive app usage
        // Simplified: return 20-60 min based on distractive usage ratio
        val distractiveMinutes = stats
            .filter { categorizeApp(it.packageName) == AppCategory.DISTRACTIVE }
            .sumOf { it.totalTimeInForeground / 60000 }.toInt()

        val totalMinutes = stats.sumOf { it.totalTimeInForeground / 60000 }.toInt()

        return when {
            totalMinutes == 0 -> 0
            distractiveMinutes == 0 -> 120 // No distractions
            distractiveMinutes < totalMinutes / 4 -> 60
            distractiveMinutes < totalMinutes / 2 -> 30
            else -> 15
        }
    }

    private fun calculateLongestContinuousUse(stats: List<UsageStats>): Int {
        // Return the longest single app session (max foreground time)
        return stats.maxOfOrNull { (it.totalTimeInForeground / 60000).toInt() } ?: 0
    }

    private fun categorizeApp(packageName: String): AppCategory {
        return when {
            distractiveApps.any { packageName.contains(it, ignoreCase = true) } -> AppCategory.DISTRACTIVE
            productiveApps.any { packageName.contains(it, ignoreCase = true) } -> AppCategory.PRODUCTIVE
            packageName.contains("game", ignoreCase = true) -> AppCategory.DISTRACTIVE
            packageName.contains("social", ignoreCase = true) -> AppCategory.DISTRACTIVE
            packageName.contains("video", ignoreCase = true) -> AppCategory.DISTRACTIVE
            packageName.contains("mail", ignoreCase = true) -> AppCategory.PRODUCTIVE
            packageName.contains("calendar", ignoreCase = true) -> AppCategory.PRODUCTIVE
            packageName.contains("office", ignoreCase = true) -> AppCategory.PRODUCTIVE
            else -> AppCategory.NEUTRAL
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val appInfo = application.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            true
        }
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
