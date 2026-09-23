package com.dirk.kalshiodds

import android.app.Application
import android.content.Context
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import com.dirk.kalshiodds.signal.service.LiveSignalsService
import com.dirk.kalshiodds.worker.MarketRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KalshiOddsApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SignalNotifier.ensureChannels(this)
        // Immediate FGS promote from the sync keep-alive flag — do not wait for
        // Activity / ViewModel. This is what keeps WS alive after process death.
        LiveSignalsKeepAlive.ensureService(this)
        MarketRefreshScheduler.enqueue(this)
        LiveSignalsKeepAlive.enqueueWatchdogs(this)
        appScope.launch {
            container.preferences.settings
                .map { it.liveSignalsEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    LiveSignalsKeepAlive.setEnabled(this@KalshiOddsApp, enabled)
                    if (enabled) {
                        LiveSignalsService.start(this@KalshiOddsApp)
                    }
                    // Off: the running service observes DataStore and stopSelfs.
                    // Do not startForegroundService just to deliver STOP.
                }
        }
    }

    companion object {
        fun from(context: Context): KalshiOddsApp = context.applicationContext as KalshiOddsApp
    }
}
