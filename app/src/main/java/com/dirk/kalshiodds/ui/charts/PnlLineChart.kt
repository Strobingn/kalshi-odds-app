package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Cumulative P&L line (dollars, oldest first) with a zero baseline and a soft
 * fill between the line and zero. Fewer than two finite points shows [ChartEmptyState].
 */
@Composable
fun PnlLineChart(values: List<Double>, modifier: Modifier = Modifier, height: Dp = 140.dp, showLabels: Boolean = true) {
    val c = DipTheme.colors
    val pts = values.filter { it.isFinite() }
    if (pts.size < 2) { ChartEmptyState(modifier, height); return }
    val color = chartSignColor(pts.last())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabels) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(chartMoney(pts.last()), style = MaterialTheme.typography.labelMedium, color = chartSignTextColor(pts.last()))
                Text("low ${chartMoney(pts.min())} · high ${chartMoney(pts.max())}",
                    style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(height).semantics {
            contentDescription = "Cumulative P&L over ${pts.size} points, now ${chartMoney(pts.last())}"
        }) { drawPnl(pts, color, c.border, dashedZero = true) }
    }
}

/** Card-sized cumulative P&L line. No labels; "No data yet" under two points. */
@Composable
fun PnlSparkline(values: List<Double>, modifier: Modifier = Modifier, height: Dp = 32.dp, color: Color? = null) {
    val c = DipTheme.colors
    val pts = values.filter { it.isFinite() }
    if (pts.size < 2) { ChartEmptyState(modifier, height); return }
    val ink = color ?: chartSignColor(pts.last())
    Canvas(modifier.fillMaxWidth().height(height).semantics {
        contentDescription = "P&L trend, now ${chartMoney(pts.last())}"
    }) { drawPnl(pts, ink, c.border, dashedZero = false) }
}

private fun DrawScope.drawPnl(pts: List<Double>, color: Color, zeroColor: Color, dashedZero: Boolean) {
    val low = minOf(0.0, pts.min()); val high = maxOf(0.0, pts.max())
    val pad = ((high - low) * 0.08).coerceAtLeast(0.05)
    val lo = low - pad; val hi = high + pad
    val inset = 4.dp.toPx()
    fun x(i: Int) = inset + (size.width - 2 * inset) * i / (pts.size - 1).toFloat()
    fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
    val zero = y(0.0)
    drawLine(zeroColor, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx(),
        pathEffect = if (dashedZero) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null)
    val line = Path().apply { pts.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
    val fill = Path().apply {
        moveTo(x(0), zero); pts.forEachIndexed { i, v -> lineTo(x(i), y(v)) }; lineTo(x(pts.lastIndex), zero); close()
    }
    drawPath(fill, color.copy(alpha = 0.15f))
    drawPath(line, color, style = Stroke(width = ChartStroke.SERIES_DP.dp.toPx(), cap = StrokeCap.Round))
    drawCircle(color, 3.dp.toPx(), Offset(x(pts.lastIndex), y(pts.last())))
}
