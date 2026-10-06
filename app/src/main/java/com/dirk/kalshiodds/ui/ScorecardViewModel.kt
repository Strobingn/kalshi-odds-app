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
    val view: ScorecardCopy.View,
    val metrics: ScorecardMetrics.Snapshot? = null,
    val allowlist: Allowlist.State? = null,
    val adapter: OnlineAdapter.State? = null,
    val guardrails: Guardrails.State? = null,
    val extendedLine: String? = null,
    val exportMessage: String? = null,
    val sitOut: Boolean = false,
    val autoTuneNote: String = "",
    val modelNote: String? = null,
    val honestLines: List<String> = emptyList(),
    val ladderLine: String = com.dirk.kalshiodds.decision.StrategyLadder.rulesText()
) {
    companion object {
        val EMPTY = ScorecardUi(ScorecardCopy.EMPTY)
    }
}

class ScorecardViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container
    private val _exportMessage = MutableStateFlow<String?>(null)
    private val _modelNote = MutableStateFlow<String?>(null)

    fun getLatestModel() {
        viewModelScope.launch {
            _modelNote.value = "Fetching latest model…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val token = container.extraSecrets.githubToken
                    when (val out = com.dirk.kalshiodds.prediction.LatestModelClient().download(token)) {
                        is com.dirk.kalshiodds.prediction.LatestModelClient.Outcome.Ready ->
                            com.dirk.kalshiodds.prediction.PublishedModelInstaller.apply(
                                container.importedModel,
                                out
                            ) { model -> container.scoring.edgeModel = model }
                        is com.dirk.kalshiodds.prediction.LatestModelClient.Outcome.NeedsAuth -> out.message
                        is com.dirk.kalshiodds.prediction.LatestModelClient.Outcome.Failed -> out.message
                    }
                }.getOrElse { it.message ?: "download failed" }
            }
            _modelNote.value = result
        }
    }

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
        container.paper.book.state,
        combine(container.lastMinuteStore.state, container.d3Store.state, container.shadow.book.state) { lm, d3, shadow ->
            Triple(lm, d3, shadow)
        },
        container.guardrailStore.stateFlow,
        combine(_exportMessage, _modelNote, container.adapterStore.stateFlow) { export, note, adapter ->
            Triple(export, note, adapter)
        }
    ) { entries, paper, books, guard, notes ->
        val lastMinute = books.first
        val d3 = books.second
        val shadow = books.third
        val settings = container.hub.settings
        val windows = runCatching { container.archive.recentSettled(limit = 400) }.getOrElse { emptyList() }
        ScorecardUi(
            view = ScorecardCopy.of(
                entries,
                paper,
                windows,
                lastMinutePicks = lastMinute.picks,
                d3Picks = d3.picks,
                shadow = shadow
            ),
            metrics = ScorecardMetrics.compute(
                entries = entries,
                calibration = container.scoring.calibration,
                policyStakeUsd = settings.policyEvalStakeUsd,
                edgeThresholdPp = settings.effectiveEdgeThresholdPp(),
                minConfidence = settings.minConfidence,
                requireUncertaintyPass = settings.uncertaintyGateEnabled,
                maxUncertainty = settings.maxUncertainty,
                fills = paper.scorecardFills(),
                settledWindows = windows
            ),
            allowlist = Allowlist.evaluate(entries, floor = settings.muteHitRateFloor),
            adapter = notes.third,
            guardrails = guard,
            extendedLine = extendedLine(),
            exportMessage = notes.first,
            sitOut = settings.isSittingOut(),
            autoTuneNote = settings.autoTuneNote,
            modelNote = notes.second,
            honestLines = com.dirk.kalshiodds.decision.HonestScorecard.lines(
                com.dirk.kalshiodds.decision.HonestScorecard.fromFills(paper.scorecardFills())
            )
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ScorecardUi.EMPTY
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
