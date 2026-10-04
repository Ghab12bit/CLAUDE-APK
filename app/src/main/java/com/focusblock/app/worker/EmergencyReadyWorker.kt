package com.focusblock.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.focusblock.app.core.AppGraph
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.FrictionPolicy
import java.util.concurrent.TimeUnit

/**
 * Tells the user when the 10-minute emergency wait is over, so a request is not forgotten. The
 * request stays usable until midnight ([FrictionPolicy.emergencyUsableUntil]).
 */
class EmergencyReadyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pkg = inputData.getString(KEY_PKG) ?: return Result.success()
        val graph = AppGraph.get(applicationContext)
        // Drops a request whose block is already over, so it is never announced as ready.
        val pending = graph.overrides.pendingEmergency(pkg) ?: return Result.success()
        val now = graph.clock.now()
        if (pending.status != UnlockEventEntity.PENDING || now < pending.readyAt) return Result.success()
        val until = FrictionPolicy.emergencyUsableUntil(pending.readyAt, graph.clock.zone())
        if (now >= until) return Result.success()
        graph.notifier.showEmergencyReady(pkg, pending.appName, until)
        return Result.success()
    }

    companion object {
        private const val KEY_PKG = "pkg"
        private fun name(pkg: String) = "emergency_ready_$pkg"

        fun schedule(context: Context, pkg: String, readyAt: Long) {
            val delay = (readyAt - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<EmergencyReadyWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_PKG to pkg))
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(name(pkg), ExistingWorkPolicy.REPLACE, request) }
        }

        fun cancel(context: Context, pkg: String) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(name(pkg)) }
            AppGraph.get(context).notifier.cancelEmergencyReady(pkg)
        }
    }
}
