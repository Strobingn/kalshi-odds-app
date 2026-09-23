package com.dirk.kalshiodds

import android.app.Application
import android.content.Context
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.worker.MarketRefreshScheduler

class KalshiOddsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SignalNotifier.ensureChannels(this)
        MarketRefreshScheduler.enqueue(this)
    }

    companion object {
        fun from(context: Context): KalshiOddsApp = context.applicationContext as KalshiOddsApp
    }
}
