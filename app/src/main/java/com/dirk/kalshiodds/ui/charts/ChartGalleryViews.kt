package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private fun heatColor(rate: Double?, n: Int, up: Color, down: Color, empty: Color): Color {
    if (n <= 0 || rate == null || !rate.isFinite()) return empty
    val dist = (abs(rate - 0.5) / 0.5).toFloat().coerceIn(0f, 1f)
    val alpha = 0.35f + 0.65f * dist
    return if (rate >= 0.5) up.copy(alpha = alpha) else down.copy(alpha = alpha)
}

/** Trailing win rate. The line is empty until 30 closed trades exist. */
@Composable
fun RollingWinRateChart(samples: List<ChartShapes.Sample>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier.fillMaxWidth().testTag("chart_rolling")
            .semantics { contentDescription = "Rolling 30-trade win rate" },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Rolling 30-trade win rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Share of the last 30 closed trades that finished above zero. UP and DOWN are in the same line.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        MultiLineChart(
            if (samples.isEmpty()) emptyList() else listOf(LineSeries("Win rate", c.upButton, samples)),
            yFormat = { "${it.roundToInt()}%" },
            xFormat = { chartDayClock(it) },
            fixedRange = 0.0 to 100.0,
            guideY = 50.0,
            guideLabel = "50%",
            contentLabel = "Rolling 30-trade win rate"
        )
    }
}

/** Closed-trade P&L. Loss bins are red, gain bins are green. */
@Composable
fun PnlHistogram(bins: List<ChartGallery.SignedBin>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier.fillMaxWidth().testTag("chart_pnl_hist")
            .semantics { contentDescription = "P and L histogram" },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("P&L histogram", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Closed trades, both sides. Green bins gained, red bins lost.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (bins.isEmpty()) {
            ChartEmptyState(height = 120.dp)
            return
        }
        val peak = bins.maxBy { it.count }
        Text("${peak.label} · ${peak.count}", color = c.textPrimary, style = MaterialTheme.typography.labelMedium)
        val max = bins.maxOf { it.count }.coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val slot = size.width / bins.size.toFloat()
            bins.forEachIndexed { i, bin ->
                val color = when {
                    bin.sign > 0 -> c.upButton
                    bin.sign < 0 -> c.downButton
                    else -> c.textSecondary
                }
                val h = (size.height - 4.dp.toPx()) * bin.count / max.toFloat()
                if (h > 0f) {
                    drawRoundRect(
                        color,
                        Offset(i * slot + slot * 0.15f, size.height - h),
                        Size(slot * 0.7f, h),
                        CornerRadius(3.dp.toPx())
                    )
                }
            }
        }
        bins.forEach { bin ->
            Text("${bin.label} · ${bin.count}", color = if (bin.sign < 0) c.down else if (bin.sign > 0) c.up else c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Entry price versus exit price, in cents. Green won, red lost. */
@Composable
fun EntryExitScatter(dots: List<ChartGallery.ScatterDot>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier.fillMaxWidth().testTag("chart_scatter")
            .semantics { contentDescription = "Entry versus exit price, colored by win and loss" },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Entry versus exit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Cents. Green won, red lost. The dashed line is exit equal to entry.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (dots.isEmpty()) {
            ChartEmptyState(height = 160.dp)
            return
        }
        val wins = dots.count { it.win }
        Text(ChartGallery.winLossCaption(wins, dots.size - wins), color = c.textPrimary, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Win", color = c.up, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Loss", color = c.down, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Canvas(
            Modifier.fillMaxWidth().height(200.dp)
                .semantics { contentDescription = "Entry exit scatter, ${dots.size} trades" }
        ) {
            val left = 28.dp.toPx()
            val bottom = 20.dp.toPx()
            val top = 8.dp.toPx()
            val plotW = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            val plotH = (size.height - top - bottom).coerceAtLeast(1f)
            fun x(cents: Double) = left + plotW * (cents / 100.0).toFloat().coerceIn(0f, 1f)
            fun y(cents: Double) = top + plotH * (1f - (cents / 100.0).toFloat().coerceIn(0f, 1f))
            drawLine(c.border, Offset(left, top), Offset(left, top + plotH), 1.dp.toPx())
            drawLine(c.border, Offset(left, top + plotH), Offset(left + plotW, top + plotH), 1.dp.toPx())
            drawLine(
                c.textSecondary.copy(alpha = 0.7f),
                Offset(x(0.0), y(0.0)),
                Offset(x(100.0), y(100.0)),
                1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            )
            dots.forEach { dot ->
                drawCircle(if (dot.win) c.upButton else c.downButton, 5.dp.toPx(), Offset(x(dot.entryCents), y(dot.exitCents)))
            }
        }
    }
}

/** Grouped bars: UP and DOWN entry counts, then UP and DOWN net, one row per strategy. */
@Composable
fun StrategySideBars(groups: List<ChartGallery.SideGroup>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .testTag("chart_side_bars")
            .semantics { contentDescription = "UP and DOWN entries and net by strategy" },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("UP and DOWN by strategy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Entries and net after fees. Both sides on each row.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (groups.isEmpty()) {
            ChartEmptyState(height = 120.dp)
            return
        }
        val maxEntries = groups.maxOf { max(it.upEntries, it.downEntries) }.coerceAtLeast(1)
        val maxNet = groups.maxOf { max(abs(it.upNet), abs(it.downNet)) }.coerceAtLeast(0.01)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("UP", color = c.up, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("DOWN", color = c.down, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        groups.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(g.label, color = c.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("UP ${g.upEntries} · ${chartMoney(g.upNet)}", color = c.up, style = MaterialTheme.typography.labelSmall)
                    Text("DOWN ${g.downEntries} · ${chartMoney(g.downNet)}", color = c.down, style = MaterialTheme.typography.labelSmall)
                }
                Canvas(Modifier.fillMaxWidth().height(36.dp).padding(bottom = 4.dp)) {
                    val gap = 8.dp.toPx()
                    val half = (size.width - gap) / 2f
                    val rowH = 12.dp.toPx()
                    fun entryBar(up: Boolean, count: Int) {
                        val y = if (up) 2.dp.toPx() else rowH + 6.dp.toPx()
                        val w = half * count / maxEntries.toFloat()
                        if (w > 0f) drawRoundRect(if (up) c.upButton else c.downButton, Offset(0f, y), Size(w, rowH), CornerRadius(3.dp.toPx()))
                    }
                    entryBar(true, g.upEntries)
                    entryBar(false, g.downEntries)
                    val zero = half + gap + half / 2f
                    val room = half / 2f - 2.dp.toPx()
                    drawLine(c.border, Offset(zero, 0f), Offset(zero, size.height), 1.dp.toPx())
                    fun netBar(up: Boolean, value: Double) {
                        val y = if (up) 2.dp.toPx() else rowH + 6.dp.toPx()
                        val len = (room * (abs(value) / maxNet)).toFloat()
                        if (len <= 0f) return
                        val left = if (value >= 0) zero else zero - len
                        drawRoundRect(if (up) c.upButton else c.downButton, Offset(left, y), Size(len, rowH), CornerRadius(3.dp.toPx()))
                    }
                    netBar(true, g.upNet)
                    netBar(false, g.downNet)
                }
            }
        }
    }
}

/** Win rate for each coin across the 24 Eastern hours. UP and DOWN trades share the cells. */
@Composable
fun WinRateHeatmap(grid: ChartGallery.HeatGrid?, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier.fillMaxWidth().testTag("chart_win_heat")
            .semantics { contentDescription = "Win rate by hour of day and coin" },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Win rate by hour and coin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Closed trades by entry hour, Eastern. Green is at least half won, red is under half. Grey has no trades.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (grid == null || grid.rows.isEmpty()) {
            ChartEmptyState(height = 120.dp)
            return
        }
        val busiest = grid.cells.flatten().filter { it.n > 0 }.maxByOrNull { it.n }
        Text(
            busiest?.let { "${it.row} ${it.column} · ${chartPct(it.rate ?: 0.0)} · ${it.n} trades" } ?: "No closed trades",
            color = c.textPrimary,
            style = MaterialTheme.typography.labelMedium
        )
        grid.rows.forEachIndexed { r, coin ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(coin, Modifier.width(36.dp), color = c.textPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Canvas(Modifier.weight(1f).height(28.dp)) {
                    val slot = size.width / 24f
                    grid.cells[r].forEachIndexed { h, cell ->
                        drawRoundRect(
                            heatColor(cell.rate, cell.n, c.upButton, c.downButton, c.surfaceAlt),
                            Offset(h * slot + 1.dp.toPx(), 2.dp.toPx()),
                            Size((slot - 2.dp.toPx()).coerceAtLeast(1f), size.height - 4.dp.toPx()),
                            CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 42.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18).forEach { h ->
                Text(ChartShapes.hourLabel(h), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Pairwise daily-net correlation. Positive is green, negative is red, undefined is grey. */
@Composable
fun CorrelationHeatmap(corr: ChartGallery.Correlation?, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier.fillMaxWidth().testTag("chart_corr")
            .semantics { contentDescription = "Strategy daily P and L correlation" },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Daily P&L correlation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Pearson correlation of Eastern-day nets. Up to 12 strategies. Green moves together, red moves apart.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        val labels = corr?.labels.orEmpty()
        val matrix = corr?.matrix.orEmpty()
        if (labels.size < 2 || matrix.size != labels.size) {
            ChartEmptyState(height = 120.dp)
            return
        }
        val pair = labels.indices.flatMap { i ->
            ((i + 1) until labels.size).mapNotNull { j ->
                matrix.getOrNull(i)?.getOrNull(j)?.takeIf { it.isFinite() }?.let { Triple(labels[i], labels[j], it) }
            }
        }.maxByOrNull { abs(it.third) }
        Text(
            pair?.let { "${it.first} × ${it.second} · ${ChartGallery.corrText(it.third)}" } ?: "No overlapping days",
            color = c.textPrimary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        labels.forEachIndexed { r, label ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, Modifier.width(108.dp), color = c.textPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(1f).height(22.dp)) {
                    val row = matrix.getOrNull(r).orEmpty()
                    val slot = size.width / labels.size.toFloat()
                    labels.indices.forEach { col ->
                        val value = row.getOrNull(col)
                        drawRoundRect(
                            corrColor(value, c.upButton, c.downButton, c.surfaceAlt),
                            Offset(col * slot + 1.dp.toPx(), 1.dp.toPx()),
                            Size((slot - 2.dp.toPx()).coerceAtLeast(1f), size.height - 2.dp.toPx()),
                            CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

private fun corrColor(r: Double?, up: Color, down: Color, empty: Color): Color {
    if (r == null || !r.isFinite()) return empty
    val alpha = (0.28f + 0.72f * abs(r).toFloat()).coerceIn(0.28f, 1f)
    return when {
        r > 0.02 -> up.copy(alpha = alpha)
        r < -0.02 -> down.copy(alpha = alpha)
        else -> empty
    }
}

/** One sparkline per strategy. Fewer than two points is a placeholder in that cell. Frozen is grey. */
@Composable
fun EquitySparkGrid(lines: List<ChartGallery.SparkLine>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier.fillMaxWidth().testTag("chart_sparks")
            .semantics { contentDescription = "Equity curve sparkline for each strategy" },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Equity curves", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("One line per strategy, from recorded window nets. Starts at $0.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (lines.isEmpty()) {
            ChartEmptyState(height = 120.dp)
            return
        }
        lines.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { line ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (line.frozen) "${line.label} · FROZEN" else line.label,
                            color = if (line.frozen) c.textSecondary else c.textPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        PnlSparkline(
                            line.values,
                            Modifier.fillMaxWidth(),
                            height = 36.dp,
                            color = if (line.frozen) c.textSecondary else null
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Net after fees, best first. Frozen strategies are grey and still listed. */
@Composable
fun StrategyLeaderboard(rows: List<ChartGallery.LeaderRow>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    val shown = ChartGallery.leaderboard(rows)
    Column(
        modifier.fillMaxWidth().testTag("chart_leaderboard")
            .semantics { contentDescription = "Strategy leaderboard by net dollars. Frozen strategies stay visible." },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Strategy leaderboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Net after fees. FROZEN stays on the board in grey.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (shown.isEmpty()) {
            ChartEmptyState(height = 120.dp)
            return
        }
        val maxAbs = shown.maxOf { abs(it.net) }.coerceAtLeast(0.01)
        val allPos = shown.all { it.net >= 0.0 }
        val allNeg = shown.all { it.net <= 0.0 }
        shown.forEach { row ->
            val ink = if (row.frozen) c.textSecondary else c.textPrimary
            val bar = if (row.frozen) c.textSecondary.copy(alpha = 0.55f) else if (row.net >= 0) c.upButton else c.downButton
            val name = if (row.frozen) "${row.label} · FROZEN" else row.label
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, Modifier.width(132.dp), color = ink, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(1f).height(16.dp).semantics { contentDescription = "$name ${chartMoney(row.net)}" }) {
                    val zero = when {
                        allPos -> 0f
                        allNeg -> size.width
                        else -> size.width / 2f
                    }
                    val room = if (allPos || allNeg) size.width else size.width / 2f
                    val len = (abs(row.net) / maxAbs * room).toFloat()
                    val left = if (row.net >= 0) zero else zero - len
                    if (len > 0f) drawRoundRect(bar, Offset(left, 2.dp.toPx()), Size(len, size.height - 4.dp.toPx()), CornerRadius(3.dp.toPx()))
                    drawLine(c.border, Offset(zero, 0f), Offset(zero, size.height), 1.dp.toPx())
                }
                Text(
                    chartMoney(row.net),
                    Modifier.width(72.dp),
                    color = if (row.frozen) c.textSecondary else chartSignTextColor(row.net),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
