package com.focusblock.app.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.focusblock.app.R
import com.focusblock.app.policy.SessionOutcome
import kotlinx.coroutines.launch

/**
 * One alarm for the next moment any policy changes (session end, interval phase, routine or bedtime
 * boundary, override expiry, midnight). Re-registering replaces the previous alarm, so there are never
 * duplicates (spec 9.3). Enforcement never depends on the alarm firing on time: the engine decides
 * from timestamps; the alarm only ends blocks in the database, posts notifications and refreshes the
 * widget.
 */
class AlarmScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)

    @Volatile var nextAt: Long? = null
        private set

    fun schedule(at: Long?) {
        val pi = pendingIntent()
        manager?.cancel(pi)
        nextAt = at
        if (at == null || manager == null) return
        try {
            if (PermissionHealth.canScheduleExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, PolicyAlarmReceiver::class.java).setAction(PolicyAlarmReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class PolicyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = AppGraph.get(context)
        graph.scope.launch {
            try { graph.tick("alarm") } finally { pending.finish() }
        }
    }

    companion object { const val ACTION = "com.focusblock.app.POLICY_TICK" }
}

/**
 * Recovery after BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED and TIME_SET (spec 9.3):
 * expires ended blocks (never restores them), keeps valid ones, re-registers the single alarm,
 * recalculates rules from the new local time and refreshes notifications and the widget.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = AppGraph.get(context)
        graph.scope.launch {
            try {
                if (intent.action == Intent.ACTION_TIME_CHANGED) graph.checkClockChange()
                graph.diagnostics.log("SYSTEM_EVENT", intent.action.orEmpty())
                graph.apps.invalidate()
                graph.safety.invalidate()
                graph.policy.invalidate()
                graph.usage.invalidate()
                graph.tick(intent.action ?: "system")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Notification buttons. Every action goes through [SessionManager], so Strict Lock is respected. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = AppGraph.get(context)
        val id = intent.getLongExtra(EXTRA_SESSION, -1)
        graph.scope.launch {
            try {
                when (intent.action) {
                    // Older notifications only: ending now goes through the app's end-early sheet.
                    ACTION_END -> graph.refresh()
                    ACTION_EXTEND -> if (graph.sessions.extend()) toast(context, R.string.notif_extended)
                    ACTION_FINISHED -> { graph.sessions.recordOutcome(id, SessionOutcome.FINISHED); graph.notifier.cancelEnded() }
                    ACTION_NOT_YET -> { graph.sessions.recordOutcome(id, SessionOutcome.NOT_FINISHED); graph.notifier.cancelEnded() }
                    ACTION_ADD_15 -> { graph.sessions.extendFinished(id); graph.notifier.cancelEnded() }
                    ACTION_DISMISSED -> graph.sessions.recordOutcome(id, SessionOutcome.UNANSWERED)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun toast(context: Context, res: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, res, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_SESSION = "session"
        const val ACTION_END = "com.focusblock.app.END"
        const val ACTION_EXTEND = "com.focusblock.app.EXTEND"
        const val ACTION_FINISHED = "com.focusblock.app.FINISHED"
        const val ACTION_NOT_YET = "com.focusblock.app.NOT_YET"
        const val ACTION_ADD_15 = "com.focusblock.app.ADD_15"
        const val ACTION_DISMISSED = "com.focusblock.app.DISMISSED"
    }
}
