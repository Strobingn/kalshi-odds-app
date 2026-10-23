package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** One calibration point: what was predicted, what happened, how many rows. */
data class CalPoint(val predicted: Double, val actual: Double, val n: Int)

/**
 * Predicted against actual, with the diagonal a perfectly calibrated model sits on. Points grow with their row
 * count and are joined in order. Both axes share one range so the diagonal is honest.
 */
@Composable
fun CalibrationPlot(points: List<CalPoint>, format: (Double) -> String, height: Dp = 170.dp, xName: String = "predicted", yName: String = "actual") {
    val c = DipTheme.colors
    if (points.size < 2) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("Not enough data yet", fontSize = 12.sp, color = c.textSecondary)
        }
        return
    }
    val all = points.flatMap { listOf(it.predicted, it.actual) }
    var lo = all.min()
    var hi = all.max()
    if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
    val pad = (hi - lo) * 0.08
    lo -= pad
    hi += pad
    val most = points.maxOf { it.n }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$yName ↑ up to ${format(hi)}", fontSize = 10.sp, color = c.textSecondary)
            Text("on the dashed line = calibrated", fontSize = 10.sp, color = c.textSecondary)
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            fun x(v: Double) = (size.width * (v - lo) / (hi - lo)).toFloat()
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            drawRect(c.surfaceAlt, style = Stroke(1.5f))
            drawLine(c.textSecondary, Offset(x(lo), y(lo)), Offset(x(hi), y(hi)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            if (lo < 0 && hi > 0) {
                drawLine(c.border, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)
                drawLine(c.border, Offset(x(0.0), 0f), Offset(x(0.0), size.height), 1f)
            }
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.predicted), y(p.actual)) else path.lineTo(x(p.predicted), y(p.actual)) }
            drawPath(path, c.accentBlue, style = Stroke(width = 3f, cap = StrokeCap.Round))
            for (p in points) {
                val r = 5f + 9f * sqrt(p.n.toFloat() / most)
                val col = if (p.actual >= p.predicted - (hi - lo) * 0.02) c.up else c.down
                drawCircle(col.copy(alpha = 0.85f), r, Offset(x(p.predicted), y(p.actual)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format(lo), fontSize = 10.sp, color = c.textSecondary)
            Text("$xName →", fontSize = 10.sp, color = c.textSecondary)
            Text(format(hi), fontSize = 10.sp, color = c.textSecondary)
        }
    }
}

/** Vertical bars around zero, one per label (minutes, hours): green up, red down; every [labelEvery]-th label shown. */
@Composable
fun SignedColumns(labels: List<String>, values: List<Double>, height: Dp = 120.dp, labelEvery: Int = 1, format: (Double) -> String = { fmtSignedUsd(it) }) {
    val c = DipTheme.colors
    if (values.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("Not enough data yet", fontSize = 12.sp, color = c.textSecondary)
        }
        return
    }
    val most = values.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
    val best = values.indices.maxByOrNull { values[it] }
    val worst = values.indices.minByOrNull { values[it] }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(best?.let { "best ${labels[it]} ${format(values[it])}" }.orEmpty(), fontSize = 10.sp, color = c.up)
            Text(worst?.let { "worst ${labels[it]} ${format(values[it])}" }.orEmpty(), fontSize = 10.sp, color = c.down)
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size
            val slot = size.width / n
            val w = max(slot * 0.7f, 2f)
            val mid = size.height / 2f
            drawLine(c.border, Offset(0f, mid), Offset(size.width, mid), 1.5f)
            values.forEachIndexed { i, v ->
                val h = (mid - 2f) * (abs(v) / most).toFloat()
                val x = i * slot + (slot - w) / 2f
                val col = if (v >= 0) c.up else c.down
                if (v >= 0) drawRoundRect(col, Offset(x, mid - h), Size(w, max(h, 1.5f)), CornerRadius(3f))
                else drawRoundRect(col, Offset(x, mid), Size(w, max(h, 1.5f)), CornerRadius(3f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(if (i % labelEvery == 0) l else "", Modifier.weight(1f), fontSize = 8.sp, color = c.textSecondary, maxLines = 1)
            }
        }
    }
}
