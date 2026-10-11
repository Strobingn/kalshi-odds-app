package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalper.ArenaStats
import com.dirk.kalshiodds.signal.scalper.CurvePoint
import com.dirk.kalshiodds.signal.scalper.LiveWindow
import com.dirk.kalshiodds.signal.scalper.PrintGrid
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.ui.theme.ArenaColors
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The second set of Arena charts: the live window with every paper entry and
 * exit on it, drawdown, the strategy × price and strategy × day heatmaps, the
 * hold-time columns and the per-strategy stats table. Numbers come from
 * [ArenaStats]; every value is also in a readout or the table.
 */

// ---------------------------------------------------------------- live window

/**
 * The window being traded right now: its YES price second by second, with a
 * ring at every paper entry and a dot at every exit (blue made money, red
 * lost, grey still open), joined by a thin line. DOWN trades are drawn on
 * the YES line (a DOWN entry at 40¢ sits at 60¢). Tap a mark to read it.
 */
@Composable
internal fun LiveWindowChart(window: LiveWindow?, markers: List<ArenaStats.Marker>, selected: ScalpStrategy?) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (window == null || window.mids.size < 2) {
        Muted("Waits for the first trades of the live window (Live signals on).")
        return
    }
    var tapped by remember(window.ticker) { mutableStateOf<ArenaStats.Marker?>(null) }
    val prices = window.mids.map { it.second } + markers.map { it.yesPrice }
    val lo = (prices.minOrNull() ?: 0.0).let { kotlin.math.floor(it * 20) / 20 }.coerceAtLeast(0.0)
    val hi = (prices.maxOrNull() ?: 1.0).let { kotlin.math.ceil(it * 20) / 20 }.coerceAtMost(1.0).let { if (it - lo < 0.1) lo + 0.1 else it }
    val openN = markers.count { it.kind == ArenaStats.MarkerKind.OPEN }
    val closedN = markers.count { it.kind == ArenaStats.MarkerKind.EXIT }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val t = tapped
        if (t == null) {
            Readout(
                ScalperResults.cents(window.mids.last().second),
                "UP now · ${ArenaStats.clock(window.mids.last().first.toDouble())} in · $closedN closed, $openN open · tap a mark"
            )
        } else {
            Readout(ArenaStats.clock(t.second), ArenaStats.markerLabel(t))
        }
        Box(Modifier.fillMaxWidth().height(210.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .testTag("LIVE_WINDOW")
                    .pointerInput(markers) {
                        detectTapGestures { o ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val best = markers.minByOrNull { m ->
                                val mx = (m.second / PrintGrid.SECONDS).toFloat() * w
                                val my = h * (1f - ((m.yesPrice - lo) / (hi - lo)).toFloat())
                                hypot(mx - o.x, my - o.y)
                            }
                            tapped = best?.takeIf { m ->
                                val mx = (m.second / PrintGrid.SECONDS).toFloat() * w
                                val my = h * (1f - ((m.yesPrice - lo) / (hi - lo)).toFloat())
                                hypot(mx - o.x, my - o.y) < 28.dp.toPx()
                            }
                        }
                    }
            ) {
                fun x(sec: Double) = size.width * (sec / PrintGrid.SECONDS).toFloat()
                fun y(p: Double) = size.height * (1f - ((p - lo) / (hi - lo)).toFloat())
                // Gridlines at every 10¢ inside the range; 50¢ is the baseline.
                var g = kotlin.math.ceil(lo * 10) / 10
                while (g <= hi + 1e-9) {
                    drawLine(if (abs(g - 0.5) < 1e-9) ac.baseline else ac.grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1.dp.toPx())
                    g += 0.1
                }
                // Minute ticks.
                for (m in 5..10 step 5) {
                    drawLine(ac.grid, Offset(x(m * 60.0), 0f), Offset(x(m * 60.0), size.height), strokeWidth = 1.dp.toPx())
                }
                val path = Path()
                window.mids.forEachIndexed { i, (s, p) -> if (i == 0) path.moveTo(x(s.toDouble()), y(p)) else path.lineTo(x(s.toDouble()), y(p)) }
                drawPath(path, colors.textSecondary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val dim = { m: ArenaStats.Marker -> selected != null && m.strategy != selected }
                fun colorOf(m: ArenaStats.Marker): Color = when {
                    m.kind == ArenaStats.MarkerKind.OPEN -> ac.muted
                    (m.pnlUsd ?: 0.0) >= 0 -> ac.gain
                    else -> ac.loss
                }
                // Connectors first (entry to exit of the same trade), then the marks on top.
                val exitOf = markers.filter { it.kind == ArenaStats.MarkerKind.EXIT && it.key != null }.associateBy { it.key }
                for (e in markers) {
                    if (e.kind != ArenaStats.MarkerKind.ENTRY) continue
                    val x2 = exitOf[e.key] ?: continue
                    val a = if (dim(e)) 0.15f else 0.6f
                    drawLine(colorOf(e).copy(alpha = a), Offset(x(e.second), y(e.yesPrice)), Offset(x(x2.second), y(x2.yesPrice)), strokeWidth = 1.dp.toPx())
                }
                for (m in markers) {
                    val c = colorOf(m).copy(alpha = if (dim(m)) 0.25f else 1f)
                    val center = Offset(x(m.second), y(m.yesPrice))
                    when (m.kind) {
                        ArenaStats.MarkerKind.EXIT -> {
                            drawCircle(ac.surface, radius = 6.dp.toPx(), center = center)
                            drawCircle(c, radius = 4.dp.toPx(), center = center)
                        }
                        else -> {
                            drawCircle(ac.surface, radius = 6.dp.toPx(), center = center)
                            drawCircle(c, radius = 4.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                    if (m == tapped) drawCircle(colors.textPrimary, radius = 8.dp.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
                }
            }
            Muted(ScalperResults.cents(hi), Modifier.align(Alignment.TopStart))
            Muted(ScalperResults.cents(lo), Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0:00", "5:00", "10:00", "15:00").forEach { Muted(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RingKey(ac.gain); Muted("entry")
            DotKey(ac.gain); Muted("exit, won")
            DotKey(ac.loss); Muted("lost")
            RingKey(ac.muted); Muted("open")
        }
    }
}

@Composable
internal fun RingKey(color: Color) {
    Canvas(Modifier.width(10.dp).height(10.dp)) {
        drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
    }
}

// ---------------------------------------------------------------- drawdown

/** How far the running P&L sits under its best so far (0 = at a new high). */
@Composable
internal fun DrawdownChart(points: List<CurvePoint>) {
    val ac = ArenaColors.current()
    if (points.size < 2) {
        Muted("Appears after the first closed trades.")
        return
    }
    val dd = ArenaStats.drawdown(points)
    val worst = dd.minOf { it.pnlUsd }
    val now = dd.last().pnlUsd
    val t0 = dd.first().tMs
    val t1 = maxOf(dd.last().tMs, t0 + 1)
    val lo = minOf(worst, -0.01) * 1.08
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Readout(ScalperResults.money(worst), "deepest fall from a high · now ${ScalperResults.money(now)}")
        Canvas(Modifier.fillMaxWidth().height(110.dp).testTag("DRAWDOWN")) {
            fun x(t: Long) = size.width * ((t - t0).toFloat() / (t1 - t0).toFloat())
            fun y(v: Double) = size.height * (v / lo).toFloat()
            drawLine(ac.baseline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            val line = Path()
            val wash = Path().apply { moveTo(x(dd.first().tMs), 0f) }
            dd.forEachIndexed { i, p ->
                if (i == 0) line.moveTo(x(p.tMs), y(p.pnlUsd)) else line.lineTo(x(p.tMs), y(p.pnlUsd))
                wash.lineTo(x(p.tMs), y(p.pnlUsd))
            }
            wash.lineTo(x(dd.last().tMs), 0f)
            wash.close()
            drawPath(wash, ac.loss.copy(alpha = 0.10f))
            drawPath(line, ac.loss, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(ScalperResults.timeLabel(t0))
            Muted(ScalperResults.timeLabel(t1))
        }
    }
}

// ---------------------------------------------------------------- heatmap

/**
 * Strategy × column grid, diverging around 0: blue made money, red lost,
 * gray ≈ nothing; deeper colour = bigger. Cells with fewer than
 * [minTrades] trades are only outlined (too few to read). Tap a cell.
 */
@Composable
internal fun HeatmapChart(
    grid: ArenaStats.Grid,
    columnLabel: (Int) -> String,
    axisLabels: List<String>,
    tag: String,
    value: (ArenaStats.Cell) -> Double = { it.avgCents },
    valueText: (Double) -> String = { String.format(java.util.Locale.US, "%+.1f¢ a contract", it) },
    minTrades: Int = 3,
    /** Fixed colour scale (e.g. 1.0 for a correlation); null scales to the biggest readable cell. */
    scaleMax: Double? = null,
    legend: List<String> = listOf("made money", "lost", "≈ 0"),
    countNoun: String = "trade"
) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (grid.isEmpty) {
        Muted("Fills in as trades close.")
        return
    }
    val maxAbs = scaleMax ?: grid.cells.flatten().filterNotNull().filter { it.trades >= minTrades }
        .maxOfOrNull { abs(value(it)) }?.takeIf { it > 1e-9 } ?: 1.0
    var tapped by remember(grid) { mutableStateOf<Pair<Int, Int>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val t = tapped
        val cell = t?.let { (r, c) -> grid.cells.getOrNull(r)?.getOrNull(c) }
        if (t == null || cell == null) {
            Muted("Tap a cell to read it.")
        } else {
            Readout(
                valueText(value(cell)),
                "${grid.rows[t.first].label} · ${columnLabel(t.second)} · ${cell.trades} $countNoun${if (cell.trades == 1) "" else "s"} · ${ScalperResults.money(cell.netUsd)}"
            )
        }
        grid.rows.forEachIndexed { r, st ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    st.label, style = MaterialTheme.typography.labelSmall, color = colors.textPrimary,
                    modifier = Modifier.width(108.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(18.dp)
                        .testTag("${tag}_ROW_${st.name}")
                        .pointerInput(grid, r) {
                            detectTapGestures { o ->
                                val c = (o.x / size.width * grid.columns.size).toInt().coerceIn(0, grid.columns.lastIndex)
                                tapped = if (tapped == (r to c)) null else r to c
                            }
                        }
                ) {
                    val n = grid.columns.size
                    val gap = 2.dp.toPx()
                    val w = (size.width - gap * (n - 1)) / n
                    for (c in 0 until n) {
                        val left = c * (w + gap)
                        val v = grid.cells[r][c]
                        val corner = CornerRadius(3.dp.toPx())
                        when {
                            v == null -> drawRoundRect(ac.grid, Offset(left, 0f), Size(w, size.height), corner)
                            v.trades < minTrades -> drawRoundRect(
                                ac.muted, Offset(left + 1, 1f), Size(w - 2, size.height - 2), corner, style = Stroke(width = 1.dp.toPx())
                            )
                            else -> {
                                val x = value(v)
                                val f = (abs(x) / maxAbs).toFloat().coerceIn(0f, 1f)
                                val fill = lerp(ac.neutral, if (x >= 0) ac.gain else ac.loss, 0.2f + 0.8f * f)
                                drawRoundRect(fill, Offset(left, 0f), Size(w, size.height), corner)
                            }
                        }
                        if (tapped == (r to c)) {
                            drawRoundRect(colors.textPrimary, Offset(left, 0f), Size(w, size.height), corner, style = Stroke(width = 1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 114.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            axisLabels.forEach { Muted(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RectKey(ac.gain); Muted(legend[0])
            RectKey(ac.loss); Muted(legend[1])
            RectKey(ac.neutral); Muted(legend[2])
            RingKey(ac.muted); Muted("< $minTrades ${countNoun}s")
        }
    }
}

// ---------------------------------------------------------------- hold time

/** Average result per contract by how long the trade was held; "close" = held to the settlement. */
@Composable
internal fun HoldTimeChart(buckets: List<ArenaStats.HoldBucket>, scope: String) {
    if (buckets.all { it.trades == 0 }) {
        Muted("No closed trades for $scope yet.")
        return
    }
    DivergingColumns(
        values = buckets.map { it.avgCents },
        tag = "HOLD_TIME",
        height = 120,
        negativeAt = { (buckets[it].avgCents ?: 0.0) < 0 },
        readout = { i ->
            buckets[i].avgCents?.let {
                String.format(java.util.Locale.US, "%+.1f¢ a contract", it) to "${buckets[i].label} · ${buckets[i].trades} trades"
            }
        }
    )
    Row(Modifier.fillMaxWidth()) {
        buckets.forEach { Muted(it.label, Modifier.weight(1f)) }
    }
}

// ---------------------------------------------------------------- stats table

/** Per-strategy numbers a trader reads first. Also the table view for the profit charts. */
@Composable
internal fun StatsTable(rows: List<ArenaStats.StrategyStats>) {
    val colors = DipTheme.colors
    if (rows.isEmpty()) {
        Muted("No closed trades yet.")
        return
    }
    Muted("¢/ct = average result per contract after fees · PF = won ÷ lost · DD = deepest fall from a high")
    rows.forEach { r ->
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("STATS_${r.strategy.name}")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(r.strategy.label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ScalperResults.money(r.netUsd), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("trades", "${r.trades}")
                Stat("¢/ct", String.format(java.util.Locale.US, "%+.2f", r.expectancyCents))
                Stat("PF", r.profitFactor?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "–")
                Stat("DD", ScalperResults.plainMoney(r.maxDrawdownUsd))
                Stat("hold", r.avgHoldSec?.let { holdLabel(it) } ?: "–")
                Stat("streak", "${r.longestWinStreak}W ${r.longestLossStreak}L")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = DipTheme.colors.textPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = DipTheme.colors.textSecondary)
    }
}

internal fun holdLabel(sec: Double): String = when {
    sec < 120 -> "${sec.toInt()}s"
    sec < 3600 -> "${(sec / 60).toInt()}m"
    else -> String.format(java.util.Locale.US, "%.1fh", sec / 3600)
}
