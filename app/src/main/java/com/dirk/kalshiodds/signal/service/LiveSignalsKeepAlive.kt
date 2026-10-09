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
    /**
     * 0.3.41: the FGS is never started during app startup. It starts only after the Activity has drawn its
     * first frame (plus [FIRST_FRAME_START_DELAY_MS]) so the main thread is free to run Service.onCreate →
     * startForeground within Android's start window (ForegroundServiceDidNotStartInTimeException fix).
     */
    private val firstFrameDrawn = AtomicBoolean(false)
    private val firstFrameScheduled = AtomicBoolean(false)
    const val FIRST_FRAME_START_DELAY_MS = 1_500L

    fun isFirstFrameDrawn(): Boolean = firstFrameDrawn.get()

    /** Test hook. */
    internal fun resetFirstFrameForTest() { firstFrameDrawn.set(false); firstFrameScheduled.set(false) }

    /**
     * Call from Activity.onCreate. Waits for the first drawn frame, then starts the service from the
     * main looper after a short delay. Idempotent per process.
     */
    fun startAfterFirstFrame(activity: android.app.Activity) {
        if (firstFrameDrawn.get() || !firstFrameScheduled.compareAndSet(false, true)) {
            if (firstFrameDrawn.get()) ensureServiceFromUi(activity)
            return
        }
        val app = activity.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val decor = runCatching { activity.window.decorView }.getOrNull()
        val fire = Runnable {
            main.postDelayed({ onFirstFrameDrawn(app) }, FIRST_FRAME_START_DELAY_MS)
        }
        if (decor == null) {
            main.postDelayed(fire, FIRST_FRAME_START_DELAY_MS)
            return
        }
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            private val done = AtomicBoolean(false)
            override fun onDraw() {
                if (!done.compareAndSet(false, true)) return
                // Can't remove an OnDrawListener inside onDraw — post the removal.
                main.post { runCatching { decor.viewTreeObserver.removeOnDrawListener(this) } }
                main.post(fire)
            }
        }
        runCatching { decor.viewTreeObserver.addOnDrawListener(listener) }
            .onFailure { main.postDelayed(fire, FIRST_FRAME_START_DELAY_MS) }
    }

    /** First frame is on screen: from now on UI-foreground starts go straight through. */
    fun onFirstFrameDrawn(context: Context) {
        firstFrameDrawn.set(true)
        if (uiInForeground.get()) ensureServiceFromUi(context)
    }

    fun isUiInForeground(): Boolean = uiInForeground.get()

    fun markUiInForeground(foreground: Boolean) {
        uiInForeground.set(foreground)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(LiveSignalsPolicy.PREFS_ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(LiveSignalsPolicy.PREFS_ENABLED_KEY, enabled).commit()
    }

    fun isTimeoutPaused(context: Context): Boolean =
        prefs(context).getBoolean(LiveSignalsPolicy.PREFS_TIMEOUT_PAUSED_KEY, false)

    fun setTimeoutPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(LiveSignalsPolicy.PREFS_TIMEOUT_PAUSED_KEY, paused).commit()
    }

    /**
     * Safe FGS start. Never throws — [android.app.ForegroundServiceStartNotAllowedException]
     * and OEM failures are caught so the UI process stays up.
     */
    fun startService(context: Context): Boolean {
        val app = context.applicationContext
        val intent = Intent(app, LiveSignalsService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                app.startService(intent)
            }
            true
        } catch (t: Throwable) {
            // Android 12+: ForegroundServiceStartNotAllowedException from a background start.
            // Fall back to a WorkManager retry with backoff; never crash the process.
            Log.w(TAG, "startForegroundService failed: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { enqueueSoon(app) }
            false
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

    /** Start only when the user left Live signals on. Never throws. @return false when a start was attempted and refused. */
    fun ensureService(context: Context): Boolean {
        if (!LiveSignalsPolicy.shouldStartFromBackground(isEnabled(context), isTimeoutPaused(context))) {
            return true
        }
        return startService(context)
    }

    /**
     * UI-visible start. Preferred over [ensureService] from Application.onCreate.
     * Clears an Android 15 dataSync timeout pause — bringing the app to the
     * foreground resets the 6h timer if we had to fall back to dataSync.
     */
    fun ensureServiceFromUi(context: Context) {
        markUiInForeground(true)
        // 0.3.41: never during startup — startAfterFirstFrame() calls back once the first frame is drawn.
        if (!firstFrameDrawn.get()) return
        setTimeoutPaused(context, false)
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
        if (!LiveSignalsPolicy.shouldStartFromBackground(isEnabled(context), isTimeoutPaused(context))) {
            return
        }
        val req = OneTimeWorkRequestBuilder<LiveSignalsWatchdogWorker>()
            .setInitialDelay(LiveSignalsPolicy.WATCHDOG_SOON_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
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
        // WorkManager is a background start — KeepAlive swallows FGS-not-allowed (Android 12+).
        // 0.3.41: a refused start retries with exponential backoff (bounded), instead of giving up.
        val ok = runCatching { LiveSignalsKeepAlive.ensureService(applicationContext) }.getOrDefault(false)
        return if (ok || runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
    }

    companion object {
        const val PERIODIC_NAME = "diphunter_live_signals_watchdog"
        const val SOON_NAME = "diphunter_live_signals_watchdog_soon"
        const val MAX_RETRIES = 5
    }
}
