package com.dirk.kalshiodds.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.supabase.PaperScalpCloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Android WorkManager is inexact; thirty minutes is a minimum cadence while
 * the phone has network, with an immediate coalesced run after ledger changes.
 */
class PaperScalpSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = runCatching { KalshiOddsApp.from(applicationContext).container }
            .getOrElse { return@withContext Result.retry() }
        val status = runCatching {
            PaperScalpCloudSync(applicationContext).upload(container.paper.book.snapshot())
        }.getOrElse {
            PaperScalpCloudSync.Status(false, "Paper scalp cloud sync failed: worker error", System.currentTimeMillis())
        }
        runCatching { container.dataPrefs.updateSyncStatus(status.message, status.atMs) }
        if (status.ok) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "paper_scalp_secure_sync_30m"
        private const val ONCE = "paper_scalp_secure_sync_once"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PaperScalpSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<PaperScalpSyncWorker>()
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
