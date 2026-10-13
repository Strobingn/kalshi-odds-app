package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalp.ScalpAccount
import com.dirk.kalshiodds.signal.scalp.ScalpCatalog
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs

/** Comparison charts and a full table for the 20 paper scalps. */
@Composable
fun ScalpCharts(accounts: List<ScalpAccount>) {
    val colors = DipTheme.colors
    val ranked = accounts.sortedByDescending { it.pnlUsd() }
    val ahead = accounts.count { it.pnlUsd() >= 0.0 }
    val behind = accounts.size - ahead
    val trades = accounts.sumOf { it.trades }
    val wins = accounts.sumOf { it.wins }
    val avgTrade = if (trades > 0) accounts.sumOf { it.pnlUsd() } / trades else 0.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Comparison",
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            String.format(
                Locale.US,
                "%d ahead · %d behind · best %+.0f · worst %+.0f · avg trade %+.2f",
                ahead,
                behind,
                ranked.firstOrNull()?.pnlUsd() ?: 0.0,
                ranked.lastOrNull()?.pnlUsd() ?: 0.0,
                avgTrade
            ),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Section("Combined equity")
        EquityLine(
            points = combinedEquity(accounts),
            modifier = Modifier.fillMaxWidth().height(88.dp)
        )
        Section("Every book, overlaid")
        OverlayCurves(ranked, Modifier.fillMaxWidth().height(120.dp))
        Section("P&L")
        SignedBars(ranked) { it.pnlUsd() }
        Section("Win rate")
        PositiveBars(ranked, 100.0) { if (it.trades > 0) 100.0 * it.wins / it.trades else 0.0 }
        Section("Trades")
        PositiveBars(ranked, ranked.maxOf { it.trades }.toDouble().coerceAtLeast(1.0)) { it.trades.toDouble() }
        Section("Average per trade")
        SignedBars(ranked) { if (it.trades > 0) it.pnlUsd() / it.trades else 0.0 }
        Section("Cash vs $20,000")
        SignedBars(ranked) { it.cashUsd - ScalpCatalog.START_CASH }
        Section("Size still used")
        PositiveBars(ranked, 1.0) { it.sizeScale }
        Section("All numbers")
        ComparisonTable(ranked)
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = DipTheme.colors.textSecondary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun OverlayCurves(accounts: List<ScalpAccount>, modifier: Modifier) {
    val colors = DipTheme.colors
    Canvas(modifier) {
        if (accounts.isEmpty()) return@Canvas
        val series = accounts.map { account ->
            val raw = account.curve.ifEmpty { listOf(ScalpCatalog.START_CASH) }.map { it.toFloat() }
            if (raw.size == 1) listOf(raw[0], raw[0]) else raw
        }
        val flat = series.flatten()
        val min = flat.min()
        val max = flat.max()
        val span = (max - min).coerceAtLeast(1f)
        val start = ScalpCatalog.START_CASH.toFloat()
        val baseY = size.height * (1f - ((start - min) / span).coerceIn(0f, 1f))
        drawLine(colors.border, Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())
        series.forEachIndexed { i, points ->
            val account = accounts[i]
            val path = Path()
            val last = points.lastIndex
            points.forEachIndexed { j, v ->
                val x = size.width * j / last
                val y = size.height * (1f - ((v - min) / span).coerceIn(0f, 1f))
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val color = if (account.pnlUsd() >= 0.0) colors.up else colors.down
            val width = if (i == 0) 2.4.dp.toPx() else 1.2.dp.toPx()
            drawPath(path, color.copy(alpha = if (i < 3) 0.95f else 0.45f), style = Stroke(width, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun SignedBars(accounts: List<ScalpAccount>, value: (ScalpAccount) -> Double) {
    val colors = DipTheme.colors
    val maxAbs = accounts.maxOf { abs(value(it)) }.coerceAtLeast(0.01)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        accounts.forEach { account ->
            val v = value(account)
            val frac = (v / maxAbs).toFloat().coerceIn(-1f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Name(account.name)
                Canvas(Modifier.weight(1f).height(10.dp).padding(horizontal = 6.dp)) {
                    val mid = size.width / 2f
                    val bar = size.width / 2f * abs(frac)
                    drawRoundRect(colors.border, cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()), size = Size(size.width, size.height))
                    val color = if (v >= 0.0) colors.up else colors.down
                    if (v >= 0.0) {
                        drawRoundRect(color, Offset(mid, 0f), Size(bar, size.height), CornerRadius(4.dp.toPx(), 4.dp.toPx()))
                    } else {
                        drawRoundRect(color, Offset(mid - bar, 0f), Size(bar, size.height), CornerRadius(4.dp.toPx(), 4.dp.toPx()))
                    }
                }
                Text(
                    String.format(Locale.US, "%+.0f", v),
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (v >= 0.0) colors.up else colors.down,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PositiveBars(accounts: List<ScalpAccount>, max: Double, value: (ScalpAccount) -> Double) {
    val colors = DipTheme.colors
    val top = max.coerceAtLeast(0.01)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        accounts.forEach { account ->
            val v = value(account)
            val frac = (v / top).toFloat().coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Name(account.name)
                Canvas(Modifier.weight(1f).height(10.dp).padding(horizontal = 6.dp)) {
                    drawRoundRect(colors.border, cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()), size = Size(size.width, size.height))
                    drawRoundRect(
                        colors.accentBlue,
                        Offset.Zero,
                        Size(size.width * frac, size.height),
                        CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
                Text(
                    when {
                        top == 100.0 -> String.format(Locale.US, "%.0f%%", v)
                        top <= 1.0 -> String.format(Locale.US, "%.0f%%", v * 100.0)
                        else -> String.format(Locale.US, "%.0f", v)
                    },
                    modifier = Modifier.width(48.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ComparisonTable(accounts: List<ScalpAccount>) {
    val colors = DipTheme.colors
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        TableRow("Book", "P&L", "Cash", "Trades", "Wins", "Win%", "Avg", "Size", "Open", header = true)
        accounts.forEach { a ->
            val pos = a.position
            TableRow(
                a.name,
                String.format(Locale.US, "%+.0f", a.pnlUsd()),
                String.format(Locale.US, "$%,.0f", a.cashUsd),
                a.trades.toString(),
                a.wins.toString(),
                if (a.trades > 0) String.format(Locale.US, "%.0f%%", 100.0 * a.wins / a.trades) else "—",
                if (a.trades > 0) String.format(Locale.US, "%+.0f", a.pnlUsd() / a.trades) else "—",
                String.format(Locale.US, "%.0f%%", a.sizeScale * 100.0),
                if (pos == null) "flat" else String.format(Locale.US, "%s %.0f¢", pos.side, pos.lastBid * 100.0),
                header = false,
                pnl = a.pnlUsd()
            )
        }
    }
    Text(
        "Scroll the table sideways.",
        style = MaterialTheme.typography.labelSmall,
        color = colors.textSecondary
    )
}

@Composable
private fun TableRow(
    name: String,
    pnl: String,
    cash: String,
    trades: String,
    wins: String,
    rate: String,
    avg: String,
    size: String,
    open: String,
    header: Boolean,
    pnlValue: Double = 0.0
) {
    val colors = DipTheme.colors
    val style = MaterialTheme.typography.labelSmall
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    val color = if (header) colors.textSecondary else colors.textPrimary
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .background(if (header) colors.surface else androidx.compose.ui.graphics.Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cell(name, 96.dp, color, weight)
        Cell(pnl, 64.dp, if (header) color else if (pnlValue >= 0.0) colors.up else colors.down, FontWeight.Bold)
        Cell(cash, 72.dp, color, weight)
        Cell(trades, 48.dp, color, weight)
        Cell(wins, 40.dp, color, weight)
        Cell(rate, 44.dp, color, weight)
        Cell(avg, 48.dp, color, weight)
        Cell(size, 44.dp, color, weight)
        Cell(open, 64.dp, color, weight)
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color, weight: FontWeight) {
    Text(
        text,
        modifier = Modifier.width(width).padding(horizontal = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun Name(name: String) {
    Text(
        name,
        modifier = Modifier.width(92.dp),
        style = MaterialTheme.typography.labelSmall,
        color = DipTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun EquityLine(points: List<Float>, modifier: Modifier = Modifier) {
    val colors = DipTheme.colors
    val up = points.size >= 2 && points.last() + 0.01f >= points.first()
    val line = if (up) colors.up else colors.down
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val min = points.min()
        val max = points.max()
        val span = (max - min).coerceAtLeast(1f)
        val mid = points.first()
        val baseY = size.height * (1f - ((mid - min) / span).coerceIn(0f, 1f))
        drawLine(colors.border, Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())
        val path = Path()
        val last = points.lastIndex
        points.forEachIndexed { i, v ->
            val x = size.width * i / last
            val y = size.height * (1f - ((v - min) / span).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun ScalpSpark(account: ScalpAccount, modifier: Modifier = Modifier) {
    val points = account.curve.ifEmpty { listOf(ScalpCatalog.START_CASH) }.map { it.toFloat() }
    if (points.size < 2) {
        Box(modifier.height(28.dp))
        return
    }
    EquityLine(points, modifier.fillMaxWidth().height(28.dp))
}

/** Right-align each book's equity so a late starter doesn't shift the sum. */
fun combinedEquity(accounts: List<ScalpAccount>): List<Float> {
    val series = accounts.map { account ->
        val raw = account.curve.ifEmpty { listOf(ScalpCatalog.START_CASH) }
        if (raw.size == 1) listOf(raw[0], raw[0]) else raw
    }
    if (series.isEmpty()) return emptyList()
    val n = series.maxOf { it.size }
    return (0 until n).map { i ->
        series.sumOf { s ->
            val idx = i - (n - s.size)
            if (idx < 0) ScalpCatalog.START_CASH else s[idx]
        }.toFloat()
    }
}
