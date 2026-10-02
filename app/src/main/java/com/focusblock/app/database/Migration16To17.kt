package com.focusblock.app.database

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.focusblock.app.database.entity.GlobalDailyLimitSettings
import org.json.JSONObject

/**
 * Version 16 → 17. Additive only: new tables and nullable/defaulted columns. No v12–16 table or row
 * is dropped. Legacy data is mapped as follows (also recorded in docs/implementation-log.md):
 *
 *  Sessions
 *   - Every quick_block_sessions row is copied into block_sessions. Finished rows become history
 *     (endReason LEGACY, outcome not recorded). The newest still-running row becomes the active block.
 *   - The recovery branch's metadata (strict, focus/break/rounds, one-time exception) is applied to
 *     that active block: strict → Strict Lock; focus > 0 with 2+ rounds → Intervals; an unexpired
 *     exception becomes a granted override until its original expiry.
 *   - Legacy quick_block_sessions rows are then marked inactive so no old code path reads them.
 *
 *  Strict Mode (settings strict_mode_*)
 *   - Active with a future end time and not paused: the lock is preserved until that end. If a block
 *     is running it becomes Strict Lock (an open-ended block gets the Strict end time as its end);
 *     otherwise a Strict block of the daily-limit apps runs until the original end time.
 *   - Active with no end time (end 0, the pause bug) or paused: not preserved, recorded.
 *   - Keys are then cleared so the legacy mode is inert.
 *
 *  Hard Mode A (settings hard_mode_*, PIN + time lock): becomes "Default block strength: Strict Lock".
 *   The plaintext PIN is deleted.
 *  Hard Mode B (global_daily_limit_settings hardMode*): kept in place; while hardModeLockUntil is in
 *   the future the daily limit is enforced as Strict Lock.
 *
 *  Essential apps: blocked_apps.isInAllowlist rows and essential_apps_whitelist rows.
 *  App limits: each app_time_limits row (one app) and the shared app_timer_settings list.
 *  Daily limit: the legacy counted set (tracked ∪ App Timer apps when shared, minus
 *   "tracked but not blocked") is written out as an explicit list; empty → the default distracting
 *   apps (the closest equivalent of the old keyword detection).
 */
class Migration16To17(private val clock: () -> Long = System::currentTimeMillis) : Migration(16, 17) {

    override fun migrate(db: SupportSQLiteDatabase) {
        CREATE_STATEMENTS.forEach(db::execSQL)
        ALTER_STATEMENTS.forEach { addColumnIfMissing(db, it) }
        LegacyMapper(db, clock()).run()
    }

    /** Idempotent ALTER: a database that already has the column (re-run or partial copy) is left as is. */
    private fun addColumnIfMissing(db: SupportSQLiteDatabase, statement: String) {
        val match = ALTER_PATTERN.find(statement) ?: error("Unexpected ALTER statement: $statement")
        val (table, column) = match.destructured
        val exists = db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            var found = false
            while (c.moveToNext()) if (c.getString(nameIndex) == column) found = true
            found
        }
        if (!exists) db.execSQL(statement)
    }

    companion object {
        val CREATE_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `block_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packages` TEXT NOT NULL, `sessionType` TEXT NOT NULL, `strength` TEXT NOT NULL, `intention` TEXT, `startedAt` INTEGER NOT NULL, `plannedEndAt` INTEGER, `focusMinutes` INTEGER NOT NULL, `breakMinutes` INTEGER NOT NULL, `rounds` INTEGER NOT NULL, `startElapsedRealtime` INTEGER NOT NULL, `bootCount` INTEGER NOT NULL, `extendedMinutes` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `endedAt` INTEGER, `endReason` TEXT, `outcome` TEXT, `outcomeAt` INTEGER, `legacySessionId` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `essential_apps` (`packageName` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
            "CREATE TABLE IF NOT EXISTS `app_limits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `packages` TEXT NOT NULL, `minutesPerDay` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `legacySource` TEXT)",
            "CREATE TABLE IF NOT EXISTS `unlock_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `requestedAt` INTEGER NOT NULL, `readyAt` INTEGER NOT NULL, `grantedAt` INTEGER, `expiresAt` INTEGER, `reasonText` TEXT, `strength` TEXT NOT NULL, `reasonType` TEXT, `ruleId` INTEGER, `ruleName` TEXT, `sessionId` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `recommendations` (`signature` TEXT NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `status` TEXT NOT NULL, `firstShownAt` INTEGER NOT NULL, `lastShownAt` INTEGER NOT NULL, `actedAt` INTEGER, `snoozedUntil` INTEGER NOT NULL, PRIMARY KEY(`signature`))",
            "CREATE TABLE IF NOT EXISTS `diagnostic_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `kind` TEXT NOT NULL, `detail` TEXT NOT NULL)",
        )

        private val ALTER_PATTERN = Regex("ALTER TABLE `([^`]+)` ADD COLUMN `([^`]+)`")

        val ALTER_STATEMENTS = listOf(
            "ALTER TABLE `block_logs` ADD COLUMN `attemptNumber` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `block_logs` ADD COLUMN `action` TEXT",
            "ALTER TABLE `block_logs` ADD COLUMN `sessionId` INTEGER",
            "ALTER TABLE `block_logs` ADD COLUMN `strength` TEXT",
            "ALTER TABLE `bedtime_mode_settings` ADD COLUMN `strength` TEXT NOT NULL DEFAULT 'NORMAL'",
            "ALTER TABLE `global_daily_limit_settings` ADD COLUMN `countsAllApps` INTEGER NOT NULL DEFAULT 0",
        )
    }

    private class LegacyMapper(private val db: SupportSQLiteDatabase, private val now: Long) {
        private val report = StringBuilder()

        fun run() {
            val settings = readSettings()
            migrateEssentials()
            migrateAppLimits()
            val counted = migrateDailyLimit()
            migrateSessions(settings, counted)
            migrateHardModeA(settings)
            setSetting(PrefKeys.MIGRATION_17_REPORT, report.toString().trim())
            db.execSQL(
                "INSERT INTO diagnostic_events (time, kind, detail) VALUES (?, 'MIGRATION_17', ?)",
                arrayOf<Any>(now, report.toString().trim()),
            )
        }

        private fun readSettings(): Map<String, String> = buildMap {
            db.query("SELECT `key`, `value` FROM settings").use { c ->
                while (c.moveToNext()) put(c.getString(0), c.getString(1) ?: "")
            }
        }

        private fun setSetting(key: String, value: String) =
            db.execSQL("INSERT OR REPLACE INTO settings (`key`, `value`) VALUES (?, ?)", arrayOf<Any>(key, value))

        private fun deleteSetting(key: String) = db.execSQL("DELETE FROM settings WHERE `key` = ?", arrayOf<Any>(key))

        private fun migrateEssentials() {
            db.execSQL("INSERT OR IGNORE INTO essential_apps (packageName, addedAt, isDefault) SELECT packageName, addedTime, 0 FROM blocked_apps WHERE isInAllowlist = 1")
            db.execSQL("INSERT OR IGNORE INTO essential_apps (packageName, addedAt, isDefault) SELECT packageName, addedAt, isDefault FROM essential_apps_whitelist")
            val count = db.query("SELECT COUNT(*) FROM essential_apps").use { it.moveToFirst(); it.getInt(0) }
            report.appendLine("Essential apps carried over: $count.")
        }

        private fun migrateAppLimits() {
            db.execSQL(
                "INSERT INTO app_limits (name, packages, minutesPerDay, isEnabled, createdAt, updatedAt, legacySource) " +
                    "SELECT appName, packageName, dailyLimitMinutes, isEnabled, createdAt, updatedAt, 'app_time_limits' FROM app_time_limits",
            )
            db.execSQL(
                "INSERT INTO app_limits (name, packages, minutesPerDay, isEnabled, createdAt, updatedAt, legacySource) " +
                    "SELECT 'Shared app limit', timerApps, dailyLimitMinutes, isEnabled, createdAt, updatedAt, 'app_timer_settings' FROM app_timer_settings WHERE timerApps != ''",
            )
            val count = db.query("SELECT COUNT(*) FROM app_limits").use { it.moveToFirst(); it.getInt(0) }
            report.appendLine("App limits carried over: $count.")
        }

        /** Returns the explicit counted set for the daily limit (empty when there is no daily limit row). */
        private fun migrateDailyLimit(): List<String> {
            val row = db.query("SELECT trackedPackages, useAppTimerApps, whitelistedPackages FROM global_daily_limit_settings WHERE id = 1").use { c ->
                if (!c.moveToFirst()) null else Triple(c.getString(0) ?: "", c.getInt(1) == 1, c.getString(2) ?: "")
            } ?: return emptyList()
            val timerApps = if (row.second) {
                db.query("SELECT timerApps FROM app_timer_settings WHERE id = 1").use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
            } else ""
            var counted = (csv(row.first) + csv(timerApps)).distinct() - csv(row.third).toSet()
            if (counted.isEmpty()) counted = GlobalDailyLimitSettings.DEFAULT_DISTRACTING_APPS
            db.execSQL(
                "UPDATE global_daily_limit_settings SET trackedPackages = ?, useAppTimerApps = 0, whitelistedPackages = '' WHERE id = 1",
                arrayOf<Any>(counted.joinToString(",")),
            )
            report.appendLine("Daily limit now counts ${counted.size} listed apps.")
            return counted
        }

        private data class Legacy(val id: Long, val start: Long, val end: Long?, val packages: String, val active: Boolean)

        private fun migrateSessions(settings: Map<String, String>, dailyLimitApps: List<String>) {
            val rows = db.query("SELECT id, startTime, endTime, blockedPackages, isActive FROM quick_block_sessions ORDER BY startTime ASC").use { c ->
                buildList {
                    while (c.moveToNext()) add(Legacy(c.getLong(0), c.getLong(1), c.longOrNull(2), c.getString(3) ?: "", c.getInt(4) == 1))
                }
            }
            val meta = runCatching { JSONObject(settings[PrefKeys.LEGACY_SESSION_META] ?: "{}") }.getOrDefault(JSONObject())
            val strictEnd = settings["strict_mode_end_time"]?.toLongOrNull() ?: 0L
            val strictOn = settings["strict_mode_enabled"] == "true"
            val strictPaused = settings["strict_mode_paused"] == "true"
            val legacyStrictActive = strictOn && !strictPaused && strictEnd > now
            if (strictOn && !legacyStrictActive) {
                report.appendLine("Legacy Strict Mode was on without a future end time (or paused); it was not carried over.")
            }

            val running = rows.filter { it.active && (it.end == null || it.end > now) }.maxByOrNull { it.start }
            for (r in rows) {
                if (r == running) continue
                insertSession(r.packages, if (r.end == null) "INDEFINITE" else "TIMED", "NORMAL", r.start, r.end,
                    0, 0, 1, active = false, endedAt = r.end ?: r.start, endReason = "LEGACY", legacyId = r.id)
            }

            if (running != null) {
                val metaApplies = meta.optLong("id", -1L) == running.id
                var strict = (metaApplies && meta.optBoolean("strict")) || legacyStrictActive
                var end = running.end
                val focus = if (metaApplies) meta.optInt("focus") else 0
                val rounds = if (metaApplies) meta.optInt("rounds", 1) else 1
                val rest = if (metaApplies) meta.optInt("rest", 5) else 5
                var type = when {
                    focus > 0 && rounds > 1 && end != null -> "INTERVALS"
                    end == null -> "INDEFINITE"
                    else -> "TIMED"
                }
                if (strict && end == null) {
                    if (legacyStrictActive) { end = strictEnd; type = "TIMED" } else strict = false
                }
                val id = insertSession(running.packages, type, if (strict) "STRICT" else "NORMAL", running.start, end,
                    if (type == "INTERVALS") focus else 0, if (type == "INTERVALS") rest else 0, if (type == "INTERVALS") rounds else 1,
                    active = true, endedAt = null, endReason = null, legacyId = running.id)
                report.appendLine("Running block carried over as ${if (strict) "Strict Lock" else "Normal"} ($type).")

                val exPkg = if (metaApplies) meta.optString("package") else ""
                val exUntil = if (metaApplies) meta.optLong("until") else 0L
                if (exPkg.isNotBlank() && exUntil > now) {
                    db.execSQL(
                        "INSERT INTO unlock_events (packageName, appName, kind, status, requestedAt, readyAt, grantedAt, expiresAt, reasonText, strength, reasonType, sessionId) " +
                            "VALUES (?, ?, ?, 'GRANTED', ?, ?, ?, ?, 'Carried over from the previous version', ?, 'SESSION', ?)",
                        arrayOf<Any>(exPkg, exPkg, if (strict) "EMERGENCY" else "OPEN_ANYWAY", now, now, now, exUntil, if (strict) "STRICT" else "NORMAL", id),
                    )
                }
            } else if (legacyStrictActive && dailyLimitApps.isNotEmpty()) {
                insertSession(dailyLimitApps.joinToString(","), "TIMED", "STRICT", now, strictEnd, 0, 0, 1,
                    active = true, endedAt = null, endReason = null, legacyId = null)
                report.appendLine("Legacy Strict Mode continues as a Strict block of the daily-limit apps until its original end.")
            }

            db.execSQL("UPDATE quick_block_sessions SET isActive = 0")
            setSetting("strict_mode_enabled", "false")
            setSetting("strict_mode_end_time", "0")
            setSetting("strict_mode_paused", "false")
        }

        private fun migrateHardModeA(settings: Map<String, String>) {
            if (settings["hard_mode_enabled"] == "true") {
                setSetting(PrefKeys.DEFAULT_STRENGTH, "STRICT")
                report.appendLine("Hard Mode (PIN) is now the default strength: Strict Lock. The stored PIN was deleted.")
            }
            setSetting("hard_mode_enabled", "false")
            deleteSetting("hard_mode_pin")
        }

        private fun insertSession(
            packages: String, type: String, strength: String, start: Long, end: Long?,
            focus: Int, rest: Int, rounds: Int, active: Boolean, endedAt: Long?, endReason: String?, legacyId: Long?,
        ): Long {
            db.execSQL(
                "INSERT INTO block_sessions (packages, sessionType, strength, intention, startedAt, plannedEndAt, focusMinutes, breakMinutes, rounds, " +
                    "startElapsedRealtime, bootCount, extendedMinutes, isActive, endedAt, endReason, outcome, outcomeAt, legacySessionId) " +
                    "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, 0, -1, 0, ?, ?, ?, NULL, NULL, ?)",
                arrayOf(packages, type, strength, start, end, focus, rest, rounds, if (active) 1 else 0, endedAt, endReason, legacyId),
            )
            return db.query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }
        }

        private fun csv(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
    }
}
