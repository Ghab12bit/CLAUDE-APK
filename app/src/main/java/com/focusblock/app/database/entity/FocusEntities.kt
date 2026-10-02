package com.focusblock.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Tables added in database version 17. Each concept from spec 8.4 has its own table so that a
 * temporary session selection is never stored as a permanent blocklist.
 */

/**
 * One immediate block (Timed, Intervals or Until I stop). The app selection is copied into the row
 * at start. Only one row may be active; [com.focusblock.app.session.SessionManager] enforces that
 * inside a transaction.
 */
@Entity(tableName = "block_sessions")
data class BlockSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Comma-separated package names copied from the picker at start. */
    val packages: String,
    /** [com.focusblock.app.policy.SessionType] name. */
    val sessionType: String,
    /** [com.focusblock.app.policy.Strength] name. */
    val strength: String,
    val intention: String? = null,
    val startedAt: Long,
    /** Absolute deadline including extensions; null for Until I stop. */
    val plannedEndAt: Long? = null,
    val focusMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val rounds: Int = 1,
    /** SystemClock.elapsedRealtime() at start, with [bootCount], for the trusted clock. */
    val startElapsedRealtime: Long = 0,
    /** Settings.Global.BOOT_COUNT at start; -1 when unknown (e.g. migrated sessions). */
    val bootCount: Int = -1,
    val extendedMinutes: Int = 0,
    val isActive: Boolean = true,
    val endedAt: Long? = null,
    /** COMPLETED, ENDED_EARLY, LEGACY. */
    val endReason: String? = null,
    /** [com.focusblock.app.policy.SessionOutcome] name; null until answered or marked unanswered. */
    val outcome: String? = null,
    val outcomeAt: Long? = null,
    /** Source row in the legacy quick_block_sessions table, for migrated history. */
    val legacySessionId: Long? = null,
)

/** Apps that can never be blocked (spec 8.4 "Essential apps"). */
@Entity(tableName = "essential_apps")
data class EssentialApp(
    @PrimaryKey
    val packageName: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** True for entries FocusBlock seeded (phone, messages, clock); false when the user added it. */
    val isDefault: Boolean = false,
)

/** "App limit": a shared daily allowance for chosen apps. */
@Entity(tableName = "app_limits")
data class AppLimitEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val packages: String,
    val minutesPerDay: Int,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Where a migrated limit came from: app_time_limits or app_timer_settings. */
    val legacySource: String? = null,
)

/**
 * Emergency access and "Open anyway" log, and the source of active per-app overrides.
 * status: PENDING (emergency wait running), GRANTED (open until [expiresAt]), CANCELLED.
 */
@Entity(tableName = "unlock_events")
data class UnlockEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    /** [com.focusblock.app.policy.OverrideKind] name. */
    val kind: String,
    val status: String,
    val requestedAt: Long,
    val readyAt: Long,
    val grantedAt: Long? = null,
    val expiresAt: Long? = null,
    /** Required for emergency access. */
    val reasonText: String? = null,
    val strength: String,
    val reasonType: String? = null,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val sessionId: Long? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val GRANTED = "GRANTED"
        const val CANCELLED = "CANCELLED"
    }
}

/** Every recommendation shown, applied or dismissed (spec 8.6). */
@Entity(tableName = "recommendations")
data class RecommendationEntity(
    @PrimaryKey
    val signature: String,
    val kind: String,
    /** Proposal parameters as JSON. */
    val payload: String,
    val status: String,
    val firstShownAt: Long,
    val lastShownAt: Long,
    val actedAt: Long? = null,
    val snoozedUntil: Long = 0,
) {
    companion object {
        const val SHOWN = "SHOWN"
        const val APPLIED = "APPLIED"
        const val DISMISSED = "DISMISSED"
    }
}

/** Troubleshooting log: suspected clock tampering, permission loss, service restarts. */
@Entity(tableName = "diagnostic_events")
data class DiagnosticEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val kind: String,
    val detail: String = "",
)
