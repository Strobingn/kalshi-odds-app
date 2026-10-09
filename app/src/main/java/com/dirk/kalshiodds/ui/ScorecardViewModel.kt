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
    val forwardTest: com.dirk.kalshiodds.signal.feedback.ForwardTest.Summary =
        com.dirk.kalshiodds.signal.feedback.ForwardTest.Summary(),
    val ticketForward: com.dirk.kalshiodds.signal.feedback.TicketForwardTest.Summary =
        com.dirk.kalshiodds.signal.feedback.TicketForwardTest.Summary()
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
                        is com.dirk.kalshiodds.prediction.LatestModelClient.Outcome.Ready -> {
                            if (out.fetch.decision.activate) {
                                container.importedModel.activate(out.fetch.model, out.fetch.manifest)
                                container.scoring.edgeModel = out.fetch.model
                            }
                            out.fetch.decision.reason
                        }
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

    fun exportForwardTest() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = com.dirk.kalshiodds.signal.feedback.ForwardTest.csv(
                        container.resultsStore.forwardTests()
                    )
                    ResultsFileExport.write(getApplication(), csv, prefix = "diphunter-forward-test")
                }.getOrElse {
                    com.dirk.kalshiodds.data.local.results.ExportResult(false, null, it.message ?: "Export failed")
                }
            }
            _exportMessage.value = result.message
        }
    }

    fun exportTicketForward() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = com.dirk.kalshiodds.signal.feedback.TicketForwardTest.csv(
                        container.resultsStore.ticketForward()
                    )
                    ResultsFileExport.write(getApplication(), csv, prefix = "diphunter-ticket-forward")
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
        container.lastMinuteStore.state,
        container.guardrailStore.stateFlow,
        combine(_exportMessage, _modelNote, container.adapterStore.stateFlow,
            container.resultsWriter.forwardRevision) { export, note, adapter, _ ->
            Triple(export, note, adapter)
        }
    ) { entries, paper, lastMinute, guard, notes ->
        val settings = container.hub.settings
        val (windows, forwardRows, ticketRows) = withContext(Dispatchers.IO) {
            Triple(
                runCatching { container.archive.recentSettled(limit = 400) }.getOrElse { emptyList() },
                runCatching { container.resultsStore.forwardTests() }.getOrElse { emptyList() },
                runCatching { container.resultsStore.ticketForward() }.getOrElse { emptyList() }
            )
        }
        ScorecardUi(
            view = ScorecardCopy.of(entries, paper, windows, lastMinutePicks = lastMinute.picks),
            metrics = ScorecardMetrics.compute(
                entries = entries,
                calibration = container.scoring.calibration,
                policyStakeUsd = settings.policyEvalStakeUsd,
                edgeThresholdPp = settings.effectiveEdgeThresholdPp(),
                minConfidence = settings.minConfidence,
                requireUncertaintyPass = settings.uncertaintyGateEnabled,
                maxUncertainty = settings.maxUncertainty,
                fills = paper.fills + paper.archived.flatMap { it.fills },
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
            forwardTest = com.dirk.kalshiodds.signal.feedback.ForwardTest.summarize(forwardRows),
            ticketForward = com.dirk.kalshiodds.signal.feedback.TicketForwardTest.summarize(ticketRows)
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
