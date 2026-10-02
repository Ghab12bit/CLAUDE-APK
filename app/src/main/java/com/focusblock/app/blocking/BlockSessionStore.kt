package com.focusblock.app.blocking

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import androidx.room.withTransaction
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.QuickBlockSession
import org.json.JSONObject

/** Metadata lives in the existing Room settings table: no destructive schema replacement. */
data class BlockSessionMetadata(
    val id: Long = 0, val strict: Boolean = false, val focus: Int = 0,
    val rest: Int = 5, val rounds: Int = 1, val budget: Int = 1,
    val used: Int = 0, val exceptionPackage: String = "", val exceptionUntil: Long = 0
) {
    fun json(): String = JSONObject().put("id", id).put("strict", strict).put("focus", focus)
        .put("rest", rest).put("rounds", rounds).put("budget", budget).put("used", used)
        .put("package", exceptionPackage).put("until", exceptionUntil).toString()
    companion object {
        fun parse(value: String?): BlockSessionMetadata = runCatching {
            val j = JSONObject(value ?: "{}")
            BlockSessionMetadata(j.optLong("id"), j.optBoolean("strict"), j.optInt("focus"),
                j.optInt("rest", 5), j.optInt("rounds", 1), j.optInt("budget", 1),
                j.optInt("used"), j.optString("package"), j.optLong("until"))
        }.getOrDefault(BlockSessionMetadata())
    }
}

object BlockSessionStore {
    const val KEY = "blocking_first_session_v1"
    const val LAST = "blocking_first_last_v1"
    const val REMINDER = "blocking_first_reminder"
    suspend fun metadata(db: FocusBlockDatabase) = BlockSessionMetadata.parse(db.settingsDao().getValue(KEY))
    suspend fun isLocked(db: FocusBlockDatabase): Boolean {
        val s = db.quickBlockSessionDao().getActiveSessionSync() ?: return false
        val m = metadata(db)
        return m.id == s.id && m.strict && !QuickBlockPolicy.isExpired(s.endTime, System.currentTimeMillis())
    }
    suspend fun blocks(db: FocusBlockDatabase, session: QuickBlockSession, pkg: String, now: Long): Boolean {
        if (pkg !in QuickBlockPolicy.packages(session.blockedPackages) || QuickBlockPolicy.isExpired(session.endTime, now)) return false
        val m = metadata(db)
        if (m.id != session.id) return true // Legacy sessions retain their existing semantics.
        return BlockSessionPolicy.phase(session.startTime, session.endTime, now, m.focus, m.rest, m.rounds).blocking &&
            !BlockSessionPolicy.exceptionApplies(pkg, m.exceptionPackage, m.exceptionUntil, now)
    }
    suspend fun grantOnce(db: FocusBlockDatabase, pkg: String): Boolean = db.withTransaction {
        val s = db.quickBlockSessionDao().getActiveSessionSync() ?: return@withTransaction false
        val now = System.currentTimeMillis()
        val m = metadata(db)
        if (m.id != s.id || m.used >= m.budget || pkg !in QuickBlockPolicy.packages(s.blockedPackages) ||
            QuickBlockPolicy.isExpired(s.endTime, now)) return@withTransaction false
        if (!blocks(db, s, pkg, now)) return@withTransaction false
        db.settingsDao().insert(AppSettings(KEY, m.copy(used = m.used + 1, exceptionPackage = pkg,
            exceptionUntil = BlockSessionPolicy.exceptionEnd(now, s.endTime)).json()))
        true
    }
    fun requiredPackages(context: Context): Set<String> = buildSet {
        add(context.packageName)
        addAll(listOf("com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer",
            "com.android.systemui", "com.android.settings"))
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let(::add)
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName?.let(::add)
    }
    suspend fun essential(context: Context, db: FocusBlockDatabase, pkg: String): Boolean =
        pkg in requiredPackages(context) || db.blockedAppDao().getBlockedApp(pkg)?.isInAllowlist == true ||
            db.essentialAppWhitelistDao().isWhitelisted(pkg)
}
