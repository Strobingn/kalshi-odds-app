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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalper.ArenaExec
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.ui.theme.ArenaColors
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The Arena's "Execution" charts: the payoff map, buy-now against resting
 * twins, fill rates, the ML forecast check and the daily rank race. Numbers
 * come from [ArenaExec]; every chart has a tap or drag readout.
 */

private fun c1(v: Double): String = String.format(Locale.US, "%+.1f¢", v)

private fun pct(v: Double): String = "${(v * 100).toInt()}%"

// ---------------------------------------------------------------- payoff map

/** Highest payoff the map draws; dots above it sit on the top edge. */
private const val PAYOFF_MAX = 3.0

/**
 * Win often or win big: each strategy at its win rate (across) and its
 * average win ÷ average loss (up). Above the dashed curve made money,
 * below it lost; the curve is where the two cancel. Tap a dot.
 */
@Composable
internal fun PayoffMapChart(dots: List<ArenaExec.PayoffDot>, selected: ScalpStrategy?) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (dots.isEmpty()) {
        Muted("Needs ${ArenaExec.MIN_PAYOFF_TRADES} trades with a win and a loss for a strategy.")
        return
    }
    var tapped by remember(dots) { mutableStateOf<ScalpStrategy?>(null) }
    val focus = tapped ?: selected
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val d = dots.firstOrNull { it.strategy == focus }
        if (d == null) {
            val ahead = dots.count { it.ahead }
            Readout("$ahead of ${dots.size} above the line", "tap a dot")
        } else {
            Readout(
                d.strategy.label,
                "wins ${pct(d.hitRate)} · win ${c1(d.avgWinCents)} vs loss ${c1(-d.avgLossCents)} · " +
                    "needs ${String.format(Locale.US, "%.2f", d.breakEvenPayoff)}×, has ${String.format(Locale.US, "%.2f", d.payoff)}×"
            )
        }
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(start = 26.dp)
                    .testTag("PAYOFF")
                    .pointerInput(dots) {
                        detectTapGestures { o ->
                            val hit = dots.minByOrNull {
                                hypot(o.x - size.width * it.hitRate.toFloat(), o.y - size.height * (1f - (it.payoff.coerceAtMost(PAYOFF_MAX) / PAYOFF_MAX).toFloat()))
                            }?.strategy
                            tapped = if (tapped == hit) null else hit
                        }
                    }
            ) {
                fun x(h: Double) = size.width * h.toFloat()
                fun y(p: Double) = size.height * (1f - (p.coerceIn(0.0, PAYOFF_MAX) / PAYOFF_MAX).toFloat())
                for (g in listOf(1.0, 2.0)) {
                    drawLine(ac.grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1.dp.toPx())
                }
                drawLine(ac.grid, Offset(x(0.5), 0f), Offset(x(0.5), size.height), strokeWidth = 1.dp.toPx())
                // Break-even: payoff = (1 − h) ÷ h, drawn where it is on the chart.
                val curve = Path()
                var started = false
                for (i in 0..100) {
                    val h = 0.25 + 0.75 * i / 100.0
                    val p = (1 - h) / h
                    if (p > PAYOFF_MAX) continue
                    if (!started) { curve.moveTo(x(h), y(p)); started = true } else curve.lineTo(x(h), y(p))
                }
                drawPath(curve, ac.baseline, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                for (dot in dots) {
                    val c = if (dot.ahead) ac.gain else ac.loss
                    val r = (3.5 + 3.0 * minOf(dot.trades, 100) / 100.0).dp.toPx()
                    val center = Offset(x(dot.hitRate), y(dot.payoff))
                    val dim = focus != null && dot.strategy != focus
                    drawCircle(ac.surface, radius = r + 1.5.dp.toPx(), center = center)
                    drawCircle(if (dim) c.copy(alpha = 0.35f) else c, radius = r, center = center)
                    if (dot.strategy == focus) {
                        drawCircle(colors.textPrimary, radius = r + 3.dp.toPx(), center = center, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
            Muted("${PAYOFF_MAX.toInt()}×", Modifier.align(Alignment.TopStart))
            Muted("0×", Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth().padding(start = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted("0%"); Muted("win rate"); Muted("100%")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DotKey(ac.gain); Muted("made money")
            DotKey(ac.loss); Muted("lost")
            LineKey(ac.baseline); Muted("break-even")
        }
        Muted("Up = average win ÷ average loss. Bigger dot = more trades.")
    }
}

// ---------------------------------------------------------------- buy now or rest

/**
 * The same signal entered two ways, side by side: pay the ask now, or rest
 * a bid and wait. Bars are the average result per contract with ±2
 * standard-error whiskers; the resting half also says how often it filled.
 */
@Composable
internal fun TwinChart(twins: List<ArenaExec.Twin>) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (twins.isEmpty()) {
        Muted("No dip, momentum or reversion trades yet.")
        return
    }
    val sides = twins.flatMap { listOfNotNull(it.taker, it.maker) }
    val lo0 = sides.minOf { it.avgCents - 2 * it.stdErrCents }
    val hi0 = sides.maxOf { it.avgCents + 2 * it.stdErrCents }
    val (lo, hi) = padded(lo0, hi0)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RectKey(ac.series[1]); Muted("buy now")
        RectKey(ac.series[0]); Muted("resting bid")
    }
    twins.forEach { t ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.signal, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
            listOf(t.taker to ac.series[1], t.maker to ac.series[0]).forEachIndexed { k, (s, col) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.weight(1f).height(14.dp).testTag("TWIN_${t.signal}_$k")) {
                        fun x(v: Double) = size.width * ((v - lo) / (hi - lo)).toFloat()
                        val zero = x(0.0)
                        drawLine(ac.baseline, Offset(zero, -2f), Offset(zero, size.height + 2f), strokeWidth = 1.dp.toPx())
                        if (s == null) return@Canvas
                        val v = x(s.avgCents)
                        if (v >= zero) bar(zero, 1f, v, size.height - 1f, col, BarEnd.RIGHT)
                        else bar(v, 1f, zero, size.height - 1f, col, BarEnd.LEFT)
                        val cy = size.height / 2
                        drawLine(
                            colors.textSecondary, Offset(x(s.avgCents - 2 * s.stdErrCents), cy), Offset(x(s.avgCents + 2 * s.stdErrCents), cy),
                            strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round
                        )
                    }
                    val text = when {
                        s == null -> "no trades"
                        k == 1 && t.makerFillRate != null -> "${c1(s.avgCents)} · ${s.trades} · ${pct(t.makerFillRate)} filled"
                        else -> "${c1(s.avgCents)} · ${s.trades}"
                    }
                    Muted(text, Modifier.width(132.dp))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(end = 138.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted(c1(lo)); Muted(c1(hi))
    }
    Muted("Per contract after fees. Whiskers are ±2 standard errors: bars whose whiskers overlap are not yet different.")
}

/** Axis bounds with a little room, always including 0. */
private fun padded(lo: Double, hi: Double): Pair<Double, Double> {
    val a = minOf(lo, 0.0)
    val b = maxOf(hi, 0.0)
    val pad = max((b - a) * 0.08, 0.5)
    return (a - pad) to (b + pad)
}

// ---------------------------------------------------------------- fill rate

/** Share of each resting strategy's bids that filled. Buy-now orders always fill. */
@Composable
internal fun FillRateChart(rows: List<ArenaExec.FillRate>) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (rows.isEmpty()) {
        Muted("No resting bids posted yet.")
        return
    }
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                r.strategy.label, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Canvas(Modifier.width(110.dp).height(14.dp).testTag("FILL_${r.strategy.name}")) {
                drawRoundRect(ac.grid, cornerRadius = CornerRadius(4.dp.toPx()))
                bar(0f, 0f, size.width * r.rate.toFloat(), size.height, ac.series[0], BarEnd.RIGHT)
            }
            Muted("${r.filled} of ${r.posted}", Modifier.width(78.dp))
        }
    }
    Muted("A bid that rarely fills may only fill when the price is about to move against it.")
}

// ---------------------------------------------------------------- model check

/**
 * The ML scalper's forecast at posting against what its filled trades made,
 * per forecast band, with ±2 standard-error whiskers. On the dashed line
 * the forecast was right on average.
 */
@Composable
internal fun ModelCheckChart(bands: List<ArenaExec.ModelBand>) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (bands.isEmpty()) {
        Muted("The ML scalper has not filled a trade yet.")
        return
    }
    val lo0 = minOf(bands.minOf { minOf(it.expectedCents, it.realizedCents - 2 * it.stdErrCents) }, 0.0)
    val hi0 = maxOf(bands.maxOf { maxOf(it.expectedCents, it.realizedCents + 2 * it.stdErrCents) }, 0.0)
    val pad = max((hi0 - lo0) * 0.08, 0.3)
    val lo = lo0 - pad
    val hi = hi0 + pad
    var tapped by remember(bands) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val i = tapped
        if (i == null) {
            val n = bands.sumOf { it.trades }
            val exp = bands.sumOf { it.expectedCents * it.trades } / n
            val got = bands.sumOf { it.realizedCents * it.trades } / n
            Readout("${c1(got)} made", "forecast ${c1(exp)} · $n fills · tap a band")
        } else {
            val b = bands[i]
            Readout("${c1(b.realizedCents)} made", "forecast ${b.label}, avg ${c1(b.expectedCents)} · ${b.trades} fills")
        }
        Box(Modifier.fillMaxWidth().height(180.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(start = 34.dp)
                    .testTag("MODEL_CHECK")
                    .pointerInput(bands) {
                        detectTapGestures { o ->
                            val v = lo + (o.x / size.width) * (hi - lo)
                            val k = bands.indices.minByOrNull { abs(bands[it].expectedCents - v) }
                            tapped = if (tapped == k) null else k
                        }
                    }
            ) {
                fun x(v: Double) = size.width * ((v - lo) / (hi - lo)).toFloat()
                fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
                drawLine(ac.grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
                drawLine(ac.grid, Offset(x(0.0), 0f), Offset(x(0.0), size.height), strokeWidth = 1.dp.toPx())
                drawLine(
                    ac.baseline, Offset(x(lo), y(lo)), Offset(x(hi), y(hi)),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
                bands.forEachIndexed { k, b ->
                    val c = if (b.realizedCents >= 0) ac.gain else ac.loss
                    val cx = x(b.expectedCents)
                    drawLine(
                        c.copy(alpha = 0.55f), Offset(cx, y(b.realizedCents + 2 * b.stdErrCents)), Offset(cx, y(b.realizedCents - 2 * b.stdErrCents)),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                    )
                    val r = (3.5 + 3.0 * minOf(b.trades, 60) / 60.0).dp.toPx()
                    drawCircle(ac.surface, radius = r + 1.5.dp.toPx(), center = Offset(cx, y(b.realizedCents)))
                    drawCircle(c, radius = r, center = Offset(cx, y(b.realizedCents)))
                    if (tapped == k) {
                        drawCircle(colors.textPrimary, radius = r + 3.dp.toPx(), center = Offset(cx, y(b.realizedCents)), style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
            Muted(c1(hi0), Modifier.align(Alignment.TopStart))
            Muted(c1(lo0), Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth().padding(start = 34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(c1(lo0)); Muted("forecast at posting"); Muted(c1(hi0))
        }
        Muted("Only bids that filled count, and fills skew to the moments the price is leaving: below the line is partly that.")
    }
}

// ---------------------------------------------------------------- rank race

/**
 * Every strategy's place at the end of each day, 1 on top. The leaders on
 * the last day (or the one you follow) are in colour. Drag to read a day.
 */
@Composable
internal fun RankRaceChart(race: ArenaExec.RankRace, selected: ScalpStrategy?) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    if (race.isEmpty) {
        Muted("Fills in as trades close.")
        return
    }
    val days = race.days.size
    val field = max(race.field, 2)
    val lastRank = { l: ArenaExec.RankLine -> l.ranks.lastOrNull { it != null } ?: Int.MAX_VALUE }
    val lit: List<ScalpStrategy> = if (selected != null && race.lines.any { it.strategy == selected }) {
        listOf(selected)
    } else {
        race.lines.sortedBy(lastRank).take(3).map { it.strategy }
    }
    val colorOf = lit.withIndex().associate { (i, st) -> st to ac.series[i] }
    var scrub by remember(race) { mutableStateOf<Int?>(null) }
    fun nearest(f: Float): Int = (f.coerceIn(0f, 1f) * (days - 1) + 0.5f).toInt().coerceIn(0, days - 1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val d = scrub ?: (days - 1)
        val head = race.lines.filter { it.ranks[d] != null }.sortedBy { it.ranks[d] }
        val leader = head.firstOrNull()
        if (leader == null) {
            Readout(race.days[d], "nobody had closed a trade yet")
        } else {
            Readout(
                "${leader.strategy.label} #1",
                "${race.days[d]} · ${ScalperResults.money(leader.netUsd[d] ?: 0.0)} · ${head.size} ranked" + if (scrub == null) " · drag to read a day" else ""
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .testTag("RANK_RACE")
                .pointerInput(race) { detectTapGestures { o -> scrub = nearest(o.x / size.width) } }
                .pointerInput(race) { detectHorizontalDragGestures { ch, _ -> scrub = nearest(ch.position.x / size.width) } }
        ) {
            fun x(i: Int) = if (days == 1) size.width / 2 else size.width * i / (days - 1).toFloat()
            val pad = 6.dp.toPx()
            fun yy(rank: Int) = pad + (size.height - 2 * pad) * ((rank - 1) / (field - 1).toFloat()).coerceIn(0f, 1f)
            // Muted lines first, coloured ones on top.
            val ordered = race.lines.sortedBy { if (it.strategy in colorOf) 1 else 0 }
            for (l in ordered) {
                val col = colorOf[l.strategy]
                val path = Path()
                var on = false
                l.ranks.forEachIndexed { i, r ->
                    if (r == null) { on = false; return@forEachIndexed }
                    if (!on) { path.moveTo(x(i), yy(r)); on = true } else path.lineTo(x(i), yy(r))
                }
                drawPath(
                    path, col ?: ac.muted.copy(alpha = 0.35f),
                    style = Stroke(width = if (col != null) 2.5.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                if (col != null) {
                    val i = l.ranks.indexOfLast { it != null }
                    if (i >= 0) {
                        drawCircle(ac.surface, radius = 5.dp.toPx(), center = Offset(x(i), yy(l.ranks[i]!!)))
                        drawCircle(col, radius = 3.5.dp.toPx(), center = Offset(x(i), yy(l.ranks[i]!!)))
                    }
                }
            }
            scrub?.let { i ->
                drawLine(colors.textSecondary, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(race.days.first()); Muted("top = #1"); Muted(race.days.last())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            lit.forEach { st ->
                LineKey(colorOf.getValue(st))
                Muted(st.label, Modifier.width(96.dp))
            }
        }
    }
}
