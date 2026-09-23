package com.dirk.kalshiodds.signal.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sync mirror of the Live-signals toggle plus start/stop helpers.
 * DataStore is async; the UI-foreground path must know immediately whether
 * to promote a foreground service. Application.onCreate must not start an FGS.
 */
object LiveSignalsKeepAlive {
    private const val TAG = "DipHunterKeepAlive"
    private val uiInForeground = AtomicBoolean(false)

    fun isUiInForeground(): Boolean = uiInForeground.get()

    fun markUiInForeground(foreground: Boolean) {
        uiInForeground.set(foreground)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(LiveSignalsPolicy.PREFS_ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(LiveSignalsPolicy.PREFS_ENABLED_KEY, enabled).commit()
    }

    /**
     * Safe FGS start. Never throws — [android.app.ForegroundServiceStartNotAllowedException]
     * and OEM failures are caught so the UI process stays up.
     */
    fun startService(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, LiveSignalsService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                app.startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForegroundService failed: ${t.javaClass.simpleName}: ${t.message}")
            enqueueSoon(app)
        }
    }

    fun stopService(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, LiveSignalsService::class.java).apply {
            action = LiveSignalsPolicy.ACTION_STOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                app.startService(intent)
            }
        } catch (_: Throwable) {
            runCatching { app.stopService(Intent(app, LiveSignalsService::class.java)) }
        }
    }

    /** Start only when the user left Live signals on. Never throws. */
    fun ensureService(context: Context) {
        if (isEnabled(context)) startService(context)
    }

    /**
     * UI-visible start. Preferred over [ensureService] from Application.onCreate.
     */
    fun ensureServiceFromUi(context: Context) {
        markUiInForeground(true)
        if (LiveSignalsPolicy.shouldPromoteFromUiForeground(isEnabled(context))) {
            startService(context)
        }
    }

    fun enqueueWatchdogs(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<LiveSignalsWatchdogWorker>(
            LiveSignalsPolicy.WATCHDOG_PERIOD_MINUTES,
            TimeUnit.MINUTES
        ).setConstraints(constraints).build()
        wm.enqueueUniquePeriodicWork(
            LiveSignalsWatchdogWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
    }

    fun enqueueSoon(context: Context) {
        if (!isEnabled(context)) return
        val req = OneTimeWorkRequestBuilder<LiveSignalsWatchdogWorker>()
            .setInitialDelay(LiveSignalsPolicy.WATCHDOG_SOON_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            LiveSignalsWatchdogWorker.SOON_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            LiveSignalsPolicy.PREFS_NAME,
            Context.MODE_PRIVATE
        )
}

/** Safety net: re-start the FGS after OEM kills or a missed START_STICKY. */
class LiveSignalsWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // WorkManager is a background start — KeepAlive swallows FGS-not-allowed.
        LiveSignalsKeepAlive.ensureService(applicationContext)
        return Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "diphunter_live_signals_watchdog"
        const val SOON_NAME = "diphunter_live_signals_watchdog_soon"
    }
}
