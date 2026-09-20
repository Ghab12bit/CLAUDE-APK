package com.focusblock.app.blocking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.RuleKind
import com.focusblock.app.receiver.RuleAlarmReceiver
import java.util.Calendar

/**
 * Schedules the moments at which blocking state changes.
 *
 * Previously, a Quick Block's expiry was enforced by a Handler posting every
 * second inside the accessibility service. If that service was killed -- by
 * the OEM battery manager, a crash, or the user toggling the permission -- the
 * session stayed `isActive` in the database forever and the apps stayed
 * blocked with no way to clear them. Conversely nothing re-evaluated on reboot.
 *
 * Expiry is now a property of the data (see [BlockRule.manualIsRunning]), and
 * these alarms exist only to WAKE the app at the right moment so the
 * notification, the widget and the home screen are correct. Missing an alarm
 * can no longer leave an app wrongly blocked; it can only delay a UI refresh.
 */
object RuleAlarmScheduler {

    private const val TAG = "RuleAlarmScheduler"
    private const val REQUEST_BASE = 20_000

    /**
     * Re-arm every boundary: each rule's next start and next end.
     * Safe to call repeatedly; alarms are replaced, never duplicated.
     */
    fun rescheduleAll(context: Context, rules: List<BlockRule>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()

        for (rule in rules) {
            if (!rule.isEnabled) {
                cancel(context, alarmManager, rule.id)
                continue
            }

            val next = nextBoundary(rule, now)
            if (next == null) {
                cancel(context, alarmManager, rule.id)
                continue
            }
            schedule(context, alarmManager, rule.id, next)
        }
    }

    /**
     * The next moment this rule's state changes.
     *
     * For a manual rule that is its end time. For a scheduled rule it is
     * whichever comes first: the start of its next window, or the end of the
     * window it is currently in.
     */
    fun nextBoundary(rule: BlockRule, now: Long): Long? {
        if (rule.kind == RuleKind.MANUAL) {
            val until = rule.activeUntil ?: return null
            return if (until > now) until else null
        }

        if (!rule.hasTimeCondition) {
            // A pure usage budget resets on a bucket boundary; waking at the
            // next midnight is enough to refresh the day's state.
            if (!rule.hasUsageCondition) return null
            return nextMidnight(now)
        }

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val active = rule.timeConditionMatches(cal)
        return if (active) rule.endsAt(now) else nextStart(rule, now)
    }

    /** The next time this rule's window opens, searching up to eight days out. */
    private fun nextStart(rule: BlockRule, now: Long): Long? {
        val days = rule.dayList()
        if (days.isEmpty()) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        for (offset in 0..8) {
            val probe = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val day = BlockRule.isoDayOfWeek(probe)
            if (day !in days) continue

            probe.set(Calendar.HOUR_OF_DAY, rule.startMinute / 60)
            probe.set(Calendar.MINUTE, rule.startMinute % 60)
            if (probe.timeInMillis > now) return probe.timeInMillis
        }
        return null
    }

    private fun nextMidnight(now: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MILLISECOND, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.HOUR_OF_DAY, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    private fun schedule(context: Context, am: AlarmManager, ruleId: Long, triggerAt: Long) {
        val pending = pendingIntent(context, ruleId)
        try {
            // Exact alarms keep a block from lingering past its end time or
            // starting late. Fall back gracefully when the OS withholds the
            // permission rather than crashing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pending)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.d(TAG, "Rule $ruleId next boundary at $triggerAt")
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied for rule $ruleId; using inexact window", e)
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pending)
        }
    }

    private fun cancel(context: Context, am: AlarmManager, ruleId: Long) {
        am.cancel(pendingIntent(context, ruleId))
    }

    private fun pendingIntent(context: Context, ruleId: Long): PendingIntent {
        val intent = Intent(context, RuleAlarmReceiver::class.java).apply {
            action = RuleAlarmReceiver.ACTION_RULE_BOUNDARY
            putExtra(RuleAlarmReceiver.EXTRA_RULE_ID, ruleId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + ruleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
