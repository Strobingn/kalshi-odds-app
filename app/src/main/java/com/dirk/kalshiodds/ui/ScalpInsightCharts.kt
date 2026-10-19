package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.charts.ChartBlock
import com.dirk.kalshiodds.ui.charts.ChartShapes
import com.dirk.kalshiodds.ui.charts.DivergingBarChart
import com.dirk.kalshiodds.ui.charts.FlowDiagram
import com.dirk.kalshiodds.ui.charts.HistogramChart
import com.dirk.kalshiodds.ui.charts.HourHeatmap
import com.dirk.kalshiodds.ui.charts.LineSeries
import com.dirk.kalshiodds.ui.charts.MultiLineChart
import com.dirk.kalshiodds.ui.charts.NetBar
import com.dirk.kalshiodds.ui.charts.SideDonutRow
import com.dirk.kalshiodds.ui.charts.TweakTimeline
import com.dirk.kalshiodds.ui.charts.WinRateGaugeRow
import com.dirk.kalshiodds.ui.charts.chartDayClock
import com.dirk.kalshiodds.ui.charts.chartMoney
import com.dirk.kalshiodds.ui.theme.ArenaColors
import com.dirk.kalshiodds.ui.theme.DipTheme

/** All-time window charts already on the Scalp analysis tab. Existing cards stay above this. */
@Composable
internal fun ScalpWindowInsightCharts(
    windows: List<ScalpModels.WindowRow>,
    nowMs: Long,
    models: List<ScalpModels.Model>,
    pnlBars: List<NetBar>,
    gauges: List<Triple<String, Double?, Int>>,
    tweaks: List<ChartShapes.TweakMark>
) {
    val c = DipTheme.colors
    val equity = remember(windows, nowMs, models) { ChartShapes.equityFromWindows(windows, nowMs, models) }
    val hour = remember(windows, nowMs, models) { ChartShapes.windowHourPnl(windows, nowMs, models) }
    val perHour = remember(windows, nowMs, models) { ChartShapes.windowTradesPerHour(windows, nowMs, models) }
    val palette = chartPalette(c.textPrimary, c.upButton, c.downButton, c.spot)
    Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        ChartBlock("Equity · each scalper and combined", "Recorded window nets after fees. Starts at $0. Open P&L is not included.") {
            MultiLineChart(
                equity.toLines(palette),
                yFormat = { chartMoney(it) },
                xFormat = { chartDayClock(it) },
                includeZero = true,
                contentLabel = "Equity curve"
            )
        }
        ChartBlock("Drawdown", "Drop from the high of the combined equity line.") {
            val combined = equity.firstOrNull { it.name == "Combined" }?.points.orEmpty()
            MultiLineChart(
                listOf(LineSeries("Drawdown", c.downButton, ChartShapes.drawdown(combined))),
                yFormat = { chartMoney(it) },
                xFormat = { chartDayClock(it) },
                includeZero = true,
                contentLabel = "Drawdown"
            )
        }
        ChartBlock("P&L by strategy", "All-time net after fees for strategies that have closed a trade.") {
            DivergingBarChart(pnlBars)
        }
        ChartBlock("Win rate", "Closed round trips. The tick on each gauge is 50%.") {
            WinRateGaugeRow(gauges)
        }
        ChartBlock("Trades per hour", "Round trips stamped at the window close, Eastern time.") {
            HistogramChart(perHour, barColor = c.upButton, contentLabel = "Trades per hour")
        }
        ChartBlock("P&L by hour of day", "Sum of recorded window nets by the hour the window closed, Eastern time.") {
            HourHeatmap(hour)
        }
        ChartBlock("AI tweaks", "Last recorded adjustment for each strategy. Earlier tweaks are not stored.") {
            TweakTimeline(tweaks)
        }
    }
}

/** Trade-level charts. [note] says which book the rows came from. */
@Composable
internal fun ScalpTradeInsightCharts(trades: List<ScalpTrade>, note: String, tweaks: List<ChartShapes.TweakMark>? = null) {
    val c = DipTheme.colors
    val roll = remember(trades) { ChartShapes.rollup(trades) }
    val flow = remember(trades) {
        ChartShapes.flowCounts(
            signaled = trades.count { it.signalAsk.isFinite() },
            entered = trades.count { it.entryPrice != null && it.contracts > 0 && it.state != ScalpState.NO_FILL },
            exited = trades.count { it.state == ScalpState.CLOSED }
        )
    }
    val latest = remember(trades) {
        trades.filter { it.entryPrice != null || it.signalAsk.isFinite() }.maxByOrNull { it.entryAtMs ?: it.signalAtMs }
    }
    val path = remember(latest) {
        if (latest == null) emptyList() else {
            val exit = when {
                latest.soldContracts > 0 -> latest.proceedsUsd / latest.soldContracts
                else -> null
            }
            ChartShapes.flowPath(latest.signalAsk, latest.entryPrice, exit, latest.exitReason, latest.contracts)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        ChartBlock("Equity", note) {
            MultiLineChart(
                listOf(LineSeries("Equity", c.upButton, roll.equity)),
                yFormat = { chartMoney(it) },
                xFormat = { chartDayClock(it) },
                includeZero = true,
                contentLabel = "Equity curve"
            )
        }
        ChartBlock("Drawdown", note) {
            MultiLineChart(
                listOf(LineSeries("Drawdown", c.downButton, roll.drawdown)),
                yFormat = { chartMoney(it) },
                xFormat = { chartDayClock(it) },
                includeZero = true,
                contentLabel = "Drawdown"
            )
        }
        ChartBlock("Cumulative fees", "Entry fee plus exit fee on closed trades. $note") {
            MultiLineChart(
                listOf(LineSeries("Fees", c.downButton, roll.fees)),
                yFormat = { chartMoney(it) },
                xFormat = { chartDayClock(it) },
                includeZero = true,
                contentLabel = "Cumulative fees"
            )
        }
        ChartBlock("Trades per hour", "By entry time, Eastern. $note") {
            HistogramChart(roll.perHour, barColor = c.upButton, contentLabel = "Trades per hour")
        }
        ChartBlock("P&L by hour of day", "By close time, Eastern. $note") {
            HourHeatmap(roll.hourPnl)
        }
        ChartBlock("Entry price", "Filled entries. $note") {
            HistogramChart(roll.entryBins, barColor = c.upButton, contentLabel = "Entry price")
        }
        ChartBlock("Hold time", "Entry to close on trades that have both times. $note") {
            HistogramChart(roll.holdBins, barColor = c.downButton, contentLabel = "Hold time")
        }
        ChartBlock("UP vs DOWN entries", note) {
            SideDonutRow(roll.sides)
        }
        ChartBlock("Strategy flow", "Counts across these trades, then the latest trade's own prices.") {
            FlowDiagram(flow)
            if (path.isNotEmpty()) FlowDiagram(path)
        }
        if (tweaks != null) {
            ChartBlock("AI tweaks", "Last recorded adjustment. Earlier tweaks are not stored.") {
                TweakTimeline(tweaks)
            }
        }
    }
}

@Composable
private fun chartPalette(primary: Color, up: Color, down: Color, spot: Color): List<Color> {
    val series = ArenaColors.current().series
    return listOf(primary) + series + listOf(up, down, spot)
}

private fun List<ChartShapes.NamedSeries>.toLines(palette: List<Color>): List<LineSeries> =
    mapIndexed { i, s ->
        val combined = s.name == "Combined"
        LineSeries(
            name = s.name,
            color = if (combined) palette.first() else palette[(i % (palette.size - 1)) + 1],
            points = s.points,
            strokeDp = if (combined) 3.5f else 2f
        )
    }
