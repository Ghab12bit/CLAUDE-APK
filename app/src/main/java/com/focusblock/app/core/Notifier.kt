package com.focusblock.app.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.BlockSessionEntity
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.policy.SessionInput
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import com.focusblock.app.ui.MainActivity

/**
 * All notifications in one place (spec 4.9 "one screen controlling all notification types").
 * Each type has its own channel and an in-app switch stored in settings.
 */
class Notifier(private val context: Context, private val db: FocusBlockDatabase, private val clock: AppClock, private val apps: InstalledApps) {
    private val manager = NotificationManagerCompat.from(context)

    enum class Kind(val pref: String, val channel: String, val defaultOn: Boolean) {
        BLOCK_STATUS(PrefKeys.NOTIFY_BLOCK_STATUS, CHANNEL_STATUS, true),
        RULES(PrefKeys.NOTIFY_RULES, CHANNEL_RULES, false),
        REMINDERS(PrefKeys.NOTIFY_USAGE_REMINDERS, CHANNEL_REMINDERS, true),
        SUMMARY(PrefKeys.NOTIFY_DAILY_SUMMARY, CHANNEL_SUMMARY, false),
        PROBLEMS(PrefKeys.NOTIFY_PROBLEMS, CHANNEL_PROBLEMS, true),
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_RULES, context.getString(R.string.channel_rules), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_SUMMARY, context.getString(R.string.channel_summary), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_PROBLEMS, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_BACKUP, context.getString(R.string.channel_backup), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        nm.createNotificationChannels(channels)
    }

    suspend fun enabled(kind: Kind): Boolean =
        db.settingsDao().getValue(kind.pref)?.toBooleanStrictOrNull() ?: kind.defaultOn

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, notification: android.app.Notification) {
        if (!canPost()) return
        runCatching { manager.notify(id, notification) }
    }

    fun cancel(id: Int) = manager.cancel(id)

    private fun openApp(extra: String? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { extra?.let { putExtra(MainActivity.EXTRA_OPEN, it) } }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(action: String, sessionId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_SESSION, sessionId)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Ongoing "Blocking until 10:26 PM" with actions that respect Strict Lock. */
    suspend fun showActive(session: BlockSessionEntity?, input: SessionInput?) {
        if (session == null || input == null || !enabled(Kind.BLOCK_STATUS)) { cancel(ID_ACTIVE); return }
        val now = clock.now()
        val state = SessionClock.state(input, now)
        if (state.ended) { cancel(ID_ACTIVE); return }
        val title = when {
            input.type == SessionType.INTERVALS && state.phase == SessionClock.Phase.BREAK ->
                context.getString(R.string.notif_break_until, Fmt.time(context, state.phaseEndsAt ?: now))
            input.type == SessionType.INTERVALS ->
                context.getString(R.string.notif_focus_until, state.round, state.totalRounds, Fmt.time(context, state.phaseEndsAt ?: now))
            input.plannedEndAt != null -> context.getString(R.string.notif_active_until, Fmt.time(context, input.plannedEndAt))
            else -> context.getString(R.string.notif_active_open)
        }
        val strength = context.getString(if (input.strength == Strength.STRICT) R.string.strength_strict else R.string.strength_normal)
        val text = context.getString(R.string.summary_dot,
            context.resources.getQuantityString(R.plurals.apps_blocked_count, input.packages.size, input.packages.size), strength)
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(input.intention?.let { context.getString(R.string.intention_quoted, it) } ?: text)
            .setSubText(if (input.intention != null) text else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(requestCode = 1))
        val end = state.phaseEndsAt
        if (end != null) {
            // The system renders the countdown; nothing is written every second.
            builder.setWhen(end).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
        }
        if (input.strength == Strength.NORMAL) {
            builder.addAction(0, context.getString(R.string.action_end_block), action(NotificationActionReceiver.ACTION_END, session.id, 10))
        }
        if (input.plannedEndAt != null) {
            builder.addAction(0, context.getString(R.string.action_add_15), action(NotificationActionReceiver.ACTION_EXTEND, session.id, 11))
        }
        post(ID_ACTIVE, builder.build())
    }

    /** "Did you finish?" when a block ends while the app is closed (spec 4.4). */
    suspend fun showEnded(session: BlockSessionEntity) {
        cancel(ID_ACTIVE)
        if (!enabled(Kind.BLOCK_STATUS)) return
        val endedAt = session.endedAt ?: clock.now()
        val text = session.intention?.let { context.getString(R.string.intention_quoted, it) }
            ?: context.getString(R.string.notif_end_text, Fmt.time(context, endedAt))
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.end_sheet_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(MainActivity.OPEN_END_SHEET, 2))
            .setDeleteIntent(action(NotificationActionReceiver.ACTION_DISMISSED, session.id, 20))
            .addAction(0, context.getString(R.string.end_sheet_finished), action(NotificationActionReceiver.ACTION_FINISHED, session.id, 21))
            .addAction(0, context.getString(R.string.end_sheet_not_yet), action(NotificationActionReceiver.ACTION_NOT_YET, session.id, 22))
            .addAction(0, context.getString(R.string.end_sheet_add_15), action(NotificationActionReceiver.ACTION_ADD_15, session.id, 23))
            .build()
        post(ID_ENDED, n)
    }

    fun cancelEnded() = cancel(ID_ENDED)

    suspend fun showRuleStarted(name: String, until: Long?) {
        if (!enabled(Kind.RULES)) return
        val title = if (until != null) context.getString(R.string.notif_rule_on, name, Fmt.time(context, until))
        else context.getString(R.string.status_rule_blocking_open, name)
        post(ID_RULE, NotificationCompat.Builder(context, CHANNEL_RULES)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setAutoCancel(true)
            .setTimeoutAfter(((until ?: (clock.now() + 3_600_000)) - clock.now()).coerceAtLeast(60_000))
            .setContentIntent(openApp(requestCode = 3)).build())
    }

    suspend fun showUsageReminder(pkg: String, minutes: Int) {
        if (!enabled(Kind.REMINDERS)) return
        post(ID_REMINDER, NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_reminder, apps.label(pkg), Fmt.minutes(context, minutes)))
            .setAutoCancel(true).setTimeoutAfter(15 * 60_000L)
            .setContentIntent(openApp(requestCode = 4)).build())
    }

    suspend fun showSummary(title: String, text: String) {
        if (!enabled(Kind.SUMMARY)) return
        post(ID_SUMMARY, NotificationCompat.Builder(context, CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp(MainActivity.OPEN_ACTIVITY, 5)).build())
    }

    /** "Blocking stopped working: Accessibility is off" (spec 4.11, 9.1). */
    suspend fun showProblem(requirement: Requirement) {
        if (!enabled(Kind.PROBLEMS)) return
        val cause = context.getString(if (requirement == Requirement.ACCESSIBILITY) R.string.cause_accessibility_off else R.string.cause_usage_off)
        post(ID_PROBLEM, NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_problem_title, cause))
            .setContentText(context.getString(R.string.notif_problem_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(MainActivity.OPEN_HEALTH, 6)).build())
    }

    fun cancelProblem() = cancel(ID_PROBLEM)

    fun backupNotification(): android.app.Notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notif_backup_title))
        .setContentText(context.getString(R.string.notif_backup_text))
        .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_backup_text)))
        .setOngoing(true).setSilent(true)
        .setContentIntent(openApp(MainActivity.OPEN_HEALTH, 7))
        .build()

    companion object {
        const val CHANNEL_STATUS = "fb_status"
        const val CHANNEL_EVENTS = "fb_events"
        const val CHANNEL_RULES = "fb_rules"
        const val CHANNEL_REMINDERS = "fb_reminders"
        const val CHANNEL_SUMMARY = "fb_summary"
        const val CHANNEL_PROBLEMS = "fb_problems"
        const val CHANNEL_BACKUP = "fb_backup"

        const val ID_ACTIVE = 4101
        const val ID_ENDED = 4102
        const val ID_RULE = 4103
        const val ID_REMINDER = 4104
        const val ID_SUMMARY = 4105
        const val ID_PROBLEM = 4106
        const val ID_BACKUP = 4107
    }
}
