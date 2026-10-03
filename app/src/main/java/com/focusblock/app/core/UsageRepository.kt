package com.focusblock.app.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.UsageCalculator
import com.focusblock.app.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Bounded, cached UsageStats queries (spec 11.4). Returns null whenever Usage Access is missing or
 * the system returns nothing, so callers show "data unavailable" instead of zeros (spec 9.5).
 */
class UsageRepository(private val context: Context, private val clock: AppClock) {
    private val mutex = Mutex()
    private var todayCache: Triple<Long, LocalDate, Map<String, Long>>? = null
    private var eventsCache: Triple<Long, Long, List<UsageCalculator.Event>>? = null

    fun hasAccess(): Boolean = PermissionUtils.hasUsageStatsPermission(context)

    /** Raw foreground events between [from] and [to], or null without access. */
    suspend fun events(from: Long, to: Long): List<UsageCalculator.Event>? = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@withContext null
        val result = runCatching { manager.queryEvents(from, to) }.getOrNull() ?: return@withContext null
        val out = ArrayList<UsageCalculator.Event>()
        val e = UsageEvents.Event()
        while (result.hasNextEvent()) {
            result.getNextEvent(e)
            val type = when (e.eventType) {
                RESUMED -> UsageCalculator.Type.RESUMED
                PAUSED -> UsageCalculator.Type.PAUSED
                STOPPED -> UsageCalculator.Type.STOPPED
                SCREEN_OFF, KEYGUARD_SHOWN -> UsageCalculator.Type.SCREEN_OFF
                SHUTDOWN -> UsageCalculator.Type.SHUTDOWN
                SCREEN_ON -> UsageCalculator.Type.SCREEN_ON
                KEYGUARD_HIDDEN -> UsageCalculator.Type.UNLOCK
                else -> null
            } ?: continue
            out += UsageCalculator.Event(e.timeStamp, e.packageName, type, e.className)
        }
        out
    }

    /** Events for the last [days] days plus today, cached for five minutes. */
    private suspend fun recentEvents(days: Int, maxAgeMs: Long): Pair<Long, List<UsageCalculator.Event>>? {
        val now = clock.now()
        val from = PolicyTime.startOfDay(now, clock.zone()) - days * DAY
        eventsCache?.let { (at, cachedFrom, events) -> if (now - at < maxAgeMs && cachedFrom <= from) return now to events }
        // Start a day earlier so an app that was already open at the window start is counted.
        val events = events(from - DAY, now) ?: return null
        eventsCache = Triple(now, from, events)
        return now to events
    }

    /** Foreground time per app since local midnight. Cached for 30 s unless [maxAgeMs] is smaller. */
    suspend fun todayTotals(maxAgeMs: Long = 30_000): Map<String, Long>? { mutex.withLock {
        val now = clock.now()
        val zone = clock.zone()
        val today = PolicyTime.localDate(now, zone)
        todayCache?.let { (at, day, totals) -> if (day == today && now - at < maxAgeMs) return totals }
        val start = PolicyTime.startOfDay(now, zone)
        val events = events(start - 2 * HOUR, now) ?: return null
        val totals = UsageCalculator.totals(UsageCalculator.intervals(events, now), start, now)
        todayCache = Triple(now, today, totals)
        return totals
    } }

    /** Foreground time per app in an arbitrary window (imported hourly rules). */
    suspend fun windowTotals(from: Long, to: Long): Map<String, Long>? {
        val events = events(from - 2 * HOUR, to) ?: return null
        return UsageCalculator.totals(UsageCalculator.intervals(events, to), from, to)
    }

    suspend fun launches(from: Long, to: Long): Map<String, Int>? {
        val events = events(from - HOUR, to) ?: return null
        return UsageCalculator.launches(events, from, to)
    }

    /**
     * Per-day, per-app totals for the [days] days before today and today itself. With
     * [cutoffMinute], every day only counts usage before that local time.
     */
    suspend fun dailyTotals(days: Int, cutoffMinute: Int? = null): Map<LocalDate, Map<String, Long>>? { mutex.withLock {
        val (now, events) = recentEvents(days, 5 * 60_000) ?: return null
        val zone = clock.zone()
        val today = PolicyTime.localDate(now, zone)
        val dates = (days downTo 0).map { today.minusDays(it.toLong()) }
        return UsageCalculator.dailyTotals(UsageCalculator.intervals(events, now), dates, zone, cutoffMinute)
    } }

    /**
     * Raw events for the Activity tab: the last [days] days plus today (cached for five minutes),
     * and the time they were read. Null without Usage access.
     */
    suspend fun insightEvents(days: Int = INSIGHT_DAYS): Pair<Long, List<UsageCalculator.Event>>? { mutex.withLock {
        return recentEvents(days, 5 * 60_000)
    } }

    /** Earliest day with any usage event in the last [days] days (for recommendation eligibility). */
    suspend fun firstDataDay(days: Int = 14): LocalDate? { mutex.withLock {
        val (_, events) = recentEvents(days, 5 * 60_000) ?: return null
        return events.minOfOrNull { it.time }?.let { PolicyTime.localDate(it, clock.zone()) }
    } }

    fun invalidate() { todayCache = null; eventsCache = null }

    companion object {
        const val INSIGHT_DAYS = 27
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        // UsageEvents.Event constants, inlined so they read on API 26 (they are compile-time ints).
        private const val RESUMED = 1 // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
        private const val PAUSED = 2 // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
        private const val SCREEN_OFF = 16 // SCREEN_NON_INTERACTIVE
        private const val KEYGUARD_SHOWN = 17
        private const val SCREEN_ON = 15 // SCREEN_INTERACTIVE
        private const val KEYGUARD_HIDDEN = 18
        private const val STOPPED = 23 // ACTIVITY_STOPPED
        private const val SHUTDOWN = 26 // DEVICE_SHUTDOWN
    }
}
