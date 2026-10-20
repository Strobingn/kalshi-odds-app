package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/**
 * Time series with a baseline, area fill, and an endpoint.
 * X is time when the stamps span more than zero, otherwise index order.
 */
@Composable
fun RunningSeriesChart(
    points: List<Pair<Long, Double>>,
    baseline: Double,
    modifier: Modifier = Modifier,
    heightDp: Int = 112,
    positive: Color = DipTheme.colors.up,
    negative: Color = DipTheme.colors.down,
    axis: Color = DipTheme.colors.textSecondary,
    showDelta: Boolean = true
) {
    if (points.size < 2) return
    val last = points.last().second
    val line = if (last >= baseline) positive else negative
    Column(modifier.fillMaxWidth()) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
    ) {
        val ys = points.map { it.second } + baseline
        val minY = ys.minOrNull() ?: baseline
        val maxY = ys.maxOrNull() ?: baseline
        val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1.0
        val t0 = points.first().first
        val t1 = points.last().first
        val spanT = t1 - t0
        fun xOf(i: Int, t: Long): Float {
            val frac = if (spanT > 0L) {
                ((t - t0).toFloat() / spanT.toFloat())
            } else {
                i.toFloat() / (points.lastIndex).coerceAtLeast(1).toFloat()
            }
            return size.width * frac.coerceIn(0f, 1f)
        }
        fun yOf(v: Double): Float {
            val frac = ((v - minY) / spanY).toFloat().coerceIn(0f, 1f)
            return size.height * (1f - frac)
        }
        val grid = axis.copy(alpha = 0.18f)
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val baseY = yOf(baseline)
        drawLine(
            axis.copy(alpha = 0.45f),
            Offset(0f, baseY),
            Offset(size.width, baseY),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        )
        val linePath = Path()
        val area = Path()
        points.forEachIndexed { i, (t, v) ->
            val x = xOf(i, t)
            val y = yOf(v)
            if (i == 0) {
                linePath.moveTo(x, y)
                area.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        val endX = xOf(points.lastIndex, points.last().first)
        val startX = xOf(0, points.first().first)
        area.lineTo(endX, baseY)
        area.lineTo(startX, baseY)
        area.close()
        drawPath(area, line.copy(alpha = 0.18f))
        drawPath(linePath, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(line, 4.dp.toPx(), Offset(endX, yOf(last)))
    }
    if (showDelta) {
        Text(
            String.format(Locale.US, "%+.2f vs start", last - baseline),
            color = if (last >= baseline) positive else negative,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    }
}

@Composable
fun OutcomeStrip(
    wonOldestFirst: List<Boolean>,
    modifier: Modifier = Modifier,
    maxMarks: Int = 24
) {
    if (wonOldestFirst.isEmpty()) return
    val colors = DipTheme.colors
    val shown = remember(wonOldestFirst, maxMarks) {
        if (wonOldestFirst.size > maxMarks) wonOldestFirst.takeLast(maxMarks) else wonOldestFirst
    }
    val wins = shown.count { it }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Recent ${shown.size} · $wins W / ${shown.size - wins} L",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { won ->
                Box(
                    Modifier
                        .size(width = 8.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (won) colors.up else colors.down)
                )
            }
        }
    }
}

@Composable
fun WinLossBar(
    wins: Int,
    losses: Int,
    modifier: Modifier = Modifier
) {
    val total = wins + losses
    if (total <= 0) return
    val colors = DipTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        if (wins > 0) {
            Box(
                Modifier
                    .weight(wins.toFloat())
                    .fillMaxHeight()
                    .background(colors.up)
            )
        }
        if (losses > 0) {
            Box(
                Modifier
                    .weight(losses.toFloat())
                    .fillMaxHeight()
                    .background(colors.down)
            )
        }
    }
}
