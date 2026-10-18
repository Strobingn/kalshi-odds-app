package com.dirk.kalshiodds.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.MainActivity
import com.dirk.kalshiodds.R
import com.dirk.kalshiodds.ui.home.DailySummary
import com.dirk.kalshiodds.ui.home.HomeSummaryCopy
import com.dirk.kalshiodds.ui.home.HomeSummaryLoader
import com.dirk.kalshiodds.ui.home.HomeSummaryPrefs
import com.dirk.kalshiodds.ui.home.HomeSummarySchedule
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Paper-only daily summary at 11:59 PM ET. Reads the results store and posts a notification.
 * Does not place orders and does not read live-money state.
 */
class HomeSummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!HomeSummaryPrefs.isEnabled(ctx)) return@withContext Result.success()
        val day = inputData.getString(KEY_DAY)
        if (!day.isNullOrBlank()) {
            runCatching { postDay(ctx, day) }
        }
        runCatching { HomeSummaryScheduler.enqueueFollowing(ctx, System.currentTimeMillis()) }
        Result.success()
    }

    private fun postDay(context: Context, dayEt: String) {
        val app = KalshiOddsApp.from(context)
        val store = app.container.predictionLedger ?: return
        val settingsResult = runCatching { app.container.archive.recentSettingsChanges(HomeSummaryLoader.SETTINGS_LIMIT) }
        val summary = HomeSummaryLoader.loadDay(
            store,
            settingsResult.getOrDefault(emptyList()),
            dayEt,
            settingsTracked = settingsResult.isSuccess
        )
        HomeSummaryNotifier.post(context, summary)
    }

    companion object {
        const val KEY_DAY = "day_et"
        const val NAME_PREFIX = "kalshi_scalper_daily_summary"
    }
}

object HomeSummaryNotifier {
    const val CHANNEL = "kalshi_scalper_daily_summary"
    const val NOTIFICATION_ID = 1159

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "Daily paper summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Paper scalper P&L at 11:59 PM ET. No orders."
        }
        nm.createNotificationChannel(ch)
    }

    fun post(context: Context, summary: DailySummary) {
        ensureChannel(context)
        val text = HomeSummaryCopy.notificationBody(summary)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(HomeSummaryCopy.NOTIFY_TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}

object HomeSummaryScheduler {
    const val TAG = "kalshi_scalper_daily_summary"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setEnabled(context: Context, enabled: Boolean) {
        HomeSummaryPrefs.write(context, enabled)
        val app = context.applicationContext
        scope.launch { runCatching { sync(app) } }
    }

    fun sync(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val ctx = context.applicationContext
        if (!HomeSummaryPrefs.isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        runCatching { HomeSummaryNotifier.ensureChannel(ctx) }
        enqueueAt(ctx, HomeSummarySchedule.nextFire(nowMs), nowMs)
    }

    fun enqueueFollowing(context: Context, nowMs: Long) {
        val ctx = context.applicationContext
        if (!HomeSummaryPrefs.isEnabled(ctx)) return
        enqueueAt(ctx, HomeSummarySchedule.followingFire(nowMs), nowMs)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
    }

    private fun enqueueAt(context: Context, fire: ZonedDateTime, nowMs: Long) {
        val delay = java.time.Duration.between(java.time.Instant.ofEpochMilli(nowMs), fire.toInstant()).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<HomeSummaryWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(HomeSummaryWorker.KEY_DAY to fire.toLocalDate().toString()))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "${HomeSummaryWorker.NAME_PREFIX}-${fire.toLocalDate()}",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
