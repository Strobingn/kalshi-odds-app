package com.dirk.kalshiodds

import android.app.Application
import com.dirk.kalshiodds.worker.MarketRefreshScheduler

class KalshiOddsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MarketRefreshScheduler.enqueue(this)
    }
}
