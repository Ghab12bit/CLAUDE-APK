package com.focusblock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.BlockSetup
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.StartRequest
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import com.focusblock.app.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Home-screen widget. It reads the same persisted session as the app and starts or ends blocks
 * through [com.focusblock.app.core.SessionManager], so the two always agree and Strict Lock holds.
 * Open decision #5 (what the widget starts) is a setting: last block (default) or default block.
 */
class FocusBlockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        AppGraph.get(context).scope.launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val pending = goAsync()
        val graph = AppGraph.get(context)
        graph.scope.launch {
            try {
                val active = graph.sessions.active()
                if (active != null && !SessionClock.state(graph.sessions.toInput(active), graph.clock.now()).ended) {
                    // Ending early goes through the app's sheet (wait and hold); Strict Lock shows its lock there.
                    openApp(context, MainActivity.OPEN_END_EARLY)
                } else if (PermissionHealth.missingRequired(context) != null) {
                    openApp(context)
                } else {
                    val setup = widgetSetup(graph)
                    if (setup == null) openApp(context) else graph.sessions.start(StartRequest(setup))
                }
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun widgetSetup(graph: AppGraph): BlockSetup? {
        val last = lastOrChosen(graph) ?: return null
        if (graph.db.settingsDao().getValue(PrefKeys.WIDGET_ACTION) != "default") return last
        val s = graph.db.settingsDao()
        val type = SessionType.values().firstOrNull { it.name == s.getValue(PrefKeys.DEFAULT_TYPE) } ?: last.type
        val strength = Strength.values().firstOrNull { it.name == s.getValue(PrefKeys.DEFAULT_STRENGTH) } ?: last.strength
        return last.copy(
            type = type,
            minutes = s.getValue(PrefKeys.DEFAULT_MINUTES)?.toIntOrNull() ?: last.minutes,
            strength = if (type == SessionType.INDEFINITE) Strength.NORMAL else strength,
        )
    }

    private fun openApp(context: Context, open: String? = null) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { open?.let { putExtra(MainActivity.EXTRA_OPEN, it) } },
        )
    }

    companion object {
        const val ACTION_TOGGLE = "com.focusblock.app.WIDGET_TOGGLE"

        /** The last block, or before the first one, the apps chosen in onboarding or the picker. */
        private suspend fun lastOrChosen(graph: AppGraph): BlockSetup? =
            graph.sessions.lastSetup() ?: graph.sessions.savedSelection().takeIf { it.isNotEmpty() }?.let { BlockSetup(it) }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusBlockWidget::class.java))
            if (ids.isEmpty()) return
            AppGraph.get(context).scope.launch { render(context, manager, ids) }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val graph = AppGraph.get(context)
            val active = graph.sessions.active()
            val input = active?.let(graph.sessions::toInput)
            val running = input != null && !SessionClock.state(input, graph.clock.now()).ended
            val views = RemoteViews(context.packageName, R.layout.widget_focus_block)
            val ready = PermissionHealth.missingRequired(context) == null
            when {
                running -> {
                    views.setTextViewText(R.id.widget_status, input!!.plannedEndAt?.let { context.getString(R.string.widget_active, Fmt.time(context, it)) }
                        ?: context.getString(R.string.widget_active_open))
                    views.setTextViewText(R.id.widget_detail, context.resources.getQuantityString(R.plurals.apps_blocked_count, input.packages.size, input.packages.size))
                    views.setTextViewText(R.id.widget_button, context.getString(if (input.strength == Strength.STRICT) R.string.widget_strict else R.string.widget_end))
                }
                !ready -> {
                    views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_needs_setup))
                    views.setTextViewText(R.id.widget_detail, "")
                    views.setTextViewText(R.id.widget_button, context.getString(R.string.widget_setup))
                }
                else -> {
                    views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_idle))
                    val last = lastOrChosen(graph)
                    views.setTextViewText(R.id.widget_detail, last?.let { context.resources.getQuantityString(R.plurals.apps_count, it.packages.size, it.packages.size) } ?: "")
                    views.setTextViewText(R.id.widget_button, context.getString(if (last == null) R.string.widget_setup else R.string.widget_start))
                }
            }
            val strictRunning = running && input!!.strength == Strength.STRICT
            val click = if (strictRunning) {
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            } else if (running) {
                // "End early…" opens the app's end-early sheet (wait and hold) instead of ending here.
                PendingIntent.getActivity(context, 2,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_END_EARLY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getBroadcast(context, 0, Intent(context, FocusBlockWidget::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            views.setOnClickPendingIntent(R.id.widget_button, click)
            views.setOnClickPendingIntent(R.id.widget_container,
                PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(ids, views)
        }
    }
}
