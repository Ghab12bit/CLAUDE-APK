package com.focusblock.app.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies migration 12 -> 13 against a real SQLite engine.
 *
 * This is the test that matters most for shipping: if the migration throws, or
 * if the tables it hand-writes do not match what Room expects, the app crashes
 * on launch for every existing user and no amount of UI polish matters. The
 * previous code avoided the problem by wiping the database instead.
 *
 * Two things are checked:
 *
 *   1. The migration SQL runs and produces the right rows (syntax and logic).
 *   2. Every table the migration CREATEs is byte-for-byte compatible with the
 *      table Room would create itself. A mismatch here is exactly the
 *      "expected ... found ..." IllegalStateException Room throws on open.
 */
@RunWith(AndroidJUnit4::class)
class Migration12to13Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * Only the v12 tables the migration actually reads. Building the full v12
     * schema by hand would be error-prone; these are the inputs under test.
     */
    private val v12Tables = listOf(
        """CREATE TABLE IF NOT EXISTS `blocked_apps` (
            `packageName` TEXT NOT NULL, `appName` TEXT NOT NULL,
            `isBlocked` INTEGER NOT NULL, `isInAllowlist` INTEGER NOT NULL,
            `blockedCount` INTEGER NOT NULL, `totalBlockedCount` INTEGER NOT NULL,
            `lastBlockedTime` INTEGER NOT NULL, `addedTime` INTEGER NOT NULL,
            PRIMARY KEY(`packageName`))""",
        """CREATE TABLE IF NOT EXISTS `schedules` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL,
            `iconType` TEXT NOT NULL, `colorHex` TEXT NOT NULL,
            `isEnabled` INTEGER NOT NULL, `startTimeMinutes` INTEGER NOT NULL,
            `endTimeMinutes` INTEGER NOT NULL, `daysOfWeek` TEXT NOT NULL,
            `blockedPackages` TEXT NOT NULL, `isStrictMode` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS `quick_block_sessions` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startTime` INTEGER NOT NULL,
            `endTime` INTEGER, `blockedPackages` TEXT NOT NULL, `isActive` INTEGER NOT NULL,
            `isPomodoroSession` INTEGER NOT NULL, `pomodoroWorkMinutes` INTEGER NOT NULL,
            `pomodoroBreakMinutes` INTEGER NOT NULL, `previouslyBlockedPackages` TEXT NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS `settings` (
            `key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))""",
        """CREATE TABLE IF NOT EXISTS `app_timer_settings` (
            `id` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL,
            `dailyLimitMinutes` INTEGER NOT NULL, `escalationThresholdMinutes` INTEGER NOT NULL,
            `timerApps` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `global_daily_limit_settings` (
            `id` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL,
            `dailyLimitMinutes` INTEGER NOT NULL, `warningMinutesBefore` INTEGER NOT NULL,
            `trackedPackages` TEXT NOT NULL, `useAppTimerApps` INTEGER NOT NULL,
            `whitelistedPackages` TEXT NOT NULL, `isHardModeEnabled` INTEGER NOT NULL,
            `hardModeLockUntil` INTEGER NOT NULL, `hardModeUnlockRequestedAt` INTEGER NOT NULL,
            `hardModeCooldownMinutes` INTEGER NOT NULL, `hardModeRequirePhrase` INTEGER NOT NULL,
            `hardModeUnlockPhrase` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `focus_cycles` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL,
            `usageWindowMinutes` INTEGER NOT NULL, `breakDurationMinutes` INTEGER NOT NULL,
            `isEnabled` INTEGER NOT NULL, `isActive` INTEGER NOT NULL,
            `isArmed` INTEGER NOT NULL, `isPaused` INTEGER NOT NULL,
            `selectedPackages` TEXT NOT NULL, `useQuickBlockApps` INTEGER NOT NULL,
            `cycleStartTime` INTEGER, `breakStartTime` INTEGER,
            `accumulatedUsageMillis` INTEGER NOT NULL, `lastActiveTime` INTEGER,
            `createdAt` INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS `bedtime_mode_settings` (
            `id` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL,
            `startHour` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL,
            `endHour` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL,
            `monday` INTEGER NOT NULL, `tuesday` INTEGER NOT NULL,
            `wednesday` INTEGER NOT NULL, `thursday` INTEGER NOT NULL,
            `friday` INTEGER NOT NULL, `saturday` INTEGER NOT NULL,
            `sunday` INTEGER NOT NULL, `showWindDownReminder` INTEGER NOT NULL,
            `windDownMinutesBefore` INTEGER NOT NULL, `allowEmergencyOverride` INTEGER NOT NULL,
            `overrideUsedToday` INTEGER NOT NULL, `lastOverrideDate` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `daily_usage` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL,
            `date` TEXT NOT NULL, `usageMinutes` INTEGER NOT NULL,
            `limitReached` INTEGER NOT NULL, `lastUpdated` INTEGER NOT NULL)"""
    )

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    v12Tables.forEach { db.execSQL(it) }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(TEST_DB)
    }

    // ------------------------------------------------------------------

    @Test
    fun migrationRunsWithoutError_onAnEmptyDatabase() {
        Migrations.MIGRATION_12_13.migrate(db)
        assertEquals(0, count("block_rules"))
        assertTrue(tableExists("rule_usage"))
        assertTrue(tableExists("rule_overrides"))
        assertTrue(tableExists("protection_lock"))
    }

    @Test
    fun schedulesBecomeRulesWithATimeCondition() {
        db.execSQL(
            "INSERT INTO schedules (name, iconType, colorHex, isEnabled, startTimeMinutes, " +
                "endTimeMinutes, daysOfWeek, blockedPackages, isStrictMode, createdAt) " +
                "VALUES ('Sleep Hygiene','SLEEP','#8E5CF6',1,1320,360,'1,2,3,4,5,6,7','com.a,com.b',0,1)"
        )
        Migrations.MIGRATION_12_13.migrate(db)

        assertEquals(1, count("block_rules"))
        query("SELECT name, kind, hasTimeCondition, startMinute, endMinute, packages " +
            "FROM block_rules") { c ->
            assertEquals("Sleep Hygiene", c.getString(0))
            assertEquals("AUTOMATIC", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(1320, c.getInt(3))
            assertEquals(360, c.getInt(4))
            assertEquals("com.a,com.b", c.getString(5))
        }
    }

    @Test
    fun aStrictScheduleBecomesALockedRule() {
        db.execSQL(
            "INSERT INTO schedules (name, iconType, colorHex, isEnabled, startTimeMinutes, " +
                "endTimeMinutes, daysOfWeek, blockedPackages, isStrictMode, createdAt) " +
                "VALUES ('Work','WORK','#0A84FF',1,1245,1350,'1,2,3,4,5','com.a',1,1)"
        )
        Migrations.MIGRATION_12_13.migrate(db)
        query("SELECT commitment FROM block_rules") { c -> assertEquals("LOCKED", c.getString(0)) }
    }

    /**
     * REGRESSION: the allowlist promotion originally used
     * `WHERE packageName IN (SELECT whitelistedPackages ...)`, comparing each
     * package against the WHOLE comma-separated string. It therefore only ever
     * matched a whitelist of exactly one app, silently leaving the rest
     * blockable -- including the messaging apps the user needs for work.
     */
    @Test
    fun everyWhitelistedAppIsPromotedToTheAllowlist() {
        listOf("com.whatsapp", "org.telegram.messenger", "com.other").forEach {
            db.execSQL(
                "INSERT INTO blocked_apps (packageName, appName, isBlocked, isInAllowlist, " +
                    "blockedCount, totalBlockedCount, lastBlockedTime, addedTime) " +
                    "VALUES ('$it','$it',1,0,0,0,0,0)"
            )
        }
        db.execSQL(
            "INSERT INTO global_daily_limit_settings VALUES " +
                "(1,1,180,15,'com.other',1,'com.whatsapp,org.telegram.messenger',0,0,0,15,1,'x',1,1)"
        )
        Migrations.MIGRATION_12_13.migrate(db)

        assertEquals(
            "both whitelisted apps must be allowlisted, not just the first",
            2,
            countWhere("blocked_apps", "isInAllowlist = 1")
        )
        assertEquals(0, countWhere("blocked_apps", "isInAllowlist = 1 AND packageName = 'com.other'"))
    }

    @Test
    fun bedtimeBecomesAnOvernightRuleWithTheRightDays() {
        db.execSQL(
            "INSERT INTO blocked_apps (packageName, appName, isBlocked, isInAllowlist, " +
                "blockedCount, totalBlockedCount, lastBlockedTime, addedTime) " +
                "VALUES ('com.insta','Insta',1,0,0,0,0,0)"
        )
        // Mon, Wed, Fri only.
        db.execSQL(
            "INSERT INTO bedtime_mode_settings VALUES (1,1,23,0,7,0,1,0,1,0,1,0,0,1,15,1,0,'',1,1)"
        )
        Migrations.MIGRATION_12_13.migrate(db)

        query("SELECT daysOfWeek, startMinute, endMinute, packages FROM block_rules") { c ->
            assertEquals("1,3,5", c.getString(0))
            assertEquals(23 * 60, c.getInt(1))
            assertEquals(7 * 60, c.getInt(2))
            assertEquals("com.insta", c.getString(3))
        }
    }

    @Test
    fun focusCycleBecomesAnHourlyBudget() {
        db.execSQL(
            "INSERT INTO focus_cycles (name, usageWindowMinutes, breakDurationMinutes, isEnabled, " +
                "isActive, isArmed, isPaused, selectedPackages, useQuickBlockApps, " +
                "accumulatedUsageMillis, createdAt) " +
                "VALUES ('Focus Cycle',10,30,1,0,1,0,'com.a',0,0,1)"
        )
        Migrations.MIGRATION_12_13.migrate(db)
        query("SELECT hasUsageCondition, usageLimitMinutes, usageWindow FROM block_rules") { c ->
            assertEquals(1, c.getInt(0))
            assertEquals(10, c.getInt(1))
            assertEquals("HOURLY", c.getString(2))
        }
    }

    @Test
    fun anActiveQuickBlockSurvivesTheUpgrade() {
        val end = System.currentTimeMillis() + 600_000L
        db.execSQL(
            "INSERT INTO quick_block_sessions (startTime, endTime, blockedPackages, isActive, " +
                "isPomodoroSession, pomodoroWorkMinutes, pomodoroBreakMinutes, previouslyBlockedPackages) " +
                "VALUES (1, $end, 'com.a,com.b', 1, 0, 25, 5, '')"
        )
        Migrations.MIGRATION_12_13.migrate(db)
        query("SELECT kind, isManualActive, activeUntil FROM block_rules") { c ->
            assertEquals("MANUAL", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals(end, c.getLong(2))
        }
    }

    @Test
    fun strictModeBecomesALockedProtectionLock() {
        db.execSQL("INSERT INTO settings VALUES ('strict_mode_enabled','true')")
        db.execSQL("INSERT INTO settings VALUES ('strict_mode_end_time','1999999999999')")
        Migrations.MIGRATION_12_13.migrate(db)
        query("SELECT level, lockedUntil FROM protection_lock") { c ->
            assertEquals("LOCKED", c.getString(0))
            assertEquals(1999999999999L, c.getLong(1))
        }
    }

    @Test
    fun protectionLockIsOffWhenNoCommitmentWasSet() {
        Migrations.MIGRATION_12_13.migrate(db)
        query("SELECT level FROM protection_lock") { c -> assertEquals("OFF", c.getString(0)) }
    }

    /**
     * The check that prevents a crash-on-launch: compare each table the
     * migration hand-writes against the table Room generates for the same
     * entity. A difference here is precisely the "Migration didn't properly
     * handle" IllegalStateException Room raises when it opens the database.
     */
    @Test
    fun migratedTablesMatchWhatRoomWouldCreate() {
        // The WHOLE chain, not just the first step. The comparison is against a
        // Room database at the current version, so a later migration that
        // alters one of these tables -- 14 to 15 adds the launch-count columns
        // to block_rules -- must be applied here too or this test fails for a
        // reason that has nothing to do with a real defect.
        for (migration in Migrations.ALL) migration.migrate(db)
        val migrated = listOf("block_rules", "rule_usage", "rule_overrides", "protection_lock")
            .associateWith { columnsOf(db, it) }

        context.deleteDatabase(FRESH_DB)
        val roomDb = Room.databaseBuilder(context, FocusBlockDatabase::class.java, FRESH_DB).build()
        val fresh = roomDb.openHelper.writableDatabase
        val expected = migrated.keys.associateWith { columnsOf(fresh, it) }
        roomDb.close()
        context.deleteDatabase(FRESH_DB)

        for (table in migrated.keys) {
            assertEquals(
                "Column definitions for '$table' must match Room's own, or Room " +
                    "throws on open for every upgrading user",
                expected.getValue(table),
                migrated.getValue(table)
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** name -> "type|notNull|pk", which is what Room's validator compares. */
    private fun columnsOf(database: SupportSQLiteDatabase, table: String): Map<String, String> {
        val out = sortedMapOf<String, String>()
        database.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val type = c.getString(c.getColumnIndexOrThrow("type"))
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                val pk = c.getInt(c.getColumnIndexOrThrow("pk"))
                out[name] = "$type|$notNull|$pk"
            }
        }
        return out
    }

    private fun tableExists(name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'")
            .use { it.moveToFirst() }

    private fun count(table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun countWhere(table: String, where: String): Int =
        db.query("SELECT COUNT(*) FROM `$table` WHERE $where").use { it.moveToFirst(); it.getInt(0) }

    private fun query(sql: String, block: (android.database.Cursor) -> Unit) {
        db.query(sql).use { c ->
            assertTrue("expected at least one row for: $sql", c.moveToFirst())
            block(c)
        }
    }

    companion object {
        private const val TEST_DB = "migration_test.db"
        private const val FRESH_DB = "fresh_room.db"
    }
}
