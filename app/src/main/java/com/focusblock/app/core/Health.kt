package com.focusblock.app.core

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.focusblock.app.R
import com.focusblock.app.utils.PermissionUtils

/** Liveness of the accessibility service (spec 9.1: detect "granted but not running"). */
object ServiceHeartbeat {
    @Volatile var connected: Boolean = false
    /** The service instance that reported [connected]. A re-bind can destroy an old instance after the new one connects. */
    @Volatile var owner: Any? = null
    @Volatile var connectedAt: Long = 0L
    @Volatile var lastEventAt: Long = 0L
    /** When the block screen last came to the front (set by InterventionActivity). */
    @Volatile var interventionShownAt: Long = 0L
    /** Set by the running service; asks it to re-check the app on screen now. */
    @Volatile var recheck: (() -> Unit)? = null
    /** elapsedRealtime when this process started, so a just-started process is not reported as broken. */
    val processStart: Long = SystemClock.elapsedRealtime()
}

/**
 * Blocking works with Usage access + Display over other apps alone ([AppBlockingService] asks the same
 * Enforcer as the accessibility service). Accessibility is an optional "faster blocking" add-on: many
 * banking and payment apps refuse to run while any third-party accessibility service is on, so it must
 * never be required. Listed in the order the Health screen shows them.
 */
enum class Requirement(@StringRes val label: Int, @StringRes val description: Int, val required: Boolean) {
    USAGE(R.string.perm_usage, R.string.health_desc_usage, true),
    OVERLAY(R.string.perm_overlay, R.string.health_desc_overlay, true),
    NOTIFICATIONS(R.string.perm_notifications, R.string.health_desc_notifications, false),
    BATTERY(R.string.perm_battery, R.string.health_desc_battery, false),
    EXACT_ALARMS(R.string.perm_exact_alarms, R.string.health_desc_exact_alarms, false),
    ACCESSIBILITY(R.string.perm_accessibility, R.string.health_desc_accessibility, false),
}

/** Short "X is off" text for a missing requirement ("Blocking stopped working: X is off"). */
val Requirement.cause: Int
    @StringRes get() = when (this) {
        Requirement.ACCESSIBILITY -> R.string.cause_accessibility_off
        Requirement.OVERLAY -> R.string.cause_overlay_off
        else -> R.string.cause_usage_off
    }

enum class HealthState { OK, OFF, NOT_RUNNING }

data class HealthItem(val requirement: Requirement, val state: HealthState)

object PermissionHealth {
    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, ACCESSIBILITY_SERVICE_CLASS)
        val match = enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        return match || PermissionUtils.hasAccessibilityServiceEnabled(context)
    }

    fun accessibilityState(context: Context): HealthState {
        if (!accessibilityEnabled(context)) return HealthState.OFF
        if (ServiceHeartbeat.connected) return HealthState.OK
        // Give Android a few seconds to bind the service after the process starts.
        return if (SystemClock.elapsedRealtime() - ServiceHeartbeat.processStart < 8_000) HealthState.OK else HealthState.NOT_RUNNING
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true)

    fun state(context: Context, r: Requirement): HealthState = when (r) {
        Requirement.ACCESSIBILITY -> accessibilityState(context)
        Requirement.USAGE -> ok(PermissionUtils.hasUsageStatsPermission(context))
        Requirement.NOTIFICATIONS -> ok(NotificationManagerCompat.from(context).areNotificationsEnabled())
        Requirement.BATTERY -> ok((context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName))
        Requirement.EXACT_ALARMS -> ok(canScheduleExact(context))
        Requirement.OVERLAY -> ok(Settings.canDrawOverlays(context))
    }

    fun items(context: Context): List<HealthItem> = Requirement.values()
        .filter { it != Requirement.EXACT_ALARMS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
        .map { HealthItem(it, state(context, it)) }

    /**
     * The required permission that is missing, which disables Start (spec 4.1, 4.10).
     * Blocking is ready when the optional accessibility add-on is running, or when Usage access and
     * (on Android 10+) Display over other apps let [com.focusblock.app.service.AppBlockingService] block.
     */
    fun missingRequired(context: Context): Requirement? = when {
        accessibilityState(context) == HealthState.OK -> null
        !PermissionUtils.hasUsageStatsPermission(context) -> Requirement.USAGE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context) -> Requirement.OVERLAY
        else -> null
    }

    private fun ok(value: Boolean) = if (value) HealthState.OK else HealthState.OFF

    /** Deep link to the exact system screen for [r] (spec 9.1). */
    fun fixIntent(context: Context, r: Requirement): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val intent = when (r) {
            Requirement.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                // Highlights FocusBlock in the list on Pixel and Samsung settings.
                val key = ComponentName(context, ACCESSIBILITY_SERVICE_CLASS).flattenToString()
                putExtra(":settings:fragment_args_key", key)
                putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
            }
            Requirement.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri)
                .takeIf { it.resolveActivity(context.packageManager) != null } ?: Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            Requirement.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Requirement.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                .takeIf { it.resolveActivity(context.packageManager) != null } ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            Requirement.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            Requirement.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context, r: Requirement) {
        val intent = fixIntent(context, r)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    const val ACCESSIBILITY_SERVICE_CLASS = "com.focusblock.app.service.FocusBlockAccessibilityService"
}
