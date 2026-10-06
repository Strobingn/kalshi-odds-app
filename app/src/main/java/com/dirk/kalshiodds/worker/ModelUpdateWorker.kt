package com.dirk.kalshiodds.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.prediction.LatestModelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Automatic edge-model refresh for the KIMI-Bitcoin branch.
 *
 * The training workflow republishes the `edge-model-KIMI-Bitcoin` release on a
 * schedule; this worker polls that release every ~6 hours and activates the new
 * model ONLY if the same honesty gate used by the manual "Check latest model"
 * button approves it (beats market holdout with verified settlement
 * provenance). A gate rejection is a normal outcome — the app keeps the
 * current model, never a worse one.
 */
class ModelUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = runCatching { KalshiOddsApp.from(applicationContext).container }
            .getOrElse { return@withContext Result.success() } // app not up; skip quietly
        val token = runCatching { container.extraSecrets.githubToken }.getOrNull()
        val outcome = runCatching { LatestModelClient().download(token) }
            .getOrElse { null }
            ?: return@withContext retryOrFail()
        when (outcome) {
            is LatestModelClient.Outcome.Ready -> {
                val fetch = outcome.fetch
                if (fetch.decision.activate) {
                    runCatching {
                        container.importedModel.activate(fetch.model, fetch.manifest)
                        container.scoring.edgeModel = fetch.model
                    }
                }
                Result.success()
            }
            // Missing release / missing token is a configuration state, not a
            // transient error — succeed and wait for the next period.
            is LatestModelClient.Outcome.NeedsAuth -> Result.success()
            is LatestModelClient.Outcome.Failed -> retryOrFail()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount + 1 >= MAX_RUN_ATTEMPTS) Result.failure() else Result.retry()

    companion object {
        const val UNIQUE = "bitcoin_kimi_model_update"
        const val MAX_RUN_ATTEMPTS = 3
        private const val INTERVAL_HOURS = 6L

        fun enqueuePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<ModelUpdateWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}
