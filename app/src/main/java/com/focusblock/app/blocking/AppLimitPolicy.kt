package com.focusblock.app.blocking

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.utils.PermissionUtils
import java.util.Calendar

object AppLimitPolicy {
    suspend fun reached(context: Context, db: FocusBlockDatabase, pkg: String): Boolean {
        val limit = db.appTimeLimitDao().getTimeLimit(pkg) ?: return false
        if (!limit.isEnabled || !PermissionUtils.hasUsageStatsPermission(context)) return false
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val usage = UsageWindowReader.read(context, start, now) ?: return false
        return (usage[pkg] ?: 0) >= limit.dailyLimitMinutes * 60000L
    }
}

/** Uses event boundaries instead of daily aggregates that can include time outside the requested window. */
object UsageWindowReader {
    fun read(context: Context, start: Long, end: Long): Map<String, Long>? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(start - 86400000L, end) ?: return null
        val totals = mutableMapOf<String, Long>()
        val open = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()
        fun closeAll(at: Long) {
            open.forEach { (p, t) -> totals[p] = (totals[p] ?: 0) + (minOf(end, at) - maxOf(start, t)).coerceAtLeast(0) }
            open.clear()
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> event.packageName?.let { open.putIfAbsent(it, event.timeStamp) }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> event.packageName?.let { p ->
                    open.remove(p)?.let { t -> totals[p] = (totals[p] ?: 0) + (minOf(end, event.timeStamp) - maxOf(start, t)).coerceAtLeast(0) }
                }
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.SCREEN_NON_INTERACTIVE -> closeAll(event.timeStamp)
            }
        }
        closeAll(end)
        return totals.filterValues { it > 0 }
    }
}
