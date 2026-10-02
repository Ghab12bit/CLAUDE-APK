package com.focusblock.app.policy

import java.time.LocalDate
import java.time.ZoneId

/** How an app's time is shown on the Activity tab. The user can change any app's category. */
enum class AppCategory { DISTRACTING, NEUTRAL, PRODUCTIVE }

/**
 * Default category for an app the user has not categorised: a short list of well-known apps first,
 * then the category the developer declared in the Play Store listing (ApplicationInfo.category).
 */
object CategoryDefaults {
    // ApplicationInfo.CATEGORY_* values (stable platform constants).
    const val ANDROID_GAME = 0
    const val ANDROID_AUDIO = 1
    const val ANDROID_VIDEO = 2
    const val ANDROID_IMAGE = 3
    const val ANDROID_SOCIAL = 4
    const val ANDROID_NEWS = 5
    const val ANDROID_MAPS = 6
    const val ANDROID_PRODUCTIVITY = 7

    private val distracting = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.snapchat.android", "com.twitter.android",
        "com.reddit.frontpage", "com.pinterest", "com.tumblr", "com.discord", "tv.twitch.android.app",
        "com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube", "com.netflix.mediaclient",
        "in.startv.hotstar", "com.amazon.avod.thirdpartyclient", "com.jio.media.ondemand", "com.mxtech.videoplayer.ad",
        "in.mohalla.sharechat", "in.mohalla.video", "com.eterno.shortvideos", "com.tinder", "com.bumble.app",
        "com.instagram.barcelona", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
    )
    private val distractingPrefixes = listOf("com.facebook.", "com.instagram.", "com.zhiliaoapp.", "com.ss.android.ugc.", "com.king.", "com.supercell.")
    private val productive = setOf(
        "com.google.android.apps.docs", "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides", "com.google.android.keep", "com.google.android.calendar",
        "com.google.android.apps.tasks", "com.google.android.apps.classroom", "com.google.android.apps.meetings",
        "com.microsoft.office.word", "com.microsoft.office.excel", "com.microsoft.office.powerpoint", "com.microsoft.office.officehubrow",
        "com.microsoft.office.outlook", "com.microsoft.teams", "com.microsoft.todos", "com.microsoft.office.onenote",
        "com.notion.id", "com.todoist", "com.ticktick.task", "md.obsidian", "com.evernote", "com.slack", "com.duolingo",
        "org.khanacademy.android", "com.adobe.reader", "com.dropbox.android", "com.android.calendar", "com.samsung.android.calendar",
    )

    fun forPackage(pkg: String, androidCategory: Int?): AppCategory = when {
        pkg in distracting || distractingPrefixes.any { pkg.startsWith(it) } -> AppCategory.DISTRACTING
        pkg in productive -> AppCategory.PRODUCTIVE
        androidCategory == ANDROID_GAME || androidCategory == ANDROID_VIDEO || androidCategory == ANDROID_SOCIAL || androidCategory == ANDROID_NEWS -> AppCategory.DISTRACTING
        androidCategory == ANDROID_PRODUCTIVITY -> AppCategory.PRODUCTIVE
        else -> AppCategory.NEUTRAL
    }
}

/**
 * One day of phone use, kept so Activity can show more than the ~7–10 days Android keeps its
 * detailed usage events. Built from events while they exist ([Insights.dayUsage]) and saved by
 * FocusBlock; days from the previous version's daily log only have [totals].
 */
data class DayUsage(
    val date: LocalDate,
    /** Per app: foreground ms in each local hour 0–23. Null when only daily totals are known. */
    val hourly: Map<String, LongArray>?,
    /** Per app: foreground ms for the whole day. */
    val totals: Map<String, Long>,
    val opens: Map<String, Int>,
    val pickups: Int?,
    val longestUse: Long?,
    val longestFocus: Long?,
) {
    fun total(counted: (String) -> Boolean): Long = totals.entries.sumOf { if (counted(it.key)) it.value else 0L }
}

/**
 * Activity-tab metrics computed from foreground intervals and screen events (formulas in
 * docs/metrics.md). Pure, so every number is unit-tested.
 */
object Insights {
    /** Awake hours used for "Balance" and "Longest focus": 07:00 to 23:00 local time (16 h). */
    const val AWAKE_START_MINUTE = 7 * 60
    const val AWAKE_END_MINUTE = 23 * 60
    const val AWAKE_MS = (AWAKE_END_MINUTE - AWAKE_START_MINUTE) * 60_000L

    /** Gaps up to one minute (switching apps, the launcher) do not break a stretch of phone use. */
    const val SESSION_GAP_MS = 60_000L

    data class Span(val start: Long, val end: Long) {
        val length: Long get() = end - start
    }

    /** Time per category in one bucket (an hour or a day), indexed by [AppCategory.ordinal]. */
    class Bucket(val start: Long, val end: Long) {
        val byCategory = LongArray(AppCategory.values().size)
        val total: Long get() = byCategory.sum()
    }

    /**
     * Splits counted foreground time into [boundaries] (n+1 increasing instants give n buckets).
     * Intervals crossing a boundary are split.
     */
    fun buckets(
        intervals: List<UsageCalculator.Interval>,
        boundaries: List<Long>,
        categoryOf: (String) -> AppCategory,
        counted: (String) -> Boolean,
    ): List<Bucket> {
        val out = boundaries.zipWithNext { a, b -> Bucket(a, b) }
        if (out.isEmpty()) return out
        for (i in intervals) {
            if (!counted(i.pkg)) continue
            val c = categoryOf(i.pkg).ordinal
            for (b in out) {
                val s = maxOf(i.start, b.start)
                val e = minOf(i.end, b.end)
                if (e > s) b.byCategory[c] += e - s
            }
        }
        return out
    }

    /** Local hour starts of [date] plus the next midnight (23 or 25 buckets on DST days are folded to 24 by hour of day). */
    fun hourBoundaries(date: LocalDate, zone: ZoneId): List<Long> {
        val start = date.atStartOfDay(zone)
        val next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<Long>()
        var t = start
        while (t.toInstant().toEpochMilli() < next) {
            out += t.toInstant().toEpochMilli()
            t = t.plusHours(1)
        }
        out += next
        return out
    }

    /** Day starts for each date in [dates] (consecutive, ascending) plus the day after the last. */
    fun dayBoundaries(dates: List<LocalDate>, zone: ZoneId): List<Long> =
        if (dates.isEmpty()) emptyList()
        else dates.map { it.atStartOfDay(zone).toInstant().toEpochMilli() } + dates.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Time per local hour of day (0–23) within [from, to), summed over days. Used for "Peak time". */
    fun hourOfDayTotals(intervals: List<UsageCalculator.Interval>, from: Long, to: Long, zone: ZoneId, counted: (String) -> Boolean): LongArray {
        val out = LongArray(24)
        for (i in intervals) {
            if (!counted(i.pkg)) continue
            var s = maxOf(i.start, from)
            val e = minOf(i.end, to)
            while (s < e) {
                val z = java.time.Instant.ofEpochMilli(s).atZone(zone)
                val hourEnd = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val stop = minOf(e, hourEnd)
                out[z.hour] += stop - s
                s = stop
            }
        }
        return out
    }

    /**
     * One day from foreground [intervals] and raw [events]: per-app time per local hour, opens,
     * pickups, continuous use and longest focus. Apps in [notInSpans] (apps the user does not count,
     * e.g. a clock left running) are left out of continuous use and longest focus.
     */
    fun dayUsage(
        intervals: List<UsageCalculator.Interval>,
        events: List<UsageCalculator.Event>,
        date: LocalDate,
        zone: ZoneId,
        now: Long,
        notInSpans: Set<String> = emptySet(),
    ): DayUsage {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        val hourly = HashMap<String, LongArray>()
        for (i in intervals) {
            var s = maxOf(i.start, start)
            val e = minOf(i.end, end)
            while (s < e) {
                val z = java.time.Instant.ofEpochMilli(s).atZone(zone)
                val hourEnd = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val stop = minOf(e, hourEnd)
                hourly.getOrPut(i.pkg) { LongArray(24) }[z.hour] += stop - s
                s = stop
            }
        }
        val spans = spans(intervals.filter { it.pkg !in notInSpans })
        return DayUsage(
            date = date,
            hourly = hourly,
            totals = hourly.mapValues { it.value.sum() },
            opens = UsageCalculator.launches(events, start, end),
            pickups = pickups(events, start, end),
            longestUse = longestUse(spans, start, end),
            longestFocus = longestFocus(spans, date, zone, now),
        )
    }

    /** Counted time per local hour of [day], stacked by category; empty when the day has no hourly detail. */
    fun hourStack(day: DayUsage, categoryOf: (String) -> AppCategory, counted: (String) -> Boolean): List<LongArray> {
        val out = List(24) { LongArray(AppCategory.values().size) }
        day.hourly?.forEach { (pkg, hours) ->
            if (!counted(pkg)) return@forEach
            val c = categoryOf(pkg).ordinal
            hours.forEachIndexed { h, v -> out[h][c] += v }
        }
        return out
    }

    /** Counted time of a whole day, stacked by category. */
    fun dayStack(day: DayUsage, categoryOf: (String) -> AppCategory, counted: (String) -> Boolean): LongArray {
        val out = LongArray(AppCategory.values().size)
        day.totals.forEach { (pkg, v) -> if (counted(pkg)) out[categoryOf(pkg).ordinal] += v }
        return out
    }

    /** Counted time per local hour of day, summed over [days] that have hourly detail. */
    fun hourOfDay(days: List<DayUsage>, counted: (String) -> Boolean): LongArray {
        val out = LongArray(24)
        days.forEach { d -> d.hourly?.forEach { (pkg, hours) -> if (counted(pkg)) hours.forEachIndexed { h, v -> out[h] += v } } }
        return out
    }

    /**
     * Counted time of [day] before [minuteOfDay] (for "usual by this time of day"). Uses whole hours
     * plus the matching share of the current hour; days without hourly detail are scaled by the
     * share of the day that has passed.
     */
    fun totalBefore(day: DayUsage, minuteOfDay: Int, counted: (String) -> Boolean): Long {
        val hours = day.hourly ?: return day.total(counted) * minuteOfDay / 1440
        val h = minuteOfDay / 60
        val part = (minuteOfDay % 60) / 60.0
        var sum = 0L
        hours.forEach { (pkg, v) ->
            if (!counted(pkg)) return@forEach
            for (i in 0 until h.coerceAtMost(24)) sum += v[i]
            if (h < 24) sum += (v[h] * part).toLong()
        }
        return sum
    }

    /** The hour of day with the most use, or null when there was none. Ties go to the earlier hour. */
    fun peakHour(hourTotals: LongArray): Int? {
        var best = -1
        hourTotals.forEachIndexed { h, v -> if (v > 0 && (best < 0 || v > hourTotals[best])) best = h }
        return best.takeIf { it >= 0 }
    }

    /**
     * Stretches of continuous phone use: all foreground intervals (every app, including the
     * launcher) merged when the gap between them is at most [gap].
     */
    fun spans(intervals: List<UsageCalculator.Interval>, gap: Long = SESSION_GAP_MS): List<Span> {
        val sorted = intervals.filter { it.end > it.start }.sortedBy { it.start }
        val out = ArrayList<Span>()
        var cur: Span? = null
        for (i in sorted) {
            val c = cur
            cur = if (c == null) Span(i.start, i.end)
            else if (i.start - c.end <= gap) Span(c.start, maxOf(c.end, i.end))
            else { out += c; Span(i.start, i.end) }
        }
        cur?.let { out += it }
        return out
    }

    /** "Continuous use": the longest stretch of use within [from, to) (clipped to the window). */
    fun longestUse(spans: List<Span>, from: Long, to: Long): Long =
        spans.maxOfOrNull { (minOf(it.end, to) - maxOf(it.start, from)).coerceAtLeast(0) } ?: 0L

    /**
     * "Longest focus" for one day: the longest time without using the phone during awake hours
     * (07:00–23:00), between two stretches of use. On today, the time since the last use counts up to
     * [now]. The time before the first use of the day is not counted (you may have been asleep).
     */
    fun longestFocus(spans: List<Span>, date: LocalDate, zone: ZoneId, now: Long): Long {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val awakeStart = dayStart + AWAKE_START_MINUTE * 60_000L
        val awakeEnd = minOf(dayStart + AWAKE_END_MINUTE * 60_000L, now)
        if (awakeEnd <= awakeStart) return 0
        val inDay = spans.filter { it.end > dayStart && it.start < awakeEnd }.sortedBy { it.start }
        if (inDay.isEmpty()) return 0
        var best = 0L
        for ((a, b) in inDay.zipWithNext()) {
            val s = maxOf(a.end, awakeStart)
            val e = minOf(b.start, awakeEnd)
            if (e > s) best = maxOf(best, e - s)
        }
        // Today: time since the last use, if the day is still going.
        val last = inDay.last()
        val today = PolicyTime.localDate(now, zone) == date
        if (today && last.end < awakeEnd) best = maxOf(best, awakeEnd - maxOf(last.end, awakeStart))
        return best
    }

    /**
     * Pickups in [from, to): unlocks when the phone reports them, otherwise screen-on events (phones
     * without a lock screen never report an unlock).
     */
    fun pickups(events: List<UsageCalculator.Event>, from: Long, to: Long): Int {
        val inWindow = events.filter { it.time in from until to }
        val unlocks = inWindow.count { it.type == UsageCalculator.Type.UNLOCK }
        return if (unlocks > 0) unlocks else inWindow.count { it.type == UsageCalculator.Type.SCREEN_ON }
    }

    /** "Balance": screen time as a share of awake time, per day (0–100, may exceed 100). */
    fun balancePercent(screenMs: Long, days: Int): Int =
        if (days <= 0) 0 else Math.round(screenMs * 100.0 / (AWAKE_MS * days)).toInt()

    /** Category shares in whole percent that add up to 100 (largest remainder), or all zero. */
    fun sharePercents(byCategory: LongArray): IntArray {
        val total = byCategory.sum()
        if (total <= 0) return IntArray(byCategory.size)
        val raw = byCategory.map { it * 100.0 / total }
        val floors = raw.map { it.toInt() }.toIntArray()
        var left = 100 - floors.sum()
        raw.withIndex().sortedByDescending { it.value - it.value.toInt() }.forEach { (i, _) -> if (left > 0) { floors[i]++; left-- } }
        return floors
    }

    /** Change from [previous] to [current] in whole percent, or null when there is no previous value. */
    fun changePercent(current: Long, previous: Long): Int? =
        if (previous <= 0) null else Math.round((current - previous) * 100.0 / previous).toInt()
}
