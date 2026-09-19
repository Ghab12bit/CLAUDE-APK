package com.focusblock.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusblock.app.blocking.RuleAlarmScheduler
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.utils.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores blocking after a reboot.
 *
 * Alarms do not survive a reboot, so every rule boundary must be re-armed
 * here or a schedule would silently fail to start until the app was next
 * opened. Rules themselves are read from the database, so enforcement is
 * correct from the moment a service runs, whether or not this succeeds.
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appContext = context.applicationContext
        val pending = goAsync()

        scope.launch {
            try {
                val db = FocusBlockDatabase.getDatabase(appContext)
                val ruleDao = db.blockRuleDao()

                // Tidy anything that expired while the device was off.
                val now = System.currentTimeMillis()
                ruleDao.pruneOverridesBefore(now)
                for (rule in ruleDao.getAllRulesSync()) {
                    val until = rule.activeUntil
                    if (rule.isManualActive && until != null && now >= until) {
                        ruleDao.update(
                            rule.copy(isManualActive = false, activeUntil = null, updatedAt = now)
                        )
                    }
                }

                RuleAlarmScheduler.rescheduleAll(appContext, ruleDao.getAllRulesSync())

                if (PermissionUtils.getPermissionStatus(appContext).hasRequiredPermissions) {
                    AppBlockingService.start(appContext)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to restore blocking after boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
