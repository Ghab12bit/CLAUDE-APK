package com.focusblock.app.core

import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.SmartApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apps worth blocking, from the last 7 days of use (today included) and blocked attempts. Only
 * apps with a launcher icon that are not essential, safety or uncounted apps are suggested. Cached
 * for two minutes, so it changes as your usage does.
 */
class SmartAppsRepository(
    private val history: UsageHistory,
    private val categories: AppCategories,
    private val policy: PolicyRepository,
    private val apps: InstalledApps,
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
) {
    @Volatile private var cache: Pair<Long, List<SmartApps.Suggestion>>? = null

    suspend fun suggestions(limit: Int = 8): List<SmartApps.Suggestion> = withContext(Dispatchers.IO) {
        val now = clock.now()
        cache?.let { (at, list) -> if (now - at < CACHE_MS) return@withContext list.take(limit) }
        categories.load()
        val zone = clock.zone()
        val today = PolicyTime.localDate(now, zone)
        val dates = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val days = history.days(dates).values.toList()
        val skip = policy.exempt() + history.notCounted()
        val launchable = apps.all().map { it.packageName }.toSet()
        val since = dates.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val attempts = db.attemptDao().since(since).groupingBy { it.packageName }.eachCount()
        val list = SmartApps.rank(days, categories::categoryOf, { it in launchable && it !in skip }, attempts, limit = 8)
        cache = now to list
        list.take(limit)
    }

    fun invalidate() { cache = null }

    companion object {
        const val DAYS = 7
        private const val CACHE_MS = 2 * 60_000L
    }
}
