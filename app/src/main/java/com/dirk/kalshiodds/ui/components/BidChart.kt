package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.chart.ChartDownsampler
import com.dirk.kalshiodds.chart.SpotAxis
import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.checklistLabelColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Two-line UP/DOWN bid chart. Canvas only — no charting library.
 */
@Composable
fun BidChart(
    points: List<BidPoint>,
    modifier: Modifier = Modifier,
    heightDp: Int = 96,
    scrub: Boolean = false,
    windowStartMs: Long? = null,
    windowEndMs: Long? = null,
    strikeLabel: String? = null,
    spotUsd: Double? = null,
    strikeUsd: Double? = null,
    spotHeightDp: Int = if (scrub) 168 else 72,
    showSpotPanel: Boolean = true,
    liveUpLabel: String? = null,
    liveDownLabel: String? = null
) {
    val colors = DipTheme.colors
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val axisColor = Contrast.readable(MaterialTheme.colorScheme.onSurfaceVariant, bg, minRatio = Contrast.AA_LARGE)
    val downsampled = remember(points, heightDp) {
        ChartDownsampler.downsample(points, if (scrub) ChartDownsampler.DETAIL_POINTS else ChartDownsampler.CARD_POINTS)
    }
    if (downsampled.size < 2) {
        val loneSpot = spotUsd?.takeIf { it.isFinite() && it > 0.0 }
        val loneRange = SpotAxis.range(listOfNotNull(loneSpot), strikeUsd)
        Column(modifier.fillMaxWidth()) {
            if (showSpotPanel && loneRange != null) {
                val t = downsampled.lastOrNull()?.tMs ?: (windowEndMs ?: System.currentTimeMillis())
                SpotPathCanvas(
                    series = listOfNotNull(loneSpot?.let { t to it }),
                    strikeUsd = strikeUsd,
                    range = loneRange,
                    windowStartMs = windowStartMs ?: (t - 900_000L),
                    windowEndMs = windowEndMs ?: t,
                    heightDp = spotHeightDp,
                    lastSpot = loneSpot,
                    axisColor = axisColor
                )
            }
            Box(
                Modifier.height(heightDp.dp).fillMaxWidth().background(bg, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    if (downsampled.isEmpty()) "Chart warming up" else "Need 2 prints",
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
        return
    }
    var scrubIdx by remember(downsampled.size) { mutableStateOf<Int?>(null) }
    val pick = scrubIdx?.let { downsampled.getOrNull(it) } ?: downsampled.last()
    val spotSeries = remember(downsampled, spotUsd) {
        downsampled.mapNotNull { p -> p.spotUsd?.takeIf { it.isFinite() && it > 0.0 }?.let { p.tMs to it } } +
            listOfNotNull(spotUsd?.takeIf { it.isFinite() && it > 0.0 }?.let { downsampled.last().tMs to it })
    }
    val spotRange = remember(spotSeries, strikeUsd) {
        SpotAxis.range(spotSeries.map { it.second }, strikeUsd)
    }
    Column(modifier.fillMaxWidth()) {
        if (showSpotPanel && spotRange != null && (strikeUsd != null || spotSeries.isNotEmpty())) {
            SpotPathCanvas(
                series = spotSeries,
                strikeUsd = strikeUsd,
                range = spotRange,
                windowStartMs = windowStartMs ?: downsampled.first().tMs,
                windowEndMs = windowEndMs ?: downsampled.last().tMs,
                heightDp = spotHeightDp,
                lastSpot = spotUsd ?: spotSeries.lastOrNull()?.second,
                axisColor = axisColor
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .then(
                    if (scrub) {
                        Modifier
                            .pointerInput(downsampled) {
                                detectTapGestures { off ->
                                    scrubIdx = indexAt(off.x, size.width.toFloat(), downsampled.size)
                                }
                            }
                            .pointerInput(downsampled) {
                                detectDragGestures { change, _ ->
                                    scrubIdx = indexAt(change.position.x, size.width.toFloat(), downsampled.size)
                                }
                            }
                    } else Modifier
                )
        ) {
            val ys = downsampled.flatMap { listOfNotNull(it.upBidCents, it.downBidCents) }
            val minY = (ys.minOrNull() ?: 0f) - 1f
            val maxY = (ys.maxOrNull() ?: 100f) + 1f
            val spanY = (maxY - minY).coerceAtLeast(2f)
            val t0 = windowStartMs ?: downsampled.first().tMs
            val t1 = windowEndMs ?: downsampled.last().tMs
            val spanT = (t1 - t0).coerceAtLeast(1L)
            fun xOf(t: Long) = size.width * ((t - t0).toFloat() / spanT.toFloat()).coerceIn(0f, 1f)
            fun yOf(v: Float) = size.height * (1f - ((v - minY) / spanY).coerceIn(0f, 1f))
            val grid = axisColor.copy(alpha = 0.22f)
            for (i in 1..3) {
                val y = size.height * i / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            fun line(sel: (BidPoint) -> Float?, color: androidx.compose.ui.graphics.Color) {
                val path = Path()
                var started = false
                downsampled.forEach { p ->
                    val v = sel(p) ?: return@forEach
                    val x = xOf(p.tMs)
                    val y = yOf(v)
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                if (started) {
                    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            line({ it.upBidCents }, colors.up)
            line({ it.downBidCents }, colors.down)
            if (scrub && scrubIdx != null) {
                val x = xOf(pick.tMs)
                drawLine(axisColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                pick.upBidCents?.let { drawCircle(colors.up, 4.dp.toPx(), Offset(x, yOf(it))) }
                pick.downBidCents?.let { drawCircle(colors.down, 4.dp.toPx(), Offset(x, yOf(it))) }
            } else {
                pick.upBidCents?.let { drawCircle(colors.up, 3.dp.toPx(), Offset(xOf(pick.tMs), yOf(it))) }
                pick.downBidCents?.let { drawCircle(colors.down, 3.dp.toPx(), Offset(xOf(pick.tMs), yOf(it))) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (scrubIdx == null && liveUpLabel != null) liveUpLabel else "UP bid ${fmtCents(pick.upBidCents)}",
                color = colors.up,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(fmtTime(pick.tMs), color = labelColor, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (scrubIdx == null && liveDownLabel != null) liveDownLabel else "DOWN bid ${fmtCents(pick.downBidCents)}",
                color = colors.down,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        strikeLabel?.let {
            Text(it, color = labelColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SpotPathCanvas(
    series: List<Pair<Long, Double>>,
    strikeUsd: Double?,
    range: Pair<Double, Double>,
    windowStartMs: Long,
    windowEndMs: Long,
    heightDp: Int,
    lastSpot: Double?,
    axisColor: androidx.compose.ui.graphics.Color
) {
    val colors = DipTheme.colors
    val (minY, maxY) = range
    val t0 = windowStartMs
    val t1 = windowEndMs
    val spanT = (t1 - t0).coerceAtLeast(1L)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .padding(bottom = 4.dp)
    ) {
        fun xOf(t: Long) = size.width * ((t - t0).toFloat() / spanT.toFloat()).coerceIn(0f, 1f)
        fun yOf(v: Double) = size.height * SpotAxis.yFraction(v, minY, maxY)
        val grid = axisColor.copy(alpha = 0.22f)
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (strikeUsd != null && strikeUsd > 0.0) {
            val yStrike = yOf(strikeUsd)
            drawLine(
                axisColor.copy(alpha = 0.7f),
                Offset(0f, yStrike),
                Offset(size.width, yStrike),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
            )
        }
        if (series.size >= 2) {
            val path = Path()
            series.forEachIndexed { i, (t, px) ->
                val x = xOf(t)
                val y = yOf(px)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors.accentOrange, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        val endPx = lastSpot ?: series.lastOrNull()?.second
        val endT = series.lastOrNull()?.first ?: t1
        if (endPx != null) {
            drawCircle(colors.accentOrange, 5.dp.toPx(), Offset(xOf(endT), yOf(endPx)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val now = lastSpot ?: series.lastOrNull()?.second
        Text(
            now?.let { String.format(Locale.US, "Now $%,.2f", it) } ?: "Spot",
            color = colors.accentOrange,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        strikeUsd?.let {
            Text(
                String.format(Locale.US, "TARGET $%,.2f", it),
                color = axisColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun indexAt(x: Float, width: Float, n: Int): Int {
    if (n <= 1 || width <= 0f) return 0
    return ((x / width) * (n - 1)).toInt().coerceIn(0, n - 1)
}

private fun fmtCents(c: Float?): String =
    c?.let { com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(it / 100.0) } ?: "—"

private fun fmtTime(ms: Long): String {
    val fmt = SimpleDateFormat("h:mm:ss a", Locale.US)
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(Date(ms))
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun BidChartPreview() {
    val now = 1_700_000_000_000L
    val pts = (0 until 30).map { i ->
        BidPoint(
            tMs = now + i * 30_000L,
            upBidCents = 40f + i * 0.4f,
            downBidCents = 55f - i * 0.3f,
            spotUsd = 84_300.0 + i * 0.8
        )
    }
    BidChart(
        pts,
        heightDp = 110,
        scrub = true,
        strikeLabel = "Strike $84,279",
        spotUsd = 84_323.0,
        strikeUsd = 84_278.84
    )
}
