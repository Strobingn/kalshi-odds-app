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
import com.dirk.kalshiodds.signal.service.LiveSignalsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class MarketRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * 0.3.79 ANR fix: all work (prefs, keystore, network, disk) runs on IO with a hard time cap, never on the
     * main thread. The live-signals service is only nudged (fire and forget, guarded against double starts);
     * the worker never waits for it.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { if (!LiveSignalsService.isRunning) LiveSignalsKeepAlive.ensureService(applicationContext) }
        // 0.3.84: never run two refreshes at once (periodic + a re-delivered job after process death).
        if (!running.compareAndSet(false, true)) return@withContext Result.success()
        try {
            withTimeoutOrNull(MAX_RUN_MS) { refresh() } ?: Result.retry()
        } finally {
            running.set(false)
        }
    }

    private suspend fun refresh(): Result {
        return try {
            // 0.3.84 memory: reuse the app's repository when it is already built (no second market cache,
            // scorer and HTTP client per run); otherwise build a lightweight one here, on IO.
            val shared = (applicationContext as? com.dirk.kalshiodds.KalshiOddsApp)?.containerOrNull?.repository
            val snapshot = (shared ?: run {
                val extras = SecureExtraStore(applicationContext)
                val settings = SignalPreferences(applicationContext, extras = extras).hydrate()
                MarketRepository(
                    applicationContext,
                    api = NetworkModule.publicApi(settings.kalshiDemoEnabled)
                )
            }).refresh()
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
        const val MAX_RUN_MS = 60_000L
        internal val running = java.util.concurrent.atomic.AtomicBoolean(false)
        /** 0.3.84: KEEP, so app start never replaces/re-runs an in-flight or scheduled refresh. */
        val POLICY: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
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
            MarketRefreshWorker.POLICY,
            request
        )
    }
}
