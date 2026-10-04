package com.focusblock.app.core

import android.content.Context
import com.focusblock.app.R
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.policy.TimeWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * User-facing time formatting (spec 3.3): follows the device 12/24-hour setting and locale, shows
 * "Midnight" instead of 12:00 AM, and always pairs a countdown with an end time.
 */
object Fmt {
    fun time(context: Context, millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val zdt = Instant.ofEpochMilli(millis).atZone(zone)
        if (zdt.hour == 0 && zdt.minute == 0) return context.getString(R.string.time_midnight)
        val format = android.text.format.DateFormat.getTimeFormat(context)
        format.timeZone = java.util.TimeZone.getTimeZone(zone)
        // No-break space so "8:58 PM" never wraps before the AM/PM marker (newer Android already emits U+202F).
        return format.format(Date(millis)).replace(' ', '\u00A0')
    }

    /** A minute of the day (0..1439) formatted like [time]. */
    fun minuteOfDay(context: Context, minute: Int): String {
        if (minute == 0) return context.getString(R.string.time_midnight)
        val zone = ZoneId.systemDefault()
        val millis = LocalDate.now(zone).atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        return time(context, millis, zone)
    }

    /** Short hour label for chart axes: "6" in 24-hour format, otherwise "6 AM". */
    fun hourShort(context: Context, hour: Int): String =
        if (android.text.format.DateFormat.is24HourFormat(context)) hour.toString()
        else context.getString(if (hour < 12) R.string.hour_am else R.string.hour_pm, if (hour % 12 == 0) 12 else hour % 12)

    /** "Mon, 29 Sep" in the device language. */
    fun dateShort(date: LocalDate): String =
        date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", java.util.Locale.getDefault()))

    /** "29 Sep". */
    fun dayMonth(date: LocalDate): String =
        date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))

    /** One-letter or short weekday for chart labels ("Mon"). */
    fun weekdayShort(date: LocalDate): String =
        date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault()))

    /** "45 min", "3 h", "3 h 40 min". Rounds down to whole minutes; under a minute shows "0 min". */
    fun duration(context: Context, millis: Long): String {
        val minutes = (millis.coerceAtLeast(0) / SessionClock.MINUTE).toInt()
        return minutes(context, minutes)
    }

    fun minutes(context: Context, minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> context.getString(R.string.duration_min, m)
            m == 0 -> context.getString(R.string.duration_h, h)
            else -> context.getString(R.string.duration_h_min, h, m)
        }
    }

    /** Countdown text such as "31 min left"; under a minute rounds up so it never shows "0 min left". */
    fun left(context: Context, millis: Long): String {
        val minutes = ((millis.coerceAtLeast(0) + SessionClock.MINUTE - 1) / SessionClock.MINUTE).toInt()
        return context.getString(R.string.time_left, minutes(context, minutes))
    }

    /** "every day", "on weekdays", "on weekends" or "on Mon, Wed". */
    fun days(context: Context, days: Set<Int>): String = when (days) {
        TimeWindow.EVERY_DAY -> context.getString(R.string.days_every_day)
        TimeWindow.WEEKDAYS -> context.getString(R.string.days_weekdays)
        TimeWindow.WEEKENDS -> context.getString(R.string.days_weekends)
        else -> context.getString(R.string.days_list, days.sorted().joinToString(", ") { dayShort(context, it) })
    }

    fun dayShort(context: Context, day: Int): String = context.getString(
        when (day) { 1 -> R.string.day_1; 2 -> R.string.day_2; 3 -> R.string.day_3; 4 -> R.string.day_4; 5 -> R.string.day_5; 6 -> R.string.day_6; else -> R.string.day_7 },
    )

    fun dayFull(context: Context, day: Int): String = context.getString(
        when (day) { 1 -> R.string.day_full_1; 2 -> R.string.day_full_2; 3 -> R.string.day_full_3; 4 -> R.string.day_full_4; 5 -> R.string.day_full_5; 6 -> R.string.day_full_6; else -> R.string.day_full_7 },
    )

    /** One app by name, otherwise "4 apps". */
    fun apps(context: Context, packages: Collection<String>, label: (String) -> String): String =
        if (packages.size == 1) label(packages.first())
        else context.resources.getQuantityString(R.plurals.apps_count, packages.size, packages.size)

    /** "1st", "2nd", "3rd", "4th" in English; the plain number elsewhere. */
    fun ordinal(n: Int): String {
        if (Locale.getDefault().language != "en") return n.toString()
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }
}
