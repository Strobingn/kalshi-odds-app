package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.ui.charts.ChartBlock
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.ChartShapes
import com.dirk.kalshiodds.ui.charts.FamilyStackChart
import com.dirk.kalshiodds.ui.charts.HistogramChart
import com.dirk.kalshiodds.ui.charts.HourHeatmap
import com.dirk.kalshiodds.ui.charts.LineSeries
import com.dirk.kalshiodds.ui.charts.MultiLineChart
import com.dirk.kalshiodds.ui.charts.ShareDonut
import com.dirk.kalshiodds.ui.charts.DonutSlice
import com.dirk.kalshiodds.ui.charts.chartDayClock
import com.dirk.kalshiodds.ui.charts.chartMoney
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.FieldShapes

/** Extra scorecard diagrams. The existing cumulative card stays where it is. */
@Composable
internal fun ScorecardDetailCharts(picks: List<ScorecardLedger.PickRow>) {
    val colors = DipTheme.colors
    val rows = remember(picks) { picks.filter { !it.noBetWouldHave } }
    val money = remember(rows) { rows.mapNotNull { p -> p.pnlUsd?.takeIf { it.isFinite() }?.let { p.settledAtMs to it } } }
    val equity = remember(money) { ChartShapes.cumulative(money) }
    val drawdown = remember(equity) { ChartShapes.drawdown(equity) }
    val fees = remember(rows) {
        ChartShapes.cumulative(rows.mapNotNull { p -> p.feeUsd?.takeIf { it.isFinite() }?.let { p.settledAtMs to it } })
    }
    val hours = remember(money) { ChartShapes.pnlByHour(money) }
    val perHour = remember(rows) { ChartShapes.tradesPerHour(rows.map { it.settledAtMs }) }
    val entries = remember(rows) { ChartShapes.entryPriceBins(rows.mapNotNull { it.entryAsk }) }
    val overlay = remember(rows) {
        rows.mapNotNull { p ->
            val yes = !p.side.equals("NO", true)
            ChartShapes.aiMarket(p.settledAtMs, yes, p.aiPct, p.marketPct, p.entryAsk)
        }.sortedBy { it.tMs }
    }
    val up = rows.count { it.side.equals("YES", true) }
    val down = rows.count { it.side.equals("NO", true) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        ScorecardChartCard {
            ChartBlock("Drawdown", "From the scorecard's settled P&L, same picks as the cumulative line.") {
                MultiLineChart(
                    listOf(LineSeries("Drawdown", colors.downButton, drawdown)),
                    yFormat = { chartMoney(it) },
                    xFormat = { chartDayClock(it) },
                    includeZero = true,
                    contentLabel = "Scorecard drawdown"
                )
            }
        }
        ScorecardChartCard {
            ChartBlock("Cumulative fees", "Fees stored on settled picks.") {
                MultiLineChart(
                    listOf(LineSeries("Fees", colors.downButton, fees)),
                    yFormat = { chartMoney(it) },
                    xFormat = { chartDayClock(it) },
                    includeZero = true,
                    contentLabel = "Scorecard fees"
                )
            }
        }
        ScorecardChartCard {
            ChartBlock("AI UP chance vs market", "Picked-side percents flipped to UP. The entry price decides whether a stored market % is the side or the YES mid.") {
                MultiLineChart(
                    listOf(
                        LineSeries("AI UP", colors.upButton, overlay.mapNotNull { p -> p.aiUp?.let { ChartShapes.Sample(p.tMs, it) } }, dashed = true),
                        LineSeries("Market UP", colors.spot, overlay.mapNotNull { p -> p.marketUp?.let { ChartShapes.Sample(p.tMs, it) } })
                    ),
                    yFormat = { "${it.toInt()}%" },
                    xFormat = { chartDayClock(it) },
                    fixedRange = 0.0 to 100.0,
                    guideY = 50.0,
                    guideLabel = "50%",
                    contentLabel = "AI versus market"
                )
            }
        }
        ScorecardChartCard {
            ChartBlock("P&L by hour of day", "Settled picks, Eastern time.") {
                HourHeatmap(hours)
            }
        }
        ScorecardChartCard {
            ChartBlock("Settled picks per hour", "By settlement time, Eastern.") {
                HistogramChart(perHour, barColor = colors.upButton, contentLabel = "Picks per hour")
            }
        }
        ScorecardChartCard {
            ChartBlock("Entry price", "Recorded entry ask.") {
                HistogramChart(entries, barColor = colors.upButton, contentLabel = "Entry price")
            }
        }
        ScorecardChartCard {
            ChartBlock("UP vs DOWN", "Settled picks by side.") {
                if (up + down == 0) {
                    com.dirk.kalshiodds.ui.charts.ChartEmptyState(height = 160.dp)
                } else {
                    ShareDonut(
                        listOf(
                            DonutSlice("UP", up.toDouble(), colors.upButton),
                            DonutSlice("DOWN", down.toDouble(), colors.downButton)
                        ),
                        Modifier.fillMaxWidth(),
                        size = 160.dp
                    )
                }
            }
        }
        ScorecardChartCard {
            val families = remember(rows) {
                ChartGallery.familyStacks(rows.mapNotNull { p ->
                    val pnl = p.pnlUsd?.takeIf { it.isFinite() } ?: return@mapNotNull null
                    val fallback = if (p.kind == ScorecardLedger.Kind.AI) "AI picks" else "Manual"
                    ChartGallery.FamilyEvent(p.settledAtMs, ChartGallery.scorecardFamily(p.source, p.pickSource, fallback), pnl)
                })
            }
            FamilyStackChart(families)
        }
    }
}

@Composable
private fun ScorecardChartCard(content: @Composable () -> Unit) {
    val colors = DipTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, FieldShapes.card)
            .padding(16.dp)
    ) {
        content()
    }
}
