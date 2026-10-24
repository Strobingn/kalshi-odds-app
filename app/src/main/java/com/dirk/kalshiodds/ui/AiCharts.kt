package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs
import kotlin.math.max

/** The AI tab's chart kit: cards, tiles, stacked bars, signed bars, percent bars, line and donut. All Canvas, no library. */

@Composable
fun AiCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val c = DipTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.textPrimary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.textSecondary) }
        }
        content()
    }
}

@Composable
fun RowScope.StatTile(label: String, value: String, color: Color, sub: String? = null) {
    val c = DipTheme.colors
    Column(
        Modifier
            .weight(1f)
            .background(c.surface, RoundedCornerShape(12.dp))
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 0.6.sp, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        sub?.let { Text(it, fontSize = 10.sp, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    val c = DipTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((label, color) in items) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(9.dp).background(color, CircleShape))
                Text(label, fontSize = 11.sp, color = c.textSecondary)
            }
        }
    }
}

/**
 * Vertical stacked bars. [buckets] are columns, each a list of segment values in [colors] order. [xLabels] has one label
 * per column; blank ones are skipped.
 */
@Composable
fun StackedBars(buckets: List<List<Float>>, colors: List<Color>, xLabels: List<String>, height: Dp = 140.dp, unit: String = "") {
    val c = DipTheme.colors
    val top = buckets.maxOfOrNull { it.sum() }?.takeIf { it > 0f } ?: 1f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("${fmtAxis(top)}$unit", fontSize = 10.sp, color = c.textSecondary)
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = buckets.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = slot * 0.72f
            // grid
            for (g in 1..3) {
                val y = size.height * g / 4f
                drawLine(c.border, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            drawLine(c.border, Offset(0f, size.height), Offset(size.width, size.height), 1.5f)
            buckets.forEachIndexed { i, segs ->
                var y = size.height
                val x = i * slot + (slot - bw) / 2f
                segs.forEachIndexed { j, v ->
                    if (v <= 0f) return@forEachIndexed
                    val h = size.height * (v / top)
                    drawRoundRect(
                        colors[j % colors.size], Offset(x, y - h), Size(bw, h),
                        cornerRadius = CornerRadius(if (j == segs.indexOfLast { it > 0f }) 3f else 0f)
                    )
                    y -= h
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            xLabels.forEach { l ->
                Text(l, Modifier.weight(1f), fontSize = 9.sp, color = c.textSecondary, maxLines = 1, softWrap = false)
            }
        }
    }
}

data class SignedBar(val label: String, val value: Double, val valueText: String, val note: String = "")

/** Horizontal bars around zero: green right for winners, red left for losers. */
@Composable
fun SignedBars(rows: List<SignedBar>, labelWidth: Dp = 118.dp) {
    val c = DipTheme.colors
    val most = rows.maxOfOrNull { abs(it.value) }?.takeIf { it > 0.0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(labelWidth)) {
                    Text(r.label, fontSize = 11.sp, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.note.isNotEmpty()) Text(r.note, fontSize = 9.sp, color = c.textSecondary, maxLines = 1)
                }
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val mid = size.width / 2f
                    drawLine(c.border, Offset(mid, 0f), Offset(mid, size.height), 1.5f)
                    val w = (size.width / 2f) * (abs(r.value) / most).toFloat()
                    val color = if (r.value >= 0) c.up else c.down
                    val x = if (r.value >= 0) mid else mid - w
                    drawRoundRect(color, Offset(x, 2f), Size(max(w, 2f), size.height - 4f), CornerRadius(4f))
                }
                Text(
                    r.valueText, Modifier.width(64.dp).padding(start = 6.dp), fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = if (r.value >= 0) c.up else c.down, maxLines = 1
                )
            }
        }
    }
}

/** 0–100% bars with a dashed 50% line, for win rates. */
@Composable
fun PercentBars(rows: List<Triple<String, Double, String>>, labelWidth: Dp = 118.dp) {
    val c = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, frac, note) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(labelWidth), fontSize = 11.sp, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    drawRoundRect(c.surfaceAlt, Offset.Zero, size, CornerRadius(7f))
                    val f = frac.coerceIn(0.0, 1.0).toFloat()
                    val color = when {
                        frac >= 0.6 -> c.up
                        frac >= 0.45 -> c.accentOrange
                        else -> c.down
                    }
                    drawRoundRect(color, Offset.Zero, Size(size.width * f, size.height), CornerRadius(7f))
                    val m = size.width / 2f
                    drawLine(c.textSecondary, Offset(m, 0f), Offset(m, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                }
                Text(note, Modifier.width(76.dp).padding(start = 6.dp), fontSize = 11.sp, color = c.textSecondary, maxLines = 1)
            }
        }
    }
}

/** A line over [values] with a zero line and a soft fill; green when it ends up, red when it ends down. */
@Composable
fun LineChart(values: List<Double>, height: Dp = 130.dp, format: (Double) -> String = { fmtUsd(it) }) {
    val c = DipTheme.colors
    if (values.size < 2) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("Not enough data yet", fontSize = 12.sp, color = c.textSecondary)
        }
        return
    }
    val lo = minOf(0.0, values.min())
    val hi = maxOf(0.0, values.max()).let { if (it == lo) lo + 1.0 else it }
    val color = if (values.last() >= 0) c.up else c.down
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("high ${format(hi)}", fontSize = 10.sp, color = c.textSecondary)
            Text("now ${format(values.last())}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            val step = size.width / (values.size - 1)
            val zero = y(0.0)
            drawLine(c.textSecondary, Offset(0f, zero), Offset(size.width, zero), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val line = Path()
            val fill = Path()
            values.forEachIndexed { i, v ->
                val x = i * step
                if (i == 0) { line.moveTo(x, y(v)); fill.moveTo(x, zero); fill.lineTo(x, y(v)) } else { line.lineTo(x, y(v)); fill.lineTo(x, y(v)) }
            }
            fill.lineTo(size.width, zero); fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.03f))))
            drawPath(line, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            drawCircle(color, 6f, Offset(size.width, y(values.last())))
        }
        Text("low ${format(lo)}", fontSize = 10.sp, color = c.textSecondary)
    }
}

/** A ring split by [slices] with [center] text in the middle and a legend at the side. */
@Composable
fun Donut(slices: List<Triple<String, Float, Color>>, center: String, centerSub: String, size: Dp = 120.dp) {
    val c = DipTheme.colors
    val total = slices.sumOf { it.second.toDouble() }.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = this.size.width * 0.16f
                val inset = stroke / 2f
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                if (total <= 0f) {
                    drawArc(c.surfaceAlt, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                } else {
                    var start = -90f
                    for ((_, v, col) in slices) {
                        val sweep = 360f * v / total
                        if (sweep > 0f) drawArc(col, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(center, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Text(centerSub, fontSize = 10.sp, color = c.textSecondary)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, v, col) in slices) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(col, CircleShape))
                    Text("$label  ${v.toInt()}", fontSize = 12.sp, color = c.textPrimary)
                }
            }
        }
    }
}

fun fmtUsd(v: Double): String {
    val a = abs(v)
    val s = if (a >= 1000) String.format(java.util.Locale.US, "%,.0f", a) else String.format(java.util.Locale.US, "%.2f", a)
    return (if (v < 0) "-$" else "$") + s
}

fun fmtSignedUsd(v: Double): String = (if (v > 0) "+" else "") + fmtUsd(v)

private fun fmtAxis(v: Float): String = if (v >= 10f || v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
