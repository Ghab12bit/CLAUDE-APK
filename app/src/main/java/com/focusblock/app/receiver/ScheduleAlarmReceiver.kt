package com.focusblock.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusblock.app.service.AppBlockingService

class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SCHEDULE_START -> {
                AppBlockingService.update(context)
            }
            ACTION_SCHEDULE_END -> {
                AppBlockingService.update(context)
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE_START = "com.focusblock.app.action.SCHEDULE_START"
        const val ACTION_SCHEDULE_END = "com.focusblock.app.action.SCHEDULE_END"
    }
}
