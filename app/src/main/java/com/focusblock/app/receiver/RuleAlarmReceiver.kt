package com.focusblock.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusblock.app.blocking.RuleAlarmScheduler
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.service.FocusBlockAccessibilityService
import com.focusblock.app.utils.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes the app when a rule's window opens or closes.
 *
 * Its job is housekeeping, not enforcement: it tidies expired manual rules and
 * stale overrides, refreshes the notification, and arms the next boundary.
 * Enforcement itself reads the rules directly, so a missed alarm cannot leave
 * an app wrongly blocked.
 */
class RuleAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RULE_BOUNDARY) return

        val ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)
        val appContext = context.applicationContext
        val pending = goAsync()

        scope.launch {
            try {
                val db = FocusBlockDatabase.getDatabase(appContext)
                val ruleDao = db.blockRuleDao()
                val now = System.currentTimeMillis()

                // Retire a manual rule whose time is up, so it stops appearing
                // as running in the UI. Enforcement already treats it as over.
                if (ruleId > 0) {
                    ruleDao.getRule(ruleId)?.let { rule ->
                        val until = rule.activeUntil
                        if (rule.isManualActive && until != null && now >= until) {
                            ruleDao.update(
                                rule.copy(isManualActive = false, activeUntil = null, updatedAt = now)
                            )
                            Log.i(TAG, "Retired expired manual rule ${rule.name}")
                        }
                    }
                }

                ruleDao.pruneOverridesBefore(now)
                RuleAlarmScheduler.rescheduleAll(appContext, ruleDao.getAllRulesSync())

                // The boundary itself must enforce, not just tidy up.
                //
                // A window opening at 20:45 finds the user already inside the
                // app it is meant to block -- they were scrolling at 20:44 and
                // never switched app, so the accessibility service received no
                // event. Poking the live service here makes the rule bite at
                // the moment it starts instead of whenever the user next
                // happens to switch app.
                FocusBlockAccessibilityService.recheckNow()

                if (PermissionUtils.getPermissionStatus(appContext).hasRequiredPermissions) {
                    AppBlockingService.update(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle rule boundary for $ruleId", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "RuleAlarmReceiver"
        const val ACTION_RULE_BOUNDARY = "com.focusblock.app.action.RULE_BOUNDARY"
        const val EXTRA_RULE_ID = "rule_id"
    }
}
