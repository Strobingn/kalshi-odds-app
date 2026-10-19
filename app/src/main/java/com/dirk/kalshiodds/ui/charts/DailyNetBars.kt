package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DayNet(val label: String, val net: Double)

@Composable
fun DailyNetBars(
    days: List<DayNet>,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp
) {
    val finiteDays = days.filter { it.net.isFinite() }
    if (finiteDays.isEmpty()) {
        ChartEmptyState(modifier, height)
        return
    }

    val sum = finiteDays.sumOf { it.net }
    val contentDesc = "Daily net for ${finiteDays.size} days, total ${chartMoney(sum)}"

    val c = DipTheme.colors
    val minNet = finiteDays.minOf { it.net }
    val maxNet = finiteDays.maxOf { it.net }
    val dataMin = min(0.0, minNet)
    val dataMax = max(0.0, maxNet)
    val rangeSpan = max(dataMax - dataMin, 0.01)


    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = contentDesc }
        ) {
            val canvasWidth = size.width
            val canvasHeightPx = size.height
            val zeroY = canvasHeightPx * (dataMax / rangeSpan).toFloat()
            val n = finiteDays.size
            if (n == 0) return@Canvas

            val slotWidth = canvasWidth / n
            val barWidthPx = min(slotWidth * 0.6f, 28.dp.toPx())

            finiteDays.forEachIndexed { i, day ->
                val slotLeft = i * slotWidth
                val barLeft = slotLeft + (slotWidth - barWidthPx) / 2
                val barRight = barLeft + barWidthPx

                val h = day.net
                if (abs(h) < 0.005) {
                    val lineY = zeroY
                    drawLine(
                        color = c.textSecondary,
                        start = Offset(barLeft, lineY),
                        end = Offset(barRight, lineY),
                        strokeWidth = 2f
                    )
                } else {
                    val barHeightPx = canvasHeightPx * (abs(h) / rangeSpan).toFloat()
                    val barTop: Float
                    val barBottom: Float
                    if (h > 0) {
                        barBottom = zeroY
                        barTop = zeroY - barHeightPx
                    } else {
                        barTop = zeroY
                        barBottom = zeroY + barHeightPx
                    }
                    val color = if (h > 0) c.upButton else c.downButton
                    val cornerRadius = CornerRadius(3.dp.toPx())
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(barLeft, barTop),
                        size = androidx.compose.ui.geometry.Size(barWidthPx, barBottom - barTop),
                        cornerRadius = cornerRadius
                    )
                }
            }

            drawLine(
                color = c.border,
                start = Offset(0f, zeroY),
                end = Offset(canvasWidth, zeroY),
                strokeWidth = 1f
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = finiteDays.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = c.textSecondary
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = "total ${chartMoney(sum)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = chartSignTextColor(sum)
                )
            }
            Text(
                text = finiteDays.last().label,
                style = MaterialTheme.typography.labelSmall,
                color = c.textSecondary
            )
        }
    }
}
