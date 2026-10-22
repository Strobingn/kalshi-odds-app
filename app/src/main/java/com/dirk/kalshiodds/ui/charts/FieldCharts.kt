@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dirk.kalshiodds.ui.charts

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LineSeries(
    val name: String,
    val color: Color,
    val points: List<ChartShapes.Sample>,
    val strokeDp: Float = 2.5f,
    val dashed: Boolean = false,
    val maxGapMs: Long? = null
)


/** Bare "1:52 PM" for callers that add the zone themselves. */
fun chartClock(ms: Long): String = com.dirk.kalshiodds.domain.EtTime.clockBare(ms)
fun chartDayClock(ms: Long): String = com.dirk.kalshiodds.domain.EtTime.shortDateClockBare(ms)

/** Axis ticks and drag readouts always carry the Eastern label. */
internal fun withEt(label: String): String = if (label.contains("ET")) label else "$label ET"

@Composable
fun ChartBlock(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = DipTheme.colors.textPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DipTheme.colors.textSecondary)
        content()
    }
}

@Composable
fun MultiLineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    yFormat: (Double) -> String = { chartMoney(it) },
    xFormat: (Long) -> String = { chartClock(it) },
    fixedRange: Pair<Double, Double>? = null,
    includeZero: Boolean = false,
    guideY: Double? = null,
    guideLabel: String? = null,
    contentLabel: String = "Chart"
) {
    val c = DipTheme.colors
    val drawn = series.map { s ->
        s.copy(points = s.points.filter { it.value.isFinite() }.sortedBy { it.tMs })
    }.filter { it.points.isNotEmpty() }
    if (drawn.isEmpty()) {
        ChartEmptyState(modifier, height)
        return
    }
    val times = drawn.flatMap { it.points.map { p -> p.tMs } }
    val t0 = times.min()
    val t1 = times.max().coerceAtLeast(t0 + 1)
    var lo = fixedRange?.first ?: drawn.minOf { it.points.minOf { p -> p.value } }
    var hi = fixedRange?.second ?: drawn.maxOf { it.points.maxOf { p -> p.value } }
    if (includeZero) {
        lo = min(lo, 0.0)
        hi = max(hi, 0.0)
    }
    if (guideY != null && fixedRange == null) {
        lo = min(lo, guideY)
        hi = max(hi, guideY)
    }
    if (hi - lo < 1e-6) {
        lo -= 1.0
        hi += 1.0
    }
    if (fixedRange == null) {
        val pad = (hi - lo) * 0.08
        lo -= pad
        hi += pad
    }
    val yLo = lo
    val yHi = hi
    var scrub by remember(drawn, t0, t1) { mutableIntStateOf(-1) }
    val readout = if (scrub < 0) null else {
        val span = (t1 - t0).coerceAtLeast(1)
        val at = t0 + span * scrub / 100
        drawn.joinToString("   ") { s ->
            val nearest = s.points.minBy { abs(it.tMs - at) }
            "${s.name} ${yFormat(nearest.value)}"
        } + " · " + withEt(xFormat(at))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            readout ?: drawn.joinToString("   ") { s -> "${s.name} ${yFormat(s.points.last().value)}" },
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "$contentLabel. ${drawn.joinToString { it.name }}. Drag to read a point." }
                .pointerInput(t0, t1) {
                    detectTapGestures { off -> scrub = scrubFrac(off.x, size.width.toFloat()) }
                }
                .pointerInput(t0, t1) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = scrubFrac(change.position.x, size.width.toFloat())
                    }
                }
        ) {
            val left = 48.dp.toPx()
            val right = 8.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = 22.dp.toPx()
            val plotW = (size.width - left - right).coerceAtLeast(1f)
            val plotH = (size.height - top - bottom).coerceAtLeast(1f)
            fun xOf(t: Long) = left + plotW * ((t - t0).toFloat() / (t1 - t0).toFloat()).coerceIn(0f, 1f)
            fun yOf(v: Double) = top + plotH * (1f - ((v - yLo) / (yHi - yLo)).toFloat())
            val label = axisPaint(c.textSecondary.toArgb(), 10.dp.toPx())
            val grid = c.border
            for (i in 0..3) {
                val v = yHi - (yHi - yLo) * i / 3.0
                val y = yOf(v)
                drawLine(grid, Offset(left, y), Offset(left + plotW, y), 1.dp.toPx())
                label.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(yFormat(v), left - 4.dp.toPx(), y + 4.dp.toPx(), label)
            }
            guideY?.let { g ->
                val y = yOf(g)
                drawLine(
                    c.accentOrange.copy(alpha = 0.85f),
                    Offset(left, y),
                    Offset(left + plotW, y),
                    1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                )
                guideLabel?.let {
                    val gp = axisPaint(c.accentOrange.toArgb(), 10.dp.toPx())
                    gp.textAlign = Paint.Align.LEFT
                    drawContext.canvas.nativeCanvas.drawText(it, left + 4.dp.toPx(), y - 4.dp.toPx(), gp)
                }
            }
            if (includeZero && 0.0 in yLo..yHi) {
                drawLine(c.textSecondary.copy(alpha = 0.45f), Offset(left, yOf(0.0)), Offset(left + plotW, yOf(0.0)), 1.dp.toPx())
            }
            label.textAlign = Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(withEt(xFormat(t0)), left, size.height - 4.dp.toPx(), label)
            label.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(withEt(xFormat(t1)), left + plotW, size.height - 4.dp.toPx(), label)
            drawn.forEach { s -> drawSeries(s, ::xOf, ::yOf) }
            if (scrub in 0..100) {
                val at = t0 + (t1 - t0) * scrub / 100
                val x = xOf(at)
                drawLine(c.textSecondary, Offset(x, top), Offset(x, top + plotH), 1.5.dp.toPx())
                drawn.forEach { s ->
                    val p = s.points.minBy { abs(it.tMs - at) }
                    drawCircle(s.color, 4.dp.toPx(), Offset(xOf(p.tMs), yOf(p.value)))
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            drawn.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(2.dp)))
                    Text(s.name, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1)
                }
            }
            guideLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.accentOrange, maxLines = 1) }
        }
    }
}

private fun DrawScope.drawSeries(s: LineSeries, xOf: (Long) -> Float, yOf: (Double) -> Float) {
    val path = Path()
    var prev: Long? = null
    var started = false
    s.points.forEach { p ->
        val x = xOf(p.tMs)
        val y = yOf(p.value)
        val gap = s.maxGapMs != null && prev != null && p.tMs - prev!! > s.maxGapMs
        if (!started || gap) path.moveTo(x, y) else path.lineTo(x, y)
        started = true
        prev = p.tMs
    }
    if (started) {
        drawPath(
            path,
            s.color,
            style = Stroke(
                width = s.strokeDp.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
            )
        )
    }
    val last = s.points.last()
    val lx = xOf(last.tMs)
    val ly = yOf(last.value)
    drawCircle(s.color, 4.dp.toPx(), Offset(lx, ly))
    val paint = axisPaint(s.color.toArgb(), 11.dp.toPx())
    paint.textAlign = if (lx > size.width * 0.72f) Paint.Align.RIGHT else Paint.Align.LEFT
    val tx = if (paint.textAlign == Paint.Align.RIGHT) lx - 6.dp.toPx() else lx + 6.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText(s.name, tx, (ly - 6.dp.toPx()).coerceAtLeast(12.dp.toPx()), paint)
}

@Composable
fun HistogramChart(
    bins: List<ChartShapes.Bin>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    barColor: Color = DipTheme.colors.upButton,
    contentLabel: String = "Histogram"
) {
    val c = DipTheme.colors
    if (bins.isEmpty()) {
        ChartEmptyState(modifier, height)
        return
    }
    val max = bins.maxOf { it.count }.coerceAtLeast(1)
    var scrub by remember(bins) { mutableIntStateOf(-1) }
    val pick = bins.getOrNull(scrub)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pick?.let { "${it.label} · ${it.count}" } ?: "Drag a bar · peak ${bins.maxBy { it.count }.label} · ${bins.sumOf { it.count }} total",
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary
        )
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "$contentLabel, ${bins.size} bins, ${bins.sumOf { it.count }} total. Drag to read a bin." }
                .pointerInput(bins) {
                    detectTapGestures { scrub = barIndex(it.x, size.width.toFloat(), bins.size) }
                }
                .pointerInput(bins) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = barIndex(change.position.x, size.width.toFloat(), bins.size)
                    }
                }
        ) {
            val left = 36.dp.toPx()
            val bottom = 22.dp.toPx()
            val top = 8.dp.toPx()
            val plotW = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            val plotH = (size.height - top - bottom).coerceAtLeast(1f)
            val slot = plotW / bins.size
            val paint = axisPaint(c.textSecondary.toArgb(), 10.dp.toPx())
            for (i in 0..3) {
                val y = top + plotH * i / 3f
                drawLine(c.border, Offset(left, y), Offset(left + plotW, y), 1.dp.toPx())
                paint.textAlign = Paint.Align.RIGHT
                val n = (max * (3 - i) / 3.0).roundToInt()
                drawContext.canvas.nativeCanvas.drawText(n.toString(), left - 4.dp.toPx(), y + 4.dp.toPx(), paint)
            }
            bins.forEachIndexed { i, bin ->
                val h = plotH * bin.count / max.toFloat()
                val x = left + i * slot + slot * 0.15f
                val w = slot * 0.7f
                drawRoundRect(
                    color = if (i == scrub) barColor else barColor.copy(alpha = 0.85f),
                    topLeft = Offset(x, top + plotH - h),
                    size = Size(w, h.coerceAtLeast(if (bin.count > 0) 2.dp.toPx() else 0f)),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
            }
            val step = if (bins.size <= 8) 1 else if (bins.size <= 12) 2 else 3
            paint.textAlign = Paint.Align.CENTER
            bins.forEachIndexed { i, bin ->
                if (i % step == 0) {
                    drawContext.canvas.nativeCanvas.drawText(bin.label, left + (i + 0.5f) * slot, size.height - 4.dp.toPx(), paint)
                }
            }
            if (scrub in bins.indices) {
                val x = left + (scrub + 0.5f) * slot
                drawLine(c.textSecondary, Offset(x, top), Offset(x, top + plotH), 1.dp.toPx())
            }
        }
    }
}

@Composable
fun HourHeatmap(
    cells: List<ChartShapes.HourCell>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    countWord: String = "windows"
) {
    val c = DipTheme.colors
    if (cells.isEmpty()) {
        ChartEmptyState(modifier, height)
        return
    }
    val ordered = (0 until 24).map { h -> cells.firstOrNull { it.hour == h } ?: ChartShapes.HourCell(h, 0.0, 0) }
    val maxAbs = ordered.maxOf { abs(it.net) }.coerceAtLeast(0.01)
    var scrub by remember(cells) { mutableIntStateOf(-1) }
    val pick = ordered.getOrNull(scrub)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pick?.let { "${com.dirk.kalshiodds.domain.EtTime.hour(it.hour)} ET · ${chartMoney(it.net)} · ${it.trades} $countWord" }
                ?: "Drag an hour · green gain, red loss",
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary
        )
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "P&L by hour of day, 24 hours Eastern. Drag to read an hour." }
                .pointerInput(ordered) {
                    detectTapGestures { scrub = barIndex(it.x, size.width.toFloat(), 24) }
                }
                .pointerInput(ordered) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = barIndex(change.position.x, size.width.toFloat(), 24)
                    }
                }
        ) {
            val left = 8.dp.toPx()
            val bottom = 22.dp.toPx()
            val top = 8.dp.toPx()
            val plotW = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            val plotH = (size.height - top - bottom).coerceAtLeast(1f)
            val slot = plotW / 24f
            ordered.forEachIndexed { i, cell ->
                val color = when {
                    abs(cell.net) < 0.005 || cell.trades == 0 -> c.surfaceAlt
                    cell.net > 0 -> c.upButton.copy(alpha = (0.35f + 0.65f * (cell.net / maxAbs).toFloat()).coerceIn(0.35f, 1f))
                    else -> c.downButton.copy(alpha = (0.35f + 0.65f * (abs(cell.net) / maxAbs).toFloat()).coerceIn(0.35f, 1f))
                }
                drawRoundRect(
                    color,
                    Offset(left + i * slot + 1.dp.toPx(), top),
                    Size((slot - 2.dp.toPx()).coerceAtLeast(1f), plotH),
                    CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
            }
            if (scrub in 0..23) {
                drawRoundRect(
                    c.textPrimary,
                    Offset(left + scrub * slot, top),
                    Size(slot, plotH),
                    CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = Stroke(1.5.dp.toPx())
                )
            }
            val paint = axisPaint(c.textSecondary.toArgb(), 10.dp.toPx())
            paint.textAlign = Paint.Align.CENTER
            listOf(0, 6, 12, 18).forEach { h ->
                drawContext.canvas.nativeCanvas.drawText(
                    ChartShapes.hourLabel(h),
                    left + (h + 0.5f) * slot,
                    size.height - 4.dp.toPx(),
                    paint
                )
            }
        }
    }
}

@Composable
fun DivergingBarChart(
    bars: List<NetBar>,
    modifier: Modifier = Modifier,
    contentLabel: String = "P&L by strategy"
) {
    val c = DipTheme.colors
    val shown = bars.filter { it.value.isFinite() }
    if (shown.isEmpty()) {
        ChartEmptyState(modifier, 220.dp)
        return
    }
    val maxAbs = shown.maxOf { abs(it.value) }.coerceAtLeast(0.01)
    val height = max(220, shown.size * 36).dp
    var scrub by remember(shown) { mutableIntStateOf(-1) }
    val pick = shown.getOrNull(scrub)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pick?.let { "${it.label} · ${chartMoney(it.value)}" } ?: "Drag a bar",
            style = MaterialTheme.typography.labelMedium,
            color = if (pick == null) c.textPrimary else chartSignTextColor(pick.value)
        )
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "$contentLabel, ${shown.size} bars. Drag to read one." }
                .pointerInput(shown) {
                    detectTapGestures { scrub = ((it.y / size.height.toFloat()) * shown.size).toInt().coerceIn(0, shown.lastIndex) }
                }
                .pointerInput(shown) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = ((change.position.y / size.height.toFloat()) * shown.size).toInt().coerceIn(0, shown.lastIndex)
                    }
                }
        ) {
            val left = 8.dp.toPx()
            val labelW = 118.dp.toPx()
            val valueW = 72.dp.toPx()
            val plotLeft = left + labelW
            val plotRight = size.width - valueW
            val mid = (plotLeft + plotRight) / 2f
            val rowH = size.height / shown.size
            val paint = axisPaint(c.textSecondary.toArgb(), 11.dp.toPx())
            drawLine(c.border, Offset(mid, 0f), Offset(mid, size.height), 1.dp.toPx())
            shown.forEachIndexed { i, bar ->
                val cy = rowH * (i + 0.5f)
                paint.textAlign = Paint.Align.LEFT
                paint.color = if (i == scrub) c.textPrimary.toArgb() else c.textSecondary.toArgb()
                drawContext.canvas.nativeCanvas.drawText(bar.label.take(18), left, cy + 4.dp.toPx(), paint)
                val reach = ((plotRight - plotLeft) / 2f - 4.dp.toPx()) * (abs(bar.value) / maxAbs).toFloat()
                val color = if (bar.value >= 0) c.upButton else c.downButton
                val x = if (bar.value >= 0) mid else mid - reach
                drawRoundRect(color, Offset(x, cy - 8.dp.toPx()), Size(reach.coerceAtLeast(1f), 16.dp.toPx()), CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                paint.textAlign = Paint.Align.RIGHT
                paint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(chartMoney(bar.value), size.width - 2.dp.toPx(), cy + 4.dp.toPx(), paint)
            }
        }
    }
}

@Composable
fun DepthChart(rungs: List<ChartShapes.DepthRung>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    if (rungs.isEmpty()) {
        ChartEmptyState(modifier, 220.dp)
        return
    }
    val height = max(220, rungs.size * 28).dp
    var scrub by remember(rungs) { mutableIntStateOf(-1) }
    val pick = rungs.getOrNull(scrub)
    val span = if (rungs.isEmpty()) "" else "${rungs.first().label()} to ${rungs.last().label()}"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pick?.let { ChartShapes.depthReadout(it) } ?: "UP green grows left · DOWN red grows right · drag a price",
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("UP", color = c.up, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("DOWN", color = c.down, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "YES price. A DOWN bid is drawn at 100¢ minus its price.",
            style = MaterialTheme.typography.labelSmall,
            color = c.textSecondary
        )
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "Order book depth, UP and DOWN together, ${rungs.size} prices from $span." }
                .pointerInput(rungs) {
                    detectTapGestures { scrub = ChartShapes.depthRowAt(it.y, size.height.toFloat(), rungs.size) }
                }
                .pointerInput(rungs) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = ChartShapes.depthRowAt(change.position.y, size.height.toFloat(), rungs.size)
                    }
                }
        ) {
            val mid = size.width / 2f
            val rowH = size.height / rungs.size
            val half = mid - 36.dp.toPx()
            val paint = axisPaint(c.textPrimary.toArgb(), 11.dp.toPx())
            paint.textAlign = Paint.Align.CENTER
            rungs.forEachIndexed { i, rung ->
                val cy = rowH * (i + 0.5f)
                if (i == scrub) drawRect(c.surfaceAlt, Offset(0f, rowH * i), Size(size.width, rowH))
                val uw = (half * ChartShapes.depthBarFraction(rung.upSize, rungs)).toFloat()
                val dw = (half * ChartShapes.depthBarFraction(rung.downSize, rungs)).toFloat()
                if (uw > 0f) drawRoundRect(c.upButton, Offset(mid - uw, cy - 7.dp.toPx()), Size(uw, 14.dp.toPx()), CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                if (dw > 0f) drawRoundRect(c.downButton, Offset(mid, cy - 7.dp.toPx()), Size(dw, 14.dp.toPx()), CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(rung.label(), mid, cy + 4.dp.toPx(), paint)
            }
        }
    }
}

@Composable
fun WindowTimeline(
    startMs: Long,
    closeMs: Long,
    events: List<ChartShapes.WindowEvent>,
    modifier: Modifier = Modifier
) {
    val c = DipTheme.colors
    if (closeMs <= startMs || events.isEmpty()) {
        ChartEmptyState(modifier, 200.dp)
        return
    }
    var scrub by remember(events) { mutableIntStateOf(-1) }
    val pick = events.getOrNull(scrub)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pick?.let { "${if (it.up) "UP" else "DOWN"} ${it.label} · ${ChartShapes.cents(it.price)} · ${chartClock(it.atMs)} ET" }
                ?: "15-minute window · UP lane on top · DOWN lane below · drag",
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary
        )
        Canvas(
            Modifier.fillMaxWidth().height(200.dp)
                .semantics { contentDescription = "Window timeline, ${events.size} entries and exits." }
                .pointerInput(events, startMs, closeMs) {
                    detectTapGestures { scrub = nearestEvent(it.x, size.width.toFloat(), startMs, closeMs, events) }
                }
                .pointerInput(events, startMs, closeMs) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = nearestEvent(change.position.x, size.width.toFloat(), startMs, closeMs, events)
                    }
                }
        ) {
            val left = 44.dp.toPx()
            val right = 8.dp.toPx()
            val top = 16.dp.toPx()
            val bottom = 24.dp.toPx()
            val plotW = (size.width - left - right).coerceAtLeast(1f)
            val upY = top + 28.dp.toPx()
            val downY = size.height - bottom - 28.dp.toPx()
            val span = (closeMs - startMs).coerceAtLeast(1)
            fun xOf(t: Long) = left + plotW * ((t - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            drawLine(c.upButton.copy(alpha = 0.35f), Offset(left, upY), Offset(left + plotW, upY), 2.dp.toPx())
            drawLine(c.downButton.copy(alpha = 0.35f), Offset(left, downY), Offset(left + plotW, downY), 2.dp.toPx())
            val paint = axisPaint(c.textSecondary.toArgb(), 11.dp.toPx())
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("UP", left - 4.dp.toPx(), upY + 4.dp.toPx(), paint)
            drawContext.canvas.nativeCanvas.drawText("DOWN", left - 4.dp.toPx(), downY + 4.dp.toPx(), paint)
            paint.textAlign = Paint.Align.CENTER
            for (minute in listOf(0, 5, 10, 15)) {
                val x = left + plotW * minute / 15f
                drawLine(c.border, Offset(x, top), Offset(x, size.height - bottom), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("${minute}m", x, size.height - 4.dp.toPx(), paint)
            }
            events.forEachIndexed { i, e ->
                val center = Offset(xOf(e.atMs), if (e.up) upY else downY)
                val color = if (e.up) c.upButton else c.downButton
                val r = if (i == scrub) 8.dp.toPx() else 5.dp.toPx()
                when (e.kind) {
                    "entry" -> drawCircle(color, r, center)
                    "exit", "settle" -> drawRect(color, center - Offset(r, r), Size(r * 2, r * 2))
                    else -> drawCircle(color, 3.dp.toPx(), center)
                }
            }
        }
        Text("● entry   ■ exit   · signal", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}

@Composable
fun FlowDiagram(nodes: List<ChartShapes.FlowNode>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    if (nodes.isEmpty()) {
        ChartEmptyState(modifier, 180.dp)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 160.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (i in nodes.indices) {
                val node = nodes[i]
                if (i > 0) Text("→", color = c.textSecondary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 140.dp)
                        .background(c.surfaceAlt, RoundedCornerShape(12.dp))
                        .border(1.dp, if (node.title == "Exit") c.downButton else c.upButton, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .semantics { contentDescription = "${node.title}: ${node.detail}" },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(node.title, color = c.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(node.detail, color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun TweakTimeline(marks: List<ChartShapes.TweakMark>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    if (marks.isEmpty()) {
        ChartEmptyState(modifier, 160.dp)
        return
    }
    Column(modifier.fillMaxWidth().heightIn(min = 160.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        marks.forEach { mark ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp).size(12.dp).background(c.upButton, RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f)) {
                    Text(mark.label, color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    val moved = mark.fromCents != null && mark.toCents != null
                    if (moved) {
                        Text("${mark.fromCents}¢ → ${mark.toCents}¢", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(mark.note, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun WinRateGaugeRow(gauges: List<Triple<String, Double?, Int>>, modifier: Modifier = Modifier) {
    val shown = gauges.filter { it.third > 0 && it.second != null && it.second!!.isFinite() }
    if (shown.isEmpty()) {
        ChartEmptyState(modifier, 140.dp)
        return
    }
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { (label, rate, n) ->
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = DipTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                WinRateGauge(rate, n, size = 140.dp)
            }
        }
    }
}

@Composable
fun SideDonutRow(counts: List<ChartShapes.SideCount>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    if (counts.isEmpty()) {
        ChartEmptyState(modifier, 160.dp)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        counts.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.label, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ShareDonut(
                    listOf(
                        DonutSlice("UP", row.up.toDouble(), c.upButton),
                        DonutSlice("DOWN", row.down.toDouble(), c.downButton)
                    ),
                    Modifier.fillMaxWidth(),
                    size = 120.dp,
                    centerLabel = "${row.up + row.down}"
                )
            }
        }
    }
}

private fun scrubFrac(x: Float, width: Float): Int {
    if (width <= 0f) return 0
    return ((x / width).coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
}

private fun barIndex(x: Float, width: Float, n: Int): Int {
    if (n <= 0 || width <= 0f) return 0
    return ((x / width).coerceIn(0f, 0.999f) * n).toInt().coerceIn(0, n - 1)
}

private fun nearestEvent(x: Float, width: Float, startMs: Long, closeMs: Long, events: List<ChartShapes.WindowEvent>): Int {
    if (events.isEmpty() || width <= 0f) return -1
    val span = (closeMs - startMs).coerceAtLeast(1)
    val at = startMs + ((x / width).coerceIn(0f, 1f) * span).toLong()
    return events.indices.minBy { abs(events[it].atMs - at) }
}

private fun axisPaint(color: Int, textSize: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.textSize = textSize
}
