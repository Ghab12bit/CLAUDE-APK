package com.focusblock.app.blocking

import com.focusblock.app.database.entity.BlockRule
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.database.entity.RuleKind
import com.focusblock.app.database.entity.ScheduleIconType
import com.focusblock.app.database.entity.UsageWindow

/**
 * Starting points for a rule. Every field is editable afterwards: a template
 * is a filled-in form, never a fixed behaviour.
 */
object RuleTemplates {

    /**
     * Apps that must never end up in a generated rule, because losing them
     * costs more than the distraction is worth.
     *
     * Messaging apps are deliberately absent from the distraction defaults.
     * A keyword match on "whatsapp", "telegram" and "signal" is what made the
     * old Strict Mode block the user's client conversations, and treating all
     * messaging as distraction is a product decision the user has to make
     * explicitly rather than one the app makes for them.
     */
    val ESSENTIAL_NEVER_SUGGEST = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.phone",
        "com.google.android.apps.maps",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.google.android.calendar",
        "com.google.android.gm",
        "com.android.settings"
    )

    /**
     * The apps most likely worth blocking in a focus window, ordered by how
     * often they are the problem. Used only to pre-tick a picker; the user
     * confirms the selection.
     */
    val COMMON_DISTRACTIONS = listOf(
        "com.instagram.android",
        "com.facebook.katana",
        "com.reddit.frontpage",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.snapchat.android",
        "com.twitter.android",
        "com.netflix.mediaclient",
        "tv.twitch.android.app",
        "com.pinterest"
    )

    data class Template(
        val key: String,
        val title: String,
        /** One line explaining what this rule does, in plain terms. */
        val summary: String,
        val build: (packages: List<String>) -> BlockRule
    )

    /**
     * The after-gym window.
     *
     * Starts at 8:45pm rather than 8:00pm: the user gets home around eight and
     * needs food and a shower before working. The gap is the point, and it is
     * an ordinary editable start time, not a hidden grace period.
     *
     * Defaults to LOCKED because the user's stated problem is that in-the-
     * moment desire beats the plan. The terms are shown before it arms, and
     * the level can be set to OFF in one tap.
     */
    val EVENING_WORK = Template(
        key = "evening_work",
        title = "Evening work block",
        summary = "Weeknights 8:45pm-10:30pm. Blocks your distractions so the " +
            "time after the gym goes to your own work.",
        build = { packages ->
            BlockRule(
                name = "Evening work block",
                kind = RuleKind.AUTOMATIC,
                packages = packages.joinToString(","),
                iconType = ScheduleIconType.WORK,
                colorHex = "#0A84FF",
                hasTimeCondition = true,
                startMinute = 20 * 60 + 45,
                endMinute = 22 * 60 + 30,
                daysOfWeek = "1,2,3,4,5",
                commitment = CommitmentLevel.LOCKED
            )
        }
    )

    val WIND_DOWN = Template(
        key = "wind_down",
        title = "Sleep",
        summary = "Every night 10pm-6am. Keeps the scroll out of bed.",
        build = { packages ->
            BlockRule(
                name = "Sleep",
                kind = RuleKind.AUTOMATIC,
                packages = packages.joinToString(","),
                iconType = ScheduleIconType.SLEEP,
                colorHex = "#8E5CF6",
                hasTimeCondition = true,
                startMinute = 22 * 60,
                endMinute = 6 * 60,
                daysOfWeek = "1,2,3,4,5,6,7",
                commitment = CommitmentLevel.OFF
            )
        }
    )

    val DAILY_BUDGET = Template(
        key = "daily_budget",
        title = "Daily budget",
        summary = "A combined daily allowance across your distracting apps. " +
            "When it runs out, they're blocked until tomorrow.",
        build = { packages ->
            BlockRule(
                name = "Daily budget",
                kind = RuleKind.AUTOMATIC,
                packages = packages.joinToString(","),
                iconType = ScheduleIconType.DETOX,
                colorHex = "#FF9F0A",
                hasUsageCondition = true,
                usageLimitMinutes = 60,
                usageWindow = UsageWindow.DAILY,
                commitment = CommitmentLevel.OFF
            )
        }
    )

    /**
     * The old Focus Cycle, expressed as a condition instead of a state machine.
     * "Ten minutes an hour" is a budget that resets hourly.
     */
    val HOURLY_SIP = Template(
        key = "hourly_sip",
        title = "A few minutes an hour",
        summary = "Allows a short check-in each hour, then blocks until the " +
            "next one. Breaks the habit of opening an app every few minutes.",
        build = { packages ->
            BlockRule(
                name = "A few minutes an hour",
                kind = RuleKind.AUTOMATIC,
                packages = packages.joinToString(","),
                iconType = ScheduleIconType.FOCUS,
                colorHex = "#30D158",
                hasUsageCondition = true,
                usageLimitMinutes = 10,
                usageWindow = UsageWindow.HOURLY,
                commitment = CommitmentLevel.OFF
            )
        }
    )

    val ALL = listOf(EVENING_WORK, WIND_DOWN, DAILY_BUDGET, HOURLY_SIP)

    /**
     * A one-off manual block, started from the home screen.
     * [durationMinutes] of null means "until I turn it off".
     */
    fun blockNow(packages: List<String>, durationMinutes: Int?): BlockRule {
        val now = System.currentTimeMillis()
        return BlockRule(
            name = "Block now",
            kind = RuleKind.MANUAL,
            packages = packages.joinToString(","),
            iconType = ScheduleIconType.FOCUS,
            isManualActive = true,
            activeUntil = durationMinutes?.let { now + it * 60_000L },
            commitment = CommitmentLevel.OFF,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Filter a suggested app list down to what is safe to pre-tick.
     */
    fun safeSuggestions(candidates: List<String>): List<String> =
        candidates.filter { it !in ESSENTIAL_NEVER_SUGGEST && it !in BlockRule.NEVER_BLOCK }
}
