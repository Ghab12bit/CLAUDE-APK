package com.focusblock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.focusblock.app.R
import com.focusblock.app.blocking.RuleAlarmScheduler
import com.focusblock.app.blocking.RuleTemplates
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.RuleKind
import com.focusblock.app.service.FocusBlockAccessibilityService
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

    /**
     * Start or stop a real block.
     *
     * This used to write a QuickBlockSession row. Nothing has enforced that
     * table since blocking was unified behind BlockRule -- BlockingEngine reads
     * block_rules and the allowlist, and nothing else -- so the widget toggled
     * a value no part of the app consulted. It reported "Blocking Active" and
     * blocked nothing at all.
     *
     * It now creates and retires the same MANUAL rule the home screen's "Block
     * now" uses, which is the thing that actually blocks.
     */
    private fun toggleBlocking(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = FocusBlockDatabase.getInstance(context)
                val ruleDao = db.blockRuleDao()
                val now = System.currentTimeMillis()
                val running = ruleDao.getAllRulesSync().firstOrNull { it.manualIsRunning(now) }

                if (running != null) {
                    ruleDao.update(
                        running.copy(isManualActive = false, activeUntil = null, updatedAt = now)
                    )
                } else {
                    // Same apps the user's routines already cover, so the
                    // widget never has to ask a question it has no screen for.
                    val packages = ruleDao.getAllRulesSync()
                        .filter { it.isEnabled }
                        .flatMap { it.packageList() }
                        .distinct()
                    if (packages.isNotEmpty()) {
                        ruleDao.insert(RuleTemplates.blockNow(packages, null))
                    }
                }

                // The engine caches the rule set for a couple of seconds and
                // the alarm for a timed end has to exist, so both are refreshed
                // exactly as the in-app path does it.
                RuleAlarmScheduler.rescheduleAll(context, ruleDao.getAllRulesSync())
                FocusBlockAccessibilityService.recheckNow()

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
                    val now = System.currentTimeMillis()
                    val rules = db.blockRuleDao().getAllRulesSync()

                    // What the widget reports must be what the engine would
                    // actually enforce: a manual session running now, or a
                    // scheduled routine inside its window.
                    val active = rules.filter {
                        it.isEnabled && (it.manualIsRunning(now) ||
                            (it.kind == RuleKind.AUTOMATIC && it.hasTimeCondition &&
                                it.timeConditionMatches()))
                    }
                    val isBlocking = active.isNotEmpty()
                    val blockedCount = active.flatMap { it.packageList() }.distinct().size

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
