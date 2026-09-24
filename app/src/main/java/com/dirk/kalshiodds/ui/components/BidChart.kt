package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.chart.ChartDownsampler
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentRed
import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.checklistLabelColor
import com.dirk.kalshiodds.ui.theme.checklistValueColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    strikeUsd: Double? = null
) {
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val axisColor = Contrast.readable(MaterialTheme.colorScheme.onSurfaceVariant, bg, minRatio = Contrast.AA_LARGE)
    val downsampled = remember(points, heightDp) {
        ChartDownsampler.downsample(points, if (scrub) ChartDownsampler.DETAIL_POINTS else ChartDownsampler.CARD_POINTS)
    }
    if (downsampled.size < 2) {
        Box(
            modifier.height(heightDp.dp).fillMaxWidth().background(bg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (downsampled.isEmpty()) "Chart warming up" else "Need 2 prints",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        return
    }
    var scrubIdx by remember(downsampled.size) { mutableStateOf<Int?>(null) }
    val pick = scrubIdx?.let { downsampled.getOrNull(it) } ?: downsampled.last()
    Column(modifier.fillMaxWidth()) {
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
            line({ it.upBidCents }, AccentGreen)
            line({ it.downBidCents }, AccentRed)
            if (strikeUsd != null && strikeUsd > 0.0) {
                val spots = downsampled.mapNotNull { it.spotUsd } + listOfNotNull(spotUsd)
                val lastSpot = spots.lastOrNull()
                val band = maxOf(
                    kotlin.math.abs((lastSpot ?: strikeUsd) - strikeUsd),
                    strikeUsd * 0.001,
                    1.0
                )
                val yStrike = size.height * 0.5f
                var x = 0f
                while (x < size.width) {
                    drawLine(axisColor.copy(alpha = 0.55f), Offset(x, yStrike), Offset(x + 8f, yStrike), strokeWidth = 2f)
                    x += 16f
                }
                if (lastSpot != null) {
                    val ySpot = (size.height * (0.5f - ((lastSpot - strikeUsd) / (2.0 * band)).toFloat())).coerceIn(0f, size.height)
                    drawCircle(AccentBlue, 4.dp.toPx(), Offset(size.width - 6.dp.toPx(), ySpot))
                }
            }
            if (scrub && scrubIdx != null) {
                val x = xOf(pick.tMs)
                drawLine(axisColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                pick.upBidCents?.let { drawCircle(AccentGreen, 4.dp.toPx(), Offset(x, yOf(it))) }
                pick.downBidCents?.let { drawCircle(AccentRed, 4.dp.toPx(), Offset(x, yOf(it))) }
            } else {
                pick.upBidCents?.let { drawCircle(AccentGreen, 3.dp.toPx(), Offset(xOf(pick.tMs), yOf(it))) }
                pick.downBidCents?.let { drawCircle(AccentRed, 3.dp.toPx(), Offset(xOf(pick.tMs), yOf(it))) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("UP bid ${fmtCents(pick.upBidCents)}", color = AccentGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(pick.tMs), color = labelColor, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text("DOWN bid ${fmtCents(pick.downBidCents)}", color = AccentRed, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        strikeLabel?.let {
            Text(it, color = labelColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun indexAt(x: Float, width: Float, n: Int): Int {
    if (n <= 1 || width <= 0f) return 0
    return ((x / width) * (n - 1)).toInt().coerceIn(0, n - 1)
}

private fun fmtCents(c: Float?): String =
    c?.let { String.format(Locale.US, "%.0f¢", it) } ?: "—"

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
            downBidCents = 55f - i * 0.3f
        )
    }
    BidChart(pts, heightDp = 110, scrub = true, strikeLabel = "Strike $111,200")
}
