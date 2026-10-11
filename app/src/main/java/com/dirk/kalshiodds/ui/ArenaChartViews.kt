package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalper.ArenaCharts
import com.dirk.kalshiodds.signal.scalper.CurvePoint
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs
import kotlin.math.max

/**
 * Charts for the Arena tab (paper scalper only). Every chart reads numbers
 * worked out in [ArenaCharts] and draws them; every value is also in a
 * list or label on the screen, so a tap readout never hides anything.
 *
 * Colours: the validated reference palette (categorical slots 1–4, the
 * blue ↔ red diverging pair for gain / loss), stepped separately for the
 * dark and light surfaces. Text always wears the theme's text colours; a
 * coloured mark beside it carries identity.
 */
internal object ArenaColors {
    class Set(
        val series: List<Color>,
        val gain: Color,
        val loss: Color,
        val muted: Color,
        val grid: Color,
        val baseline: Color,
        val shadeLight: Color,
        val shadeDark: Color,
        val surface: Color
    )

    @Composable
    fun current(): Set {
        val c = DipTheme.colors
        val dark = c.surface.luminance() < 0.5f
        return if (dark) {
            Set(
                series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500)),
                gain = Color(0xFF3987E5),
                loss = Color(0xFFE66767),
                muted = Color(0xFF898781),
                grid = Color(0xFF2C2C2A),
                baseline = Color(0xFF5A5A55),
                shadeLight = Color(0xFF86B6EF),
                shadeDark = Color(0xFF3987E5),
                surface = c.surface
            )
        } else {
            Set(
                series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100)),
                gain = Color(0xFF2A78D6),
                loss = Color(0xFFE34948),
                muted = Color(0xFF898781),
                grid = Color(0xFFE1E0D9),
                baseline = Color(0xFFC3C2B7),
                shadeLight = Color(0xFF86B6EF),
                shadeDark = Color(0xFF1C5CAB),
                surface = c.surface
            )
        }
    }
}

/** A titled card. [subtitle] says what the chart answers. */
@Composable
internal fun ChartCard(title: String, subtitle: String, tag: String, content: @Composable () -> Unit) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = DipTheme.colors.textSecondary, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The value leads, the label follows (tap / drag readout). */
@Composable
private fun Readout(value: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = DipTheme.colors.textPrimary)
        Muted(label)
    }
}

/** A short stroke of the series colour: the key for lines (legends and readouts). */
@Composable
private fun LineKey(color: Color) {
    Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** A small filled square: the key for bars and segments. */
@Composable
private fun RectKey(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx()))
    }
}

/** A bar with a 4 dp rounded data end and a square end on the baseline. */
private fun DrawScope.bar(left: Float, top: Float, right: Float, bottom: Float, color: Color, roundEnd: BarEnd) {
    val w = right - left
    val h = bottom - top
    if (w <= 0.5f || h <= 0.5f) return
    val r = CornerRadius(minOf(4.dp.toPx(), w / 2f, h / 2f))
    val z = CornerRadius.Zero
    val rr = when (roundEnd) {
        BarEnd.RIGHT -> RoundRect(left, top, right, bottom, z, r, r, z)
        BarEnd.LEFT -> RoundRect(left, top, right, bottom, r, z, z, r)
        BarEnd.TOP -> RoundRect(left, top, right, bottom, r, r, z, z)
        BarEnd.BOTTOM -> RoundRect(left, top, right, bottom, z, z, r, r)
        BarEnd.NONE -> RoundRect(left, top, right, bottom, z, z, z, z)
    }
    drawPath(Path().apply { addRoundRect(rr) }, color)
}

private enum class BarEnd { LEFT, RIGHT, TOP, BOTTOM, NONE }

// ---------------------------------------------------------------- P&L bars

/**
 * Profit after fees per strategy, diverging around $0 (blue gain, red loss),
 * best on top. Tap a row to select that strategy for the other charts.
 */
@Composable
internal fun PnlBarsChart(
    board: List<ScalperResults.Standing>,
    selected: ScalpStrategy?,
    onSelect: (ScalpStrategy) -> Unit
) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    val maxAbs = board.maxOfOrNull { abs(it.stats.pnlUsd) }?.takeIf { it > 1e-9 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        board.forEach { r ->
            val sel = r.strategy == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (sel) colors.surfaceAlt else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(r.strategy) }
                    .padding(vertical = 4.dp, horizontal = 4.dp)
                    .testTag("PNL_BAR_${r.strategy.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    r.strategy.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (r.stats.closed == 0) colors.textSecondary else colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Canvas(Modifier.width(110.dp).height(14.dp)) {
                    val mid = size.width / 2f
                    drawLine(ac.baseline, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.dp.toPx())
                    if (r.stats.closed > 0) {
                        val len = (abs(r.stats.pnlUsd) / maxAbs).toFloat() * (mid - 1.dp.toPx())
                        if (r.stats.pnlUsd >= 0) {
                            bar(mid + 1.dp.toPx(), 0f, mid + 1.dp.toPx() + len, size.height, ac.gain, BarEnd.RIGHT)
                        } else {
                            bar(mid - 1.dp.toPx() - len, 0f, mid - 1.dp.toPx(), size.height, ac.loss, BarEnd.LEFT)
                        }
                    }
                }
                Text(
                    if (r.stats.closed == 0) "–" else ScalperResults.money(r.stats.pnlUsd),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.width(66.dp),
                    maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------- equity race

/**
 * Running P&L of every strategy over time, from the same $0 line. The top
 * three (and the selected one) in colour, the rest grey. Drag across to
 * read every coloured line at that moment.
 */
@Composable
internal fun EquityRaceChart(curves: List<ArenaCharts.Curve>, selected: ScalpStrategy?) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (curves.isEmpty() || curves.all { it.points.size < 2 }) {
        Muted("The race starts when the first trade closes.")
        return
    }
    val hl = ArenaCharts.highlighted(curves, selected)
    val colorOf = hl.mapIndexed { i, c -> c.strategy to ac.series[i % ac.series.size] }.toMap()
    val all = curves.flatMap { it.points }
    val t0 = all.minOf { it.tMs }
    val t1 = max(all.maxOf { it.tMs }, t0 + 1)
    val (lo, hi) = ArenaCharts.niceRange(all.minOf { it.pnlUsd }, all.maxOf { it.pnlUsd })
    val step = ArenaCharts.niceStep((hi - lo) / 4.0)
    var scrubT by remember(curves) { mutableStateOf<Long?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val at = scrubT
        if (at == null) {
            Muted("Drag across the chart to read the lines at one moment.")
        } else {
            Muted(ScalperResults.timeLabel(at))
            hl.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineKey(colorOf.getValue(c.strategy))
                    Readout(ScalperResults.money(ArenaCharts.valueAt(c, at)), c.strategy.label)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .testTag("EQUITY_RACE")
                    .pointerInput(curves) {
                        detectTapGestures { o -> scrubT = t0 + ((o.x / size.width).coerceIn(0f, 1f) * (t1 - t0)).toLong() }
                    }
                    .pointerInput(curves) {
                        detectHorizontalDragGestures { change, _ ->
                            scrubT = t0 + ((change.position.x / size.width).coerceIn(0f, 1f) * (t1 - t0)).toLong()
                        }
                    }
            ) {
                fun x(t: Long) = size.width * ((t - t0).toFloat() / (t1 - t0).toFloat())
                fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
                var g = lo
                while (g <= hi + 1e-9) {
                    drawLine(if (abs(g) < 1e-9) ac.baseline else ac.grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1.dp.toPx())
                    g += step
                }
                val grey = curves.filter { it.strategy !in colorOf }
                for (c in grey) drawCurve(c.points, ::x, ::y, ac.muted.copy(alpha = 0.55f), 1.dp.toPx())
                for (c in hl) drawCurve(c.points, ::x, ::y, colorOf.getValue(c.strategy), 2.dp.toPx())
                for (c in hl) {
                    val p = c.points.last()
                    drawCircle(ac.surface, radius = 6.dp.toPx(), center = Offset(x(p.tMs), y(p.pnlUsd)))
                    drawCircle(colorOf.getValue(c.strategy), radius = 4.dp.toPx(), center = Offset(x(p.tMs), y(p.pnlUsd)))
                }
                scrubT?.let { s -> drawLine(colors.textSecondary, Offset(x(s), 0f), Offset(x(s), size.height), strokeWidth = 1.dp.toPx()) }
            }
            Muted(ScalperResults.money(hi), Modifier.align(Alignment.TopStart))
            Muted(ScalperResults.money(lo), Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(ScalperResults.timeLabel(t0))
            Muted(ScalperResults.timeLabel(t1))
        }
        // Legend: always present for the coloured lines; grey = every other strategy.
        hl.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LineKey(colorOf.getValue(c.strategy))
                Text(c.strategy.label, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(ScalperResults.money(c.last), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            }
        }
        if (curves.size > hl.size) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LineKey(ac.muted)
                Muted("the other ${curves.size - hl.size} strategies")
            }
        }
    }
}

private fun DrawScope.drawCurve(
    pts: List<CurvePoint>,
    x: (Long) -> Float,
    y: (Double) -> Float,
    color: Color,
    width: Float
) {
    if (pts.size < 2) return
    val path = Path()
    pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.tMs), y(p.pnlUsd)) else path.lineTo(x(p.tMs), y(p.pnlUsd)) }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------------------------------------------------------- one line (bankroll)

/** One series over time with a 10 % wash under it and a drag readout. [baseline] is drawn as the zero line. */
@Composable
internal fun ScrubLineChart(points: List<CurvePoint>, baseline: Double, valueLabel: (Double) -> String) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (points.size < 2) return
    val t0 = points.first().tMs
    val t1 = max(points.last().tMs, t0 + 1)
    val lo0 = minOf(points.minOf { it.pnlUsd }, baseline)
    val hi0 = maxOf(points.maxOf { it.pnlUsd }, baseline)
    val pad = ((hi0 - lo0) * 0.08).coerceAtLeast(0.5)
    val lo = lo0 - pad
    val hi = hi0 + pad
    val line = if (points.last().pnlUsd >= baseline) ac.gain else ac.loss
    var scrub by remember(points) { mutableStateOf<Int?>(null) }
    fun nearest(xFrac: Float): Int {
        val t = t0 + (xFrac.coerceIn(0f, 1f) * (t1 - t0)).toLong()
        var best = 0
        for (i in points.indices) if (abs(points[i].tMs - t) < abs(points[best].tMs - t)) best = i
        return best
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val i = scrub
        if (i == null) {
            Readout(valueLabel(points.last().pnlUsd), "now · drag to read any moment")
        } else {
            Readout(valueLabel(points[i].pnlUsd), ScalperResults.timeLabel(points[i].tMs))
        }
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .testTag("BANKROLL_CHART")
                    .pointerInput(points) { detectTapGestures { o -> scrub = nearest(o.x / size.width) } }
                    .pointerInput(points) {
                        detectHorizontalDragGestures { change, _ -> scrub = nearest(change.position.x / size.width) }
                    }
            ) {
                fun x(t: Long) = size.width * ((t - t0).toFloat() / (t1 - t0).toFloat())
                fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
                drawLine(ac.baseline, Offset(0f, y(baseline)), Offset(size.width, y(baseline)), strokeWidth = 1.dp.toPx())
                val path = Path()
                points.forEachIndexed { k, p -> if (k == 0) path.moveTo(x(p.tMs), y(p.pnlUsd)) else path.lineTo(x(p.tMs), y(p.pnlUsd)) }
                val wash = Path().apply {
                    moveTo(x(points.first().tMs), y(baseline))
                    points.forEach { p -> lineTo(x(p.tMs), y(p.pnlUsd)) }
                    lineTo(x(points.last().tMs), y(baseline))
                    close()
                }
                drawPath(wash, line.copy(alpha = 0.10f))
                drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val end = points.last()
                drawCircle(ac.surface, radius = 6.dp.toPx(), center = Offset(x(end.tMs), y(end.pnlUsd)))
                drawCircle(line, radius = 4.dp.toPx(), center = Offset(x(end.tMs), y(end.pnlUsd)))
                scrub?.let { k ->
                    val p = points[k]
                    drawLine(colors.textSecondary, Offset(x(p.tMs), 0f), Offset(x(p.tMs), size.height), strokeWidth = 1.dp.toPx())
                    drawCircle(ac.surface, radius = 6.dp.toPx(), center = Offset(x(p.tMs), y(p.pnlUsd)))
                    drawCircle(line, radius = 4.dp.toPx(), center = Offset(x(p.tMs), y(p.pnlUsd)))
                }
            }
            Muted(valueLabel(hi0), Modifier.align(Alignment.TopStart))
            Muted(valueLabel(lo0), Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(ScalperResults.timeLabel(t0))
            Muted(ScalperResults.timeLabel(t1))
        }
    }
}

// ---------------------------------------------------------------- columns (hourly, histogram)

/** Columns diverging around 0: blue up, red down; null = an empty slot. Tap a column to read it. */
@Composable
private fun DivergingColumns(
    values: List<Double?>,
    tag: String,
    height: Int,
    negativeAt: (Int) -> Boolean,
    readout: (Int) -> Pair<String, String>?,
    allPositive: Boolean = false
) {
    val ac = ArenaColors.current()
    var tapped by remember(values) { mutableStateOf<Int?>(null) }
    val maxAbs = values.filterNotNull().maxOfOrNull { abs(it) }?.takeIf { it > 1e-9 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val r = tapped?.let(readout)
        if (r == null) Muted("Tap a column to read it.") else Readout(r.first, r.second)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .testTag(tag)
                .pointerInput(values) {
                    detectTapGestures { o ->
                        val i = (o.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)
                        tapped = if (tapped == i) null else i
                    }
                }
        ) {
            val slot = size.width / values.size
            val w = minOf(24.dp.toPx(), slot - 2.dp.toPx()).coerceAtLeast(1f)
            val zero = if (allPositive) size.height else size.height / 2f
            val room = if (allPositive) size.height else size.height / 2f - 1.dp.toPx()
            drawLine(ac.baseline, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
            values.forEachIndexed { i, v ->
                if (v == null || abs(v) < 1e-12) return@forEachIndexed
                val cx = slot * i + slot / 2f
                val len = (abs(v) / maxAbs).toFloat() * room
                val neg = negativeAt(i)
                val color = if (neg) ac.loss else ac.gain
                val c = if (tapped == i) color.copy(alpha = 0.75f) else color
                if (!allPositive && v < 0) {
                    bar(cx - w / 2, zero + 1.dp.toPx(), cx + w / 2, zero + 1.dp.toPx() + len, c, BarEnd.BOTTOM)
                } else {
                    bar(cx - w / 2, zero - len, cx + w / 2, zero, c, BarEnd.TOP)
                }
            }
        }
    }
}

/** P&L by hour of the day (the phone's clock). */
@Composable
internal fun HourlyChart(values: List<Double?>) {
    if (values.all { it == null }) {
        Muted("Fills in as trades close through the day.")
        return
    }
    DivergingColumns(
        values = values,
        tag = "HOURLY_CHART",
        height = 120,
        negativeAt = { (values[it] ?: 0.0) < 0 },
        readout = { i -> values[i]?.let { ScalperResults.money(it) to ScalperResults.hourLabel(i) } }
    )
    Row(Modifier.fillMaxWidth()) {
        listOf(0, 6, 12, 18).forEach { h -> Muted(ScalperResults.hourLabel(h), Modifier.weight(1f)) }
    }
}

/** How many trades made how much per contract, after fees. Red bins lost money. */
@Composable
internal fun HistogramChart(bins: List<ArenaCharts.Bin>, scope: String) {
    val total = bins.sumOf { it.count }
    if (total == 0) {
        Muted("No closed trades for $scope yet.")
        return
    }
    val values = bins.map { it.count.toDouble() }
    DivergingColumns(
        values = values,
        tag = "HISTOGRAM",
        height = 130,
        negativeAt = { bins[it].negative },
        readout = { i -> "${bins[i].count} trade${if (bins[i].count == 1) "" else "s"}" to "${bins[i].label} per contract" },
        allPositive = true
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted("lost more than 20¢")
        Muted("0")
        Muted("made 20¢+")
    }
    val lost = bins.filter { it.negative }.sumOf { it.count }
    Muted("$total trades · ${pctOf(lost, total)} lost money after fees")
}

private fun pctOf(n: Int, total: Int): String = if (total == 0) "–" else "${(100.0 * n / total).toInt()}%"

// ---------------------------------------------------------------- exit mix

/** How each strategy's trades ended: target, stop, time-out, settled (part-to-whole per row). */
@Composable
internal fun ExitMixChart(rows: List<ArenaCharts.ExitMix>) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (rows.isEmpty()) {
        Muted("No closed trades yet.")
        return
    }
    val names = listOf("target hit", "stopped", "timed out", "held to close")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RectKey(ac.series[i])
                Muted(n)
            }
        }
    }
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth().testTag("EXIT_MIX_${r.strategy.name}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.strategy.label, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier.width(130.dp).height(14.dp)) {
                val parts = listOf(r.target, r.stop, r.timeout, r.settled)
                val total = r.total.coerceAtLeast(1)
                val gap = 2.dp.toPx()
                val shown = parts.count { it > 0 }
                val usable = size.width - gap * (shown - 1).coerceAtLeast(0)
                var x = 0f
                var k = 0
                parts.forEachIndexed { i, n ->
                    if (n <= 0) return@forEachIndexed
                    val w = usable * n / total
                    // Square on the left baseline, a rounded end on the last segment only.
                    val end = if (k == shown - 1) BarEnd.RIGHT else BarEnd.NONE
                    bar(x, 0f, x + w, size.height, ac.series[i], end)
                    x += w + gap
                    k++
                }
            }
            Muted("${r.total}", Modifier.width(34.dp))
        }
    }
}

// ---------------------------------------------------------------- win rate

/** Share of trades that made money after fees, with the 50 % line. */
@Composable
internal fun WinRateChart(rows: List<ArenaCharts.WinRate>) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (rows.isEmpty()) {
        Muted("No closed trades yet.")
        return
    }
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.strategy.label, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier.width(110.dp).height(14.dp)) {
                drawRoundRect(ac.grid, cornerRadius = CornerRadius(4.dp.toPx()))
                bar(0f, 0f, size.width * r.rate.toFloat().coerceIn(0f, 1f), size.height, ac.gain, BarEnd.RIGHT)
                val half = size.width / 2f
                drawLine(colors.textSecondary, Offset(half, -2f), Offset(half, size.height + 2f), strokeWidth = 1.dp.toPx())
            }
            Muted("${(r.rate * 100).toInt()}% of ${r.closed}", Modifier.width(70.dp))
        }
    }
    Muted("The line is 50%. Many small wins can still lose money: compare with the profit bars.")
}

// ---------------------------------------------------------------- fees

/** Before fees → after fees per strategy (dumbbell). The gap is what the fees took. */
@Composable
internal fun FeeDragChart(rows: List<ArenaCharts.FeeDrag>) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (rows.isEmpty()) {
        Muted("No closed trades yet.")
        return
    }
    val (lo, hi) = ArenaCharts.niceRange(rows.minOf { minOf(it.grossUsd, it.netUsd) }, rows.maxOf { maxOf(it.grossUsd, it.netUsd) })
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            DotKey(ac.shadeLight)
            Muted("before fees")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            DotKey(ac.shadeDark)
            Muted("after fees")
        }
    }
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.strategy.label, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier.width(120.dp).height(16.dp)) {
                fun x(v: Double) = size.width * ((v - lo) / (hi - lo)).toFloat()
                val cy = size.height / 2
                drawLine(ac.baseline, Offset(x(0.0), 0f), Offset(x(0.0), size.height), strokeWidth = 1.dp.toPx())
                drawLine(ac.muted, Offset(x(r.grossUsd), cy), Offset(x(r.netUsd), cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(ac.surface, radius = 6.dp.toPx(), center = Offset(x(r.grossUsd), cy))
                drawCircle(ac.shadeLight, radius = 4.dp.toPx(), center = Offset(x(r.grossUsd), cy))
                drawCircle(ac.surface, radius = 6.dp.toPx(), center = Offset(x(r.netUsd), cy))
                drawCircle(ac.shadeDark, radius = 4.dp.toPx(), center = Offset(x(r.netUsd), cy))
            }
            Muted("fees ${ScalperResults.plainMoney(r.feesUsd)}", Modifier.width(78.dp))
        }
    }
    Muted("Left of the line lost money. A strategy whose dot jumps the line made money before fees and lost it to them.")
}

@Composable
private fun DotKey(color: Color) {
    Canvas(Modifier.size(10.dp)) { drawCircle(color, radius = size.minDimension / 2) }
}

// ---------------------------------------------------------------- sparkline

/** A strategy's running P&L in one small grey line; the end dot is blue above $0, red below. */
@Composable
internal fun Sparkline(curve: ArenaCharts.Curve?, modifier: Modifier = Modifier) {
    val ac = ArenaColors.current()
    Canvas(modifier.size(width = 64.dp, height = 22.dp)) {
        val pts = curve?.points ?: return@Canvas
        if (pts.size < 2) return@Canvas
        val t0 = pts.first().tMs
        val t1 = max(pts.last().tMs, t0 + 1)
        val lo = minOf(pts.minOf { it.pnlUsd }, 0.0)
        val hi = maxOf(pts.maxOf { it.pnlUsd }, 0.0)
        val span = (hi - lo).coerceAtLeast(1e-6)
        fun x(t: Long) = 3.dp.toPx() + (size.width - 6.dp.toPx()) * ((t - t0).toFloat() / (t1 - t0).toFloat())
        fun y(v: Double) = 3.dp.toPx() + (size.height - 6.dp.toPx()) * (1f - ((v - lo) / span).toFloat())
        drawLine(ac.grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
        drawCurve(pts, ::x, ::y, ac.muted, 1.5.dp.toPx())
        val end = pts.last()
        drawCircle(if (end.pnlUsd >= 0) ac.gain else ac.loss, radius = 3.dp.toPx(), center = Offset(x(end.tMs), y(end.pnlUsd)))
    }
}
