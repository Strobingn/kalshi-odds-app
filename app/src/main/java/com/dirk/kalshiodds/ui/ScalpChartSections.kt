package com.dirk.kalshiodds.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.llm.AiAdjustment
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.ui.charts.ChartShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.CorrelationHeatmap
import com.dirk.kalshiodds.ui.charts.EntryExitScatter
import com.dirk.kalshiodds.ui.charts.EquitySparkGrid
import com.dirk.kalshiodds.ui.charts.PnlHistogram
import com.dirk.kalshiodds.ui.charts.RollingWinRateChart
import com.dirk.kalshiodds.ui.charts.StrategyLeaderboard
import com.dirk.kalshiodds.ui.charts.StrategySideBars
import com.dirk.kalshiodds.ui.charts.WinRateHeatmap

/** Optional inputs for the live-window card charts. Empty fields render the placeholder. */
data class LiveWindowExtras(
    val bookYes: List<Pair<Double, Double>> = emptyList(),
    val bookNo: List<Pair<Double, Double>> = emptyList(),
    val calibration: List<Pair<Double, Boolean>> = emptyList(),
    val dayFees: ChartGallery.FeeLeg? = null,
    val knobs: List<ChartGallery.KnobTick> = emptyList()
)

/** Recorded tuner changes. Reads the existing adjustment log and does not call a provider. */
@Composable
fun rememberKnobTicks(): List<ChartGallery.KnobTick> {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as? KalshiOddsApp)?.let { runCatching { it.container.aiWatch }.getOrNull() }
    }
    val log by (store?.adjustments ?: kotlinx.coroutines.flow.flowOf(emptyList<AiAdjustment>()))
        .collectAsStateWithLifecycle(emptyList())
    return remember(log) {
        ChartGallery.knobTicks(log.map { ChartGallery.KnobTick(it.atMs, it.scalper, it.setting, it.oldValue, it.newValue) })
    }
}

/** Settled paper fills → (AI UP chance 0–100, whether UP settled). */
fun paperCalibration(fills: List<PaperFill>): List<Pair<Double, Boolean>> = fills.mapNotNull { fill ->
    val won = fill.won ?: return@mapNotNull null
    if (!fill.settled) return@mapNotNull null
    val yes = !fill.side.equals("NO", true)
    val up = ChartShapes.aiUp(yes, fill.aiPct) ?: return@mapNotNull null
    up to if (yes) won else !won
}

/** Charts added on the Scalp Analysis tab. Existing cards stay where they are. */
@Composable
internal fun AnalysisGallery(
    trades: List<ScalpTrade>,
    leaders: List<ChartGallery.LeaderRow> = emptyList(),
    sparks: List<ChartGallery.SparkLine> = emptyList(),
    windows: List<ScalpModels.WindowRow> = emptyList(),
    nowMs: Long = 0L,
    models: List<ScalpModels.Model> = emptyList()
) {
    val groups = remember(trades) { ChartGallery.sideGroups(trades) }
    val heat = remember(trades) { ChartGallery.winRateByHourCoin(trades) }
    val board = remember(leaders) { ChartGallery.leaderboard(leaders) }
    val corr = remember(windows, nowMs, models) { ChartGallery.dailyCorrelation(windows, nowMs, models) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ArenaCard { StrategySideBars(groups) }
        ArenaCard { WinRateHeatmap(heat) }
        ArenaCard { StrategyLeaderboard(board) }
        ArenaCard { EquitySparkGrid(sparks) }
        ArenaCard { CorrelationHeatmap(corr) }
    }
}

/** Rolling win rate, P&L histogram, and entry/exit scatter for one strategy. */
@Composable
internal fun StrategyDetailCharts(trades: List<ScalpTrade>) {
    val rolling = remember(trades) { ChartGallery.rollingWinRate(trades) }
    val bins = remember(trades) { ChartGallery.pnlBins(trades) }
    val dots = remember(trades) { ChartGallery.entryExit(trades) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ArenaCard { RollingWinRateChart(rolling) }
        ArenaCard { PnlHistogram(bins) }
        ArenaCard { EntryExitScatter(dots) }
    }
}
