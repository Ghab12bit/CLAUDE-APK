package com.focusblock.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.focusblock.app.core.AppGraph
import com.focusblock.app.worker.DailySummaryWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch

@HiltAndroidApp
class FocusBlockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.get(this)
        graph.notifier.createChannels()
        registerPackageChanges(graph)
        DailySummaryWorker.schedule(this)
        graph.scope.launch {
            graph.essentials.ensureSeeded()
            graph.tick("app_start")
        }
    }

    /** Package add/remove broadcasts only reach runtime receivers on Android 8+; caches also expire on their own. */
    private fun registerPackageChanges(graph: AppGraph) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                graph.apps.invalidate()
                graph.safety.invalidate()
                graph.policy.invalidate()
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    companion object {
        // Legacy channel id, kept only until the old Home screen is removed.
        const val CHANNEL_MINDFUL_REMINDER = "fb_reminders"
    }
}
