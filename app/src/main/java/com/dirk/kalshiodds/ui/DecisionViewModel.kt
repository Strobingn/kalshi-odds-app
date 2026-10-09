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

data class ScalpUi(
    val open: List<ScalpCopy.Line> = emptyList(),
    val history: List<ScalpCopy.Line> = emptyList(),
    val stats: List<String> = emptyList(),
    val ladder: String = "",
    val rules: String = com.dirk.kalshiodds.decision.ScalpRule.rulesText(),
    /** 0.3.40: current per-coin params version + out-of-sample result, and scorecard breakdowns. */
    val tuning: List<String> = emptyList(),
    val breakdown: List<String> = emptyList()
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

    private val _scalp = MutableStateFlow(ScalpUi())
    val scalp: StateFlow<ScalpUi> = _scalp.asStateFlow()

    init {
        refreshCalibration()
        viewModelScope.launch {
            combine(container.ladder.state, container.d3Store.state, container.scalp.trades) { l, d3, sc -> Triple(l, d3, sc) }
                .collect { (l, d3, sc) ->
                    _ladder.value = buildLadder(l.entries, d3.picks, sc)
                }
        }
        viewModelScope.launch {
            combine(container.scalp.trades, container.scalp.marks, container.scalp.tune) { t, m, tu -> Triple(t, m, tu) }.collect { (t, m, tu) ->
                val status = StrategyLadder.status(
                    StrategyLadder.Id.SCALP,
                    container.ladder.stage(StrategyLadder.Id.SCALP),
                    com.dirk.kalshiodds.decision.ScalpStats.ladderItems(t)
                )
                _scalp.value = ScalpUi(
                    open = ScalpCopy.openLines(t, m),
                    history = ScalpCopy.historyLines(t),
                    stats = ScalpCopy.statsLines(com.dirk.kalshiodds.decision.ScalpStats.summary(t)),
                    ladder = status.reason,
                    tuning = ScalpCopy.tuningLines(tu),
                    breakdown = com.dirk.kalshiodds.decision.ScalpBreakdown.lines(t)
                )
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
        d3: List<com.dirk.kalshiodds.signal.d3.D3Pick>,
        scalps: List<com.dirk.kalshiodds.decision.ScalpTrade> = emptyList()
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
            StrategyLadder.status(StrategyLadder.Id.FAV15, container.ladder.stage(StrategyLadder.Id.FAV15), container.ladder.items(Fav15Rule.ID)),
            StrategyLadder.status(
                StrategyLadder.Id.SCALP,
                container.ladder.stage(StrategyLadder.Id.SCALP),
                com.dirk.kalshiodds.decision.ScalpStats.ladderItems(scalps)
            )
        )
        return LadderUi(statuses = statuses, entries = entries)
    }
}
