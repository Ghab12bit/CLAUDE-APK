package com.focusblock.app.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.focusblock.app.FocusBlockApp
import com.focusblock.app.ui.MainActivity
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Peak-Time Reminder Worker
 *
 * Detects continuous active session beyond threshold (30+ minutes)
 * and shows one gentle notification. Does not spam repeat reminders.
 */
class PeakTimeReminderWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PeakTimeReminder"
        private const val NOTIFICATION_ID = 2001
        private const val PREF_NAME = "peak_time_reminder"
        private const val KEY_LAST_REMINDER_TIME = "last_reminder_time"
        private const val REMINDER_COOLDOWN_HOURS = 2 // Don't remind more than once every 2 hours
        private const val CONTINUOUS_SESSION_THRESHOLD_MINUTES = 30

        /**
         * Schedule the peak-time reminder check
         * Runs periodically to check for prolonged screen time
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<PeakTimeReminderWorker>(
                15, TimeUnit.MINUTES // Check every 15 minutes
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        /**
         * Cancel the scheduled reminder
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Quiet hours: no reminders between 10 PM (22:00) and 8 AM (08:00)
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (currentHour >= 22 || currentHour < 8) {
                return@withContext Result.success()
            }

            val prefs = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastReminderTime = prefs.getLong(KEY_LAST_REMINDER_TIME, 0L)
            val now = System.currentTimeMillis()

            // Check cooldown - don't spam reminders
            val cooldownMs = REMINDER_COOLDOWN_HOURS * 60 * 60 * 1000L
            if (now - lastReminderTime < cooldownMs) {
                return@withContext Result.success()
            }

            // Calculate today's midnight to detect stale sessions from previous days
            val todayMidnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Check if there's an active blocking session (Quick Block or Focus Cycle)
            val database = FocusBlockDatabase.getDatabase(applicationContext)
            val activeSession = database.quickBlockSessionDao().getActiveSessionSync()
            val activeFocusCycle = database.focusCycleDao().getActiveFocusCycleSync()

            // Check for continuous screen time - only count sessions that started today
            val sessionStart: Long? = when {
                activeSession != null && activeSession.startTime >= todayMidnight ->
                    activeSession.startTime
                activeFocusCycle != null && activeFocusCycle.isEnabled -> {
                    // For Focus Cycle, use cycleStartTime if it's from today; ignore stale sessions
                    val cycleStart = activeFocusCycle.cycleStartTime ?: activeFocusCycle.createdAt
                    if (cycleStart >= todayMidnight) cycleStart else null
                }
                else -> null
            }

            if (sessionStart != null && sessionStart > 0) {
                val sessionDuration = now - sessionStart
                val sessionMinutes = sessionDuration / (60 * 1000)

                // Only remind if session is longer than threshold
                if (sessionMinutes >= CONTINUOUS_SESSION_THRESHOLD_MINUTES) {
                    // Show gentle reminder
                    showReminderNotification()

                    // Record reminder time to prevent spamming
                    prefs.edit().putLong(KEY_LAST_REMINDER_TIME, now).apply()
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun showReminderNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create intent to open the app
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent, pendingIntentFlags
        )

        // Gentle motivational messages
        val messages = listOf(
            "You've been active for a while. Want to take a short break?",
            "Time for a mindful moment? A quick break can refresh your focus.",
            "Your screen time is adding up. Consider stretching or looking away."
        )
        val message = messages.random()

        val notification = NotificationCompat.Builder(applicationContext, FocusBlockApp.CHANNEL_MINDFUL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Mindful Break")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Take 5-min break",
                pendingIntent
            )
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Continue",
                pendingIntent
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
