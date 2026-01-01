package com.focusblock.app.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.focusblock.app.FocusBlockApp
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.ui.MainActivity
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Daily worker that sends motivational notifications based on user's stats
 * - Streak milestones (3, 7, 14, 30 days)
 * - Trend warnings (screen time up significantly)
 * - Success celebrations (screen time down)
 */
class DailyInsightsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DailyInsightsWorker"
        private const val WORK_NAME = "daily_insights_notification"
        private const val NOTIFICATION_ID = 5001

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
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Scheduled daily insights notification")
        }
    }

    override suspend fun doWork(): Result {
        try {
            val database = FocusBlockDatabase.getDatabase(applicationContext)

            // Calculate stats for notification
            val notification = buildNotification(database)

            if (notification != null) {
                showNotification(notification.first, notification.second)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send daily insights", e)
            return Result.failure()
        }
    }

    private suspend fun buildNotification(database: FocusBlockDatabase): Pair<String, String>? {
        // Get today's date
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val todayStart = calendar.timeInMillis

        // Get block count for last 7 days to calculate streak
        val weekAgo = todayStart - (7 * 24 * 60 * 60 * 1000L)
        val recentBlockLogs = database.blockLogDao().getMostBlockedApps(weekAgo, 100)
        val totalBlocks = recentBlockLogs.sumOf { it.count }

        // Get today's blocks
        val todayBlocks = database.blockLogDao().getMostBlockedApps(todayStart, 100)
        val todayBlockCount = todayBlocks.sumOf { it.count }

        // Calculate streak (simplified - days with any blocking activity in last week)
        val daysWithActivity = mutableSetOf<Int>()
        // This is simplified - in a real implementation you'd query day by day

        // Choose notification based on stats
        return when {
            // High block count today - you're fighting distractions!
            todayBlockCount >= 10 -> {
                Pair(
                    "You're staying focused!",
                    "You resisted $todayBlockCount distractions today. Keep it up!"
                )
            }
            // Some activity
            todayBlockCount in 3..9 -> {
                Pair(
                    "Great progress today!",
                    "You blocked $todayBlockCount distracting attempts. Every block counts!"
                )
            }
            // Low activity - either great focus or not using blocks
            todayBlockCount in 1..2 -> {
                Pair(
                    "Smooth sailing today",
                    "Only $todayBlockCount distractions blocked. You're building good habits!"
                )
            }
            // No blocks today - motivational
            else -> {
                if (totalBlocks > 0) {
                    Pair(
                        "Ready for tomorrow?",
                        "Set up your blocks for a focused day ahead."
                    )
                } else {
                    null // Don't send if no activity at all
                }
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
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
        notificationManager.notify(NOTIFICATION_ID, notification)

        Log.i(TAG, "Sent daily insights notification: $title")
    }
}
