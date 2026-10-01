package com.focusblock.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.utils.PermissionUtils

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            // Check if we have required permissions before starting service
            val permissionStatus = PermissionUtils.getPermissionStatus(context)
            if (permissionStatus.hasRequiredPermissions) {
                try {
                    AppBlockingService.start(context)
                } catch (e: IllegalStateException) {
                    android.util.Log.w("FocusBlockBoot", "Android deferred background start; accessibility or opening FocusBlock restores monitoring", e)
                } catch (e: SecurityException) {
                    android.util.Log.w("FocusBlockBoot", "Permission changed before service restart", e)
                }
            }
        }
    }
}
