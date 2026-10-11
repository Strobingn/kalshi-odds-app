package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.ScalpStrategies
import com.dirk.kalshiodds.ui.theme.DipPalette
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/**
 * Per-strategy chart card: cumulative settled P&L line, per-fill win/loss
 * bars, and a compact stat row. Drawn with Canvas \u2014 no chart library.
 */
@Composable
fun StrategyChartCard(
    kind: ScalpStrategies.Kind,
    fills: List<PaperFill>,
    colors: DipPalette
) {
    val settled = fills.filter { it.settled && it.pnlUsd != null }
        .sortedBy { it.createdAtMs }
    val cumulative = ArrayList<Double>()
    var run = 0.0
    settled.forEach { run += it.pnlUsd ?: 0.0; cumulative.add(run) }
    val wins = settled.count { (it.pnlUsd ?: 0.0) > 0 }
    val losses = settled.size - wins
    val totalPnl = run

    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                kind.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                String.format(Locale.US, "%+.2f", totalPnl),
                style = MaterialTheme.typography.titleSmall,
                color = if (totalPnl > 0) colors.up else if (totalPnl < 0) colors.down else colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        if (settled.isEmpty()) {
            Text(
                "No settled fills yet",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
            return@Column
        }

        // Cumulative P&L line
        PnlLineChart(
            points = cumulative,
            positive = totalPnl >= 0,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 10.dp)
        )

        // Per-fill win/loss bars
        FillBarsChart(
            pnls = settled.map { it.pnlUsd ?: 0.0 },
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(top = 8.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            StatLabel("fills", settled.size.toString(), colors)
            StatLabel("W/L", "$wins/$losses", colors)
            StatLabel(
                "win rate",
                if (settled.isEmpty()) "\u2014"
                else String.format(Locale.US, "%.0f%%", 100.0 * wins / settled.size),
                colors
            )
            StatLabel(
                "avg",
                if (settled.isEmpty()) "\u2014"
                else String.format(Locale.US, "%+.2f", totalPnl / settled.size),
                colors
            )
            StatLabel(
                "best/worst",
                if (settled.isEmpty()) "\u2014"
                else String.format(
                    Locale.US,
                    "%+.0f/%+.0f",
                    (settled.maxOf { it.pnlUsd ?: 0.0 }),
                    (settled.minOf { it.pnlUsd ?: 0.0 })
                ),
                colors
            )
        }
    }
}

@Composable
private fun StatLabel(label: String, value: String, colors: DipPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Cumulative P&L sparkline with a zero baseline and end-point label. */
@Composable
private fun PnlLineChart(
    points: List<Double>,
    positive: Boolean,
    colors: DipPalette,
    modifier: Modifier
) {
    val line = if (positive) colors.up else colors.down
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val pad = 8f
        val minV = (points.min() ).coerceAtMost(0.0)
        val maxV = (points.max()).coerceAtLeast(0.0)
        val span = (maxV - minV).takeIf { it > 1e-9 } ?: 1.0
        fun y(v: Double): Float =
            (h - pad - ((v - minV) / span) * (h - 2 * pad)).toFloat()
        fun x(i: Int): Float =
            pad + (if (points.size == 1) (w - 2 * pad) / 2
            else (i.toFloat() / (points.size - 1)) * (w - 2 * pad))

        // zero baseline
        val zeroY = y(0.0)
        drawLine(
            color = colors.border,
            start = Offset(pad, zeroY),
            end = Offset(w - pad, zeroY),
            strokeWidth = 2f
        )

        // area + line
        val path = Path()
        points.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
        }
        val areaPath = Path()
        areaPath.moveTo(x(0), zeroY)
        points.forEachIndexed { i, v -> areaPath.lineTo(x(i), y(v)) }
        areaPath.lineTo(x(points.size - 1), zeroY)
        areaPath.close()
        drawPath(areaPath, color = line.copy(alpha = 0.12f))
        drawPath(path, color = line, style = Stroke(width = 4f))

        // last point dot
        drawCircle(
            color = line,
            radius = 6f,
            center = Offset(x(points.size - 1), y(points.last()))
        )

        // min/max labels
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(200, 160, 160, 160)
                textSize = 24f
                isAntiAlias = true
            }
            drawText(String.format(Locale.US, "%+.0f", maxV), pad, 28f, paint)
            drawText(String.format(Locale.US, "%+.0f", minV), pad, h - 4f, paint)
        }
    }
}

/** Per-fill vertical bars: green above zero for wins, red below for losses. */
@Composable
private fun FillBarsChart(
    pnls: List<Double>,
    colors: DipPalette,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        if (pnls.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val zeroY = h / 2f
        val maxAbs = pnls.maxOf { kotlin.math.abs(it) }.takeIf { it > 1e-9 } ?: 1.0

        // bars (most recent on the right)
        val barSpace = w / pnls.size.coerceAtLeast(1)
        pnls.forEachIndexed { i, v ->
            val barW = (barSpace * 0.7f).coerceAtMost(12f)
            val barH = ((kotlin.math.abs(v) / maxAbs) * (h / 2f - 4f)).toFloat()
            val left = barSpace * i + (barSpace - barW) / 2f
            if (v >= 0) {
                drawRect(
                    color = colors.up.copy(alpha = 0.8f),
                    topLeft = Offset(left, zeroY - barH),
                    size = androidx.compose.ui.geometry.Size(barW, barH)
                )
            } else {
                drawRect(
                    color = colors.down.copy(alpha = 0.8f),
                    topLeft = Offset(left, zeroY),
                    size = androidx.compose.ui.geometry.Size(barW, barH)
                )
            }
        }

        // zero line
        drawLine(
            color = colors.border,
            start = Offset(0f, zeroY),
            end = Offset(w, zeroY),
            strokeWidth = 1f
        )
    }
}
