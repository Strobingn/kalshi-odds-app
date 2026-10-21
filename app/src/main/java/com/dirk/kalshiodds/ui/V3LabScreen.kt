package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.v3.V3ShadowFill
import com.dirk.kalshiodds.signal.v3.V3ShadowState
import com.dirk.kalshiodds.signal.v3.V3ChartData
import com.dirk.kalshiodds.signal.v3.V3Observation
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Independent V3 tab. It renders only local shadow records and never exposes buy controls. */
@Composable
fun V3LabScreen(
    state: V3ShadowState,
    onBack: () -> Unit,
    onOpenArena: () -> Unit,
    onOpenChat: () -> Unit
) {
    val colors = DipTheme.colors
    val settled = state.settled
    val chartObservations = V3ChartData.currentMarket(state.observations)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxWidth().background(colors.bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home",
                            tint = colors.accentBlue
                        )
                    }
                    Column {
                        Text("V3 shadow lab", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                        Text("Market-offset test · local paper evidence · no live order path", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    }
                }
                ScalpModeTabs(selected = 1, onSelect = { if (it == 0) onOpenArena() else if (it == 2) onOpenChat() })
            }
        }
        item { V3MetricCard("Open shadows", state.openCount.toString(), "one unit each") }
        item { V3MetricCard("Settled", settled.size.toString(), "voids excluded") }
        item { V3MetricCard("V3 Brier", state.modelBrier?.let(::formatBrier) ?: "—", "lower is better") }
        item { V3MetricCard("Market Brier", state.marketBrier?.let(::formatBrier) ?: "—", "same settled rows") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            V3NoticeCard(state.lastMessage)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            V3GraphCard(
                title = "Probability trace",
                subtitle = "Blue = Kalshi mid · green = V3 forecast · ${chartObservations.lastOrNull()?.ticker ?: "waiting for a market"} · ${chartObservations.size} observations",
                observations = chartObservations
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            V3PnlGraphCard(settled)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            V3GapGraphCard(chartObservations)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Settlement ledger", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        }
        if (state.fills.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                V3NoticeCard("V3 is collecting public quote history. It opens a one-contract shadow only after an exact executable ask clears full spread + fee + 3¢; this does not limit the V2 arena.")
            }
        } else {
            items(state.fills.sortedByDescending { it.createdAtMs }, key = V3ShadowFill::id) { fill ->
                V3FillCard(fill)
            }
        }
    }
}

@Composable
fun ScalpModeTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = DipTheme.colors
    TabRow(
        selectedTabIndex = selected,
        containerColor = colors.surface,
        contentColor = colors.accentBlue
    ) {
        Tab(selected = selected == 0, onClick = { onSelect(0) }, text = { Text("Arena V2") })
        Tab(selected = selected == 1, onClick = { onSelect(1) }, text = { Text("V3 test") })
        Tab(selected = selected == 2, onClick = { onSelect(2) }, text = { Text("AI chat") })
    }
}

@Composable
private fun V3MetricCard(label: String, value: String, detail: String) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
    }
}

@Composable
private fun V3NoticeCard(message: String) {
    val colors = DipTheme.colors
    Text(
        message,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surfaceAlt, RoundedCornerShape(16.dp))
            .padding(14.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSecondary
    )
}

@Composable
private fun V3GraphCard(title: String, subtitle: String, observations: List<V3Observation>) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .background(colors.surface, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        if (observations.size < 2) {
            Text("Waiting for at least two valid quotes for this market.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            ProbabilityCanvas(
                market = observations.map { it.marketYes },
                model = observations.map { it.fairYes },
                marketColor = colors.accentBlue,
                modelColor = colors.up,
                gridColor = colors.border
            )
        }
    }
}

@Composable
private fun V3GapGraphCard(observations: List<V3Observation>) {
    val colors = DipTheme.colors
    val gap = observations.map { (it.fairYes - it.marketYes) * 100.0 }
    Column(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .background(colors.surface, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Forecast minus market", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text("YES probability difference in percentage points; not an executable or after-fee edge.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        if (gap.size < 2) {
            Text("Waiting for at least two valid quotes.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(String.format(Locale.US, "Latest: %+.2f points", gap.last()), color = colors.textPrimary, style = MaterialTheme.typography.labelLarge)
            SeriesCanvas(gap, colors.accentBlue, colors.border)
        }
    }
}

@Composable
private fun V3PnlGraphCard(settled: List<V3ShadowFill>) {
    val colors = DipTheme.colors
    val values = V3ChartData.settledPnl(settled)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .background(colors.surface, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Normalized settlement P&L", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text("One contract per V3 shadow entry; legacy records without a settlement time use their entry time. This is not a profit forecast or stake limit.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        if (values.size < 2) {
            Text("Waiting for settled V3 entries; open entries are not included.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            SeriesCanvas(values, if ((values.lastOrNull() ?: 0.0) >= 0.0) colors.up else colors.down, colors.border)
        }
    }
}

@Composable
private fun V3FillCard(fill: V3ShadowFill) {
    val colors = DipTheme.colors
    val tone = when {
        !fill.settled -> colors.accentBlue
        fill.won == true -> colors.up
        fill.outcome == "void" -> colors.textSecondary
        else -> colors.down
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("${fill.side} · ${if (fill.settled) fill.outcome?.uppercase() ?: "SETTLED" else "OPEN"}", color = tone, fontWeight = FontWeight.Bold)
        Text(fill.ticker, color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
        Text("Ask ${formatCents(fill.entryAsk)} · fee ${formatMoney(fill.entryFee)} · entry EV ${formatSignedMoney(fill.netEvAtEntry)}", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(
            if (fill.settled) "Realized ${formatSignedMoney(fill.pnlPerContract ?: 0.0)} per contract" else "Awaiting Kalshi settlement",
            color = tone,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ProbabilityCanvas(market: List<Double>, model: List<Double>, marketColor: Color, modelColor: Color, gridColor: Color) {
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val width = size.width
        val height = size.height
        listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
            drawLine(gridColor.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(0f, height * fraction), end = androidx.compose.ui.geometry.Offset(width, height * fraction), strokeWidth = 1f)
        }
        linePath(market, width, height, 0.0, 1.0)?.let { drawPath(it, marketColor, style = Stroke(4f, cap = StrokeCap.Round)) }
        linePath(model, width, height, 0.0, 1.0)?.let { drawPath(it, modelColor, style = Stroke(4f, cap = StrokeCap.Round)) }
    }
}

@Composable
private fun SeriesCanvas(values: List<Double>, color: Color, gridColor: Color) {
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val low = minOf(0.0, values.minOrNull() ?: 0.0)
        val high = maxOf(0.0, values.maxOrNull() ?: 0.0)
        val pad = if (high - low < 0.01) 0.01 else (high - low) * 0.12
        val min = low - pad
        val max = high + pad
        val zeroY = size.height - ((0.0 - min) / (max - min) * size.height).toFloat()
        drawLine(gridColor.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(0f, zeroY), androidx.compose.ui.geometry.Offset(size.width, zeroY), strokeWidth = 1f)
        linePath(values, size.width, size.height, min, max)?.let { drawPath(it, color, style = Stroke(4f, cap = StrokeCap.Round)) }
    }
}

private fun linePath(values: List<Double>, width: Float, height: Float, min: Double, max: Double): Path? {
    if (values.size < 2 || max <= min) return null
    return Path().apply {
        values.forEachIndexed { index, value ->
            val x = index.toFloat() / (values.lastIndex).toFloat() * width
            val y = height - (((value - min) / (max - min)).toFloat() * height)
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}

private fun formatBrier(value: Double): String = String.format(Locale.US, "%.4f", value)
private fun formatMoney(value: Double): String = String.format(Locale.US, "$%.3f", value)
private fun formatSignedMoney(value: Double): String = String.format(Locale.US, "%+.3f", value)
private fun formatCents(value: Double): String = String.format(Locale.US, "%.1f¢", value * 100.0)
