package com.focusblock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FocusBlockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        cancelLegacyNotificationWork()
    }

    /**
     * Cancels the background workers that existed only to send unprompted
     * notifications about screen time.
     *
     * Not scheduling them is not enough. Both were enqueued with
     * enqueueUniquePeriodicWork(..., KEEP), so WorkManager has them persisted
     * on any device that ran an earlier build and will keep running them
     * across app updates no matter what this class stops calling. They have to
     * be cancelled by name.
     *
     *  - daily_insights_notification fired a screen-time summary at 9pm every
     *    day, which lands in the middle of the evening work block.
     *  - PeakTimeReminder woke every 15 minutes to look for a reason to send a
     *    high-usage warning.
     *
     * Neither is needed for the Insights screen, which reads UsageStatsManager
     * directly when it is opened. A blocker earns attention by blocking, not by
     * notifying, and the user asked specifically not to be given a motivational
     * dashboard.
     */
    private fun cancelLegacyNotificationWork() {
        runCatching {
            val workManager = WorkManager.getInstance(this)
            workManager.cancelUniqueWork("daily_insights_notification")
            workManager.cancelUniqueWork("PeakTimeReminder")
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Blocking Service Channel
        val blockingChannel = NotificationChannel(
            CHANNEL_BLOCKING,
            getString(R.string.notification_channel_blocking),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_blocking_desc)
            setShowBadge(false)
        }

        // Alerts Channel
        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Important alerts and reminders"
            setShowBadge(true)
        }

        // Pomodoro Channel
        val pomodoroChannel = NotificationChannel(
            CHANNEL_POMODORO,
            "Pomodoro Timer",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Pomodoro timer notifications"
            setShowBadge(true)
        }

        // Mindful Reminder Channel (gentle, non-intrusive)
        val reminderChannel = NotificationChannel(
            CHANNEL_MINDFUL_REMINDER,
            "Mindful Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Gentle reminders for screen time awareness"
            setShowBadge(false)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200) // Short, gentle vibration
        }

        // App Timer Channel - shows remaining time
        val appTimerChannel = NotificationChannel(
            CHANNEL_APP_TIMER,
            "App Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows remaining App Timer time"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(
            listOf(blockingChannel, alertsChannel, pomodoroChannel, reminderChannel, appTimerChannel)
        )
    }

    companion object {
        const val CHANNEL_BLOCKING = "blocking_service"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_POMODORO = "pomodoro"
        const val CHANNEL_MINDFUL_REMINDER = "mindful_reminder"
        const val CHANNEL_APP_TIMER = "app_timer"
    }
}
