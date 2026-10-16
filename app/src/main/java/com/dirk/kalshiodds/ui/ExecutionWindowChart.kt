package com.dirk.kalshiodds.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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

internal fun executionTime(at: Long): String = executionClock.format(Instant.ofEpochMilli(at))
private val executionClock = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US).withZone(ZoneId.of("America/New_York"))
private fun executionPrice(price: Double?) = price?.let { String.format(Locale.US, "%.1f¢", it * 100) } ?: "—"

/** The same 15-minute chart is used by the main paper book and Kashi's individual strategies. */
@Composable
internal fun ExecutionWindowChart(data: ExecutionWindowData, onTrade: (String) -> Unit = {}) {
    val c = DipTheme.colors
    var side by remember(data.ticker) { mutableStateOf("YES") }
    var selected by remember(data.ticker, side) { mutableStateOf<String?>(null) }
    var scrubAt by remember(data.ticker, side) { mutableStateOf<Long?>(null) }
    val markers = remember(data.executions, data.ticker, data.closeMs, data.nowMs, side) { data.markers(side) }
    val quotes = remember(data.points, data.closeMs, data.nowMs, side) { data.quotes(side) }
    val chosen = markers.firstOrNull { it.id == selected }
    val scrubbed = scrubAt?.let { at -> quotes.minByOrNull { abs(it.first - at) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when {
                data.closeMs <= 0 -> "Waiting for a market"
                data.nowMs >= data.closeMs -> "Window complete"
                data.quoteAtMs == null || data.nowMs - data.quoteAtMs !in 0..3_000 -> "Quote stale / unavailable"
                else -> "LIVE · ${((data.closeMs - data.nowMs) / 60_000)}m ${((data.closeMs - data.nowMs) / 1000) % 60}s left"
            }, color = c.textSecondary, style = MaterialTheme.typography.labelMedium)
            Text("15 MIN", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = side == "YES", onClick = { side = "YES" }, label = { Text("UP / YES") })
            FilterChip(selected = side == "NO", onClick = { side = "NO" }, label = { Text("DOWN / NO") })
            Spacer(Modifier.weight(1f))
            Text(executionPrice(quotes.lastOrNull()?.second), color = c.textPrimary,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        }
        if (data.closeMs > 0) {
            val lineColor = if (side == "YES") c.up else c.down
            Canvas(Modifier.fillMaxWidth().height(250.dp)
                .semantics { contentDescription = "$side bid chart for ${data.ticker}. ${markers.count { it.kind == ExecutionWindowData.Kind.BUY }} buy fills and ${markers.count { it.kind == ExecutionWindowData.Kind.SELL }} sell fills. Select fill details below." }
                .pointerInput(markers, data.startMs) {
                    detectTapGestures { tap ->
                        val left = 38.dp.toPx(); val top = 12.dp.toPx()
                        val w = size.width - left - 12.dp.toPx(); val h = size.height - top - 26.dp.toPx()
                        val nearest = markers.minByOrNull { m ->
                            val x = left + (m.atMs - data.startMs) / 900_000f * w
                            val y = top + (1f - m.price.toFloat()) * h
                            (tap.x - x) * (tap.x - x) + (tap.y - y) * (tap.y - y)
                        }
                        val hit = nearest?.takeIf { m ->
                            val dx = tap.x - (left + (m.atMs - data.startMs) / 900_000f * w)
                            val dy = tap.y - (top + (1f - m.price.toFloat()) * h)
                            dx * dx + dy * dy <= 24.dp.toPx() * 24.dp.toPx()
                        }
                        selected = hit?.id
                        hit?.let { onTrade(it.tradeId) }
                        scrubAt = if (hit != null) null else data.startMs + (((tap.x - left) / w).coerceIn(0f, 1f) * 900_000).toLong()
                    }
                }
                .pointerInput(data.startMs) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val left = 38.dp.toPx(); val w = size.width - left - 12.dp.toPx()
                        selected = null
                        scrubAt = data.startMs + (((change.position.x - left) / w).coerceIn(0f, 1f) * 900_000).toLong()
                    }
                }) {
                val left = 38.dp.toPx(); val top = 12.dp.toPx()
                val w = size.width - left - 12.dp.toPx(); val h = size.height - top - 26.dp.toPx()
                fun x(at: Long) = left + (at - data.startMs) / 900_000f * w
                fun y(px: Double) = top + (1 - px.toFloat()) * h
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.textSecondary.toArgb(); textSize = 11.dp.toPx() }
                for (cent in listOf(0, 25, 50, 75, 100)) {
                    drawLine(c.border, Offset(left, y(cent / 100.0)), Offset(left + w, y(cent / 100.0)), 1.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText("$cent¢", 0f, y(cent / 100.0) + 4.dp.toPx(), paint)
                }
                for (minute in listOf(0, 5, 10, 15)) {
                    paint.textAlign = when (minute) { 0 -> Paint.Align.LEFT; 15 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    drawContext.canvas.nativeCanvas.drawText("${minute}m", left + w * minute / 15f, size.height - 2.dp.toPx(), paint)
                }
                drawLine(c.accentOrange.copy(alpha = .65f), Offset(left, y(.50)), Offset(left + w, y(.50)),
                    1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                var previous: Pair<Long, Offset>? = null
                // Keep null observations as breaks: do not draw a path through missing quotes.
                data.points.filter { it.tMs in data.startMs..minOf(data.nowMs, data.closeMs) }.sortedBy { it.tMs }.forEach { p ->
                    val bid = (if (side == "NO") p.downBidCents else p.upBidCents)?.takeIf { it.isFinite() && it in 0f..100f }
                    if (bid == null) previous = null else {
                        val next = Offset(x(p.tMs), y(bid / 100.0))
                        previous?.takeIf { p.tMs - it.first in 0..15_000 }?.let { drawLine(lineColor, it.second, next, 2.dp.toPx()) }
                        drawCircle(lineColor, 1.5.dp.toPx(), next)
                        previous = p.tMs to next
                    }
                }
                if (data.nowMs in data.startMs..data.closeMs) drawLine(c.textSecondary.copy(alpha = .4f), Offset(x(data.nowMs), top), Offset(x(data.nowMs), top + h), 1.dp.toPx())
                markers.forEach { m ->
                    val center = Offset(x(m.atMs), y(m.price)); val r = if (m.id == selected) 8.dp.toPx() else 5.dp.toPx()
                    when (m.kind) {
                        ExecutionWindowData.Kind.SIGNAL -> drawCircle(c.textSecondary, 2.dp.toPx(), center)
                        ExecutionWindowData.Kind.ORDER -> drawCircle(c.up, r, center, style = Stroke(1.5.dp.toPx()))
                        ExecutionWindowData.Kind.BUY -> drawPath(Path().apply { moveTo(center.x, center.y - r); lineTo(center.x - r, center.y + r); lineTo(center.x + r, center.y + r); close() }, c.up)
                        ExecutionWindowData.Kind.SELL -> drawRect(c.accentOrange, center - Offset(r, r), androidx.compose.ui.geometry.Size(2*r, 2*r))
                        else -> drawCircle(c.textSecondary, r, center)
                    }
                    if (m.id == selected) drawCircle(c.textPrimary, r + 3.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                }
                scrubbed?.let { (at, price) ->
                    drawLine(c.textPrimary.copy(alpha = .5f), Offset(x(at), top), Offset(x(at), top + h), 1.dp.toPx())
                    drawCircle(c.textPrimary, 4.dp.toPx(), Offset(x(at), y(price)))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(executionTime(data.startMs), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                Text(executionTime(data.closeMs) + " ET", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
            Text("○ Order  ▲ BUY fill  ■ SELL fill  ● Settlement", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text("Dashed 50¢ reference · tap a fill or drag to inspect bids", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Low ${executionPrice(quotes.minOfOrNull { it.second })} · high ${executionPrice(quotes.maxOfOrNull { it.second })}", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text("${markers.count { it.kind == ExecutionWindowData.Kind.BUY }} buys · ${markers.count { it.kind == ExecutionWindowData.Kind.SELL }} sells", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            if (quotes.isEmpty()) Text("No recorded bids for this side. Missing history is left blank.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            scrubbed?.let { Text("Bid ${executionPrice(it.second)} · ${executionTime(it.first)} ET", color = c.textPrimary) }
            chosen?.let { m ->
                Text("${m.kind.label} · ${m.qty.takeIf { it > 0 }?.let { "$it ct · " }.orEmpty()}${executionPrice(m.price)} · ${executionTime(m.recordedAtMs)} ET" +
                    (m.feeUsd?.let { String.format(Locale.US, " · fee $%.2f", it) } ?: ""), color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }
            if (markers.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(markers, key = { it.id }) { m ->
                    FilterChip(selected = selected == m.id, onClick = { selected = m.id; scrubAt = null; onTrade(m.tradeId) },
                        label = { Text("${m.kind.label} ${executionPrice(m.price)} · ${executionTime(m.recordedAtMs)}") })
                }
            }
        }
        data.error?.let { Text(it, color = c.down, style = MaterialTheme.typography.bodySmall) }
    }
}
