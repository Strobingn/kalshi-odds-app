package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.decision.ScalpRule

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
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal fun windowTime(at: Long): String = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
    .withZone(ZoneId.of("America/New_York")).format(Instant.ofEpochMilli(at))
internal fun markerName(kind: ScalpWindowChartData.Kind): String = when (kind) {
    ScalpWindowChartData.Kind.SIGNAL -> "Signal observed"
    ScalpWindowChartData.Kind.ORDER -> "BUY limit posted"
    ScalpWindowChartData.Kind.BUY -> "BUY fill"
    ScalpWindowChartData.Kind.SELL -> "SELL fill"
    ScalpWindowChartData.Kind.SETTLEMENT -> "Settlement"
    ScalpWindowChartData.Kind.LEGACY_EXIT -> "Historical average exit"
}

@Composable
internal fun ScalpWindowChart(d: ScalpAlgoDetail, onWindow: (String) -> Unit = {}, onLive: () -> Unit = {}, title: String = "Live strategy window · 15 minutes", onTrade: (String) -> Unit = {}) {
    val c = DipTheme.colors
    val data = d.chart
    var side by remember(d.model) { mutableStateOf("YES") }
    var selected by remember(data.ticker, side) { mutableStateOf<String?>(null) }
    val markers = remember(data.trades, data.ticker, side) { data.markers(side) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = side == "YES", onClick = { side = "YES" }, label = { Text("UP / YES") })
            FilterChip(selected = side == "NO", onClick = { side = "NO" }, label = { Text("DOWN / NO") })
            Spacer(Modifier.weight(1f))
            Text("Bid ${ScalpTabCopy.cents(data.bid(side))}", color = c.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        }
        if (data.closeMs > 0) {
            val lineColor = if (side == "YES") c.up else c.down
            Canvas(Modifier.fillMaxWidth().height(230.dp)
                .semantics { contentDescription = "$side bid chart for ${data.ticker}. ${markers.count { it.kind == ScalpWindowChartData.Kind.BUY }} buys and ${markers.count { it.kind == ScalpWindowChartData.Kind.SELL }} sells. Fill details are below." }
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
                drawLine(c.accentOrange.copy(alpha = .65f), Offset(left, y(ScalpRule.ENTRY_HI)), Offset(left + w, y(ScalpRule.ENTRY_HI)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                var previous: Pair<Long, Offset>? = null
                data.points.filter { it.tMs in data.startMs..minOf(data.nowMs, data.closeMs) }.forEach { point ->
                    val bid = if (side == "YES") point.upBidCents else point.downBidCents
                    if (bid == null || !bid.isFinite() || bid !in 0f..100f) previous = null
                    else {
                        val next = Offset(x(point.tMs), y(bid / 100.0))
                        previous?.takeIf { point.tMs - it.first in 0..15_000 }?.let { drawLine(lineColor, it.second, next, 2.dp.toPx()) }
                        drawCircle(lineColor, 1.5.dp.toPx(), next)
                        previous = point.tMs to next
                    }
                }
                if (data.nowMs in data.startMs..data.closeMs) drawLine(c.textSecondary.copy(alpha = .4f), Offset(x(data.nowMs), top), Offset(x(data.nowMs), top + h), 1.dp.toPx())
                markers.forEach { m ->
                    val center = Offset(x(m.atMs), y(m.price)); val r = if (m.id == selected) 8.dp.toPx() else 5.dp.toPx()
                    when (m.kind) {
                        ScalpWindowChartData.Kind.SIGNAL -> drawCircle(c.textSecondary, 2.dp.toPx(), center)
                        ScalpWindowChartData.Kind.ORDER -> drawCircle(c.up, r, center, style = Stroke(1.5.dp.toPx()))
                        ScalpWindowChartData.Kind.BUY -> drawPath(Path().apply { moveTo(center.x, center.y - r); lineTo(center.x - r, center.y + r); lineTo(center.x + r, center.y + r); close() }, c.up)
                        ScalpWindowChartData.Kind.SELL -> drawRect(c.accentOrange, center - Offset(r, r), androidx.compose.ui.geometry.Size(2*r, 2*r))
                        else -> drawCircle(c.textSecondary, r, center)
                    }
                    if (m.id == selected) drawCircle(c.textPrimary, r + 3.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(windowTime(data.startMs), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(windowTime(data.closeMs) + " ET", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text("○ Buy order   ▲ Buy fill   ■ Sell fill   · Signal", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text("Dashed 55¢ buy ceiling · tap a marker for price and time", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            if (data.points.none { (if (side == "YES") it.upBidCents else it.downBidCents) != null }) Text("No recorded bids for this side yet. Missing history is left blank.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            selectedMarker?.let { m -> Text("${markerName(m.kind)} · ${m.qty.takeIf { it > 0 }?.let { "$it ct · " }.orEmpty()}${ScalpTabCopy.cents(m.price)} · ${windowTime(m.recordedAtMs)} ET", color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall) }
            data.error?.let { Text(it, color = c.down, style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Window realized", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text(ScalpTabCopy.money(data.windowNet()), color = c.textPrimary, fontWeight = FontWeight.Bold) }
            Column { Text("Open P&L · both sides", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text(ScalpTabCopy.money(data.openNet()), color = c.textPrimary, fontWeight = FontWeight.Bold) }
            Column { Text("Round trips", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text("${data.trades.count { it.state == com.dirk.kalshiodds.decision.ScalpState.CLOSED }}", color = c.textPrimary, fontWeight = FontWeight.Bold) }
        }
    }
}
