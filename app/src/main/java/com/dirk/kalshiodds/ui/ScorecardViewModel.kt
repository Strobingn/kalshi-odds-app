package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.feedback.Allowlist
import com.dirk.kalshiodds.signal.feedback.Guardrails
import com.dirk.kalshiodds.signal.feedback.OnlineAdapter
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ScorecardUi(
    val metrics: ScorecardMetrics.Snapshot,
    val allowlist: Allowlist.State,
    val adapter: OnlineAdapter.State,
    val guardrails: Guardrails.State
)

class ScorecardViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container

    val snapshot: StateFlow<ScorecardUi> = combine(
        container.logStore.entriesFlow,
        container.adapterStore.stateFlow,
        container.guardrailStore.stateFlow
    ) { entries, adapter, guard ->
        val settings = container.hub.settings
        ScorecardUi(
            metrics = ScorecardMetrics.compute(
                entries = entries,
                calibration = container.scoring.calibration,
                policyStakeUsd = settings.policyEvalStakeUsd,
                edgeThresholdPp = settings.edgeThresholdPp,
                minConfidence = settings.minConfidence,
                requireUncertaintyPass = settings.uncertaintyGateEnabled,
                maxUncertainty = settings.maxUncertainty
            ),
            allowlist = Allowlist.evaluate(entries, floor = settings.muteHitRateFloor),
            adapter = adapter,
            guardrails = guard
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ScorecardUi(
            metrics = ScorecardMetrics.compute(emptyList(), calibration = container.scoring.calibration),
            allowlist = Allowlist.State(),
            adapter = OnlineAdapter.identity(),
            guardrails = Guardrails.identity()
        )
    )
}
