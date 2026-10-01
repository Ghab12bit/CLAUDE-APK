package com.focusblock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FocusBlockWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_BLOCKING -> {
                toggleBlocking(context)
            }
            ACTION_REFRESH -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                appWidgetIds?.forEach { appWidgetId ->
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
        }
    }

    private fun toggleBlocking(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = FocusBlockDatabase.getInstance(context)
                val dao = db.focusBlockDao()
                val activeSession = dao.getActiveQuickBlockSessionDirect()

                if (activeSession != null) {
                    // Widgets must obey the same persisted lock as the app.
                    if (!com.focusblock.app.blocking.BlockSessionStore.isLocked(db) &&
                        db.settingsDao().getValue("hard_mode_enabled") != "true" &&
                        (db.settingsDao().getValue("strict_mode_end_time")?.toLongOrNull() ?: 0) <= System.currentTimeMillis()) {
                        db.quickBlockSessionDao().update(activeSession.copy(isActive = false, endTime = System.currentTimeMillis()))
                    }
                } else {
                    // Start blocking with default apps
                    val saved = com.focusblock.app.blocking.QuickBlockPolicy.packages(db.settingsDao().getValue("quick_block_saved_apps").orEmpty())
                    val packages = saved.filter { !com.focusblock.app.blocking.BlockSessionStore.essential(context, db, it) }.joinToString(",")
                    if (packages.isNotEmpty()) {
                        val session = com.focusblock.app.database.entity.QuickBlockSession(
                            startTime = System.currentTimeMillis(),
                            endTime = System.currentTimeMillis() + 25 * 60000L,
                            blockedPackages = packages,
                            isActive = true
                        )
                        dao.insertQuickBlockSession(session)
                    }
                }

                com.focusblock.app.service.AppBlockingService.update(context)

                // Refresh widget
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val widgetComponent = android.content.ComponentName(context, FocusBlockWidget::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
                appWidgetIds.forEach { appWidgetId ->
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_BLOCKING = "com.focusblock.app.widget.TOGGLE_BLOCKING"
        const val ACTION_REFRESH = "com.focusblock.app.widget.REFRESH"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = FocusBlockDatabase.getInstance(context)
                    val dao = db.focusBlockDao()
                    val activeSession = dao.getActiveQuickBlockSessionDirect()
                    val isBlocking = activeSession != null
                    val blockedCount = activeSession?.blockedPackages?.split(",")
                        ?.filter { it.isNotBlank() }?.size ?: 0

                    val views = RemoteViews(context.packageName, R.layout.widget_focus_block)

                    // Update status
                    views.setTextViewText(
                        R.id.widget_status,
                        if (isBlocking) "Blocking Active" else "Not Blocking"
                    )

                    views.setTextViewText(
                        R.id.widget_apps_count,
                        if (isBlocking) "$blockedCount apps blocked" else "Tap to start"
                    )

                    views.setTextViewText(
                        R.id.widget_button,
                        if (isBlocking) "Stop" else "Start"
                    )

                    // Toggle action
                    val toggleIntent = Intent(context, FocusBlockWidget::class.java).apply {
                        action = ACTION_TOGGLE_BLOCKING
                    }
                    val togglePendingIntent = PendingIntent.getBroadcast(
                        context, 0, toggleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_button, togglePendingIntent)

                    // Open app action
                    val openAppIntent = Intent(context, MainActivity::class.java)
                    val openAppPendingIntent = PendingIntent.getActivity(
                        context, 0, openAppIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_container, openAppPendingIntent)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    }
}
