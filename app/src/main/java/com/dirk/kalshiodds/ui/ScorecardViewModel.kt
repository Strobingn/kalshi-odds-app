package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.local.results.ResultsExporter
import com.dirk.kalshiodds.data.local.results.ResultsFileExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScorecardUi(
    val view: ScorecardCopy.View
) {
    companion object {
        val EMPTY = ScorecardUi(ScorecardCopy.EMPTY)
    }
}

class ScorecardViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container

    fun getLatestModel() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
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
        }
    }

    fun exportResults() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val csv = ResultsExporter.csv(container.resultsStore.exportBundle())
                    ResultsFileExport.write(getApplication(), csv)
                }.getOrElse {
                    com.dirk.kalshiodds.data.local.results.ExportResult(false, null, it.message ?: "Export failed")
                }
            }
        }
    }

    val snapshot: StateFlow<ScorecardUi> = combine(
        container.logStore.entriesFlow,
        container.paper.book.state
    ) { entries, paper ->
        ScorecardUi(view = ScorecardCopy.of(entries, paper.realizedPnlUsd))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ScorecardUi.EMPTY
    )
}
