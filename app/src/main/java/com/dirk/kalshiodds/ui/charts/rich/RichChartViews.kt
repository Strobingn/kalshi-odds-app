@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dirk.kalshiodds.ui.charts.rich

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.EtTime
import com.dirk.kalshiodds.ui.charts.ChartBlock
import com.dirk.kalshiodds.ui.charts.ChartEmptyState
import com.dirk.kalshiodds.ui.charts.chartMoney
import com.dirk.kalshiodds.ui.charts.chartSignTextColor
import com.dirk.kalshiodds.ui.components.FieldCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class LineSpec(
    val name: String,
    val color: Color,
    val points: List<RichTradeMath.Sample>,
    val dashed: Boolean = false
)

private const val DRAW_CAP = 360

@Composable
fun RichReportCharts(
    report: RichTradeMath.Report,
    compare: List<RichTradeMath.NamedSeries>,
    scalp: Boolean,
    capped: Boolean
) {
    val n = report.summary.n
    val colors = DipTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (capped) {
            Text(
                "Newest ${RichTradeMath.MAX_ROWS} closed trades.",
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        FieldCard { SummaryGrid(report.summary) }
        FieldCard {
            ChartBlock("Cumulative net P&L", note(n, "After fees. UP and DOWN on the same chart.")) {
                EtLineChart(
                    listOf(
                        LineSpec("Combined", colors.spot, report.equity),
                        LineSpec("UP", colors.upButton, report.equityUp),
                        LineSpec("DOWN", colors.downButton, report.equityDown)
                    ),
                    emptyText = emptyTrades(n),
                    contentLabel = "Cumulative net P and L"
                )
            }
        }
        if (scalp) {
            FieldCard {
                ChartBlock("Drawdown", note(n, "Peak to current, after fees.")) {
                    EtLineChart(
                        listOf(LineSpec("Drawdown", colors.downButton, report.drawdown)),
                        emptyText = emptyTrades(n),
                        contentLabel = "Drawdown"
                    )
                }
            }
            FieldCard {
                ChartBlock("Per-trade P&L", note(n, "UP and DOWN counts in each band.")) {
                    SideBars(report.pnlHistogram, format = ::countText, emptyText = emptyTrades(n), contentLabel = "P and L histogram")
                }
            }
            FieldCard {
                ChartBlock("Rolling win rate", rollingNote(n, report.rollingWin20, 20, report.rollingWin50, 50)) {
                    EtLineChart(
                        listOf(
                            LineSpec("20", colors.upButton, report.rollingWin20),
                            LineSpec("50", colors.downButton, report.rollingWin50, dashed = true)
                        ),
                        yFormat = { String.format(Locale.US, "%.0f%%", it * 100) },
                        fixedRange = 0.0 to 1.0,
                        emptyText = rollingNote(n, report.rollingWin20, 20, report.rollingWin50, 50),
                        contentLabel = "Rolling win rate"
                    )
                }
            }
            FieldCard {
                ChartBlock("Rolling net per bet", rollingNote(n, report.rollingNet20, 20, report.rollingNet50, 50)) {
                    EtLineChart(
                        listOf(
                            LineSpec("20", colors.upButton, report.rollingNet20),
                            LineSpec("50", colors.downButton, report.rollingNet50, dashed = true)
                        ),
                        emptyText = rollingNote(n, report.rollingNet20, 20, report.rollingNet50, 50),
                        contentLabel = "Rolling net per bet"
                    )
                }
            }
            FieldCard {
                ChartBlock("P&L by entry price", note(n, "5¢ bands. UP and DOWN together.")) {
                    SideBars(report.byEntry, emptyText = emptyTrades(n), contentLabel = "P and L by entry price")
                }
            }
            FieldCard {
                ChartBlock("P&L by time left", note(n, "Time left in the window at entry.")) {
                    SideBars(report.byTimeLeft, emptyText = emptyTrades(n), contentLabel = "P and L by time left")
                }
            }
            FieldCard {
                ChartBlock("P&L by hour of day", note(n, "Entry hour, US Eastern.")) {
                    SideBars(report.byHour, emptyText = emptyTrades(n), contentLabel = "P and L by hour")
                }
            }
            FieldCard {
                ChartBlock("Hold time", note(n, "UP and DOWN counts.")) {
                    SideBars(report.hold, format = ::countText, emptyText = emptyTrades(n), contentLabel = "Hold time")
                }
            }
            FieldCard {
                ChartBlock("Exit reason", note(n, "TP, stop, settle, time.")) {
                    SideBars(report.exits, emptyText = emptyTrades(n), contentLabel = "Exit reason")
                }
            }
            FieldCard {
                ChartBlock(
                    "Fees vs gross",
                    note(n, "Gross ${chartMoney(report.summary.grossUsd)} · fees ${chartMoney(report.summary.feesUsd)}")
                ) {
                    EtLineChart(
                        listOf(
                            LineSpec("Gross", colors.upButton, report.grossSeries),
                            LineSpec("Fees", colors.downButton, report.feeSeries, dashed = true)
                        ),
                        emptyText = emptyTrades(n),
                        contentLabel = "Fees versus gross"
                    )
                }
            }
        } else {
            FieldCard {
                ChartBlock("Daily P&L", note(n, "US Eastern close day. UP and DOWN together.")) {
                    SideBars(report.daily, emptyText = emptyTrades(n), contentLabel = "Daily P and L")
                }
            }
            FieldCard {
                ChartBlock("Calibration", calibrationNote(n, report.calibration)) {
                    CalibrationChart(report.calibration, emptyText = calibrationNote(n, report.calibration))
                }
            }
            FieldCard {
                ChartBlock("Brier score", brierNote(n, report.brier)) {
                    EtLineChart(
                        listOf(LineSpec("Brier", colors.spot, report.brier)),
                        yFormat = { String.format(Locale.US, "%.3f", it) },
                        includeZero = true,
                        emptyText = brierNote(n, report.brier),
                        contentLabel = "Brier score"
                    )
                }
            }
            FieldCard {
                ChartBlock("P&L by confidence", note(n, "Model confidence. UP and DOWN together.")) {
                    SideBars(report.byConfidence, emptyText = confidenceNote(n, report.byConfidence), contentLabel = "P and L by confidence")
                }
            }
            FieldCard {
                ChartBlock("Open exposure", exposureNote(report)) {
                    EtLineChart(
                        listOf(LineSpec("Open", colors.spot, report.exposure)),
                        emptyText = exposureNote(report),
                        contentLabel = "Open exposure"
                    )
                }
            }
            FieldCard {
                ChartBlock("Bet size", note(n + report.openCount, "Contracts. UP and DOWN together.")) {
                    SideBars(report.sizeBins, format = ::countText, emptyText = emptyTrades(n + report.openCount), contentLabel = "Bet size")
                }
            }
        }
        FieldCard {
            ChartBlock("UP vs DOWN", note(n, "Net after fees, both sides.")) {
                SideBars(report.bySide, emptyText = emptyTrades(n), contentLabel = "UP versus DOWN")
            }
        }
        if (scalp && compare.isNotEmpty()) {
            val palette = listOf(colors.upButton, colors.downButton, colors.spot, colors.accentOrange, colors.accentBlue, colors.up, colors.down)
            FieldCard {
                ChartBlock("Scalper equity", note(n, "${compare.size} kept scalpers · after fees.")) {
                    EtLineChart(
                        compare.mapIndexed { i, series ->
                            LineSpec(series.name, palette[i % palette.size], RichTradeMath.thin(series.points, 180))
                        },
                        emptyText = emptyTrades(n),
                        contentLabel = "Scalper equity overlay"
                    )
                }
            }
        } else if (scalp) {
            FieldCard {
                ChartBlock("Scalper equity", "Kept scalpers with closed trades.") {
                    ChartEmptyState(text = emptyTrades(n), height = 120.dp)
                }
            }
        }
    }
}

@Composable
private fun SummaryGrid(s: RichTradeMath.Summary) {
    val cells = listOf(
        cell("Net", s.netUsd, chartMoney(s.netUsd)),
        cell("Win rate", null, s.winRate?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—"),
        cell("Best trade", s.bestUsd, moneyOrDash(s.bestUsd)),
        cell("Worst trade", s.worstUsd, moneyOrDash(s.worstUsd)),
        cell("Win streak", null, if (s.n == 0) "—" else s.longestWinStreak.toString()),
        cell("Loss streak", null, if (s.n == 0) "—" else s.longestLossStreak.toString()),
        cell("Profit factor", null, factorText(s.profitFactor)),
        cell("Expectancy", s.expectancyUsd, moneyOrDash(s.expectancyUsd)),
        cell("Avg win", s.avgWinUsd, moneyOrDash(s.avgWinUsd)),
        cell("Avg loss", s.avgLossUsd, moneyOrDash(s.avgLossUsd)),
        cell("Sharpe", s.sharpe, s.sharpe?.let { String.format(Locale.US, "%.2f", it) } ?: "—"),
        cell("Trades/day", null, s.tradesPerDay?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
        cell("Fees", if (s.feesUsd > 0.0) -s.feesUsd else 0.0, chartMoney(-s.feesUsd)),
        cell("Gross", s.grossUsd, chartMoney(s.grossUsd))
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Summary · ${RichTradeMath.countLabel(s.n)}",
            color = DipTheme.colors.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        cells.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, tone, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = DipTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                        Text(
                            value,
                            color = if (tone == null) DipTheme.colors.textPrimary else chartSignTextColor(tone),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (row.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}

private data class StatCell(val label: String, val tone: Double?, val value: String)

private fun cell(label: String, tone: Double?, value: String) = StatCell(label, tone, value)

@Composable
fun EtLineChart(
    series: List<LineSpec>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    yFormat: (Double) -> String = { chartMoney(it) },
    includeZero: Boolean = true,
    fixedRange: Pair<Double, Double>? = null,
    emptyText: String = "No trades yet",
    contentLabel: String
) {
    val c = DipTheme.colors
    val drawn = series.map { s ->
        s.copy(points = RichTradeMath.thin(s.points.filter { it.value.isFinite() }.sortedBy { it.tMs }, DRAW_CAP))
    }.filter { it.points.isNotEmpty() }
    if (drawn.isEmpty()) {
        ChartEmptyState(modifier, height, emptyText)
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
    val span = t1 - t0
    fun xText(t: Long) = if (span > 18 * 3_600_000L) EtTime.shortDateClockBare(t) else EtTime.clockBare(t)
    val readout = if (scrub < 0) {
        drawn.joinToString("   ") { s -> "${s.name} ${yFormat(s.points.last().value)}" }
    } else {
        val at = t0 + span * scrub / 100
        drawn.joinToString("   ") { s ->
            val nearest = s.points.minBy { abs(it.tMs - at) }
            "${s.name} ${yFormat(nearest.value)}"
        } + " · " + xText(at) + " ET"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(readout, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "$contentLabel. ${drawn.joinToString { it.name }}." }
                .pointerInput(t0, t1) { detectTapGestures { scrub = ((it.x / size.width).coerceIn(0f, 1f) * 100).toInt() } }
                .pointerInput(t0, t1) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = ((change.position.x / size.width).coerceIn(0f, 1f) * 100).toInt()
                    }
                }
        ) {
            val left = 52.dp.toPx()
            val right = 8.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = 22.dp.toPx()
            val plotW = (size.width - left - right).coerceAtLeast(1f)
            val plotH = (size.height - top - bottom).coerceAtLeast(1f)
            fun xPx(t: Long) = left + plotW * ((t - t0).toFloat() / (t1 - t0).toFloat()).coerceIn(0f, 1f)
            fun yPx(v: Double) = top + plotH * (1f - ((v - yLo) / (yHi - yLo)).toFloat())
            val label = axisPaint(c.textSecondary.toArgb(), 10.dp.toPx())
            for (i in 0..3) {
                val v = yHi - (yHi - yLo) * i / 3.0
                val y = yPx(v)
                drawLine(c.border, Offset(left, y), Offset(left + plotW, y), 1.dp.toPx())
                label.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(yFormat(v), left - 4.dp.toPx(), y + 4.dp.toPx(), label)
            }
            if (0.0 in yLo..yHi) {
                drawLine(c.textSecondary.copy(alpha = 0.45f), Offset(left, yPx(0.0)), Offset(left + plotW, yPx(0.0)), 1.dp.toPx())
            }
            label.textAlign = Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(xText(t0), left, size.height - 4.dp.toPx(), label)
            label.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(xText(t1) + " ET", left + plotW, size.height - 4.dp.toPx(), label)
            drawn.forEach { s ->
                val path = Path()
                s.points.forEachIndexed { i, p ->
                    val x = xPx(p.tMs)
                    val y = yPx(p.value)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(s.color, 3.dp.toPx(), Offset(x, y))
                }
                drawPath(
                    path, s.color,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
                    )
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            drawn.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(2.dp)))
                    Text(s.name, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SideBars(
    bins: List<RichTradeMath.Split>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    format: (Double) -> String = { chartMoney(it) },
    emptyText: String = "No trades yet",
    contentLabel: String
) {
    val shown = bins.filter { it.n > 0 || abs(it.total) > 1e-9 }
    if (shown.isEmpty()) {
        ChartEmptyState(modifier, height, emptyText)
        return
    }
    val c = DipTheme.colors
    val values = shown.flatMap { listOf(it.up, it.down) }
    var lo = min(0.0, values.minOrNull() ?: 0.0)
    var hi = max(0.0, values.maxOrNull() ?: 0.0)
    if (hi - lo < 1e-6) {
        lo -= 1.0
        hi += 1.0
    }
    val pad = (hi - lo) * 0.08
    val yLo = lo - pad
    val yHi = hi + pad
    var scrub by remember(shown) { mutableIntStateOf(-1) }
    val pick = shown.getOrNull(scrub)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pick?.let { "${clockish(it.label)} · UP ${format(it.up)} · DOWN ${format(it.down)} · n=${it.n}" }
                ?: "UP green · DOWN red · n=${shown.sumOf { it.n }}",
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "$contentLabel, ${shown.size} groups." }
                .pointerInput(shown) {
                    detectTapGestures { scrub = barAt(it.x, size.width.toFloat(), shown.size) }
                }
                .pointerInput(shown) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = barAt(change.position.x, size.width.toFloat(), shown.size)
                    }
                }
        ) {
            val left = 48.dp.toPx()
            val bottom = 28.dp.toPx()
            val top = 8.dp.toPx()
            val plotW = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            val plotH = (size.height - top - bottom).coerceAtLeast(1f)
            fun yPx(v: Double) = top + plotH * (1f - ((v - yLo) / (yHi - yLo)).toFloat())
            val label = axisPaint(c.textSecondary.toArgb(), 10.dp.toPx())
            val zero = yPx(0.0)
            drawLine(c.textSecondary.copy(alpha = 0.45f), Offset(left, zero), Offset(left + plotW, zero), 1.dp.toPx())
            val slot = plotW / shown.size
            shown.forEachIndexed { i, bin ->
                val mid = left + (i + 0.5f) * slot
                listOf(bin.up to c.upButton, bin.down to c.downButton).forEachIndexed { side, (value, color) ->
                    val y = yPx(value)
                    val topY = min(y, zero)
                    val h = abs(y - zero).coerceAtLeast(if (abs(value) > 1e-9) 2.dp.toPx() else 0f)
                    val w = slot * 0.28f
                    val x = mid + (if (side == 0) -w - 1.dp.toPx() else 1.dp.toPx())
                    drawRoundRect(color, Offset(x, topY), Size(w, h), CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                }
            }
            val step = if (shown.size <= 6) 1 else if (shown.size <= 12) 2 else 4
            label.textAlign = Paint.Align.CENTER
            shown.forEachIndexed { i, bin ->
                if (i % step == 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        bin.label,
                        left + (i + 0.5f) * slot,
                        size.height - 6.dp.toPx(),
                        label
                    )
                }
            }
        }
    }
}

@Composable
fun CalibrationChart(
    points: List<RichTradeMath.CalPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    emptyText: String = "No trades yet"
) {
    if (points.isEmpty()) {
        ChartEmptyState(modifier, height, emptyText)
        return
    }
    val c = DipTheme.colors
    var scrub by remember(points) { mutableIntStateOf(-1) }
    val pick = points.getOrNull(scrub)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pick?.let {
                "${it.label} · predicted ${pct(it.predicted)} · actual ${pct(it.actual)} · n=${it.n}"
            } ?: "Predicted vs actual win rate · n=${points.sumOf { it.n }}",
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary,
            maxLines = 2
        )
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "Calibration, ${points.size} buckets, n=${points.sumOf { it.n }}." }
                .pointerInput(points) {
                    detectTapGestures { scrub = barAt(it.x, size.width.toFloat(), points.size) }
                }
                .pointerInput(points) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scrub = barAt(change.position.x, size.width.toFloat(), points.size)
                    }
                }
        ) {
            val left = 36.dp.toPx()
            val bottom = 28.dp.toPx()
            val top = 8.dp.toPx()
            val plotW = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            val plotH = (size.height - top - bottom).coerceAtLeast(1f)
            val slot = plotW / points.size
            val label = axisPaint(c.textSecondary.toArgb(), 10.dp.toPx())
            drawLine(c.border, Offset(left, top + plotH), Offset(left + plotW, top + plotH), 1.dp.toPx())
            points.forEachIndexed { i, p ->
                val mid = left + (i + 0.5f) * slot
                val w = slot * 0.28f
                fun bar(value: Double, color: Color, leftOf: Boolean) {
                    val h = plotH * value.coerceIn(0.0, 1.0).toFloat()
                    val x = mid + (if (leftOf) -w - 1.dp.toPx() else 1.dp.toPx())
                    drawRoundRect(color, Offset(x, top + plotH - h), Size(w, h), CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                }
                bar(p.predicted, c.spot, true)
                bar(p.actual, c.upButton, false)
                if (points.size <= 6 || i % 2 == 0) {
                    label.textAlign = Paint.Align.CENTER
                    drawContext.canvas.nativeCanvas.drawText(p.label, mid, size.height - 6.dp.toPx(), label)
                }
            }
        }
        Text("Spot = predicted · green = actual win rate", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

private fun note(n: Int, extra: String) = "${RichTradeMath.countLabel(n)} · $extra"

private fun emptyTrades(n: Int) = if (n <= 0) "No trades yet" else "n=$n"

private fun rollingNote(
    n: Int,
    short: List<RichTradeMath.Sample>,
    shortN: Int,
    long: List<RichTradeMath.Sample>,
    longN: Int
): String = when {
    n <= 0 -> "No trades yet"
    short.isEmpty() -> "n=$n · needs $shortN closed trades"
    long.isEmpty() -> "n=$n · $longN-trade line needs $longN"
    else -> "n=$n"
}

private fun calibrationNote(n: Int, points: List<RichTradeMath.CalPoint>): String = when {
    n <= 0 -> "No trades yet"
    points.isEmpty() -> "n=$n · no predicted probability stored"
    else -> "n=${points.sumOf { it.n }}"
}

private fun brierNote(n: Int, points: List<RichTradeMath.Sample>): String = when {
    n <= 0 -> "No trades yet"
    points.isEmpty() -> "n=$n · no predicted probability stored"
    else -> "n=$n · cumulative"
}

private fun confidenceNote(n: Int, bins: List<RichTradeMath.Split>): String =
    if (n <= 0) "No trades yet" else if (bins.isEmpty()) "n=$n · no confidence stored" else "n=$n"

private fun exposureNote(report: RichTradeMath.Report): String = when {
    report.exposure.isEmpty() && report.summary.n == 0 && report.openCount == 0 -> "No trades yet"
    report.exposure.isEmpty() -> "n=${report.summary.n} · no stake stored"
    else -> "n=${report.summary.n} · open ${report.openCount}"
}

private fun moneyOrDash(v: Double?) = v?.let { chartMoney(it) } ?: "—"

private fun factorText(v: Double?) = when {
    v == null -> "—"
    v.isInfinite() -> "∞"
    else -> String.format(Locale.US, "%.2f", v)
}

private fun countText(v: Double) = v.toInt().toString()

private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)

private fun clockish(label: String) = when {
    label.endsWith("AM") || label.endsWith("PM") -> "$label ET"
    label.matches(Regex("""\d{1,2}/\d{1,2}""")) -> "$label ET"
    else -> label
}

private fun barAt(x: Float, width: Float, n: Int): Int {
    if (n <= 1 || width <= 0f) return 0
    return ((x / width).coerceIn(0f, 1f) * n).toInt().coerceIn(0, n - 1)
}

private fun axisPaint(color: Int, sizePx: Float) = Paint().apply {
    isAntiAlias = true
    textSize = sizePx
    this.color = color
}
