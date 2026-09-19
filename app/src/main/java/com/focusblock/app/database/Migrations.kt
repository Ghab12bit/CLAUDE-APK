package com.focusblock.app.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real migrations.
 *
 * The database previously ran with `fallbackToDestructiveMigration()`, which
 * means every schema change since v1 silently deleted the user's entire
 * history: their blocked-app list, schedules, usage stats and active
 * restrictions. That is unacceptable for an app whose whole value is the
 * configuration the user has built up, so destructive fallback is gone and
 * this migration carries everything forward.
 *
 * 12 -> 13 introduces the unified [com.focusblock.app.database.entity.BlockRule]
 * model and translates every existing blocking source into it. Nothing is
 * dropped: the old tables stay in place, untouched, so a user who downgrades
 * or a bug in the translation cannot cost anyone their data.
 */
object Migrations {

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {

            // ---------------------------------------------------------------
            // 1. New tables
            // ---------------------------------------------------------------

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `block_rules` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `isEnabled` INTEGER NOT NULL,
                    `packages` TEXT NOT NULL,
                    `iconType` TEXT NOT NULL,
                    `colorHex` TEXT NOT NULL,
                    `note` TEXT NOT NULL,
                    `hasTimeCondition` INTEGER NOT NULL,
                    `startMinute` INTEGER NOT NULL,
                    `endMinute` INTEGER NOT NULL,
                    `daysOfWeek` TEXT NOT NULL,
                    `hasUsageCondition` INTEGER NOT NULL,
                    `usageLimitMinutes` INTEGER NOT NULL,
                    `usageWindow` TEXT NOT NULL,
                    `isManualActive` INTEGER NOT NULL,
                    `activeUntil` INTEGER,
                    `commitment` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `rule_usage` (
                    `ruleId` INTEGER NOT NULL,
                    `bucket` TEXT NOT NULL,
                    `usageSeconds` INTEGER NOT NULL,
                    `launchCount` INTEGER NOT NULL,
                    `lastUpdated` INTEGER NOT NULL,
                    PRIMARY KEY(`ruleId`, `bucket`)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `rule_overrides` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `ruleId` INTEGER NOT NULL,
                    `date` TEXT NOT NULL,
                    `expiresAt` INTEGER NOT NULL,
                    `reason` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `protection_lock` (
                    `id` INTEGER NOT NULL,
                    `level` TEXT NOT NULL,
                    `lockedUntil` INTEGER NOT NULL,
                    `cooldownMinutes` INTEGER NOT NULL,
                    `unlockRequestedAt` INTEGER NOT NULL,
                    `pinHash` TEXT NOT NULL,
                    `pinSalt` TEXT NOT NULL,
                    `emergencyDate` TEXT NOT NULL,
                    `emergencyUsedToday` INTEGER NOT NULL,
                    `maxEmergencyPerDay` INTEGER NOT NULL,
                    `emergencyDurationMinutes` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            val now = System.currentTimeMillis()

            // ---------------------------------------------------------------
            // 2. Schedules  ->  rules with a Time condition
            //
            // Carries the user's existing schedules across verbatim, including
            // the Sleep Hygiene routine. A schedule that had isStrictMode set
            // becomes a LOCKED rule, which is the closest honest equivalent.
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT INTO block_rules (
                    name, kind, isEnabled, packages, iconType, colorHex, note,
                    hasTimeCondition, startMinute, endMinute, daysOfWeek,
                    hasUsageCondition, usageLimitMinutes, usageWindow,
                    isManualActive, activeUntil, commitment, createdAt, updatedAt
                )
                SELECT
                    name, 'AUTOMATIC', isEnabled, blockedPackages, iconType, colorHex, '',
                    1, startTimeMinutes, endTimeMinutes, daysOfWeek,
                    0, 0, 'DAILY',
                    0, NULL,
                    CASE WHEN isStrictMode = 1 THEN 'LOCKED' ELSE 'OFF' END,
                    createdAt, $now
                FROM schedules
                """.trimIndent()
            )

            // ---------------------------------------------------------------
            // 3. Bedtime Mode  ->  an overnight rule
            //
            // Bedtime never actually blocked anything: it was absent from
            // shouldBlockApp() and appeared only when labelling a block that
            // had already been decided elsewhere. Migrating it means the
            // setting the user configured finally does what it always claimed.
            // Apps come from their blocked list, since Bedtime had no list.
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT INTO block_rules (
                    name, kind, isEnabled, packages, iconType, colorHex, note,
                    hasTimeCondition, startMinute, endMinute, daysOfWeek,
                    hasUsageCondition, usageLimitMinutes, usageWindow,
                    isManualActive, activeUntil, commitment, createdAt, updatedAt
                )
                SELECT
                    'Bedtime', 'AUTOMATIC', isEnabled,
                    COALESCE((
                        SELECT GROUP_CONCAT(packageName)
                        FROM blocked_apps
                        WHERE isBlocked = 1 AND isInAllowlist = 0
                    ), ''),
                    'SLEEP', '#8E5CF6', '',
                    1,
                    startHour * 60 + startMinute,
                    endHour * 60 + endMinute,
                    TRIM(
                        CASE WHEN monday    = 1 THEN '1,' ELSE '' END ||
                        CASE WHEN tuesday   = 1 THEN '2,' ELSE '' END ||
                        CASE WHEN wednesday = 1 THEN '3,' ELSE '' END ||
                        CASE WHEN thursday  = 1 THEN '4,' ELSE '' END ||
                        CASE WHEN friday    = 1 THEN '5,' ELSE '' END ||
                        CASE WHEN saturday  = 1 THEN '6,' ELSE '' END ||
                        CASE WHEN sunday    = 1 THEN '7,' ELSE '' END,
                        ','
                    ),
                    0, 0, 'DAILY',
                    0, NULL, 'OFF', createdAt, $now
                FROM bedtime_mode_settings
                WHERE isEnabled = 1
                """.trimIndent()
            )

            // ---------------------------------------------------------------
            // 4. Daily App Timer  ->  a DAILY usage budget
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT INTO block_rules (
                    name, kind, isEnabled, packages, iconType, colorHex, note,
                    hasTimeCondition, startMinute, endMinute, daysOfWeek,
                    hasUsageCondition, usageLimitMinutes, usageWindow,
                    isManualActive, activeUntil, commitment, createdAt, updatedAt
                )
                SELECT
                    'Daily app budget', 'AUTOMATIC', isEnabled, timerApps,
                    'FOCUS', '#0A84FF', '',
                    0, 0, 0, '1,2,3,4,5,6,7',
                    1, dailyLimitMinutes, 'DAILY',
                    0, NULL, 'OFF', createdAt, $now
                FROM app_timer_settings
                WHERE isEnabled = 1 AND timerApps <> ''
                """.trimIndent()
            )

            // ---------------------------------------------------------------
            // 5. Global Daily Limit  ->  a DAILY usage budget
            //
            // Its whitelist (apps tracked but never blocked, e.g. WhatsApp for
            // client work) is promoted into the real allowlist, which the
            // engine honours above every rule including a PIN-locked one.
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT INTO block_rules (
                    name, kind, isEnabled, packages, iconType, colorHex, note,
                    hasTimeCondition, startMinute, endMinute, daysOfWeek,
                    hasUsageCondition, usageLimitMinutes, usageWindow,
                    isManualActive, activeUntil, commitment, createdAt, updatedAt
                )
                SELECT
                    'Daily screen budget', 'AUTOMATIC', isEnabled, trackedPackages,
                    'DETOX', '#FF9F0A', '',
                    0, 0, 0, '1,2,3,4,5,6,7',
                    1, dailyLimitMinutes, 'DAILY',
                    0, NULL, 'OFF', createdAt, $now
                FROM global_daily_limit_settings
                WHERE isEnabled = 1 AND trackedPackages <> ''
                """.trimIndent()
            )

            // Matched with LIKE against the comma-delimited list rather than
            // IN (...), because whitelistedPackages is a single string: an
            // IN comparison would only ever match a list of exactly one app,
            // silently leaving the rest blockable.
            db.execSQL(
                """
                UPDATE blocked_apps
                SET isInAllowlist = 1
                WHERE EXISTS (
                    SELECT 1 FROM global_daily_limit_settings g
                    WHERE g.whitelistedPackages <> ''
                      AND ',' || REPLACE(g.whitelistedPackages, ' ', '') || ','
                          LIKE '%,' || blocked_apps.packageName || ',%'
                )
                """.trimIndent()
            )

            // ---------------------------------------------------------------
            // 6. Focus Cycle  ->  an HOURLY usage budget
            //
            // "10 minutes of use, then a 30 minute break" is exactly an hourly
            // budget, so the behaviour survives while the armed/paused state
            // machine, its overlay service and its override table all go away.
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT INTO block_rules (
                    name, kind, isEnabled, packages, iconType, colorHex, note,
                    hasTimeCondition, startMinute, endMinute, daysOfWeek,
                    hasUsageCondition, usageLimitMinutes, usageWindow,
                    isManualActive, activeUntil, commitment, createdAt, updatedAt
                )
                SELECT
                    name, 'AUTOMATIC', isEnabled,
                    CASE WHEN selectedPackages <> '' THEN selectedPackages ELSE COALESCE((
                        SELECT GROUP_CONCAT(packageName)
                        FROM blocked_apps
                        WHERE isBlocked = 1 AND isInAllowlist = 0
                    ), '') END,
                    'FOCUS', '#30D158', '',
                    0, 0, 0, '1,2,3,4,5,6,7',
                    1, usageWindowMinutes, 'HOURLY',
                    0, NULL, 'OFF', createdAt, $now
                FROM focus_cycles
                WHERE isEnabled = 1
                """.trimIndent()
            )

            // ---------------------------------------------------------------
            // 7. An active Quick Block session  ->  a running manual rule
            //
            // An active restriction must survive the upgrade. If the user is
            // mid-block when they update, they stay blocked.
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT INTO block_rules (
                    name, kind, isEnabled, packages, iconType, colorHex, note,
                    hasTimeCondition, startMinute, endMinute, daysOfWeek,
                    hasUsageCondition, usageLimitMinutes, usageWindow,
                    isManualActive, activeUntil, commitment, createdAt, updatedAt
                )
                SELECT
                    'Block now', 'MANUAL', 1, blockedPackages,
                    'FOCUS', '#0A84FF', '',
                    0, 0, 0, '1,2,3,4,5,6,7',
                    0, 0, 'DAILY',
                    1, endTime, 'OFF', startTime, $now
                FROM quick_block_sessions
                WHERE isActive = 1
                ORDER BY startTime DESC
                LIMIT 1
                """.trimIndent()
            )

            // ---------------------------------------------------------------
            // 8. Strict Mode / Hard Mode  ->  one ProtectionLock
            //
            // There were two unrelated Hard Mode flags: one in the settings
            // table (read by the enforcement path) and one on the global-limit
            // row (written by the Settings screen). Either one being set is
            // honoured here, so nobody silently loses a commitment they made.
            //
            // The old plaintext PIN is deliberately NOT carried across: it was
            // stored in the clear, and PIN_LOCKED now requires a salted hash.
            // A user with a PIN is migrated to LOCKED, keeping their time lock,
            // and is asked to set a new PIN if they want PIN_LOCKED back.
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT OR REPLACE INTO protection_lock (
                    id, level, lockedUntil, cooldownMinutes, unlockRequestedAt,
                    pinHash, pinSalt, emergencyDate, emergencyUsedToday,
                    maxEmergencyPerDay, emergencyDurationMinutes, updatedAt
                )
                VALUES (
                    1,
                    CASE
                        WHEN (SELECT `value` FROM settings WHERE `key` = 'strict_mode_enabled') = 'true'
                          OR (SELECT `value` FROM settings WHERE `key` = 'hard_mode_enabled') = 'true'
                          OR (SELECT isHardModeEnabled FROM global_daily_limit_settings WHERE id = 1) = 1
                        THEN 'LOCKED'
                        ELSE 'OFF'
                    END,
                    COALESCE(
                        CAST((SELECT `value` FROM settings WHERE `key` = 'strict_mode_end_time') AS INTEGER),
                        0
                    ),
                    5, 0, '', '', '', 0, 1, 15, $now
                )
                """.trimIndent()
            )

            // ---------------------------------------------------------------
            // 9. Seed usage buckets from today's recorded usage, so a freshly
            //    migrated budget rule does not start the day at zero and hand
            //    the user back time they have already spent.
            // ---------------------------------------------------------------
            db.execSQL(
                """
                INSERT OR IGNORE INTO rule_usage (ruleId, bucket, usageSeconds, launchCount, lastUpdated)
                SELECT
                    r.id,
                    DATE('now', 'localtime'),
                    COALESCE((
                        SELECT SUM(u.usageMinutes) * 60
                        FROM daily_usage u
                        WHERE u.date = DATE('now', 'localtime')
                          AND ',' || r.packages || ',' LIKE '%,' || u.packageName || ',%'
                    ), 0),
                    0,
                    $now
                FROM block_rules r
                WHERE r.hasUsageCondition = 1
                """.trimIndent()
            )
        }
    }

    /**
     * 13 -> 14: the person, and what they have at stake.
     *
     * Adds the user's own reason for the protected time (captured once, shown
     * at the moment of the urge), the impulse-pause setting, park mode, and
     * stake tracking. Purely additive -- no existing table is touched.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `focus_profile` (
                    `id` INTEGER NOT NULL,
                    `reason` TEXT NOT NULL,
                    `reasonDetail` TEXT NOT NULL,
                    `pauseSeconds` INTEGER NOT NULL,
                    `allowBreathThrough` INTEGER NOT NULL,
                    `parkUntil` INTEGER,
                    `parkStartedAt` INTEGER,
                    `currentStreakDays` INTEGER NOT NULL,
                    `longestStreakDays` INTEGER NOT NULL,
                    `lastCleanDate` TEXT NOT NULL,
                    `graceUsedMonth` TEXT NOT NULL,
                    `totalProtectedMinutes` INTEGER NOT NULL,
                    `windowsCompleted` INTEGER NOT NULL,
                    `impulsesPassed` INTEGER NOT NULL,
                    `impulsesFollowed` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `window_outcomes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `ruleId` INTEGER NOT NULL,
                    `ruleName` TEXT NOT NULL,
                    `date` TEXT NOT NULL,
                    `startedAt` INTEGER NOT NULL,
                    `endedAt` INTEGER NOT NULL,
                    `minutesProtected` INTEGER NOT NULL,
                    `impulsesPassed` INTEGER NOT NULL,
                    `impulsesFollowed` INTEGER NOT NULL,
                    `clean` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `milestones` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `value` INTEGER NOT NULL,
                    `achievedAt` INTEGER NOT NULL,
                    `seen` INTEGER NOT NULL,
                    `shared` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT OR IGNORE INTO focus_profile VALUES " +
                    "(1, '', '', 8, 1, NULL, NULL, 0, 0, '', '', 0, 0, 0, 0, $now, $now)"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_12_13, MIGRATION_13_14)
}
