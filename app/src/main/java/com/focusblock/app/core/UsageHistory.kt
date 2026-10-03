package com.focusblock.app.core

import android.content.Context
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.ExcludedApp
import com.focusblock.app.database.entity.ExclusionType
import com.focusblock.app.policy.DayUsage
import com.focusblock.app.policy.Insights
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.UsageCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Usage history for the Activity tab. Android keeps detailed usage events for only about 7–10 days,
 * so FocusBlock saves every complete day it sees (one small JSON file per day in app-private
 * storage) and reads three sources, newest first:
 *  1. Android's events, while they still cover the day;
 *  2. days FocusBlock saved earlier;
 *  3. the daily per-app log written by earlier versions of the app (totals only, no hours).
 * Days older than Android's window from before FocusBlock started saving cannot be recovered.
 */
class UsageHistory(
    private val context: Context,
    private val db: FocusBlockDatabase,
    private val usage: UsageRepository,
    private val clock: AppClock,
    private val safety: SafetyApps,
) {
    private val dir = File(context.filesDir, "usage-history")
    private val mutex = Mutex()

    /** Apps the user chose not to count in screen time (e.g. a clock used as a stopwatch). */
    suspend fun userExcluded(): Set<String> = withContext(Dispatchers.IO) {
        db.excludedAppDao().getExcludedPackageNames(ExclusionType.SCREEN_TIME_REPORT).toSet()
    }

    /** Never counted in screen time: FocusBlock, home-screen launchers, System UI and the user's exclusions. */
    suspend fun notCounted(): Set<String> = safety.launchers() + setOf(context.packageName, "com.android.systemui") + userExcluded()

    suspend fun setCounted(pkg: String, label: String, counted: Boolean) = withContext(Dispatchers.IO) {
        if (counted) db.excludedAppDao().deleteByPackage(pkg)
        else db.excludedAppDao().insert(ExcludedApp(pkg, label, ExclusionType.SCREEN_TIME_REPORT))
        // Saved days keep their hourly detail; continuous use and focus are recomputed where events still exist.
        usage.invalidate()
    }

    /** Usage for each of [dates] that has any data. Also saves every complete day still in Android's window. */
    suspend fun days(dates: List<LocalDate>): Map<LocalDate, DayUsage> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val zone = clock.zone()
            val now = clock.now()
            val today = PolicyTime.localDate(now, zone)
            val out = HashMap<LocalDate, DayUsage>()
            val read = usage.insightEvents()
            if (read != null) {
                val (readAt, events) = read
                val first = events.minOfOrNull { it.time }
                if (first != null) {
                    val intervals = UsageCalculator.intervals(events, readAt)
                    val notInSpans = userExcluded()
                    val firstDate = PolicyTime.localDate(first, zone)
                    var d = firstDate
                    while (!d.isAfter(today)) {
                        val dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli()
                        // Complete when Android's events began before the day did.
                        val complete = first < dayStart
                        val wanted = d in dates
                        // Re-saved while recent, or when it was saved by an older way of counting.
                        val save = complete && d.isBefore(today) && (!d.isBefore(today.minusDays(2)) || savedVersion(d) < VERSION)
                        if (wanted || save) {
                            val day = Insights.dayUsage(intervals, events, d, zone, now, notInSpans)
                            if (save) write(day)
                            // A partial first day is only used when nothing better was saved.
                            if (wanted && (complete || d == today || !file(d).exists())) out[d] = day
                        }
                        d = d.plusDays(1)
                    }
                }
            }
            val missing = dates.filter { it !in out && !it.isAfter(today) }
            missing.forEach { d -> read(d)?.let { out[d] = it } }
            val legacyDates = dates.filter { it !in out && !it.isAfter(today) }
            if (legacyDates.isNotEmpty()) {
                runCatching {
                    db.usageStatDao().between(legacyDates.min().toString(), legacyDates.max().toString())
                        .filter { it.usageTimeMillis > 0 }
                        .groupBy { LocalDate.parse(it.date) }
                        .forEach { (d, rows) ->
                            if (d in legacyDates) {
                                out[d] = DayUsage(
                                    date = d, hourly = null,
                                    totals = rows.associate { it.packageName to it.usageTimeMillis },
                                    opens = rows.filter { it.openCount > 0 }.associate { it.packageName to it.openCount },
                                    pickups = null, longestUse = null, longestFocus = null,
                                )
                            }
                        }
                }
            }
            prune(today)
            out
        }
    }

    /** Saves complete days still in Android's window. Run daily, so no day is lost while the app stays closed. */
    suspend fun persistRecent() {
        val today = PolicyTime.localDate(clock.now(), clock.zone())
        days(listOf(today))
    }

    /** The earliest day with any history, from any source. */
    suspend fun earliestDate(): LocalDate? = withContext(Dispatchers.IO) {
        val saved = dir.list()?.mapNotNull { name -> runCatching { LocalDate.parse(name.removeSuffix(".json")) }.getOrNull() }?.minOrNull()
        val legacy = runCatching { db.usageStatDao().firstDate()?.let(LocalDate::parse) }.getOrNull()
        val events = usage.insightEvents()?.second?.minOfOrNull { it.time }?.let { PolicyTime.localDate(it, clock.zone()) }
        listOfNotNull(saved, legacy, events).minOrNull()
    }

    /** Deletes FocusBlock's saved history (Settings › Data › Delete history). */
    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock { dir.deleteRecursively() } }

    // ---- Storage ----------------------------------------------------------------------------------

    private fun file(d: LocalDate) = File(dir, "$d.json")

    private fun write(day: DayUsage) {
        val hourly = JSONObject()
        day.hourly?.forEach { (pkg, hours) -> hourly.put(pkg, JSONArray().apply { hours.forEach { put(it) } }) }
        val json = JSONObject()
            .put("v", VERSION)
            .put("h", hourly)
            .put("o", JSONObject().apply { day.opens.forEach { (k, v) -> put(k, v) } })
        day.pickups?.let { json.put("p", it) }
        day.longestUse?.let { json.put("u", it) }
        day.longestFocus?.let { json.put("f", it) }
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${day.date}.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file(day.date))
        }
    }

    /** Version of the day saved for [d]; 0 when none is saved. */
    private fun savedVersion(d: LocalDate): Int = runCatching {
        val f = file(d)
        if (f.exists()) JSONObject(f.readText()).optInt("v", 1) else 0
    }.getOrDefault(0)

    private fun read(d: LocalDate): DayUsage? = runCatching {
        val f = file(d)
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        val h = json.getJSONObject("h")
        val hourly = h.keys().asSequence().associateWith { pkg ->
            val a = h.getJSONArray(pkg)
            LongArray(24) { i -> if (i < a.length()) a.getLong(i) else 0L }
        }
        val o = json.optJSONObject("o")
        DayUsage(
            date = d,
            hourly = hourly,
            totals = hourly.mapValues { it.value.sum() },
            opens = o?.keys()?.asSequence()?.associateWith { o.getInt(it) }.orEmpty(),
            pickups = if (json.has("p")) json.getInt("p") else null,
            longestUse = if (json.has("u")) json.getLong("u") else null,
            longestFocus = if (json.has("f")) json.getLong("f") else null,
        )
    }.getOrNull()

    private fun prune(today: LocalDate) {
        val oldest = today.minusDays(KEEP_DAYS)
        dir.listFiles()?.forEach { f ->
            val d = runCatching { LocalDate.parse(f.name.removeSuffix(".json").removeSuffix(".tmp")) }.getOrNull()
            if (d == null || d.isBefore(oldest) || f.name.endsWith(".tmp")) f.delete()
        }
    }

    companion object {
        /** About a year of history is kept. */
        const val KEEP_DAYS = 400L

        /**
         * How saved days were counted. 2: moving between screens of one app no longer stops its
         * time; days saved as 1 are recounted while Android still has their events.
         */
        const val VERSION = 2
    }
}
