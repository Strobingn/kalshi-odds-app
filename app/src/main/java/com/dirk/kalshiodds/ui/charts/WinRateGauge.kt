package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WinRateGauge(
    rate: Double?,
    settled: Int,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    breakEven: Double? = null
) {
    val c = DipTheme.colors

    if (rate == null || !rate.isFinite() || settled <= 0) {
        ChartEmptyState(modifier, size * 0.6f)
        return
    }

    val clampedRate = rate.coerceIn(0.0, 1.0)
    val arcColor = if (rate >= (breakEven ?: 0.5)) c.upButton else c.downButton
    val valueDegrees = 180f * clampedRate
    val canvasWidth = size
    val canvasHeight = size * 0.6f
    val strokeWidth = 10.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(canvasWidth, canvasHeight),
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
                    .semantics {
                        contentDescription = "Win rate ${chartPct(rate)} over $settled settled"
                    }
            ) {
                val stroke = strokeWidth.toPx()
                val radius = minOf(this.size.width / 2f, this.size.height) - stroke / 2f
                val centerX = this.size.width / 2f
                val centerY = radius + stroke / 2f

                // Track arc (full 180 degrees)
                drawArc(
                    color = c.surfaceAlt,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(centerX - radius, centerY - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                // Value arc
                drawArc(
                    color = arcColor,
                    startAngle = 180f,
                    sweepAngle = valueDegrees.toFloat(),
                    useCenter = false,
                    topLeft = Offset(centerX - radius, centerY - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                // Break-even tick
                breakEven?.let { be ->
                    val tickAngle = 180f + (180f * be.coerceIn(0.0, 1.0))
                    val rad = Math.toRadians(tickAngle.toDouble())
                    val innerRadius = radius - stroke / 2f - 2.dp.toPx()
                    val outerRadius = radius + stroke / 2f + 2.dp.toPx()
                    val innerX = centerX + innerRadius * cos(rad).toFloat()
                    val innerY = centerY + innerRadius * sin(rad).toFloat()
                    val outerX = centerX + outerRadius * cos(rad).toFloat()
                    val outerY = centerY + outerRadius * sin(rad).toFloat()
                    drawLine(
                        color = c.textSecondary,
                        start = Offset(innerX, innerY),
                        end = Offset(outerX, outerY),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = chartPct(rate),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = c.textPrimary
                )
                Text(
                    text = "$settled settled",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary
                )
            }
        }
    }
}
