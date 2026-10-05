package com.dirk.kalshiodds

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import com.dirk.kalshiodds.signal.service.LiveSignalsService
import com.dirk.kalshiodds.worker.MarketRefreshScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KalshiOddsApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "appScope", t)
        }
    )

    override fun onCreate() {
        super.onCreate()
        runCatching { CrashBreadcrumb.install(this) }
        container = AppContainer(this)
        runCatching { container.lastOrderError.clearStaleLifecycleNotice() }
        HeavyMlGuard.persistHook = { reason ->
            // SharedPreferences.apply() only — do not launch a coroutine
            // here. The confirmed 0.3.0 death was CancellableContinuationImpl
            // after the 256MB heap was already gone.
            runCatching { container.oomFlag.setDisabled(reason) }
        }
        if (container.oomFlag.isDisabled()) {
            HeavyMlGuard.disableForSession(
                container.oomFlag.reason() ?: "persisted OOM flag",
                persist = false
            )
            appScope.launch {
                runCatching {
                    container.preferences.updateHeavyMl(false)
                    container.preferences.updateExtendedAi(false)
                }
            }
        }
        runCatching { HeavyMlGuard.applyCrashHintIfNeeded() }
        runCatching { SignalNotifier.ensureChannels(this) }
        runCatching { com.dirk.kalshiodds.signal.notify.OpportunityNotifier.ensureChannel(this) }
        runCatching { com.dirk.kalshiodds.signal.lastminute.LastMinuteNotifier.ensureChannel(this) }
        runCatching { com.dirk.kalshiodds.worker.SyncWorker.enqueuePeriodic(this) }
        // Do NOT start the FGS here. Application.onCreate is often still treated
        // as a background start (ForegroundServiceStartNotAllowedException) and
        // a throw in Service.onCreate kills the whole process mid-session too
        // (START_STICKY / DataStore collector / watchdog).
        if (LiveSignalsPolicy.shouldPromoteFromApplicationOnCreate()) {
            LiveSignalsKeepAlive.ensureService(this)
        }
        runCatching { MarketRefreshScheduler.enqueue(this) }
        runCatching { LiveSignalsKeepAlive.enqueueWatchdogs(this) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                LiveSignalsKeepAlive.ensureServiceFromUi(this@KalshiOddsApp)
            }

            override fun onStop(owner: LifecycleOwner) {
                LiveSignalsKeepAlive.markUiInForeground(false)
                runCatching { container.endSession() }
            }
        })
        appScope.launch {
            runCatching { checkKashiUpdateIfDue() }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching { refreshPublishedEdgeModel() }
        }
        appScope.launch {
            runCatching { container.preferences.applySafeLightDefaultsIfNeeded() }
            runCatching { container.preferences.applyPaperBankrollReset0328IfNeeded(container.paper.book) }
            runCatching { container.preferences.applyLastMinuteStakeIfNeeded() }
            runCatching { restorePersistedHistory() }
            runCatching { firstLaunchSync() }
        }
        appScope.launch {
            runCatching {
                container.preferences.settings
                    .map { it.liveSignalsEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        LiveSignalsKeepAlive.setEnabled(this@KalshiOddsApp, enabled)
                        if (enabled && LiveSignalsKeepAlive.isUiInForeground()) {
                            LiveSignalsKeepAlive.ensureServiceFromUi(this@KalshiOddsApp)
                        }
                        // Off: the running service observes DataStore and stopSelfs.
                    }
            }
        }
    }

    private fun refreshPublishedEdgeModel() {
        val token = runCatching { container.extraSecrets.githubToken }.getOrNull()
        val out = com.dirk.kalshiodds.prediction.LatestModelClient().download(token)
        com.dirk.kalshiodds.prediction.PublishedModelInstaller.apply(
            container.importedModel,
            out
        ) { model ->
            container.scoring.edgeModel = model
        }
    }

    private suspend fun checkKashiUpdateIfDue() {
        val prefs = container.preferences
        val now = System.currentTimeMillis()
        val last = prefs.lastKashiUpdateCheckMs()
        if (!com.dirk.kalshiodds.update.UpdateCheckSchedule.due(last, now)) return
        val check = com.dirk.kalshiodds.update.KashiUpdateClient.http().check(
            com.dirk.kalshiodds.ui.AppVersion.versionName
        )
        when (check) {
            is com.dirk.kalshiodds.update.UpdateCheck.Available -> {
                prefs.markKashiUpdateCheck(now)
                com.dirk.kalshiodds.update.UpdateAvailability.publish(check.release)
            }
            is com.dirk.kalshiodds.update.UpdateCheck.UpToDate -> {
                prefs.markKashiUpdateCheck(now)
                com.dirk.kalshiodds.update.UpdateAvailability.publish(null)
            }
            is com.dirk.kalshiodds.update.UpdateCheck.Failed -> Unit
        }
    }

    private suspend fun firstLaunchSync() {
        val hub = runCatching { container.dataPrefs.hydrate() }.getOrNull() ?: return
        if (hub.firstRestoreDone) return
        if (hub.supabaseConfigured && hub.syncEnabled) {
            com.dirk.kalshiodds.worker.SyncWorker.enqueueOnce(this)
        }
        runCatching { container.dataPrefs.markFirstRestoreDone() }
    }

    private fun restorePersistedHistory() {
        val rows = runCatching { container.resultsStore.recentAlerts(20) }.getOrElse { emptyList() }
        if (rows.isEmpty()) return
        val alerts = rows.map { r ->
            SignalAlert(
                id = r.alertId.ifBlank { "persisted-${r.id}" },
                ticker = r.ticker,
                series = r.series,
                deltaPp = r.edgePp,
                fairValuePp = r.fairPp,
                marketMidPp = r.marketPp,
                reason = r.reason,
                createdAtMs = r.createdAtMs,
                receiveElapsedNanos = 0L,
                regime = r.regime,
                predictedSide = r.side.ifBlank { if (r.edgePp >= 0) "YES" else "NO" }
            )
        }
        container.hub.restoreAlerts(alerts)
    }

    companion object {
        private const val TAG = "DipHunterApp"
        fun from(context: Context): KalshiOddsApp = context.applicationContext as KalshiOddsApp
    }
}
