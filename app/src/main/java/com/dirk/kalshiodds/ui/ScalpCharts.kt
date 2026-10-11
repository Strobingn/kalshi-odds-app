package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** P&L bars and the combined equity line for the 15 paper scalps. */
@Composable
fun ScalpCharts(accounts: List<ScalpAccount>) {
    val colors = DipTheme.colors
    val ahead = accounts.count { it.pnlUsd() >= 0.0 }
    val behind = accounts.size - ahead
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Scalp numbers",
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            String.format(Locale.US, "%d ahead · %d behind", ahead, behind),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Text(
            "Combined equity",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        EquityLine(
            points = combinedEquity(accounts),
            modifier = Modifier.fillMaxWidth().height(88.dp)
        )
        Text(
            "P&L by book",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        PnlBars(accounts.sortedByDescending { it.pnlUsd() })
    }
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
        drawLine(
            color = colors.border,
            start = Offset(0f, baseY),
            end = Offset(size.width, baseY),
            strokeWidth = 1.dp.toPx()
        )
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
private fun PnlBars(accounts: List<ScalpAccount>) {
    val colors = DipTheme.colors
    val maxAbs = accounts.maxOf { abs(it.pnlUsd()) }.coerceAtLeast(1.0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        accounts.forEach { account ->
            val pnl = account.pnlUsd()
            val frac = (pnl / maxAbs).toFloat().coerceIn(-1f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.name,
                    modifier = Modifier.width(92.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(12.dp)
                        .padding(horizontal = 6.dp)
                ) {
                    val mid = size.width / 2f
                    val bar = size.width / 2f * abs(frac)
                    drawRoundRect(
                        color = colors.border,
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        size = Size(size.width, size.height)
                    )
                    val color = if (pnl >= 0.0) colors.up else colors.down
                    if (pnl >= 0.0) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(mid, 0f),
                            size = Size(bar, size.height),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                    } else {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(mid - bar, 0f),
                            size = Size(bar, size.height),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                    }
                }
                Text(
                    String.format(Locale.US, "%+.0f", pnl),
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pnl >= 0.0) colors.up else colors.down,
                    fontWeight = FontWeight.Bold
                )
            }
        }
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
