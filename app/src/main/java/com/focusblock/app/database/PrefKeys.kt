package com.focusblock.app.database

/** Keys in the existing key/value `settings` table used by the v17 product. */
object PrefKeys {
    /** JSON of the last block setup; key kept from the recovery branch so "Repeat last block" survives. */
    const val LAST_SETUP = "blocking_first_last_v1"
    /** Recovery-branch session metadata (read only by the v17 migration). */
    const val LEGACY_SESSION_META = "blocking_first_session_v1"

    const val DEFAULT_TYPE = "default_block_type"
    const val DEFAULT_MINUTES = "default_block_minutes"
    const val DEFAULT_STRENGTH = "default_block_strength"
    const val DEFAULT_FOCUS = "default_block_focus"
    const val DEFAULT_BREAK = "default_block_break"
    const val DEFAULT_ROUNDS = "default_block_rounds"

    const val NOTIFY_BLOCK_STATUS = "notify_block_status"
    const val NOTIFY_RULES = "notify_rules"
    const val NOTIFY_USAGE_REMINDERS = "notify_usage_reminders"
    const val NOTIFY_DAILY_SUMMARY = "notify_daily_summary"
    const val NOTIFY_PROBLEMS = "notify_problems"
    /** Minutes of continuous use in one app before the pause screen (0 = off). */
    const val PAUSE_MINUTES = "pause_minutes"
    /** Daily limit taper (LimitTaper): target in minutes (0 = off), step, days under in a row, last day judged. */
    const val TAPER_TARGET = "taper_target_minutes"
    const val TAPER_STEP = "taper_step_minutes"
    const val TAPER_UNDER_STREAK = "taper_under_streak"
    const val TAPER_LAST_DAY = "taper_last_day"

    const val WIDGET_ACTION = "widget_action"
    const val ONBOARDING_DONE = "onboarding_v17_done"
    const val ESSENTIALS_SEEDED = "essentials_seeded"
    const val MIGRATION_17_REPORT = "migration_17_report"

    const val SAVED_APPS = "quick_block_saved_apps"
    /** JSON {package: DISTRACTING|NEUTRAL|PRODUCTIVE} of categories the user chose on the Activity tab. */
    const val APP_CATEGORIES = "app_categories_v1"
}
