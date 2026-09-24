package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.local.results.ResultsExporter
import com.dirk.kalshiodds.data.local.results.ResultsFileExport
import com.dirk.kalshiodds.signal.feedback.Allowlist
import com.dirk.kalshiodds.signal.feedback.Guardrails
import com.dirk.kalshiodds.signal.feedback.OnlineAdapter
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScorecardUi(
    val metrics: ScorecardMetrics.Snapshot,
    val allowlist: Allowlist.State,
    val adapter: OnlineAdapter.State,
    val guardrails: Guardrails.State,
    val extendedLine: String? = null,
    val exportMessage: String? = null
)

class ScorecardViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container
    private val _exportMessage = MutableStateFlow<String?>(null)

    fun exportResults() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = ResultsExporter.csv(container.resultsStore.exportBundle())
                    ResultsFileExport.write(getApplication(), csv)
                }.getOrElse {
                    com.dirk.kalshiodds.data.local.results.ExportResult(false, null, it.message ?: "Export failed")
                }
            }
            _exportMessage.value = result.message
        }
    }

    val snapshot: StateFlow<ScorecardUi> = combine(
        container.logStore.entriesFlow,
        container.adapterStore.stateFlow,
        container.guardrailStore.stateFlow,
        _exportMessage
    ) { entries, adapter, guard, export ->
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
            guardrails = guard,
            extendedLine = extendedLine(),
            exportMessage = export
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ScorecardUi(
            metrics = ScorecardMetrics.compute(emptyList(), calibration = container.scoring.calibration),
            allowlist = Allowlist.State(),
            adapter = OnlineAdapter.identity(),
            guardrails = Guardrails.identity(),
            extendedLine = null
        )
    )

    private fun extendedLine(): String {
        val ext = container.scoring.extended
        val news = if (ext.news.headlineCount > 0) {
            " · news ${ext.news.headlineCount} (${ext.news.source})"
        } else {
            ""
        }
        return "RL n=${ext.rl.sampleCount} · meta n=${ext.meta.sampleCount} · " +
            "conformal n=${ext.conformal.scores.size}${if (ext.conformal.ready) " ready" else " cold"}" +
            news
    }
}
