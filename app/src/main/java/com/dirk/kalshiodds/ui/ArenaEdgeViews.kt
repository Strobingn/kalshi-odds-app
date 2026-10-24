package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalper.ArenaEdge
import com.dirk.kalshiodds.signal.scalper.CurvePoint
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.ui.theme.ArenaColors
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The Arena's "Edge" charts: is the price right (calibration), what the
 * record so far implies for the next trades (a resampled fan), and is the
 * edge holding up (rolling result per contract). Numbers come from
 * [ArenaEdge]; every chart has a tap or drag readout.
 */

private fun pct(v: Double): String = "${(v * 100).toInt()}%"

private fun cents(v: Double): String = "${(v * 100).toInt()}¢"

// ---------------------------------------------------------------- calibration

/**
 * Trades held to the settlement, by entry price: the dot is how often they
 * won, the whisker ±2 standard errors, the dashed diagonal "won exactly as
 * often as the price said" and the short dash the break-even after the
 * entry fee. Blue dots won clearly more than they needed, red clearly less,
 * gray can't tell yet. Tap a band.
 */
@Composable
internal fun CalibrationChart(bands: List<ArenaEdge.CalBand>, scope: String) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (bands.isEmpty()) {
        Muted("No trades held to the close for $scope yet.")
        return
    }
    var tapped by remember(bands) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val i = tapped
        if (i == null) {
            val n = bands.sumOf { it.trades }
            val w = bands.sumOf { it.wins }
            val need = bands.sumOf { it.breakEven * it.trades } / n
            Readout("${pct(w.toDouble() / n)} won", "needed ${pct(need)} after fees · $n trades · tap a band")
        } else {
            val b = bands[i]
            Readout(
                "${pct(b.winRate)} won",
                "${cents(b.lo)}–${cents(b.hi)} · paid ${cents(b.avgPrice)} · needed ${pct(b.breakEven)} · ${b.trades} trades"
            )
        }
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(start = 30.dp)
                    .testTag("CALIBRATION")
                    .pointerInput(bands) {
                        detectTapGestures { o ->
                            val p = o.x / size.width
                            val k = bands.indices.minByOrNull { abs(bands[it].avgPrice - p) }
                            tapped = if (tapped == k) null else k
                        }
                    }
            ) {
                fun x(p: Double) = size.width * p.toFloat()
                fun y(p: Double) = size.height * (1f - p.toFloat())
                for (g in listOf(0.0, 0.5, 1.0)) {
                    drawLine(ac.grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1.dp.toPx())
                }
                drawLine(
                    ac.baseline, Offset(x(0.0), y(0.0)), Offset(x(1.0), y(1.0)),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
                bands.forEachIndexed { k, b ->
                    val c = when {
                        b.clearlyAbove -> ac.gain
                        b.clearlyBelow -> ac.loss
                        else -> ac.muted
                    }
                    val cx = x(b.avgPrice)
                    val top = (b.winRate + 2 * b.stdErr).coerceAtMost(1.0)
                    val bottom = (b.winRate - 2 * b.stdErr).coerceAtLeast(0.0)
                    drawLine(c.copy(alpha = 0.55f), Offset(cx, y(top)), Offset(cx, y(bottom)), strokeWidth = 1.25.dp.toPx(), cap = StrokeCap.Round)
                    val dash = 7.dp.toPx()
                    drawLine(colors.textSecondary, Offset(cx - dash, y(b.breakEven)), Offset(cx + dash, y(b.breakEven)), strokeWidth = 1.5.dp.toPx())
                    val r = (3.5 + 3.0 * minOf(b.trades, 60) / 60.0).dp.toPx()
                    drawCircle(ac.surface, radius = r + 1.5.dp.toPx(), center = Offset(cx, y(b.winRate)))
                    drawCircle(c, radius = r, center = Offset(cx, y(b.winRate)))
                    if (tapped == k) {
                        drawCircle(colors.textPrimary, radius = r + 3.dp.toPx(), center = Offset(cx, y(b.winRate)), style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
            Muted("100%", Modifier.align(Alignment.TopStart))
            Muted("50%", Modifier.align(Alignment.CenterStart))
            Muted("0%", Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth().padding(start = 30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted("0¢"); Muted("price paid"); Muted("100¢")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DotKey(ac.gain); Muted("beat the price")
            DotKey(ac.loss); Muted("lagged it")
            DotKey(ac.muted); Muted("can't tell yet")
        }
    }
}

// ---------------------------------------------------------------- projection fan

/**
 * The record so far, resampled: the band holds 80% of [ArenaEdge.Fan]'s
 * paths for the next trades, the line is the middle one. Drag to read.
 */
@Composable
internal fun ProjectionChart(fan: ArenaEdge.Fan?, scope: String) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (fan == null) {
        Muted("Needs ${ArenaEdge.MIN_FAN_TRADES} closed trades over ${ArenaEdge.MIN_FAN_WINDOWS} windows for $scope.")
        return
    }
    val lo0 = minOf(fan.p10.minOrNull() ?: 0.0, 0.0)
    val hi0 = maxOf(fan.p90.maxOrNull() ?: 0.0, 0.0)
    val pad = max((hi0 - lo0) * 0.08, 0.5)
    val lo = lo0 - pad
    val hi = hi0 + pad
    val mid = if (fan.p50.last() >= 0) ac.gain else ac.loss
    var scrub by remember(fan) { mutableStateOf<Int?>(null) }
    fun nearest(f: Float): Int {
        val s = f.coerceIn(0f, 1f) * fan.horizon
        return fan.steps.indices.minByOrNull { abs(fan.steps[it] - s) } ?: 0
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val i = scrub
        if (i == null) {
            Readout(
                "${pct(fan.pProfit)} finish ahead",
                "after ${fan.horizon} more trades · ${fan.sample} trades in ${fan.windows} windows, resampled by window · drag to read"
            )
        } else {
            Readout(
                ScalperResults.money(fan.p50[i]),
                "middle after ${fan.steps[i]} · 80% between ${ScalperResults.money(fan.p10[i])} and ${ScalperResults.money(fan.p90[i])}"
            )
        }
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .testTag("PROJECTION")
                    .pointerInput(fan) { detectTapGestures { o -> scrub = nearest(o.x / size.width) } }
                    .pointerInput(fan) { detectHorizontalDragGestures { ch, _ -> scrub = nearest(ch.position.x / size.width) } }
            ) {
                fun x(step: Int) = size.width * (step.toFloat() / fan.horizon)
                fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
                drawLine(ac.baseline, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
                val band = Path().apply {
                    moveTo(x(0), y(0.0))
                    fan.steps.forEachIndexed { k, s -> lineTo(x(s), y(fan.p90[k])) }
                    for (k in fan.steps.indices.reversed()) lineTo(x(fan.steps[k]), y(fan.p10[k]))
                    lineTo(x(0), y(0.0))
                    close()
                }
                drawPath(band, ac.series[0].copy(alpha = 0.16f))
                val line = Path().apply {
                    moveTo(x(0), y(0.0))
                    fan.steps.forEachIndexed { k, s -> lineTo(x(s), y(fan.p50[k])) }
                }
                drawPath(line, mid, style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                scrub?.let { k ->
                    val sx = x(fan.steps[k])
                    drawLine(colors.textSecondary, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1.dp.toPx())
                    drawCircle(ac.surface, radius = 6.dp.toPx(), center = Offset(sx, y(fan.p50[k])))
                    drawCircle(mid, radius = 4.dp.toPx(), center = Offset(sx, y(fan.p50[k])))
                }
            }
            Muted(ScalperResults.money(hi0), Modifier.align(Alignment.TopStart))
            Muted(ScalperResults.money(lo0), Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted("now"); Muted("+${fan.horizon} trades")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RectKey(ac.series[0].copy(alpha = 0.35f)); Muted("80% of paths")
            LineKey(mid); Muted("middle path")
        }
        Muted("Assumes the next trades look like the last ones. They may not.")
    }
}

// ---------------------------------------------------------------- rolling edge

/** Average result per contract over the last [window] trades, through time. Drag to read. */
@Composable
internal fun RollingEdgeChart(points: List<CurvePoint>, window: Int, scope: String) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (points.size < 2) {
        Muted("Needs ${window + 1} closed trades for $scope.")
        return
    }
    val t0 = points.first().tMs
    val t1 = max(points.last().tMs, t0 + 1)
    val lo0 = minOf(points.minOf { it.pnlUsd }, 0.0)
    val hi0 = maxOf(points.maxOf { it.pnlUsd }, 0.0)
    val pad = max((hi0 - lo0) * 0.1, 0.2)
    val lo = lo0 - pad
    val hi = hi0 + pad
    var scrub by remember(points) { mutableStateOf<Int?>(null) }
    fun nearest(f: Float): Int {
        val t = t0 + (f.coerceIn(0f, 1f) * (t1 - t0)).toLong()
        return points.indices.minByOrNull { abs(points[it].tMs - t) } ?: 0
    }
    fun c(v: Double) = String.format(Locale.US, "%+.1f¢", v)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val i = scrub
        if (i == null) {
            Readout("${c(points.last().pnlUsd)} a contract", "last $window trades · drag to read any moment")
        } else {
            Readout("${c(points[i].pnlUsd)} a contract", "$window trades to ${ScalperResults.timeLabel(points[i].tMs)}")
        }
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .testTag("ROLLING_EDGE")
                    .pointerInput(points) { detectTapGestures { o -> scrub = nearest(o.x / size.width) } }
                    .pointerInput(points) { detectHorizontalDragGestures { ch, _ -> scrub = nearest(ch.position.x / size.width) } }
            ) {
                fun x(t: Long) = size.width * ((t - t0).toFloat() / (t1 - t0).toFloat())
                fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
                val zero = y(0.0)
                drawLine(ac.baseline, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
                // Each segment takes the colour of its side of zero, so a fading edge shows as the line turning red.
                for (k in 1 until points.size) {
                    val a = points[k - 1]
                    val b = points[k]
                    val col = if ((a.pnlUsd + b.pnlUsd) / 2 >= 0) ac.gain else ac.loss
                    drawLine(col, Offset(x(a.tMs), y(a.pnlUsd)), Offset(x(b.tMs), y(b.pnlUsd)), strokeWidth = 1.25.dp.toPx(), cap = StrokeCap.Round)
                }
                scrub?.let { k ->
                    val p = points[k]
                    drawLine(colors.textSecondary, Offset(x(p.tMs), 0f), Offset(x(p.tMs), size.height), strokeWidth = 1.dp.toPx())
                    drawCircle(ac.surface, radius = 6.dp.toPx(), center = Offset(x(p.tMs), y(p.pnlUsd)))
                    drawCircle(if (p.pnlUsd >= 0) ac.gain else ac.loss, radius = 4.dp.toPx(), center = Offset(x(p.tMs), y(p.pnlUsd)))
                }
            }
            Muted(c(hi0), Modifier.align(Alignment.TopStart))
            Muted(c(lo0), Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(ScalperResults.timeLabel(t0))
            Muted(ScalperResults.timeLabel(t1))
        }
    }
}
