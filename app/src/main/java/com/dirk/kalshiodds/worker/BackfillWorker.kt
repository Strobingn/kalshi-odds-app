package com.dirk.kalshiodds.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.backfill.CoinbaseSpotBackfill
import com.dirk.kalshiodds.data.backfill.HistoryTransport
import com.dirk.kalshiodds.data.backfill.KalshiBackfillEngine
import com.dirk.kalshiodds.data.backfill.OkHttpHistoryTransport
import com.dirk.kalshiodds.data.local.archive.ArchiveJobs
import com.dirk.kalshiodds.data.local.archive.BackfillCursorRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackfillWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val container = runCatching { KalshiOddsApp.from(app).container }.getOrElse {
            return@withContext Result.failure(workDataOf(KEY_MSG to (it.message ?: "no app")))
        }
        val archive = container.archive
        val days = inputData.getInt(KEY_DAYS, 30).coerceIn(1, 180)
        val includeSpot = inputData.getBoolean(KEY_SPOT, true)
        val minClose = System.currentTimeMillis() - days * 86_400_000L
        val engine = KalshiBackfillEngine(OkHttpHistoryTransport.kalshi())
        val cutoff = runCatching { engine.cutoffMs() }.getOrNull()
        var processed = 0
        var imported = 0
        try {
            for (series in KalshiBackfillEngine.SERIES) {
                if (isStopped) return@withContext cancelResult("cancelled")
                val jobKey = "${ArchiveJobs.KALSHI}:$series"
                var cursor = archive.readCursor(jobKey)
                    ?: BackfillCursorRow(job = jobKey, series = series, minCloseMs = minClose, days = days, status = "running")
                if (cursor.status == "done" && (cursor.minCloseMs ?: 0L) <= minClose) {
                    continue
                }
                if ((cursor.minCloseMs ?: minClose) > minClose) {
                    cursor = cursor.copy(minCloseMs = minClose, cursor = null, lastTicker = null, status = "running")
                }
                var historicalPass = false
                var guard = 0
                while (guard++ < 80) {
                    if (isStopped) return@withContext cancelResult("cancelled")
                    val useHist = historicalPass || (cutoff != null && (cursor.minCloseMs ?: minClose) < cutoff)
                    val step = engine.step(
                        series = series,
                        cursor = cursor,
                        minCloseMs = minClose,
                        historical = useHist,
                        cancel = { isStopped }
                    )
                    archive.upsertSettled(step.settled)
                    archive.insertPricePath(step.path)
                    archive.writeCursor(step.cursor)
                    cursor = step.cursor
                    processed = step.progress.processed
                    imported += step.progress.imported
                    setProgress(
                        workDataOf(
                            KEY_MSG to step.progress.message,
                            KEY_PROCESSED to processed,
                            KEY_SERIES to series
                        )
                    )
                    if (step.progress.cancelled) return@withContext cancelResult("cancelled")
                    if (step.progress.done) {
                        if (!historicalPass && cutoff != null && minClose < cutoff) {
                            historicalPass = true
                            cursor = cursor.copy(cursor = null, lastTicker = null, status = "running")
                            continue
                        }
                        break
                    }
                }
            }
            if (includeSpot) {
                val spot = CoinbaseSpotBackfill(OkHttpHistoryTransport.coinbase())
                for (series in KalshiBackfillEngine.SERIES) {
                    if (isStopped) return@withContext cancelResult("cancelled")
                    val product = spot.productForSeries(series)
                    val rows = runCatching { spot.candles(product, minClose, System.currentTimeMillis()) }
                        .getOrElse { emptyList() }
                    archive.insertSpotCandles(rows)
                    setProgress(workDataOf(KEY_MSG to "spot $product +${rows.size}", KEY_PROCESSED to processed))
                }
            }
            Result.success(workDataOf(KEY_MSG to "Backfill finished · $imported windows", KEY_PROCESSED to processed))
        } catch (e: HistoryTransport.Cancelled) {
            cancelResult("cancelled")
        } catch (e: Exception) {
            // Retry transient failures, but stop after MAX_RUN_ATTEMPTS so a
            // permanent error fails visibly instead of looping forever.
            if (runAttemptCount + 1 >= MAX_RUN_ATTEMPTS) {
                Result.failure(workDataOf(KEY_MSG to (e.message ?: "backfill failed")))
            } else {
                Result.retry()
            }
        }
    }

    private fun cancelResult(msg: String) =
        Result.success(workDataOf(KEY_MSG to msg, KEY_CANCELLED to true))

    companion object {
        const val UNIQUE = "diphunter-backfill"
        const val KEY_DAYS = "days"
        const val KEY_SPOT = "spot"
        const val KEY_MSG = "msg"
        const val KEY_PROCESSED = "processed"
        const val KEY_SERIES = "series"
        const val KEY_CANCELLED = "cancelled"
        const val MAX_RUN_ATTEMPTS = 3

        fun enqueue(context: Context, days: Int, includeSpot: Boolean = true) {
            val req = OneTimeWorkRequestBuilder<BackfillWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(workDataOf(KEY_DAYS to days, KEY_SPOT to includeSpot))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}
