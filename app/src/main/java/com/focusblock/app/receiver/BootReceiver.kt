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
                AppBlockingService.start(context)
            }
        }
    }
}
