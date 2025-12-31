package com.focusblock.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.service.FocusBlockAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receiver for extending App Timer limit from notification action
 */
class AppTimerExtendReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppTimerExtendReceiver"
        private const val EXTEND_MINUTES = 15
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FocusBlockAccessibilityService.ACTION_EXTEND_APP_TIMER) return

        Log.i(TAG, "Extending App Timer by $EXTEND_MINUTES minutes")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = FocusBlockDatabase.getDatabase(context)
                val settings = database.appTimerSettingsDao().getSettingsSync()

                if (settings != null) {
                    val newLimit = settings.dailyLimitMinutes + EXTEND_MINUTES
                    database.appTimerSettingsDao().setDailyLimit(newLimit)

                    // Show confirmation toast on main thread
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(
                            context,
                            "App Timer extended to ${newLimit} minutes",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    Log.i(TAG, "App Timer extended from ${settings.dailyLimitMinutes} to $newLimit minutes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extend App Timer", e)
            }
        }
    }
}
