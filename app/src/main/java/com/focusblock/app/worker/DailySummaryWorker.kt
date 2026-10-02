package com.focusblock.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.Notifier
import com.focusblock.app.policy.PolicyTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Daily summary notification at about 9 PM (Settings › Notifications, off by default). It uses the
 * same sources as Activity: UsageStats for screen time and block_logs for blocked attempts.
 */
class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        // Save complete days before Android drops its detailed events (about 7–10 days).
        runCatching { graph.history.persistRecent() }
        if (!graph.notifier.enabled(Notifier.Kind.SUMMARY)) return Result.success()
        val now = graph.clock.now()
        val exempt = graph.history.notCounted()
        val totals = graph.usage.todayTotals(0)
        val attempts = graph.db.attemptDao().since(PolicyTime.startOfDay(now, graph.clock.zone())).size
        val ctx = applicationContext
        val title = if (totals == null) ctx.getString(R.string.data_unavailable)
        else ctx.getString(R.string.notif_summary_title, Fmt.duration(ctx, totals.filterKeys { it !in exempt }.values.sum()))
        val text = ctx.resources.getQuantityString(R.plurals.attempts_count, attempts, attempts)
        graph.notifier.showSummary(title, text)
        return Result.success()
    }

    companion object {
        private const val NAME = "daily_summary"
        private val LEGACY = listOf("daily_insights_notification", "PeakTimeReminder")

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            LEGACY.forEach { wm.cancelUniqueWork(it); wm.cancelAllWorkByTag(it) }
            val now = ZonedDateTime.now()
            var next = now.withHour(21).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
            val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
