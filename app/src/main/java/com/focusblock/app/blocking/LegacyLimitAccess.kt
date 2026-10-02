package com.focusblock.app.blocking

import androidx.room.withTransaction
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.BlockedByType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Retains existing limit-specific access without bypassing sessions or schedules. */
object LegacyLimitAccess {
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    suspend fun available(db: FocusBlockDatabase, type: BlockedByType, pkg: String): Boolean {
        return when (type) {
            BlockedByType.APP_TIMER -> {
                val settings = db.appTimerSettingsDao().getSettingsSync()
                val usage = db.appTimerDailyUsageDao().getUsageForDateSync(today())
                settings?.isEnabled == true && pkg in QuickBlockPolicy.packages(settings.timerApps) && usage != null && !usage.dailyOverrideUsed
            }
            BlockedByType.GLOBAL_LIMIT -> {
                val usage = db.globalDailyUsageDao().getUsageForDateSync(today())
                db.globalDailyLimitSettingsDao().getSettingsSync()?.isEnabled == true && usage != null &&
                    (usage.overrideCooldownUntil ?: 0) <= System.currentTimeMillis()
            }
            else -> false
        }
    }
    suspend fun grant(db: FocusBlockDatabase, type: BlockedByType, pkg: String): Boolean = db.withTransaction {
        if (!available(db, type, pkg)) return@withTransaction false
        val now = System.currentTimeMillis()
        when (type) {
            BlockedByType.APP_TIMER -> db.appTimerDailyUsageDao().activateOverride(today(), now + 15 * 60000L)
            BlockedByType.GLOBAL_LIMIT -> db.globalDailyUsageDao().activateOverride(today(), now, now + 5 * 60000L, now + 15 * 60000L)
            else -> return@withTransaction false
        }
        true
    }
}
