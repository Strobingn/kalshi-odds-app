package com.dirk.kalshiodds.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpTrade
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
