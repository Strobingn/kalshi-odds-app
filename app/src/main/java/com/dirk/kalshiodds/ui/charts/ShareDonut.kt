package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs

data class DonutSlice(val label: String, val value: Double, val color: Color)

@Composable
fun ShareDonut(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    centerLabel: String? = null
) {
    val validSlices = slices.filter { it.value.isFinite() && it.value > 0 }
    val total = validSlices.sumOf { it.value }

    if (validSlices.isEmpty() || total <= 0) {
        ChartEmptyState(modifier, size)
        return
    }

    val gapDegrees = if (validSlices.size > 1) 2f else 0f

    val contentDesc = buildString {
        validSlices.forEachIndexed { i, slice ->
            if (i > 0) append(", ")
            append("${slice.label} ${chartPct(slice.value / total)}")
        }
    }

    Row(
        modifier = modifier.semantics { contentDescription = contentDesc },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val diameter = this.size.minDimension
                val strokeWidth = diameter * 0.18f
                val inset = strokeWidth / 2
                var startAngle = -90f
                validSlices.forEach { slice ->
                    val full = (360f * (slice.value / total)).toFloat()
                    val sweep = (full - minOf(gapDegrees, full / 2f)).coerceAtLeast(0.5f)
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(diameter - strokeWidth, diameter - strokeWidth)
                    )
                    startAngle += full
                }
            }
            val centerText = centerLabel ?: run {
                val largest = validSlices.maxByOrNull { it.value }!!
                chartPct(largest.value / total)
            }
            Text(
                text = centerText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = DipTheme.colors.textPrimary,
                textAlign = TextAlign.Center
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            validSlices.forEach { slice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(color = slice.color) }
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = DipTheme.colors.textPrimary
                    )
                    val pct = chartPct(slice.value / total)
                    val valueStr = if (abs(slice.value - slice.value.toInt()) < 0.001) {
                        slice.value.toInt().toString()
                    } else {
                        String.format(java.util.Locale.US, "%.2f", slice.value)
                    }
                    Text(
                        text = "$pct · $valueStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = DipTheme.colors.textSecondary
                    )
                }
            }
        }
    }
}
