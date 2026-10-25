package com.dirk.kalshiodds.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.Typeface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.dirk.kalshiodds.ui.charts.ChartStroke
import com.dirk.kalshiodds.ui.charts.CalibrationPlot
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.ChartShapes
import com.dirk.kalshiodds.ui.charts.CumulativeDepthChart
import com.dirk.kalshiodds.ui.charts.DistanceToStrikeChart
import com.dirk.kalshiodds.ui.charts.FeeWaterfall
import com.dirk.kalshiodds.ui.charts.KnobTimeline
import com.dirk.kalshiodds.ui.charts.LineSeries
import com.dirk.kalshiodds.ui.charts.MultiLineChart
import com.dirk.kalshiodds.ui.charts.WindowTimeline
import com.dirk.kalshiodds.ui.charts.chartClock
import com.dirk.kalshiodds.ui.charts.chartMoney
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Last-price callouts and marker sizes for the window bid chart.
 * The series stroke stays [ChartStroke.SERIES_DP]. Near 0¢ and 100¢ the label
 * moves inside the plot so it is not clipped against the axis.
 */
internal object ScalpChartReadability {
    const val HALO_EXTRA_DP = 2.2f
    const val MARKER_DP = 7f
    const val SIGNAL_DP = 3.5f
    const val END_DOT_DP = 5.5f
    const val LABEL_TEXT_DP = 13f
    /** Cents at or above this draw the callout under the point. */
    const val TOP_BAND = 92.0

    fun labelAbove(cents: Double): Boolean = cents < TOP_BAND

    /** Negative is above the point. Positive is below it. */
    fun labelDyDp(cents: Double): Float = if (labelAbove(cents)) -14f else 16f
}

/** Bare "1:52:07 PM"; callers add the "ET" label. */
internal fun windowTime(at: Long): String = com.dirk.kalshiodds.domain.EtTime.clockSecBare(at)
internal fun markerName(kind: ScalpWindowChartData.Kind): String = when (kind) {
    ScalpWindowChartData.Kind.SIGNAL -> "Signal observed"
    ScalpWindowChartData.Kind.ORDER -> "BUY limit posted"
    ScalpWindowChartData.Kind.BUY -> "BUY fill"
    ScalpWindowChartData.Kind.SELL -> "SELL fill"
    ScalpWindowChartData.Kind.SETTLEMENT -> "Settlement"
    ScalpWindowChartData.Kind.LEGACY_EXIT -> "Historical average exit"
}

@Composable
internal fun ScalpWindowChart(d: ScalpAlgoDetail, onWindow: (String) -> Unit = {}, onLive: () -> Unit = {}, title: String = "Live strategy window · 15 minutes", extras: LiveWindowExtras = LiveWindowExtras(), onTrade: (String) -> Unit = {}) {
    val c = DipTheme.colors
    val data = d.chart
    var selected by remember(data.ticker) { mutableStateOf<String?>(null) }
    val markers = remember(data.trades, data.ticker) { data.allMarkers() }
    val selectedMarker = markers.firstOrNull { it.id == selected }
    val hasFair = data.points.any { it.aiYesPp != null }
    ArenaCard(accent = c.accentBlue) {
        Text(title, color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val status = when {
                data.ticker.isEmpty() -> "Waiting for a market"
                data.nowMs >= data.closeMs -> "Window complete"
                data.bookAtMs == null || data.nowMs - data.bookAtMs !in 0..3_000 -> "STALE BOOK"
                else -> "LIVE · " + ScalpTabCopy.timeLeft(((data.closeMs - data.nowMs) / 1000).coerceAtLeast(0))
            }
            if (status.startsWith("LIVE")) ArenaBadge(status, ArenaTone.LIVE)
            else Text(status, color = c.textSecondary, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onLive, enabled = !d.followLive) { Text(if (d.followLive) "Following live" else "Follow live") }
        }
        if (d.chartMarkets.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.chartMarkets, key = { it.ticker }) { market ->
                FilterChip(selected = market.ticker == data.ticker, onClick = { onWindow(market.ticker) }, label = { Text(market.label) })
            }
        }
        Text(data.ticker.ifEmpty { "Chart and trade markers appear here when a window is available." }, color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("UP ${ScalpTabCopy.cents(data.bid("YES"))}", color = c.up, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("50¢", color = c.accentOrange, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text("DOWN ${ScalpTabCopy.cents(data.bid("NO"))}", color = c.down, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (data.closeMs > 0) {
            val plotWell = ArenaChrome.well()
            Canvas(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(10.dp)).background(plotWell)
                .semantics { contentDescription = "UP and DOWN bid chart for ${data.ticker}. ${markers.count { it.kind == ScalpWindowChartData.Kind.BUY }} buys and ${markers.count { it.kind == ScalpWindowChartData.Kind.SELL }} sells. Fill details are below." }
                .pointerInput(markers, data.startMs, data.closeMs) {
                    detectTapGestures { tap ->
                        val left = 36.dp.toPx(); val bottom = 24.dp.toPx()
                        val w = size.width - left - 8.dp.toPx(); val h = size.height - bottom - 10.dp.toPx()
                        val nearest = markers.minByOrNull { m ->
                            val x = left + ((m.atMs - data.startMs) / 900_000f) * w
                            val y = 10.dp.toPx() + (1f - m.price.toFloat()) * h
                            (tap.x - x) * (tap.x - x) + (tap.y - y) * (tap.y - y)
                        }
                        selected = nearest?.id
                        nearest?.let { onTrade(it.tradeId) }
                    }
                }) {
                val left = 36.dp.toPx(); val top = 10.dp.toPx()
                val w = size.width - left - 8.dp.toPx(); val h = size.height - top - 24.dp.toPx()
                fun x(at: Long) = left + ((at - data.startMs) / 900_000f) * w
                fun y(price: Double) = top + (1 - price.toFloat()) * h
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.textSecondary.toArgb(); textSize = 11.dp.toPx() }
                val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = c.textPrimary.toArgb()
                    textSize = 12.dp.toPx()
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                for (cent in listOf(0, 25, 50, 75, 100)) {
                    val yy = y(cent / 100.0)
                    val edge = cent == 0 || cent == 100
                    drawLine(if (edge) c.textPrimary.copy(alpha = 0.45f) else c.border, Offset(left, yy), Offset(left + w, yy), if (edge) 1.4.dp.toPx() else 1.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText("$cent¢", 0f, yy + 4.dp.toPx(), if (edge) edgePaint else paint)
                }
                for (minute in listOf(0, 5, 10, 15)) {
                    val xx = left + w * minute / 15f
                    paint.textAlign = when (minute) { 0 -> Paint.Align.LEFT; 15 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    drawContext.canvas.nativeCanvas.drawText("${minute}m", xx, size.height - 2.dp.toPx(), paint)
                }
                drawLine(c.accentOrange.copy(alpha = .65f), Offset(left, y(.50)), Offset(left + w, y(.50)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                val labelBg = plotWell
                val halo = Color.Black.copy(alpha = 0.88f)
                val endLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = ScalpChartReadability.LABEL_TEXT_DP.dp.toPx()
                    textAlign = Paint.Align.RIGHT
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val stroke = ChartStroke.SERIES_DP.dp.toPx()
                val haloStroke = stroke + ScalpChartReadability.HALO_EXTRA_DP.dp.toPx()
                val placedLabels = ArrayList<Float>()
                fun drawSeries(color: Color, labelTip: Boolean, value: (com.dirk.kalshiodds.chart.BidPoint) -> Float?) {
                    var previous: Pair<Long, Offset>? = null
                    var last: Offset? = null
                    var lastBid: Float? = null
                    data.points.filter { it.tMs in data.startMs..minOf(data.nowMs, data.closeMs) }.forEach { point ->
                        val bid = value(point)
                        if (bid == null || !bid.isFinite() || bid !in 0f..100f) previous = null
                        else {
                            val next = Offset(x(point.tMs), y(bid / 100.0))
                            previous?.takeIf { point.tMs - it.first in 0..15_000 }?.let { prev ->
                                drawLine(halo, prev.second, next, haloStroke)
                                drawLine(color, prev.second, next, stroke)
                            }
                            previous = point.tMs to next
                            last = next
                            lastBid = bid
                        }
                    }
                    val tip = last
                    val cents = lastBid
                    if (tip != null && cents != null) {
                        drawCircle(halo, (ScalpChartReadability.END_DOT_DP + 1.6f).dp.toPx(), tip)
                        drawCircle(color, ScalpChartReadability.END_DOT_DP.dp.toPx(), tip)
                        drawCircle(c.textPrimary, ScalpChartReadability.END_DOT_DP.dp.toPx(), tip, style = Stroke(1.4.dp.toPx()))
                        if (labelTip) {
                            val label = ChartShapes.centLabel(cents.toDouble())
                            endLabel.color = color.toArgb()
                            val tw = endLabel.measureText(label)
                            val th = endLabel.textSize
                            var yText = tip.y + ScalpChartReadability.labelDyDp(cents.toDouble()).dp.toPx()
                            val gap = th + 4.dp.toPx()
                            for (prevY in placedLabels) {
                                if (abs(yText - prevY) < gap) {
                                    yText = if (ScalpChartReadability.labelAbove(cents.toDouble())) prevY - gap else prevY + gap
                                }
                            }
                            val right = (tip.x - 4.dp.toPx()).coerceIn(left + tw + 8.dp.toPx(), left + w - 2.dp.toPx())
                            yText = yText.coerceIn(top + th, top + h - 2.dp.toPx())
                            placedLabels += yText
                            val padX = 5.dp.toPx()
                            val padY = 3.dp.toPx()
                            drawRoundRect(
                                color = labelBg,
                                topLeft = Offset(right - tw - padX, yText - th + padY),
                                size = Size(tw + padX * 2, th + padY),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                            drawContext.canvas.nativeCanvas.drawText(label, right, yText, endLabel)
                        }
                    }
                }
                drawSeries(c.upButton, true) { it.upBidCents }
                drawSeries(c.downButton, true) { it.downBidCents }
                if (hasFair) drawSeries(c.accentBlue, false) { it.aiYesPp }
                if (data.nowMs in data.startMs..data.closeMs) drawLine(c.textSecondary.copy(alpha = .4f), Offset(x(data.nowMs), top), Offset(x(data.nowMs), top + h), 1.dp.toPx())
                markers.forEach { m ->
                    val center = Offset(x(m.atMs), y(m.price))
                    val r = if (m.id == selected) 9.dp.toPx() else ScalpChartReadability.MARKER_DP.dp.toPx()
                    val sideColor = if (m.side == "NO") c.downButton else c.upButton
                    val ink = c.textPrimary
                    when (m.kind) {
                        ScalpWindowChartData.Kind.SIGNAL -> {
                            val sr = ScalpChartReadability.SIGNAL_DP.dp.toPx()
                            drawCircle(sideColor, sr, center)
                            drawCircle(ink, sr, center, style = Stroke(1.25.dp.toPx()))
                        }
                        ScalpWindowChartData.Kind.ORDER -> drawCircle(sideColor, r, center, style = Stroke(2.dp.toPx()))
                        ScalpWindowChartData.Kind.BUY -> {
                            val path = Path().apply {
                                moveTo(center.x, center.y - r)
                                lineTo(center.x - r, center.y + r)
                                lineTo(center.x + r, center.y + r)
                                close()
                            }
                            drawPath(path, sideColor)
                            drawPath(path, ink, style = Stroke(1.35.dp.toPx()))
                        }
                        ScalpWindowChartData.Kind.SELL -> {
                            drawRect(sideColor, center - Offset(r, r), Size(2 * r, 2 * r))
                            drawRect(ink, center - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(1.35.dp.toPx()))
                        }
                        else -> {
                            drawCircle(sideColor, r, center)
                            drawCircle(ink, r, center, style = Stroke(1.25.dp.toPx()))
                        }
                    }
                    if (m.id == selected) drawCircle(c.textPrimary, r + 3.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.dirk.kalshiodds.domain.EtTime.clockSec(data.startMs), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(com.dirk.kalshiodds.domain.EtTime.clockSec(data.closeMs), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            ChartLegend(
                buildList {
                    add(c.upButton to "UP bid")
                    add(c.downButton to "DOWN bid")
                    add(c.accentOrange to "50¢ ceiling")
                    if (hasFair) add(c.accentBlue to "AI fair")
                },
                emphasis = true
            )
            Text("○ Buy order   ▲ Buy fill   ■ Sell fill   · Signal", color = c.textPrimary, style = MaterialTheme.typography.labelMedium)
            Text("Green UP · red DOWN · both sides. Dashed 50¢ buy ceiling · tap a marker for price and time", color = c.textPrimary, style = MaterialTheme.typography.labelSmall)
            data.quietSideLine()?.let { Text(it, color = c.textSecondary, style = MaterialTheme.typography.bodySmall) }
            run {
                val upAny = data.points.any { it.upBidCents != null }
                val downAny = data.points.any { it.downBidCents != null }
                val blank = when {
                    !upAny && !downAny -> "No recorded bids yet. Missing history is left blank."
                    !upAny || !downAny -> "A side with no recorded bids is left blank."
                    else -> null
                }
                blank?.let { Text(it, color = c.textSecondary, style = MaterialTheme.typography.bodySmall) }
            }
            selectedMarker?.let { m ->
                val sideName = if (m.side.equals("NO", true)) "DOWN" else "UP"
                Text("$sideName · ${markerName(m.kind)} · ${m.qty.takeIf { it > 0 }?.let { "$it ct · " }.orEmpty()}${ScalpTabCopy.cents(m.price)} · ${com.dirk.kalshiodds.domain.EtTime.clockSec(m.recordedAtMs)}", color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }
            data.error?.let { Text(it, color = c.down, style = MaterialTheme.typography.bodySmall) }
            data.sidePauseLine()?.let {
                Text(
                    it,
                    color = c.down,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("window_side_pause")
                )
            }
            WindowPaperPnl(data)
            if (markers.isNotEmpty()) {
                WindowTimeline(
                    data.startMs,
                    data.closeMs,
                    markers.map { m ->
                        ChartShapes.WindowEvent(
                            m.atMs,
                            when (m.kind) {
                                ScalpWindowChartData.Kind.BUY -> "entry"
                                ScalpWindowChartData.Kind.SELL, ScalpWindowChartData.Kind.LEGACY_EXIT -> "exit"
                                ScalpWindowChartData.Kind.SETTLEMENT -> "settle"
                                ScalpWindowChartData.Kind.ORDER -> "order"
                                ScalpWindowChartData.Kind.SIGNAL -> "signal"
                            },
                            !m.side.equals("NO", true),
                            m.price,
                            markerName(m.kind)
                        )
                    }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Window realized", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text(ScalpTabCopy.money(data.windowNet()), color = c.textPrimary, fontWeight = FontWeight.Bold) }
            Column { Text("Open P&L · both sides", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text(ScalpTabCopy.money(data.openNet()), color = c.textPrimary, fontWeight = FontWeight.Bold) }
            Column { Text("Round trips", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text("${data.windowRoundTrips()}", color = c.textPrimary, fontWeight = FontWeight.Bold) }
        }
        Text(
            "Primary scalper paper on this market. Shadows and manual ladder fills are not included.",
            color = c.textSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        CumulativeDepthChart(ChartGallery.cumulativeDepth(extras.bookYes, extras.bookNo))
        run {
            val report = rememberCalibrationReport(extras.calibration)
            CalibrationPlot(
                ChartGallery.calibration(report.pairs()),
                sampleCount = report.count,
                brierLine = report.brierLabel()
            )
        }
        FeeWaterfall(ChartGallery.feeLegFromTrades("Window", data.trades), extras.dayFees)
        KnobTimeline(extras.knobs)
        DistanceToStrikeChart(ChartGallery.distanceToStrike(data.points, extras.strikeUsd))
    }
}

/** The slot under the odds chart. A quiet window still draws both sides at $0. */
@Composable
private fun WindowPaperPnl(data: ScalpWindowChartData) {
    val c = DipTheme.colors
    val lines = remember(data.trades, data.ticker, data.startMs, data.closeMs, data.nowMs, data.points) {
        ChartGallery.windowSidePnl(
            data.trades, data.ticker, data.startMs, data.closeMs, data.nowMs,
            data.bid("YES"), data.bid("NO")
        )
    }
    Column(
        Modifier.fillMaxWidth().testTag("chart_window_pnl"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Window paper P&L", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text(
            "UP green and DOWN red, after fees, on this market. Starts at $0. A flat line means no round trip has closed. The right edge includes open P&L.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary
        )
        MultiLineChart(
            lines.map { s ->
                LineSeries(
                    name = s.name,
                    color = if (s.name == "UP") c.upButton else c.downButton,
                    points = s.points
                )
            },
            yFormat = { chartMoney(it) },
            xFormat = { chartClock(it) },
            includeZero = true,
            height = 200.dp,
            contentLabel = "Window paper P&L for UP and DOWN"
        )
    }
}
