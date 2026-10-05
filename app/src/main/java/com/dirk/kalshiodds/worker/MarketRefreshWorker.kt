package com.dirk.kalshiodds.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.repo.MarketRepository
import com.dirk.kalshiodds.signal.config.SecureExtraStore
import com.dirk.kalshiodds.signal.config.SignalPreferences
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import java.util.concurrent.TimeUnit

class MarketRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        LiveSignalsKeepAlive.ensureService(applicationContext)
        return try {
            val extras = SecureExtraStore(applicationContext)
            val settings = SignalPreferences(applicationContext, extras = extras).hydrate()
            val snapshot = MarketRepository(
                applicationContext,
                api = NetworkModule.publicApi(settings.kalshiDemoEnabled)
            ).refresh()
            if (snapshot.errorMessage != null && !snapshot.fromCache && snapshot.allMarkets.isEmpty()) {
                retryOrFail()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    /**
     * Retry transient failures, but stop after [MAX_RUN_ATTEMPTS] so a
     * permanent error (bad config, API change) fails visibly instead of
     * looping with backoff forever.
     */
    private fun retryOrFail(): Result =
        if (runAttemptCount + 1 >= MAX_RUN_ATTEMPTS) Result.failure() else Result.retry()

    companion object {
        const val UNIQUE_NAME = "kalshi_market_refresh_15m"
        const val MAX_RUN_ATTEMPTS = 3
    }
}

object MarketRefreshScheduler {
    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Background only: PeriodicWorkRequest minimum is 15 minutes on Android.
        // Foreground UI polls ~750ms (500–1000ms) in OddsViewModel.
        val request = PeriodicWorkRequestBuilder<MarketRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            MarketRefreshWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
