package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ScorecardViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container

    val snapshot: StateFlow<ScorecardMetrics.Snapshot> = container.logStore.entriesFlow
        .map { entries ->
            ScorecardMetrics.compute(
                entries = entries,
                calibration = container.scoring.calibration
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ScorecardMetrics.compute(emptyList(), calibration = container.scoring.calibration)
        )
}
