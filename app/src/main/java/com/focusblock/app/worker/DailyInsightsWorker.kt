package com.focusblock.app.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.focusblock.app.FocusBlockApp
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.GlobalDailyLimitSettings
import com.focusblock.app.receiver.ChargingStateReceiver
import com.focusblock.app.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Daily worker that:
 * 1. Tracks screen time usage
 * 2. Compares today's usage with previous days' average
 * 3. If usage is increasing, suggests a 20% reduction
 * 4. Sends motivational notifications based on user's stats
 */
class DailyInsightsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DailyInsightsWorker"
        private const val WORK_NAME = "daily_insights_notification"
        private const val NOTIFICATION_ID = 5001
        private const val USAGE_NOTIFICATION_ID = 5002
        private const val PREFS_NAME = "daily_insights_prefs"
        private const val KEY_LAST_RUN = "last_run_date"

        fun schedule(context: Context) {
            // Schedule to run daily at 9 PM
            val currentTime = Calendar.getInstance()
            val targetTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }

            // If time has passed today, schedule for tomorrow
            if (currentTime.after(targetTime)) {
                targetTime.add(Calendar.DAY_OF_YEAR, 1)
            }

            val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

            val request = PeriodicWorkRequestBuilder<DailyInsightsWorker>(
                1, TimeUnit.DAYS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Scheduled daily insights notification for ${targetTime.time}")
        }
    }

    override suspend fun doWork(): Result {
        try {
            Log.i(TAG, "DailyInsightsWorker started at ${Date()}")

            // Only run in the evening window (8 PM - midnight) to prevent 4 AM wake-ups.
            // WorkManager can drift or be deferred by battery constraints and fire at odd hours.
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (currentHour < 20 || currentHour > 23) {
                Log.i(TAG, "Outside evening window (hour=$currentHour), skipping")
                return Result.success()
            }

            val database = FocusBlockDatabase.getDatabase(applicationContext)

            // Check if we already ran today (prevent duplicate runs)
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val lastRun = prefs.getString(KEY_LAST_RUN, null)

            if (lastRun == today) {
                Log.i(TAG, "Already ran today, skipping")
                return Result.success()
            }

            // Mark as ran today
            prefs.edit().putString(KEY_LAST_RUN, today).apply()

            // 1. Analyze screen time and show 20% reduction notification if needed
            analyzeScreenTimeAndNotify(database)

            // 2. Send motivational notification based on block stats
            val notification = buildBlockNotification(database)
            if (notification != null) {
                showNotification(notification.first, notification.second, NOTIFICATION_ID)
            }

            // 3. Show charging pattern insight if significant phone use while charging detected
            analyzeChargingPatternAndNotify(today)

            Log.i(TAG, "DailyInsightsWorker completed successfully")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send daily insights", e)
            return Result.failure()
        }
    }

    /**
     * Analyze screen time usage and send notification if usage is increasing.
     * If today's usage exceeds the 7-day average by >20%, suggest a 20% reduction.
     */
    private fun analyzeScreenTimeAndNotify(database: FocusBlockDatabase) {
        try {
            val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) {
                Log.w(TAG, "UsageStatsManager not available")
                return
            }

            // Calculate today's usage
            val todayUsage = calculateDayUsage(usageStatsManager, 0)

            // Calculate average of last 7 days (excluding today)
            var totalPreviousDays = 0L
            var daysWithData = 0
            for (daysAgo in 1..7) {
                val dayUsage = calculateDayUsage(usageStatsManager, daysAgo)
                if (dayUsage > 0) {
                    totalPreviousDays += dayUsage
                    daysWithData++
                }
            }

            if (daysWithData == 0) {
                Log.i(TAG, "No previous usage data found")
                return
            }

            val averageUsage = totalPreviousDays / daysWithData
            val todayMinutes = (todayUsage / 60_000).toInt()
            val averageMinutes = (averageUsage / 60_000).toInt()

            // Get yesterday's usage explicitly for a clear day-over-day comparison
            val yesterdayUsage = calculateDayUsage(usageStatsManager, 1)
            val yesterdayMinutes = (yesterdayUsage / 60_000).toInt()

            Log.i(TAG, "Usage analysis: today=${todayMinutes}m, yesterday=${yesterdayMinutes}m, average=${averageMinutes}m, daysWithData=$daysWithData")

            // Save today's usage to database
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            kotlinx.coroutines.runBlocking {
                try {
                    val existingUsage = database.globalDailyUsageDao().getUsageForDateSync(dateStr)
                    if (existingUsage == null) {
                        database.globalDailyUsageDao().insert(
                            com.focusblock.app.database.entity.GlobalDailyUsage(
                                date = dateStr,
                                totalUsageMinutes = todayMinutes
                            )
                        )
                    } else {
                        database.globalDailyUsageDao().updateUsage(dateStr, todayMinutes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save daily usage", e)
                }
            }

            // Compare today vs yesterday first (more meaningful than 7-day average)
            val hasYesterdayData = yesterdayMinutes > 30
            val hasTodayData = todayMinutes > 10

            if (hasYesterdayData && hasTodayData) {
                when {
                    todayMinutes > yesterdayMinutes * 1.2 -> {
                        // Today is >20% worse than yesterday
                        val increasePercent = ((todayMinutes - yesterdayMinutes) * 100) / yesterdayMinutes
                        val suggestedLimit = (averageMinutes * 0.8).toInt().coerceAtLeast(60)
                        val title = "Screen Time Up ${increasePercent}% vs Yesterday"
                        val message = "Yesterday: ${formatMinutes(yesterdayMinutes)} → Today: ${formatMinutes(todayMinutes)}. " +
                                "Try setting a ${formatMinutes(suggestedLimit)} daily limit tomorrow."
                        showNotification(title, message, USAGE_NOTIFICATION_ID)
                        Log.i(TAG, "Sent usage increase notification: $title")
                    }
                    todayMinutes < yesterdayMinutes * 0.8 -> {
                        // Today is >20% better than yesterday - celebrate!
                        val decreasePercent = ((yesterdayMinutes - todayMinutes) * 100) / yesterdayMinutes
                        val title = "Great Progress! Down ${decreasePercent}% vs Yesterday"
                        val message = "Yesterday: ${formatMinutes(yesterdayMinutes)} → Today: ${formatMinutes(todayMinutes)}. Keep building healthy habits!"
                        showNotification(title, message, USAGE_NOTIFICATION_ID)
                        Log.i(TAG, "Sent usage decrease notification: $title")
                    }
                    averageMinutes > 30 && todayMinutes > averageMinutes * 1.2 -> {
                        // Similar to yesterday but above 7-day average
                        val increasePercent = ((todayMinutes - averageMinutes) * 100) / averageMinutes
                        val suggestedLimit = (averageMinutes * 0.8).toInt().coerceAtLeast(60)
                        val title = "Above Average Screen Time"
                        val message = "Today: ${formatMinutes(todayMinutes)} (7-day avg: ${formatMinutes(averageMinutes)}, ${increasePercent}% higher). " +
                                "Try a ${formatMinutes(suggestedLimit)} limit tomorrow."
                        showNotification(title, message, USAGE_NOTIFICATION_ID)
                        Log.i(TAG, "Sent above-average usage notification: $title")
                    }
                }
            } else if (averageMinutes > 30 && hasTodayData) {
                // Fallback to average-based comparison when no yesterday data
                if (todayMinutes > averageMinutes * 1.2) {
                    val increasePercent = ((todayMinutes - averageMinutes) * 100) / averageMinutes
                    val suggestedLimit = (averageMinutes * 0.8).toInt().coerceAtLeast(60)
                    val title = "Screen Time Up ${increasePercent}%"
                    val message = "You've used ${formatMinutes(todayMinutes)} today (avg: ${formatMinutes(averageMinutes)}). " +
                            "Set a ${formatMinutes(suggestedLimit)} daily limit to reduce usage by 20%."
                    showNotification(title, message, USAGE_NOTIFICATION_ID)
                    Log.i(TAG, "Sent usage increase notification: $title")
                } else if (todayMinutes < averageMinutes * 0.8) {
                    val decreasePercent = ((averageMinutes - todayMinutes) * 100) / averageMinutes
                    val title = "Great Progress!"
                    val message = "Your screen time is down ${decreasePercent}% vs your average! Keep building healthy habits."
                    showNotification(title, message, USAGE_NOTIFICATION_ID)
                    Log.i(TAG, "Sent usage decrease notification: $title")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze screen time", e)
        }
    }

    /**
     * Calculate screen time usage for distractive apps for a specific day.
     * @param daysAgo 0 = today, 1 = yesterday, etc.
     * @return Total usage in milliseconds
     */
    private fun calculateDayUsage(usageStatsManager: UsageStatsManager, daysAgo: Int): Long {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val endTime = if (daysAgo == 0) System.currentTimeMillis() else calendar.timeInMillis

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        val activeApps = mutableMapOf<String, Long>()
        val appUsageMillis = mutableMapOf<String, Long>()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName ?: continue

            // Skip non-distractive apps
            if (!isDistractiveApp(packageName)) continue

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    activeApps[packageName] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val foregroundStart = activeApps.remove(packageName)
                    if (foregroundStart != null && foregroundStart < event.timeStamp) {
                        val duration = event.timeStamp - foregroundStart
                        if (duration < 4 * 60 * 60 * 1000) { // Less than 4 hours
                            appUsageMillis[packageName] = (appUsageMillis[packageName] ?: 0L) + duration
                        }
                    }
                }
            }
        }

        // Add currently active apps (for today only)
        if (daysAgo == 0) {
            val now = System.currentTimeMillis()
            for ((packageName, foregroundStart) in activeApps) {
                val duration = now - foregroundStart
                if (duration > 0 && duration < 4 * 60 * 60 * 1000) {
                    appUsageMillis[packageName] = (appUsageMillis[packageName] ?: 0L) + duration
                }
            }
        }

        return appUsageMillis.values.sum()
    }

    /**
     * Check if an app is distractive (social media, entertainment, games)
     */
    private fun isDistractiveApp(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()

        // Skip system apps
        val systemApps = listOf(
            "com.android.settings", "com.android.systemui", "com.android.launcher",
            "com.google.android.gms", "com.focusblock.app", "com.android.vending"
        )
        if (systemApps.any { pkgLower.startsWith(it) }) return false

        // Social media keywords
        val socialKeywords = listOf(
            "instagram", "facebook", "twitter", "tiktok", "snapchat", "reddit",
            "whatsapp", "telegram", "discord", "messenger", "wechat", "signal"
        )

        // Entertainment keywords
        val entertainmentKeywords = listOf(
            "youtube", "netflix", "twitch", "spotify", "hulu", "disney", "video",
            "stream", "music", "podcast", "player", "media"
        )

        // Gaming keywords
        val gameKeywords = listOf(
            "game", "gaming", "clash", "pubg", "candy", "minecraft", "fortnite",
            "roblox", "mobile.legends", "ludo", "chess"
        )

        // Check if any keyword matches
        return socialKeywords.any { pkgLower.contains(it) } ||
                entertainmentKeywords.any { pkgLower.contains(it) } ||
                gameKeywords.any { pkgLower.contains(it) } ||
                GlobalDailyLimitSettings.DEFAULT_DISTRACTING_APPS.any { pkgLower == it.lowercase() }
    }

    private fun formatMinutes(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    private suspend fun buildBlockNotification(database: FocusBlockDatabase): Pair<String, String>? {
        // Get today's date
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val todayStart = calendar.timeInMillis

        // Get block count for last 7 days
        val weekAgo = todayStart - (7 * 24 * 60 * 60 * 1000L)
        val recentBlockLogs = database.blockLogDao().getMostBlockedApps(weekAgo, 100)
        val totalBlocks = recentBlockLogs.sumOf { it.count }

        // Get today's blocks
        val todayBlocks = database.blockLogDao().getMostBlockedApps(todayStart, 100)
        val todayBlockCount = todayBlocks.sumOf { it.count }

        // Choose notification based on stats
        return when {
            todayBlockCount >= 10 -> {
                Pair(
                    "You're staying focused!",
                    "You resisted $todayBlockCount distractions today. Keep it up!"
                )
            }
            todayBlockCount in 3..9 -> {
                Pair(
                    "Great progress today!",
                    "You blocked $todayBlockCount distracting attempts. Every block counts!"
                )
            }
            todayBlockCount in 1..2 -> {
                Pair(
                    "Smooth sailing today",
                    "Only $todayBlockCount distractions blocked. You're building good habits!"
                )
            }
            else -> {
                if (totalBlocks > 0) {
                    Pair(
                        "Ready for tomorrow?",
                        "Set up your blocks for a focused day ahead."
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun showNotification(title: String, message: String, notificationId: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_daily_limit", true) // Deep link to daily limit settings
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, notificationId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, FocusBlockApp.CHANNEL_MINDFUL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)

        Log.i(TAG, "Sent notification: $title")
    }

    /**
     * Reads today's charging usage from ChargingStateReceiver's SharedPreferences.
     * If the user spent a meaningful amount of time on distracting apps while the phone
     * was plugged in, show an adaptive tip to break the charging-scroll habit.
     */
    private fun analyzeChargingPatternAndNotify(today: String) {
        try {
            val chargingPrefs = applicationContext.getSharedPreferences(
                ChargingStateReceiver.PREF_NAME, Context.MODE_PRIVATE
            )
            val savedDate = chargingPrefs.getString(ChargingStateReceiver.KEY_CHARGING_USAGE_DATE, null)
            if (savedDate != today) return // No charging data for today yet

            val chargingUsageMs = chargingPrefs.getLong(ChargingStateReceiver.KEY_CHARGING_USAGE_TODAY_MS, 0L)
            val chargingMinutes = (chargingUsageMs / 60_000).toInt()

            // Only notify if they used their phone meaningfully while charging (>= 20 min)
            if (chargingMinutes < 20) return

            val title = "Phone Habit While Charging"
            val message = "You spent ${formatMinutes(chargingMinutes)} on distracting apps while your phone was charging today. " +
                    "Try leaving your phone face-down on the charger — or charge it in another room."
            showNotification(title, message, NOTIFICATION_ID + 2)
            Log.i(TAG, "Sent charging pattern insight: $chargingMinutes min while charging")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze charging pattern", e)
        }
    }
}
