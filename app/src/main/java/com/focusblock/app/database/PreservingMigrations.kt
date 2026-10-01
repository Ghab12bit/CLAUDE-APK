package com.focusblock.app.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Versions 13–15 added tables but retained every v12 table. Preserve those tables,
 * including profiles, rules and usage history, rather than downgrading or dropping data. */
object PreservingMigrations {
    val ALL: Array<Migration> = (12..15).map { old -> object : Migration(old, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No v12 columns change in v16. Room validates them before opening.
            // Extra tables remain readable by ImportedRuleStore and are never deleted.
        }
    } }.toTypedArray()
}
