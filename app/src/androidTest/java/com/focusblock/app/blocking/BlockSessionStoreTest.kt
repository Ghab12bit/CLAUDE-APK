package com.focusblock.app.blocking

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.PreservingMigrations
import com.focusblock.app.database.entity.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun exceptionIsAtomicAndDoesNotModifyOtherPolicies() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusBlockDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            val id = db.quickBlockSessionDao().insert(QuickBlockSession(startTime = now, endTime = now + 600000, blockedPackages = "example.app"))
            db.settingsDao().insert(AppSettings(BlockSessionStore.KEY, BlockSessionMetadata(id = id, strict = true).json()))
            val rule = db.scheduleDao().insert(Schedule(name = "Other protection", startTimeMinutes = 0, endTimeMinutes = 1439, blockedPackages = "example.app"))
            val results = coroutineScope { (1..8).map { async(Dispatchers.IO) { BlockSessionStore.grantOnce(db, "example.app") } }.awaitAll() }
            assertEquals(1, results.count { it })
            assertEquals(1, BlockSessionStore.metadata(db).used)
            assertTrue(BlockSessionStore.isLocked(db))
            assertTrue(db.scheduleDao().getSchedule(rule)!!.isEnabled)
            assertFalse(BlockSessionStore.blocks(db, db.quickBlockSessionDao().getActiveSessionSync()!!, "example.app", now + 1000))
            assertTrue(BlockSessionStore.blocks(db, db.quickBlockSessionDao().getActiveSessionSync()!!, "example.app", now + 121000))
        } finally { db.close() }
    }
    @Test fun zeroBudgetCannotGrantAccess() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusBlockDatabase::class.java).build()
        try {
            val id = db.quickBlockSessionDao().insert(QuickBlockSession(startTime = 0, endTime = Long.MAX_VALUE, blockedPackages = "example.app"))
            db.settingsDao().insert(AppSettings(BlockSessionStore.KEY, BlockSessionMetadata(id = id, budget = 0).json()))
            assertFalse(BlockSessionStore.grantOnce(db, "example.app"))
        } finally { db.close() }
    }
    @Test fun legacyTimerAllowanceCannotBeSpentTwiceOrChangeSessionLock() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusBlockDatabase::class.java).build()
        try {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            db.appTimerSettingsDao().insert(AppTimerSettings(isEnabled = true, timerApps = "example.app"))
            db.appTimerDailyUsageDao().insert(AppTimerDailyUsage(date = date))
            val id = db.quickBlockSessionDao().insert(QuickBlockSession(startTime = System.currentTimeMillis(), endTime = Long.MAX_VALUE, blockedPackages = "example.app"))
            db.settingsDao().insert(AppSettings(BlockSessionStore.KEY, BlockSessionMetadata(id = id, strict = true, budget = 0).json()))
            assertTrue(LegacyLimitAccess.grant(db, BlockedByType.APP_TIMER, "example.app"))
            assertFalse(LegacyLimitAccess.grant(db, BlockedByType.APP_TIMER, "example.app"))
            assertTrue(BlockSessionStore.isLocked(db))
            assertTrue(BlockSessionStore.blocks(db, db.quickBlockSessionDao().getActiveSessionSync()!!, "example.app", System.currentTimeMillis()))
        } finally { db.close() }
    }
    @Test fun migrationsRetainSessionsRulesAndExtraTables() = runBlocking {
        for (version in 12..15) {
            val name = "migration-$version-${System.nanoTime()}.db"
            var db = Room.databaseBuilder(context, FocusBlockDatabase::class.java, name).build()
            val id = db.quickBlockSessionDao().insert(QuickBlockSession(startTime = 1, endTime = Long.MAX_VALUE, blockedPackages = "saved.app"))
            db.settingsDao().insert(AppSettings(BlockSessionStore.KEY, BlockSessionMetadata(id = id, used = 1).json()))
            db.openHelper.writableDatabase.execSQL("CREATE TABLE preserved_payload (value TEXT)")
            db.openHelper.writableDatabase.execSQL("INSERT INTO preserved_payload VALUES ('keep me')")
            db.openHelper.writableDatabase.version = version
            db.close()
            db = Room.databaseBuilder(context, FocusBlockDatabase::class.java, name)
                .addMigrations(*PreservingMigrations.ALL, com.focusblock.app.database.Migration16To17()).build()
            try {
                // v17 moves the running block into block_sessions and retires the legacy row.
                assertEquals("saved.app", db.blockSessionDao().active()!!.packages)
                assertNull(db.quickBlockSessionDao().getActiveSessionSync())
                assertEquals(1, BlockSessionStore.metadata(db).used)
                db.openHelper.readableDatabase.query("SELECT value FROM preserved_payload").use { c -> assertTrue(c.moveToFirst()); assertEquals("keep me", c.getString(0)) }
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
    @Test fun importedRuleEditingPreservesUnknownFieldsAndHonorsConfigurationLock() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusBlockDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE block_rules (id INTEGER PRIMARY KEY, name TEXT, packages TEXT, isEnabled INTEGER, kind TEXT, commitment TEXT, hasUsageCondition INTEGER, usageLimitMinutes INTEGER, usageWindow TEXT, updatedAt INTEGER, original_notes TEXT)")
            sql.execSQL("INSERT INTO block_rules VALUES (1, 'Combined', 'example.app', 1, 'SCHEDULE', 'OFF', 1, 30, 'DAILY', 0, 'preserve this')")
            val rule = ImportedRuleStore.rules(db).single()
            ImportedRuleStore.save(db, rule.copy(name = "Edited", minutes = 45))
            assertEquals(45, ImportedRuleStore.rules(db).single().minutes)
            sql.query("SELECT original_notes FROM block_rules").use { c -> assertTrue(c.moveToFirst()); assertEquals("preserve this", c.getString(0)) }
            sql.execSQL("CREATE TABLE protection_lock (id INTEGER PRIMARY KEY, lockedUntil INTEGER, level TEXT)")
            sql.execSQL("INSERT INTO protection_lock VALUES (1, ?, 'STRICT')", arrayOf(System.currentTimeMillis() + 60000))
            assertTrue(runCatching { ImportedRuleStore.save(db, rule.copy(minutes = 60)) }.isFailure)
            assertEquals(45, ImportedRuleStore.rules(db).single().minutes)
        } finally { db.close() }
    }
}
