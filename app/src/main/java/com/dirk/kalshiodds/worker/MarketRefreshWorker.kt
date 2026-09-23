package com.dirk.kalshiodds.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dirk.kalshiodds.data.repo.MarketRepository
import java.util.concurrent.TimeUnit

class MarketRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val snapshot = MarketRepository(applicationContext).refresh()
            if (snapshot.errorMessage != null && !snapshot.fromCache && snapshot.allMarkets.isEmpty()) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "kalshi_market_refresh_15m"
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
