package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.EasternTime
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs

/** Observational dashboard; charts never submit orders or alter strategies. */
@Composable
fun PaperChartsScreen(state: OddsUiState, onBack: () -> Unit, onSelectTab: (Int) -> Unit) {
    val colors = DipTheme.colors
    val charts = remember(state.paperFeedChart) { PaperChartsData.market(state.paperFeedChart) }
    val tracks = remember(state.paper.fills) { PaperChartsData.tracks(state.paper.fills) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(320.dp),
        modifier = Modifier.fillMaxWidth().background(colors.bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.accentBlue) }
                    Column {
                        Text("Paper charts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                        Text("${state.paperFeedChart.lastOrNull()?.ticker ?: "Waiting for BTC window"} · 15-second samples", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                ScalpModeTabs(selected = 3, onSelect = onSelectTab)
                Text("Current window is device-local and resets at the next market. Paper results are simulated; spot freshness is unknown.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(charts.size) { index -> MarketChart(charts[index]) }
        item(span = { GridItemSpan(maxLineSpan) }) { StrategyEvidenceChart(tracks) }
        item(span = { GridItemSpan(maxLineSpan) }) { StrategyOutcomeChart(tracks) }
        item(span = { GridItemSpan(maxLineSpan) }) { StrategyChart(tracks) }
    }
}

@Composable
private fun StrategyOutcomeChart(tracks: List<PaperChartsData.Track>) {
    val colors = DipTheme.colors
    ChartSurface {
        Text("Wins, losses and flat exits", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text("After-fee settled paper exits only · bar length shows the share of each outcome, not profitability.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        tracks.forEach { track ->
            val flat = (track.settled - track.wins - track.losses).coerceAtLeast(0)
            Text("${track.label} · ${track.wins} win / ${track.losses} loss / $flat flat", color = colors.textPrimary, style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(9.dp).semantics { contentDescription = "${track.label}: ${track.wins} wins, ${track.losses} losses, $flat flat exits" }) {
                if (track.settled > 0) {
                    val winWidth = size.width * track.wins / track.settled
                    val lossWidth = size.width * track.losses / track.settled
                    drawLine(colors.up, Offset(0f, size.height / 2), Offset(winWidth, size.height / 2), 6.dp.toPx())
                    drawLine(colors.down, Offset(winWidth, size.height / 2), Offset(winWidth + lossWidth, size.height / 2), 6.dp.toPx())
                    drawLine(colors.textSecondary, Offset(winWidth + lossWidth, size.height / 2), Offset(size.width, size.height / 2), 6.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun StrategyEvidenceChart(tracks: List<PaperChartsData.Track>) {
    val colors = DipTheme.colors
    val max = tracks.mapNotNull { it.equalWindowPerContract?.let(::abs) }.maxOrNull()?.coerceAtLeast(0.001) ?: 0.001
    ChartSurface {
        Text("P&L per contract · equal-window average", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text("Each market window counts equally; after fees. Win/loss counts are paper exits, not independent windows. Small samples are provisional.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        tracks.forEach { track ->
            val value = track.equalWindowPerContract
            Text("${track.label} · ${track.windows} windows · ${track.wins}W/${track.losses}L · ${value?.let { String.format(Locale.US, "%+.3f USD/ct", it) } ?: "no data"}", color = colors.textPrimary, style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "${track.label}, ${track.windows} windows, ${value?.let { number(it) } ?: "no result"} dollars per contract" }) {
                val center = size.width / 2f
                drawLine(colors.border, Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
                if (value != null) {
                    val width = (abs(value) / max).toFloat().coerceIn(0f, 1f) * center
                    drawLine(if (value >= 0.0) colors.up else colors.down, Offset(if (value >= 0.0) center else center - width, size.height / 2f), Offset(if (value >= 0.0) center + width else center, size.height / 2f), 4.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun ChartSurface(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = DipTheme.colors
    Column(Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(16.dp))
        .background(colors.surface, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
}

@Composable
private fun MarketChart(chart: PaperChartsData.Chart) {
    val colors = DipTheme.colors
    val seriesColors = listOf(colors.accentBlue, colors.up)
    val values = chart.points.flatMap { it.values }.filterNotNull().filter(Double::isFinite)
    ChartSurface {
        Text(chart.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        chart.labels.forEachIndexed { index, label ->
            val current = chart.points.lastOrNull()?.values?.getOrNull(index)
            Text("$label: ${current?.let { number(it) + " " + chart.unit } ?: "waiting / unavailable"}", color = seriesColors[index], style = MaterialTheme.typography.bodyMedium)
        }
        if (values.size < 2 || chart.points.size < 2) {
            Text("Waiting for more observations", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            val low = values.minOrNull() ?: 0.0
            val high = values.maxOrNull() ?: low
            val firstTime = chart.points.first().timeMs
            val lastTime = chart.points.last().timeMs
            val duration = (lastTime - firstTime).coerceAtLeast(1L)
            val lineDescription = "${chart.title}, ${chart.labels.joinToString()} in ${chart.unit}, range ${number(low)} to ${number(high)}"
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = lineDescription }) {
                val span = high - low
                for (i in chart.labels.indices) {
                    var previous: Offset? = null
                    var previousTime: Long? = null
                    chart.points.forEach { point ->
                        val v = point.values.getOrNull(i)?.takeIf(Double::isFinite)
                        if (v == null) { previous = null; previousTime = null } else {
                            if (previousTime != null && point.timeMs - previousTime!! > 30_000L) previous = null
                            val x = ((point.timeMs - firstTime).toDouble() / duration).toFloat().coerceIn(0f, 1f) * size.width
                            val y = if (span <= 1e-12) size.height / 2f else size.height - ((v - low) / span).toFloat() * (size.height - 8.dp.toPx()) - 4.dp.toPx()
                            val position = Offset(x, y)
                            previous?.let { drawLine(seriesColors[i], it, position, strokeWidth = 2.dp.toPx()) }
                            previous = position
                            previousTime = point.timeMs
                        }
                    }
                }
            }
            Text("${number(low)}–${number(high)} ${chart.unit} · ${EasternTime.short(firstTime)} – ${EasternTime.short(lastTime)}", color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Text(chart.note, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StrategyChart(tracks: List<PaperChartsData.Track>) {
    val colors = DipTheme.colors
    val max = tracks.mapNotNull { it.pnl?.let(::abs) }.maxOrNull()?.coerceAtLeast(0.01) ?: 0.01
    ChartSurface {
        Text("Strategy results · 10 active", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text("Current shared paper run · settled, non-void, after-fee P&L. Open positions are not profits; small samples prove nothing.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        tracks.forEach { track ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${track.label} · ${if (track.active) "active" else "retired"}", color = colors.textPrimary, style = MaterialTheme.typography.labelLarge)
                Text("${track.pnl?.let { String.format(Locale.US, "%+.2f USD", it) } ?: "No settled P&L"} · ${track.settled} settled · ${track.open} open · fees ${number(track.fees)} USD", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                val pnl = track.pnl
                Canvas(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "${track.label}: ${pnl?.let { number(it) + " dollars" } ?: "no settled result"}; ${track.open} open, ${track.settled} settled" }) {
                    val center = size.width / 2f
                    drawLine(colors.border, Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
                    if (pnl != null) {
                        val width = (abs(pnl) / max).toFloat().coerceIn(0f, 1f) * center
                        drawLine(if (pnl >= 0.0) colors.up else colors.down, Offset(if (pnl >= 0.0) center else center - width, size.height / 2f), Offset(if (pnl >= 0.0) center + width else center, size.height / 2f), 5.dp.toPx())
                    }
                }
            }
        }
    }
}

private fun number(value: Double): String = String.format(Locale.US, "%.2f", value)
