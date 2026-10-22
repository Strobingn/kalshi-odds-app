package com.dirk.kalshiodds.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    SwarmCard(title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val status = when {
                data.ticker.isEmpty() -> "Waiting for a market"
                data.nowMs >= data.closeMs -> "Window complete"
                data.bookAtMs == null || data.nowMs - data.bookAtMs !in 0..3_000 -> "STALE BOOK"
                else -> "LIVE · " + ScalpTabCopy.timeLeft(((data.closeMs - data.nowMs) / 1000).coerceAtLeast(0))
            }
            Text(status, color = if (status.startsWith("LIVE")) c.up else c.textSecondary, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onLive, enabled = !d.followLive) { Text(if (d.followLive) "Following live" else "Follow live") }
        }
        if (d.chartMarkets.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.chartMarkets, key = { it.ticker }) { market ->
                FilterChip(selected = market.ticker == data.ticker, onClick = { onWindow(market.ticker) }, label = { Text(market.label) })
            }
        }
        Text(data.ticker.ifEmpty { "Chart and trade markers appear here when a window is available." }, color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("UP ${ScalpTabCopy.cents(data.bid("YES"))}", color = c.up, fontWeight = FontWeight.Bold)
            Text("50¢", color = c.accentOrange, style = MaterialTheme.typography.labelMedium)
            Text("DOWN ${ScalpTabCopy.cents(data.bid("NO"))}", color = c.down, fontWeight = FontWeight.Bold)
        }
        if (data.closeMs > 0) {
            Canvas(Modifier.fillMaxWidth().height(230.dp)
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
                for (cent in listOf(0, 25, 50, 75, 100)) {
                    val yy = y(cent / 100.0)
                    drawLine(c.border, Offset(left, yy), Offset(left + w, yy), 1.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText("$cent¢", 0f, yy + 4.dp.toPx(), paint)
                }
                for (minute in listOf(0, 5, 10, 15)) {
                    val xx = left + w * minute / 15f
                    paint.textAlign = when (minute) { 0 -> Paint.Align.LEFT; 15 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    drawContext.canvas.nativeCanvas.drawText("${minute}m", xx, size.height - 2.dp.toPx(), paint)
                }
                drawLine(c.accentOrange.copy(alpha = .65f), Offset(left, y(.50)), Offset(left + w, y(.50)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                val endLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.dp.toPx(); textAlign = Paint.Align.RIGHT }
                fun drawBids(up: Boolean) {
                    val color = if (up) c.upButton else c.downButton
                    var previous: Pair<Long, Offset>? = null
                    var last: Offset? = null
                    var lastBid: Float? = null
                    data.points.filter { it.tMs in data.startMs..minOf(data.nowMs, data.closeMs) }.forEach { point ->
                        val bid = if (up) point.upBidCents else point.downBidCents
                        if (bid == null || !bid.isFinite() || bid !in 0f..100f) previous = null
                        else {
                            val next = Offset(x(point.tMs), y(bid / 100.0))
                            previous?.takeIf { point.tMs - it.first in 0..15_000 }?.let { drawLine(color, it.second, next, 2.5.dp.toPx()) }
                            previous = point.tMs to next
                            last = next
                            lastBid = bid
                        }
                    }
                    val tip = last
                    val cents = lastBid
                    if (tip != null && cents != null) {
                        drawCircle(color, 3.5.dp.toPx(), tip)
                        endLabel.color = color.toArgb()
                        drawContext.canvas.nativeCanvas.drawText(ChartShapes.centLabel(cents.toDouble()), tip.x - 6.dp.toPx(), tip.y - 6.dp.toPx(), endLabel)
                    }
                }
                drawBids(true)
                drawBids(false)
                if (data.nowMs in data.startMs..data.closeMs) drawLine(c.textSecondary.copy(alpha = .4f), Offset(x(data.nowMs), top), Offset(x(data.nowMs), top + h), 1.dp.toPx())
                markers.forEach { m ->
                    val center = Offset(x(m.atMs), y(m.price)); val r = if (m.id == selected) 8.dp.toPx() else 5.dp.toPx()
                    val sideColor = if (m.side == "NO") c.downButton else c.upButton
                    when (m.kind) {
                        ScalpWindowChartData.Kind.SIGNAL -> drawCircle(sideColor, 2.dp.toPx(), center)
                        ScalpWindowChartData.Kind.ORDER -> drawCircle(sideColor, r, center, style = Stroke(1.5.dp.toPx()))
                        ScalpWindowChartData.Kind.BUY -> drawPath(Path().apply { moveTo(center.x, center.y - r); lineTo(center.x - r, center.y + r); lineTo(center.x + r, center.y + r); close() }, sideColor)
                        ScalpWindowChartData.Kind.SELL -> drawRect(sideColor, center - Offset(r, r), androidx.compose.ui.geometry.Size(2*r, 2*r))
                        else -> drawCircle(sideColor, r, center)
                    }
                    if (m.id == selected) drawCircle(c.textPrimary, r + 3.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.dirk.kalshiodds.domain.EtTime.clockSec(data.startMs), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(com.dirk.kalshiodds.domain.EtTime.clockSec(data.closeMs), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text("○ Buy order   ▲ Buy fill   ■ Sell fill   · Signal", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text("Green UP · red DOWN · both sides. Dashed 50¢ buy ceiling · tap a marker for price and time", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
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
