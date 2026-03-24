package com.focusblock.app.receiver

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Tracks when the device is plugged in / unplugged and records how much screen time
 * the user accumulates while charging.
 *
 * Data is stored in SharedPreferences as rolling daily counters:
 *   - charging_session_start_ms  : timestamp when charger was connected (0 = not charging)
 *   - charging_usage_today_ms    : total distractive app usage accumulated while charging today
 *   - charging_usage_date        : date string for the above counter (yyyy-MM-dd)
 *
 * DailyInsightsWorker reads charging_usage_today_ms to produce the adaptive insight
 * "You spend X min on your phone while it charges – try leaving it face-down."
 */
class ChargingStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChargingStateReceiver"
        const val PREF_NAME = "charging_pattern_prefs"
        const val KEY_CHARGING_SESSION_START = "charging_session_start_ms"
        const val KEY_CHARGING_USAGE_TODAY_MS = "charging_usage_today_ms"
        const val KEY_CHARGING_USAGE_DATE = "charging_usage_date"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> onChargerConnected(context)
            Intent.ACTION_POWER_DISCONNECTED -> onChargerDisconnected(context)
        }
    }

    private fun onChargerConnected(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_CHARGING_SESSION_START, now).apply()
        Log.d(TAG, "Charger connected at $now")
    }

    private fun onChargerDisconnected(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val sessionStart = prefs.getLong(KEY_CHARGING_SESSION_START, 0L)
        if (sessionStart == 0L) return

        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_CHARGING_SESSION_START, 0L).apply()

        // Calculate distractive app usage during this charging session
        val usageWhileCharging = getDistractiveUsageBetween(context, sessionStart, now)
        if (usageWhileCharging <= 0) return

        // Accumulate into today's counter (reset if date changed)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val savedDate = prefs.getString(KEY_CHARGING_USAGE_DATE, null)
        val existing = if (savedDate == today) prefs.getLong(KEY_CHARGING_USAGE_TODAY_MS, 0L) else 0L

        prefs.edit()
            .putString(KEY_CHARGING_USAGE_DATE, today)
            .putLong(KEY_CHARGING_USAGE_TODAY_MS, existing + usageWhileCharging)
            .apply()

        Log.d(TAG, "Charging session ended. Usage while charging: ${usageWhileCharging / 60_000} min (today total: ${(existing + usageWhileCharging) / 60_000} min)")
    }

    /**
     * Returns total distractive app usage in milliseconds between [startMs] and [endMs].
     */
    private fun getDistractiveUsageBetween(context: Context, startMs: Long, endMs: Long): Long {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return 0L

            val events = usm.queryEvents(startMs, endMs)
            val event = UsageEvents.Event()
            val activeApps = mutableMapOf<String, Long>()
            var totalMs = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (!isDistractiveApp(pkg)) continue

                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> activeApps[pkg] = event.timeStamp

                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val start = activeApps.remove(pkg)
                        if (start != null) {
                            val duration = event.timeStamp - start
                            if (duration in 1..(4 * 3600 * 1000)) totalMs += duration
                        }
                    }
                }
            }
            // Count still-active apps
            for ((_, fgStart) in activeApps) {
                val duration = endMs - fgStart
                if (duration in 1..(4 * 3600 * 1000)) totalMs += duration
            }
            totalMs
        } catch (e: Exception) {
            Log.e(TAG, "Error reading usage during charging", e)
            0L
        }
    }

    private fun isDistractiveApp(packageName: String): Boolean {
        val p = packageName.lowercase()
        val skip = listOf(
            "com.android.settings", "com.android.systemui", "com.android.launcher",
            "com.google.android.gms", "com.focusblock.app", "com.android.vending"
        )
        if (skip.any { p.startsWith(it) }) return false

        val keywords = listOf(
            "instagram", "facebook", "twitter", "tiktok", "snapchat", "reddit",
            "whatsapp", "telegram", "discord", "messenger", "wechat", "signal",
            "youtube", "netflix", "twitch", "spotify", "hulu", "disney", "video",
            "stream", "music", "podcast", "player", "media",
            "game", "gaming", "clash", "pubg", "candy", "minecraft", "fortnite",
            "roblox", "mobile.legends", "ludo", "chess"
        )
        return keywords.any { p.contains(it) }
    }
}
