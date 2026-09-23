package com.dirk.kalshiodds

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
        container = AppContainer(this)
        runCatching { SignalNotifier.ensureChannels(this) }
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
            }
        })
        appScope.launch {
            runCatching {
                container.preferences.settings
                    .map { it.liveSignalsEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        LiveSignalsKeepAlive.setEnabled(this@KalshiOddsApp, enabled)
                        if (enabled && LiveSignalsKeepAlive.isUiInForeground()) {
                            LiveSignalsService.start(this@KalshiOddsApp)
                        }
                        // Off: the running service observes DataStore and stopSelfs.
                    }
            }
        }
    }

    companion object {
        private const val TAG = "DipHunterApp"
        fun from(context: Context): KalshiOddsApp = context.applicationContext as KalshiOddsApp
    }
}
