package com.focusblock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FocusBlockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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

        notificationManager.createNotificationChannels(
            listOf(blockingChannel, alertsChannel, pomodoroChannel)
        )
    }

    companion object {
        const val CHANNEL_BLOCKING = "blocking_service"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_POMODORO = "pomodoro"
    }
}
