package com.focusblock.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 8.5: legacy Strict Mode and both Hard Mode models map onto the two strengths, with real
 * legacy data fixtures, and any active legacy lock is preserved until it expires.
 */
@RunWith(AndroidJUnit4::class)
class Migration17Test {
    private val db = "migration-17-test"
    private val now = 1_790_000_000_000L
    private val min = 60_000L

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FocusBlockDatabase::class.java)

    private fun SupportSQLiteDatabase.setting(key: String, value: String) =
        execSQL("INSERT OR REPLACE INTO settings (`key`, `value`) VALUES (?, ?)", arrayOf<Any>(key, value))

    private fun SupportSQLiteDatabase.string(sql: String, vararg args: Any): String? =
        query(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun SupportSQLiteDatabase.int(sql: String, vararg args: Any): Int =
        query(sql, args).use { it.moveToFirst(); it.getInt(0) }

    private fun legacyFixtures(v16: SupportSQLiteDatabase, runningSession: Boolean) {
        // A finished and (optionally) a running quick block, plus recovery-branch metadata.
        v16.execSQL("INSERT INTO quick_block_sessions (id, startTime, endTime, blockedPackages, isActive, isPomodoroSession, pomodoroWorkMinutes, pomodoroBreakMinutes, previouslyBlockedPackages) VALUES (1, ?, ?, 'com.old.app', 0, 0, 25, 5, '')", arrayOf<Any>(now - 120 * min, now - 60 * min))
        if (runningSession) {
            v16.execSQL("INSERT INTO quick_block_sessions (id, startTime, endTime, blockedPackages, isActive, isPomodoroSession, pomodoroWorkMinutes, pomodoroBreakMinutes, previouslyBlockedPackages) VALUES (2, ?, NULL, 'com.instagram.android,com.reddit.frontpage', 1, 0, 25, 5, '')", arrayOf<Any>(now - 5 * min))
            v16.setting(PrefKeys.LEGACY_SESSION_META, """{"id":2,"strict":false,"focus":0,"rest":5,"rounds":1,"budget":1,"used":0,"package":"com.instagram.android","until":${now + 2 * min}}""")
        }
        // Legacy Strict Mode, active for another 45 minutes.
        v16.setting("strict_mode_enabled", "true")
        v16.setting("strict_mode_end_time", (now + 45 * min).toString())
        // Legacy Hard Mode A with a plaintext PIN.
        v16.setting("hard_mode_enabled", "true")
        v16.setting("hard_mode_pin", "1234")
        // Allowlist, essential whitelist, per-app limit, shared App Timer and global limit with Hard Mode B.
        v16.execSQL("INSERT INTO blocked_apps (packageName, appName, isBlocked, isInAllowlist, blockedCount, totalBlockedCount, lastBlockedTime, addedTime) VALUES ('com.whatsapp', 'WhatsApp', 0, 1, 0, 0, 0, 1)")
        v16.execSQL("INSERT INTO essential_apps_whitelist (packageName, appName, isDefault, addedAt) VALUES ('com.google.android.apps.maps', 'Maps', 1, 1)")
        v16.execSQL("INSERT INTO app_time_limits (packageName, appName, dailyLimitMinutes, isEnabled, warningThreshold, resetTime, createdAt, updatedAt) VALUES ('com.youtube', 'YouTube', 30, 1, 5, 0, 1, 1)")
        v16.execSQL("INSERT INTO app_timer_settings (id, isEnabled, dailyLimitMinutes, escalationThresholdMinutes, timerApps, createdAt, updatedAt) VALUES (1, 1, 40, 20, 'com.tiktok,com.snapchat', 1, 1)")
        v16.execSQL("INSERT INTO global_daily_limit_settings (id, isEnabled, dailyLimitMinutes, warningMinutesBefore, trackedPackages, useAppTimerApps, whitelistedPackages, isHardModeEnabled, hardModeLockUntil, hardModeUnlockRequestedAt, hardModeCooldownMinutes, hardModeRequirePhrase, hardModeUnlockPhrase, createdAt, updatedAt) VALUES (1, 1, 120, 15, 'com.netflix', 1, 'com.snapchat', 1, ?, 0, 15, 1, 'x', 1, 1)", arrayOf<Any>(now + 60 * min))
        // A historical attempt.
        v16.execSQL("INSERT INTO block_logs (packageName, appName, timestamp, blockedBy) VALUES ('com.old.app', 'Old', ?, 'QUICK_BLOCK')", arrayOf<Any>(now - 90 * min))
    }

    @Test fun legacyDataMapsOntoTheTwoStrengths() {
        helper.createDatabase(db, 16).use { legacyFixtures(it, runningSession = true) }
        val v17 = helper.runMigrationsAndValidate(db, 17, true, Migration16To17 { now })

        // The running open-ended block becomes Strict Lock until the legacy Strict end time.
        assertEquals(1, v17.int("SELECT COUNT(*) FROM block_sessions WHERE isActive = 1"))
        assertEquals("STRICT", v17.string("SELECT strength FROM block_sessions WHERE isActive = 1"))
        assertEquals("TIMED", v17.string("SELECT sessionType FROM block_sessions WHERE isActive = 1"))
        assertEquals((now + 45 * min).toString(), v17.string("SELECT plannedEndAt FROM block_sessions WHERE isActive = 1"))
        assertEquals("com.instagram.android,com.reddit.frontpage", v17.string("SELECT packages FROM block_sessions WHERE isActive = 1"))
        // History is kept, legacy rows are retired.
        assertEquals("LEGACY", v17.string("SELECT endReason FROM block_sessions WHERE legacySessionId = 1"))
        assertEquals(0, v17.int("SELECT COUNT(*) FROM quick_block_sessions WHERE isActive = 1"))
        assertEquals(2, v17.int("SELECT COUNT(*) FROM quick_block_sessions"))
        // The one-time exception survives as an emergency override (block is Strict) until its expiry.
        assertEquals("EMERGENCY", v17.string("SELECT kind FROM unlock_events WHERE packageName = 'com.instagram.android'"))
        assertEquals((now + 2 * min).toString(), v17.string("SELECT expiresAt FROM unlock_events"))
        // Legacy Strict Mode is inert, Hard Mode A became the default strength and its PIN is gone.
        assertEquals("false", v17.string("SELECT value FROM settings WHERE `key` = 'strict_mode_enabled'"))
        assertEquals("STRICT", v17.string("SELECT value FROM settings WHERE `key` = ?", PrefKeys.DEFAULT_STRENGTH))
        assertNull(v17.string("SELECT value FROM settings WHERE `key` = 'hard_mode_pin'"))
        // Hard Mode B lock is kept for the engine.
        assertEquals((now + 60 * min).toString(), v17.string("SELECT hardModeLockUntil FROM global_daily_limit_settings"))
        // Essentials, limits, daily-limit counted set.
        assertEquals(2, v17.int("SELECT COUNT(*) FROM essential_apps WHERE packageName IN ('com.whatsapp','com.google.android.apps.maps')"))
        assertEquals(2, v17.int("SELECT COUNT(*) FROM app_limits"))
        assertEquals("com.tiktok,com.snapchat", v17.string("SELECT packages FROM app_limits WHERE legacySource = 'app_timer_settings'"))
        assertEquals("com.netflix,com.tiktok", v17.string("SELECT trackedPackages FROM global_daily_limit_settings"))
        assertEquals(0, v17.int("SELECT countsAllApps FROM global_daily_limit_settings"))
        // Attempt log gained structured columns without losing rows.
        assertEquals(0, v17.int("SELECT attemptNumber FROM block_logs"))
        assertEquals(1, v17.int("SELECT COUNT(*) FROM diagnostic_events WHERE kind = 'MIGRATION_17'"))
    }

    @Test fun legacyStrictWithoutABlockContinuesAsAStrictBlockOfDailyLimitApps() {
        helper.createDatabase(db, 16).use { legacyFixtures(it, runningSession = false) }
        val v17 = helper.runMigrationsAndValidate(db, 17, true, Migration16To17 { now })
        assertEquals("STRICT", v17.string("SELECT strength FROM block_sessions WHERE isActive = 1"))
        assertEquals("com.netflix,com.tiktok", v17.string("SELECT packages FROM block_sessions WHERE isActive = 1"))
        assertEquals((now + 45 * min).toString(), v17.string("SELECT plannedEndAt FROM block_sessions WHERE isActive = 1"))
    }

    @Test fun expiredOrPausedStrictModeIsNotCarriedOver() {
        helper.createDatabase(db, 16).use {
            it.setting("strict_mode_enabled", "true")
            it.setting("strict_mode_end_time", "0")
        }
        val v17 = helper.runMigrationsAndValidate(db, 17, true, Migration16To17 { now })
        assertEquals(0, v17.int("SELECT COUNT(*) FROM block_sessions"))
        assertFalse(v17.string("SELECT value FROM settings WHERE `key` = ?", PrefKeys.MIGRATION_17_REPORT).isNullOrBlank())
        assertTrue(v17.string("SELECT value FROM settings WHERE `key` = 'strict_mode_enabled'") == "false")
    }
}
