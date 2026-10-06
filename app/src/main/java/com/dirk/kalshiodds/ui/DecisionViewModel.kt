package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.decision.LadderEntry
import com.dirk.kalshiodds.decision.StrategyLadder
import com.dirk.kalshiodds.decision.V060Rule
import com.dirk.kalshiodds.decision.Fav15Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CalibrationUi(
    val header: List<String> = emptyList(),
    val rows: List<DecisionCopy.CalibrationRow> = emptyList(),
    val busy: Boolean = false
)

data class LadderUi(
    val statuses: List<StrategyLadder.Status> = emptyList(),
    val entries: List<LadderEntry> = emptyList(),
    val rules: String = StrategyLadder.rulesText()
)

/** Calibration report + strategy ladder. Read-only views over deterministic models; no orders here. */
class DecisionViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container
    private val _calibration = MutableStateFlow(CalibrationUi())
    val calibration: StateFlow<CalibrationUi> = _calibration.asStateFlow()
    private val _ladder = MutableStateFlow(LadderUi())
    val ladder: StateFlow<LadderUi> = _ladder.asStateFlow()

    init {
        refreshCalibration()
        viewModelScope.launch {
            combine(container.ladder.state, container.d3Store.state) { l, d3 -> l to d3 }.collect { (l, d3) ->
                _ladder.value = buildLadder(l.entries, d3.picks)
            }
        }
    }

    fun refreshCalibration(refit: Boolean = false) {
        viewModelScope.launch {
            _calibration.value = _calibration.value.copy(busy = true)
            val ui = withContext(Dispatchers.IO) {
                if (refit) container.decisions.refitNow()
                val model = container.decisions.calibration
                val count = runCatching { container.predictionLedger?.ledgerCount() }.getOrNull()
                CalibrationUi(DecisionCopy.calibrationHeader(model, count), DecisionCopy.calibrationRows(model), busy = false)
            }
            _calibration.value = ui
        }
    }

    fun promote(status: StrategyLadder.Status) {
        container.ladder.promote(status.id, status)
    }

    fun demote(id: StrategyLadder.Id) = container.ladder.demoteToPaper(id)

    private fun buildLadder(
        entries: List<LadderEntry>,
        d3: List<com.dirk.kalshiodds.signal.d3.D3Pick>
    ): LadderUi {
        val v150Items = d3.filter { it.filled }.map {
            StrategyLadder.Item(
                cluster = it.eventTicker ?: it.ticker,
                filled = true,
                settled = it.settled,
                pnlUsd = it.pnlUsd
            )
        }
        val statuses = listOf(
            StrategyLadder.status(StrategyLadder.Id.V060, container.ladder.stage(StrategyLadder.Id.V060), container.ladder.items(V060Rule.ID)),
            StrategyLadder.status(StrategyLadder.Id.V150, container.ladder.stage(StrategyLadder.Id.V150), v150Items),
            StrategyLadder.status(StrategyLadder.Id.FAV15, container.ladder.stage(StrategyLadder.Id.FAV15), container.ladder.items(Fav15Rule.ID))
        )
        return LadderUi(statuses = statuses, entries = entries)
    }
}
