package com.dirk.kalshiodds

import android.content.Context
import com.dirk.kalshiodds.data.repo.MarketRepository
import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.signal.SignalHub
import com.dirk.kalshiodds.signal.config.SignalPreferences
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.notify.SignalNotifier

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val preferences = SignalPreferences(app)
    val model = DipHunterModel(app)
    val repository = MarketRepository(app, model = model)
    val notifier = SignalNotifier(app)
    val scoring = ScoringEngine(model = model)
    val hub = SignalHub(scoring = scoring, notifier = notifier)
}
