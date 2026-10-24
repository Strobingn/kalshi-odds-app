package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalper.ArenaTable
import com.dirk.kalshiodds.signal.scalper.PrintGrid
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.signal.scalper.micro.LiveMicro
import com.dirk.kalshiodds.signal.scalper.micro.MicroPoint
import com.dirk.kalshiodds.ui.theme.ArenaColors
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** The four colours of the micro charts: index fair, the book, the VWAP, Bitcoin against the strike. */
private data class MicroColors(val fair: Color, val book: Color, val vwap: Color, val spot: Color, val up: Color, val down: Color, val mid: Color)

@Composable
private fun microColors(): MicroColors {
    val c = DipTheme.colors
    val ac = ArenaColors.current()
    return MicroColors(fair = ac.series[0], book = ac.series[2], vwap = c.accentPurple, spot = ac.series[3], up = c.up, down = c.down, mid = ac.muted)
}

private fun money(v: Double): String = String.format(Locale.US, "$%,.2f", v)
private fun cents(v: Double): String = String.format(Locale.US, "%.1f¢", v * 100)
private fun clockLeft(s: Int): String { val left = (PrintGrid.SECONDS - s).coerceAtLeast(0); return "${left / 60}:${(left % 60).toString().padStart(2, '0')} left" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Keys(items: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((label, color) in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                LineKey(color)
                Muted(label)
            }
        }
    }
}

/** A small rounded tag: the AI's call on the window. */
@Composable
private fun CallTag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** A line through the points [ys] (null = gap) placed at seconds [xs]. */
private fun DrawScope.series(xs: List<Int>, ys: List<Double?>, x: (Int) -> Float, y: (Double) -> Float, color: Color, width: Float) {
    val path = Path()
    var pen = false
    for (i in xs.indices) {
        val v = ys[i]
        if (v == null || !v.isFinite()) { pen = false; continue }
        if (!pen) path.moveTo(x(xs[i]), y(v)) else path.lineTo(x(xs[i]), y(v))
        pen = true
    }
    drawPath(path, color, style = Stroke(width = width))
}

/**
 * The live window as the AI follows it: Bitcoin against the strike on top (dollars), and below it the UP bid, the
 * DOWN bid and the index's Student-t fair chance of UP (cents), second by second.
 */
@Composable
internal fun LiveFollowerChart(micro: LiveMicro?) {
    val mc = microColors()
    val ac = ArenaColors.current()
    val pts = micro?.points.orEmpty()
    if (pts.size < 2) {
        Muted("Waits for the live window and the settlement index (Live signals on).")
        return
    }
    val last = pts.last()
    val fair = pts.lastOrNull { it.fair != null }?.fair
    val spot = pts.lastOrNull { it.spot != null }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                val k = spot?.strike
                val sp = spot?.spot
                Text(
                    if (k != null && sp != null) "Strike ${money(k)} · BTC ${money(sp)} (${if (sp >= k) "+" else "−"}${money(abs(sp - k))})" else "Index not in yet",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = DipTheme.colors.textPrimary
                )
                Muted(
                    "${clockLeft(last.s)} · UP ${last.yesBid?.let { cents(it) } ?: "–"}/${last.yesAsk?.let { cents(it) } ?: "–"} · " +
                        "DOWN ${last.yesAsk?.let { cents(1 - it) } ?: "–"}/${last.yesBid?.let { cents(1 - it) } ?: "–"}"
                )
            }
            if (fair != null) CallTag(if (fair >= 0.5) "AI UP ${Math.round(fair * 100)}%" else "AI DOWN ${Math.round((1 - fair) * 100)}%", if (fair >= 0.5) mc.up else mc.down)
        }
        val dists = pts.mapNotNull { p -> if (p.spot != null && p.strike != null) p.spot - p.strike else null }
        val dMax = max(1.0, dists.maxOfOrNull { abs(it) } ?: 1.0) * 1.1
        val prices = pts.flatMap { listOfNotNull(it.yesBid, it.noBid, it.fair) }
        val lo = (prices.minOrNull() ?: 0.0).let { kotlin.math.floor(it * 20) / 20 }.coerceAtLeast(0.0)
        val hi = (prices.maxOrNull() ?: 1.0).let { kotlin.math.ceil(it * 20) / 20 }.coerceAtMost(1.0).let { if (it - lo < 0.1) lo + 0.1 else it }
        Canvas(Modifier.fillMaxWidth().height(220.dp).testTag("CHART_FOLLOW")) {
            val w = size.width
            val top = size.height * 0.38f
            val gap = 10.dp.toPx()
            fun x(s: Int) = w * (s / PrintGrid.SECONDS.toFloat())
            // Top pane: Bitcoin minus the strike.
            fun yd(d: Double) = top / 2f - (d / dMax).toFloat() * (top / 2f)
            drawLine(ac.baseline, Offset(0f, yd(0.0)), Offset(w, yd(0.0)), strokeWidth = 1.dp.toPx())
            val xs = pts.map { it.s }
            series(xs, pts.map { p -> if (p.spot != null && p.strike != null) p.spot - p.strike else null }, ::x, ::yd, mc.spot, 1.4.dp.toPx())
            // Bottom pane: the two bids and the fair chance of UP.
            val b0 = top + gap
            val bh = size.height - b0
            fun yp(p: Double) = b0 + bh * (1f - ((p - lo) / (hi - lo)).toFloat())
            var g = kotlin.math.ceil(lo * 10) / 10
            while (g <= hi + 1e-9) {
                drawLine(if (abs(g - 0.5) < 1e-9) ac.baseline else ac.grid, Offset(0f, yp(g)), Offset(w, yp(g)), strokeWidth = 1.dp.toPx())
                g += 0.1
            }
            for (m in 5..10 step 5) drawLine(ac.grid, Offset(x(m * 60), 0f), Offset(x(m * 60), size.height), strokeWidth = 1.dp.toPx())
            series(xs, pts.map { it.yesBid }, ::x, ::yp, mc.up, 1.25.dp.toPx())
            series(xs, pts.map { it.noBid }, ::x, ::yp, mc.down, 1.2.dp.toPx())
            series(xs, pts.map { it.fair }, ::x, ::yp, mc.fair, 1.25.dp.toPx())
        }
        Keys(listOf("BTC − strike" to mc.spot, "UP bid" to mc.up, "DOWN bid" to mc.down, "Fair UP (Student-t)" to mc.fair))
    }
}

/**
 * The order book second by second over the last five minutes: the UP mid, the index's fair, the micro-price (the
 * mid leaned to the thinner side) and the 30-second VWAP of the prints, with the five-level imbalance (OBI5) and order
 * flow (OFI5) underneath. What the four micro scalpers trade on.
 */
@Composable
internal fun MicroRibbonChart(micro: LiveMicro?) {
    val mc = microColors()
    val ac = ArenaColors.current()
    val all = micro?.points.orEmpty()
    if (all.size < 2) {
        Muted("Waits for the live window's book (Live signals on).")
        return
    }
    val pts = all.filter { it.s >= all.last().s - WINDOW_S }
    val last = pts.last()
    fun sgn(v: Double?, f: String) = v?.let { String.format(Locale.US, f, it) } ?: "–"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Readout(
            "fair ${last.fair?.let { cents(it) } ?: "–"}",
            "micro ${last.micro?.let { cents(it) } ?: "–"} · VWAP ${last.vwap?.let { cents(it) } ?: "–"} · " +
                "OBI5 ${sgn(last.obi5, "%+.2f")} · OFI5 ${sgn(last.ofi5, "%+.2f")} · index ${sgn(last.idxZ10, "%+.1f")}σ/10s"
        )
        val prices = pts.flatMap { listOfNotNull(it.yesMid, it.fair, it.micro, it.vwap) }
        val lo = (prices.minOrNull() ?: 0.0) - 0.01
        val hi = ((prices.maxOrNull() ?: 1.0) + 0.01).let { if (it - lo < 0.04) lo + 0.04 else it }
        val first = pts.first().s
        val span = (last.s - first).coerceAtLeast(30)
        Canvas(Modifier.fillMaxWidth().height(170.dp).testTag("CHART_MICRO")) {
            val w = size.width
            fun x(s: Int) = w * ((s - first).toFloat() / span)
            fun y(p: Double) = size.height * (1f - ((p - lo) / (hi - lo)).toFloat())
            var g = kotlin.math.ceil(lo * 100 / 5) * 5 / 100
            while (g <= hi + 1e-9) {
                drawLine(ac.grid, Offset(0f, y(g)), Offset(w, y(g)), strokeWidth = 1.dp.toPx())
                g += 0.05
            }
            val xs = pts.map { it.s }
            series(xs, pts.map { it.yesMid }, ::x, ::y, mc.mid, 1.dp.toPx())
            series(xs, pts.map { it.vwap }, ::x, ::y, mc.vwap, 1.2.dp.toPx())
            series(xs, pts.map { it.micro }, ::x, ::y, mc.book, 1.2.dp.toPx())
            series(xs, pts.map { it.fair }, ::x, ::y, mc.fair, 1.4.dp.toPx())
        }
        Keys(listOf("UP mid" to mc.mid, "Fair (index)" to mc.fair, "Micro-price" to mc.book, "VWAP 30 s" to mc.vwap))
        Canvas(Modifier.fillMaxWidth().height(70.dp).testTag("CHART_OFI")) {
            val w = size.width
            val h = size.height
            fun x(s: Int) = w * ((s - first).toFloat() / span)
            val mid = h / 2f
            drawLine(ac.baseline, Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())
            val barW = max(1f, w / span * 0.7f)
            for (p in pts) {
                val o = p.ofi5 ?: continue
                val v = (o / OFI_SCALE).coerceIn(-1.0, 1.0).toFloat()
                val color = if (v >= 0) mc.up.copy(alpha = 0.55f) else mc.down.copy(alpha = 0.55f)
                val top = if (v >= 0) mid - v * mid else mid
                drawRect(color, Offset(x(p.s) - barW / 2, top), androidx.compose.ui.geometry.Size(barW, abs(v) * mid))
            }
            series(pts.map { it.s }, pts.map { it.obi5 }, ::x, { v -> mid - v.toFloat().coerceIn(-1f, 1f) * mid }, mc.fair, 1.2.dp.toPx())
        }
        Keys(listOf("OBI5 (line, −1…+1)" to mc.fair, "OFI5 (bars, ±$OFI_SCALE books)" to mc.up))
    }
}

private const val WINDOW_S = 300
private const val OFI_SCALE = 2.0

/**
 * Risk and return: each strategy that has traded as a bubble at its win rate (across) and its net after fees (up),
 * sized by its trades. The dashed line is 50% won. Tap a bubble to read it (and follow it).
 */
@Composable
internal fun RiskReturnChart(rows: List<ArenaTable.Row>, selected: ScalpStrategy?, onSelect: (ScalpStrategy) -> Unit) {
    val ac = ArenaColors.current()
    val pts = rows.filter { it.trades > 0 && it.winRate != null }
    if (pts.isEmpty()) {
        Muted("Draws once strategies have closed trades.")
        return
    }
    var tapped by remember(pts.size) { mutableStateOf<ArenaTable.Row?>(null) }
    val yMax = max(1.0, pts.maxOf { abs(it.netUsd) }) * 1.15
    val maxTrades = pts.maxOf { it.trades }.coerceAtLeast(1)
    val shown = tapped ?: pts.firstOrNull { it.strategy == selected }
    if (shown != null) {
        Readout(ScalperResults.money(shown.netUsd), "${shown.strategy.label} · ${ScalperResults.pct(shown.winRate)} won · ${shown.trades} trades")
    } else {
        Muted("${pts.size} strategies · ${pts.count { it.netUsd > 0 }} in profit · tap a bubble")
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .testTag("CHART_RISK_RETURN")
            .pointerInput(pts) {
                detectTapGestures { o ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val best = pts.minByOrNull { r ->
                        val bx = (r.winRate!!).toFloat() * w
                        val by = h / 2f - (r.netUsd / yMax).toFloat() * (h / 2f)
                        hypot(bx - o.x, by - o.y)
                    }
                    if (best != null) {
                        tapped = best
                        onSelect(best.strategy)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        fun x(wr: Double) = (wr.toFloat() * w).coerceIn(0f, w)
        fun y(net: Double) = h / 2f - (net / yMax).toFloat() * (h / 2f)
        for (g in listOf(0.25, 0.75)) drawLine(ac.grid, Offset(x(g), 0f), Offset(x(g), h), strokeWidth = 1.dp.toPx())
        drawLine(ac.baseline, Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 1.dp.toPx())
        drawLine(ac.muted, Offset(x(0.5), 0f), Offset(x(0.5), h), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        for (r in pts.sortedByDescending { it.trades }) {
            val rad = (4.dp.toPx() + 14.dp.toPx() * sqrt(r.trades.toFloat() / maxTrades))
            val color = if (r.netUsd >= 0) ac.gain else ac.loss
            val c = Offset(x(r.winRate!!), y(r.netUsd))
            val focus = r.strategy == selected || r == tapped
            drawCircle(color.copy(alpha = if (focus) 0.6f else 0.3f), rad, c)
            drawCircle(color, rad, c, style = Stroke(width = if (focus) 2.5.dp.toPx() else 1.2.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted("0% won")
        Muted("50%")
        Muted("100% won")
    }
}
